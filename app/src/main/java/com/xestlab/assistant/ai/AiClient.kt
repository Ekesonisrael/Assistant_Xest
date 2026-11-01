package com.xestlab.assistant.ai

import com.xestlab.assistant.data.LlmProvider
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.UserPreferencesState
import com.xestlab.assistant.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AssistantResponse(
    val replyText: String,
    val providerUsed: LlmProvider
)

data class EmailAnalysisResult(
    val isActionable: Boolean,
    val category: String, // "TASK", "INVOICE", "GENERAL"
    val extractedTaskTitle: String?
)

object AiClient {

    const val SYSTEM_COACH_PROMPT =
        "You are Xestlab Assistant, a strict but supportive business coach for King Israel. Keep replies conversational, short, and punchy."

    const val AI_BRAIN_UNREACHABLE_MESSAGE =
        "I cannot reach the AI brain right now. Check your internet or API key."

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun buildLocalContext(db: AppDatabase, prefs: UserPreferencesState): String {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val checklist = db.checklistDao().getAllItemsDirect()
        val logs = db.dailyLogDao().getLogsForDateDirect(today).associateBy { it.itemId }
        val pending = db.itemDao().getPendingItemsDirect()
            .filter { !it.sourceText.startsWith("web_pipeline_") }
            .take(10)
        val facts = db.factDao().getAllFactsDirect().take(12)
        val unreadEmails = db.emailDao().getRecentUnreadDirect(5)
        val goal = db.goalDao().getGoalsDirect()

        return buildString {
            appendLine("User Name: ${prefs.userName.ifBlank { "King Israel" }}")
            appendLine("Date: $today")
            if (goal != null) {
                appendLine("Annual Revenue Target: NGN ${String.format(Locale.US, "%,d", goal.yearlyGoal)}")
            }
            appendLine("Core Daily Checklist:")
            checklist.forEach { item ->
                val state = if (logs[item.id]?.isDone == true) "DONE" else "REMAINING"
                appendLine("- [$state] ${item.title}")
            }
            appendLine("Pending Tasks & Reminders:")
            if (pending.isEmpty()) {
                appendLine("- None")
            } else {
                pending.forEach { item ->
                    val dueStr = item.dueAt?.let {
                        SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(it))
                    } ?: "Unscheduled"
                    appendLine("- (${item.type}, ${item.priority}, $dueStr) ${item.title}")
                }
            }
            if (unreadEmails.isNotEmpty()) {
                appendLine("Recent Unread Emails:")
                unreadEmails.forEach { email ->
                    appendLine("- From ${email.sender}: ${email.subject}")
                }
            }
            if (facts.isNotEmpty()) {
                appendLine("Remembered Facts:")
                facts.forEach { fact -> appendLine("- ${fact.text}") }
            }
        }
    }

    /**
     * Routes user voice/chat input to either Gemini or Groq depending on SecurePreferences.llmProvider.
     * Wrapped in strict try/catch with OkHttp timeouts so it never crashes.
     */
    suspend fun generateAssistantResponse(
        userPrompt: String,
        db: AppDatabase,
        prefs: UserPreferencesState,
        securePrefs: SecurePreferences
    ): Result<AssistantResponse> = withContext(Dispatchers.IO) {
        try {
            val provider = securePrefs.llmProvider
            val localContext = buildLocalContext(db, prefs)
            val fullSystemInstruction = buildString {
                appendLine(SYSTEM_COACH_PROMPT)
                appendLine("Do not use markdown asterisks, bullet symbols, or emojis because your response is spoken aloud.")
                appendLine()
                appendLine("Live Workspace Context:")
                append(localContext)
            }

            when (provider) {
                LlmProvider.GEMINI -> {
                    val key = securePrefs.geminiApiKey
                    if (key.isBlank()) {
                        return@withContext Result.failure(IllegalStateException("Missing Gemini API key"))
                    }
                    val textResult = callGeminiWithFallback(
                        apiKey = key,
                        model = securePrefs.geminiModel,
                        systemPrompt = fullSystemInstruction,
                        userMessage = userPrompt
                    )
                    textResult.map { AssistantResponse(replyText = sanitizeForSpeech(it), providerUsed = LlmProvider.GEMINI) }
                }

                LlmProvider.GROQ -> {
                    val key = securePrefs.groqApiKey
                    if (key.isBlank()) {
                        return@withContext Result.failure(IllegalStateException("Missing Groq API key"))
                    }
                    val textResult = callGroqOpenAiCompat(
                        apiKey = key,
                        model = securePrefs.groqModel,
                        systemPrompt = fullSystemInstruction,
                        userMessage = userPrompt
                    )
                    textResult.map { AssistantResponse(replyText = sanitizeForSpeech(it), providerUsed = LlmProvider.GROQ) }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateCoachBriefing(
        briefingTitle: String,
        db: AppDatabase,
        prefs: UserPreferencesState,
        securePrefs: SecurePreferences
    ): String? = withContext(Dispatchers.IO) {
        if (!securePrefs.isAiEnabled || !securePrefs.hasActiveLlmKey()) {
            return@withContext null
        }
        val displayName = prefs.userName.ifBlank { "King Israel" }
        val prompt = "Deliver a natural 2-sentence spoken $briefingTitle starting with 'Good morning $displayName, how are you doing? Let's start with...' grounded in the remaining checklist and top task."
        generateAssistantResponse(prompt, db, prefs, securePrefs).getOrNull()?.replyText
    }

    suspend fun analyzeEmailForTaskOrInvoice(
        sender: String,
        subject: String,
        snippet: String,
        securePrefs: SecurePreferences
    ): EmailAnalysisResult = withContext(Dispatchers.IO) {
        // If AI brain is configured and enabled, ask the active LLM to classify and extract clean task title
        if (securePrefs.isAiEnabled && securePrefs.hasActiveLlmKey()) {
            val aiAttempt = tryExtractEmailViaLlm(sender, subject, snippet, securePrefs)
            if (aiAttempt != null) {
                return@withContext aiAttempt
            }
        }
        // Deterministic local fallback so Smart Email Checker works offline or without AI keys
        heuristicEmailAnalysis(sender, subject, snippet)
    }

    private fun tryExtractEmailViaLlm(
        sender: String,
        subject: String,
        snippet: String,
        securePrefs: SecurePreferences
    ): EmailAnalysisResult? {
        return try {
            val system = "Extract actionable tasks or invoices from emails. Reply ONLY with compact JSON: {\"actionable\":true|false,\"category\":\"TASK\"|\"INVOICE\"|\"GENERAL\",\"taskTitle\":\"short actionable task title\"}."
            val user = "From: $sender\nSubject: $subject\nBody: ${snippet.take(400)}"

            val rawReply = when (securePrefs.llmProvider) {
                LlmProvider.GEMINI -> callGeminiWithFallback(
                    apiKey = securePrefs.geminiApiKey,
                    model = securePrefs.geminiModel,
                    systemPrompt = system,
                    userMessage = user
                ).getOrNull()

                LlmProvider.GROQ -> callGroqOpenAiCompat(
                    apiKey = securePrefs.groqApiKey,
                    model = securePrefs.groqModel,
                    systemPrompt = system,
                    userMessage = user
                ).getOrNull()
            } ?: return null

            val jsonStart = rawReply.indexOf('{')
            val jsonEnd = rawReply.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd <= jsonStart) return null
            val parsed = JSONObject(rawReply.substring(jsonStart, jsonEnd + 1))
            val actionable = parsed.optBoolean("actionable", false)
            val category = parsed.optString("category", "GENERAL").uppercase(Locale.US)
            val title = parsed.optString("taskTitle", "").trim().ifBlank { null }
            EmailAnalysisResult(
                isActionable = actionable || category == "INVOICE" || category == "TASK",
                category = if (category in setOf("TASK", "INVOICE")) category else "GENERAL",
                extractedTaskTitle = title
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun heuristicEmailAnalysis(
        sender: String,
        subject: String,
        snippet: String
    ): EmailAnalysisResult {
        val combined = "$subject $snippet".lowercase(Locale.getDefault())
        val invoiceKeywords = listOf(
            "invoice", "receipt", "payment due", "bill", "billing", "overdue", "remittance", "quote", "deposit"
        )
        val taskKeywords = listOf(
            "action required", "urgent", "deadline", "please review", "sign", "contract",
            "deliverable", "feedback", "follow up", "approval", "proposal", "meeting", "schedule"
        )

        val isInvoice = invoiceKeywords.any { combined.contains(it) }
        val isTask = taskKeywords.any { combined.contains(it) }

        return when {
            isInvoice -> EmailAnalysisResult(
                isActionable = true,
                category = "INVOICE",
                extractedTaskTitle = "Review invoice: ${subject.take(65)} ($sender)"
            )
            isTask -> EmailAnalysisResult(
                isActionable = true,
                category = "TASK",
                extractedTaskTitle = "${subject.take(65)} (from $sender)"
            )
            else -> EmailAnalysisResult(
                isActionable = false,
                category = "GENERAL",
                extractedTaskTitle = null
            )
        }
    }

    suspend fun testGeminiConnection(apiKey: String, model: String): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API key is empty."))
        }
        callGeminiWithFallback(
            apiKey = apiKey.trim(),
            model = model.trim().ifBlank { "gemini-2.5-flash" },
            systemPrompt = SYSTEM_COACH_PROMPT,
            userMessage = "Say 'Gemini brain online for King Israel' in one short sentence."
        )
    }

    suspend fun testGroqConnection(apiKey: String, model: String): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Groq API key is empty."))
        }
        callGroqOpenAiCompat(
            apiKey = apiKey.trim(),
            model = model.trim().ifBlank { "llama3-70b-8192" },
            systemPrompt = SYSTEM_COACH_PROMPT,
            userMessage = "Say 'Groq brain online for King Israel' in one short sentence."
        )
    }

    private fun callGeminiWithFallback(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userMessage: String
    ): Result<String> {
        val primaryModel = model.trim().ifBlank { "gemini-2.5-flash" }
        val firstTry = executeGeminiPost(apiKey, primaryModel, systemPrompt, userMessage)
        if (firstTry.isSuccess || primaryModel == "gemini-2.5-flash") {
            return firstTry
        }
        return executeGeminiPost(apiKey, "gemini-2.5-flash", systemPrompt, userMessage)
    }

    private fun executeGeminiPost(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userMessage: String
    ): Result<String> {
        return try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${apiKey.trim()}"
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
                        .put("temperature", 0.5)
                        .put("maxOutputTokens", 280)
                )
            }

            val request = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException("Gemini HTTP ${response.code}: ${bodyStr.take(180)}"))
                }
                val json = JSONObject(bodyStr)
                val candidates = json.optJSONArray("candidates")
                    ?: return Result.failure(IllegalStateException("Empty Gemini candidates"))
                if (candidates.length() == 0) {
                    return Result.failure(IllegalStateException("Zero Gemini candidates"))
                }
                val parts = candidates.getJSONObject(0)
                    .optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?: return Result.failure(IllegalStateException("Missing Gemini content parts"))
                val text = parts.optJSONObject(0)?.optString("text", "")?.trim().orEmpty()
                if (text.isEmpty()) {
                    Result.failure(IllegalStateException("Blank Gemini response"))
                } else {
                    Result.success(text)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Groq OpenAI-compatible POST to https://api.groq.com/openai/v1/chat/completions
     * with Authorization: Bearer <Groq_API_Key> and model llama3-70b-8192.
     */
    private fun callGroqOpenAiCompat(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userMessage: String
    ): Result<String> {
        val requestedModel = model.trim().ifBlank { "llama3-70b-8192" }
        val firstAttempt = executeGroqPost(apiKey, requestedModel, systemPrompt, userMessage)
        if (firstAttempt.isSuccess) {
            return firstAttempt
        }
        // If Groq returns model_decommissioned for legacy model alias, retry seamlessly with llama-3.3-70b-versatile
        val errMsg = firstAttempt.exceptionOrNull()?.message.orEmpty()
        if (requestedModel == "llama3-70b-8192" && errMsg.contains("decommission", ignoreCase = true)) {
            return executeGroqPost(apiKey, "llama-3.3-70b-versatile", systemPrompt, userMessage)
        }
        return firstAttempt
    }

    private fun executeGroqPost(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userMessage: String
    ): Result<String> {
        return try {
            val payload = JSONObject().apply {
                put("model", model)
                put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userMessage))
                )
                put("temperature", 0.5)
                put("max_tokens", 280)
            }

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException("Groq HTTP ${response.code}: ${bodyStr.take(180)}"))
                }
                val json = JSONObject(bodyStr)
                val choices = json.optJSONArray("choices")
                    ?: return Result.failure(IllegalStateException("Missing choices in Groq response"))
                if (choices.length() == 0) {
                    return Result.failure(IllegalStateException("Empty choices in Groq response"))
                }
                val messageObj = choices.getJSONObject(0).optJSONObject("message")
                    ?: return Result.failure(IllegalStateException("Missing message in Groq choice"))
                val content = messageObj.optString("content", "").trim()
                if (content.isEmpty()) {
                    Result.failure(IllegalStateException("Blank Groq message content"))
                } else {
                    Result.success(content)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sanitizeForSpeech(raw: String): String {
        return raw
            .replace("**", "")
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
