package com.xestlab.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.*

class SpeechHelper(
    private val context: Context,
    private val onResult: (SpeechCommand) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available on this device.")
            return
        }

        stopListening()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Say 'done', 'not done', or a number."
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> "Speech recognition failed. For offline use, download the English offline speech pack in the Google app settings."
                        else -> "Listening ended."
                    }
                    onError(msg)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()?.lowercase(Locale.ROOT)?.trim() ?: ""
                    val command = parseCommand(spoken)
                    onResult(command)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    private fun parseCommand(raw: String): SpeechCommand {
        if (raw.contains("not done") || raw.contains("unfinished") || Regex("\\bno\\b").containsMatchIn(raw)) {
            return SpeechCommand.NotDone
        }
        if (raw.contains("done") || raw.contains("finished") || Regex("\\byes\\b").containsMatchIn(raw) || raw.contains("completed")) {
            return SpeechCommand.Done
        }
        // Extract numbers (e.g. "12 messages" or "twenty" or "5")
        val numberRegex = "\\d+".toRegex()
        val match = numberRegex.find(raw)
        if (match != null) {
            val num = match.value.toIntOrNull()
            if (num != null) return SpeechCommand.Count(num)
        }
        return SpeechCommand.Unrecognized(raw)
    }
}

sealed class SpeechCommand {
    object Done : SpeechCommand()
    object NotDone : SpeechCommand()
    data class Count(val value: Int) : SpeechCommand()
    data class Unrecognized(val raw: String) : SpeechCommand()
}
