package com.xestlab.assistant.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.xestlab.assistant.ai.AiClient
import com.xestlab.assistant.ai.ElevenLabsApiException
import com.xestlab.assistant.ai.ElevenLabsClient
import com.xestlab.assistant.data.SecurePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.util.Locale

data class InstalledTtsVoiceInfo(
    val name: String,
    val displayLabel: String,
    val localeDisplay: String,
    val quality: Int,
    val qualityLabel: String,
    val matchesDeviceLanguage: Boolean,
    val networkRequired: Boolean
)

/**
 * Unified Voice Engine routing all spoken output in Xestlab Assistant:
 *
 * - Default ElevenLabs Voice ID: EXAVITQu4vr4xnSDxMaL
 * - Caches synthesized MP3 audio by SHA-256 text hash.
 * - Uses cloud voice ONLY for the wake-up briefing (`isWakeUpBriefing = true`) and replies under 200 characters.
 * - Counts characters used this month and warns at 80% of 10,000 (8,000 chars).
 * - On HTTP 401 or 402, records ONE clear message in Settings and pauses cloud voice for 24 hours without retrying.
 * - Android TTS fallback: speech rate 1.0, installed voice picker sorted by quality, and short sentences with small pauses.
 */
class VoiceEngine(
    context: Context,
    private val securePrefs: SecurePreferences,
    private val onSpeakingStateChanged: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VoiceEngine"

        const val ELEVENLABS_VOICE_URL = ElevenLabsClient.TTS_ENDPOINT
        const val DEFAULT_FALLBACK_RATE = 1.0f
        const val DEFAULT_FALLBACK_PITCH = 1.0f
        const val MAX_CLOUD_REPLY_CHARS = 200
        private const val SENTENCE_PAUSE_MS = 180L
        private const val MAX_SENTENCE_CHARS = 110

        private val _statusMessageFlow = MutableStateFlow(
            "Voice Engine ready (Default Voice ID: ${ElevenLabsClient.VOICE_ID} | Fallback: Android TTS 1.0x)."
        )
        val statusMessageFlow: StateFlow<String> = _statusMessageFlow.asStateFlow()

        private val _statusIsErrorFlow = MutableStateFlow(false)
        val statusIsErrorFlow: StateFlow<Boolean> = _statusIsErrorFlow.asStateFlow()

        private val _installedVoicesFlow = MutableStateFlow<List<InstalledTtsVoiceInfo>>(emptyList())
        val installedVoicesFlow: StateFlow<List<InstalledTtsVoiceInfo>> = _installedVoicesFlow.asStateFlow()

        private val _monthlyCharsUsedFlow = MutableStateFlow(0)
        val monthlyCharsUsedFlow: StateFlow<Int> = _monthlyCharsUsedFlow.asStateFlow()

        private val _cloudPausedUntilFlow = MutableStateFlow(0L)
        val cloudPausedUntilFlow: StateFlow<Long> = _cloudPausedUntilFlow.asStateFlow()

        private val _cloudPauseMessageFlow = MutableStateFlow("")
        val cloudPauseMessageFlow: StateFlow<String> = _cloudPauseMessageFlow.asStateFlow()

        fun updateExternalDiagnostic(message: String, isError: Boolean) {
            _statusMessageFlow.value = message
            _statusIsErrorFlow.value = isError
        }

        fun syncPersistedCloudState(securePrefs: SecurePreferences) {
            val isPaused = securePrefs.isElevenLabsCurrentlyPaused()
            _cloudPausedUntilFlow.value = if (isPaused) securePrefs.elevenLabsPausedUntilMillis else 0L
            _cloudPauseMessageFlow.value = if (isPaused) securePrefs.elevenLabsPauseMessage else ""
            _monthlyCharsUsedFlow.value = securePrefs.elevenLabsCharsUsedThisMonth
        }

        fun splitIntoShortSentences(rawText: String): List<String> {
            val normalized = rawText.replace(Regex("\\s+"), " ").trim()
            if (normalized.isEmpty()) return emptyList()

            val primaryChunks = normalized.split(Regex("(?<=[.!?])\\s+"))
            val result = ArrayList<String>(primaryChunks.size + 4)

            for (chunk in primaryChunks) {
                val trimmed = chunk.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.length <= MAX_SENTENCE_CHARS) {
                    result.add(trimmed)
                } else {
                    val clauses = trimmed.split(Regex("(?<=[,;:])\\s+"))
                    var buffer = StringBuilder()
                    for (clause in clauses) {
                        val cleanClause = clause.trim()
                        if (cleanClause.isEmpty()) continue
                        if (buffer.isEmpty()) {
                            if (cleanClause.length <= MAX_SENTENCE_CHARS) {
                                buffer.append(cleanClause)
                            } else {
                                result.addAll(splitLongClauseByWords(cleanClause))
                            }
                        } else if (buffer.length + 1 + cleanClause.length <= MAX_SENTENCE_CHARS) {
                            buffer.append(' ').append(cleanClause)
                        } else {
                            result.add(buffer.toString())
                            buffer = StringBuilder()
                            if (cleanClause.length <= MAX_SENTENCE_CHARS) {
                                buffer.append(cleanClause)
                            } else {
                                result.addAll(splitLongClauseByWords(cleanClause))
                            }
                        }
                    }
                    if (buffer.isNotEmpty()) {
                        result.add(buffer.toString())
                    }
                }
            }
            return if (result.isEmpty()) listOf(normalized) else result
        }

        private fun splitLongClauseByWords(clause: String): List<String> {
            val words = clause.split(' ')
            val parts = ArrayList<String>()
            var current = StringBuilder()
            for (word in words) {
                if (word.isEmpty()) continue
                if (current.isEmpty()) {
                    current.append(word)
                } else if (current.length + 1 + word.length <= MAX_SENTENCE_CHARS) {
                    current.append(' ').append(word)
                } else {
                    parts.add(current.toString())
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) {
                parts.add(current.toString())
            }
            return parts
        }
    }

    private val appContext = context.applicationContext
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeSpeakJob: Job? = null

    private var tts: TextToSpeech? = try {
        TextToSpeech(appContext, this)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to instantiate Android TextToSpeech fallback", e)
        null
    }
    private var isTtsReady = false
    private var queuedFallbackText: String? = null
    private var queuedFallbackRate: Float = DEFAULT_FALLBACK_RATE
    private var queuedOnDone: (() -> Unit)? = null

    @Volatile
    private var activeFinalUtteranceId: String? = null
    private var activeOnDoneCallback: (() -> Unit)? = null
    private var mediaPlayer: MediaPlayer? = null
    private var activeMp3File: File? = null

    init {
        syncPersistedCloudState(securePrefs)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val langResult = engine.setLanguage(Locale.getDefault())
            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.setLanguage(Locale.US)
            }
            val speechAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            engine.setAudioAttributes(speechAttrs)
            engine.setPitch(DEFAULT_FALLBACK_PITCH)
            engine.setSpeechRate(DEFAULT_FALLBACK_RATE)

            refreshInstalledVoicesAndApplySelection(engine)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onSpeakingStateChanged(true)
                }

                override fun onDone(utteranceId: String?) {
                    val expectedFinal = activeFinalUtteranceId
                    if (expectedFinal != null && utteranceId != expectedFinal) {
                        // Intermediate sentence or pause finished; wait for final sentence
                        return
                    }
                    activeFinalUtteranceId = null
                    onSpeakingStateChanged(false)
                    val cb = activeOnDoneCallback
                    activeOnDoneCallback = null
                    cb?.invoke()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "Android TextToSpeech utterance error (utteranceId=$utteranceId)")
                    activeFinalUtteranceId = null
                    onSpeakingStateChanged(false)
                    val cb = activeOnDoneCallback
                    activeOnDoneCallback = null
                    cb?.invoke()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(
                        TAG,
                        "Android TextToSpeech utterance error (utteranceId=$utteranceId, code=$errorCode)"
                    )
                    activeFinalUtteranceId = null
                    onSpeakingStateChanged(false)
                    val cb = activeOnDoneCallback
                    activeOnDoneCallback = null
                    cb?.invoke()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    val expectedFinal = activeFinalUtteranceId
                    if (!interrupted && expectedFinal != null && utteranceId != expectedFinal) {
                        return
                    }
                    activeFinalUtteranceId = null
                    onSpeakingStateChanged(false)
                    val cb = activeOnDoneCallback
                    activeOnDoneCallback = null
                    cb?.invoke()
                }
            })
            isTtsReady = true
            queuedFallbackText?.let { pending ->
                val rate = queuedFallbackRate
                val cb = queuedOnDone
                queuedFallbackText = null
                queuedOnDone = null
                speakViaAndroidTts(pending, rate, cb)
            }
        } else {
            Log.e(TAG, "Android TextToSpeech initialization failed with status=$status")
            onSpeakingStateChanged(false)
            queuedOnDone?.invoke()
            queuedOnDone = null
        }
    }

    fun refreshInstalledVoices(): List<InstalledTtsVoiceInfo> {
        val engine = tts ?: return _installedVoicesFlow.value
        if (!isTtsReady) return _installedVoicesFlow.value
        return refreshInstalledVoicesAndApplySelection(engine)
    }

    private fun refreshInstalledVoicesAndApplySelection(engine: TextToSpeech): List<InstalledTtsVoiceInfo> {
        return try {
            val deviceLang = Locale.getDefault().language.lowercase(Locale.US)
            val rawVoices = engine.voices?.toList().orEmpty()
            val installed = rawVoices.filter { voice ->
                val features = voice.features
                features == null || !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }

            val mapped = installed.map { voice ->
                val qLabel = qualityToLabel(voice.quality)
                val locDisplay = voice.locale?.displayName?.ifBlank { voice.locale.toLanguageTag() }
                    ?: "System"
                val netTag = if (voice.isNetworkConnectionRequired) "Network" else "On-device"
                InstalledTtsVoiceInfo(
                    name = voice.name,
                    displayLabel = "$locDisplay — $qLabel ($netTag) [${voice.name}]",
                    localeDisplay = locDisplay,
                    quality = voice.quality,
                    qualityLabel = qLabel,
                    matchesDeviceLanguage = voice.locale?.language?.lowercase(Locale.US) == deviceLang,
                    networkRequired = voice.isNetworkConnectionRequired
                )
            }.sortedWith(
                compareByDescending<InstalledTtsVoiceInfo> { it.quality }
                    .thenByDescending { it.matchesDeviceLanguage }
                    .thenBy { it.networkRequired }
                    .thenBy { it.localeDisplay }
                    .thenBy { it.name }
            )

            _installedVoicesFlow.value = mapped

            val savedVoiceName = securePrefs.androidTtsVoiceName
            val chosenVoice: Voice? = if (savedVoiceName.isNotBlank()) {
                installed.firstOrNull { it.name == savedVoiceName }
            } else {
                val topDeviceLang = mapped.firstOrNull { it.matchesDeviceLanguage }
                topDeviceLang?.let { best -> installed.firstOrNull { it.name == best.name } }
            }
            if (chosenVoice != null) {
                engine.voice = chosenVoice
            }
            mapped
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enumerate Android TTS voices: ${e.message}")
            emptyList()
        }
    }

    fun selectAndroidVoice(voiceName: String) {
        securePrefs.androidTtsVoiceName = voiceName
        val engine = tts ?: return
        if (!isTtsReady) return
        try {
            val available = engine.voices?.toList().orEmpty()
            val target = available.firstOrNull { it.name == voiceName }
            if (target != null) {
                engine.voice = target
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed applying selected Android TTS voice '$voiceName': ${e.message}")
        }
    }

    private fun qualityToLabel(quality: Int): String {
        return when {
            quality >= Voice.QUALITY_VERY_HIGH -> "Very High (${quality})"
            quality >= Voice.QUALITY_HIGH -> "High (${quality})"
            quality >= Voice.QUALITY_NORMAL -> "Normal (${quality})"
            quality >= Voice.QUALITY_LOW -> "Low (${quality})"
            else -> "Very Low (${quality})"
        }
    }

    /**
     * Speaks [text] aloud:
     * - Plays cached MP3 immediately if SHA-256 text hash already exists on disk.
     * - Uses ElevenLabs cloud voice (`EXAVITQu4vr4xnSDxMaL`) ONLY when:
     *   1. ElevenLabs key is saved,
     *   2. Cloud voice is not paused for 24h from a prior 401/402 error,
     *   3. `isWakeUpBriefing == true` OR `cleanText.length < 200`, and
     *   4. Internet is available.
     * - Otherwise speaks via Android TTS fallback (rate 1.0x, selected voice, short sentences with small pauses).
     */
    fun speak(
        text: String,
        fallbackSpeechRate: Float = DEFAULT_FALLBACK_RATE,
        isWakeUpBriefing: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        val cleanText = AiClient.sanitizeForSpeech(text)
        if (cleanText.isEmpty()) {
            onComplete?.invoke()
            return
        }

        stop()
        onSpeakingStateChanged(true)
        syncPersistedCloudState(securePrefs)

        activeSpeakJob = engineScope.launch {
            val savedElevenKey = securePrefs.elevenLabsApiKey
            val voiceId = securePrefs.elevenLabsVoiceId
            val eligibleForCloud = isWakeUpBriefing || cleanText.length < MAX_CLOUD_REPLY_CHARS

            if (savedElevenKey.isBlank()) {
                updateExternalDiagnostic(
                    "Android TTS active (${String.format(Locale.US, "%.2fx", fallbackSpeechRate)}). Save an ElevenLabs key to enable cloud voice ($voiceId).",
                    isError = false
                )
                speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
                return@launch
            }

            // Gate cloud voice strictly to wake-up briefings and replies under 200 characters
            if (!eligibleForCloud) {
                speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
                return@launch
            }

            // If this exact phrase is already cached by text hash, play from disk with zero API calls
            val cachedMp3 = ElevenLabsClient.getCachedAudioFile(appContext, cleanText, voiceId)
            if (cachedMp3 != null) {
                val startedCached = playMp3FileWithMediaPlayer(
                    mp3File = cachedMp3,
                    fallbackText = cleanText,
                    fallbackSpeechRate = fallbackSpeechRate,
                    onComplete = onComplete
                )
                if (startedCached) {
                    if (!securePrefs.isElevenLabsCurrentlyPaused()) {
                        updateExternalDiagnostic(
                            "Playing cached ElevenLabs audio (${cachedMp3.name.take(16)}..., 0 chars used).",
                            isError = false
                        )
                    }
                    return@launch
                }
            }

            // Respect 24-hour pause after HTTP 401 or 402 without retrying
            if (securePrefs.isElevenLabsCurrentlyPaused()) {
                val pauseMsg = securePrefs.elevenLabsPauseMessage.ifBlank {
                    "ElevenLabs cloud voice is paused for 24 hours. Using Android TTS (1.0x)."
                }
                _cloudPausedUntilFlow.value = securePrefs.elevenLabsPausedUntilMillis
                _cloudPauseMessageFlow.value = pauseMsg
                updateExternalDiagnostic(pauseMsg, isError = true)
                speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
                return@launch
            }

            if (!hasActiveInternet()) {
                Log.w(TAG, "No internet connection available. Falling back to Android TTS.")
                updateExternalDiagnostic(
                    "Offline — speaking via Android TTS (${String.format(Locale.US, "%.2fx", fallbackSpeechRate)}).",
                    isError = false
                )
                speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
                return@launch
            }

            try {
                val synthesisResult = ElevenLabsClient.synthesizeToCacheFile(
                    context = appContext,
                    text = cleanText,
                    rawApiKey = savedElevenKey,
                    voiceId = voiceId
                )

                if (!isActive) {
                    return@launch
                }

                synthesisResult.fold(
                    onSuccess = { synth ->
                        val totalUsed = if (!synth.fromCache && synth.charCount > 0) {
                            securePrefs.addElevenLabsCharsUsed(synth.charCount)
                        } else {
                            securePrefs.elevenLabsCharsUsedThisMonth
                        }
                        _monthlyCharsUsedFlow.value = totalUsed

                        val started = playMp3FileWithMediaPlayer(
                            mp3File = synth.audioFile,
                            fallbackText = cleanText,
                            fallbackSpeechRate = fallbackSpeechRate,
                            onComplete = onComplete
                        )
                        if (started) {
                            val quotaWarning = if (totalUsed >= SecurePreferences.MONTHLY_ELEVENLABS_WARN_THRESHOLD) {
                                " | Warning: $totalUsed / ${SecurePreferences.MONTHLY_ELEVENLABS_CHAR_LIMIT} chars used this month (80%+)."
                            } else {
                                " | Monthly usage: $totalUsed / ${SecurePreferences.MONTHLY_ELEVENLABS_CHAR_LIMIT} chars."
                            }
                            updateExternalDiagnostic(
                                "ElevenLabs voice ($voiceId) playing${if (synth.fromCache) " from hash cache" else ""}$quotaWarning",
                                isError = totalUsed >= SecurePreferences.MONTHLY_ELEVENLABS_WARN_THRESHOLD
                            )
                        } else {
                            speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
                        }
                    },
                    onFailure = { err ->
                        handleElevenLabsFailure(err, cleanText, fallbackSpeechRate, onComplete)
                    }
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                handleElevenLabsFailure(e, cleanText, fallbackSpeechRate, onComplete)
            }
        }
    }

    private fun handleElevenLabsFailure(
        err: Throwable,
        cleanText: String,
        fallbackSpeechRate: Float,
        onComplete: (() -> Unit)?
    ) {
        val statusCode = (err as? ElevenLabsApiException)?.statusCode
            ?: when {
                err.message?.contains("HTTP 401") == true -> 401
                err.message?.contains("HTTP 402") == true -> 402
                else -> -1
            }

        if (statusCode == 401 || statusCode == 402) {
            val clearSettingsMessage = if (statusCode == 401) {
                "ElevenLabs API key rejected (HTTP 401). Cloud voice is paused for 24 hours — using Android voice (1.0x). Save a valid key to resume."
            } else {
                "ElevenLabs quota or plan limit reached (HTTP 402). Cloud voice is paused for 24 hours — using Android voice (1.0x)."
            }
            Log.w(TAG, clearSettingsMessage)
            securePrefs.pauseElevenLabsFor24Hours(clearSettingsMessage)
            _cloudPausedUntilFlow.value = securePrefs.elevenLabsPausedUntilMillis
            _cloudPauseMessageFlow.value = clearSettingsMessage
            updateExternalDiagnostic(clearSettingsMessage, isError = true)
            speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
        } else {
            val reason = formatElevenLabsError(err)
            Log.e(TAG, "ElevenLabs TTS failed ($reason). Falling back to Android TTS.", err)
            updateExternalDiagnostic(
                "$reason -> Using Android TTS (${String.format(Locale.US, "%.2fx", fallbackSpeechRate)}).",
                isError = true
            )
            speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
        }
    }

    private fun playMp3FileWithMediaPlayer(
        mp3File: File,
        fallbackText: String,
        fallbackSpeechRate: Float,
        onComplete: (() -> Unit)?
    ): Boolean {
        releaseMediaPlayerResources(mediaPlayer, activeMp3File, deleteCorruptFile = false)

        var createdPlayer: MediaPlayer? = null
        return try {
            if (!mp3File.exists() || mp3File.length() == 0L) {
                Log.e(TAG, "Cached ElevenLabs MP3 file missing or empty: ${mp3File.absolutePath}")
                mp3File.delete()
                return false
            }

            val player = MediaPlayer()
            createdPlayer = player

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            FileInputStream(mp3File).use { fis ->
                player.setDataSource(fis.fd)
            }

            player.setOnCompletionListener { mp ->
                releaseMediaPlayerResources(mp, mp3File, deleteCorruptFile = false)
                onSpeakingStateChanged(false)
                onComplete?.invoke()
            }

            player.setOnErrorListener { mp, what, extra ->
                val errMsg =
                    "MediaPlayer playback error (what=$what, extra=$extra) -> Fell back to Android TTS."
                Log.e(TAG, errMsg)
                updateExternalDiagnostic(errMsg, isError = true)
                releaseMediaPlayerResources(mp, mp3File, deleteCorruptFile = true)
                speakViaAndroidTts(fallbackText, fallbackSpeechRate, onComplete)
                true
            }

            player.prepare()
            mediaPlayer = player
            activeMp3File = mp3File
            onSpeakingStateChanged(true)
            player.start()
            true
        } catch (e: Exception) {
            val errMsg =
                "MediaPlayer prepare/start failed (${e.javaClass.simpleName}: ${e.message}) -> Fell back to Android TTS."
            Log.e(TAG, errMsg, e)
            updateExternalDiagnostic(errMsg, isError = true)
            releaseMediaPlayerResources(createdPlayer, mp3File, deleteCorruptFile = true)
            false
        }
    }

    private fun releaseMediaPlayerResources(
        player: MediaPlayer?,
        file: File?,
        deleteCorruptFile: Boolean
    ) {
        if (player != null) {
            try {
                player.setOnCompletionListener(null)
                player.setOnErrorListener(null)
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: IllegalStateException) {
            } catch (_: Exception) {
            }
            try {
                player.reset()
            } catch (_: Exception) {
            }
            try {
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaPlayer", e)
            }
            if (mediaPlayer === player) {
                mediaPlayer = null
            }
        }

        if (file != null) {
            if (deleteCorruptFile) {
                try {
                    if (file.exists()) {
                        file.delete()
                    }
                } catch (_: Exception) {
                }
            }
            if (activeMp3File == file) {
                activeMp3File = null
            }
        }
    }

    private fun speakViaAndroidTts(
        text: String,
        rate: Float,
        onComplete: (() -> Unit)?
    ) {
        val maxLen = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(1000)
        val safeText = if (text.length > maxLen) text.take(maxLen) else text
        val naturalRate = rate.takeIf { it > 0f } ?: DEFAULT_FALLBACK_RATE

        if (!isTtsReady) {
            queuedFallbackText = safeText
            queuedFallbackRate = naturalRate
            queuedOnDone = onComplete
            return
        }

        val engine = tts
        if (engine == null) {
            Log.e(TAG, "Android TextToSpeech engine is null; cannot speak fallback text.")
            onSpeakingStateChanged(false)
            onComplete?.invoke()
            return
        }

        val savedVoiceName = securePrefs.androidTtsVoiceName
        if (savedVoiceName.isNotBlank()) {
            try {
                val targetVoice = engine.voices?.firstOrNull { it.name == savedVoiceName }
                if (targetVoice != null && engine.voice?.name != targetVoice.name) {
                    engine.voice = targetVoice
                }
            } catch (_: Exception) {
            }
        }

        val sentences = splitIntoShortSentences(safeText)
        if (sentences.isEmpty()) {
            onSpeakingStateChanged(false)
            onComplete?.invoke()
            return
        }

        engine.setPitch(DEFAULT_FALLBACK_PITCH)
        engine.setSpeechRate(naturalRate.coerceIn(0.6f, 1.8f))

        val batchPrefix = "xestlab_${System.currentTimeMillis()}"
        val finalUtteranceId = "${batchPrefix}_${sentences.lastIndex}"
        activeFinalUtteranceId = finalUtteranceId
        activeOnDoneCallback = onComplete

        var firstError = false
        sentences.forEachIndexed { idx, sentence ->
            val utteranceId = "${batchPrefix}_$idx"
            val queueMode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val res = engine.speak(sentence, queueMode, null, utteranceId)
            if (res == TextToSpeech.ERROR && idx == 0) {
                firstError = true
            }
            if (!firstError && idx < sentences.lastIndex) {
                engine.playSilentUtterance(
                    SENTENCE_PAUSE_MS,
                    TextToSpeech.QUEUE_ADD,
                    "${batchPrefix}_pause_$idx"
                )
            }
        }

        if (firstError) {
            Log.e(TAG, "Android TextToSpeech.speak() returned ERROR")
            activeFinalUtteranceId = null
            activeOnDoneCallback = null
            onSpeakingStateChanged(false)
            onComplete?.invoke()
        }
    }

    private fun hasActiveInternet(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.w(TAG, "Connectivity check threw exception; proceeding with network attempt", e)
            true
        }
    }

    private fun formatElevenLabsError(error: Throwable): String {
        return when (error) {
            is ElevenLabsApiException -> "ElevenLabs HTTP ${error.statusCode}: ${error.errorDetail}"
            else -> {
                val cls = error.javaClass.simpleName.ifBlank { error.javaClass.name }
                "$cls: ${error.message ?: error.toString()}"
            }
        }
    }

    fun stop() {
        val jobToCancel = activeSpeakJob
        activeSpeakJob = null
        jobToCancel?.cancel()

        queuedFallbackText = null
        queuedOnDone = null
        activeFinalUtteranceId = null
        activeOnDoneCallback = null

        try {
            tts?.stop()
        } catch (_: Exception) {
        }

        releaseMediaPlayerResources(mediaPlayer, activeMp3File, deleteCorruptFile = false)
        onSpeakingStateChanged(false)
    }

    fun shutdown() {
        stop()
        engineScope.cancel()
        try {
            tts?.shutdown()
            tts = null
        } catch (_: Exception) {
        }
    }
}
