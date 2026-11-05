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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xestlab.assistant.MainActivity
import com.xestlab.assistant.R
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.UserPreferencesDataStore
import com.xestlab.assistant.data.local.AppDatabase
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Continuous-Flow Audio & Thought Buffer Foreground Service.
 *
 * Configured at 16,000Hz Mono 16-bit PCM.
 * Continuously captures audio into a rolling [AudioRingBuffer] without timing out or
 * cutting off after pauses in speech. Transcribed chunks are fed into [ThoughtBufferManager],
 * keeping the assistant silent until an explicit trigger keyword (e.g. "Execute", "Save note") is voiced.
 */
class ContinuousAudioService : Service() {

    companion object {
        private const val TAG = "ContinuousAudioService"

        const val CHANNEL_ID = "xestlab_continuous_audio_channel"
        const val NOTIFICATION_ID = 8801
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        const val ACTION_START = "com.xestlab.assistant.action.START_CONTINUOUS_AUDIO"
        const val ACTION_STOP = "com.xestlab.assistant.action.STOP_CONTINUOUS_AUDIO"
        const val ACTION_FORCE_FINALIZE = "com.xestlab.assistant.action.FORCE_FINALIZE_THOUGHT"

        private val _isRecordingFlow = MutableStateFlow(false)
        val isRecordingFlow: StateFlow<Boolean> = _isRecordingFlow.asStateFlow()

        private val _micLevelFlow = MutableStateFlow(0)
        val micLevelFlow: StateFlow<Int> = _micLevelFlow.asStateFlow()

        private val _thoughtDraftFlow = MutableStateFlow("")
        val thoughtDraftFlow: StateFlow<String> = _thoughtDraftFlow.asStateFlow()

        val ringBuffer = AudioRingBuffer(durationSeconds = 60, sampleRate = SAMPLE_RATE_HZ)
        val thoughtBufferManager = ThoughtBufferManager()

        /**
         * Optional listener (e.g. from AssistantViewModel) to intercept finalized thoughts.
         * If returns true, ContinuousAudioService skips its background-only fallback persistence.
         */
        @Volatile
        var onThoughtDispatched: ((FinalizedThought) -> Boolean)? = null

        fun start(context: Context) {
            val intent = Intent(context, ContinuousAudioService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ContinuousAudioService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun forceFinalize(context: Context) {
            val intent = Intent(context, ContinuousAudioService::class.java).apply {
                action = ACTION_FORCE_FINALIZE
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private var thoughtObserverJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private val isRunning = AtomicBoolean(false)
    private lateinit var repository: AssistantRepository
    private lateinit var userPrefsStore: UserPreferencesDataStore

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(applicationContext)
        userPrefsStore = UserPreferencesDataStore(applicationContext)
        repository = AssistantRepository(db, userPrefsStore)

        createNotificationChannel()
        observeFinalizedThoughts()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopContinuousCapture()
                stopForegroundSafely()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_FORCE_FINALIZE -> {
                thoughtBufferManager.forceFinalize("Manual Action")
                return START_STICKY
            }

            ACTION_START, null -> {
                if (!hasAudioPermission()) {
                    Log.e(TAG, "Cannot start ContinuousAudioService without RECORD_AUDIO permission.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (isRunning.compareAndSet(false, true)) {
                    promoteToForeground()
                    acquireWakeLock()
                    startAudioRecordingLoop()
                    startContinuousSpeechListener()
                }
            }
        }
        return START_STICKY
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Continuous Thought & Audio Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps continuous 16kHz audio recording active without silence timeouts."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun promoteToForeground() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Thought Buffer Active")
            .setContentText("Continuous audio running. Say 'Execute' or 'Save note' to dispatch.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Xestlab:ContinuousAudioWakeLock"
            ).apply {
                setReferenceCounted(false)
            }
        }
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
                Log.i(TAG, "Partial WakeLock acquired for continuous audio service.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "WakeLock released.")
            }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecordingLoop() {
        recordingJob?.cancel()
        recordingJob = serviceScope.launch {
            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ).coerceAtLeast(SAMPLE_RATE_HZ * 2)

            val bufferSize = minBufSize * 2
            val readBuffer = ByteArray(bufferSize)

            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize.")
                    record.release()
                    return@launch
                }

                audioRecord = record
                record.startRecording()
                _isRecordingFlow.value = true
                Log.i(TAG, "Continuous 16kHz audio recording loop started successfully.")

                while (isActive && isRunning.get()) {
                    val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                    if (bytesRead > 0) {
                        // Store continuously in rolling ring buffer (no 2-second timeout!)
                        ringBuffer.write(readBuffer, 0, bytesRead)

                        // Compute audio energy level (0..100)
                        val level = calculateRmsLevel(readBuffer, bytesRead)
                        _micLevelFlow.value = level
                    } else if (bytesRead < 0) {
                        Log.w(TAG, "AudioRecord read returned error code: $bytesRead")
                        delay(50L)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Error in continuous audio recording loop", e)
            } finally {
                stopAudioRecordInternal()
                _isRecordingFlow.value = false
                _micLevelFlow.value = 0
            }
        }
    }

    private fun calculateRmsLevel(buffer: ByteArray, bytesRead: Int): Int {
        var sum = 0.0
        val sampleCount = bytesRead / 2
        if (sampleCount <= 0) return 0

        for (i in 0 until bytesRead step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val signed = sample.toShort()
            sum += signed * signed
        }
        val rms = sqrt(sum / sampleCount)
        return ((rms / 32767.0) * 100.0).roundToInt().coerceIn(0, 100)
    }

    private fun startContinuousSpeechListener() {
        serviceScope.launch(Dispatchers.Main) {
            initSpeechRecognizerLoop()
        }
    }

    private fun initSpeechRecognizerLoop() {
        if (!isRunning.get() || !SpeechRecognizer.isRecognitionAvailable(this)) return

        try {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    // In continuous-flow mode, silence timeout is NOT an error — restart automatically
                    if (isRunning.get()) {
                        serviceScope.launch(Dispatchers.Main) {
                            delay(120L)
                            restartSpeechRecognizer()
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        // Append chunk into ThoughtBufferManager (remains silent until explicit trigger)
                        thoughtBufferManager.appendChunk(text)
                    }
                    if (isRunning.get()) {
                        serviceScope.launch(Dispatchers.Main) {
                            restartSpeechRecognizer()
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull()?.trim().orEmpty()
                    if (partial.isNotEmpty()) {
                        thoughtBufferManager.updatePartialDraft(partial)
                        _thoughtDraftFlow.value = thoughtBufferManager.getCurrentDraft()
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer continuous loop exception: ${e.message}")
        }
    }

    private fun restartSpeechRecognizer() {
        if (!isRunning.get()) return
        try {
            speechRecognizer?.cancel()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            initSpeechRecognizerLoop()
        }
    }

    private fun observeFinalizedThoughts() {
        thoughtObserverJob = serviceScope.launch {
            thoughtBufferManager.finalizedThoughtFlow.collect { finalized ->
                Log.i(TAG, "Explicit trigger detected [${finalized.triggerKeyword}] -> Dispatching thought: '${finalized.cleanText}'")
                _thoughtDraftFlow.value = ""

                val handledByListener = onThoughtDispatched?.invoke(finalized) == true
                if (!handledByListener) {
                    // Fallback when UI ViewModel is not attached: persist directly in Room
                    repository.recordTurn("USER", finalized.cleanText)
                    handleDispatchedThought(finalized)
                }
            }
        }
    }

    private suspend fun handleDispatchedThought(thought: FinalizedThought) {
        when (thought.actionType) {
            ThoughtActionType.SAVE_NOTE -> {
                repository.saveItem(
                    com.xestlab.assistant.data.model.Item(
                        type = "NOTE",
                        title = thought.cleanText.take(60),
                        details = thought.cleanText,
                        sourceText = thought.rawText
                    )
                )
                repository.recordTurn("ASSISTANT", "Note saved: ${thought.cleanText.take(60)}")
            }

            ThoughtActionType.ADD_TASK -> {
                AssistantRepository.persistAndVerifyTaskInDao(
                    itemDao = AppDatabase.getInstance(this).itemDao(),
                    title = thought.cleanText.take(80),
                    details = thought.cleanText,
                    sourceText = thought.rawText
                )
                repository.recordTurn("ASSISTANT", "Task added: ${thought.cleanText.take(80)}")
            }

            ThoughtActionType.EXECUTE_COMMAND, ThoughtActionType.PROCESS_GENERAL -> {
                // General query or command processed by Assistant
                // Can be wired directly to Groq or universal input router
            }
        }
    }

    private fun stopContinuousCapture() {
        isRunning.set(false)
        recordingJob?.cancel()
        recordingJob = null

        stopAudioRecordInternal()
        releaseWakeLock()

        serviceScope.launch(Dispatchers.Main) {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
        }

        _isRecordingFlow.value = false
        _micLevelFlow.value = 0
    }

    private fun stopAudioRecordInternal() {
        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (_: Exception) {}
        audioRecord = null
    }

    private fun stopForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopContinuousCapture()
        thoughtObserverJob?.cancel()
        serviceScope.cancel()
        Log.i(TAG, "ContinuousAudioService destroyed and all resources cleaned up.")
    }
}
