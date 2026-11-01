package com.xestlab.assistant.service

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

object FishAudioClient {

    private const val TAG = "FishAudioClient"
    private const val ENDPOINT = "https://api.fish.audio/v1/tts"
    private const val TIMEOUT_MILLIS = 8000

    /**
     * Synthesizes speech with Fish Audio. Returns true if played successfully, false on fallback.
     */
    fun playFishAudio(
        context: Context,
        apiKey: String,
        voiceId: String,
        text: String,
        onCompletion: (() -> Unit)? = null
    ): Boolean {
        if (apiKey.isBlank()) return false
        var connection: HttpURLConnection? = null
        var tempFile: File? = null

        return try {
            val url = URL(ENDPOINT)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                doOutput = true
                doInput = true
            }

            val body = JSONObject().apply {
                put("text", text)
                if (voiceId.isNotBlank()) {
                    put("reference_id", voiceId)
                }
                put("format", "mp3")
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use {
                it.write(body.toString())
                it.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                tempFile = File.createTempFile("fish_tts_", ".mp3", context.cacheDir)
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val mediaPlayer = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    prepare()
                    setOnCompletionListener { mp ->
                        mp.release()
                        tempFile?.delete()
                        onCompletion?.invoke()
                    }
                    start()
                }
                true
            } else {
                Log.e(TAG, "Fish Audio response code: ${connection.responseCode}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fish Audio synthesis error", e)
            tempFile?.delete()
            false
        } finally {
            connection?.disconnect()
        }
    }
}
