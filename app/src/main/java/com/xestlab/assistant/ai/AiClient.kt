package com.xestlab.assistant.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.xestlab.assistant.data.local.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class AssistantAction(
    val name: String,
    val itemId: String? = null,
    val type: String? = null,
    val title: String? = null,
    val count: Int? = null,
    val reason: String? = null,
    val minutes: Int? = null,
    val time: String? = null,
    val contactName: String? = null,
    val targetAmount: Double? = null,
    val unit: String? = null,
    val details: String? = null
)

// Backwards compatibility alias
typealias JarvisAction = AssistantAction

data class AssistantResponse(
    val reply: String,
    val actions: List<AssistantAction>
)

typealias JarvisResponse = AssistantResponse

object AiClient {

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val TIMEOUT_MILLIS = 6000

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Builds compact schedule and context summary from Room database.
     * Contains only necessary schedule metrics and stored facts.
     */
    suspend fun buildLocalContext(db: AppDatabase): JSONObject {
        val contextJson = JSONObject()
        val settings = db.settingsDao().getSettingsDirect()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val nowTimeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        contextJson.put("user_name", settings?.userName ?: "Founder")
        contextJson.put("current_date", todayStr)
        contextJson.put("current_time", nowTimeStr)
        contextJson.put("wake_up_time", settings?.wakeUpTime ?: "06:30")
        contextJson.put("quiet_hours", "${settings?.quietHoursStart ?: "22:00"} - ${settings?.quietHoursEnd ?: "07:00"}")

        // 1. Yearly goal and quarter targets
        val goal = db.goalDao().getGoalsDirect()
        val goalsJson = JSONObject()
        goalsJson.put("yearly_target", goal?.yearlyGoal ?: 10000000L)
        val quartersJson = JSONObject()
        quartersJson.put("q1", goal?.q1Target ?: 1500000L)
        quartersJson.put("q2", goal?.q2Target ?: 2400000L)
        quartersJson.put("q3", goal?.q3Target ?: 3200000L)
        quartersJson.put("q4", goal?.q4Target ?: 3500000L)
        goalsJson.put("quarters", quartersJson)
        contextJson.put("goals", goalsJson)

        // 2. This week's metrics
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val weekStart = sdf.format(cal.time)
        cal.add(Calendar.DAY_OF_WEEK, 6)
        val weekEnd = sdf.format(cal.time)

        val weekLogs = db.dailyLogDao().getLogsBetweenDates(weekStart, weekEnd)
        var messagesDone = 0
        var followUpsDone = 0
        var leadSheetDone = 0

        weekLogs.forEach { log ->
            if (log.isDone) {
                when (log.itemId) {
                    "messages10" -> messagesDone += if (log.countValue > 0) log.countValue else 10
                    "followups" -> followUpsDone += 1
                    "leadsheet" -> leadSheetDone += 1
                }
            }
        }

        val weeklyJson = JSONObject()
        weeklyJson.put("messages_sent", messagesDone)
        weeklyJson.put("messages_target", goal?.weeklyMessagesTarget ?: 50)
        weeklyJson.put("conversations_held", followUpsDone)
        weeklyJson.put("conversations_target", goal?.weeklyConversationsTarget ?: 5)
        weeklyJson.put("quotes_sent", (leadSheetDone / 2).coerceAtLeast(0))
        weeklyJson.put("quotes_target", goal?.weeklyQuotesTarget ?: 3)
        contextJson.put("this_week", weeklyJson)

        // 3. Today's checklist
        val allItems = db.checklistDao().getAllItemsDirect()
        val todayLogs = db.dailyLogDao().getLogsForDateDirect(todayStr).associateBy { it.itemId }
        val checklistArray = JSONArray()

        allItems.forEach { item ->
            val log = todayLogs[item.id]
            val itemObj = JSONObject()
            itemObj.put("id", item.id)
            itemObj.put("title", item.title)
            itemObj.put("is_done", log?.isDone == true)
            if (item.isCountType) {
                itemObj.put("count", log?.countValue ?: 0)
            }
            if (log != null && !log.isDone && !log.reasonNotDone.isNullOrBlank()) {
                itemObj.put("reason_not_done", log.reasonNotDone)
            }
            checklistArray.put(itemObj)
        }
        contextJson.put("today_checklist", checklistArray)

        // 4. Pending generic items
        val pendingItems = db.itemDao().getPendingItemsDirect()
        val itemsArray = JSONArray()
        pendingItems.take(10).forEach { pi ->
            val pObj = JSONObject().apply {
                put("id", pi.id)
                put("type", pi.type)
                put("title", pi.title)
                put("priority", pi.priority)
                if (pi.contactName != null) put("contact", pi.contactName)
            }
            itemsArray.put(pObj)
        }
        contextJson.put("pending_items", itemsArray)

        // 5. Memory facts
        val facts = db.factDao().getAllFactsDirect()
        val factsArray = JSONArray()
        facts.take(10).forEach { f ->
            factsArray.put(f.text)
        }
        contextJson.put("remembered_facts", factsArray)

        return contextJson
    }

    /** Coach spoken reminder under 40 words. Returns null on any failure. */
    fun generateCoachReminder(
        apiKey: String,
        modelName: String,
        contextJson: JSONObject,
        reminderType: String
    ): String? {
        val prompt = "You are the personal assistant and coach for an executive and studio founder.\n" +
                "Reminder occasion: $reminderType.\n" +
                "Schedule & Context:\n$contextJson\n\n" +
                "Instruction: Under 40 words, name the one thing I should do next. " +
                "If I keep skipping something, say so plainly. " +
                "Plain spoken text only. No quotes, no emojis, no em dashes. Limit: under 40 words."

        return callGemini(apiKey, modelName, prompt)?.let { cleanSpeechText(it) }
    }

    /**
     * NLU prompt returning structured JSON:
     * {"reply": "...", "actions": [{"name": "create_item", ...}]}
     */
    fun generateAssistantResponse(
        apiKey: String,
        modelName: String,
        contextJson: JSONObject,
        sessionHistory: List<Pair<String, String>>,
        userInput: String
    ): AssistantResponse? {
        val historyText = buildString {
            sessionHistory.takeLast(6).forEach { (speaker, text) ->
                append("$speaker: $text\n")
            }
        }

        val prompt = "You are a proactive, direct, warm personal assistant.\n" +
                "Context & Schedule:\n$contextJson\n\n" +
                (if (historyText.isNotBlank()) "Recent Conversation:\n$historyText\n" else "") +
                "User: $userInput\n\n" +
                "Answer STRICTLY in valid JSON matching this schema:\n" +
                "{\n" +
                "  \"reply\": \"<short direct reply under 40 words, plain spoken text, no emojis, no em dashes>\",\n" +
                "  \"actions\": [\n" +
                "    {\"name\": \"create_item\", \"type\": \"TASK|GOAL|NOTE|REMINDER|HABIT\", \"title\": \"...\", \"time\": \"HH:mm\", \"contact_name\": \"...\", \"target_amount\": 0.0, \"unit\": \"...\"},\n" +
                "    {\"name\": \"update_item\", \"item_id\": \"...\", \"status\": \"COMPLETED|DROPPED\"},\n" +
                "    {\"name\": \"complete_item\", \"title\": \"...\"},\n" +
                "    {\"name\": \"snooze\", \"minutes\": 15},\n" +
                "    {\"name\": \"add_note\", \"title\": \"...\", \"details\": \"...\"},\n" +
                "    {\"name\": \"propose_plan\", \"title\": \"...\"},\n" +
                "    {\"name\": \"remember_fact\", \"details\": \"...\"},\n" +
                "    {\"name\": \"read_emails\"}\n" +
                "  ]\n" +
                "}\n" +
                "If no action is needed, actions must be empty array []."

        val raw = callGemini(apiKey, modelName, prompt) ?: return null
        return parseAssistantResponse(raw)
    }

    // Compatibility method
    fun generateJarvisReply(
        apiKey: String,
        modelName: String,
        contextJson: JSONObject,
        sessionHistory: List<Pair<String, String>>,
        userMessage: String
    ): AssistantResponse? {
        return generateAssistantResponse(apiKey, modelName, contextJson, sessionHistory, userMessage)
    }

    fun testConnection(apiKey: String, modelName: String): Pair<Boolean, String> {
        return try {
            val reply = callGemini(apiKey, modelName, "Reply with 'Connected' in one word.")
            if (reply != null && reply.isNotBlank()) {
                Pair(true, "AI connected successfully ($modelName).")
            } else {
                Pair(false, "Connection failed. Check API key or network.")
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Connection error")
        }
    }

    private fun callGemini(apiKey: String, modelName: String, prompt: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val endpoint = "$BASE_URL$modelName:generateContent"
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                doOutput = true
                doInput = true
            }

            val requestBody = JSONObject().apply {
                val contents = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        }
                        put("parts", parts)
                    }
                    put(contentObj)
                }
                put("contents", contents)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseStr = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
                    reader.readText()
                }
                extractGeneratedText(responseStr)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractGeneratedText(responseJsonStr: String): String? {
        return try {
            val root = JSONObject(responseJsonStr)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val first = candidates.getJSONObject(0)
            val content = first.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            if (parts.length() == 0) return null
            parts.getJSONObject(0).optString("text", null)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAssistantResponse(rawJsonStr: String): AssistantResponse {
        var clean = rawJsonStr.trim()
        if (clean.startsWith("```json")) {
            clean = clean.removePrefix("```json").trim()
        }
        if (clean.startsWith("```")) {
            clean = clean.removePrefix("```").trim()
        }
        if (clean.endsWith("```")) {
            clean = clean.removeSuffix("```").trim()
        }

        return try {
            val root = JSONObject(clean)
            val reply = cleanSpeechText(root.optString("reply", "Understood."))
            val actionsArray = root.optJSONArray("actions")
            val actionsList = mutableListOf<AssistantAction>()

            if (actionsArray != null) {
                for (i in 0 until actionsArray.length()) {
                    val obj = actionsArray.getJSONObject(i)
                    actionsList.add(
                        AssistantAction(
                            name = obj.optString("name", ""),
                            itemId = obj.optString("item_id", "").takeIf { it.isNotBlank() },
                            type = obj.optString("type", "").takeIf { it.isNotBlank() },
                            title = obj.optString("title", "").takeIf { it.isNotBlank() },
                            count = if (obj.has("count")) obj.optInt("count") else null,
                            reason = obj.optString("reason", "").takeIf { it.isNotBlank() },
                            minutes = if (obj.has("minutes")) obj.optInt("minutes") else null,
                            time = obj.optString("time", "").takeIf { it.isNotBlank() },
                            contactName = obj.optString("contact_name", "").takeIf { it.isNotBlank() },
                            targetAmount = if (obj.has("target_amount")) obj.optDouble("target_amount") else null,
                            unit = obj.optString("unit", "").takeIf { it.isNotBlank() },
                            details = obj.optString("details", "").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            AssistantResponse(reply = reply, actions = actionsList)
        } catch (e: Exception) {
            AssistantResponse(reply = cleanSpeechText(rawJsonStr), actions = emptyList())
        }
    }

    fun cleanSpeechText(input: String): String {
        return input.replace("—", "-")
            .replace("–", "-")
            .replace("\"", "")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .trim()
    }
}
