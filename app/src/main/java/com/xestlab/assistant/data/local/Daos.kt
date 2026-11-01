package com.xestlab.assistant.data.local

import androidx.room.*
import com.xestlab.assistant.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE id = 1 LIMIT 1")
    fun getGoals(): Flow<Goal?>

    @Query("SELECT * FROM goals WHERE id = 1 LIMIT 1")
    suspend fun getGoalsDirect(): Goal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGoals(goal: Goal)
}

@Dao
interface ChecklistDao {
    @Query("SELECT * FROM checklist_items ORDER BY orderIndex ASC")
    fun getAllItems(): Flow<List<ChecklistItem>>

    @Query("SELECT * FROM checklist_items ORDER BY orderIndex ASC")
    suspend fun getAllItemsDirect(): List<ChecklistItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ChecklistItem>)
}

@Dao
interface DailyLogDao {
    @Query("SELECT * FROM daily_logs WHERE date = :date ORDER BY timestamp DESC")
    fun getLogsForDate(date: String): Flow<List<DailyLog>>

    @Query("SELECT * FROM daily_logs WHERE date = :date")
    suspend fun getLogsForDateDirect(date: String): List<DailyLog>

    @Query("SELECT * FROM daily_logs WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getLogsBetweenDates(startDate: String, endDate: String): List<DailyLog>

    @Query("SELECT * FROM daily_logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<DailyLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: DailyLog)

    @Query("DELETE FROM daily_logs WHERE date = :date AND itemId = :itemId")
    suspend fun deleteLogForItem(date: String, itemId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<DailyLog>)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder_schedules WHERE isActive = 1")
    fun getActiveReminders(): Flow<List<ReminderSchedule>>

    @Query("SELECT * FROM reminder_schedules")
    fun getAllReminders(): Flow<List<ReminderSchedule>>

    @Query("SELECT * FROM reminder_schedules")
    suspend fun getAllRemindersDirect(): List<ReminderSchedule>

    @Query("SELECT * FROM reminder_schedules WHERE id = :id LIMIT 1")
    suspend fun getReminderById(id: String): ReminderSchedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderSchedule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<ReminderSchedule>)

    @Delete
    suspend fun deleteReminder(reminder: ReminderSchedule)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM user_settings WHERE id = 1 LIMIT 1")
    fun getSettings(): Flow<UserSettings?>

    @Query("SELECT * FROM user_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettingsDirect(): UserSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: UserSettings)
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY createdAt DESC")
    fun getAllItems(): Flow<List<Item>>

    @Query("SELECT * FROM items ORDER BY createdAt DESC")
    suspend fun getAllItemsDirect(): List<Item>

    @Query("SELECT * FROM items WHERE status = 'PENDING' ORDER BY dueAt ASC, priority DESC")
    fun getPendingItems(): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE status = 'PENDING' ORDER BY dueAt ASC, priority DESC")
    suspend fun getPendingItemsDirect(): List<Item>

    @Query("SELECT * FROM items WHERE type = :type ORDER BY createdAt DESC")
    fun getItemsByType(type: String): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE type = 'NOTE' AND (title LIKE '%' || :query || '%' OR details LIKE '%' || :query || '%') ORDER BY createdAt DESC")
    fun searchNotes(query: String): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: Long): Item?

    @Query("SELECT * FROM items WHERE title = :title LIMIT 1")
    suspend fun getItemByTitle(title: String): Item?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: Item): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<Item>)

    @Update
    suspend fun updateItem(item: Item)

    @Delete
    suspend fun deleteItem(item: Item)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteItemById(id: Long)

    @Query("UPDATE items SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE items SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Double)
}

@Dao
interface FactDao {
    @Query("SELECT * FROM facts ORDER BY createdAt DESC")
    fun getAllFacts(): Flow<List<Fact>>

    @Query("SELECT * FROM facts ORDER BY createdAt DESC")
    suspend fun getAllFactsDirect(): List<Fact>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFact(fact: Fact): Long

    @Delete
    suspend fun deleteFact(fact: Fact)

    @Query("DELETE FROM facts WHERE id = :id")
    suspend fun deleteFactById(id: Long)

    @Query("DELETE FROM facts")
    suspend fun clearAllFacts()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation_turns ORDER BY timestamp DESC LIMIT :limit")
    fun getLastTurns(limit: Int = 50): Flow<List<ConversationTurn>>

    @Query("SELECT * FROM conversation_turns ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLastTurnsDirect(limit: Int = 50): List<ConversationTurn>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: ConversationTurn): Long

    @Query("DELETE FROM conversation_turns")
    suspend fun clearAllTurns()

    @Query("DELETE FROM conversation_turns WHERE id NOT IN (SELECT id FROM conversation_turns ORDER BY timestamp DESC LIMIT :limit)")
    suspend fun trimToLast(limit: Int = 50)
}
