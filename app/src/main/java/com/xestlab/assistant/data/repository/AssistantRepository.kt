package com.xestlab.assistant.data.repository

import android.content.Context
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
import com.xestlab.assistant.data.model.Lead
import com.xestlab.assistant.data.model.LeadEvent
import com.xestlab.assistant.data.model.MessageTemplateEntity
import com.xestlab.assistant.data.model.Payment
import com.xestlab.assistant.data.model.ReminderSchedule
import com.xestlab.assistant.data.model.UnreadEmailEntity
import com.xestlab.assistant.data.model.learnedKeywordsList
import com.xestlab.assistant.data.model.tagsList
import com.xestlab.assistant.service.AlarmScheduler
import com.xestlab.assistant.service.EmailPriorityEngine
import com.xestlab.assistant.service.LeadCadenceEngine
import com.xestlab.assistant.service.TtsGreetingHelper
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

data class WeeklySummaryData(
    val messagesCount: Int,
    val messagesTarget: Int,
    val conversationsCount: Int,
    val conversationsTarget: Int,
    val quotesCount: Int,
    val quotesTarget: Int,
    val winsCount: Int = 0,
    val weekCashNgn: Long = 0L,
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
    val leads: Flow<List<Lead>> = db.leadDao().observeAllLeads()
    val allLeadEvents: Flow<List<LeadEvent>> = db.leadDao().observeAllEvents()
    val messageTemplates: Flow<List<MessageTemplateEntity>> = db.messageTemplateDao().observeAllTemplates()
    val payments: Flow<List<Payment>> = db.paymentDao().observeAllPayments()

    fun getLogsForDate(date: String): Flow<List<DailyLog>> =
        db.dailyLogDao().observeLogsForDate(date)

    fun searchNotes(query: String): Flow<List<Item>> =
        db.itemDao().searchNotes(query)

    fun observeEventsForLead(leadId: Long): Flow<List<LeadEvent>> =
        db.leadDao().observeEventsForLead(leadId)

    suspend fun saveItem(item: Item): Long = db.itemDao().insertItem(item)

    /**
     * Writes a task directly into the Room `items` table that PlanScreen reads (`pendingItems`),
     * verifies the row actually exists with `getItemById`, schedules any due reminder,
     * and returns `Result.success(verified)` or `Result.failure(...)` if anything fails.
     */
    suspend fun createTaskVerified(
        context: Context,
        title: String,
        details: String = "",
        dueAt: Long? = null,
        durationMinutes: Int = 30,
        priority: String = "NORMAL",
        type: String = "TASK",
        sourceText: String = "coach_create_task"
    ): Result<Item> {
        val result = persistAndVerifyTaskInDao(
            itemDao = db.itemDao(),
            title = title,
            details = details,
            dueAt = dueAt,
            durationMinutes = durationMinutes,
            priority = priority,
            type = type,
            sourceText = sourceText
        )
        result.getOrNull()?.let { verified ->
            if (verified.dueAt != null) {
                AlarmScheduler.scheduleItemReminder(context, verified)
            }
        }
        return result
    }

    companion object {
        suspend fun persistAndVerifyTaskInDao(
            itemDao: com.xestlab.assistant.data.local.ItemDao,
            title: String,
            details: String = "",
            dueAt: Long? = null,
            durationMinutes: Int = 30,
            priority: String = "NORMAL",
            type: String = "TASK",
            sourceText: String = "coach_create_task"
        ): Result<Item> {
            val cleanTitle = title.trim()
            if (cleanTitle.isBlank()) {
                return Result.failure(IllegalArgumentException("Task title cannot be empty."))
            }
            return try {
                val normalizedPriority = when (priority.trim().uppercase(Locale.US)) {
                    "HIGH", "LOW", "NORMAL" -> priority.trim().uppercase(Locale.US)
                    else -> "NORMAL"
                }
                val itemToSave = Item(
                    type = type,
                    title = cleanTitle,
                    details = details.trim(),
                    dueAt = dueAt,
                    durationMinutes = durationMinutes.coerceAtLeast(0),
                    priority = normalizedPriority,
                    status = "PENDING",
                    sourceText = sourceText
                )
                val insertedId = itemDao.insertItem(itemToSave)
                if (insertedId <= 0L) {
                    return Result.failure(IllegalStateException("Database returned invalid task ID ($insertedId)."))
                }
                val verified = itemDao.getItemById(insertedId)
                if (verified == null || verified.id != insertedId || verified.status != "PENDING") {
                    return Result.failure(IllegalStateException("Saved task could not be verified in storage."))
                }
                Result.success(verified)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

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

    // --- Leads & Cadence ---

    suspend fun createLead(
        context: Context,
        name: String,
        service: String,
        phone: String = "",
        source: String = "",
        referredBy: String = "",
        observation: String = "",
        notes: String = "",
        startContactedCadence: Boolean = false
    ): Lead {
        val now = System.currentTimeMillis()
        val cleanName = name.trim()
        val normService = LeadCadenceEngine.normalizeService(service)
        val leadId = db.leadDao().insertLead(
            Lead(
                name = cleanName,
                phone = phone.trim(),
                source = source.trim(),
                referredBy = referredBy.trim(),
                stage = LeadCadenceEngine.STAGE_NEW,
                service = normService,
                observation = observation.trim(),
                notes = notes.trim(),
                createdAt = now
            )
        )
        db.leadDao().insertEvent(
            LeadEvent(
                leadId = leadId,
                type = "CREATED",
                text = "Added lead ($normService)${if (source.isNotBlank()) " via ${source.trim()}" else ""}.",
                at = now
            )
        )
        if (startContactedCadence) {
            LeadCadenceEngine.markLeadContacted(
                context = context,
                db = db,
                leadId = leadId,
                day0AlreadySent = false,
                nowMillis = now
            )
        }
        return db.leadDao().getLeadById(leadId) ?: Lead(id = leadId, name = cleanName, service = normService)
    }

    suspend fun updateLeadDetails(lead: Lead) {
        db.leadDao().updateLead(lead)
        val pending = db.itemDao().getPendingLeadFollowUpsDirect(lead.id)
        pending.forEach { item ->
            val stepKey = LeadCadenceEngine.parseFollowUpSourceText(item.sourceText)?.second
            val refreshedTitle = LeadCadenceEngine.buildDueSentence(lead.name, stepKey)
            db.itemDao().updateItem(
                item.copy(
                    title = refreshedTitle,
                    contactName = lead.name,
                    details = "Lead (${lead.service}): ${lead.observation.ifBlank { lead.notes }}".trim()
                )
            )
        }
    }

    suspend fun addLeadNoteEvent(leadId: Long, noteText: String) {
        val clean = noteText.trim()
        if (clean.isEmpty()) return
        db.leadDao().insertEvent(
            LeadEvent(
                leadId = leadId,
                type = "NOTE",
                text = clean,
                at = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteLead(context: Context, lead: Lead) {
        LeadCadenceEngine.cancelAllPendingForLead(context, db, lead.id)
        db.leadDao().deleteEventsForLead(lead.id)
        db.leadDao().deleteLead(lead)
        AlarmScheduler.syncAlarmTelemetry(context)
    }

    suspend fun findBestMatchingLead(nameQuery: String): Lead? {
        val clean = nameQuery.trim()
        if (clean.isEmpty()) return null
        db.leadDao().getLeadByExactName(clean)?.let { return it }
        val candidates = db.leadDao().findLeadsByName(clean)
        return candidates.firstOrNull {
            it.name.equals(clean, ignoreCase = true)
        } ?: candidates.firstOrNull()
    }

    // --- Message Templates ---

    suspend fun saveMessageTemplate(template: MessageTemplateEntity) {
        db.messageTemplateDao().upsertTemplate(
            template.copy(updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun resetMessageTemplatesToDefaults() {
        db.messageTemplateDao().upsertTemplates(LeadCadenceEngine.defaultTemplates())
    }

    // --- Payments ---

    suspend fun addPayment(
        clientName: String,
        amount: Long,
        type: String,
        note: String = "",
        paidAt: Long = System.currentTimeMillis(),
        explicitLeadId: Long? = null
    ): Payment {
        val matchedLead = if (explicitLeadId != null) {
            db.leadDao().getLeadById(explicitLeadId)
        } else {
            findBestMatchingLead(clientName)
        }
        val payment = Payment(
            clientName = clientName.trim(),
            leadId = matchedLead?.id,
            amount = amount,
            type = type.uppercase(Locale.US),
            paidAt = paidAt,
            note = note.trim()
        )
        val id = db.paymentDao().insertPayment(payment)
        if (matchedLead != null) {
            db.leadDao().insertEvent(
                LeadEvent(
                    leadId = matchedLead.id,
                    type = "PAYMENT",
                    text = "Logged payment of ₦${LeadCadenceEngine.formatWithCommas(amount)} (${payment.type}).",
                    at = paidAt
                )
            )
        }
        return payment.copy(id = id)
    }

    suspend fun updatePayment(payment: Payment) {
        db.paymentDao().updatePayment(payment)
    }

    suspend fun deletePayment(payment: Payment) {
        db.paymentDao().deletePayment(payment)
    }

    // --- Email Priority & Learning ---

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

    /**
     * Automatically aggregates weekly targets (50 messages, 5 conversations, 3 quotes) plus wins & cash
     * from Lead / LeadEvent / Payment records AND daily checklist logs without requiring double entry.
     */
    suspend fun getWeeklySummary(weekStart: String, weekEnd: String): WeeklySummaryData {
        val goal = db.goalDao().getGoalsDirect() ?: Goal()
        val logs = db.dailyLogDao().getLogsBetweenDates(weekStart, weekEnd)

        var checklistMessages = 0
        var checklistFollowups = 0
        var checklistLeadsheet = 0
        var content = 0
        var study = 0

        logs.forEach { log ->
            if (log.isDone) {
                when (log.itemId) {
                    "messages10" -> checklistMessages += if (log.countValue > 0) log.countValue else 10
                    "followups" -> checklistFollowups += 1
                    "leadsheet" -> checklistLeadsheet += 1
                    "content" -> content += 1
                    "study" -> study += 1
                }
            }
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val startMs = try {
            LeadCadenceEngine.startOfDayMillis(sdf.parse(weekStart)?.time ?: System.currentTimeMillis())
        } catch (_: Exception) {
            System.currentTimeMillis() - 6L * 86_400_000L
        }
        val endMs = try {
            LeadCadenceEngine.endOfDayMillis(sdf.parse(weekEnd)?.time ?: System.currentTimeMillis())
        } catch (_: Exception) {
            System.currentTimeMillis()
        }

        val weekEvents = db.leadDao().getEventsBetweenDirect(startMs, endMs)
        val allLeads = db.leadDao().getAllLeadsDirect()
        val weekCash = db.paymentDao().sumPaymentsBetweenDirect(startMs, endMs)

        // Messages sent from Leads: every MESSAGE_SENT event + any CONTACTED event for leads without a MESSAGE_SENT event yet
        val messageSentEvents = weekEvents.count { it.type == "MESSAGE_SENT" }
        val messageSentLeadIds = weekEvents.filter { it.type == "MESSAGE_SENT" }.map { it.leadId }.toSet()
        val contactedOnlyLeadIds = weekEvents
            .filter { it.type == "CONTACTED" && it.leadId !in messageSentLeadIds }
            .map { it.leadId }
            .toSet()
        val leadMessagesCount = messageSentEvents + contactedOnlyLeadIds.size

        // Conversations: distinct leads with REPLIED event this week (or imported active replied leads touched this week)
        val repliedLeadIds = weekEvents
            .filter { it.type == "REPLIED" }
            .map { it.leadId }
            .toMutableSet()
        allLeads.forEach { lead ->
            val touchedAt = lead.lastContactAt ?: lead.createdAt
            if (touchedAt in startMs..endMs && lead.stage in setOf(
                    LeadCadenceEngine.STAGE_REPLIED,
                    LeadCadenceEngine.STAGE_QUOTED,
                    LeadCadenceEngine.STAGE_WON
                )
            ) {
                repliedLeadIds.add(lead.id)
            }
        }

        // Quotes: distinct leads with QUOTED event this week (or leads in QUOTED/WON with quoteAmount touched this week)
        val quotedLeadIds = weekEvents
            .filter { it.type == "QUOTED" }
            .map { it.leadId }
            .toMutableSet()
        allLeads.forEach { lead ->
            val touchedAt = lead.lastContactAt ?: lead.createdAt
            if (touchedAt in startMs..endMs &&
                (lead.stage == LeadCadenceEngine.STAGE_QUOTED || (lead.stage == LeadCadenceEngine.STAGE_WON && lead.quoteAmount != null))
            ) {
                quotedLeadIds.add(lead.id)
            }
        }

        // Wins: distinct leads marked WON this week
        val wonLeadIds = weekEvents
            .filter { it.type == "WON" }
            .map { it.leadId }
            .toMutableSet()
        allLeads.forEach { lead ->
            val touchedAt = lead.lastContactAt ?: lead.createdAt
            if (touchedAt in startMs..endMs && lead.stage == LeadCadenceEngine.STAGE_WON) {
                wonLeadIds.add(lead.id)
            }
        }

        val totalMessages = leadMessagesCount + checklistMessages
        val totalConversations = if (allLeads.isNotEmpty() || repliedLeadIds.isNotEmpty()) {
            repliedLeadIds.size
        } else {
            checklistFollowups
        }
        val totalQuotes = if (allLeads.isNotEmpty() || quotedLeadIds.isNotEmpty()) {
            quotedLeadIds.size
        } else {
            (checklistLeadsheet / 2).coerceAtLeast(0)
        }

        return WeeklySummaryData(
            messagesCount = totalMessages,
            messagesTarget = goal.weeklyMessagesTarget,
            conversationsCount = totalConversations,
            conversationsTarget = goal.weeklyConversationsTarget,
            quotesCount = totalQuotes,
            quotesTarget = goal.weeklyQuotesTarget,
            winsCount = wonLeadIds.size,
            weekCashNgn = weekCash,
            contentCount = content,
            studyCount = study
        )
    }

    suspend fun buildSpokenDailyPlan(
        overrideGreetingPrefix: String? = null,
        isWakeUpAlarm: Boolean = false,
        includeGreeting: Boolean = true
    ): String {
        val prefs = userPreferences.getSnapshot()
        val displayName = prefs.userName.ifBlank { "Israel" }
        val body = TtsGreetingHelper.buildDynamicBriefing(
            db = db,
            userName = displayName,
            includeGreeting = if (!overrideGreetingPrefix.isNullOrBlank()) false else includeGreeting
        )
        return if (!overrideGreetingPrefix.isNullOrBlank()) {
            "${overrideGreetingPrefix.trim()} $body".trim()
        } else {
            body
        }
    }

    suspend fun exportJson(): String {
        val prefs = userPreferences.getSnapshot()
        val goal = db.goalDao().getGoalsDirect() ?: Goal()
        val logs = db.dailyLogDao().getAllLogs()
        val items = db.itemDao().getAllItemsDirect()
        val facts = db.factDao().getAllFactsDirect()
        val leadsList = db.leadDao().getAllLeadsDirect()
        val leadEventsList = db.leadDao().getAllEventsDirect()
        val paymentsList = db.paymentDao().getAllPaymentsDirect()
        val templatesList = db.messageTemplateDao().getAllTemplatesDirect()

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
            put("websitePrice", goal.websitePrice)
            put("retainerPrice", goal.retainerPrice)
            put("monthlyTarget", goal.monthlyTarget)
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

        val leadsArr = JSONArray()
        leadsList.forEach { lead ->
            leadsArr.put(
                JSONObject().apply {
                    put("id", lead.id)
                    put("name", lead.name)
                    put("phone", lead.phone)
                    put("source", lead.source)
                    put("referredBy", lead.referredBy)
                    put("stage", lead.stage)
                    put("service", lead.service)
                    put("observation", lead.observation)
                    put("quoteAmount", lead.quoteAmount ?: JSONObject.NULL)
                    put("notes", lead.notes)
                    put("createdAt", lead.createdAt)
                    put("lastContactAt", lead.lastContactAt ?: JSONObject.NULL)
                    put("nextActionAt", lead.nextActionAt ?: JSONObject.NULL)
                    put("nextActionType", lead.nextActionType ?: JSONObject.NULL)
                }
            )
        }
        root.put("leads", leadsArr)

        val eventsArr = JSONArray()
        leadEventsList.forEach { ev ->
            eventsArr.put(
                JSONObject().apply {
                    put("id", ev.id)
                    put("leadId", ev.leadId)
                    put("type", ev.type)
                    put("text", ev.text)
                    put("at", ev.at)
                }
            )
        }
        root.put("leadEvents", eventsArr)

        val paymentsArr = JSONArray()
        paymentsList.forEach { p ->
            paymentsArr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("clientName", p.clientName)
                    put("leadId", p.leadId ?: JSONObject.NULL)
                    put("amount", p.amount)
                    put("type", p.type)
                    put("paidAt", p.paidAt)
                    put("note", p.note)
                }
            )
        }
        root.put("payments", paymentsArr)

        val templatesArr = JSONArray()
        templatesList.forEach { tpl ->
            templatesArr.put(
                JSONObject().apply {
                    put("stepKey", tpl.stepKey)
                    put("title", tpl.title)
                    put("bodyTemplate", tpl.bodyTemplate)
                    put("orderIndex", tpl.orderIndex)
                    put("updatedAt", tpl.updatedAt)
                }
            )
        }
        root.put("messageTemplates", templatesArr)

        return root.toString(2)
    }

    suspend fun importJson(jsonString: String): Boolean {
        return WebTrackerImporter.importData(db, userPreferences, jsonString)
    }
}
