package com.xestlab.assistant.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.xestlab.assistant.MainActivity
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.model.ReminderSchedule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object AlarmScheduler {

    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_REMINDER_TITLE = "extra_title"
    const val EXTRA_VOICE_ENABLED = "extra_voice_enabled"
    const val EXTRA_CONTACT_NAME = "extra_contact_name"
    const val EXTRA_ITEM_ID = "extra_item_id"
    const val EXTRA_IS_WAKEUP = "extra_is_wakeup"

    const val ACTION_ALARM_FIRE = "com.xestlab.assistant.ACTION_ALARM_FIRE"
    const val ACTION_NOTIFICATION_DONE = "com.xestlab.assistant.ACTION_NOTIFICATION_DONE"
    const val ACTION_NOTIFICATION_SNOOZE = "com.xestlab.assistant.ACTION_NOTIFICATION_SNOOZE"
    const val ACTION_OPEN_CHAT = "com.xestlab.assistant.ACTION_OPEN_CHAT"
    const val ACTION_WAKEUP_ALARM = "com.xestlab.assistant.ACTION_WAKEUP_ALARM"
    const val ACTION_DISMISS_WAKEUP = "com.xestlab.assistant.ACTION_DISMISS_WAKEUP"

    private const val TAG = "AlarmScheduler"
    private const val NUDGE_ID = "nudge"
    private const val MAX_NUDGES_PER_DAY = 2
    private const val PREFS = "nudge_counter"

    private val DAY_CODES = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

    /**
     * Schedules daily wake-up alarm using AlarmManager.setAlarmClock.
     * Rings alarm, vocalizes day's plan, and repeats until dismissed or snoozed.
     */
    fun scheduleWakeUpAlarm(context: Context, timeString: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = nextTriggerMillis(timeString, "ALL") ?: return

        val showIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_SCREEN", "wakeup")
        }
        val showPendingIntent = PendingIntent.getActivity(
            context,
            101,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fireIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_WAKEUP_ALARM
            putExtra(EXTRA_REMINDER_ID, "wakeup")
            putExtra(EXTRA_REMINDER_TITLE, "Wake-up Alarm")
            putExtra(EXTRA_VOICE_ENABLED, true)
            putExtra(EXTRA_IS_WAKEUP, true)
        }
        val firePendingIntent = PendingIntent.getBroadcast(
            context,
            102,
            fireIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showPendingIntent),
                firePendingIntent
            )
            Log.d(TAG, "Wake-up alarm set for ${Date(triggerAt)}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting alarm clock", e)
        }
    }

    /** Schedules the next occurrence of a reminder. Repeating reminders re-arm themselves when they fire. */
    fun scheduleReminder(context: Context, reminder: ReminderSchedule) {
        if (!reminder.isActive) {
            cancelReminder(context, reminder.id)
            return
        }
        val trigger = nextTriggerMillis(reminder.timeString, reminder.daysOfWeek) ?: return
        setAlarm(context, reminder.id, reminder.title, reminder.voiceEnabled, trigger)
    }

    /** Schedules a person reminder ("remind me to message Tola at 4 pm"). */
    fun schedulePersonReminder(context: Context, item: Item) {
        val triggerAt = item.dueAt ?: return
        if (triggerAt <= System.currentTimeMillis()) return

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_FIRE
            putExtra(EXTRA_REMINDER_ID, "item_${item.id}")
            putExtra(EXTRA_REMINDER_TITLE, item.title)
            putExtra(EXTRA_VOICE_ENABLED, true)
            putExtra(EXTRA_CONTACT_NAME, item.contactName)
            putExtra(EXTRA_ITEM_ID, item.id)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            ("item_${item.id}").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exactOk) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    suspend fun scheduleAllActive(context: Context) {
        val db = AppDatabase.getInstance(context)
        val secure = SecurePreferences(context)

        // 1. Wake-up alarm
        if (secure.isWakeUpEnabled) {
            scheduleWakeUpAlarm(context, secure.wakeUpTime)
        }

        // 2. Built-in and custom reminders
        db.reminderDao().getAllRemindersDirect()
            .filter { it.isActive }
            .forEach { scheduleReminder(context, it) }

        // 3. Pending person reminders and items with due dates
        val pendingItems = db.itemDao().getPendingItemsDirect()
        pendingItems.filter { it.dueAt != null && it.dueAt > System.currentTimeMillis() }
            .forEach { schedulePersonReminder(context, it) }

        // 4. Background email checking WorkManager
        if (secure.isImapEnabled) {
            EmailHelper.scheduleWork(context, secure.isImapWifiOnly)
        }
    }

    fun cancelReminder(context: Context, id: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(context, id, "", true))
    }

    fun scheduleSnooze(context: Context, id: String, title: String, minutes: Int = 15) {
        setAlarm(context, "snooze_$id", title, true, System.currentTimeMillis() + minutes * 60_000L, targetId = id)
    }

    fun scheduleTestReminder(context: Context) {
        setAlarm(context, "test", "Test reminder", true, System.currentTimeMillis() + 60_000L)
    }

    /** Follow-up nudge for unfinished items. Capped at 2 per day. */
    fun scheduleNudge(context: Context, minutes: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val count = if (prefs.getString("date", "") == today) prefs.getInt("count", 0) else 0
        if (count >= MAX_NUDGES_PER_DAY) return
        prefs.edit().putString("date", today).putInt("count", count + 1).apply()
        setAlarm(context, NUDGE_ID, "Nudge", true, System.currentTimeMillis() + minutes * 60_000L)
    }

    fun cancelNudges(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(context, NUDGE_ID, "", true))
    }

    /**
     * Dispatches an Android app chooser to message the contact via WhatsApp or SMS.
     * Never sends messages itself; opens the composer addressed to contactName.
     */
    fun openChatChooser(context: Context, contactName: String) {
        val greeting = "Hi $contactName, "
        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:")
            putExtra("sms_body", greeting)
        }

        val chooser = Intent.createChooser(smsIntent, "Message $contactName with...").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open chat chooser", e)
        }
    }

    private fun nextTriggerMillis(time: String, days: String): Long? {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val allowed = if (days.equals("ALL", ignoreCase = true)) null
        else days.split(",").map { it.trim().uppercase(Locale.ROOT) }.toSet()

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        for (offset in 0..7) {
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, offset)
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val code = DAY_CODES[cal.get(Calendar.DAY_OF_WEEK) - 1]
            if (cal.timeInMillis > now && (allowed == null || code in allowed)) return cal.timeInMillis
        }
        return null
    }

    private fun pendingIntent(
        context: Context,
        key: String,
        title: String,
        voice: Boolean,
        targetId: String = key
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_FIRE
            putExtra(EXTRA_REMINDER_ID, targetId)
            putExtra(EXTRA_REMINDER_TITLE, title)
            putExtra(EXTRA_VOICE_ENABLED, voice)
        }
        return PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setAlarm(
        context: Context,
        key: String,
        title: String,
        voice: Boolean,
        triggerAt: Long,
        targetId: String = key
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context, key, title, voice, targetId)
        try {
            val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (exactOk) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.d(TAG, "Scheduled $key at ${Date(triggerAt)} exact=$exactOk")
        } catch (e: SecurityException) {
            Log.e(TAG, "Could not schedule $key", e)
        }
    }
}
