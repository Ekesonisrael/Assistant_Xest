package com.xestlab.assistant.data.repository

import com.xestlab.assistant.data.WebTrackerImporter
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

class AssistantRepository(private val db: AppDatabase) {

    val goals: Flow<Goal?> = db.goalDao().getGoals()
    val checklistItems: Flow<List<ChecklistItem>> = db.checklistDao().getAllItems()
    val reminders: Flow<List<ReminderSchedule>> = db.reminderDao().getAllReminders()
    val settings: Flow<UserSettings?> = db.settingsDao().getSettings()

    val allItems: Flow<List<Item>> = db.itemDao().getAllItems()
    val pendingItems: Flow<List<Item>> = db.itemDao().getPendingItems()
    val allFacts: Flow<List<Fact>> = db.factDao().getAllFacts()
    val conversationTurns: Flow<List<ConversationTurn>> = db.conversationDao().getLastTurns(50)

    fun getLogsForDate(date: String): Flow<List<DailyLog>> {
        return db.dailyLogDao().getLogsForDate(date)
    }

    fun searchNotes(query: String): Flow<List<Item>> {
        return db.itemDao().searchNotes(query)
    }

    suspend fun markItemDone(date: String, itemId: String, count: Int = 0) {
        db.dailyLogDao().deleteLogForItem(date, itemId)
        val log = DailyLog(
            date = date,
            itemId = itemId,
            isDone = true,
            countValue = count,
            reasonNotDone = null,
            timestamp = System.currentTimeMillis()
        )
        db.dailyLogDao().insertLog(log)
    }

    suspend fun markItemNotDone(date: String, itemId: String, reason: String) {
        db.dailyLogDao().deleteLogForItem(date, itemId)
        val log = DailyLog(
            date = date,
            itemId = itemId,
            isDone = false,
            countValue = 0,
            reasonNotDone = reason,
            timestamp = System.currentTimeMillis()
        )
        db.dailyLogDao().insertLog(log)
    }

    suspend fun saveGoals(goal: Goal) {
        db.goalDao().saveGoals(goal)
    }

    suspend fun saveSettings(settings: UserSettings) {
        db.settingsDao().saveSettings(settings)
    }

    suspend fun saveReminder(reminder: ReminderSchedule) {
        db.reminderDao().insertReminder(reminder)
    }

    suspend fun deleteReminder(reminder: ReminderSchedule) {
        db.reminderDao().deleteReminder(reminder)
    }

    // Generic Item Operations
    suspend fun saveItem(item: Item): Long {
        return db.itemDao().insertItem(item)
    }

    suspend fun updateItemStatus(id: Long, status: String) {
        db.itemDao().updateStatus(id, status)
    }

    suspend fun updateItemProgress(id: Long, progress: Double) {
        db.itemDao().updateProgress(id, progress)
    }

    suspend fun deleteItem(item: Item) {
        db.itemDao().deleteItem(item)
    }

    suspend fun deleteItemById(id: Long) {
        db.itemDao().deleteItemById(id)
    }

    // Fact Memory Operations
    suspend fun saveFact(text: String, category: String = "GENERAL"): Long {
        return db.factDao().insertFact(Fact(text = text, category = category))
    }

    suspend fun deleteFact(fact: Fact) {
        db.factDao().deleteFact(fact)
    }

    suspend fun deleteFactById(id: Long) {
        db.factDao().deleteFactById(id)
    }

    suspend fun clearAllFacts() {
        db.factDao().clearAllFacts()
    }

    // Conversation History Operations
    suspend fun recordTurn(sender: String, text: String, actionsJson: String? = null) {
        db.conversationDao().insertTurn(
            ConversationTurn(sender = sender, text = text, actionsJson = actionsJson)
        )
        db.conversationDao().trimToLast(50)
    }

    suspend fun clearConversation() {
        db.conversationDao().clearAllTurns()
    }

    suspend fun getWeeklySummary(weekStartDate: String, weekEndDate: String): WeeklySummaryData {
        val logs = db.dailyLogDao().getLogsBetweenDates(weekStartDate, weekEndDate)
        val goal = db.goalDao().getGoalsDirect() ?: Goal()

        var messagesDone = 0
        var followUpsDone = 0
        var contentDone = 0
        var studyDone = 0
        var leadSheetDone = 0

        logs.forEach { log ->
            if (log.isDone) {
                when (log.itemId) {
                    "messages10" -> messagesDone += if (log.countValue > 0) log.countValue else 10
                    "followups" -> followUpsDone += 1
                    "content" -> contentDone += 1
                    "study" -> studyDone += 1
                    "leadsheet" -> leadSheetDone += 1
                }
            }
        }

        return WeeklySummaryData(
            messagesCount = messagesDone,
            messagesTarget = goal.weeklyMessagesTarget,
            conversationsCount = followUpsDone,
            conversationsTarget = goal.weeklyConversationsTarget,
            quotesCount = (leadSheetDone / 2).coerceAtLeast(0),
            quotesTarget = goal.weeklyQuotesTarget,
            contentCount = contentDone,
            studyCount = studyDone
        )
    }

    /**
     * Exports sanitized JSON without any passwords, tokens, or API keys.
     */
    suspend fun exportJson(): String {
        val root = JSONObject()
        val goal = db.goalDao().getGoalsDirect() ?: Goal()
        val settings = db.settingsDao().getSettingsDirect() ?: UserSettings()
        val allLogs = db.dailyLogDao().getAllLogs()
        val allReminders = db.reminderDao().getAllRemindersDirect()
        val allGenericItems = db.itemDao().getAllItemsDirect()
        val facts = db.factDao().getAllFactsDirect()

        // Settings & Targets (no sensitive keys)
        val settingsObj = JSONObject().apply {
            put("yearGoal", goal.yearlyGoal)
            put("startDate", goal.startDate)
            put("userName", settings.userName)
            put("wakeUpTime", settings.wakeUpTime)
            put("quietHoursStart", settings.quietHoursStart)
            put("quietHoursEnd", settings.quietHoursEnd)
            put("quietHoursEnabled", settings.quietHoursEnabled)
            put("voiceSpeed", settings.voiceSpeed.toDouble())

            val weeklyObj = JSONObject().apply {
                put("messages", goal.weeklyMessagesTarget)
                put("conversations", goal.weeklyConversationsTarget)
                put("quotes", goal.weeklyQuotesTarget)
            }
            put("weeklyTargets", weeklyObj)

            val quarterObj = JSONObject().apply {
                put("q1", goal.q1Target)
                put("q2", goal.q2Target)
                put("q3", goal.q3Target)
                put("q4", goal.q4Target)
            }
            put("quarterTargets", quarterObj)
        }
        root.put("settings", settingsObj)

        // Logs
        val logsArray = JSONArray()
        allLogs.forEach { log ->
            val lObj = JSONObject().apply {
                put("date", log.date)
                put("itemId", log.itemId)
                put("isDone", log.isDone)
                put("countValue", log.countValue)
                put("reasonNotDone", log.reasonNotDone ?: "")
                put("timestamp", log.timestamp)
            }
            logsArray.put(lObj)
        }
        root.put("logs", logsArray)

        // Reminders
        val remindersArray = JSONArray()
        allReminders.forEach { r ->
            val rObj = JSONObject().apply {
                put("id", r.id)
                put("title", r.title)
                put("timeString", r.timeString)
                put("isRepeating", r.isRepeating)
                put("daysOfWeek", r.daysOfWeek)
                put("voiceEnabled", r.voiceEnabled)
                put("isActive", r.isActive)
                put("isBuiltIn", r.isBuiltIn)
            }
            remindersArray.put(rObj)
        }
        root.put("reminders", remindersArray)

        // Generic Items
        val itemsArray = JSONArray()
        allGenericItems.forEach { item ->
            val itObj = JSONObject().apply {
                put("id", item.id)
                put("type", item.type)
                put("title", item.title)
                put("details", item.details)
                if (item.dueAt != null) put("dueAt", item.dueAt)
                put("durationMinutes", item.durationMinutes)
                put("priority", item.priority)
                put("status", item.status)
                if (item.repeatRule != null) put("repeatRule", item.repeatRule)
                if (item.targetAmount != null) put("targetAmount", item.targetAmount)
                if (item.unit != null) put("unit", item.unit)
                put("progress", item.progress)
                if (item.contactName != null) put("contactName", item.contactName)
                put("sourceText", item.sourceText)
                put("createdAt", item.createdAt)
            }
            itemsArray.put(itObj)
        }
        root.put("items", itemsArray)

        // Facts Memory
        val factsArray = JSONArray()
        facts.forEach { f ->
            val fObj = JSONObject().apply {
                put("id", f.id)
                put("text", f.text)
                put("category", f.category)
                put("createdAt", f.createdAt)
            }
            factsArray.put(fObj)
        }
        root.put("facts", factsArray)

        return root.toString(2)
    }

    suspend fun importJson(jsonString: String): Boolean {
        return WebTrackerImporter.importData(db, jsonString)
    }
}
