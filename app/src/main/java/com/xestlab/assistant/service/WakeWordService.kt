package com.xestlab.assistant.service

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service running Picovoice Porcupine wake word detection in the background
 * and executing the hands-free Jarvis Conversation Loop:
 * 1. Detect custom wake word (.ppn loaded from assets/custom_wake_word.ppn or imported .ppn).
 * 2. Pause Porcupine mic stream & play activation chime.
 * 3. Speak via TTS: "Yes? What do you need?"
 * 4. Start Android SpeechRecognizer on the main thread to capture spoken command.
 * 5. Route spoken command to Gemini or Groq via AiClient.
 * 6. Speak AI response via TTS.
 * 7. Automatically resume Porcupine wake word detection in the background.
 */
class WakeWordService : Service() {

    companion object {
        const val CHANNEL_ID = "xestlab_wake_word_channel"
        const val NOTIFICATION_ID = 7701

        // Loaded from app/src/main/assets/custom_wake_word.ppn
        const val ASSET_WAKE_WORD_PATH = "custom_wake_word.ppn"
        const val IMPORTED_PPN_FILENAME = "imported_custom_wake_word.ppn"

        const val ACTION_START_OR_RELOAD = "com.xestlab.assistant.action.WAKE_START_OR_RELOAD"
        const val ACTION_STOP_SERVICE = "com.xestlab.assistant.action.WAKE_STOP_SERVICE"
        const val ACTION_TEMP_PAUSE = "com.xestlab.assistant.action.WAKE_TEMP_PAUSE"
        const val ACTION_TEMP_RESUME = "com.xestlab.assistant.action.WAKE_TEMP_RESUME"
        const val ACTION_TEST_JARVIS_LOOP = "com.xestlab.assistant.action.WAKE_TEST_JARVIS_LOOP"

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        private val _statusMessageFlow = MutableStateFlow(
            "Wake word service idle. Enter a Wake Word Name and Picovoice Access Key in Settings."
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
                    "Microphone permission (RECORD_AUDIO) is required before starting WakeWordService.",
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
            updateExternalStatus("Background wake word detection stopped.", false)
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
                    "Grant Microphone permission first to test the Jarvis conversation loop.",
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

    private var voiceEngine: VoiceEngine? = null
    private var porcupineManager: PorcupineManager? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var toneGenerator: ToneGenerator? = null

    private val isInConversationLoop = AtomicBoolean(false)
    private var isTemporarilyPausedForUiMic = false
    private var isStopping = false

    private val recognizerWatchdogRunnable = Runnable {
        if (isInConversationLoop.get()) {
            destroySpeechRecognizerOnMain()
            resumeWakeWordListeningOnMain()
        }
    }

    private val wakeWordCallback = PorcupineManagerCallback { _ ->
        mainHandler.post {
            onWakeWordDetected()
        }
    }

    override fun onCreate() {
        super.onCreate()
        securePrefs = SecurePreferences(applicationContext)
        userPrefsStore = UserPreferencesDataStore(applicationContext)
        db = AppDatabase.getInstance(applicationContext)
        repository = AssistantRepository(db, userPrefsStore)
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
                stopForegroundSafely()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TEMP_PAUSE -> {
                isTemporarilyPausedForUiMic = true
                mainHandler.post {
                    pausePorcupineInternal()
                    updateForegroundNotification("Paused briefly while in-app mic is active")
                }
                return START_STICKY
            }

            ACTION_TEMP_RESUME -> {
                isTemporarilyPausedForUiMic = false
                mainHandler.post {
                    if (!isInConversationLoop.get()) {
                        resumeWakeWordListeningOnMain()
                    }
                }
                return START_STICKY
            }

            ACTION_TEST_JARVIS_LOOP -> {
                if (!promoteToForeground("Testing Jarvis Conversation Loop...")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                _isRunningFlow.value = true
                if (porcupineManager == null && securePrefs.hasPicovoiceKey()) {
                    initializePorcupineEngine()
                }
                mainHandler.post {
                    onWakeWordDetected()
                }
                return START_STICKY
            }
        }

        if (!hasRecordAudioPermission(this)) {
            updateExternalStatus(
                "Microphone permission (RECORD_AUDIO) is not granted. Enable mic permission first.",
                true
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val configuredName = activeWakeWordLabel()
        if (!promoteToForeground("Initializing wake word \"$configuredName\"...")) {
            stopSelf()
            return START_NOT_STICKY
        }

        _isRunningFlow.value = true
        isTemporarilyPausedForUiMic = false
        initializePorcupineEngine()

        return START_STICKY
    }

    private fun activeWakeWordLabel(): String {
        return securePrefs.wakeWordName.ifBlank { "Assistant" }
    }

    private fun resolveKeywordPath(): String {
        val importedFile = File(filesDir, IMPORTED_PPN_FILENAME)
        return if (importedFile.exists() && importedFile.length() > 64L) {
            importedFile.absolutePath
        } else {
            // Loads custom_wake_word.ppn directly from app/src/main/assets/
            ASSET_WAKE_WORD_PATH
        }
    }

    private fun initializePorcupineEngine() {
        releasePorcupineInternal()

        val accessKey = securePrefs.picovoiceAccessKey
        val wakeName = activeWakeWordLabel()

        if (accessKey.isBlank()) {
            val msg = "No Picovoice Access Key found in EncryptedSharedPreferences. Save your Picovoice key in Settings."
            updateExternalStatus(msg, true)
            updateForegroundNotification("Missing Picovoice Access Key")
            return
        }

        val keywordPath = resolveKeywordPath()
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(keywordPath)
                .setSensitivity(0.65f)
                .build(applicationContext, wakeWordCallback)

            porcupineManager?.start()

            val sourceDesc = if (keywordPath == ASSET_WAKE_WORD_PATH) {
                "assets/$ASSET_WAKE_WORD_PATH"
            } else {
                "imported .ppn"
            }
            val okMsg = "Active — Listening for \"$wakeName\" ($sourceDesc)."
            updateExternalStatus(okMsg, false)
            updateForegroundNotification("Listening for \"$wakeName\"")
        } catch (primaryError: PorcupineException) {
            // If assets/custom_wake_word.ppn is still the placeholder file and the user typed a name
            // matching a Porcupine built-in keyword (e.g., "Jarvis", "Computer", "Porcupine"),
            // fall back cleanly so built-in names work immediately even before replacing the .ppn!
            val matchedBuiltIn = findMatchingBuiltInKeyword(securePrefs.wakeWordName)
            if (keywordPath == ASSET_WAKE_WORD_PATH && matchedBuiltIn != null) {
                try {
                    porcupineManager = PorcupineManager.Builder()
                        .setAccessKey(accessKey)
                        .setKeyword(matchedBuiltIn)
                        .setSensitivity(0.65f)
                        .build(applicationContext, wakeWordCallback)

                    porcupineManager?.start()
                    val okBuiltInMsg =
                        "Active — Listening for \"$wakeName\" (using Porcupine built-in ${matchedBuiltIn.name} keyword while assets/$ASSET_WAKE_WORD_PATH is placeholder)."
                    updateExternalStatus(okBuiltInMsg, false)
                    updateForegroundNotification("Listening for \"$wakeName\"")
                    return
                } catch (builtInErr: PorcupineException) {
                    val errText =
                        "PorcupineException: ${builtInErr.message ?: builtInErr.toString()}"
                    updateExternalStatus(errText, true)
                    updateForegroundNotification("Porcupine error — check Settings")
                    return
                }
            }

            val errDetail = buildString {
                append("PorcupineException loading $keywordPath for \"$wakeName\": ")
                append(primaryError.message ?: primaryError.toString())
                if (keywordPath == ASSET_WAKE_WORD_PATH) {
                    append(" (Replace app/src/main/assets/custom_wake_word.ppn with your trained Picovoice .ppn for \"$wakeName\", or import your .ppn in Settings.)")
                }
            }
            updateExternalStatus(errDetail, true)
            updateForegroundNotification("Needs valid .ppn or Picovoice key — tap to check")
        } catch (e: Exception) {
            val unexpected = "${e.javaClass.simpleName}: ${e.message ?: e.toString()}"
            updateExternalStatus(unexpected, true)
            updateForegroundNotification("Wake word error — tap to view")
        }
    }

    @Suppress("DEPRECATION")
    private fun findMatchingBuiltInKeyword(rawWakeName: String): Porcupine.BuiltInKeyword? {
        val normalized = rawWakeName.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return null
        return Porcupine.BuiltInKeyword.values().firstOrNull { keyword ->
            keyword.name.replace('_', ' ').equals(normalized, ignoreCase = true)
        }
    }

    /**
     * Step 1-3 of the Jarvis Conversation Loop when the wake word is detected:
     * - Pause Porcupine mic capture
     * - Play chime
     * - Speak "Yes? What do you need?" via TTS
     * - Automatically start SpeechRecognizer when TTS completes
     */
    private fun onWakeWordDetected() {
        if (isStopping) return
        if (!isInConversationLoop.compareAndSet(false, true)) return

        val wakeName = activeWakeWordLabel()
        pausePorcupineInternal()

        updateExternalStatus("Wake word \"$wakeName\" detected — playing chime & prompting...", false)
        updateForegroundNotification("\"$wakeName\" detected — Prompting...")

        playActivationChime()

        // Allow 220ms for the activation chime to finish cleanly before speaking
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
     * Step 4 of the Jarvis Conversation Loop:
     * Automatically starts Android SpeechRecognizer on the Main thread to listen to user's command.
     */
    private fun startCommandSpeechRecognizer() {
        if (isStopping) {
            isInConversationLoop.set(false)
            return
        }
        if (!hasRecordAudioPermission(this) || !SpeechRecognizer.isRecognitionAvailable(this)) {
            updateExternalStatus(
                "SpeechRecognizer unavailable or mic permission missing. Resuming wake word listener.",
                true
            )
            resumeWakeWordListeningOnMain()
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
                    mainHandler.removeCallbacks(recognizerWatchdogRunnable)
                    destroySpeechRecognizerOnMain()
                    mainHandler.postDelayed({
                        resumeWakeWordListeningOnMain()
                    }, 250L)
                }

                override fun onResults(results: Bundle?) {
                    mainHandler.removeCallbacks(recognizerWatchdogRunnable)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenCommand = matches?.firstOrNull()?.trim().orEmpty()
                    destroySpeechRecognizerOnMain()

                    if (spokenCommand.isEmpty()) {
                        resumeWakeWordListeningOnMain()
                    } else {
                        processSpokenCommandAndReply(spokenCommand)
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
            mainHandler.removeCallbacks(recognizerWatchdogRunnable)
            destroySpeechRecognizerOnMain()
            updateExternalStatus("SpeechRecognizer error: ${e.message}", true)
            resumeWakeWordListeningOnMain()
        }
    }

    /**
     * Step 5-7 of the Jarvis Conversation Loop:
     * - Send the recognized command to Gemini/Groq (or handle explicit Note:/Email commands).
     * - Speak the AI's response via TTS.
     * - Automatically resume listening for the wake word in the background.
     */
    private fun processSpokenCommandAndReply(spokenCommand: String) {
        updateExternalStatus("Routing command to ${securePrefs.llmProvider.name}: \"$spokenCommand\"", false)
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
                                updateExternalStatus("LLM error: $diag", true)
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
                                resumeWakeWordListeningOnMain()
                            }, 220L)
                        }
                    ) ?: resumeWakeWordListeningOnMain()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    updateExternalStatus("Jarvis loop error: ${e.message}", true)
                    resumeWakeWordListeningOnMain()
                }
            }
        }
    }

    private fun resumeWakeWordListeningOnMain() {
        isInConversationLoop.set(false)
        if (isStopping || !securePrefs.isWakeWordEnabled || isTemporarilyPausedForUiMic) {
            return
        }

        val wakeName = activeWakeWordLabel()
        val manager = porcupineManager
        if (manager == null) {
            initializePorcupineEngine()
            return
        }

        try {
            manager.start()
            updateExternalStatus("Active — Listening for \"$wakeName\" in background.", false)
            updateForegroundNotification("Listening for \"$wakeName\"")
        } catch (e: PorcupineException) {
            updateExternalStatus("Porcupine resume error: ${e.message}", true)
            updateForegroundNotification("Wake word paused — check Settings")
        }
    }

    private fun pausePorcupineInternal() {
        try {
            porcupineManager?.stop()
        } catch (_: PorcupineException) {
        } catch (_: Exception) {
        }
    }

    private fun releasePorcupineInternal() {
        try {
            porcupineManager?.stop()
        } catch (_: Exception) {
        }
        try {
            porcupineManager?.delete()
        } catch (_: Exception) {
        } finally {
            porcupineManager = null
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
        val wakeName = activeWakeWordLabel()

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
            .setContentTitle("Xestlab Wake Word ($wakeName)")
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
            description = "Persistent background wake word listener (Picovoice Porcupine)"
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
        releasePorcupineInternal()
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
