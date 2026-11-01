package com.xestlab.assistant.service

import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.DailyLog
import java.text.SimpleDateFormat
import java.util.*

object VoiceEngine {

    suspend fun generateMessage(
        db: AppDatabase,
        reminderType: String,
        customTitle: String? = null
    ): String {
        val settings = db.settingsDao().getSettingsDirect()
        val name = settings?.userName?.takeIf { it.isNotBlank() } ?: "there"
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val allItems = db.checklistDao().getAllItemsDirect()
        val logsToday = db.dailyLogDao().getLogsForDateDirect(todayStr).associateBy { it.itemId }

        val unfinishedItems = allItems.filter { item ->
            val log = logsToday[item.id]
            log == null || !log.isDone
        }

        return when (reminderType.lowercase(Locale.ROOT)) {
            "morning" -> {
                val cal = Calendar.getInstance()
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                val weekStart = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
                cal.add(Calendar.DAY_OF_WEEK, 6)
                val weekEnd = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)

                val weekLogs = db.dailyLogDao().getLogsBetweenDates(weekStart, weekEnd)
                val goal = db.goalDao().getGoalsDirect()
                val messagesLogged = weekLogs.filter { it.isDone && it.itemId == "messages10" }
                    .sumOf { if (it.countValue > 0) it.countValue else 10 }
                val target = goal?.weeklyMessagesTarget ?: 50

                val unfinishedTitles = if (unfinishedItems.isEmpty()) {
                    "All five daily checklist items are already complete."
                } else {
                    "Today's unfinished items are: " + unfinishedItems.joinToString(", ") { it.title } + "."
                }

                "Good morning $name. $unfinishedTitles You have logged $messagesLogged outreach messages out of your weekly target of $target."
            }

            "midday" -> {
                val messagesLog = logsToday["messages10"]
                val currentCount = messagesLog?.countValue ?: 0
                "Hello $name. Midday check-in. How many outreach messages have you sent so far today? Currently logged: $currentCount."
            }

            "evening" -> {
                if (unfinishedItems.isEmpty()) {
                    "Good evening $name. Outstanding work. Every item on your daily checklist is marked done."
                } else {
                    val count = unfinishedItems.size
                    val list = unfinishedItems.joinToString(", ") { it.title }
                    "Good evening $name. You have $count items unfinished: $list. Tap Done or speak to complete."
                }
            }

            "sunday" -> {
                val goal = db.goalDao().getGoalsDirect()
                "Sunday review time, $name. Time to log weekly totals against your goal of 10,000,000 naira. Open your weekly scorecard."
            }

            "nudge" -> {
                if (unfinishedItems.isEmpty()) {
                    "Great news $name. All checklist items are done for today."
                } else {
                    val first = unfinishedItems.first().title
                    "Quick nudge $name. $first is still pending. One push will get it done."
                }
            }

            else -> {
                customTitle?.takeIf { it.isNotBlank() } ?: "Reminder from Xestlab Assistant."
            }
        }
    }
}
