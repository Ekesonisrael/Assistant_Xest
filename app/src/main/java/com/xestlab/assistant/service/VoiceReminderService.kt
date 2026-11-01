package com.xestlab.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.xestlab.assistant.MainActivity
import com.xestlab.assistant.R
import com.xestlab.assistant.ai.AiClient
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.UserPreferencesDataStore
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.repository.AssistantRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VoiceReminderService : Service() {

    companion object {
        const val CHANNEL_ID = "xestlab_voice_channel"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_STOP_SPEECH = "com.xestlab.assistant.ACTION_STOP_SPEECH"
        const val EXTRA_CUSTOM_TEXT = "extra_custom_text"
        const val EXTRA_BRIEFING_TITLE = "extra_briefing_title"
        const val EXTRA_BYPASS_QUIET = "extra_bypass_quiet"
        const val EXTRA_IS_WAKE_UP = "extra_is_wake_up"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var securePrefs: SecurePreferences
    private lateinit var voiceEngine: VoiceEngine
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        securePrefs = SecurePreferences(applicationContext)
        voiceEngine = VoiceEngine(applicationContext, securePrefs)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SPEECH) {
            stopPlaybackAndFinish()
            return START_NOT_STICKY
        }

        val briefingTitle = intent?.getStringExtra(EXTRA_BRIEFING_TITLE) ?: "Morning Daily Plan"
        val customText = intent?.getStringExtra(EXTRA_CUSTOM_TEXT)
        val bypassQuiet = intent?.getBooleanExtra(EXTRA_BYPASS_QUIET, false) ?: false
        val isWakeUp = intent?.getBooleanExtra(EXTRA_IS_WAKE_UP, false) ?: false

        acquireSpeechWakeLock()

        val fgNotification = buildForegroundNotification(briefingTitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                fgNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, fgNotification)
        }

        serviceScope.launch {
            val userPrefsStore = UserPreferencesDataStore(applicationContext)
            val prefs = userPrefsStore.getSnapshot()

            if (!bypassQuiet && !isWakeUp && AlarmScheduler.isWithinQuietHours(prefs)) {
                stopPlaybackAndFinish()
                return@launch
            }

            val db = AppDatabase.getInstance(applicationContext)
            val repo = AssistantRepository(db, userPrefsStore)

            val spokenScript = when {
                !customText.isNullOrBlank() -> customText
                isWakeUp -> {
                    // Natural 7:30 AM greeting: "Good morning King Israel, how are you doing? Let's start with..."
                    AiClient.generateCoachBriefing(briefingTitle, db, prefs, securePrefs)
                        ?: repo.buildSpokenDailyPlan(isWakeUpAlarm = true)
                }
                else -> {
                    AiClient.generateCoachBriefing(briefingTitle, db, prefs, securePrefs)
                        ?: repo.buildSpokenDailyPlan(overrideGreetingPrefix = briefingTitle)
                }
            }

            voiceEngine.speak(
                text = spokenScript,
                fallbackSpeechRate = prefs.voiceSpeed,
                onComplete = { stopPlaybackAndFinish() }
            )
        }

        return START_NOT_STICKY
    }

    private fun acquireSpeechWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "xestlab:VoiceBriefingWakeLock"
                ).apply {
                    acquire(180_000L)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun stopPlaybackAndFinish() {
        try {
            voiceEngine.stop()
        } catch (_: Exception) {
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        releaseWakeLock()
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            voiceEngine.shutdown()
        } catch (_: Exception) {
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Briefings",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active voice playback for morning plans and reminders"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(title: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VoiceReminderService::class.java).apply {
            action = ACTION_STOP_SPEECH
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("Speaking your daily plan aloud")
            .setContentIntent(openPending)
            .addAction(0, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }
}
