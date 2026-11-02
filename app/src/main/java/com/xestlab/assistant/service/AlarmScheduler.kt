package com.xestlab.assistant.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.xestlab.assistant.MainActivity
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.UserPreferencesState
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.model.ReminderSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.Locale

object AlarmScheduler {

    const val EXTRA_ALARM_ID = "extra_alarm_id"
    const val EXTRA_ALARM_TITLE = "extra_alarm_title"
    const val EXTRA_VOICE_ENABLED = "extra_voice_enabled"
    const val EXTRA_IS_WAKE_UP = "extra_is_wake_up"
    const val EXTRA_IS_TEST = "extra_is_test"
    const val EXTRA_ITEM_ID = "extra_item_id"
    const val EXTRA_CONTACT_NAME = "extra_contact_name"

    private const val WAKE_UP_REQUEST_CODE = 9001
    private const val TEST_ALARM_REQUEST_CODE = 9999

    private val _lastAlarmFiredAtFlow = MutableStateFlow(0L)
    val lastAlarmFiredAtFlow: StateFlow<Long> = _lastAlarmFiredAtFlow.asStateFlow()

    private val _lastAlarmFiredTitleFlow = MutableStateFlow("")
    val lastAlarmFiredTitleFlow: StateFlow<String> = _lastAlarmFiredTitleFlow.asStateFlow()

    private val _nextAlarmAtFlow = MutableStateFlow(0L)
    val nextAlarmAtFlow: StateFlow<Long> = _nextAlarmAtFlow.asStateFlow()

    private val _nextAlarmTitleFlow = MutableStateFlow("")
    val nextAlarmTitleFlow: StateFlow<String> = _nextAlarmTitleFlow.asStateFlow()

    fun syncAlarmTelemetry(context: Context) {
        val securePrefs = SecurePreferences(context.applicationContext)
        _lastAlarmFiredAtFlow.value = securePrefs.lastAlarmFiredTimestamp
        _lastAlarmFiredTitleFlow.value = securePrefs.lastAlarmFiredTitle

        val now = System.currentTimeMillis()
        var nextTs = securePrefs.nextAlarmTimestamp
        var nextLabel = securePrefs.nextAlarmTitle

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val systemClockNext = try {
            alarmManager.nextAlarmClock?.triggerTime ?: 0L
        } catch (_: Exception) {
            0L
        }

        if (nextTs <= now && systemClockNext > now) {
            nextTs = systemClockNext
            if (nextLabel.isBlank()) nextLabel = "System Alarm Clock"
        }
        _nextAlarmAtFlow.value = nextTs
        _nextAlarmTitleFlow.value = nextLabel
    }

    fun recordAlarmFired(
        context: Context,
        title: String,
        firedAtMillis: Long = System.currentTimeMillis()
    ) {
        val securePrefs = SecurePreferences(context.applicationContext)
        securePrefs.lastAlarmFiredTimestamp = firedAtMillis
        securePrefs.lastAlarmFiredTitle = title
        _lastAlarmFiredAtFlow.value = firedAtMillis
        _lastAlarmFiredTitleFlow.value = title
    }

    private fun recordNextCandidate(
        context: Context,
        triggerAtMillis: Long,
        title: String,
        forceReplace: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (triggerAtMillis <= now) return
        val securePrefs = SecurePreferences(context.applicationContext)
        val existingNext = securePrefs.nextAlarmTimestamp
        if (forceReplace || existingNext <= now || triggerAtMillis <= existingNext) {
            securePrefs.nextAlarmTimestamp = triggerAtMillis
            securePrefs.nextAlarmTitle = title
            _nextAlarmAtFlow.value = triggerAtMillis
            _nextAlarmTitleFlow.value = title
        }
    }

    fun isWithinQuietHours(
        prefs: UserPreferencesState,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!prefs.quietHoursEnabled) return false
        val startMins = parseMinutesOfDay(prefs.quietHoursStart) ?: (22 * 60)
        val endMins = parseMinutesOfDay(prefs.quietHoursEnd) ?: (7 * 60)

        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        return if (startMins > endMins) {
            nowMins >= startMins || nowMins < endMins
        } else {
            nowMins in startMins until endMins
        }
    }

    private fun parseMinutesOfDay(hhmm: String): Int? {
        val parts = hhmm.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun scheduleAllActive(
        context: Context,
        wakeUpTime: String,
        schedules: List<ReminderSchedule>,
        pendingItems: List<Item>
    ) {
        val securePrefs = SecurePreferences(context.applicationContext)
        securePrefs.nextAlarmTimestamp = 0L
        securePrefs.nextAlarmTitle = ""
        _nextAlarmAtFlow.value = 0L
        _nextAlarmTitleFlow.value = ""

        val cleanWake = wakeUpTime.trim().ifBlank { "07:30" }
        scheduleWakeUpAlarm(context, cleanWake)

        schedules.forEach { schedule ->
            // Avoid double-firing if Morning briefing has the exact same HH:mm as the Wake-Up alarm
            if (schedule.isActive && !(schedule.id == "morning" && schedule.timeString.trim() == cleanWake)) {
                scheduleDailyBriefing(context, schedule)
            } else {
                cancelDailyBriefing(context, schedule.id)
            }
        }

        val now = System.currentTimeMillis()
        pendingItems.forEach { item ->
            val due = item.dueAt
            if (due != null && due > now && (item.type == "REMINDER" || item.type == "TASK")) {
                scheduleItemReminder(context, item)
            }
        }
        syncAlarmTelemetry(context)
    }

    /**
     * Schedules the 7:30 AM (or user-configured) morning wake-up alarm via setAlarmClock
     * so Android wakes the device from Doze mode reliably.
     */
    fun scheduleWakeUpAlarm(context: Context, wakeUpTime: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val mins = parseMinutesOfDay(wakeUpTime) ?: (7 * 60 + 30)
        val hour = mins / 60
        val minute = mins % 60

        val triggerAt = nextOccurrenceMillis(hour, minute, "ALL")
        val label = "$wakeUpTime Morning Plan"
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, "wake_up_alarm")
            putExtra(EXTRA_ALARM_TITLE, label)
            putExtra(EXTRA_VOICE_ENABLED, true)
            putExtra(EXTRA_IS_WAKE_UP, true)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WAKE_UP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val showPendingIntent = PendingIntent.getActivity(
            context,
            WAKE_UP_REQUEST_CODE + 1,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val clockInfo = AlarmManager.AlarmClockInfo(triggerAt, showPendingIntent)
            alarmManager.setAlarmClock(clockInfo, pendingIntent)
        } catch (_: SecurityException) {
            scheduleExactSafe(alarmManager, triggerAt, pendingIntent)
        }
        recordNextCandidate(context, triggerAt, label)
    }

    fun scheduleDailyBriefing(context: Context, schedule: ReminderSchedule) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val mins = parseMinutesOfDay(schedule.timeString) ?: return
        val triggerAt = nextOccurrenceMillis(mins / 60, mins % 60, schedule.daysOfWeek)

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, schedule.id)
            putExtra(EXTRA_ALARM_TITLE, schedule.title)
            putExtra(EXTRA_VOICE_ENABLED, schedule.voiceEnabled)
            putExtra(EXTRA_IS_WAKE_UP, schedule.id == "morning")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        scheduleExactSafe(alarmManager, triggerAt, pendingIntent)
        recordNextCandidate(context, triggerAt, schedule.title)
    }

    fun cancelDailyBriefing(context: Context, scheduleId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            scheduleId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }

    fun scheduleItemReminder(context: Context, item: Item) {
        val dueAt = item.dueAt ?: return
        if (dueAt <= System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, "item_${item.id}")
            putExtra(EXTRA_ALARM_TITLE, item.title)
            putExtra(EXTRA_VOICE_ENABLED, true)
            putExtra(EXTRA_ITEM_ID, item.id)
            if (!item.contactName.isNullOrBlank()) {
                putExtra(EXTRA_CONTACT_NAME, item.contactName)
            }
        }
        val reqCode = ("item_${item.id}").hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        scheduleExactSafe(alarmManager, dueAt, pendingIntent)
        recordNextCandidate(context, dueAt, item.title)
    }

    fun cancelItemReminder(context: Context, itemId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        for (reqCode in listOf(("item_$itemId").hashCode(), ("snooze_$itemId").hashCode())) {
            val pi = PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                alarmManager.cancel(pi)
                pi.cancel()
            }
        }
    }

    fun scheduleSnooze(
        context: Context,
        itemId: Long,
        title: String,
        contactName: String?,
        delayMinutes: Int = 15
    ) {
        val triggerAt = System.currentTimeMillis() + delayMinutes * 60_000L
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, "snooze_$itemId")
            putExtra(EXTRA_ALARM_TITLE, title)
            putExtra(EXTRA_VOICE_ENABLED, true)
            putExtra(EXTRA_ITEM_ID, itemId)
            if (!contactName.isNullOrBlank()) {
                putExtra(EXTRA_CONTACT_NAME, contactName)
            }
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ("snooze_$itemId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        scheduleExactSafe(alarmManager, triggerAt, pendingIntent)
        recordNextCandidate(context, triggerAt, "Snooze: $title")
    }

    fun scheduleOneMinuteTest(context: Context) {
        val triggerAt = System.currentTimeMillis() + 60_000L
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, "test_alarm_1m")
            putExtra(EXTRA_ALARM_TITLE, "Morning Wake-Up Test")
            putExtra(EXTRA_VOICE_ENABLED, true)
            putExtra(EXTRA_IS_WAKE_UP, true)
            putExtra(EXTRA_IS_TEST, true)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            TEST_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        scheduleExactSafe(alarmManager, triggerAt, pendingIntent)
        recordNextCandidate(context, triggerAt, "Morning Wake-Up Test")
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    private fun scheduleExactSafe(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun nextOccurrenceMillis(hour: Int, minute: Int, daysOfWeek: String): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val upperDays = daysOfWeek.uppercase(Locale.US)
        if (upperDays == "SUN" || upperDays == "SUNDAY") {
            while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || cal.timeInMillis <= now) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
