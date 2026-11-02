package com.xestlab.assistant.service

import com.xestlab.assistant.data.local.AppDatabase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Time-aware dynamic TTS greetings, Room-backed daily remaining-task briefings,
 * and non-repeating supportive task-completion voice responses for King Israel.
 */
object TtsGreetingHelper {

    private val supportiveCompletionPhrases = listOf(
        "Excellent work.",
        "Let's keep the momentum going.",
        "Task cleared. Take a quick breath.",
        "Another win on the board, King Israel.",
        "Solid execution. Keep stacking wins.",
        "Cleanly handled. Ready when you are for the next move.",
        "That is how real momentum is built. Nice focus.",
        "Checked off and locked in. Keep that rhythm.",
        "Strong follow-through. Take a quick breath and stay sharp."
    )

    private val lastCompletionIndex = AtomicInteger(-1)

    /**
     * Checks `Calendar.getInstance().get(Calendar.HOUR_OF_DAY)`:
     * - 0..11  -> "Good morning"
     * - 12..16 -> "Good afternoon"
     * - 17..23 -> "Good evening"
     */
    fun getGreetingWord(
        hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ): String {
        return when (hourOfDay) {
            in 0..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    /**
     * Returns the exact time-aware greeting sentence:
     * - 0..11:  "Good morning, King Israel."
     * - 12..16: "Good afternoon, King Israel."
     * - 17..23: "Good evening, King Israel."
     */
    fun getTimeAwareGreeting(
        userName: String = "King Israel",
        hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ): String {
        val displayName = userName.trim().ifBlank { "King Israel" }
        val greetingWord = getGreetingWord(hourOfDay)
        return "$greetingWord, $displayName."
    }

    /**
     * Queries the Room database for all unfinished tasks & core daily targets remaining today,
     * returning their titles in focus priority order.
     */
    suspend fun getRemainingTaskTitlesForToday(db: AppDatabase): List<String> {
        AppDatabase.ensureSeeded(db)

        val nowCal = Calendar.getInstance()
        val todayDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(nowCal.time)
        val endOfTodayMillis = (nowCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val remainingTasks = db.itemDao().getRemainingTasksForTodayDirect(endOfTodayMillis)
        val remainingChecklist = db.checklistDao().getRemainingChecklistForDateDirect(todayDateStr)

        return buildList {
            remainingTasks.forEach { item ->
                val title = item.title.trim()
                if (title.isNotEmpty()) add(title)
            }
            remainingChecklist.forEach { check ->
                val title = check.title.trim()
                if (title.isNotEmpty()) add(title)
            }
        }
    }

    suspend fun countRemainingTasksForToday(db: AppDatabase): Int {
        return getRemainingTaskTitlesForToday(db).size
    }

    /**
     * Builds the complete time-aware spoken daily briefing from Room:
     * - Greeting based on `Calendar.HOUR_OF_DAY` (0-11 morning, 12-16 afternoon, 17-23 evening)
     * - High and Normal non-dismissed unread emails appear in the daily briefing; Low emails are only counted.
     * - If tasks remain:
     *   "You have [X] tasks remaining. Let's focus on [First Task]. ... What do you want to do first?"
     * - If ZERO tasks remain:
     *   "You have cleared all your tasks for today. Excellent work. ... You should rest."
     */
    suspend fun buildDynamicBriefing(
        db: AppDatabase,
        userName: String = "King Israel"
    ): String {
        val greeting = getTimeAwareGreeting(userName)
        val remainingTitles = getRemainingTaskTitlesForToday(db)
        val remainingCount = remainingTitles.size

        val activeEmails = db.emailDao().getActiveUnreadDirect(50)
        val highEmails = activeEmails.filter { it.priority == "HIGH" }
        val normalEmails = activeEmails.filter { it.priority == "NORMAL" }
        val lowCount = activeEmails.count { it.priority == "LOW" }

        val emailBriefingSegment = buildList {
            if (highEmails.isNotEmpty()) {
                val preview = highEmails.take(3).joinToString("; ") { "${it.sender}: ${it.subject}" }
                val extra = if (highEmails.size > 3) " and ${highEmails.size - 3} more" else ""
                add("High-priority emails (${highEmails.size}): $preview$extra.")
            }
            if (normalEmails.isNotEmpty()) {
                val preview = normalEmails.take(3).joinToString("; ") { "${it.sender}: ${it.subject}" }
                val extra = if (normalEmails.size > 3) " and ${normalEmails.size - 3} more" else ""
                add("Normal emails (${normalEmails.size}): $preview$extra.")
            }
            if (lowCount > 0) {
                add("Low-priority emails: $lowCount unread.")
            }
        }.joinToString(" ")

        return if (remainingCount > 0) {
            val firstTask = remainingTitles.first()
            val taskNoun = if (remainingCount == 1) "task" else "tasks"
            if (emailBriefingSegment.isNotBlank()) {
                "$greeting You have $remainingCount $taskNoun remaining. Let's focus on $firstTask. $emailBriefingSegment What do you want to do first?"
            } else {
                "$greeting You have $remainingCount $taskNoun remaining. Let's focus on $firstTask. What do you want to do first?"
            }
        } else {
            if (emailBriefingSegment.isNotBlank()) {
                "$greeting You have cleared all your tasks for today. Excellent work. $emailBriefingSegment You should rest."
            } else {
                "$greeting You have cleared all your tasks for today. Excellent work. You should rest."
            }
        }
    }

    /**
     * Randomly selects a supportive phrase without repeating the immediately preceding phrase.
     */
    fun nextSupportiveCompletionPhrase(): String {
        val total = supportiveCompletionPhrases.size
        if (total <= 1) return supportiveCompletionPhrases.firstOrNull() ?: "Excellent work."

        val previous = lastCompletionIndex.get()
        var chosen = Random.Default.nextInt(total)
        if (chosen == previous) {
            chosen = (chosen + 1 + Random.Default.nextInt(total - 1)) % total
        }
        lastCompletionIndex.set(chosen)
        return supportiveCompletionPhrases[chosen]
    }

    /**
     * Builds the spoken response when a task or daily checklist target is completed.
     * If all tasks for today are now cleared, celebrates clearing the board; otherwise
     * speaks a varied supportive phrase so it never sounds robotic.
     */
    fun buildTaskCompletionSpeech(allClearedForToday: Boolean = false): String {
        return if (allClearedForToday) {
            "You have cleared all your tasks for today. Excellent work. You should rest."
        } else {
            nextSupportiveCompletionPhrase()
        }
    }
}
