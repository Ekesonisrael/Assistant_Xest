package com.xestlab.assistant.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.xestlab.assistant.ai.AiClient
import com.xestlab.assistant.data.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Unified voice playback engine:
 * 1. If ElevenLabs API key is present in EncryptedSharedPreferences, synthesizes MP3 via
 *    https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM and plays with MediaPlayer.
 * 2. Otherwise (or on network error), falls back to built-in Android TextToSpeech with natural
 *    speech rate 0.9f and pitch 1.0f.
 */
class VoiceEngine(
    context: Context,
    private val securePrefs: SecurePreferences,
    private val onSpeakingStateChanged: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        const val ELEVENLABS_VOICE_URL =
            "https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM"
        const val DEFAULT_FALLBACK_RATE = 0.9f
        const val DEFAULT_FALLBACK_PITCH = 1.0f
    }

    private val appContext = context.applicationContext
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeSpeakJob: Job? = null

    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var isTtsReady = false
    private var queuedFallbackText: String? = null
    private var queuedFallbackRate: Float = DEFAULT_FALLBACK_RATE
    private var queuedOnDone: (() -> Unit)? = null

    private var activeOnDoneCallback: (() -> Unit)? = null
    private var mediaPlayer: MediaPlayer? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val langResult = engine.setLanguage(Locale.getDefault())
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }
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
                    onSpeakingStateChanged(false)
                    val cb = activeOnDoneCallback
                    activeOnDoneCallback = null
                    cb?.invoke()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
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
            onSpeakingStateChanged(false)
            queuedOnDone?.invoke()
            queuedOnDone = null
        }
    }

    fun speak(
        text: String,
        fallbackSpeechRate: Float = DEFAULT_FALLBACK_RATE,
        onComplete: (() -> Unit)? = null
    ) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            onComplete?.invoke()
            return
        }

        stop()
        onSpeakingStateChanged(true)

        activeSpeakJob = engineScope.launch {
            val elevenKey = securePrefs.elevenLabsApiKey
            if (elevenKey.isNotBlank()) {
                val mp3Bytes = fetchElevenLabsMp3(cleanText, elevenKey)
                if (mp3Bytes != null && playMp3WithMediaPlayer(mp3Bytes, onComplete)) {
                    return@launch
                }
            }
            speakViaAndroidTts(cleanText, fallbackSpeechRate, onComplete)
        }
    }

    private suspend fun fetchElevenLabsMp3(text: String, apiKey: String): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val primaryBody = JSONObject().apply {
                    put("text", text)
                    put("model_id", "eleven_multilingual_v2")
                    put(
                        "voice_settings",
                        JSONObject()
                            .put("stability", 0.5)
                            .put("similarity_boost", 0.75)
                    )
                }

                val req = Request.Builder()
                    .url(ELEVENLABS_VOICE_URL)
                    .header("xi-api-key", apiKey.trim())
                    .header("Accept", "audio/mpeg")
                    .header("Content-Type", "application/json")
                    .post(primaryBody.toString().toRequestBody(mediaType))
                    .build()

                AiClient.httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            return@withContext bytes
                        }
                    }
                }

                // Fallback attempt omitting model_id so ElevenLabs uses account default model
                val defaultBody = JSONObject().apply {
                    put("text", text)
                    put(
                        "voice_settings",
                        JSONObject()
                            .put("stability", 0.5)
                            .put("similarity_boost", 0.75)
                    )
                }
                val fallbackReq = Request.Builder()
                    .url(ELEVENLABS_VOICE_URL)
                    .header("xi-api-key", apiKey.trim())
                    .header("Accept", "audio/mpeg")
                    .header("Content-Type", "application/json")
                    .post(defaultBody.toString().toRequestBody(mediaType))
                    .build()

                AiClient.httpClient.newCall(fallbackReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            return@withContext bytes
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }

    private fun playMp3WithMediaPlayer(
        mp3Bytes: ByteArray,
        onComplete: (() -> Unit)?
    ): Boolean {
        return try {
            val outFile = File(appContext.cacheDir, "elevenlabs_tts_output.mp3")
            FileOutputStream(outFile).use { it.write(mp3Bytes) }

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(outFile.absolutePath)
                setOnCompletionListener { mp ->
                    try {
                        mp.release()
                    } catch (_: Exception) {
                    }
                    if (mediaPlayer === mp) mediaPlayer = null
                    onSpeakingStateChanged(false)
                    onComplete?.invoke()
                }
                setOnErrorListener { mp, _, _ ->
                    try {
                        mp.release()
                    } catch (_: Exception) {
                    }
                    if (mediaPlayer === mp) mediaPlayer = null
                    onSpeakingStateChanged(false)
                    onComplete?.invoke()
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

    private fun speakViaAndroidTts(
        text: String,
        rate: Float,
        onComplete: (() -> Unit)?
    ) {
        val naturalRate = rate.takeIf { it > 0f } ?: DEFAULT_FALLBACK_RATE
        if (!isTtsReady) {
            queuedFallbackText = text
            queuedFallbackRate = naturalRate
            queuedOnDone = onComplete
            return
        }
        val engine = tts
        if (engine == null) {
            onSpeakingStateChanged(false)
            onComplete?.invoke()
            return
        }
        activeOnDoneCallback = onComplete
        engine.setPitch(DEFAULT_FALLBACK_PITCH)
        engine.setSpeechRate(naturalRate.coerceIn(0.6f, 1.8f))
        val res = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "xestlab_voice_${System.currentTimeMillis()}"
        )
        if (res == TextToSpeech.ERROR) {
            activeOnDoneCallback = null
            onSpeakingStateChanged(false)
            onComplete?.invoke()
        }
    }

    fun stop() {
        activeSpeakJob?.cancel()
        activeSpeakJob = null
        queuedFallbackText = null
        queuedOnDone = null
        activeOnDoneCallback = null

        try {
            tts?.stop()
        } catch (_: Exception) {
        }

        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.release()
            }
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
        }

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
