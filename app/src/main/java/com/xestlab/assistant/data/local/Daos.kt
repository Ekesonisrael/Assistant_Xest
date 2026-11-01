package com.xestlab.assistant.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xestlab.assistant.data.model.ChecklistItem
import com.xestlab.assistant.data.model.ConversationTurn
import com.xestlab.assistant.data.model.DailyLog
import com.xestlab.assistant.data.model.Fact
import com.xestlab.assistant.data.model.Goal
import com.xestlab.assistant.data.model.Item
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
    @Query("SELECT * FROM conversation_turns ORDER BY timestamp DESC LIMIT 50")
    fun observeTurns(): Flow<List<ConversationTurn>>

    @Query("SELECT * FROM conversation_turns ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLastTurnsDirect(limit: Int): List<ConversationTurn>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: ConversationTurn)

    @Query("DELETE FROM conversation_turns WHERE id NOT IN (SELECT id FROM conversation_turns ORDER BY timestamp DESC LIMIT 50)")
    suspend fun trimToLastFifty()

    @Query("DELETE FROM conversation_turns")
    suspend fun clearHistory()
}

@Dao
interface EmailDao {
    @Query("SELECT * FROM unread_emails ORDER BY receivedAt DESC LIMIT 25")
    fun observeRecentUnread(): Flow<List<UnreadEmailEntity>>

    @Query("SELECT * FROM unread_emails ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun getRecentUnreadDirect(limit: Int = 25): List<UnreadEmailEntity>

    @Query("SELECT * FROM unread_emails WHERE id = :id LIMIT 1")
    suspend fun getEmailById(id: String): UnreadEmailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmails(emails: List<UnreadEmailEntity>)

    @Query("UPDATE unread_emails SET addedToTasks = 1 WHERE id = :id")
    suspend fun markAddedToTasks(id: String)

    @Query("UPDATE unread_emails SET notified = 1 WHERE id = :id")
    suspend fun markNotified(id: String)

    @Query("DELETE FROM unread_emails WHERE id NOT IN (:activeIds)")
    suspend fun pruneSyncedUnread(activeIds: List<String>)

    @Query("DELETE FROM unread_emails")
    suspend fun clearAllEmails()
}
