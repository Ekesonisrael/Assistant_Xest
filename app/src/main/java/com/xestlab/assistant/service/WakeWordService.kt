package com.xestlab.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xestlab.assistant.MainActivity
import com.xestlab.assistant.R
import com.xestlab.assistant.ai.AiClient
import com.xestlab.assistant.ai.AssistantParser
import com.xestlab.assistant.ai.ParsedIntent
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.UserPreferencesDataStore
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.repository.AssistantRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service running the 3-model openWakeWord ONNX Runtime pipeline
 * (`melspectrogram.onnx` -> `embedding_model.onnx` -> `hey_jarvis_v0.1.onnx`) in a background
 * Coroutine and executing the full Jarvis conversation loop when `score > 0.5`:
 *
 * 1. Stop the AudioRecord stream.
 * 2. Play an activation chime.
 * 3. Speak "Yes? What do you need?" via VoiceEngine (ElevenLabs or Android TTS fallback).
 * 4. Start SpeechRecognizer to capture the user's spoken command.
 * 5. Send the command to Groq ("openai/gpt-oss-120b").
 * 6. Speak Groq's reply via VoiceEngine.
 * 7. Restart the WakeWordService ONNX listening loop ("Listening for 'Hey Jarvis'...").
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"

        const val CHANNEL_ID = "xestlab_wake_word_channel"
        const val NOTIFICATION_ID = 7701
        const val ACTIVE_NOTIFICATION_TEXT = "Listening for 'Hey Jarvis'..."

        const val ACTION_START_OR_RELOAD = "com.xestlab.assistant.action.WAKE_START_OR_RELOAD"
        const val ACTION_STOP_SERVICE = "com.xestlab.assistant.action.WAKE_STOP_SERVICE"
        const val ACTION_TEMP_PAUSE = "com.xestlab.assistant.action.WAKE_TEMP_PAUSE"
        const val ACTION_TEMP_RESUME = "com.xestlab.assistant.action.WAKE_TEMP_RESUME"
        const val ACTION_TEST_JARVIS_LOOP = "com.xestlab.assistant.action.WAKE_TEST_JARVIS_LOOP"

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        private val _statusMessageFlow = MutableStateFlow(
            "openWakeWord ONNX service idle. Enable Wake Word in Settings to listen for 'Hey Jarvis'."
        )
        val statusMessageFlow: StateFlow<String> = _statusMessageFlow.asStateFlow()

        private val _statusIsErrorFlow = MutableStateFlow(false)
        val statusIsErrorFlow: StateFlow<Boolean> = _statusIsErrorFlow.asStateFlow()

        fun updateExternalStatus(message: String, isError: Boolean) {
            _statusMessageFlow.value = message
            _statusIsErrorFlow.value = isError
        }

        fun hasRecordAudioPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun start(context: Context) {
            val appContext = context.applicationContext
            if (!hasRecordAudioPermission(appContext)) {
                updateExternalStatus(
                    "RECORD_AUDIO permission is required before starting WakeWordService.",
                    true
                )
                return
            }
            val intent = Intent(appContext, WakeWordService::class.java).apply {
                action = ACTION_START_OR_RELOAD
            }
            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WakeWordService", e)
                updateExternalStatus(
                    "Failed to start WakeWordService: ${e.javaClass.simpleName}: ${e.message}",
                    true
                )
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            try {
                appContext.stopService(Intent(appContext, WakeWordService::class.java))
            } catch (_: Exception) {
            }
            _isRunningFlow.value = false
            updateExternalStatus("Background 'Hey Jarvis' wake word listener stopped.", false)
        }

        fun pauseTemporarily(context: Context) {
            if (!_isRunningFlow.value) return
            val appContext = context.applicationContext
            val intent = Intent(appContext, WakeWordService::class.java).apply {
                action = ACTION_TEMP_PAUSE
            }
            try {
                appContext.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun resumeTemporarily(context: Context) {
            if (!_isRunningFlow.value) return
            val appContext = context.applicationContext
            val intent = Intent(appContext, WakeWordService::class.java).apply {
                action = ACTION_TEMP_RESUME
            }
            try {
                appContext.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun triggerJarvisLoopTest(context: Context) {
            val appContext = context.applicationContext
            if (!hasRecordAudioPermission(appContext)) {
                updateExternalStatus(
                    "Grant RECORD_AUDIO permission first to test the Jarvis conversation loop.",
                    true
                )
                return
            }
            val intent = Intent(appContext, WakeWordService::class.java).apply {
                action = ACTION_TEST_JARVIS_LOOP
            }
            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (e: Exception) {
                updateExternalStatus(
                    "Unable to trigger Jarvis loop: ${e.javaClass.simpleName}: ${e.message}",
                    true
                )
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var securePrefs: SecurePreferences
    private lateinit var userPrefsStore: UserPreferencesDataStore
    private lateinit var db: AppDatabase
    private lateinit var repository: AssistantRepository

    private var openWakeWordPipeline: OpenWakeWordPipeline? = null
    private var detectionLoopJob: Job? = null

    private var voiceEngine: VoiceEngine? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var toneGenerator: ToneGenerator? = null

    private val isInConversationLoop = AtomicBoolean(false)
    private var isTemporarilyPausedForUiMic = false
    private var isStopping = false

    private val recognizerWatchdogRunnable = Runnable {
        if (isInConversationLoop.get()) {
            destroySpeechRecognizerOnMain()
            restartWakeWordLoopOnMain()
        }
    }

    override fun onCreate() {
        super.onCreate()
        securePrefs = SecurePreferences(applicationContext)
        userPrefsStore = UserPreferencesDataStore(applicationContext)
        db = AppDatabase.getInstance(applicationContext)
        repository = AssistantRepository(db, userPrefsStore)
        openWakeWordPipeline = OpenWakeWordPipeline(applicationContext)
        voiceEngine = VoiceEngine(
            context = applicationContext,
            securePrefs = securePrefs
        )
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                securePrefs.isWakeWordEnabled = false
                updateExternalStatus("Wake word disabled from notification.", false)
                stopDetectionCoroutine()
                stopForegroundSafely()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TEMP_PAUSE -> {
                isTemporarilyPausedForUiMic = true
                stopDetectionCoroutine()
                updateForegroundNotification("Paused briefly while in-app mic is active")
                return START_STICKY
            }

            ACTION_TEMP_RESUME -> {
                isTemporarilyPausedForUiMic = false
                if (!isInConversationLoop.get() && securePrefs.isWakeWordEnabled) {
                    startOnnxDetectionLoop()
                }
                return START_STICKY
            }

            ACTION_TEST_JARVIS_LOOP -> {
                if (!promoteToForeground("Testing 'Hey Jarvis' conversation loop...")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                _isRunningFlow.value = true
                stopDetectionCoroutine()
                mainHandler.post {
                    onWakeWordTriggered(score = 1.0f)
                }
                return START_STICKY
            }
        }

        if (!hasRecordAudioPermission(this)) {
            updateExternalStatus(
                "Microphone permission (RECORD_AUDIO) is required. Grant permission in Settings.",
                true
            )
            stopSelf()
            return START_NOT_STICKY
        }

        if (!promoteToForeground(ACTIVE_NOTIFICATION_TEXT)) {
            stopSelf()
            return START_NOT_STICKY
        }

        _isRunningFlow.value = true
        isTemporarilyPausedForUiMic = false

        if (!isInConversationLoop.get()) {
            startOnnxDetectionLoop()
        }

        return START_STICKY
    }

    /**
     * Starts the background Coroutine running the 3-model openWakeWord ONNX pipeline:
     * AudioRecord (16kHz PCM) -> melspectrogram.onnx -> embedding_model.onnx -> hey_jarvis_v0.1.onnx
     */
    private fun startOnnxDetectionLoop() {
        if (isStopping || isTemporarilyPausedForUiMic || isInConversationLoop.get()) {
            return
        }

        stopDetectionCoroutine()

        val pipeline = openWakeWordPipeline ?: OpenWakeWordPipeline(applicationContext).also {
            openWakeWordPipeline = it
        }

        updateForegroundNotification(ACTIVE_NOTIFICATION_TEXT)
        updateExternalStatus(
            "$ACTIVE_NOTIFICATION_TEXT (ONNX 3-model pipeline: melspectrogram -> embedding -> hey_jarvis_v0.1, threshold > 0.5)",
            false
        )

        detectionLoopJob = serviceScope.launch {
            var lastUiReportMs = 0L
            var peakRecentScore = 0.0f

            while (!isStopping &&
                !isTemporarilyPausedForUiMic &&
                !isInConversationLoop.get() &&
                securePrefs.isWakeWordEnabled
            ) {
                try {
                    var triggeredScore: Float? = null
                    pipeline.runDetectionLoop(
                        onScoreUpdate = { score ->
                            if (score > peakRecentScore) {
                                peakRecentScore = score
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastUiReportMs >= 1_500L) {
                                lastUiReportMs = now
                                val formatted = String.format(Locale.US, "%.3f", peakRecentScore)
                                updateExternalStatus(
                                    "$ACTIVE_NOTIFICATION_TEXT | Peak score: $formatted (triggers > 0.500)",
                                    false
                                )
                                peakRecentScore = 0.0f
                            }
                        },
                        onDetected = { score ->
                            triggeredScore = score
                        }
                    )

                    val hitScore = triggeredScore
                    if (hitScore != null) {
                        mainHandler.post {
                            onWakeWordTriggered(hitScore)
                        }
                        break
                    } else {
                        break
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "openWakeWord ONNX pipeline error: ${e.message}", e)
                    updateExternalStatus(
                        "ONNX pipeline error (${e.javaClass.simpleName}: ${e.message}). Retrying in 2s...",
                        true
                    )
                    updateForegroundNotification("Wake word audio busy — retrying...")
                    delay(2_000L)
                }
            }
        }
    }

    private fun stopDetectionCoroutine() {
        openWakeWordPipeline?.stopAudioCapture()
        val job = detectionLoopJob
        detectionLoopJob = null
        job?.cancel()
    }

    /**
     * Triggered when `hey_jarvis_v0.1.onnx` score > 0.5:
     * 1. AudioRecord stream is already stopped & released.
     * 2. Play chime.
     * 3. Speak "Yes? What do you need?" via VoiceEngine (ElevenLabs / Android TTS).
     * 4. Start SpeechRecognizer to capture command.
     */
    private fun onWakeWordTriggered(score: Float) {
        if (isStopping) return
        if (!isInConversationLoop.compareAndSet(false, true)) return

        stopDetectionCoroutine()

        val scoreStr = String.format(Locale.US, "%.2f", score)
        updateExternalStatus(
            "'Hey Jarvis' detected (score=$scoreStr) — playing chime & listening...",
            false
        )
        updateForegroundNotification("'Hey Jarvis' detected — Prompting...")

        playActivationChime()

        // Allow 220ms for chime to finish cleanly before speaking prompt
        mainHandler.postDelayed({
            if (isStopping) {
                isInConversationLoop.set(false)
                return@postDelayed
            }
            serviceScope.launch {
                val speechRate = userPrefsStore.getSnapshot().voiceSpeed.coerceIn(0.6f, 1.8f)
                mainHandler.post {
                    voiceEngine?.speak(
                        text = "Yes? What do you need?",
                        fallbackSpeechRate = speechRate,
                        onComplete = {
                            mainHandler.postDelayed({
                                startCommandSpeechRecognizer()
                            }, 200L)
                        }
                    ) ?: run {
                        startCommandSpeechRecognizer()
                    }
                }
            }
        }, 220L)
    }

    private fun playActivationChime() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
        } catch (_: Exception) {
        }
    }

    /**
     * Starts Android SpeechRecognizer on the Main thread to capture the user's command.
     */
    private fun startCommandSpeechRecognizer() {
        if (isStopping) {
            isInConversationLoop.set(false)
            return
        }
        if (!hasRecordAudioPermission(this) || !SpeechRecognizer.isRecognitionAvailable(this)) {
            updateExternalStatus(
                "SpeechRecognizer unavailable or RECORD_AUDIO missing. Restarting 'Hey Jarvis' listener.",
                true
            )
            restartWakeWordLoopOnMain()
            return
        }

        destroySpeechRecognizerOnMain()
        updateExternalStatus("Listening to your command...", false)
        updateForegroundNotification("Listening to your command...")

        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    Log.w(TAG, "SpeechRecognizer error code=$error; restarting wake word loop")
                    mainHandler.removeCallbacks(recognizerWatchdogRunnable)
                    destroySpeechRecognizerOnMain()
                    mainHandler.postDelayed({
                        restartWakeWordLoopOnMain()
                    }, 250L)
                }

                override fun onResults(results: Bundle?) {
                    mainHandler.removeCallbacks(recognizerWatchdogRunnable)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenCommand = matches?.firstOrNull()?.trim().orEmpty()
                    destroySpeechRecognizerOnMain()

                    if (spokenCommand.isEmpty()) {
                        restartWakeWordLoopOnMain()
                    } else {
                        routeCommandToGroqAndSpeak(spokenCommand)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val recognizeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 2)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1500L
                )
            }

            mainHandler.removeCallbacks(recognizerWatchdogRunnable)
            mainHandler.postDelayed(recognizerWatchdogRunnable, 15_000L)
            recognizer.startListening(recognizeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "SpeechRecognizer failed to start: ${e.message}", e)
            mainHandler.removeCallbacks(recognizerWatchdogRunnable)
            destroySpeechRecognizerOnMain()
            updateExternalStatus("SpeechRecognizer error: ${e.message}", true)
            restartWakeWordLoopOnMain()
        }
    }

    /**
     * Routes the recognized voice command to Groq ("openai/gpt-oss-120b"), speaks the response
     * aloud via VoiceEngine (ElevenLabs / Android TTS), and restarts the ONNX wake-word loop.
     */
    private fun routeCommandToGroqAndSpeak(spokenCommand: String) {
        updateExternalStatus("Sending to Groq: \"$spokenCommand\"", false)
        updateForegroundNotification("Thinking...")

        serviceScope.launch {
            try {
                repository.recordTurn("USER", spokenCommand)
                val userPrefs = userPrefsStore.getSnapshot()
                val speechRate = userPrefs.voiceSpeed.coerceIn(0.6f, 1.8f)

                val parsed = AssistantParser.parse(spokenCommand)
                val replyToSpeak: String = when (parsed) {
                    is ParsedIntent.ExplicitNote -> {
                        repository.saveItem(
                            Item(
                                type = "NOTE",
                                title = parsed.title,
                                details = parsed.details,
                                sourceText = parsed.sourceText
                            )
                        )
                        val noteReply = "Note saved: ${parsed.title}."
                        repository.recordTurn("ASSISTANT", noteReply)
                        noteReply
                    }

                    is ParsedIntent.CheckEmails -> {
                        if (securePrefs.isImapEnabled &&
                            securePrefs.imapUser.isNotBlank() &&
                            securePrefs.imapPassword.isNotBlank() &&
                            db.emailDao().getRecentUnreadDirect(1).isEmpty()
                        ) {
                            EmailHelper.syncUnreadEmailsAndNotify(
                                applicationContext,
                                postNotifications = false
                            )
                        }
                        val emailSummary = repository.buildUnreadEmailSpokenSummary()
                        repository.recordTurn("ASSISTANT", emailSummary)
                        emailSummary
                    }

                    else -> {
                        val aiResult = AiClient.generateAssistantResponse(
                            userPrompt = spokenCommand,
                            db = db,
                            prefs = userPrefs,
                            securePrefs = securePrefs
                        )
                        aiResult.fold(
                            onSuccess = { aiResp ->
                                repository.recordTurn("ASSISTANT", aiResp.replyText)
                                aiResp.replyText
                            },
                            onFailure = { err ->
                                val diag = AiClient.formatThrowableDiagnostic(err)
                                repository.recordTurn(
                                    "ASSISTANT",
                                    "${AiClient.AI_BRAIN_UNREACHABLE_MESSAGE}\n[Diagnostic: $diag]"
                                )
                                updateExternalStatus("Groq error: $diag", true)
                                AiClient.AI_BRAIN_UNREACHABLE_MESSAGE
                            }
                        )
                    }
                }

                mainHandler.post {
                    if (isStopping) {
                        isInConversationLoop.set(false)
                        return@post
                    }
                    updateForegroundNotification("Speaking response...")
                    voiceEngine?.speak(
                        text = replyToSpeak,
                        fallbackSpeechRate = speechRate,
                        onComplete = {
                            mainHandler.postDelayed({
                                restartWakeWordLoopOnMain()
                            }, 250L)
                        }
                    ) ?: restartWakeWordLoopOnMain()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Jarvis conversation loop error: ${e.message}", e)
                mainHandler.post {
                    updateExternalStatus("Jarvis loop error: ${e.message}", true)
                    restartWakeWordLoopOnMain()
                }
            }
        }
    }

    private fun restartWakeWordLoopOnMain() {
        isInConversationLoop.set(false)
        if (isStopping) return

        if (!securePrefs.isWakeWordEnabled) {
            stopDetectionCoroutine()
            stopForegroundSafely()
            stopSelf()
            return
        }

        if (!isTemporarilyPausedForUiMic) {
            startOnnxDetectionLoop()
        }
    }

    private fun destroySpeechRecognizerOnMain() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        } finally {
            speechRecognizer = null
        }
    }

    private fun promoteToForeground(statusLine: String): Boolean {
        val notification = buildPersistentNotification(statusLine)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val fgsType = if (hasRecordAudioPermission(this)) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Foreground service start blocked by OS", e)
            updateExternalStatus(
                "Foreground microphone service blocked by OS: ${e.javaClass.simpleName}: ${e.message}",
                true
            )
            false
        }
    }

    private fun updateForegroundNotification(statusLine: String) {
        if (isStopping) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIFICATION_ID, buildPersistentNotification(statusLine))
        } catch (_: SecurityException) {
        }
    }

    private fun buildPersistentNotification(statusLine: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WakeWordService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this,
            NOTIFICATION_ID + 1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Xestlab Assistant")
            .setContentText(statusLine)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Wake Word Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent openWakeWord ONNX listener ('Hey Jarvis')"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopForegroundSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        isStopping = true
        _isRunningFlow.value = false
        mainHandler.removeCallbacksAndMessages(null)
        destroySpeechRecognizerOnMain()
        stopDetectionCoroutine()
        openWakeWordPipeline?.close()
        openWakeWordPipeline = null
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {
        }
        voiceEngine?.shutdown()
        voiceEngine = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
