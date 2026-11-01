package com.xestlab.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class VoiceReminderService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "xestlab_voice_channel"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_STOP_SPEECH = "com.xestlab.assistant.ACTION_STOP_SPEECH"
        const val EXTRA_CUSTOM_TEXT = "extra_custom_text"
        const val EXTRA_BRIEFING_TITLE = "extra_briefing_title"
        const val EXTRA_BYPASS_QUIET = "extra_bypass_quiet"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingTextToSpeak: String? = null
    private var pendingSpeechRate: Float = 1.0f
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        tts = TextToSpeech(applicationContext, this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SPEECH) {
            stopPlaybackAndFinish()
            return START_NOT_STICKY
        }

        val briefingTitle = intent?.getStringExtra(EXTRA_BRIEFING_TITLE) ?: "Daily Plan Briefing"
        val customText = intent?.getStringExtra(EXTRA_CUSTOM_TEXT)
        val bypassQuiet = intent?.getBooleanExtra(EXTRA_BYPASS_QUIET, false) ?: false

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

            if (!bypassQuiet && AlarmScheduler.isWithinQuietHours(prefs)) {
                stopSelf()
                return@launch
            }

            val db = AppDatabase.getInstance(applicationContext)
            val repo = AssistantRepository(db, userPrefsStore)
            val securePrefs = SecurePreferences(applicationContext)

            val spokenScript = when {
                !customText.isNullOrBlank() -> customText
                securePrefs.isAiEnabled && securePrefs.hasGeminiKey() -> {
                    AiClient.generateCoachReminder(briefingTitle, db, prefs, securePrefs)
                        ?: repo.buildSpokenDailyPlan(overrideGreetingPrefix = briefingTitle)
                }
                else -> repo.buildSpokenDailyPlan(overrideGreetingPrefix = briefingTitle)
            }

            if (securePrefs.isFishAudioEnabled && securePrefs.fishAudioApiKey.isNotBlank()) {
                val mp3Bytes = FishAudioClient.synthesizeMp3(
                    text = spokenScript,
                    apiKey = securePrefs.fishAudioApiKey,
                    voiceId = securePrefs.fishAudioVoiceId
                )
                if (mp3Bytes != null && playMp3Bytes(mp3Bytes)) {
                    return@launch
                }
            }

            speakWithAndroidTts(spokenScript, prefs.voiceSpeed)
        }

        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val langResult = engine.setLanguage(Locale.getDefault())
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    stopSelf()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    stopSelf()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    stopSelf()
                }
            })
            isTtsReady = true
            pendingTextToSpeak?.let { text ->
                pendingTextToSpeak = null
                speakWithAndroidTts(text, pendingSpeechRate)
            }
        } else {
            stopSelf()
        }
    }

    private fun speakWithAndroidTts(text: String, rate: Float) {
        if (!isTtsReady) {
            pendingTextToSpeak = text
            pendingSpeechRate = rate
            return
        }
        val engine = tts
        if (engine == null) {
            stopSelf()
            return
        }
        engine.setSpeechRate(rate.coerceIn(0.6f, 1.8f))
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "xestlab_briefing_utt")
        if (result == TextToSpeech.ERROR) {
            stopSelf()
        }
    }

    private fun playMp3Bytes(bytes: ByteArray): Boolean {
        return try {
            val tempFile = File(cacheDir, "briefing_tts.mp3")
            FileOutputStream(tempFile).use { it.write(bytes) }
            val player = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    stopSelf()
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    mediaPlayer = null
                    stopSelf()
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun stopPlaybackAndFinish() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Briefings",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Spoken daily plan briefings and reminders"
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
            .setContentText("Reading your daily plan aloud")
            .setContentIntent(openPending)
            .addAction(0, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }
}
