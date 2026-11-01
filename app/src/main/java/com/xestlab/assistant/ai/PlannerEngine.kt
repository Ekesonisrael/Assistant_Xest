package com.xestlab.assistant.ai

import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.Item
import java.text.SimpleDateFormat
import java.util.*

enum class RecommendationType {
    NOW,
    LATER_TODAY,
    TOMORROW,
    DROP
}

data class PlannerProposal(
    val item: Item,
    val recommendation: RecommendationType,
    val suggestedTime: String?, // e.g. "14:30"
    val suggestedDueAtMillis: Long?,
    val rationale: String,
    val summarySpeech: String
)

object PlannerEngine {

    /**
     * Evaluates today's unfinished items, scheduled alarms, and available free time
     * between wake time and quiet hours to generate a "Now or Later" recommendation.
     * Never commits anything to the database until explicitly chosen by user.
     */
    suspend fun propose(
        db: AppDatabase,
        candidateItem: Item
    ): PlannerProposal {
        val settings = db.settingsDao().getSettingsDirect()
        val wakeUpTime = settings?.wakeUpTime ?: "06:30"
        val quietStart = settings?.quietHoursStart ?: "22:00"

        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(Calendar.MINUTE)
        val currentMinutesOfDay = currentHour * 60 + currentMinute

        val quietMinutes = parseMinutes(quietStart, 22 * 60)
        val wakeMinutes = parseMinutes(wakeUpTime, 6 * 60 + 30)

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val allChecklist = db.checklistDao().getAllItemsDirect()
        val todayLogs = db.dailyLogDao().getLogsForDateDirect(todayStr).associateBy { it.itemId }
        val unfinishedChecklistCount = allChecklist.count { todayLogs[it.id]?.isDone != true }

        val activeReminders = db.reminderDao().getAllRemindersDirect().filter { it.isActive }
        val pendingItems = db.itemDao().getPendingItemsDirect()

        // If it's already in quiet hours or within 45 minutes of quiet hours:
        if (currentMinutesOfDay >= quietMinutes - 45 || currentMinutesOfDay < wakeMinutes) {
            val tomorrowTime = "09:00"
            val tomorrowCal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            return PlannerProposal(
                item = candidateItem.copy(dueAt = tomorrowCal.timeInMillis),
                recommendation = RecommendationType.TOMORROW,
                suggestedTime = tomorrowTime,
                suggestedDueAtMillis = tomorrowCal.timeInMillis,
                rationale = "It is near your quiet hours. Scheduling for tomorrow morning at 09:00 will keep your focus fresh.",
                summarySpeech = "I recommend tomorrow at 09:00. Quiet hours start soon. Should I schedule this?"
            )
        }

        // Check if user has heavy unfinished checklist or pending urgent items
        val urgentPending = pendingItems.count { it.priority.equals("HIGH", ignoreCase = true) || it.priority.equals("URGENT", ignoreCase = true) }
        val durationMins = if (candidateItem.durationMinutes > 0) candidateItem.durationMinutes else 30

        if (urgentPending > 3 && unfinishedChecklistCount > 2) {
            // Suggest later today or drop
            val laterSlot = findNextSlot(currentMinutesOfDay, activeReminders.map { parseMinutes(it.timeString, 12 * 60) })
            val laterHour = laterSlot / 60
            val laterMinute = laterSlot % 60
            val laterTimeStr = String.format(Locale.US, "%02d:%02d", laterHour, laterMinute)

            val laterCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, laterHour)
                set(Calendar.MINUTE, laterMinute)
                set(Calendar.SECOND, 0)
            }

            return PlannerProposal(
                item = candidateItem.copy(dueAt = laterCal.timeInMillis),
                recommendation = RecommendationType.LATER_TODAY,
                suggestedTime = laterTimeStr,
                suggestedDueAtMillis = laterCal.timeInMillis,
                rationale = "You have $unfinishedChecklistCount unfinished core items and $urgentPending urgent tasks queued right now.",
                summarySpeech = "I recommend later today at $laterTimeStr. You have core items to finish first. Save for $laterTimeStr?"
            )
        }

        // If light schedule or candidate is quick / urgent:
        if (candidateItem.priority.equals("HIGH", ignoreCase = true) || durationMins <= 15 || (unfinishedChecklistCount <= 1 && urgentPending == 0)) {
            val dueNowCal = Calendar.getInstance().apply {
                add(Calendar.MINUTE, durationMins)
            }
            return PlannerProposal(
                item = candidateItem.copy(dueAt = dueNowCal.timeInMillis),
                recommendation = RecommendationType.NOW,
                suggestedTime = "Now",
                suggestedDueAtMillis = dueNowCal.timeInMillis,
                rationale = "Your immediate schedule is clear before your next reminder block.",
                summarySpeech = "I recommend doing this now. You have clear focus time. Want me to mark it active?"
            )
        }

        // Default: Propose a comfortable slot later today
        val slot = findNextSlot(currentMinutesOfDay, activeReminders.map { parseMinutes(it.timeString, 12 * 60) })
        val slotHour = slot / 60
        val slotMinute = slot % 60
        val slotTimeStr = String.format(Locale.US, "%02d:%02d", slotHour, slotMinute)

        val slotCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, slotHour)
            set(Calendar.MINUTE, slotMinute)
            set(Calendar.SECOND, 0)
        }

        return PlannerProposal(
            item = candidateItem.copy(dueAt = slotCal.timeInMillis),
            recommendation = RecommendationType.LATER_TODAY,
            suggestedTime = slotTimeStr,
            suggestedDueAtMillis = slotCal.timeInMillis,
            rationale = "There is an open $durationMins-minute slot at $slotTimeStr between scheduled check-ins.",
            summarySpeech = "I recommend later today at $slotTimeStr. You have an open block then. Should I lock that in?"
        )
    }

    private fun parseMinutes(timeStr: String, defaultVal: Int): Int {
        val parts = timeStr.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return defaultVal
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return h * 60 + m
    }

    private fun findNextSlot(currentMin: Int, busyMinutes: List<Int>): Int {
        var candidate = currentMin + 45 // 45 minutes from now
        val sortedBusy = busyMinutes.sorted()
        for (busy in sortedBusy) {
            if (candidate in (busy - 20)..(busy + 20)) {
                candidate = busy + 30
            }
        }
        return candidate.coerceAtMost(21 * 60)
    }
}
