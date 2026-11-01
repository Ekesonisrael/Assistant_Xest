package com.xestlab.assistant.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.xestlab.assistant.MainActivity
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.local.AppDatabase
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class ReminderService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeechText: String? = null
    private var speechSpeed = 1.0f

    companion object {
        const val CHANNEL_ID = "xestlab_reminders_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_VOICE_ENABLED = "extra_voice_enabled"
        const val EXTRA_CONTACT_NAME = "extra_contact_name"
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_IS_WAKEUP = "extra_is_wakeup"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reminderId = intent?.getStringExtra(EXTRA_REMINDER_ID) ?: "generic"
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Reminder"
        val voiceEnabled = intent?.getBooleanExtra(EXTRA_VOICE_ENABLED, true) ?: true
        val contactName = intent?.getStringExtra(EXTRA_CONTACT_NAME)
        val itemId = intent?.getLongExtra(EXTRA_ITEM_ID, -1L) ?: -1L
        val isWakeUp = intent?.getBooleanExtra(EXTRA_IS_WAKEUP, false) ?: false

        // Display Foreground Notification immediately
        val initialNotification = buildNotification(title, reminderId, contactName, itemId, isWakeUp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val settings = db.settingsDao().getSettingsDirect()
            val secure = SecurePreferences(applicationContext)
            speechSpeed = settings?.voiceSpeed ?: 1.0f

            // Determine spoken text
            val fallbackText = when {
                isWakeUp -> {
                    val pending = db.itemDao().getPendingItemsDirect()
                    val tasksCount = pending.size
                    "Good morning. Here is your plan for today. You have $tasksCount items queued. Let's make today count."
                }
                !contactName.isNullOrBlank() -> {
                    "Reminder: message $contactName regarding $title."
                }
                reminderId == "test" -> {
                    "This is a test reminder. Exact alarms and voice synthesis are active."
                }
                else -> {
                    VoiceEngine.generateMessage(db, reminderId, title)
                }
            }

            var spokenText = fallbackText

            // Optional AI coaching prompt with 6-second timeout fallback
            if (secure.isAiEnabled && secure.hasGeminiKey() && com.xestlab.assistant.ai.AiClient.isNetworkAvailable(applicationContext)) {
                try {
                    val aiMessage = withTimeoutOrNull(6000L) {
                        val contextJson = com.xestlab.assistant.ai.AiClient.buildLocalContext(db)
                        com.xestlab.assistant.ai.AiClient.generateCoachReminder(
                            apiKey = secure.geminiApiKey,
                            modelName = secure.geminiModel,
                            contextJson = contextJson,
                            reminderType = if (isWakeUp) "morning_wakeup" else reminderId
                        )
                    }
                    if (!aiMessage.isNullOrBlank()) {
                        spokenText = aiMessage.trim()
                    }
                } catch (e: Exception) {
                    // Keep fallbackText
                }
            }

            // Update notification with the real text
            val updatedNotification = buildNotification(title, reminderId, contactName, itemId, isWakeUp, spokenText)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, updatedNotification)

            // Check quiet hours
            val inQuietHours = isCurrentTimeInQuietHours(
                settings?.quietHoursStart ?: "22:00",
                settings?.quietHoursEnd ?: "07:00",
                settings?.quietHoursEnabled ?: true
            )

            // Reschedule next recurrence for built-in reminders
            val reminder = db.reminderDao().getReminderById(reminderId)
            if (reminder != null && reminder.isRepeating) {
                AlarmScheduler.scheduleReminder(applicationContext, reminder)
            }

            // Re-arm wake-up alarm for tomorrow if it fired
            if (isWakeUp && secure.isWakeUpEnabled) {
                AlarmScheduler.scheduleWakeUpAlarm(applicationContext, secure.wakeUpTime)
            }

            // After evening check-in or nudge, re-nudge in 30 minutes if unfinished
            if (reminderId == "evening" || reminderId == "nudge") {
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val doneIds = db.dailyLogDao().getLogsForDateDirect(todayStr)
                    .filter { it.isDone }.map { it.itemId }.toSet()
                val unfinished = db.checklistDao().getAllItemsDirect().count { it.id !in doneIds }
                if (unfinished > 0) {
                    AlarmScheduler.scheduleNudge(applicationContext, 30)
                }
            }

            val shouldSpeak = voiceEnabled && (!inQuietHours || isWakeUp)

            if (shouldSpeak) {
                // Check if Fish Audio is enabled
                var fishAudioSuccess = false
                if (secure.isFishAudioEnabled && secure.fishAudioApiKey.isNotBlank()) {
                    fishAudioSuccess = FishAudioClient.playFishAudio(
                        context = applicationContext,
                        apiKey = secure.fishAudioApiKey,
                        voiceId = secure.fishAudioVoiceId,
                        text = spokenText,
                        onCompletion = {
                            cleanupAndStop()
                        }
                    )
                }

                if (!fishAudioSuccess) {
                    speakMessage(spokenText)
                }
            } else {
                delay(3000)
                cleanupAndStop()
            }
        }

        return START_NOT_STICKY
    }

    private fun isCurrentTimeInQuietHours(start: String, end: String, enabled: Boolean): Boolean {
        if (!enabled) return false
        try {
            val now = Calendar.getInstance()
            val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

            val sParts = start.split(":")
            val startMinutes = sParts[0].toInt() * 60 + sParts[1].toInt()

            val eParts = end.split(":")
            val endMinutes = eParts[0].toInt() * 60 + eParts[1].toInt()

            return if (startMinutes < endMinutes) {
                currentMinutes in startMinutes..endMinutes
            } else {
                currentMinutes >= startMinutes || currentMinutes < endMinutes
            }
        } catch (e: Exception) {
            return false
        }
    }

    private fun speakMessage(text: String) {
        if (isTtsReady && tts != null) {
            tts?.setSpeechRate(speechSpeed)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_utterance")
        } else {
            pendingSpeechText = text
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.ENGLISH
            tts?.setSpeechRate(speechSpeed)
            isTtsReady = true

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    cleanupAndStop()
                }
                override fun onError(utteranceId: String?) {
                    cleanupAndStop()
                }
            })

            pendingSpeechText?.let {
                speakMessage(it)
                pendingSpeechText = null
            }
        } else {
            cleanupAndStop()
        }
    }

    private fun cleanupAndStop() {
        serviceScope.launch(Dispatchers.Main) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun buildNotification(
        title: String,
        reminderId: String,
        contactName: String?,
        itemId: Long,
        isWakeUp: Boolean,
        contentText: String? = null
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_SCREEN", if (isWakeUp) "home" else "plan")
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Assistant: $title")
            .setContentText(contentText ?: "Accountability check-in...")
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText ?: "Tap to open."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)

        if (!contactName.isNullOrBlank()) {
            // Person reminder buttons: Open chat, Done, Snooze
            val chatIntent = Intent(this, AlarmReceiver::class.java).apply {
                action = AlarmScheduler.ACTION_OPEN_CHAT
                putExtra(AlarmScheduler.EXTRA_CONTACT_NAME, contactName)
            }
            val chatPendingIntent = PendingIntent.getBroadcast(
                this,
                11,
                chatIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_send, "Open chat", chatPendingIntent)

            val doneIntent = Intent(this, AlarmReceiver::class.java).apply {
                action = AlarmScheduler.ACTION_NOTIFICATION_DONE
                putExtra(AlarmScheduler.EXTRA_ITEM_ID, itemId)
            }
            val donePendingIntent = PendingIntent.getBroadcast(
                this,
                12,
                doneIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_dialog_info, "Done", donePendingIntent)

            val snoozeIntent = Intent(this, AlarmReceiver::class.java).apply {
                action = AlarmScheduler.ACTION_NOTIFICATION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminderId)
                putExtra(AlarmScheduler.EXTRA_REMINDER_TITLE, title)
            }
            val snoozePendingIntent = PendingIntent.getBroadcast(
                this,
                13,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze 15m", snoozePendingIntent)

        } else if (isWakeUp) {
            // Wake-up alarm buttons: Dismiss, Snooze 10m
            val dismissIntent = Intent(this, AlarmReceiver::class.java).apply {
                action = AlarmScheduler.ACTION_DISMISS_WAKEUP
            }
            val dismissPending = PendingIntent.getBroadcast(
                this,
                21,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPending)

            val snoozeIntent = Intent(this, AlarmReceiver::class.java).apply {
                action = AlarmScheduler.ACTION_NOTIFICATION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_REMINDER_ID, "wakeup")
                putExtra(AlarmScheduler.EXTRA_REMINDER_TITLE, "Wake-up")
            }
            val snoozePending = PendingIntent.getBroadcast(
                this,
                22,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze 10m", snoozePending)

        } else {
            // Standard accountability reminder
            val doneIntent = Intent(this, AlarmReceiver::class.java).apply {
                action = AlarmScheduler.ACTION_NOTIFICATION_DONE
                putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminderId)
            }
            val donePendingIntent = PendingIntent.getBroadcast(
                this,
                1,
                doneIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_dialog_info, "Done", donePendingIntent)

            val snoozeIntent = Intent(this, AlarmReceiver::class.java).apply {
                action = AlarmScheduler.ACTION_NOTIFICATION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminderId)
                putExtra(AlarmScheduler.EXTRA_REMINDER_TITLE, title)
            }
            val snoozePendingIntent = PendingIntent.getBroadcast(
                this,
                2,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze 15m", snoozePendingIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scheduled Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Accountability voice check-ins, briefings, and wake-up alarms"
                enableLights(true)
                lightColor = Color.parseColor("#2BA5A0")
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
