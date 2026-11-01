package com.xestlab.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xestlab.assistant.MainActivity
import com.xestlab.assistant.R
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.UserPreferencesDataStore
import com.xestlab.assistant.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ALERT_CHANNEL_ID = "xestlab_alerts_channel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: "briefing"
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_TITLE) ?: "Assistant Reminder"
        val voiceEnabled = intent.getBooleanExtra(AlarmScheduler.EXTRA_VOICE_ENABLED, true)
        val isWakeUp = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_WAKE_UP, false)
        val isTest = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_TEST, false)
        val itemId = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, -1L)
        val contactName = intent.getStringExtra(AlarmScheduler.EXTRA_CONTACT_NAME)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefsStore = UserPreferencesDataStore(context)
                val prefs = prefsStore.getSnapshot()
                val db = AppDatabase.getInstance(context)

                // Reschedule repeating briefings or wake-up alarm for tomorrow
                if (isWakeUp) {
                    AlarmScheduler.scheduleWakeUpAlarm(context, prefs.wakeUpTime)
                } else {
                    val matchingSchedule = db.reminderDao().getAllRemindersDirect()
                        .firstOrNull { it.id == alarmId && it.isActive && it.isRepeating }
                    if (matchingSchedule != null) {
                        AlarmScheduler.scheduleDailyBriefing(context, matchingSchedule)
                    }
                }

                postAlertNotification(
                    context = context,
                    notificationId = alarmId.hashCode(),
                    title = title,
                    itemId = itemId,
                    contactName = contactName
                )

                val bypassQuiet = isWakeUp || isTest
                if (voiceEnabled && (bypassQuiet || !AlarmScheduler.isWithinQuietHours(prefs))) {
                    val userName = prefs.userName.ifBlank { "Friend" }
                    val customLine = if (itemId > 0L) {
                        "$userName, reminder: $title."
                    } else {
                        null
                    }
                    val serviceIntent = Intent(context, VoiceReminderService::class.java).apply {
                        putExtra(VoiceReminderService.EXTRA_BRIEFING_TITLE, title)
                        putExtra(VoiceReminderService.EXTRA_BYPASS_QUIET, bypassQuiet)
                        if (customLine != null) {
                            putExtra(VoiceReminderService.EXTRA_CUSTOM_TEXT, customLine)
                        }
                    }
                    try {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (_: Exception) {
                        // Android 12+ background start restriction fallback: notification already posted
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postAlertNotification(
        context: Context,
        notificationId: Int,
        title: String,
        itemId: Long,
        contactName: String?
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Reminders and Briefings",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Scheduled briefings, tasks, and person follow-up alerts"
        }
        manager.createNotificationChannel(channel)

        val openAppIntent = Intent(context, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            context,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(
                if (!contactName.isNullOrBlank()) {
                    "Follow up with $contactName"
                } else {
                    "Tap to view your daily plan"
                }
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppPending)

        if (itemId > 0L) {
            if (!contactName.isNullOrBlank()) {
                val chatIntent = Intent(context, PersonActionReceiver::class.java).apply {
                    action = PersonActionReceiver.ACTION_OPEN_CHAT
                    putExtra(PersonActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                    putExtra(PersonActionReceiver.EXTRA_ITEM_ID, itemId)
                    putExtra(PersonActionReceiver.EXTRA_CONTACT_NAME, contactName)
                    putExtra(PersonActionReceiver.EXTRA_TITLE, title)
                }
                val chatPending = PendingIntent.getBroadcast(
                    context,
                    notificationId + 1,
                    chatIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "Open chat", chatPending)
            }

            val doneIntent = Intent(context, PersonActionReceiver::class.java).apply {
                action = PersonActionReceiver.ACTION_MARK_DONE
                putExtra(PersonActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(PersonActionReceiver.EXTRA_ITEM_ID, itemId)
            }
            val donePending = PendingIntent.getBroadcast(
                context,
                notificationId + 2,
                doneIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Done", donePending)

            val snoozeIntent = Intent(context, PersonActionReceiver::class.java).apply {
                action = PersonActionReceiver.ACTION_SNOOZE_15M
                putExtra(PersonActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(PersonActionReceiver.EXTRA_ITEM_ID, itemId)
                putExtra(PersonActionReceiver.EXTRA_TITLE, title)
                putExtra(PersonActionReceiver.EXTRA_CONTACT_NAME, contactName)
            }
            val snoozePending = PendingIntent.getBroadcast(
                context,
                notificationId + 3,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Snooze 15m", snoozePending)
        }

        try {
            manager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not yet granted
        }
    }
}

class PersonActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_OPEN_CHAT = "com.xestlab.assistant.ACTION_OPEN_CHAT"
        const val ACTION_MARK_DONE = "com.xestlab.assistant.ACTION_MARK_DONE"
        const val ACTION_SNOOZE_15M = "com.xestlab.assistant.ACTION_SNOOZE_15M"

        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_CONTACT_NAME = "extra_contact_name"
        const val EXTRA_TITLE = "extra_title"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val itemId = intent.getLongExtra(EXTRA_ITEM_ID, -1L)
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Follow up"

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)

        when (intent.action) {
            ACTION_MARK_DONE -> {
                if (itemId > 0L) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            AppDatabase.getInstance(context).itemDao().updateStatus(itemId, "COMPLETED")
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
            ACTION_SNOOZE_15M -> {
                if (itemId > 0L) {
                    AlarmScheduler.scheduleSnooze(context, itemId, title, contactName, 15)
                }
            }
            ACTION_OPEN_CHAT -> {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Hi ${contactName ?: ""}, following up: ")
                }
                val chooser = Intent.createChooser(shareIntent, "Message ${contactName ?: "Contact"}").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefsStore = UserPreferencesDataStore(context)
                val prefs = prefsStore.getSnapshot()
                if (prefs.isFirstLaunchComplete) {
                    val db = AppDatabase.getInstance(context)
                    val schedules = db.reminderDao().getAllRemindersDirect()
                    val pendingItems = db.itemDao().getPendingItemsDirect()
                    AlarmScheduler.scheduleAllActive(
                        context = context,
                        wakeUpTime = prefs.wakeUpTime,
                        schedules = schedules,
                        pendingItems = pendingItems
                    )
                }
                val securePrefs = SecurePreferences(context)
                if (securePrefs.isImapEnabled && securePrefs.imapUser.isNotBlank()) {
                    EmailHelper.schedulePeriodicChecks(context, securePrefs.isImapWifiOnly)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
