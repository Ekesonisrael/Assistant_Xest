package com.xestlab.assistant.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
import com.xestlab.assistant.data.model.UserSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE id = 1 LIMIT 1")
    fun observeGoals(): Flow<Goal?>

    @Query("SELECT * FROM goals WHERE id = 1 LIMIT 1")
    suspend fun getGoalsDirect(): Goal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGoals(goal: Goal)
}

@Dao
interface ChecklistDao {
    @Query("SELECT * FROM checklist_items ORDER BY orderIndex ASC")
    fun observeAllItems(): Flow<List<ChecklistItem>>

    @Query("SELECT * FROM checklist_items ORDER BY orderIndex ASC")
    suspend fun getAllItemsDirect(): List<ChecklistItem>

    @Query(
        """
        SELECT * FROM checklist_items
        WHERE id NOT IN (
            SELECT itemId FROM daily_logs WHERE date = :date AND isDone = 1
        )
        ORDER BY orderIndex ASC
        """
    )
    suspend fun getRemainingChecklistForDateDirect(date: String): List<ChecklistItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ChecklistItem>)
}

@Dao
interface DailyLogDao {
    @Query("SELECT * FROM daily_logs WHERE date = :date")
    fun observeLogsForDate(date: String): Flow<List<DailyLog>>

    @Query("SELECT * FROM daily_logs WHERE date = :date")
    suspend fun getLogsForDateDirect(date: String): List<DailyLog>

    @Query("SELECT * FROM daily_logs WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getLogsBetweenDates(startDate: String, endDate: String): List<DailyLog>

    @Query("SELECT * FROM daily_logs")
    suspend fun getAllLogs(): List<DailyLog>

    @Query("DELETE FROM daily_logs WHERE date = :date AND itemId = :itemId")
    suspend fun deleteForDateAndItem(date: String, itemId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: DailyLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<DailyLog>)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder_schedules ORDER BY timeString ASC")
    fun observeReminders(): Flow<List<ReminderSchedule>>

    @Query("SELECT * FROM reminder_schedules ORDER BY timeString ASC")
    suspend fun getAllRemindersDirect(): List<ReminderSchedule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveReminder(reminder: ReminderSchedule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<ReminderSchedule>)

    @Delete
    suspend fun deleteReminder(reminder: ReminderSchedule)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM user_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<UserSettings?>

    @Query("SELECT * FROM user_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettingsDirect(): UserSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: UserSettings)
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY createdAt DESC")
    fun observeAllItems(): Flow<List<Item>>

    @Query("SELECT * FROM items ORDER BY createdAt DESC")
    suspend fun getAllItemsDirect(): List<Item>

    @Query("SELECT * FROM items WHERE status = 'PENDING' AND type != 'NOTE' ORDER BY CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC, createdAt DESC")
    fun observePendingItems(): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE status = 'PENDING' AND type != 'NOTE' ORDER BY CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC, createdAt DESC")
    suspend fun getPendingItemsDirect(): List<Item>

    @Query(
        """
        SELECT * FROM items
        WHERE status = 'PENDING'
          AND type NOT IN ('NOTE', 'GOAL')
          AND sourceText NOT LIKE 'web_pipeline_%'
          AND (dueAt IS NULL OR dueAt <= :endOfTodayMillis)
        ORDER BY
          CASE WHEN priority = 'HIGH' THEN 0 ELSE 1 END ASC,
          CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END ASC,
          dueAt ASC,
          createdAt DESC
        """
    )
    suspend fun getRemainingTasksForTodayDirect(endOfTodayMillis: Long): List<Item>

    @Query("SELECT * FROM items WHERE status = 'PENDING' AND sourceText LIKE 'lead_followup:' || :leadId || ':%' ORDER BY CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC")
    suspend fun getPendingLeadFollowUpsDirect(leadId: Long): List<Item>

    @Query("SELECT * FROM items WHERE status = 'PENDING' AND sourceText LIKE 'lead_followup:%' ORDER BY CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC")
    suspend fun getAllPendingLeadFollowUpsDirect(): List<Item>

    @Query("SELECT * FROM items WHERE type = 'NOTE' AND (title LIKE '%' || :query || '%' OR details LIKE '%' || :query || '%') ORDER BY createdAt DESC")
    fun searchNotes(query: String): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: Long): Item?

    @Query("SELECT * FROM items WHERE status = 'PENDING' AND title LIKE '%' || :titleQuery || '%' LIMIT 1")
    suspend fun getItemByTitle(titleQuery: String): Item?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: Item): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<Item>)

    @Update
    suspend fun updateItem(item: Item)

    @Query("UPDATE items SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Delete
    suspend fun deleteItem(item: Item)
}

@Dao
interface FactDao {
    @Query("SELECT * FROM facts ORDER BY createdAt DESC")
    fun observeAllFacts(): Flow<List<Fact>>

    @Query("SELECT * FROM facts ORDER BY createdAt DESC")
    suspend fun getAllFactsDirect(): List<Fact>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFact(fact: Fact): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFacts(facts: List<Fact>)

    @Delete
    suspend fun deleteFact(fact: Fact)

    @Query("DELETE FROM facts")
    suspend fun clearAllFacts()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation_turns ORDER BY timestamp DESC, id DESC LIMIT 50")
    fun observeTurns(): Flow<List<ConversationTurn>>

    @Query("SELECT * FROM conversation_turns ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getLastTurnsDirect(limit: Int): List<ConversationTurn>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: ConversationTurn)

    @Query("DELETE FROM conversation_turns WHERE id NOT IN (SELECT id FROM conversation_turns ORDER BY timestamp DESC, id DESC LIMIT 50)")
    suspend fun trimToLastFifty()

    @Query("DELETE FROM conversation_turns")
    suspend fun clearHistory()
}

@Dao
interface EmailDao {
    @Query("SELECT * FROM unread_emails ORDER BY receivedAt DESC LIMIT 100")
    fun observeRecentUnread(): Flow<List<UnreadEmailEntity>>

    @Query("SELECT * FROM unread_emails ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun getRecentUnreadDirect(limit: Int = 100): List<UnreadEmailEntity>

    @Query("SELECT * FROM unread_emails WHERE dismissedInApp = 0 ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun getActiveUnreadDirect(limit: Int = 100): List<UnreadEmailEntity>

    @Query("SELECT * FROM unread_emails WHERE id = :id LIMIT 1")
    suspend fun getEmailById(id: String): UnreadEmailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmail(email: UnreadEmailEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmails(emails: List<UnreadEmailEntity>)

    @Query("UPDATE unread_emails SET addedToTasks = 1 WHERE id = :id")
    suspend fun markAddedToTasks(id: String)

    @Query("UPDATE unread_emails SET notified = 1 WHERE id = :id")
    suspend fun markNotified(id: String)

    @Query("UPDATE unread_emails SET dismissedInApp = 1 WHERE id = :id")
    suspend fun markDismissedInApp(id: String)

    @Query("UPDATE unread_emails SET reminderCreated = 1 WHERE id = :id")
    suspend fun markReminderCreated(id: String)

    @Query("UPDATE unread_emails SET tagsCsv = :tagsCsv, priority = :priority, priorityReason = :priorityReason WHERE id = :id")
    suspend fun updateEmailTagsAndPriority(
        id: String,
        tagsCsv: String,
        priority: String,
        priorityReason: String
    )

    @Query("DELETE FROM unread_emails WHERE id NOT IN (:activeIds)")
    suspend fun pruneSyncedUnread(activeIds: List<String>)

    @Query("DELETE FROM unread_emails")
    suspend fun clearAllEmails()
}

@Dao
interface EmailRuleDao {
    @Query("SELECT * FROM email_sender_rules ORDER BY updatedAt DESC")
    fun observeSenderRules(): Flow<List<EmailSenderRuleEntity>>

    @Query("SELECT * FROM email_sender_rules ORDER BY updatedAt DESC")
    suspend fun getAllSenderRulesDirect(): List<EmailSenderRuleEntity>

    @Query("SELECT * FROM email_sender_rules WHERE senderKey = :senderKey LIMIT 1")
    suspend fun getSenderRule(senderKey: String): EmailSenderRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSenderRule(rule: EmailSenderRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSenderRules(rules: List<EmailSenderRuleEntity>)

    @Query("DELETE FROM email_sender_rules WHERE senderKey = :senderKey")
    suspend fun deleteSenderRule(senderKey: String)

    @Query("SELECT * FROM email_custom_tags ORDER BY createdAt ASC")
    fun observeCustomTags(): Flow<List<CustomEmailTagEntity>>

    @Query("SELECT * FROM email_custom_tags ORDER BY createdAt ASC")
    suspend fun getAllCustomTagsDirect(): List<CustomEmailTagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomTag(tag: CustomEmailTagEntity)

    @Query("DELETE FROM email_custom_tags WHERE tagName = :tagName")
    suspend fun deleteCustomTag(tagName: String)
}

@Dao
interface LeadDao {
    @Query("SELECT * FROM leads ORDER BY CASE WHEN nextActionAt IS NULL THEN 1 ELSE 0 END ASC, nextActionAt ASC, createdAt DESC")
    fun observeAllLeads(): Flow<List<Lead>>

    @Query("SELECT * FROM leads ORDER BY CASE WHEN nextActionAt IS NULL THEN 1 ELSE 0 END ASC, nextActionAt ASC, createdAt DESC")
    suspend fun getAllLeadsDirect(): List<Lead>

    @Query("SELECT * FROM leads WHERE id = :id LIMIT 1")
    suspend fun getLeadById(id: Long): Lead?

    @Query("SELECT * FROM leads WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getLeadByExactName(name: String): Lead?

    @Query("SELECT * FROM leads WHERE LOWER(name) LIKE '%' || LOWER(:query) || '%' ORDER BY createdAt DESC")
    suspend fun findLeadsByName(query: String): List<Lead>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLead(lead: Lead): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLeads(leads: List<Lead>)

    @Update
    suspend fun updateLead(lead: Lead)

    @Delete
    suspend fun deleteLead(lead: Lead)

    @Query("SELECT * FROM lead_events ORDER BY at DESC")
    fun observeAllEvents(): Flow<List<LeadEvent>>

    @Query("SELECT * FROM lead_events WHERE leadId = :leadId ORDER BY at DESC")
    fun observeEventsForLead(leadId: Long): Flow<List<LeadEvent>>

    @Query("SELECT * FROM lead_events WHERE leadId = :leadId ORDER BY at DESC")
    suspend fun getEventsForLeadDirect(leadId: Long): List<LeadEvent>

    @Query("SELECT * FROM lead_events ORDER BY at DESC")
    suspend fun getAllEventsDirect(): List<LeadEvent>

    @Query("SELECT * FROM lead_events WHERE at BETWEEN :startMillis AND :endMillis ORDER BY at DESC")
    suspend fun getEventsBetweenDirect(startMillis: Long, endMillis: Long): List<LeadEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: LeadEvent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<LeadEvent>)

    @Query("DELETE FROM lead_events WHERE leadId = :leadId")
    suspend fun deleteEventsForLead(leadId: Long)
}

@Dao
interface MessageTemplateDao {
    @Query("SELECT * FROM message_templates ORDER BY orderIndex ASC")
    fun observeAllTemplates(): Flow<List<MessageTemplateEntity>>

    @Query("SELECT * FROM message_templates ORDER BY orderIndex ASC")
    suspend fun getAllTemplatesDirect(): List<MessageTemplateEntity>

    @Query("SELECT * FROM message_templates WHERE stepKey = :stepKey LIMIT 1")
    suspend fun getTemplateDirect(stepKey: String): MessageTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(template: MessageTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplates(templates: List<MessageTemplateEntity>)
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments ORDER BY paidAt DESC")
    fun observeAllPayments(): Flow<List<Payment>>

    @Query("SELECT * FROM payments ORDER BY paidAt DESC")
    suspend fun getAllPaymentsDirect(): List<Payment>

    @Query("SELECT * FROM payments WHERE paidAt BETWEEN :startMillis AND :endMillis ORDER BY paidAt DESC")
    suspend fun getPaymentsBetweenDirect(startMillis: Long, endMillis: Long): List<Payment>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM payments WHERE paidAt BETWEEN :startMillis AND :endMillis")
    suspend fun sumPaymentsBetweenDirect(startMillis: Long, endMillis: Long): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: Payment): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayments(payments: List<Payment>)

    @Update
    suspend fun updatePayment(payment: Payment)

    @Delete
    suspend fun deletePayment(payment: Payment)
}
