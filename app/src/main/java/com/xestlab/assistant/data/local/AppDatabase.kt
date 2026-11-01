package com.xestlab.assistant.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xestlab.assistant.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        Goal::class,
        ChecklistItem::class,
        DailyLog::class,
        ReminderSchedule::class,
        UserSettings::class,
        Item::class,
        Fact::class,
        ConversationTurn::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun goalDao(): GoalDao
    abstract fun checklistDao(): ChecklistDao
    abstract fun dailyLogDao(): DailyLogDao
    abstract fun reminderDao(): ReminderDao
    abstract fun settingsDao(): SettingsDao
    abstract fun itemDao(): ItemDao
    abstract fun factDao(): FactDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Non-destructive: add wakeUpTime column to user_settings table
                db.execSQL("ALTER TABLE user_settings ADD COLUMN wakeUpTime TEXT NOT NULL DEFAULT '06:30'")

                // Create generic items table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `details` TEXT NOT NULL,
                        `dueAt` INTEGER,
                        `durationMinutes` INTEGER NOT NULL,
                        `priority` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `repeatRule` TEXT,
                        `targetAmount` REAL,
                        `unit` TEXT,
                        `progress` REAL NOT NULL,
                        `contactName` TEXT,
                        `sourceText` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // Create facts memory table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `facts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `text` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // Create conversation history table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_turns` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sender` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `actionsJson` TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xestlab_assistant.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                seedDefaultData(getInstance(context))
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private suspend fun seedDefaultData(db: AppDatabase) {
            // Default Goal
            db.goalDao().saveGoals(
                Goal(
                    id = 1,
                    yearlyGoal = 10000000L,
                    startDate = "2026-11-01",
                    q1Target = 1500000L,
                    q2Target = 2400000L,
                    q3Target = 3200000L,
                    q4Target = 3500000L,
                    weeklyMessagesTarget = 50,
                    weeklyConversationsTarget = 5,
                    weeklyQuotesTarget = 3
                )
            )

            // Default Checklist Items
            val defaultItems = listOf(
                ChecklistItem("messages10", "10 personalised messages sent", isCountType = true, defaultTargetCount = 10, orderIndex = 1),
                ChecklistItem("followups", "Follow-ups due sent", isCountType = false, orderIndex = 2),
                ChecklistItem("content", "One content post", isCountType = false, orderIndex = 3),
                ChecklistItem("study", "One study block", isCountType = false, orderIndex = 4),
                ChecklistItem("leadsheet", "Lead sheet updated", isCountType = false, orderIndex = 5)
            )
            db.checklistDao().insertItems(defaultItems)

            // Default Reminder Schedules
            val defaultReminders = listOf(
                ReminderSchedule("morning", "Morning briefing", "07:30", isRepeating = true, daysOfWeek = "ALL", voiceEnabled = true, isActive = true, isBuiltIn = true),
                ReminderSchedule("midday", "Midday nudge", "12:30", isRepeating = true, daysOfWeek = "ALL", voiceEnabled = true, isActive = true, isBuiltIn = true),
                ReminderSchedule("evening", "Evening check-in", "19:30", isRepeating = true, daysOfWeek = "ALL", voiceEnabled = true, isActive = true, isBuiltIn = true),
                ReminderSchedule("sunday", "Sunday review", "18:00", isRepeating = true, daysOfWeek = "SUN", voiceEnabled = true, isActive = true, isBuiltIn = true)
            )
            db.reminderDao().insertAll(defaultReminders)

            // Default User Settings
            db.settingsDao().saveSettings(
                UserSettings(
                    id = 1,
                    userName = "Studio Lead",
                    wakeUpTime = "06:30",
                    quietHoursStart = "22:00",
                    quietHoursEnd = "07:00",
                    quietHoursEnabled = true,
                    voiceSpeed = 1.0f,
                    isFirstRunComplete = false
                )
            )

            // Initial generic items for demonstration
            val initialItems = listOf(
                Item(
                    type = "TASK",
                    title = "Send 10 outreach messages",
                    details = "Personalized messages to prospective corporate and wedding clients.",
                    priority = "HIGH",
                    durationMinutes = 45,
                    status = "PENDING"
                ),
                Item(
                    type = "GOAL",
                    title = "Read 320 pages by Friday",
                    details = "Creative studio direction and brand strategy.",
                    targetAmount = 320.0,
                    unit = "pages",
                    progress = 40.0,
                    dueAt = System.currentTimeMillis() + 5 * 86400000L,
                    status = "PENDING"
                ),
                Item(
                    type = "NOTE",
                    title = "Studio lighting arrangement",
                    details = "Use 3-point softbox setup for afternoon interview sessions.",
                    status = "PENDING"
                )
            )
            db.itemDao().insertItems(initialItems)

            // Initial memory fact
            db.factDao().insertFact(
                Fact(text = "Prefers morning focus work before client messages.", category = "PREFERENCE")
            )
        }
    }
}
