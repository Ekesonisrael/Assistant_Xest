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
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ElevenLabsSynthesisResult(
    val audioFile: File,
    val modelUsed: String,
    val statusCode: Int,
    val byteCount: Long,
    val charCount: Int,
    val fromCache: Boolean
)

class ElevenLabsApiException(
    val statusCode: Int,
    val errorDetail: String
) : IOException("ElevenLabs HTTP $statusCode: $errorDetail")

/**
 * Dedicated OkHttp client for ElevenLabs Neural Text-to-Speech.
 *
 * - Default Voice ID: EXAVITQu4vr4xnSDxMaL
 * - Caches synthesized MP3 audio on disk by SHA-256(voiceId + normalized text) so repeated phrases
 *   play instantaneously without consuming monthly cloud characters.
 * - Never retries on HTTP 401 or 402 responses so VoiceEngine can pause cloud voice for 24 hours.
 */
object ElevenLabsClient {

    private const val TAG = "ElevenLabsClient"

    const val VOICE_ID = SecurePreferences.DEFAULT_ELEVENLABS_VOICE_ID
    const val TTS_ENDPOINT = "https://api.elevenlabs.io/v1/text-to-speech/$VOICE_ID"

    const val PRIMARY_MODEL_ID = "eleven_multilingual_v2"
    private const val FALLBACK_TURBO_MODEL_ID = "eleven_turbo_v2_5"

    private const val HASH_CACHE_DIR_NAME = "tts_hash_cache"
    private const val HASH_FILE_PREFIX = "tts_"
    private const val HASH_FILE_SUFFIX = ".mp3"
    private const val MAX_CACHED_FILES = 150
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

    fun normalizeSpeechText(text: String): String {
        return AiClient.sanitizeForSpeech(text).take(MAX_SPEECH_CHARS).trim()
    }

    fun computeTextHash(text: String, voiceId: String = VOICE_ID): String {
        val normalized = normalizeSpeechText(text)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$voiceId|$normalized".toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                append(String.format(Locale.US, "%02x", b))
            }
        }
    }

    private fun getOrCreateHashCacheDir(context: Context): File {
        val dir = File(context.cacheDir, HASH_CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCachedAudioFile(
        context: Context,
        text: String,
        voiceId: String = VOICE_ID
    ): File? {
        val cleanText = normalizeSpeechText(text)
        if (cleanText.isEmpty()) return null
        val hash = computeTextHash(cleanText, voiceId)
        val cachedFile = File(getOrCreateHashCacheDir(context), "$HASH_FILE_PREFIX$hash$HASH_FILE_SUFFIX")
        return if (cachedFile.exists() && cachedFile.length() > 64L) {
            try {
                cachedFile.setLastModified(System.currentTimeMillis())
            } catch (_: Exception) {
            }
            cachedFile
        } else {
            null
        }
    }

    suspend fun synthesizeToCacheFile(
        context: Context,
        text: String,
        rawApiKey: String,
        voiceId: String = VOICE_ID
    ): Result<ElevenLabsSynthesisResult> = withContext(Dispatchers.IO) {
        val cleanText = normalizeSpeechText(text)
        if (cleanText.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Speech text is empty."))
        }

        val resolvedVoiceId = voiceId.trim().ifBlank { VOICE_ID }

        // 1. Check deterministic SHA-256 text-hash cache first
        getCachedAudioFile(context, cleanText, resolvedVoiceId)?.let { hitFile ->
            return@withContext Result.success(
                ElevenLabsSynthesisResult(
                    audioFile = hitFile,
                    modelUsed = "disk-cache",
                    statusCode = 200,
                    byteCount = hitFile.length(),
                    charCount = 0,
                    fromCache = true
                )
            )
        }

        val cleanKey = SecurePreferences.sanitizeApiKey(rawApiKey)
        if (cleanKey.isBlank()) {
            val err = IllegalArgumentException("ElevenLabs API key is blank.")
            Log.w(TAG, err.message ?: "Missing ElevenLabs key")
            return@withContext Result.failure(err)
        }

        val hashCacheDir = getOrCreateHashCacheDir(context)
        val targetCacheFile = File(
            hashCacheDir,
            "$HASH_FILE_PREFIX${computeTextHash(cleanText, resolvedVoiceId)}$HASH_FILE_SUFFIX"
        )

        val candidateModels = listOf(
            PRIMARY_MODEL_ID,
            FALLBACK_TURBO_MODEL_ID
        )

        var lastError: Throwable? = null

        for ((index, modelId) in candidateModels.withIndex()) {
            try {
                val attempt = executeSingleTtsRequest(
                    hashCacheDir = hashCacheDir,
                    targetCacheFile = targetCacheFile,
                    voiceId = resolvedVoiceId,
                    apiKey = cleanKey,
                    text = cleanText,
                    modelId = modelId
                )
                if (attempt.isSuccess) {
                    evictOldestCacheFilesIfNeeded(hashCacheDir)
                    return@withContext attempt
                }

                val failure = attempt.exceptionOrNull()
                lastError = failure

                // Never retry on HTTP 401 or 402 — pause cloud voice immediately in VoiceEngine
                if (failure is ElevenLabsApiException &&
                    (failure.statusCode == 401 || failure.statusCode == 402)
                ) {
                    break
                }

                val shouldRetryNextModel = failure is ElevenLabsApiException &&
                    index < candidateModels.lastIndex &&
                    isModelDeprecationOrUnsupportedError(failure.statusCode, failure.errorDetail)

                if (shouldRetryNextModel) {
                    val nextModel = candidateModels[index + 1]
                    Log.w(
                        TAG,
                        "Model '$modelId' unsupported (${failure?.message}); trying '$nextModel'."
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
        hashCacheDir: File,
        targetCacheFile: File,
        voiceId: String,
        apiKey: String,
        text: String,
        modelId: String
    ): Result<ElevenLabsSynthesisResult> {
        var stagingFile: File? = null
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

            val requestBody = payloadJson
                .toString()
                .toByteArray(Charsets.UTF_8)
                .toRequestBody(JSON_MEDIA_TYPE)

            val endpointUrl = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"
            val request = Request.Builder()
                .url(endpointUrl)
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
                        if (response.code == 401 || response.code == 402) {
                            Log.w(
                                TAG,
                                "ElevenLabs HTTP ${response.code} (voice=$voiceId, model=$modelId): $detail"
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

                    val tmpOut = File.createTempFile("staging_", ".tmp", hashCacheDir)
                    stagingFile = tmpOut

                    responseBody.byteStream().use { inputStream ->
                        FileOutputStream(tmpOut).use { outputStream ->
                            inputStream.copyTo(outputStream, bufferSize = 8 * 1024)
                            outputStream.flush()
                            try {
                                outputStream.fd.sync()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    val bytesWritten = tmpOut.length()
                    if (bytesWritten <= 64L) {
                        tmpOut.delete()
                        val emptyEx = IOException(
                            "ElevenLabs returned invalid/empty audio stream ($bytesWritten bytes)."
                        )
                        Log.e(TAG, emptyEx.message ?: "Empty MP3 stream")
                        return Result.failure(emptyEx)
                    }

                    if (targetCacheFile.exists()) {
                        targetCacheFile.delete()
                    }
                    val finalFile = if (tmpOut.renameTo(targetCacheFile)) {
                        stagingFile = null
                        targetCacheFile
                    } else {
                        tmpOut.copyTo(targetCacheFile, overwrite = true)
                        tmpOut.delete()
                        stagingFile = null
                        targetCacheFile
                    }

                    Log.i(
                        TAG,
                        "ElevenLabs TTS cached: HTTP ${response.code}, voice=$voiceId, model=$modelId, " +
                            "chars=${text.length}, bytes=$bytesWritten, file=${finalFile.name}"
                    )
                    Result.success(
                        ElevenLabsSynthesisResult(
                            audioFile = finalFile,
                            modelUsed = modelId,
                            statusCode = response.code,
                            byteCount = bytesWritten,
                            charCount = text.length,
                            fromCache = false
                        )
                    )
                }
            } finally {
                cancelHandle?.dispose()
            }
        } catch (ce: CancellationException) {
            stagingFile?.delete()
            throw ce
        } catch (e: Exception) {
            stagingFile?.delete()
            Log.e(TAG, "ElevenLabs network/stream failure (model=$modelId): ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun isModelDeprecationOrUnsupportedError(statusCode: Int, detail: String): Boolean {
        if (statusCode !in setOf(400, 404, 422)) return false
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

    private fun evictOldestCacheFilesIfNeeded(hashCacheDir: File) {
        try {
            val mp3Files = hashCacheDir.listFiles { file ->
                file.isFile &&
                    file.name.startsWith(HASH_FILE_PREFIX) &&
                    file.name.endsWith(HASH_FILE_SUFFIX)
            } ?: return
            if (mp3Files.size <= MAX_CACHED_FILES) return
            mp3Files.sortBy { it.lastModified() }
            val toDelete = mp3Files.size - MAX_CACHED_FILES
            for (i in 0 until toDelete) {
                mp3Files[i].delete()
            }
        } catch (_: Exception) {
        }
    }
}
