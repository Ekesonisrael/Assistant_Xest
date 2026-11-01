import "dotenv/config";
import express from "express";
import type { Request, Response } from "express";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { GoogleGenAI, Type } from "@google/genai";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const ALLOWED_MODELS = new Set([
  "gemini-3.8-flash",
  "gemini-3.1-flash-lite",
  "gemini-flash-latest",
]);

function resolveModelName(requested?: string): string {
  if (requested && ALLOWED_MODELS.has(requested.trim())) {
    return requested.trim();
  }
  return "gemini-3.8-flash";
}

function getAiClient(): GoogleGenAI {
  return new GoogleGenAI({
    apiKey: process.env.GEMINI_API_KEY,
    httpOptions: {
      headers: {
        "User-Agent": "aistudio-build",
      },
    },
  });
}

function cleanSpeechText(input: string): string {
  return input
    .replace(/—/g, "-")
    .replace(/–/g, "-")
    .replace(/"/g, "")
    .replace(/[\p{Extended_Pictographic}]/gu, "")
    .trim();
}

function formatGeminiError(err: unknown): { status: number; message: string } {
  const raw = err instanceof Error ? err.message : String(err);
  if (raw.includes("403") || raw.includes("PERMISSION_DENIED") || raw.includes("API_KEY_INVALID")) {
    return {
      status: 403,
      message: "API key unavailable or invalid. Check your key in the Settings > Secrets panel.",
    };
  }
  if (raw.includes("429") || raw.includes("RESOURCE_EXHAUSTED")) {
    return {
      status: 429,
      message: "Gemini rate limit reached. Select a billing-enabled API key in Settings > Secrets for higher quota.",
    };
  }
  return {
    status: 500,
    message: raw || "Gemini request failed.",
  };
}

async function startServer() {
  const app = express();
  app.use(express.json({ limit: "2mb" }));

  app.get("/api/health", (_req: Request, res: Response) => {
    res.json({
      ok: true,
      hasGeminiKey: Boolean(process.env.GEMINI_API_KEY && process.env.GEMINI_API_KEY.trim()),
    });
  });

  app.post("/api/ai/test", async (req: Request, res: Response) => {
    if (!process.env.GEMINI_API_KEY || !process.env.GEMINI_API_KEY.trim()) {
      res.status(400).json({
        ok: false,
        message: "GEMINI_API_KEY is not configured. Check your attached key in the Settings > Secrets panel.",
      });
      return;
    }

    const model = resolveModelName(req.body?.modelName);
    try {
      const ai = getAiClient();
      const response = await ai.models.generateContent({
        model,
        contents: "Reply with the single word Connected.",
      });
      const text = (response.text ?? "").trim();
      if (text) {
        res.json({
          ok: true,
          message: `AI connected successfully (${model}).`,
        });
      } else {
        res.status(502).json({
          ok: false,
          message: "Empty response from Gemini.",
        });
      }
    } catch (err) {
      const formatted = formatGeminiError(err);
      res.status(formatted.status).json({
        ok: false,
        message: formatted.message,
      });
    }
  });

  app.post("/api/ai/assistant", async (req: Request, res: Response) => {
    if (!process.env.GEMINI_API_KEY || !process.env.GEMINI_API_KEY.trim()) {
      res.status(400).json({
        error: "GEMINI_API_KEY is not set in Settings > Secrets.",
      });
      return;
    }

    const { modelName, context, sessionHistory, userInput } = req.body ?? {};
    if (!userInput || typeof userInput !== "string") {
      res.status(400).json({ error: "userInput is required." });
      return;
    }

    const model = resolveModelName(modelName);
    const historyLines = Array.isArray(sessionHistory)
      ? sessionHistory
          .slice(-6)
          .map((pair: { sender?: string; text?: string }) => `${pair.sender ?? "User"}: ${pair.text ?? ""}`)
          .join("\n")
      : "";

    const prompt = [
      "Context & Schedule:",
      JSON.stringify(context ?? {}, null, 2),
      historyLines ? `\nRecent Conversation:\n${historyLines}` : "",
      `\nUser: ${userInput}`,
    ].join("\n");

    try {
      const ai = getAiClient();
      const response = await ai.models.generateContent({
        model,
        contents: prompt,
        config: {
          systemInstruction:
            "You are a proactive, direct, warm personal assistant and studio execution coach. " +
            "Keep your reply under 40 words, plain spoken text only, no emojis, no em dashes. " +
            "Supported action names: create_item, update_item, complete_item, snooze, add_note, propose_plan, remember_fact. " +
            "If the user wants to add a task, goal, reminder, or habit, emit a create_item action. " +
            "If the user says remember that ..., emit remember_fact. " +
            "If the user takes a note, emit add_note. " +
            "If the user marks something done, emit complete_item. " +
            "If no action is needed, return an empty actions array.",
          responseMimeType: "application/json",
          responseSchema: {
            type: Type.OBJECT,
            properties: {
              reply: {
                type: Type.STRING,
                description: "Short direct spoken reply under 40 words, no emojis, no em dashes.",
              },
              actions: {
                type: Type.ARRAY,
                items: {
                  type: Type.OBJECT,
                  properties: {
                    name: {
                      type: Type.STRING,
                      description:
                        "One of: create_item, update_item, complete_item, snooze, add_note, propose_plan, remember_fact",
                    },
                    item_id: { type: Type.STRING },
                    type: {
                      type: Type.STRING,
                      description: "TASK, GOAL, NOTE, REMINDER, or HABIT",
                    },
                    title: { type: Type.STRING },
                    details: { type: Type.STRING },
                    time: { type: Type.STRING, description: "HH:mm 24-hour format if specified" },
                    contact_name: { type: Type.STRING },
                    target_amount: { type: Type.NUMBER },
                    unit: { type: Type.STRING },
                    minutes: { type: Type.INTEGER },
                    status: { type: Type.STRING },
                  },
                  required: ["name"],
                },
              },
            },
            required: ["reply", "actions"],
          },
        },
      });

      const rawText = (response.text ?? "{}").trim();
      const parsed = JSON.parse(rawText);
      const reply = cleanSpeechText(parsed.reply || "Understood.");
      const rawActions = Array.isArray(parsed.actions) ? parsed.actions : [];
      const actions = rawActions.map((a: Record<string, unknown>) => ({
        name: String(a.name ?? ""),
        itemId: a.item_id ? String(a.item_id) : undefined,
        type: a.type ? String(a.type) : undefined,
        title: a.title ? String(a.title) : undefined,
        details: a.details ? String(a.details) : undefined,
        time: a.time ? String(a.time) : undefined,
        contactName: a.contact_name ? String(a.contact_name) : undefined,
        targetAmount: typeof a.target_amount === "number" ? a.target_amount : undefined,
        unit: a.unit ? String(a.unit) : undefined,
        minutes: typeof a.minutes === "number" ? a.minutes : undefined,
        status: a.status ? String(a.status) : undefined,
      }));

      res.json({ reply, actions });
    } catch (err) {
      const formatted = formatGeminiError(err);
      res.status(formatted.status).json({ error: formatted.message });
    }
  });

  app.post("/api/ai/coach", async (req: Request, res: Response) => {
    if (!process.env.GEMINI_API_KEY || !process.env.GEMINI_API_KEY.trim()) {
      res.status(400).json({ error: "GEMINI_API_KEY is not set." });
      return;
    }

    const { modelName, context, reminderType } = req.body ?? {};
    const model = resolveModelName(modelName);
    const prompt = [
      `Reminder occasion: ${reminderType || "Scheduled Briefing"}.`,
      "Schedule & Context:",
      JSON.stringify(context ?? {}, null, 2),
      "\nUnder 40 words, name the one thing I should do next. If something is unfinished, say so plainly. Plain spoken text only. No quotes, no emojis, no em dashes.",
    ].join("\n");

    try {
      const ai = getAiClient();
      const response = await ai.models.generateContent({
        model,
        contents: prompt,
        config: {
          systemInstruction:
            "You are the personal assistant and coach for an executive and studio founder. Keep spoken briefings under 40 words, plain text, no emojis, no em dashes.",
        },
      });
      const speech = cleanSpeechText(response.text ?? "");
      res.json({ speech });
    } catch (err) {
      const formatted = formatGeminiError(err);
      res.status(formatted.status).json({ error: formatted.message });
    }
  });

  const distPath = path.resolve(__dirname, "dist");
  const isProd = process.env.NODE_ENV === "production" && fs.existsSync(distPath);

  if (isProd) {
    app.use(express.static(distPath));
    app.get("*", (_req: Request, res: Response) => {
      res.sendFile(path.join(distPath, "index.html"));
    });
  } else {
    const { createServer: createViteServer } = await import("vite");
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  }

  const port = Number(process.env.PORT) || 3000;
  app.listen(port, "0.0.0.0", () => {
    console.log(`Server listening on http://0.0.0.0:${port}`);
  });
}

startServer();
