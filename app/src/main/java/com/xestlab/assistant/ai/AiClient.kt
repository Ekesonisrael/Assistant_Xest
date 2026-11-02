package com.xestlab.assistant.ai

import android.util.Log
import com.xestlab.assistant.data.LlmProvider
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.UserPreferencesState
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.Lead
import com.xestlab.assistant.data.model.tagsList
import com.xestlab.assistant.service.EmailClassificationDecision
import com.xestlab.assistant.service.LeadCadenceEngine
import com.xestlab.assistant.service.TtsGreetingHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AssistantResponse(
    val replyText: String,
    val providerUsed: LlmProvider,
    val modelUsed: String
)

data class ApiDiagnosticResult(
    val provider: LlmProvider,
    val modelUsed: String,
    val statusCode: Int,
    val replyText: String
)

class ApiHttpException(
    val provider: String,
    val statusCode: Int,
    val statusMessage: String,
    val errorDetail: String
) : Exception("HTTP $statusCode ($provider ${statusMessage.ifBlank { "Error" }}): $errorDetail")

data class EmailAnalysisResult(
    val isActionable: Boolean,
    val category: String, // "TASK", "INVOICE", "GENERAL"
    val extractedTaskTitle: String?
)

data class AiEmailBatchInput(
    val id: String,
    val sender: String,
    val subject: String,
    val snippet: String
)

object AiClient {

    private const val TAG = "AiClient"

    const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    const val GROQ_MODEL = "openai/gpt-oss-120b"

    // Kept for future re-enabling once Gemini cloud permissions are resolved:
    // const val GEMINI_MODEL = "gemini-1.5-flash-latest"

    const val SYSTEM_COACH_PROMPT =
        "You are Xestlab Assistant, a sharp business coach for King Israel. You have been provided with a summary of his unread emails. Read the summary carefully. If he asks 'do I have any meetings tomorrow?', scan the email subjects for keywords like 'meeting', 'invite', or specific dates. Answer his question directly. If you don't see a meeting, explicitly say 'I checked your unread emails and I don't see any meetings for tomorrow.' Never dump the whole email list unless he asks for a summary."

    const val AI_BRAIN_UNREACHABLE_MESSAGE =
        "I cannot reach the AI brain right now. Check your internet or API key."

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun formatThrowableDiagnostic(error: Throwable): String {
        return when (error) {
            is ApiHttpException -> {
                "HTTP ${error.statusCode} [${error.provider}]: ${error.errorDetail}"
            }
            else -> {
                val className = error.javaClass.simpleName.ifBlank { error.javaClass.name }
                val msg = error.message?.takeIf { it.isNotBlank() } ?: error.toString()
                "$className: $msg"
            }
        }
    }

    suspend fun buildLocalContext(db: AppDatabase, prefs: UserPreferencesState): String {
        val nowCal = Calendar.getInstance()
        val tomorrowCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val dayFmt = SimpleDateFormat("yyyy-MM-dd (EEEE)", Locale.getDefault())
        val shortDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val emailTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val today = shortDateFmt.format(nowCal.time)
        val todayVerbose = dayFmt.format(nowCal.time)
        val tomorrowVerbose = dayFmt.format(tomorrowCal.time)

        val checklist = db.checklistDao().getAllItemsDirect()
        val logs = db.dailyLogDao().getLogsForDateDirect(today).associateBy { it.itemId }
        val pending = db.itemDao().getPendingItemsDirect()
            .filter { !it.sourceText.startsWith("web_pipeline_") }
            .take(10)
        val facts = db.factDao().getAllFactsDirect().take(12)
        val unreadEmails = db.emailDao().getActiveUnreadDirect(50)
            .sortedWith(
                compareBy<com.xestlab.assistant.data.model.UnreadEmailEntity> {
                    when (it.priority) {
                        "HIGH" -> 0
                        "NORMAL" -> 1
                        else -> 2
                    }
                }.thenByDescending { it.receivedAt }
            )
        val goal = db.goalDao().getGoalsDirect() ?: com.xestlab.assistant.data.model.Goal()
        val allLeads = db.leadDao().getAllLeadsDirect()
        val allPendingRaw = db.itemDao().getPendingItemsDirect()
        val dueFollowUps = LeadCadenceEngine.computeDueFollowUpsToday(allLeads, allPendingRaw)
        val payments = db.paymentDao().getAllPaymentsDirect()
        val gauge = LeadCadenceEngine.computeRevenueGauge(goal, payments)

        return buildString {
            appendLine("User Name: ${prefs.userName.ifBlank { "King Israel" }}")
            appendLine("Today's Date: $todayVerbose")
            appendLine("Tomorrow's Date: $tomorrowVerbose")
            appendLine("Annual Revenue Gauge: ${gauge.homeGaugeLine}")
            if (dueFollowUps.isNotEmpty()) {
                appendLine("Lead Follow-Ups Due Today (${dueFollowUps.size}):")
                dueFollowUps.take(10).forEach { due -> appendLine("- ${due.displayLine}") }
            }
            val highCount = unreadEmails.count { it.priority == "HIGH" }
            val normalCount = unreadEmails.count { it.priority == "NORMAL" }
            val lowCount = unreadEmails.count { it.priority == "LOW" }
            appendLine("Unread Emails Summary (${unreadEmails.size} unread: $highCount High, $normalCount Normal, $lowCount Low):")
            if (unreadEmails.isEmpty()) {
                appendLine("- None (0 unread emails currently in inbox).")
            } else {
                unreadEmails.take(30).forEach { email ->
                    val receivedStr = emailTimeFmt.format(Date(email.receivedAt))
                    val snippetClean = email.snippet.replace(Regex("\\s+"), " ").trim().take(140)
                    val tagsNote = if (email.tagsList.isNotEmpty()) {
                        " | Tags: ${email.tagsList.joinToString(",")}"
                    } else {
                        ""
                    }
                    val eventNote = email.eventTimeMillis?.let {
                        " | EventTime: ${emailTimeFmt.format(Date(it))}"
                    }.orEmpty()
                    appendLine(
                        "- [${email.priority}] [$receivedStr] From: ${email.sender} | Subject: ${email.subject}$tagsNote$eventNote" +
                            if (snippetClean.isNotEmpty()) " | Preview: $snippetClean" else ""
                    )
                }
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
                        SimpleDateFormat("EEE yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
                    } ?: "Unscheduled"
                    appendLine("- (${item.type}, ${item.priority}, $dueStr) ${item.title}")
                }
            }
            if (facts.isNotEmpty()) {
                appendLine("Remembered Facts:")
                facts.forEach { fact -> appendLine("- ${fact.text}") }
            }
        }
    }

    /**
     * Primary AI Engine: Routes all chat and microphone prompts to Groq ("openai/gpt-oss-120b"),
     * reading the API key strictly from EncryptedSharedPreferences.
     */
    suspend fun generateAssistantResponse(
        userPrompt: String,
        db: AppDatabase,
        prefs: UserPreferencesState,
        securePrefs: SecurePreferences
    ): Result<AssistantResponse> = withContext(Dispatchers.IO) {
        try {
            val localContext = buildLocalContext(db, prefs)
            val customWakeName = securePrefs.wakeWordName
            val recentTurns = db.conversationDao()
                .getLastTurnsDirect(6)
                .reversed()
                .filter { it.text.isNotBlank() && it.text != userPrompt }

            val systemInstruction = buildString {
                appendLine(SYSTEM_COACH_PROMPT)
                if (customWakeName.isNotBlank()) {
                    appendLine("Your wake word name is \"$customWakeName\". Respond naturally if addressed as $customWakeName.")
                }
                appendLine("If asked for a spoken summary of unread emails, read High priority emails first, then state only the count of the remaining Normal and Low emails.")
                appendLine("Vary your sentence openings, cadence, and phrasing every turn so you never repeat yourself or sound scripted.")
                append("Reply in plain spoken sentences with no markdown, backticks, asterisks, bullet symbols, or emojis because your reply will be spoken aloud via TextToSpeech.")
            }

            val contextualUserMessage = buildString {
                appendLine("Live Workspace Context:")
                appendLine(localContext)
                append("King Israel says: $userPrompt")
            }

            val groqKey = securePrefs.getKeyForProvider(LlmProvider.GROQ)
                ?: return@withContext Result.failure(
                    IllegalStateException("No Groq API key found in EncryptedSharedPreferences.")
                )

            val historyPairs = recentTurns.map { turn ->
                val role = if (turn.sender.equals("ASSISTANT", ignoreCase = true)) "assistant" else "user"
                role to turn.text.take(350)
            }

            executeGroqPost(
                rawApiKey = groqKey,
                systemPrompt = systemInstruction,
                historyTurns = historyPairs,
                userText = contextualUserMessage
            ).map { diag ->
                AssistantResponse(
                    replyText = sanitizeForSpeech(diag.replyText),
                    providerUsed = LlmProvider.GROQ,
                    modelUsed = GROQ_MODEL
                )
            }

            /*
             * GEMINI ROUTING TEMPORARILY DISABLED (Cloud permission issues):
             *
             * LlmProvider.GEMINI -> {
             *     val key = securePrefs.getKeyForProvider(LlmProvider.GEMINI)
             *         ?: return@withContext Result.failure(
             *             IllegalStateException("No Gemini API key found in EncryptedSharedPreferences.")
             *         )
             *     executeGeminiPost(
             *         rawApiKey = key,
             *         systemPrompt = fullSystemInstruction,
             *         userMessage = userPrompt
             *     ).map { diag ->
             *         AssistantResponse(
             *             replyText = sanitizeForSpeech(diag.replyText),
             *             providerUsed = LlmProvider.GEMINI,
             *             modelUsed = GEMINI_MODEL
             *         )
             *     }
             * }
             */
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateCoachBriefing(
        briefingTitle: String,
        db: AppDatabase,
        prefs: UserPreferencesState,
        securePrefs: SecurePreferences
    ): String {
        val displayName = prefs.userName.ifBlank { "King Israel" }
        return TtsGreetingHelper.buildDynamicBriefing(db, displayName, briefingTitle)
    }

    /**
     * Rewrites a WhatsApp lead follow-up draft using the lead's observation in a short, friendly
     * Nigerian business tone via Groq ("openai/gpt-oss-120b"). Returns null on failure so the
     * caller falls back seamlessly to the offline editable template.
     */
    suspend fun rewriteLeadDraftViaGroq(
        lead: Lead,
        stepKey: String,
        offlineDraft: String,
        securePrefs: SecurePreferences
    ): String? = withContext(Dispatchers.IO) {
        if (!securePrefs.isAiEnabled || !securePrefs.hasGroqKey()) return@withContext null
        val key = securePrefs.getKeyForProvider(LlmProvider.GROQ) ?: return@withContext null

        try {
            val systemPrompt = buildString {
                appendLine("You write short, warm, natural WhatsApp outreach and follow-up messages for King Israel in a friendly Nigerian business tone.")
                appendLine("Keep it concise (2 to 4 sentences max), respectful, human, and direct — never pushy, robotic, or overly formal.")
                appendLine("Do not use hashtags, markdown, bullet points, or quotation marks around the message.")
                if (stepKey == LeadCadenceEngine.STEP_DAY_0_MESSAGE) {
                    appendLine("CRITICAL: This is the first message (Day 0). You MUST naturally include the specific observation about their page or website.")
                }
                append("Output ONLY the ready-to-send WhatsApp message text.")
            }

            val userPrompt = buildString {
                appendLine("Lead Name: ${lead.name}")
                appendLine("Service: ${LeadCadenceEngine.serviceReadableLabel(lead.service)}")
                appendLine("Cadence Step: ${LeadCadenceEngine.stepShortLabel(stepKey)}")
                if (lead.observation.isNotBlank()) {
                    appendLine("Specific Observation: ${lead.observation}")
                }
                lead.quoteAmount?.let {
                    appendLine("Quote Amount: NGN ${String.format(Locale.US, "%,d", it)}")
                }
                appendLine("Base Template Draft: $offlineDraft")
                append("Rewrite this draft naturally using the observation above.")
            }

            val reply = executeGroqPost(
                rawApiKey = key,
                systemPrompt = systemPrompt,
                userText = userPrompt
            ).getOrNull()?.replyText?.trim()?.trim('"') ?: return@withContext null

            if (reply.isBlank()) return@withContext null
            reply
        } catch (e: Exception) {
            Log.w(TAG, "Lead draft AI rewrite failed, using offline template: ${e.message}")
            null
        }
    }

    /**
     * Classifies unread emails via Groq using ONLY sender, subject, and a short snippet.
     * Returns an empty map on network/parse error so the caller falls back to offline rules.
     */
    suspend fun classifyEmailsBatchViaGroq(
        inputs: List<AiEmailBatchInput>,
        prioritySenders: List<String>,
        lowSenders: List<String>,
        emailedContacts: List<String>,
        learnedKeywords: List<String>,
        securePrefs: SecurePreferences
    ): Map<String, EmailClassificationDecision> = withContext(Dispatchers.IO) {
        if (inputs.isEmpty() || !securePrefs.isAiEnabled || !securePrefs.hasGroqKey()) {
            return@withContext emptyMap()
        }
        val key = securePrefs.getKeyForProvider(LlmProvider.GROQ) ?: return@withContext emptyMap()

        try {
            val emailsJson = JSONArray()
            inputs.forEachIndexed { idx, item ->
                emailsJson.put(
                    JSONObject().apply {
                        put("i", idx)
                        put("sender", item.sender.take(100))
                        put("subject", item.subject.take(120))
                        put("snippet", item.snippet.take(160))
                    }
                )
            }

            val classifierSystemPrompt = buildString {
                appendLine("You classify unread emails for King Israel as HIGH, NORMAL, or LOW using ONLY sender, subject, and snippet.")
                appendLine("Rules:")
                appendLine("- HIGH: meetings, invitations, calls, interviews, appointments, deadlines, invoices, payments, proposals, urgent, security alerts, replies from people he has emailed, and senders on his priority list.")
                appendLine("- LOW: newsletters, promotions, social and game notifications, and anything from a no-reply address or unsubscribe list.")
                appendLine("- NORMAL: everything else.")
                if (prioritySenders.isNotEmpty()) {
                    appendLine("Priority senders: ${prioritySenders.take(25).joinToString(", ")}")
                }
                if (lowSenders.isNotEmpty()) {
                    appendLine("Low-priority senders: ${lowSenders.take(25).joinToString(", ")}")
                }
                if (emailedContacts.isNotEmpty()) {
                    appendLine("People King Israel has emailed: ${emailedContacts.take(30).joinToString(", ")}")
                }
                if (learnedKeywords.isNotEmpty()) {
                    appendLine("Learned priority keywords: ${learnedKeywords.take(25).joinToString(", ")}")
                }
                append("Reply ONLY with a valid JSON array: [{\"i\":0,\"priority\":\"HIGH\"|\"NORMAL\"|\"LOW\",\"why\":\"one short reason under 60 chars\",\"category\":\"INVOICE\"|\"MEETING\"|\"TASK\"|\"GENERAL\",\"taskTitle\":\"short task title\"}]")
            }

            val rawReply = executeGroqPost(
                rawApiKey = key,
                systemPrompt = classifierSystemPrompt,
                userText = emailsJson.toString()
            ).getOrNull()?.replyText ?: return@withContext emptyMap()

            val arrStart = rawReply.indexOf('[')
            val arrEnd = rawReply.lastIndexOf(']')
            if (arrStart == -1 || arrEnd <= arrStart) return@withContext emptyMap()

            val parsedArray = JSONArray(rawReply.substring(arrStart, arrEnd + 1))
            val resultMap = HashMap<String, EmailClassificationDecision>()
            for (pos in 0 until parsedArray.length()) {
                val obj = parsedArray.optJSONObject(pos) ?: continue
                val idx = obj.optInt("i", -1)
                if (idx !in inputs.indices) continue
                val targetInput = inputs[idx]
                val prioRaw = obj.optString("priority", "NORMAL").uppercase(Locale.US)
                val prio = if (prioRaw in setOf("HIGH", "NORMAL", "LOW")) prioRaw else "NORMAL"
                val why = obj.optString("why", "").trim()
                    .ifBlank {
                        when (prio) {
                            "HIGH" -> "AI classified as high priority"
                            "LOW" -> "AI classified as low priority"
                            else -> "AI classified as normal priority"
                        }
                    }
                    .take(80)
                val catRaw = obj.optString("category", "GENERAL").uppercase(Locale.US)
                val cat = if (catRaw in setOf("INVOICE", "MEETING", "TASK", "GENERAL")) catRaw else "GENERAL"
                val taskTitle = obj.optString("taskTitle", "").trim()
                    .ifBlank { "${targetInput.subject.take(65)} (from ${targetInput.sender.take(40)})" }

                resultMap[targetInput.id] = EmailClassificationDecision(
                    priority = prio,
                    reason = why,
                    isActionable = prio == "HIGH" || cat in setOf("INVOICE", "MEETING", "TASK"),
                    category = cat,
                    extractedTaskTitle = taskTitle
                )
            }
            resultMap
        } catch (e: Exception) {
            Log.w(TAG, "Batch AI email classification failed; falling back to offline rules: ${e.message}")
            emptyMap()
        }
    }

    suspend fun analyzeEmailForTaskOrInvoice(
        sender: String,
        subject: String,
        snippet: String,
        securePrefs: SecurePreferences
    ): EmailAnalysisResult = withContext(Dispatchers.IO) {
        if (securePrefs.isAiEnabled && securePrefs.hasGroqKey()) {
            val aiAttempt = tryExtractEmailViaGroq(sender, subject, snippet, securePrefs)
            if (aiAttempt != null) {
                return@withContext aiAttempt
            }
        }
        heuristicEmailAnalysis(sender, subject, snippet)
    }

    private fun tryExtractEmailViaGroq(
        sender: String,
        subject: String,
        snippet: String,
        securePrefs: SecurePreferences
    ): EmailAnalysisResult? {
        return try {
            val key = securePrefs.getKeyForProvider(LlmProvider.GROQ) ?: return null
            val prompt = buildString {
                appendLine("Extract actionable tasks or invoices from this email.")
                appendLine("Reply ONLY with compact JSON: {\"actionable\":true|false,\"category\":\"TASK\"|\"INVOICE\"|\"GENERAL\",\"taskTitle\":\"short actionable task title\"}.")
                appendLine("From: $sender")
                appendLine("Subject: $subject")
                append("Body: ${snippet.take(400)}")
            }

            val rawReply = executeGroqPost(
                rawApiKey = key,
                userText = prompt
            ).getOrNull()?.replyText ?: return null

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

    suspend fun testGroqConnection(apiKey: String): Result<ApiDiagnosticResult> =
        withContext(Dispatchers.IO) {
            val cleanKey = SecurePreferences.sanitizeApiKey(apiKey)
            if (cleanKey.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("No API key found. Save it first.")
                )
            }
            executeGroqPost(
                rawApiKey = cleanKey,
                systemPrompt = SYSTEM_COACH_PROMPT,
                userText = "Give King Israel a fresh, short one-sentence check-in confirming the Groq coaching brain is online."
            )
        }

    /**
     * Groq API Service:
     * - URL: https://api.groq.com/openai/v1/chat/completions
     * - Headers: Authorization: Bearer <key>, Content-Type: application/json
     * - Model: Hardcoded to "openai/gpt-oss-120b"
     * - Includes dynamic system coach prompt + recent conversation turns for natural variety
     * - Response Parsing: choices[0].message.content
     */
    private fun executeGroqPost(
        rawApiKey: String,
        systemPrompt: String? = SYSTEM_COACH_PROMPT,
        historyTurns: List<Pair<String, String>> = emptyList(),
        userText: String
    ): Result<ApiDiagnosticResult> {
        return try {
            val apiKey = SecurePreferences.sanitizeApiKey(rawApiKey)

            val messagesArray = JSONArray().apply {
                if (!systemPrompt.isNullOrBlank()) {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        }
                    )
                }
                historyTurns.forEach { (role, content) ->
                    put(
                        JSONObject().apply {
                            put("role", role)
                            put("content", content)
                        }
                    )
                }
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", userText)
                    }
                )
            }
            val payload = JSONObject().apply {
                put("model", GROQ_MODEL)
                put("messages", messagesArray)
                put("temperature", 0.8)
            }

            val request = Request.Builder()
                .url(GROQ_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = extractReadableApiError(bodyStr)
                    Log.e(TAG, "Groq HTTP ${response.code} ($GROQ_MODEL): $detail")
                    return Result.failure(
                        ApiHttpException(
                            provider = "GROQ/$GROQ_MODEL",
                            statusCode = response.code,
                            statusMessage = response.message,
                            errorDetail = detail
                        )
                    )
                }

                val json = JSONObject(bodyStr)
                val choices = json.optJSONArray("choices")
                    ?: return Result.failure(IllegalStateException("Groq HTTP 200 missing 'choices' array"))
                if (choices.length() == 0) {
                    return Result.failure(IllegalStateException("Groq HTTP 200 empty 'choices' array"))
                }
                val messageObj = choices.getJSONObject(0).optJSONObject("message")
                    ?: return Result.failure(IllegalStateException("Groq HTTP 200 missing 'message' object"))
                val content = messageObj.optString("content", "").trim()
                if (content.isEmpty()) {
                    Result.failure(IllegalStateException("Groq HTTP 200 blank choices[0].message.content"))
                } else {
                    Result.success(
                        ApiDiagnosticResult(
                            provider = LlmProvider.GROQ,
                            modelUsed = GROQ_MODEL,
                            statusCode = response.code,
                            replyText = content
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq request failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /*
     * =========================================================================================
     * GEMINI API SERVICE — TEMPORARILY DISABLED (Cloud permission issues)
     * Preserved for future re-activation.
     * =========================================================================================
     *
     * suspend fun testGeminiConnection(apiKey: String): Result<ApiDiagnosticResult> =
     *     withContext(Dispatchers.IO) {
     *         val cleanKey = SecurePreferences.sanitizeApiKey(apiKey)
     *         if (cleanKey.isBlank()) {
     *             return@withContext Result.failure(
     *                 IllegalArgumentException("No API key found. Save it first.")
     *             )
     *         }
     *         executeGeminiPost(
     *             rawApiKey = cleanKey,
     *             systemPrompt = SYSTEM_COACH_PROMPT,
     *             userMessage = "Reply in one short sentence: Gemini brain online for King Israel."
     *         )
     *     }
     *
     * private fun executeGeminiPost(
     *     rawApiKey: String,
     *     systemPrompt: String,
     *     userMessage: String
     * ): Result<ApiDiagnosticResult> {
     *     return try {
     *         val apiKey = SecurePreferences.sanitizeApiKey(rawApiKey)
     *         val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=$apiKey"
     *         val combinedPrompt = "$systemPrompt\n\nUser: $userMessage"
     *
     *         val payload = JSONObject().apply {
     *             put(
     *                 "contents",
     *                 JSONArray().put(
     *                     JSONObject().put(
     *                         "parts",
     *                         JSONArray().put(JSONObject().put("text", combinedPrompt))
     *                     )
     *                 )
     *             )
     *             put(
     *                 "generationConfig",
     *                 JSONObject()
     *                     .put("temperature", 0.5)
     *                     .put("maxOutputTokens", 400)
     *             )
     *         }
     *
     *         val request = Request.Builder()
     *             .url(endpoint)
     *             .header("Content-Type", "application/json")
     *             .header("Accept", "application/json")
     *             .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
     *             .build()
     *
     *         httpClient.newCall(request).execute().use { response ->
     *             val bodyStr = response.body?.string().orEmpty()
     *             if (!response.isSuccessful) {
     *                 val detail = extractReadableApiError(bodyStr)
     *                 return Result.failure(
     *                     ApiHttpException(
     *                         provider = "GEMINI/gemini-1.5-flash-latest",
     *                         statusCode = response.code,
     *                         statusMessage = response.message,
     *                         errorDetail = detail
     *                     )
     *                 )
     *             }
     *             val json = JSONObject(bodyStr)
     *             val candidates = json.optJSONArray("candidates")
     *             if (candidates == null || candidates.length() == 0) {
     *                 return Result.failure(IllegalStateException("Gemini HTTP 200 but zero candidates"))
     *             }
     *             val firstCandidate = candidates.getJSONObject(0)
     *             val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
     *             val combinedText = buildString {
     *                 if (parts != null) {
     *                     for (i in 0 until parts.length()) {
     *                         val t = parts.optJSONObject(i)?.optString("text", "")?.trim().orEmpty()
     *                         if (t.isNotEmpty()) {
     *                             if (isNotEmpty()) append(" ")
     *                             append(t)
     *                         }
     *                     }
     *                 }
     *             }.trim()
     *             Result.success(
     *                 ApiDiagnosticResult(
     *                     provider = LlmProvider.GEMINI,
     *                     modelUsed = "gemini-1.5-flash-latest",
     *                     statusCode = response.code,
     *                     replyText = combinedText
     *                 )
     *             )
     *         }
     *     } catch (e: Exception) {
     *         Result.failure(e)
     *     }
     * }
     */

    private fun extractReadableApiError(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return "Empty error body from server"
        return try {
            val root = JSONObject(trimmed)
            val errObj = root.optJSONObject("error")
            if (errObj != null) {
                val code = errObj.optString("code", "").ifBlank { errObj.optString("status", "") }
                val message = errObj.optString("message", "").trim()
                if (message.isNotEmpty()) {
                    if (code.isNotEmpty()) "[$code] $message" else message
                } else {
                    trimmed.take(400)
                }
            } else {
                trimmed.take(400)
            }
        } catch (_: Exception) {
            trimmed.replace(Regex("\\s+"), " ").take(400)
        }
    }

    fun sanitizeForSpeech(raw: String): String {
        return raw
            .replace("```", " ")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace("**", "")
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .replace("|", " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
