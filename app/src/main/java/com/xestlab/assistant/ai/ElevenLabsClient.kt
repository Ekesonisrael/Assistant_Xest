package com.xestlab.assistant.ai

import android.content.Context
import android.util.Log
import com.xestlab.assistant.data.SecurePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ElevenLabsSynthesisResult(
    val audioFile: File,
    val modelUsed: String,
    val statusCode: Int,
    val byteCount: Long
)

class ElevenLabsApiException(
    val statusCode: Int,
    val errorDetail: String
) : IOException("ElevenLabs HTTP $statusCode: $errorDetail")

/**
 * Dedicated OkHttp client for ElevenLabs Neural Text-to-Speech.
 *
 * Endpoint: https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM
 * Headers:
 *  - "xi-api-key": <saved_elevenlabs_key>
 *  - "Content-Type": "application/json"
 * Body:
 *  {"text": "...", "model_id": "eleven_monolingual_v1", "voice_settings": {"stability": 0.5, "similarity_boost": 0.5}}
 */
object ElevenLabsClient {

    private const val TAG = "ElevenLabsClient"

    const val VOICE_ID = "21m00Tcm4TlvDq8ikWAM"
    const val TTS_ENDPOINT = "https://api.elevenlabs.io/v1/text-to-speech/$VOICE_ID"

    const val PRIMARY_MODEL_ID = "eleven_monolingual_v1"
    private const val FALLBACK_MULTILINGUAL_MODEL_ID = "eleven_multilingual_v2"
    private const val FALLBACK_TURBO_MODEL_ID = "eleven_turbo_v2_5"

    private const val TEMP_FILE_PREFIX = "elevenlabs_tts_"
    private const val TEMP_FILE_SUFFIX = ".mp3"
    private const val MAX_SPEECH_CHARS = 2500

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    val ttsHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Synthesizes [text] via ElevenLabs and writes the resulting MP3 audio stream to a
     * temporary file inside `context.cacheDir`.
     *
     * Primary call uses `eleven_monolingual_v1` with `stability = 0.5` and `similarity_boost = 0.5`.
     * If ElevenLabs rejects `eleven_monolingual_v1` specifically due to v1 model deprecation on
     * free/modern accounts (`model_deprecated_free_tier`), automatically retries with
     * `eleven_multilingual_v2` / `eleven_turbo_v2_5` so neural playback still succeeds.
     * Any auth (`401 invalid_api_key`), quota (`quota_exceeded`), rate-limit (`429`), or network
     * failure is caught, logged via `Log.e`, and returned as `Result.failure` for instant Android TTS fallback.
     */
    suspend fun synthesizeToCacheFile(
        context: Context,
        text: String,
        rawApiKey: String
    ): Result<ElevenLabsSynthesisResult> = withContext(Dispatchers.IO) {
        val cleanKey = SecurePreferences.sanitizeApiKey(rawApiKey)
        if (cleanKey.isBlank()) {
            val err = IllegalArgumentException("ElevenLabs API key is blank.")
            Log.w(TAG, err.message ?: "Missing ElevenLabs key")
            return@withContext Result.failure(err)
        }

        val cleanText = AiClient.sanitizeForSpeech(text).take(MAX_SPEECH_CHARS).trim()
        if (cleanText.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Speech text is empty."))
        }

        pruneStaleCacheFiles(context.cacheDir)

        val candidateModels = listOf(
            PRIMARY_MODEL_ID,
            FALLBACK_MULTILINGUAL_MODEL_ID,
            FALLBACK_TURBO_MODEL_ID
        )

        var lastError: Throwable? = null

        for ((index, modelId) in candidateModels.withIndex()) {
            try {
                val attempt = executeSingleTtsRequest(
                    cacheDir = context.cacheDir,
                    apiKey = cleanKey,
                    text = cleanText,
                    modelId = modelId
                )
                if (attempt.isSuccess) {
                    return@withContext attempt
                }

                val failure = attempt.exceptionOrNull()
                lastError = failure

                val shouldRetryNextModel = failure is ElevenLabsApiException &&
                    index < candidateModels.lastIndex &&
                    isModelDeprecationOrUnsupportedError(failure.statusCode, failure.errorDetail)

                if (shouldRetryNextModel) {
                    val nextModel = candidateModels[index + 1]
                    Log.w(
                        TAG,
                        "Model '$modelId' rejected by ElevenLabs (${failure?.message}); retrying with '$nextModel'."
                    )
                    continue
                } else {
                    break
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "ElevenLabs request exception (model=$modelId): ${e.message}", e)
                lastError = e
                break
            }
        }

        Result.failure(
            lastError ?: IOException("ElevenLabs synthesis failed with unknown error.")
        )
    }

    private suspend fun executeSingleTtsRequest(
        cacheDir: File,
        apiKey: String,
        text: String,
        modelId: String
    ): Result<ElevenLabsSynthesisResult> {
        var tempFile: File? = null
        return try {
            val voiceSettingsJson = JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.5)
            }
            val payloadJson = JSONObject().apply {
                put("text", text)
                put("model_id", modelId)
                put("voice_settings", voiceSettingsJson)
            }

            // Convert UTF-8 bytes directly so OkHttp keeps Content-Type as exact "application/json"
            val requestBody = payloadJson
                .toString()
                .toByteArray(Charsets.UTF_8)
                .toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(TTS_ENDPOINT)
                .header("xi-api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "audio/mpeg")
                .post(requestBody)
                .build()

            val call = ttsHttpClient.newCall(request)
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    try {
                        call.cancel()
                    } catch (_: Exception) {
                    }
                }
            }

            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val rawErrorBody = response.body?.string().orEmpty()
                        val detail = extractReadableError(rawErrorBody)
                        val apiEx = ElevenLabsApiException(response.code, detail)
                        if (response.code == 402) {
                            Log.w(
                                TAG,
                                "ElevenLabs HTTP 402 (free-tier library voice restriction, model=$modelId): $detail. Falling back to Android TTS (0.9x)."
                            )
                        } else {
                            Log.e(
                                TAG,
                                "ElevenLabs API error (HTTP ${response.code}, model=$modelId): $detail"
                            )
                        }
                        return Result.failure(apiEx)
                    }

                    val responseBody = response.body
                        ?: return Result.failure(
                            IOException("ElevenLabs HTTP ${response.code} returned null body.")
                        )

                    val outFile = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, cacheDir)
                    tempFile = outFile

                    responseBody.byteStream().use { inputStream ->
                        FileOutputStream(outFile).use { outputStream ->
                            inputStream.copyTo(outputStream, bufferSize = 8 * 1024)
                            outputStream.flush()
                            try {
                                outputStream.fd.sync()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    val bytesWritten = outFile.length()
                    if (bytesWritten <= 64L) {
                        outFile.delete()
                        val emptyEx = IOException(
                            "ElevenLabs returned invalid/empty audio stream ($bytesWritten bytes)."
                        )
                        Log.e(TAG, emptyEx.message ?: "Empty MP3 stream")
                        return Result.failure(emptyEx)
                    }

                    Log.i(
                        TAG,
                        "ElevenLabs TTS success: HTTP ${response.code}, model=$modelId, bytes=$bytesWritten, file=${outFile.name}"
                    )
                    Result.success(
                        ElevenLabsSynthesisResult(
                            audioFile = outFile,
                            modelUsed = modelId,
                            statusCode = response.code,
                            byteCount = bytesWritten
                        )
                    )
                }
            } finally {
                cancelHandle?.dispose()
            }
        } catch (ce: CancellationException) {
            tempFile?.delete()
            throw ce
        } catch (e: Exception) {
            tempFile?.delete()
            Log.e(TAG, "ElevenLabs network/stream failure (model=$modelId): ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun isModelDeprecationOrUnsupportedError(statusCode: Int, detail: String): Boolean {
        if (statusCode !in setOf(400, 401, 403, 404, 422)) return false
        val lower = detail.lowercase(Locale.US)
        if (lower.contains("invalid_api_key") ||
            lower.contains("invalid api key") ||
            lower.contains("quota_exceeded") ||
            lower.contains("quota") ||
            lower.contains("missing_permissions")
        ) {
            return false
        }
        return lower.contains("model_deprecated") ||
            lower.contains("deprecated") ||
            lower.contains("model_not_found") ||
            lower.contains("eleven_monolingual_v1") ||
            lower.contains("free_tier") ||
            lower.contains("model")
    }

    private fun extractReadableError(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return "Empty error response from ElevenLabs"
        return try {
            val root = JSONObject(trimmed)
            val detailObj = root.optJSONObject("detail")
            if (detailObj != null) {
                val status = detailObj.optString("status", "").trim()
                val message = detailObj.optString("message", "").trim()
                when {
                    status.isNotEmpty() && message.isNotEmpty() -> "[$status] $message"
                    message.isNotEmpty() -> message
                    status.isNotEmpty() -> status
                    else -> trimmed.take(350)
                }
            } else {
                val detailStr = root.optString("detail", "").trim()
                if (detailStr.isNotEmpty()) detailStr else trimmed.take(350)
            }
        } catch (_: Exception) {
            trimmed.replace(Regex("\\s+"), " ").take(350)
        }
    }

    private fun pruneStaleCacheFiles(cacheDir: File) {
        try {
            val cutoff = System.currentTimeMillis() - 5 * 60_000L
            cacheDir.listFiles { file ->
                file.isFile &&
                    file.name.startsWith(TEMP_FILE_PREFIX) &&
                    file.name.endsWith(TEMP_FILE_SUFFIX)
            }?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }
}
