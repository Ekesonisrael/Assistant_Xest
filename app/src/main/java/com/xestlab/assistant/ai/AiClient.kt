package com.xestlab.assistant.ai

import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.UserPreferencesState
import com.xestlab.assistant.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AssistantAction(
    val actionType: String, // "ADD_TASK", "SET_REMINDER", "SAVE_FACT", "MARK_DONE"
    val title: String,
    val details: String = "",
    val dueAtMillis: Long? = null
)

data class AssistantResponse(
    val replyText: String,
    val actions: List<AssistantAction> = emptyList()
)

object AiClient {

    suspend fun buildLocalContext(db: AppDatabase, prefs: UserPreferencesState): String {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val checklist = db.checklistDao().getAllItemsDirect()
        val logs = db.dailyLogDao().getLogsForDateDirect(today).associateBy { it.itemId }
        val pending = db.itemDao().getPendingItemsDirect().take(12)
        val facts = db.factDao().getAllFactsDirect().take(15)
        val goal = db.goalDao().getGoalsDirect()

        val sb = StringBuilder()
        sb.appendLine("User Name: ${prefs.userName.ifBlank { "User" }}")
        sb.appendLine("Today Date: $today")
        sb.appendLine("Wake-Up Time: ${prefs.wakeUpTime}, Quiet Hours: ${prefs.quietHoursStart}-${prefs.quietHoursEnd}")
        if (goal != null) {
            sb.appendLine("Yearly Goal Target: ${goal.yearlyGoal} (Start ${goal.startDate})")
        }
        sb.appendLine("Daily Core Checklist:")
        checklist.forEach { item ->
            val status = if (logs[item.id]?.isDone == true) "DONE" else "REMAINING"
            sb.appendLine("- [${status}] ${item.title}")
        }
        sb.appendLine("Pending Tasks & Reminders:")
        if (pending.isEmpty()) {
            sb.appendLine("- None")
        } else {
            pending.forEach { t ->
                val dueStr = t.dueAt?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it)) } ?: "Unscheduled"
                sb.appendLine("- (${t.type}, ${t.priority}, due $dueStr) ${t.title}")
            }
        }
        if (facts.isNotEmpty()) {
            sb.appendLine("Remembered Facts:")
            facts.forEach { f -> sb.appendLine("- ${f.text}") }
        }
        return sb.toString()
    }

    suspend fun generateAssistantResponse(
        userPrompt: String,
        db: AppDatabase,
        prefs: UserPreferencesState,
        securePrefs: SecurePreferences
    ): AssistantResponse? = withContext(Dispatchers.IO) {
        if (!securePrefs.isAiEnabled || !securePrefs.hasGeminiKey()) {
            return@withContext null
        }

        val contextBlock = buildLocalContext(db, prefs)
        val systemInstruction = """
            You are a concise, direct personal execution assistant for ${prefs.userName.ifBlank { "the user" }}.
            Keep spoken responses under 3 sentences, grounded strictly in the user's schedule and goals below.
            Do not use markdown bullet symbols or emojis because your reply will be read aloud by Android TextToSpeech.

            Current Local Context:
            $contextBlock
        """.trimIndent()

        val reply = callGeminiText(
            apiKey = securePrefs.geminiApiKey,
            model = securePrefs.geminiModel,
            systemPrompt = systemInstruction,
            userMessage = userPrompt
        ) ?: return@withContext null

        AssistantResponse(replyText = reply)
    }

    suspend fun generateCoachReminder(
        briefingTitle: String,
        db: AppDatabase,
        prefs: UserPreferencesState,
        securePrefs: SecurePreferences
    ): String? = withContext(Dispatchers.IO) {
        if (!securePrefs.isAiEnabled || !securePrefs.hasGeminiKey()) {
            return@withContext null
        }
        val contextBlock = buildLocalContext(db, prefs)
        val prompt = "Deliver a crisp 2-sentence spoken $briefingTitle for ${prefs.userName.ifBlank { "the user" }} based on remaining checklist and next task. Plain text only."
        callGeminiText(
            apiKey = securePrefs.geminiApiKey,
            model = securePrefs.geminiModel,
            systemPrompt = contextBlock,
            userMessage = prompt
        )
    }

    suspend fun testConnection(apiKey: String, model: String): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API key is empty."))
        }
        val reply = callGeminiText(
            apiKey = apiKey.trim(),
            model = model.trim().ifBlank { "gemini-2.5-flash" },
            systemPrompt = "Reply with a single short sentence confirming connection.",
            userMessage = "Ping"
        )
        if (reply != null) {
            Result.success(reply)
        } else {
            Result.failure(IllegalStateException("Unable to reach Gemini endpoint or invalid API key."))
        }
    }

    private fun callGeminiText(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userMessage: String
    ): String? {
        var connection: HttpURLConnection? = null
        return try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val url = URL(endpoint)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            val payload = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemPrompt))
                    )
                )
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
                    )
                )
                put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.4)
                        .put("maxOutputTokens", 220)
                )
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                return null
            }

            val responseBody = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }

            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            if (parts.length() == 0) return null
            parts.getJSONObject(0).optString("text", "").trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
