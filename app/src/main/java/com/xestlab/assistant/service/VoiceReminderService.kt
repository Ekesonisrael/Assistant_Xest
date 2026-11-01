package com.xestlab.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xestlab.assistant.MainActivity
import com.xestlab.assistant.R
import com.xestlab.assistant.data.ReminderDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceReminderService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "xestlab_voice_reminders_channel"
        const val NOTIFICATION_ID = 4001
        const val UTTERANCE_ID = "morning_outreach_briefing"
    }

    private var tts: TextToSpeech? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var dataStore: ReminderDataStore
    private var pendingSpeechText: String? = null
    private var isTtsReady = false

    override fun onCreate() {
        super.onCreate()
        dataStore = ReminderDataStore(applicationContext)
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Build initial notification immediately to satisfy foreground service contract
        val initialNotification = buildNotification("Initializing morning briefing...")
        startForeground(NOTIFICATION_ID, initialNotification)

        serviceScope.launch {
            val state = dataStore.reminderFlow.first()
            val textToSpeak = "Good morning. Your target today is ${state.messageTarget} outreach messages. You have ${state.followUpTarget} follow-ups due."
            pendingSpeechText = textToSpeak

            // Update notification content text with the message
            val updatedNotification = buildNotification(textToSpeak)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, updatedNotification)

            if (isTtsReady) {
                speakUtterance(textToSpeak)
            }
        }

        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d("VoiceReminderService", "TTS started speaking: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d("VoiceReminderService", "TTS finished speaking. Stopping service and dismissing notification.")
                    cleanupAndStop()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e("VoiceReminderService", "TTS error on utterance: $utteranceId")
                    cleanupAndStop()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e("VoiceReminderService", "TTS error code $errorCode on utterance: $utteranceId")
                    cleanupAndStop()
                }
            })

            isTtsReady = true
            pendingSpeechText?.let { text ->
                speakUtterance(text)
            }
        } else {
            Log.e("VoiceReminderService", "TextToSpeech initialization failed with status: $status")
            cleanupAndStop()
        }
    }

    private fun speakUtterance(text: String) {
        Log.d("VoiceReminderService", "Speaking utterance: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun cleanupAndStop() {
        serviceScope.launch(Dispatchers.Main) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun buildNotification(contentText: String): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Done
        val doneIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_NOTIFICATION_DONE
        }
        val donePendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Snooze 15 minutes
        val snoozeIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_NOTIFICATION_SNOOZE
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            this,
            2,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Morning Voice Briefing")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_dialog_info, "Done", donePendingIntent)
            .addAction(android.R.drawable.ic_popup_sync, "Snooze", snoozePendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Speaks daily morning outreach targets aloud"
                enableVibration(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
