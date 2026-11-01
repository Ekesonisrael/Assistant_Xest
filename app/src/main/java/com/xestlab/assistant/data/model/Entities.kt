package com.xestlab.assistant.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey val id: Int = 1,
    val yearlyGoal: Long = 10000000L,
    val startDate: String = "2026-11-01",
    val q1Target: Long = 1500000L,
    val q2Target: Long = 2400000L,
    val q3Target: Long = 3200000L,
    val q4Target: Long = 3500000L,
    val weeklyMessagesTarget: Int = 50,
    val weeklyConversationsTarget: Int = 5,
    val weeklyQuotesTarget: Int = 3
)

@Entity(tableName = "checklist_items")
data class ChecklistItem(
    @PrimaryKey val id: String,
    val title: String,
    val isCountType: Boolean = false,
    val defaultTargetCount: Int = 0,
    val orderIndex: Int = 0
)

@Entity(tableName = "daily_logs")
data class DailyLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String, // format: yyyy-MM-dd
    val itemId: String,
    val isDone: Boolean,
    val countValue: Int = 0,
    val reasonNotDone: String? = null, // "busy", "forgot", "avoided it", "other"
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminder_schedules")
data class ReminderSchedule(
    @PrimaryKey val id: String,
    val title: String,
    val timeString: String, // HH:mm format, e.g. "07:30"
    val isRepeating: Boolean = true,
    val daysOfWeek: String = "ALL", // "ALL" or comma-separated days e.g. "SUN"
    val voiceEnabled: Boolean = true,
    val isActive: Boolean = true,
    val isBuiltIn: Boolean = true
)

@Entity(tableName = "user_settings")
data class UserSettings(
    @PrimaryKey val id: Int = 1,
    val userName: String = "Studio Lead",
    val wakeUpTime: String = "06:30",
    val quietHoursStart: String = "22:00",
    val quietHoursEnd: String = "07:00",
    val quietHoursEnabled: Boolean = true,
    val voiceSpeed: Float = 1.0f,
    val isFirstRunComplete: Boolean = false
)

/**
 * Generic item representation covering tasks, goals, notes, reminders, and habits.
 */
@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // "TASK", "GOAL", "NOTE", "REMINDER", "HABIT"
    val title: String,
    val details: String = "",
    val dueAt: Long? = null,
    val durationMinutes: Int = 0,
    val priority: String = "NORMAL", // "LOW", "NORMAL", "HIGH", "URGENT"
    val status: String = "PENDING", // "PENDING", "COMPLETED", "DROPPED"
    val repeatRule: String? = null, // "DAILY", "WEEKLY", "MONTHLY", null
    val targetAmount: Double? = null,
    val unit: String? = null, // e.g. "pages", "messages", "hours"
    val progress: Double = 0.0,
    val contactName: String? = null,
    val sourceText: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val isCompleted: Boolean get() = status.equals("COMPLETED", ignoreCase = true)

    /**
     * For goals with an amount and deadline, returns computed daily required amount.
     */
    fun computeDailyRequired(currentTime: Long = System.currentTimeMillis()): Double? {
        if (targetAmount == null || dueAt == null || dueAt <= currentTime) return null
        val remaining = (targetAmount - progress).coerceAtLeast(0.0)
        val diffDays = ((dueAt - currentTime) / (1000L * 60 * 60 * 24)).coerceAtLeast(1L)
        return remaining / diffDays
    }
}

/**
 * Persistent memory facts captured from "remember that..." or AI analysis.
 */
@Entity(tableName = "facts")
data class Fact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val category: String = "GENERAL",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Stored session conversation turns (max 50 persistent).
 */
@Entity(tableName = "conversation_turns")
data class ConversationTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String, // "USER" or "ASSISTANT"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val actionsJson: String? = null
)
