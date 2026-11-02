package com.xestlab.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xestlab.assistant.MainActivity
import com.xestlab.assistant.R
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.UserPreferencesDataStore
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.Item
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ALERT_CHANNEL_ID = "xestlab_wakeup_heads_up_v2"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()

        // Acquire short CPU WakeLock immediately so 7:30 AM wake-up alarm never drops in Doze
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = try {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xestlab:AlarmReceiverWakeLock").apply {
                acquire(30_000L)
            }
        } catch (_: Exception) {
            null
        }

        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: "briefing"
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_TITLE) ?: "Morning Daily Plan"
        val voiceEnabled = intent.getBooleanExtra(AlarmScheduler.EXTRA_VOICE_ENABLED, true)
        val isWakeUp = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_WAKE_UP, false)
        val isTest = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_TEST, false)
        val itemId = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, -1L)
        val contactName = intent.getStringExtra(AlarmScheduler.EXTRA_CONTACT_NAME)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                AlarmScheduler.recordAlarmFired(context, title)

                val prefsStore = UserPreferencesDataStore(context)
                val prefs = prefsStore.getSnapshot()
                val db = AppDatabase.getInstance(context)

                // Reschedule repeating 7:30 AM wake-up alarm or daily briefings for tomorrow & refresh next alarm telemetry
                val schedules = db.reminderDao().getAllRemindersDirect()
                val pendingItems = db.itemDao().getPendingItemsDirect()
                if (!isTest) {
                    AlarmScheduler.scheduleAllActive(
                        context = context,
                        wakeUpTime = prefs.wakeUpTime,
                        schedules = schedules,
                        pendingItems = pendingItems
                    )
                } else {
                    AlarmScheduler.syncAlarmTelemetry(context)
                }

                val displayName = prefs.userName.ifBlank { "King Israel" }

                val dynamicWakeGreeting = TtsGreetingHelper.getTimeAwareGreeting(displayName).removeSuffix(".")

                postHeadsUpNotification(
                    context = context,
                    notificationId = alarmId.hashCode(),
                    title = if (isWakeUp) dynamicWakeGreeting else title,
                    subtitle = if (isWakeUp) {
                        "7:30 AM Daily Plan Briefing - Tap to open"
                    } else if (!contactName.isNullOrBlank()) {
                        "Follow up with $contactName"
                    } else {
                        "Tap to view your execution plan"
                    },
                    isWakeUp = isWakeUp,
                    itemId = itemId,
                    contactName = contactName
                )

                val bypassQuiet = isWakeUp || isTest
                if (voiceEnabled && (bypassQuiet || !AlarmScheduler.isWithinQuietHours(prefs))) {
                    val customLine = if (itemId > 0L) {
                        "$displayName, reminder: $title."
                    } else {
                        null
                    }
                    val serviceIntent = Intent(context, VoiceReminderService::class.java).apply {
                        putExtra(VoiceReminderService.EXTRA_BRIEFING_TITLE, title)
                        putExtra(VoiceReminderService.EXTRA_BYPASS_QUIET, bypassQuiet)
                        putExtra(VoiceReminderService.EXTRA_IS_WAKE_UP, isWakeUp)
                        if (customLine != null) {
                            putExtra(VoiceReminderService.EXTRA_CUSTOM_TEXT, customLine)
                        }
                    }
                    try {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (_: Exception) {
                        // Notification already posted if OS restricted background FGS start
                    }
                }
            } finally {
                try {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                } catch (_: Exception) {
                }
                pendingResult.finish()
            }
        }
    }

    private fun postHeadsUpNotification(
        context: Context,
        notificationId: Int,
        title: String,
        subtitle: String,
        isWakeUp: Boolean,
        itemId: Long,
        contactName: String?
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Wake-Up Alarms & Heads-Up Briefings",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "7:30 AM wake-up daily plan briefings and high-priority reminders"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            context,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (isWakeUp) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppPending)

        if (isWakeUp) {
            builder.setFullScreenIntent(openAppPending, true)
        }

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

class EmailTaskActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ADD_EMAIL_TO_TASKS = "com.xestlab.assistant.ACTION_ADD_EMAIL_TO_TASKS"
        const val EXTRA_NOTIFICATION_ID = "extra_email_notif_id"
        const val EXTRA_EMAIL_ID = "extra_email_id"
        const val EXTRA_TASK_TITLE = "extra_email_task_title"
        const val EXTRA_SENDER = "extra_email_sender"
        const val EXTRA_SENDER_ADDRESS = "extra_email_sender_address"
        const val EXTRA_SUBJECT = "extra_email_subject"
        const val EXTRA_SNIPPET = "extra_email_snippet"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADD_EMAIL_TO_TASKS) return

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val emailId = intent.getStringExtra(EXTRA_EMAIL_ID).orEmpty()
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "Follow up email"
        val sender = intent.getStringExtra(EXTRA_SENDER) ?: "Email"
        val senderAddress = intent.getStringExtra(EXTRA_SENDER_ADDRESS).orEmpty()
        val subject = intent.getStringExtra(EXTRA_SUBJECT) ?: taskTitle
        val snippet = intent.getStringExtra(EXTRA_SNIPPET).orEmpty()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                db.itemDao().insertItem(
                    Item(
                        type = "TASK",
                        title = taskTitle,
                        details = "From $sender: $subject",
                        priority = "HIGH",
                        status = "PENDING",
                        sourceText = "imap_email_$emailId"
                    )
                )
                if (emailId.isNotBlank()) {
                    db.emailDao().markAddedToTasks(emailId)
                }
                EmailPriorityEngine.learnPositiveInteraction(
                    db = db,
                    senderDisplay = sender,
                    senderAddress = senderAddress,
                    subject = subject,
                    snippet = snippet
                )
            } finally {
                pendingResult.finish()
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
                if (securePrefs.isWakeWordEnabled) {
                    WakeWordService.postRebootTapToStartNotification(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
