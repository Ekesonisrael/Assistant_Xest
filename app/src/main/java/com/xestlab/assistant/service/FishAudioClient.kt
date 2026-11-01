package com.xestlab.assistant.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object FishAudioClient {

    /**
     * Optional neural TTS byte synthesis. Returns null on timeout/error so callers
     * immediately fall back to built-in Android TextToSpeech.
     */
    suspend fun synthesizeMp3(
        text: String,
        apiKey: String,
        voiceId: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://api.fish.audio/v1/tts")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                setRequestProperty("Content-Type", "application/json")
            }

            val body = JSONObject().apply {
                put("text", text)
                put("format", "mp3")
                if (voiceId.isNotBlank()) {
                    put("reference_id", voiceId.trim())
                }
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            if (conn.responseCode in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
