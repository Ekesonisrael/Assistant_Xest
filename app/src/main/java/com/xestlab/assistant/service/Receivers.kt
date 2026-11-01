package com.xestlab.assistant.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.xestlab.assistant.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_ID) ?: "generic"
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_TITLE) ?: "Reminder"
        val contactName = intent.getStringExtra(AlarmScheduler.EXTRA_CONTACT_NAME)
        val itemId = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, -1L)
        val isWakeUp = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_WAKEUP, false)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (action) {
            AlarmScheduler.ACTION_ALARM_FIRE -> {
                val service = Intent(context, ReminderService::class.java).apply {
                    putExtra(ReminderService.EXTRA_REMINDER_ID, id)
                    putExtra(ReminderService.EXTRA_TITLE, title)
                    putExtra(
                        ReminderService.EXTRA_VOICE_ENABLED,
                        intent.getBooleanExtra(AlarmScheduler.EXTRA_VOICE_ENABLED, true)
                    )
                    if (contactName != null) {
                        putExtra(ReminderService.EXTRA_CONTACT_NAME, contactName)
                    }
                    if (itemId != -1L) {
                        putExtra(ReminderService.EXTRA_ITEM_ID, itemId)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(service)
                } else {
                    context.startService(service)
                }
            }

            AlarmScheduler.ACTION_WAKEUP_ALARM -> {
                val service = Intent(context, ReminderService::class.java).apply {
                    putExtra(ReminderService.EXTRA_REMINDER_ID, "wakeup")
                    putExtra(ReminderService.EXTRA_TITLE, "Wake-up Alarm")
                    putExtra(ReminderService.EXTRA_VOICE_ENABLED, true)
                    putExtra(ReminderService.EXTRA_IS_WAKEUP, true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(service)
                } else {
                    context.startService(service)
                }
            }

            AlarmScheduler.ACTION_OPEN_CHAT -> {
                manager.cancel(ReminderService.NOTIFICATION_ID)
                if (!contactName.isNullOrBlank()) {
                    AlarmScheduler.openChatChooser(context, contactName)
                }
            }

            AlarmScheduler.ACTION_NOTIFICATION_DONE -> {
                manager.cancel(ReminderService.NOTIFICATION_ID)
                AlarmScheduler.cancelNudges(context)
                if (itemId != -1L) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val db = AppDatabase.getInstance(context)
                        db.itemDao().updateStatus(itemId, "COMPLETED")
                    }
                }
            }

            AlarmScheduler.ACTION_NOTIFICATION_SNOOZE -> {
                manager.cancel(ReminderService.NOTIFICATION_ID)
                Log.d("AlarmReceiver", "Snoozing $id for 15 minutes")
                AlarmScheduler.scheduleSnooze(context, id, title, 15)
            }

            AlarmScheduler.ACTION_DISMISS_WAKEUP -> {
                manager.cancel(ReminderService.NOTIFICATION_ID)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    AlarmScheduler.scheduleAllActive(context.applicationContext)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
