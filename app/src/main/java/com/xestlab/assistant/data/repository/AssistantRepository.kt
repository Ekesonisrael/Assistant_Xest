package com.xestlab.assistant.data.repository

import com.xestlab.assistant.data.UserPreferencesDataStore
import com.xestlab.assistant.data.WebTrackerImporter
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.ChecklistItem
import com.xestlab.assistant.data.model.ConversationTurn
import com.xestlab.assistant.data.model.CustomEmailTagEntity
import com.xestlab.assistant.data.model.DailyLog
import com.xestlab.assistant.data.model.EmailSenderRuleEntity
import com.xestlab.assistant.data.model.Fact
import com.xestlab.assistant.data.model.Goal
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.model.ReminderSchedule
import com.xestlab.assistant.data.model.UnreadEmailEntity
import com.xestlab.assistant.data.model.learnedKeywordsList
import com.xestlab.assistant.data.model.tagsList
import com.xestlab.assistant.service.EmailPriorityEngine
import com.xestlab.assistant.service.TtsGreetingHelper
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
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
    val senderRules: Flow<List<EmailSenderRuleEntity>> = db.emailRuleDao().observeSenderRules()
    val customEmailTags: Flow<List<CustomEmailTagEntity>> = db.emailRuleDao().observeCustomTags()

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
        EmailPriorityEngine.learnPositiveInteraction(
            db = db,
            senderDisplay = email.sender,
            senderAddress = email.senderAddress,
            subject = email.subject,
            snippet = email.snippet
        )
        return id
    }

    suspend fun toggleEmailTag(email: UnreadEmailEntity, rawTag: String) {
        val cleanTag = rawTag.trim().replace(",", " ")
        if (cleanTag.isEmpty()) return

        val currentTags = email.tagsList.toMutableList()
        val existingIdx = currentTags.indexOfFirst { it.equals(cleanTag, ignoreCase = true) }
        val isRemoving = existingIdx >= 0

        if (isRemoving) {
            currentTags.removeAt(existingIdx)
        } else {
            currentTags.add(cleanTag)
        }
        val updatedCsv = EmailPriorityEngine.serializeTags(currentTags)

        if (!isRemoving && cleanTag.equals("Low", ignoreCase = true)) {
            db.emailDao().updateEmailTagsAndPriority(
                id = email.id,
                tagsCsv = updatedCsv,
                priority = "LOW",
                priorityReason = "Tagged Low by you"
            )
        } else if (!isRemoving) {
            db.emailDao().updateEmailTagsAndPriority(
                id = email.id,
                tagsCsv = updatedCsv,
                priority = "HIGH",
                priorityReason = "Tagged '$cleanTag' by you"
            )
            EmailPriorityEngine.learnPositiveInteraction(
                db = db,
                senderDisplay = email.sender,
                senderAddress = email.senderAddress,
                subject = email.subject,
                snippet = email.snippet
            )
        } else {
            db.emailDao().updateEmailTagsAndPriority(
                id = email.id,
                tagsCsv = updatedCsv,
                priority = email.priority,
                priorityReason = email.priorityReason
            )
            EmailPriorityEngine.recalculateCachedEmails(db)
        }
    }

    suspend fun createAndApplyCustomTag(email: UnreadEmailEntity, rawTag: String): String? {
        val cleanTag = rawTag.trim().replace(",", " ")
        if (cleanTag.isEmpty()) return null
        val isBuiltIn = EmailPriorityEngine.BUILT_IN_TAGS.any { it.equals(cleanTag, ignoreCase = true) }
        if (!isBuiltIn) {
            db.emailRuleDao().insertCustomTag(CustomEmailTagEntity(tagName = cleanTag))
        }
        toggleEmailTag(email, cleanTag)
        return cleanTag
    }

    suspend fun deleteCustomEmailTag(tagName: String) {
        db.emailRuleDao().deleteCustomTag(tagName)
    }

    /**
     * Toggles whether this email's sender is explicitly marked as a Priority (HIGH) sender.
     * @return true if the sender is now marked HIGH priority, false if reset to AUTO.
     */
    suspend fun togglePrioritySender(email: UnreadEmailEntity): Boolean {
        val key = EmailPriorityEngine.normalizeSenderKey(email.senderAddress, email.sender)
        if (key.isBlank()) return false
        val existing = db.emailRuleDao().getSenderRule(key)
        val newOverride = if (existing?.priorityOverride == "HIGH") "AUTO" else "HIGH"
        val updated = EmailSenderRuleEntity(
            senderKey = key,
            senderAddress = email.senderAddress.ifBlank { existing?.senderAddress.orEmpty() },
            senderDisplayName = email.sender.ifBlank { existing?.senderDisplayName.orEmpty() },
            priorityOverride = newOverride,
            dismissCount = if (newOverride == "HIGH") 0 else (existing?.dismissCount ?: 0),
            positiveCount = existing?.positiveCount ?: 0,
            learnedKeywordsCsv = existing?.learnedKeywordsCsv.orEmpty(),
            hasEmailedBefore = existing?.hasEmailedBefore ?: false,
            updatedAt = System.currentTimeMillis()
        )
        db.emailRuleDao().upsertSenderRule(updated)
        EmailPriorityEngine.recalculateCachedEmails(db)
        return newOverride == "HIGH"
    }

    /**
     * Dismisses an email card ONLY inside the app (never marks, moves, or deletes on IMAP).
     * Lowers the sender to LOW if 3 emails from the same sender have been dismissed.
     */
    suspend fun dismissEmailInApp(email: UnreadEmailEntity): Boolean {
        return EmailPriorityEngine.recordDismissalInAppOnly(db, email)
    }

    suspend fun markEmailReminderCreated(emailId: String) {
        db.emailDao().markReminderCreated(emailId)
    }

    suspend fun saveOrUpdateSenderRule(
        senderInput: String,
        displayNameInput: String,
        priorityOverride: String
    ) {
        val cleanInput = senderInput.trim()
        if (cleanInput.isBlank()) return
        val extractedAddr = EmailPriorityEngine.extractEmailAddress(cleanInput)
        val key = EmailPriorityEngine.normalizeSenderKey(extractedAddr, cleanInput)
        if (key.isBlank()) return
        val existing = db.emailRuleDao().getSenderRule(key)
        val normalizedPriority = priorityOverride.uppercase(Locale.US).let {
            if (it in setOf("HIGH", "NORMAL", "LOW", "AUTO")) it else "HIGH"
        }
        val rule = EmailSenderRuleEntity(
            senderKey = key,
            senderAddress = extractedAddr.ifBlank { existing?.senderAddress.orEmpty() },
            senderDisplayName = displayNameInput.trim().ifBlank {
                existing?.senderDisplayName?.ifBlank { cleanInput } ?: cleanInput
            },
            priorityOverride = normalizedPriority,
            dismissCount = if (normalizedPriority == "LOW") maxOf(existing?.dismissCount ?: 0, 0) else 0,
            positiveCount = existing?.positiveCount ?: 0,
            learnedKeywordsCsv = existing?.learnedKeywordsCsv.orEmpty(),
            hasEmailedBefore = existing?.hasEmailedBefore ?: false,
            updatedAt = System.currentTimeMillis()
        )
        db.emailRuleDao().upsertSenderRule(rule)
        EmailPriorityEngine.recalculateCachedEmails(db)
    }

    suspend fun updateSenderRulePriority(rule: EmailSenderRuleEntity, priorityOverride: String) {
        val normalized = priorityOverride.uppercase(Locale.US)
        val updated = rule.copy(
            priorityOverride = normalized,
            dismissCount = if (normalized == "HIGH" || normalized == "NORMAL") 0 else rule.dismissCount,
            updatedAt = System.currentTimeMillis()
        )
        db.emailRuleDao().upsertSenderRule(updated)
        EmailPriorityEngine.recalculateCachedEmails(db)
    }

    suspend fun deleteSenderRule(rule: EmailSenderRuleEntity) {
        db.emailRuleDao().deleteSenderRule(rule.senderKey)
        EmailPriorityEngine.recalculateCachedEmails(db)
    }

    suspend fun removeKeywordFromSenderRule(rule: EmailSenderRuleEntity, keyword: String) {
        val remaining = rule.learnedKeywordsList.filterNot { it.equals(keyword, ignoreCase = true) }
        db.emailRuleDao().upsertSenderRule(
            rule.copy(
                learnedKeywordsCsv = remaining.joinToString(","),
                updatedAt = System.currentTimeMillis()
            )
        )
        EmailPriorityEngine.recalculateCachedEmails(db)
    }

    suspend fun clearKeywordsOnSenderRule(rule: EmailSenderRuleEntity) {
        db.emailRuleDao().upsertSenderRule(
            rule.copy(
                learnedKeywordsCsv = "",
                updatedAt = System.currentTimeMillis()
            )
        )
        EmailPriorityEngine.recalculateCachedEmails(db)
    }

    /**
     * Spoken email summary: reads High priority unread emails first, then states the count of the rest.
     */
    suspend fun buildUnreadEmailSpokenSummary(): String {
        val activeUnread = db.emailDao().getActiveUnreadDirect(100)
        if (activeUnread.isEmpty()) {
            return "You have no unread emails right now. If IMAP is enabled in Settings, tap Check Now or wait for the next 30-minute check."
        }

        val highEmails = activeUnread
            .filter { it.priority == "HIGH" }
            .sortedByDescending { it.receivedAt }
        val normalCount = activeUnread.count { it.priority == "NORMAL" }
        val lowCount = activeUnread.count { it.priority == "LOW" }
        val restCount = normalCount + lowCount

        return if (highEmails.isNotEmpty()) {
            val highHeader = "You have ${highEmails.size} high-priority unread ${if (highEmails.size == 1) "email" else "emails"}. "
            val highReadout = highEmails.take(5).mapIndexed { index, email ->
                "${index + 1}: From ${email.sender}, subject: ${email.subject}"
            }.joinToString(". ")
            val moreHigh = if (highEmails.size > 5) {
                ", plus ${highEmails.size - 5} more high-priority"
            } else {
                ""
            }
            val restTail = if (restCount > 0) {
                " You also have $restCount other unread ${if (restCount == 1) "email" else "emails"} ($normalCount normal and $lowCount low priority)."
            } else {
                ""
            }
            "$highHeader$highReadout$moreHigh.$restTail"
        } else {
            "You have no high-priority unread emails, and $restCount other unread ${if (restCount == 1) "email" else "emails"} ($normalCount normal and $lowCount low priority)."
        }
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
     * Builds the time-aware spoken daily briefing from Room:
     * - Checks `Calendar.getInstance().get(Calendar.HOUR_OF_DAY)`:
     *   - 0..11:  "Good morning, King Israel."
     *   - 12..16: "Good afternoon, King Israel."
     *   - 17..23: "Good evening, King Israel."
     * - Queries Room for today's remaining tasks:
     *   - > 0 left: "You have [X] tasks remaining. Let's focus on [First Task]. What do you want to do first?"
     *   - 0 left:   "You have cleared all your tasks for today. Excellent work. You should rest."
     */
    suspend fun buildSpokenDailyPlan(
        overrideGreetingPrefix: String? = null,
        isWakeUpAlarm: Boolean = false
    ): String {
        val prefs = userPreferences.getSnapshot()
        val displayName = prefs.userName.ifBlank { "King Israel" }
        return TtsGreetingHelper.buildDynamicBriefing(db, displayName)
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
