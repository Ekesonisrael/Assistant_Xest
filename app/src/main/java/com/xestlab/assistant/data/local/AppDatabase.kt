package com.xestlab.assistant.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xestlab.assistant.data.model.ChecklistItem
import com.xestlab.assistant.data.model.ConversationTurn
import com.xestlab.assistant.data.model.DailyLog
import com.xestlab.assistant.data.model.Fact
import com.xestlab.assistant.data.model.Goal
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.model.ReminderSchedule
import com.xestlab.assistant.data.model.UnreadEmailEntity
import com.xestlab.assistant.data.model.UserSettings
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
        ConversationTurn::class,
        UnreadEmailEntity::class
    ],
    version = 4,
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
    abstract fun emailDao(): EmailDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_settings ADD COLUMN wakeUpTime TEXT NOT NULL DEFAULT '07:30'")
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `unread_emails` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `sender` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `snippet` TEXT NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `isActionable` INTEGER NOT NULL,
                        `category` TEXT NOT NULL,
                        `extractedTaskTitle` TEXT,
                        `addedToTasks` INTEGER NOT NULL,
                        `notified` INTEGER NOT NULL,
                        `fetchedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `user_settings` ADD COLUMN `selectedVoiceName` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "UPDATE `user_settings` SET `voiceSpeed` = 1.0 WHERE ABS(`voiceSpeed` - 0.9) < 0.001"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                val instance = Room.databaseBuilder(
                    appContext,
                    AppDatabase::class.java,
                    "xestlab_assistant.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                seedDefaultData(getInstance(appContext))
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }

        suspend fun ensureSeeded(db: AppDatabase) {
            val existingChecklist = db.checklistDao().getAllItemsDirect()
            if (existingChecklist.isEmpty()) {
                seedDefaultData(db)
            }
        }

        private suspend fun seedDefaultData(db: AppDatabase) {
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

            val defaultChecklist = listOf(
                ChecklistItem("messages10", "10 personalised outreach messages sent", isCountType = true, defaultTargetCount = 10, orderIndex = 1),
                ChecklistItem("followups", "Follow-ups due today sent", isCountType = false, orderIndex = 2),
                ChecklistItem("content", "One content piece published", isCountType = false, orderIndex = 3),
                ChecklistItem("study", "One focused study block completed", isCountType = false, orderIndex = 4),
                ChecklistItem("leadsheet", "Lead sheet updated with next actions", isCountType = false, orderIndex = 5)
            )
            db.checklistDao().insertItems(defaultChecklist)

            val defaultReminders = listOf(
                ReminderSchedule("morning", "Morning briefing", "07:30", isRepeating = true, daysOfWeek = "ALL", voiceEnabled = true, isActive = true, isBuiltIn = true),
                ReminderSchedule("midday", "Midday nudge", "12:30", isRepeating = true, daysOfWeek = "ALL", voiceEnabled = true, isActive = true, isBuiltIn = true),
                ReminderSchedule("evening", "Evening check-in", "19:30", isRepeating = true, daysOfWeek = "ALL", voiceEnabled = true, isActive = true, isBuiltIn = true),
                ReminderSchedule("sunday", "Sunday review", "18:00", isRepeating = true, daysOfWeek = "SUN", voiceEnabled = true, isActive = true, isBuiltIn = true)
            )
            db.reminderDao().insertAll(defaultReminders)

            db.settingsDao().saveSettings(
                UserSettings(
                    id = 1,
                    userName = "",
                    wakeUpTime = "07:30",
                    quietHoursStart = "22:00",
                    quietHoursEnd = "07:00",
                    quietHoursEnabled = true,
                    voiceSpeed = 1.0f,
                    isFirstRunComplete = false,
                    selectedVoiceName = ""
                )
            )

            val initialItems = listOf(
                Item(
                    type = "TASK",
                    title = "Review priority leads and send 10 outreach messages",
                    details = "Focus on personalised outreach before afternoon calls.",
                    priority = "HIGH",
                    durationMinutes = 45,
                    dueAt = System.currentTimeMillis() + 2 * 3600000L,
                    status = "PENDING"
                ),
                Item(
                    type = "GOAL",
                    title = "Read 320 pages by Friday",
                    details = "Creative direction and brand positioning playbook.",
                    targetAmount = 320.0,
                    unit = "pages",
                    progress = 40.0,
                    dueAt = System.currentTimeMillis() + 5 * 86400000L,
                    status = "PENDING"
                )
            )
            db.itemDao().insertItems(initialItems)

            db.factDao().insertFact(
                Fact(text = "Prefers morning deep focus work before client calls.", category = "PREFERENCE")
            )
        }
    }
}
