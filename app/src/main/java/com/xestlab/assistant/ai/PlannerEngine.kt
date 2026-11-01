package com.xestlab.assistant.ai

import com.xestlab.assistant.data.UserPreferencesState
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.Item
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class RecommendationType {
    NOW,
    LATER_TODAY,
    TOMORROW,
    DROP
}

data class PlannerProposal(
    val candidateItem: Item,
    val recommendation: RecommendationType,
    val proposedDueAt: Long?,
    val reason: String
)

object PlannerEngine {

    suspend fun propose(
        db: AppDatabase,
        prefs: UserPreferencesState,
        candidateItem: Item,
        nowMillis: Long = System.currentTimeMillis()
    ): PlannerProposal {
        val pending = db.itemDao().getPendingItemsDirect()
            .filter { !it.sourceText.startsWith("web_pipeline_") }

        val todayStart = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val todayEnd = todayStart + 86_400_000L

        val todayScheduledMinutes = pending
            .filter { it.dueAt == null || it.dueAt in todayStart..todayEnd }
            .sumOf { if (it.durationMinutes > 0) it.durationMinutes else 30 }

        val quietStartHour = prefs.quietHoursStart.substringBefore(":").toIntOrNull() ?: 22
        val currentCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val currentHour = currentCal.get(Calendar.HOUR_OF_DAY)
        val hoursUntilQuiet = (quietStartHour - currentHour).coerceAtLeast(0)
        val remainingMinutesToday = hoursUntilQuiet * 60

        val isHighPriority = candidateItem.priority.equals("HIGH", ignoreCase = true)
        val isLowPriority = candidateItem.priority.equals("LOW", ignoreCase = true)
        val itemDuration = if (candidateItem.durationMinutes > 0) candidateItem.durationMinutes else 30

        // If explicit due time was requested by user, validate conflict and keep or shift slightly
        if (candidateItem.dueAt != null) {
            val conflicting = pending.firstOrNull { existing ->
                val existingDue = existing.dueAt ?: return@firstOrNull false
                kotlin.math.abs(existingDue - candidateItem.dueAt) < 25 * 60_000L
            }
            if (conflicting == null) {
                val formatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(candidateItem.dueAt))
                return PlannerProposal(
                    candidateItem = candidateItem,
                    recommendation = if (candidateItem.dueAt - nowMillis <= 30 * 60_000L) {
                        RecommendationType.NOW
                    } else if (candidateItem.dueAt < todayEnd) {
                        RecommendationType.LATER_TODAY
                    } else {
                        RecommendationType.TOMORROW
                    },
                    proposedDueAt = candidateItem.dueAt,
                    reason = "Slot at $formatted is clear with no calendar conflict."
                )
            } else {
                val shifted = candidateItem.dueAt + 45 * 60_000L
                val formatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(shifted))
                return PlannerProposal(
                    candidateItem = candidateItem.copy(dueAt = shifted),
                    recommendation = RecommendationType.LATER_TODAY,
                    proposedDueAt = shifted,
                    reason = "Conflicts with '${conflicting.title}'. Shifted 45 minutes to $formatted."
                )
            }
        }

        // Low priority + overloaded day -> suggest DROP or TOMORROW
        if (isLowPriority && todayScheduledMinutes >= 240) {
            return PlannerProposal(
                candidateItem = candidateItem,
                recommendation = RecommendationType.DROP,
                proposedDueAt = null,
                reason = "You already have ${todayScheduledMinutes}m committed today and this is low priority."
            )
        }

        // Quiet hours or no capacity left today -> push to tomorrow morning after wake-up
        if (remainingMinutesToday < itemDuration || todayScheduledMinutes + itemDuration > 360) {
            val wakeParts = prefs.wakeUpTime.split(":")
            val wakeHour = (wakeParts.getOrNull(0)?.toIntOrNull() ?: 6) + 2
            val wakeMin = wakeParts.getOrNull(1)?.toIntOrNull() ?: 30
            val tomorrowSlot = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, wakeHour.coerceIn(6, 11))
                set(Calendar.MINUTE, wakeMin.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val formatted = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(tomorrowSlot))
            return PlannerProposal(
                candidateItem = candidateItem.copy(dueAt = tomorrowSlot),
                recommendation = RecommendationType.TOMORROW,
                proposedDueAt = tomorrowSlot,
                reason = "Today's focus window is full (${todayScheduledMinutes}m scheduled). Queued for $formatted."
            )
        }

        // High priority or light load -> NOW
        if (isHighPriority || todayScheduledMinutes < 90) {
            val nowSlot = nowMillis + 5 * 60_000L
            return PlannerProposal(
                candidateItem = candidateItem.copy(dueAt = nowSlot),
                recommendation = RecommendationType.NOW,
                proposedDueAt = nowSlot,
                reason = if (isHighPriority) {
                    "High priority item and you have focus capacity right now."
                } else {
                    "Schedule is light (${todayScheduledMinutes}m booked). Best tackled immediately."
                }
            )
        }

        // Moderate load -> LATER_TODAY (in 2 hours)
        val laterSlot = (nowMillis + 2 * 3_600_000L).coerceAtMost(todayEnd - 60 * 60_000L)
        val formatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(laterSlot))
        return PlannerProposal(
            candidateItem = candidateItem.copy(dueAt = laterSlot),
            recommendation = RecommendationType.LATER_TODAY,
            proposedDueAt = laterSlot,
            reason = "Placed in your next open block at $formatted so current focus stays uninterrupted."
        )
    }
}
