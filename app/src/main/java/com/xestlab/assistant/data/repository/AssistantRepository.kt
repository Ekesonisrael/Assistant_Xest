package com.xestlab.assistant.data.repository

import com.xestlab.assistant.data.UserPreferencesDataStore
import com.xestlab.assistant.data.WebTrackerImporter
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.ChecklistItem
import com.xestlab.assistant.data.model.ConversationTurn
import com.xestlab.assistant.data.model.DailyLog
import com.xestlab.assistant.data.model.Fact
import com.xestlab.assistant.data.model.Goal
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.model.ReminderSchedule
import com.xestlab.assistant.data.model.UnreadEmailEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class WeeklySummaryData(
    val messagesCount: Int,
    val messagesTarget: Int,
    val conversationsCount: Int,
    val conversationsTarget: Int,
    val quotesCount: Int,
    val quotesTarget: Int,
    val contentCount: Int,
    val studyCount: Int
)

class AssistantRepository(
    private val db: AppDatabase,
    private val userPreferences: UserPreferencesDataStore
) {

    val goals: Flow<Goal?> = db.goalDao().observeGoals()
    val checklistItems: Flow<List<ChecklistItem>> = db.checklistDao().observeAllItems()
    val reminders: Flow<List<ReminderSchedule>> = db.reminderDao().observeReminders()
    val allItems: Flow<List<Item>> = db.itemDao().observeAllItems()
    val pendingItems: Flow<List<Item>> = db.itemDao().observePendingItems()
    val allFacts: Flow<List<Fact>> = db.factDao().observeAllFacts()
    val conversationTurns: Flow<List<ConversationTurn>> = db.conversationDao().observeTurns()
    val unreadEmails: Flow<List<UnreadEmailEntity>> = db.emailDao().observeRecentUnread()

    fun getLogsForDate(date: String): Flow<List<DailyLog>> =
        db.dailyLogDao().observeLogsForDate(date)

    fun searchNotes(query: String): Flow<List<Item>> =
        db.itemDao().searchNotes(query)

    suspend fun saveItem(item: Item): Long = db.itemDao().insertItem(item)

    suspend fun updateItemStatus(id: Long, status: String) =
        db.itemDao().updateStatus(id, status)

    suspend fun deleteItem(item: Item) = db.itemDao().deleteItem(item)

    suspend fun saveFact(text: String, category: String = "GENERAL"): Long =
        db.factDao().insertFact(Fact(text = text, category = category))

    suspend fun deleteFact(fact: Fact) = db.factDao().deleteFact(fact)

    suspend fun clearAllFacts() = db.factDao().clearAllFacts()

    suspend fun recordTurn(sender: String, text: String) {
        db.conversationDao().insertTurn(ConversationTurn(sender = sender, text = text))
        db.conversationDao().trimToLastFifty()
    }

    suspend fun clearConversation() = db.conversationDao().clearHistory()

    suspend fun markItemDone(date: String, itemId: String, countValue: Int = 0) {
        db.dailyLogDao().deleteForDateAndItem(date, itemId)
        db.dailyLogDao().insertLog(
            DailyLog(
                date = date,
                itemId = itemId,
                isDone = true,
                countValue = countValue
            )
        )
    }

    suspend fun markItemNotDone(date: String, itemId: String, reason: String) {
        db.dailyLogDao().deleteForDateAndItem(date, itemId)
        db.dailyLogDao().insertLog(
            DailyLog(
                date = date,
                itemId = itemId,
                isDone = false,
                countValue = 0,
                reasonNotDone = reason
            )
        )
    }

    suspend fun saveGoals(goal: Goal) = db.goalDao().saveGoals(goal)

    suspend fun saveReminder(reminder: ReminderSchedule) =
        db.reminderDao().saveReminder(reminder)

    suspend fun deleteReminder(reminder: ReminderSchedule) =
        db.reminderDao().deleteReminder(reminder)

    suspend fun promoteEmailToTask(email: UnreadEmailEntity): Long {
        val taskTitle = email.extractedTaskTitle?.takeIf { it.isNotBlank() }
            ?: "Follow up email: ${email.subject}"
        val id = db.itemDao().insertItem(
            Item(
                type = "TASK",
                title = taskTitle,
                details = "From ${email.sender}: ${email.subject}. ${email.snippet}".take(280),
                priority = "HIGH",
                status = "PENDING",
                sourceText = "imap_email_${email.id}"
            )
        )
        db.emailDao().markAddedToTasks(email.id)
        return id
    }

    suspend fun buildUnreadEmailSpokenSummary(): String {
        val allCached = db.emailDao().getRecentUnreadDirect(20)
        if (allCached.isEmpty()) {
            return "You have no unread emails cached right now. If IMAP is enabled in Settings, tap Sync Now or wait for the next 30-minute check."
        }

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        val todaysEmails = allCached.filter { it.receivedAt >= todayStart }
        val targetList = if (todaysEmails.isNotEmpty()) todaysEmails else allCached

        val header = if (todaysEmails.isNotEmpty()) {
            "You received ${todaysEmails.size} unread ${if (todaysEmails.size == 1) "email" else "emails"} today. "
        } else {
            "No new unread emails arrived today, but you have ${allCached.size} unread in your inbox. "
        }

        val subjectsText = targetList.take(5).mapIndexed { index, email ->
            val tag = when (email.category) {
                "INVOICE" -> "Invoice from ${email.sender}"
                "TASK" -> "Action item from ${email.sender}"
                else -> "From ${email.sender}"
            }
            "${index + 1}: $tag, subject: ${email.subject}"
        }.joinToString(". ")

        return "$header$subjectsText."
    }

    suspend fun getWeeklySummary(weekStart: String, weekEnd: String): WeeklySummaryData {
        val goal = db.goalDao().getGoalsDirect() ?: Goal()
        val logs = db.dailyLogDao().getLogsBetweenDates(weekStart, weekEnd)

        var messages = 0
        var followups = 0
        var leadsheet = 0
        var content = 0
        var study = 0

        logs.forEach { log ->
            if (log.isDone) {
                when (log.itemId) {
                    "messages10" -> messages += if (log.countValue > 0) log.countValue else 10
                    "followups" -> followups += 1
                    "leadsheet" -> leadsheet += 1
                    "content" -> content += 1
                    "study" -> study += 1
                }
            }
        }

        return WeeklySummaryData(
            messagesCount = messages,
            messagesTarget = goal.weeklyMessagesTarget,
            conversationsCount = followups,
            conversationsTarget = goal.weeklyConversationsTarget,
            quotesCount = (leadsheet / 2).coerceAtLeast(0),
            quotesTarget = goal.weeklyQuotesTarget,
            contentCount = content,
            studyCount = study
        )
    }

    /**
     * Builds the natural spoken summary of today's plan, greeting King Israel (or the user's
     * saved DataStore name) warmly: "Good morning King Israel, how are you doing? Let's start with..."
     */
    suspend fun buildSpokenDailyPlan(
        overrideGreetingPrefix: String? = null,
        isWakeUpAlarm: Boolean = false
    ): String {
        AppDatabase.ensureSeeded(db)
        val prefs = userPreferences.getSnapshot()
        val displayName = prefs.userName.ifBlank { "King Israel" }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greetingWord = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val checklist = db.checklistDao().getAllItemsDirect()
        val logsByItem = db.dailyLogDao().getLogsForDateDirect(todayStr).associateBy { it.itemId }
        val unfinishedChecklist = checklist.filter { logsByItem[it.id]?.isDone != true }
        val pendingTasks = db.itemDao().getPendingItemsDirect()
            .filter { !it.sourceText.startsWith("web_pipeline_") }

        val sb = StringBuilder()
        when {
            isWakeUpAlarm || hour < 12 -> {
                sb.append("Good morning $displayName, how are you doing? ")
            }
            !overrideGreetingPrefix.isNullOrBlank() -> {
                sb.append("$overrideGreetingPrefix $displayName, how are you doing? ")
            }
            else -> {
                sb.append("$greetingWord $displayName, here is where we stand today. ")
            }
        }

        if (unfinishedChecklist.isEmpty() && pendingTasks.isEmpty()) {
            sb.append("Every single daily target and scheduled task is complete. Outstanding execution today.")
            return sb.toString()
        }

        if (unfinishedChecklist.isNotEmpty()) {
            sb.append("Let's start with ${unfinishedChecklist.first().title}. ")
            if (unfinishedChecklist.size > 1) {
                sb.append("You have ${unfinishedChecklist.size} core daily targets left on the board. ")
            }
        }

        if (pendingTasks.isNotEmpty()) {
            val topTask = pendingTasks.first()
            val timeFormatted = topTask.dueAt?.let {
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it))
            }
            if (unfinishedChecklist.isEmpty()) {
                sb.append("Let's start with ${topTask.title}${if (timeFormatted != null) " scheduled for $timeFormatted" else ""}. ")
            } else if (timeFormatted != null) {
                sb.append("Next on your schedule at $timeFormatted is ${topTask.title}. ")
            } else {
                sb.append("Your priority task after that is ${topTask.title}. ")
            }

            val dailyPace = topTask.computeDailyRequired()
            if (dailyPace != null && topTask.unit != null) {
                sb.append("Required pace is ${String.format(Locale.US, "%.0f", dailyPace)} ${topTask.unit} per day.")
            }
        }

        return sb.toString().trim()
    }

    suspend fun exportJson(): String {
        val prefs = userPreferences.getSnapshot()
        val goal = db.goalDao().getGoalsDirect() ?: Goal()
        val logs = db.dailyLogDao().getAllLogs()
        val items = db.itemDao().getAllItemsDirect()
        val facts = db.factDao().getAllFactsDirect()

        val root = JSONObject()
        val settingsObj = JSONObject().apply {
            put("userName", prefs.userName)
            put("wakeUpTime", prefs.wakeUpTime)
            put("quietHoursStart", prefs.quietHoursStart)
            put("quietHoursEnd", prefs.quietHoursEnd)
            put("quietHoursEnabled", prefs.quietHoursEnabled)
            put("voiceSpeed", prefs.voiceSpeed.toDouble())
            put("yearGoal", goal.yearlyGoal)
            put("startDate", goal.startDate)
        }
        root.put("settings", settingsObj)

        val logsArr = JSONArray()
        logs.forEach { l ->
            logsArr.put(
                JSONObject().apply {
                    put("date", l.date)
                    put("itemId", l.itemId)
                    put("isDone", l.isDone)
                    put("countValue", l.countValue)
                    put("reasonNotDone", l.reasonNotDone ?: JSONObject.NULL)
                    put("timestamp", l.timestamp)
                }
            )
        }
        root.put("logs", logsArr)

        val itemsArr = JSONArray()
        items.forEach { i ->
            itemsArr.put(
                JSONObject().apply {
                    put("type", i.type)
                    put("title", i.title)
                    put("details", i.details)
                    put("dueAt", i.dueAt ?: JSONObject.NULL)
                    put("durationMinutes", i.durationMinutes)
                    put("priority", i.priority)
                    put("status", i.status)
                    put("targetAmount", i.targetAmount ?: JSONObject.NULL)
                    put("unit", i.unit ?: JSONObject.NULL)
                    put("progress", i.progress)
                    put("contactName", i.contactName ?: JSONObject.NULL)
                    put("sourceText", i.sourceText)
                }
            )
        }
        root.put("items", itemsArr)

        val factsArr = JSONArray()
        facts.forEach { f ->
            factsArr.put(
                JSONObject().apply {
                    put("text", f.text)
                    put("category", f.category)
                }
            )
        }
        root.put("facts", factsArr)

        return root.toString(2)
    }

    suspend fun importJson(jsonString: String): Boolean {
        return WebTrackerImporter.importData(db, userPreferences, jsonString)
    }
}
