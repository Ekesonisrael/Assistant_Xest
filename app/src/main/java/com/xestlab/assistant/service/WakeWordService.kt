package com.xestlab.assistant.service

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.PowerManager
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resilient Foreground Service running the 3-model openWakeWord ONNX Runtime pipeline
 * (`melspectrogram.onnx` -> `embedding_model.onnx` -> `hey_jarvis_v0.1.onnx`) in an
 * infinite `while (isListening)` Coroutine loop:
 *
 * - Dynamically sizes `AudioRecord` via `AudioRecord.getMinBufferSize()`.
 * - Never breaks on empty/null reads; catches & logs any audio/ONNX exception and immediately
 *   restarts the recording loop so wake-word listening never times out or drops out.
 * - Holds a `PARTIAL_WAKE_LOCK` while active so CPU inference is never throttled when the screen dims.
 * - Executes the full Jarvis conversation loop when `score > 0.20`:
 *   1. Stop the AudioRecord stream.
 *   2. Play activation chime.
 *   3. Speak "Yes? What do you need?" via VoiceEngine (ElevenLabs or Android TTS fallback).
 *   4. Start SpeechRecognizer to capture command.
 *   5. Route to Groq ("openai/gpt-oss-120b") or local briefing/task intents.
 *   6. Speak response via VoiceEngine.
 *   7. Immediately restart the infinite `while (isListening)` wake-word loop.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"

        const val CHANNEL_ID = "xestlab_wake_word_channel"
        const val NOTIFICATION_ID = 7701
        const val ACTIVE_NOTIFICATION_TEXT = "Listening for 'Hey Jarvis'..."
        const val WAKE_WORD_THRESHOLD = 0.20f

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
    private var wakeLock: PowerManager.WakeLock? = null

    private var voiceEngine: VoiceEngine? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var toneGenerator: ToneGenerator? = null

    private val isListeningState = AtomicBoolean(false)
    private val isInConversationLoop = AtomicBoolean(false)
    private val isTemporarilyPausedForUiMic = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)

    val isListening: Boolean
        get() = isListeningState.get()

    private val recognizerWatchdogRunnable = Runnable {
        if (isInConversationLoop.get()) {
            Log.w(TAG, "SpeechRecognizer watchdog timeout — resuming 'Hey Jarvis' wake word loop")
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
                isListeningState.set(false)
                updateExternalStatus("Wake word disabled from notification.", false)
                stopDetectionCoroutine()
                releaseCpuWakeLock()
                stopForegroundSafely()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TEMP_PAUSE -> {
                isTemporarilyPausedForUiMic.set(true)
                isListeningState.set(false)
                stopDetectionCoroutine()
                updateForegroundNotification("Paused briefly while in-app mic is active")
                return START_STICKY
            }

            ACTION_TEMP_RESUME -> {
                isTemporarilyPausedForUiMic.set(false)
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
                acquireCpuWakeLock()
                _isRunningFlow.value = true
                isListeningState.set(false)
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

        acquireCpuWakeLock()
        _isRunningFlow.value = true
        isTemporarilyPausedForUiMic.set(false)

        if (!isInConversationLoop.get()) {
            startOnnxDetectionLoop()
        }

        return START_STICKY
    }

    /**
     * Starts an infinite `while (isListening)` Coroutine running the 3-model openWakeWord ONNX
     * pipeline (`AudioRecord` -> `melspectrogram.onnx` -> `embedding_model.onnx` -> `hey_jarvis_v0.1.onnx`).
     * Never breaks on transient empty reads or recoverable audio/ONNX exceptions.
     */
    private fun startOnnxDetectionLoop() {
        if (isStopping.get() || isTemporarilyPausedForUiMic.get() || isInConversationLoop.get()) {
            return
        }

        stopDetectionCoroutine()
        isListeningState.set(true)
        acquireCpuWakeLock()

        val pipeline = openWakeWordPipeline ?: OpenWakeWordPipeline(applicationContext).also {
            openWakeWordPipeline = it
        }

        updateForegroundNotification(ACTIVE_NOTIFICATION_TEXT)
        updateExternalStatus(
            "$ACTIVE_NOTIFICATION_TEXT (ONNX 3-model pipeline active, threshold > 0.20)",
            false
        )

        detectionLoopJob = serviceScope.launch {
            var lastUiReportMs = 0L
            var peakRecentScore = 0.0f

            while (isListening &&
                isActive &&
                !isStopping.get() &&
                !isTemporarilyPausedForUiMic.get() &&
                !isInConversationLoop.get() &&
                securePrefs.isWakeWordEnabled
            ) {
                try {
                    var triggeredScore: Float? = null
                    pipeline.runDetectionLoop(
                        triggerThreshold = WAKE_WORD_THRESHOLD,
                        onScoreUpdate = { score ->
                            if (score > peakRecentScore) {
                                peakRecentScore = score
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastUiReportMs >= 1_500L) {
                                lastUiReportMs = now
                                val formatted = String.format(Locale.US, "%.3f", peakRecentScore)
                                updateExternalStatus(
                                    "$ACTIVE_NOTIFICATION_TEXT | Peak score: $formatted (triggers > 0.20)",
                                    false
                                )
                                peakRecentScore = 0.0f
                            }
                        },
                        onStreamRecovering = { err ->
                            Log.w(TAG, "Recovering wake word AudioRecord stream: ${err.message}")
                            updateExternalStatus(
                                "Audio stream interrupted (${err.javaClass.simpleName}) — restarting mic immediately...",
                                true
                            )
                        },
                        onDetected = { score ->
                            triggeredScore = score
                        }
                    )

                    val hitScore = triggeredScore
                    if (hitScore != null) {
                        isListeningState.set(false)
                        mainHandler.post {
                            onWakeWordTriggered(hitScore)
                        }
                        break
                    }

                    // If we did NOT trigger and should still be listening, never exit — restart immediately
                    if (isListening &&
                        isActive &&
                        !isStopping.get() &&
                        !isTemporarilyPausedForUiMic.get() &&
                        !isInConversationLoop.get() &&
                        securePrefs.isWakeWordEnabled
                    ) {
                        Log.w(TAG, "runDetectionLoop returned without trigger while isListening=true; restarting immediately")
                        delay(250L)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "openWakeWord ONNX loop exception; restarting immediately: ${e.message}", e)
                    updateExternalStatus(
                        "ONNX pipeline error (${e.javaClass.simpleName}: ${e.message}). Restarting immediately...",
                        true
                    )
                    updateForegroundNotification(ACTIVE_NOTIFICATION_TEXT)
                    delay(400L)
                }
            }
        }
    }

    private fun stopDetectionCoroutine() {
        isListeningState.set(false)
        openWakeWordPipeline?.stopAudioCapture()
        val job = detectionLoopJob
        detectionLoopJob = null
        job?.cancel()
    }

    /**
     * Triggered when `hey_jarvis_v0.1.onnx` score > 0.20:
     * 1. Stop AudioRecord stream.
     * 2. Play chime.
     * 3. Speak "Yes? What do you need?" via VoiceEngine (ElevenLabs / Android TTS).
     * 4. Start SpeechRecognizer to capture command.
     */
    private fun onWakeWordTriggered(score: Float) {
        if (isStopping.get()) return
        if (!isInConversationLoop.compareAndSet(false, true)) return

        stopDetectionCoroutine()

        val scoreStr = String.format(Locale.US, "%.2f", score)
        updateExternalStatus(
            "'Hey Jarvis' detected (score=$scoreStr) — playing chime & listening...",
            false
        )
        updateForegroundNotification("'Hey Jarvis' detected — Prompting...")

        playActivationChime()

        mainHandler.postDelayed({
            if (isStopping.get()) {
                isInConversationLoop.set(false)
                return@postDelayed
            }
            serviceScope.launch {
                val speechRate = userPrefsStore.getSnapshot().voiceSpeed.coerceIn(0.6f, 1.8f)
                val transitioned = AtomicBoolean(false)
                val proceedToRecognizer = Runnable {
                    if (transitioned.compareAndSet(false, true)) {
                        startCommandSpeechRecognizer()
                    }
                }

                mainHandler.post {
                    // Safety fallback so SpeechRecognizer always launches even if an OEM TTS callback stalls
                    mainHandler.postDelayed(proceedToRecognizer, 12_000L)
                    val engine = voiceEngine
                    if (engine != null) {
                        engine.speak(
                            text = "Yes? What do you need?",
                            fallbackSpeechRate = speechRate,
                            onComplete = {
                                mainHandler.removeCallbacks(proceedToRecognizer)
                                mainHandler.postDelayed(proceedToRecognizer, 200L)
                            }
                        )
                    } else {
                        mainHandler.removeCallbacks(proceedToRecognizer)
                        proceedToRecognizer.run()
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
        if (isStopping.get()) {
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
     * Routes the recognized voice command to Groq ("openai/gpt-oss-120b") or dynamic local
     * briefing/task intents, speaks the response aloud via VoiceEngine, and immediately
     * restarts the infinite ONNX wake-word loop.
     */
    private fun routeCommandToGroqAndSpeak(spokenCommand: String) {
        updateExternalStatus("Sending to Groq: \"$spokenCommand\"", false)
        updateForegroundNotification("Thinking...")

        serviceScope.launch {
            try {
                repository.recordTurn("USER", spokenCommand)
                val userPrefs = userPrefsStore.getSnapshot()
                val displayName = userPrefs.userName.ifBlank { "King Israel" }
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

                    is ParsedIntent.ReadPlan -> {
                        val briefing = TtsGreetingHelper.buildDynamicBriefing(db, displayName)
                        repository.recordTurn("ASSISTANT", briefing)
                        briefing
                    }

                    is ParsedIntent.Done -> {
                        val queryLower = parsed.targetQuery.lowercase(Locale.getDefault())
                        val todayDateStr = java.text.SimpleDateFormat(
                            "yyyy-MM-dd",
                            Locale.getDefault()
                        ).format(java.util.Date())
                        val matchingChecklist = db.checklistDao().getAllItemsDirect().firstOrNull {
                            it.title.lowercase(Locale.getDefault()).contains(queryLower) ||
                                it.id.lowercase(Locale.getDefault()).contains(queryLower)
                        }
                        if (matchingChecklist != null) {
                            repository.markItemDone(
                                date = todayDateStr,
                                itemId = matchingChecklist.id,
                                countValue = matchingChecklist.defaultTargetCount
                            )
                            val allCleared = TtsGreetingHelper.countRemainingTasksForToday(db) == 0
                            val msg = TtsGreetingHelper.buildTaskCompletionSpeech(allCleared)
                            repository.recordTurn("ASSISTANT", msg)
                            msg
                        } else {
                            val matchingItem = db.itemDao().getItemByTitle(parsed.targetQuery)
                            if (matchingItem != null) {
                                repository.updateItemStatus(matchingItem.id, "COMPLETED")
                                val allCleared = TtsGreetingHelper.countRemainingTasksForToday(db) == 0
                                val msg = TtsGreetingHelper.buildTaskCompletionSpeech(allCleared)
                                repository.recordTurn("ASSISTANT", msg)
                                msg
                            } else {
                                askGroqForSpokenReply(spokenCommand, userPrefs)
                            }
                        }
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
                        if (securePrefs.hasGroqKey()) {
                            askGroqForSpokenReply(spokenCommand, userPrefs)
                        } else {
                            val emailSummary = repository.buildUnreadEmailSpokenSummary()
                            repository.recordTurn("ASSISTANT", emailSummary)
                            emailSummary
                        }
                    }

                    else -> {
                        if (securePrefs.isImapEnabled &&
                            securePrefs.imapUser.isNotBlank() &&
                            securePrefs.imapPassword.isNotBlank() &&
                            db.emailDao().getRecentUnreadDirect(1).isEmpty() &&
                            (spokenCommand.contains("meeting", ignoreCase = true) ||
                                spokenCommand.contains("invite", ignoreCase = true) ||
                                spokenCommand.contains("email", ignoreCase = true))
                        ) {
                            EmailHelper.syncUnreadEmailsAndNotify(
                                applicationContext,
                                postNotifications = false
                            )
                        }
                        askGroqForSpokenReply(spokenCommand, userPrefs)
                    }
                }

                mainHandler.post {
                    if (isStopping.get()) {
                        isInConversationLoop.set(false)
                        return@post
                    }
                    updateForegroundNotification("Speaking response...")
                    val resumed = AtomicBoolean(false)
                    val resumeAction = Runnable {
                        if (resumed.compareAndSet(false, true)) {
                            restartWakeWordLoopOnMain()
                        }
                    }
                    // Safety watchdog so wake word ALWAYS resumes even if audio playback stalls
                    mainHandler.postDelayed(resumeAction, 45_000L)

                    val engine = voiceEngine
                    if (engine != null) {
                        engine.speak(
                            text = replyToSpeak,
                            fallbackSpeechRate = speechRate,
                            onComplete = {
                                mainHandler.removeCallbacks(resumeAction)
                                mainHandler.postDelayed(resumeAction, 250L)
                            }
                        )
                    } else {
                        mainHandler.removeCallbacks(resumeAction)
                        resumeAction.run()
                    }
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

    private suspend fun askGroqForSpokenReply(
        spokenCommand: String,
        userPrefs: com.xestlab.assistant.data.UserPreferencesState
    ): String {
        val aiResult = AiClient.generateAssistantResponse(
            userPrompt = spokenCommand,
            db = db,
            prefs = userPrefs,
            securePrefs = securePrefs
        )
        return aiResult.fold(
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

    private fun restartWakeWordLoopOnMain() {
        isInConversationLoop.set(false)
        if (isStopping.get()) return

        if (!securePrefs.isWakeWordEnabled) {
            stopDetectionCoroutine()
            releaseCpuWakeLock()
            stopForegroundSafely()
            stopSelf()
            return
        }

        if (!isTemporarilyPausedForUiMic.get()) {
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

    @SuppressLint("WakelockTimeout")
    private fun acquireCpuWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val existing = wakeLock
            if (existing == null) {
                val created = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "xestlab:WakeWordContinuousLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                wakeLock = created
            } else if (!existing.isHeld) {
                existing.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire WakeWord PARTIAL_WAKE_LOCK: ${e.message}")
        }
    }

    private fun releaseCpuWakeLock() {
        try {
            val existing = wakeLock
            if (existing?.isHeld == true) {
                existing.release()
            }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
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
        if (isStopping.get()) return
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
        isStopping.set(true)
        isListeningState.set(false)
        _isRunningFlow.value = false
        mainHandler.removeCallbacksAndMessages(null)
        destroySpeechRecognizerOnMain()
        stopDetectionCoroutine()
        openWakeWordPipeline?.close()
        openWakeWordPipeline = null
        releaseCpuWakeLock()
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
