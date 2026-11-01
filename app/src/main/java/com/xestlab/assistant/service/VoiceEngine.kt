package com.xestlab.assistant.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

/**
 * Unified Voice Engine routing all spoken output in Xestlab Assistant
 * (daily briefings, AI chat replies, task completions, checklist congratulations,
 * 7:30 AM wake-up alarms, and Jarvis wake-word responses):
 *
 * 1. Checks if the ElevenLabs API key is saved in EncryptedSharedPreferences AND
 *    whether an internet connection is available.
 * 2. IF key exists and internet is available:
 *    - Sends OkHttp POST to https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM
 *    - Saves the returned MP3 stream to a temporary file in appContext.cacheDir
 *    - Plays it via Android's MediaPlayer (using an open FileDescriptor) and releases
 *      all MediaPlayer + cache file resources upon completion/error/stop.
 * 3. IF key is missing, offline, ElevenLabs returns an error (401, quota limit, etc.),
 *    or MediaPlayer fails:
 *    - Immediately catches & logs the exact error via Log.e and falls back to Android
 *      TextToSpeech (0.9f speech rate, 1.0f pitch) so the app never goes silent.
 */
class VoiceEngine(
    context: Context,
    private val securePrefs: SecurePreferences,
    private val onSpeakingStateChanged: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VoiceEngine"

        const val ELEVENLABS_VOICE_URL = ElevenLabsClient.TTS_ENDPOINT
        const val DEFAULT_FALLBACK_RATE = 0.9f
        const val DEFAULT_FALLBACK_PITCH = 1.0f

        private val _statusMessageFlow = MutableStateFlow(
            "Voice Engine ready. Save your ElevenLabs API key to enable neural voice (21m00Tcm4TlvDq8ikWAM)."
        )
        val statusMessageFlow: StateFlow<String> = _statusMessageFlow.asStateFlow()

        private val _statusIsErrorFlow = MutableStateFlow(false)
        val statusIsErrorFlow: StateFlow<Boolean> = _statusIsErrorFlow.asStateFlow()

        fun updateExternalDiagnostic(message: String, isError: Boolean) {
            _statusMessageFlow.value = message
            _statusIsErrorFlow.value = isError
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

    private var activeOnDoneCallback: (() -> Unit)? = null
    private var mediaPlayer: MediaPlayer? = null
    private var activeTempMp3File: File? = null

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
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onSpeakingStateChanged(true)
                }

                override fun onDone(utteranceId: String?) {
                    onSpeakingStateChanged(false)
                    val cb = activeOnDoneCallback
                    activeOnDoneCallback = null
                    cb?.invoke()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "Android TextToSpeech utterance error (utteranceId=$utteranceId)")
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
                    onSpeakingStateChanged(false)
                    val cb = activeOnDoneCallback
                    activeOnDoneCallback = null
                    cb?.invoke()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
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

    /**
     * Speaks [text] aloud:
     * - Routes to ElevenLabs (https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM)
     *   whenever an ElevenLabs API key is saved in EncryptedSharedPreferences and internet is available.
     * - Automatically falls back to Android TextToSpeech if the key is missing, internet is unavailable,
     *   ElevenLabs returns an HTTP/quota/auth error, or MediaPlayer fails.
     */
    fun speak(
        text: String,
        fallbackSpeechRate: Float = DEFAULT_FALLBACK_RATE,
        onComplete: (() -> Unit)? = null
    ) {
        val cleanText = AiClient.sanitizeForSpeech(text)
        if (cleanText.isEmpty()) {
            onComplete?.invoke()
            return
        }

        stop()
        onSpeakingStateChanged(true)

        activeSpeakJob = engineScope.launch {
            val savedElevenKey = securePrefs.elevenLabsApiKey

            if (savedElevenKey.isBlank()) {
                Log.d(
                    TAG,
                    "No ElevenLabs API key saved in EncryptedSharedPreferences. Falling back to Android TTS."
                )
                updateExternalDiagnostic(
                    "No ElevenLabs key saved — speaking via Android TTS (${String.format(Locale.US, "%.2fx", fallbackSpeechRate)}).",
                    isError = false
                )
                speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
                return@launch
            }

            if (!hasActiveInternet()) {
                Log.w(TAG, "No internet connection available. Falling back to Android TTS.")
                updateExternalDiagnostic(
                    "No internet connection — fell back to Android TTS.",
                    isError = true
                )
                speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
                return@launch
            }

            updateExternalDiagnostic(
                "Synthesizing speech via ElevenLabs (21m00Tcm4TlvDq8ikWAM)...",
                isError = false
            )

            try {
                val synthesisResult = ElevenLabsClient.synthesizeToCacheFile(
                    context = appContext,
                    text = cleanText,
                    rawApiKey = savedElevenKey
                )

                if (!isActive) {
                    synthesisResult.getOrNull()?.audioFile?.delete()
                    return@launch
                }

                synthesisResult.fold(
                    onSuccess = { synth ->
                        val started = playMp3FileWithMediaPlayer(
                            mp3File = synth.audioFile,
                            fallbackText = cleanText,
                            fallbackSpeechRate = fallbackSpeechRate,
                            onComplete = onComplete
                        )
                        if (started) {
                            updateExternalDiagnostic(
                                "ElevenLabs HTTP ${synth.statusCode} OK (${synth.modelUsed}, ${synth.byteCount} bytes) — Playing neural MP3 via MediaPlayer.",
                                isError = false
                            )
                        } else {
                            // playMp3FileWithMediaPlayer already logged the error and released resources
                            speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
                        }
                    },
                    onFailure = { err ->
                        val isFreeTier402 = (err is ElevenLabsApiException && err.statusCode == 402) ||
                            (err.message?.contains("402") == true)

                        if (isFreeTier402) {
                            Log.w(
                                TAG,
                                "ElevenLabs returned 402 on free tier (${err.message}). Silently falling back to Android TTS (0.9x)."
                            )
                            updateExternalDiagnostic(
                                "Using Android TTS (0.9x natural voice — ElevenLabs free-tier fallback active).",
                                isError = false
                            )
                            speakViaAndroidTts(cleanText, DEFAULT_FALLBACK_RATE, onComplete)
                        } else {
                            val reason = formatElevenLabsError(err)
                            Log.e(
                                TAG,
                                "ElevenLabs TTS failed ($reason). Falling back to Android TTS (0.9x).",
                                err
                            )
                            updateExternalDiagnostic(
                                "$reason -> Fell back to Android TTS (0.9x).",
                                isError = false
                            )
                            speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
                        }
                    }
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val isFreeTier402 = (e is ElevenLabsApiException && e.statusCode == 402) ||
                    (e.message?.contains("402") == true)
                if (isFreeTier402) {
                    Log.w(
                        TAG,
                        "Caught ElevenLabs 402 on free tier (${e.message}). Silently falling back to Android TTS (0.9x)."
                    )
                    updateExternalDiagnostic(
                        "Using Android TTS (0.9x natural voice — ElevenLabs free-tier fallback active).",
                        isError = false
                    )
                    speakViaAndroidTts(cleanText, DEFAULT_FALLBACK_RATE, onComplete)
                } else {
                    val reason = formatElevenLabsError(e)
                    Log.e(
                        TAG,
                        "Unexpected ElevenLabs routing error ($reason). Falling back to Android TTS (0.9x).",
                        e
                    )
                    updateExternalDiagnostic(
                        "$reason -> Fell back to Android TTS (0.9x).",
                        isError = false
                    )
                    speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
                }
            }
        }
    }

    /**
     * Plays the temporary MP3 file stored in `appContext.cacheDir` using an open [FileInputStream]
     * file descriptor so Android's external `mediaserver` process can read private cache files
     * across all Android versions (API 26-35), and guarantees `MediaPlayer.release()` + temp file
     * cleanup after playback or on error.
     */
    private fun playMp3FileWithMediaPlayer(
        mp3File: File,
        fallbackText: String,
        fallbackSpeechRate: Float,
        onComplete: (() -> Unit)?
    ): Boolean {
        releaseMediaPlayerResources(mediaPlayer, activeTempMp3File)

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
                releaseMediaPlayerResources(mp, mp3File)
                onSpeakingStateChanged(false)
                onComplete?.invoke()
            }

            player.setOnErrorListener { mp, what, extra ->
                val errMsg =
                    "MediaPlayer playback error (what=$what, extra=$extra) -> Fell back to Android TTS."
                Log.e(TAG, errMsg)
                updateExternalDiagnostic(errMsg, isError = true)
                releaseMediaPlayerResources(mp, mp3File)
                speakViaAndroidTts(fallbackText, fallbackSpeechRate, onComplete)
                true
            }

            player.prepare()
            mediaPlayer = player
            activeTempMp3File = mp3File
            onSpeakingStateChanged(true)
            player.start()
            true
        } catch (e: Exception) {
            val errMsg =
                "MediaPlayer prepare/start failed (${e.javaClass.simpleName}: ${e.message}) -> Fell back to Android TTS."
            Log.e(TAG, errMsg, e)
            updateExternalDiagnostic(errMsg, isError = true)
            releaseMediaPlayerResources(createdPlayer, mp3File)
            false
        }
    }

    private fun releaseMediaPlayerResources(player: MediaPlayer?, tempFile: File?) {
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

        if (tempFile != null) {
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            } catch (_: Exception) {
            }
            if (activeTempMp3File == tempFile) {
                activeTempMp3File = null
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

        activeOnDoneCallback = onComplete
        engine.setPitch(DEFAULT_FALLBACK_PITCH)
        engine.setSpeechRate(naturalRate.coerceIn(0.6f, 1.8f))
        val res = engine.speak(
            safeText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "xestlab_voice_${System.currentTimeMillis()}"
        )
        if (res == TextToSpeech.ERROR) {
            Log.e(TAG, "Android TextToSpeech.speak() returned ERROR")
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
        activeOnDoneCallback = null

        try {
            tts?.stop()
        } catch (_: Exception) {
        }

        releaseMediaPlayerResources(mediaPlayer, activeTempMp3File)
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
