package com.xestlab.assistant.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.xestlab.assistant.ai.AiClient
import com.xestlab.assistant.ui.theme.Shapes8px
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel
import com.xestlab.assistant.ui.viewmodel.JarvisMessage
import java.util.Locale

@Composable
fun JarvisScreen(viewModel: AssistantViewModel) {
    val context = LocalContext.current
    val messages by viewModel.chatMessages.collectAsState()
    val isProcessing by viewModel.isJarvisProcessing.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val isAiEnabled by viewModel.isAiEnabled.collectAsState()
    val isOnline = remember { AiClient.isNetworkAvailable(context) }

    var textInput by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var liveTranscript by remember { mutableStateOf("") }
    var micError by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    // Scroll to latest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Permission launcher for microphone
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (!granted) {
            micError = "Microphone permission required for voice input."
        }
    }

    // Speech recognizer management
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun stopRecording() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        isListening = false
    }

    fun startRecording() {
        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            micError = "Speech recognizer unavailable on this device."
            return
        }

        stopRecording()
        micError = null
        liveTranscript = ""
        isListening = true

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
            }
            override fun onError(error: Int) {
                isListening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Offline speech recognition used."
                    else -> "Voice input ended."
                }
                micError = msg
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val finalSpoken = matches?.firstOrNull()?.trim() ?: ""
                if (finalSpoken.isNotBlank()) {
                    liveTranscript = finalSpoken
                    viewModel.sendJarvisMessage(finalSpoken)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.firstOrNull()?.let { liveTranscript = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        sr.startListening(intent)
        recognizer = sr
    }

    DisposableEffect(Unit) {
        onDispose {
            stopRecording()
            viewModel.stopSpeaking()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Status header and offline banner
        if (!isAiEnabled || !isOnline || !viewModel.aiPreferences.hasValidKey()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, Shapes8px.small),
                shape = Shapes8px.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = if (!isAiEnabled) "AI layer is disabled in Settings."
                        else if (!viewModel.aiPreferences.hasValidKey()) "No Gemini API key set in Settings."
                        else "No network connection. Running in offline mode.",
                        fontSize = 12.dp.value.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Voice commands fall back to local offline commands: 'done', 'not done', or a count.",
                        fontSize = 11.dp.value.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // Live transcript or listening indicator
        if (isListening || liveTranscript.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .border(1.dp, TealAccent, Shapes8px.small),
                shape = Shapes8px.small,
                color = TealAccent.copy(alpha = 0.08f)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isListening) "Listening: " else "Transcript: ",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.dp.value.sp,
                        color = TealAccent
                    )
                    Text(
                        text = if (liveTranscript.isNotBlank()) liveTranscript else "Speak now...",
                        fontSize = 12.dp.value.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (micError != null) {
            Text(
                text = micError!!,
                color = Color(0xFFB3261E),
                fontSize = 11.dp.value.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // Stop speech control when assistant is speaking
        if (isSpeaking) {
            Button(
                onClick = { viewModel.stopSpeaking() },
                shape = Shapes8px.small,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text("Stop Speech", fontWeight = FontWeight.SemiBold)
            }
        }

        // Messages list (session only, max 10)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                JarvisMessageCard(message = msg)
            }

            if (isProcessing) {
                item {
                    Text(
                        text = "Jarvis is thinking...",
                        fontSize = 12.dp.value.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Large microphone button
        Button(
            onClick = {
                if (isListening) {
                    stopRecording()
                } else {
                    startRecording()
                }
            },
            shape = Shapes8px,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening) Color(0xFFB3261E) else TealAccent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(
                text = if (isListening) "Stop Recording" else "Speak to Jarvis (Voice Input)",
                fontSize = 15.dp.value.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Text input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { Text("Type a question or command...") },
                modifier = Modifier.weight(1f),
                shape = Shapes8px.small,
                singleLine = true
            )

            Button(
                onClick = {
                    val msg = textInput
                    textInput = ""
                    viewModel.sendJarvisMessage(msg)
                },
                enabled = textInput.isNotBlank() && !isProcessing,
                shape = Shapes8px.small,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun JarvisMessageCard(message: JarvisMessage) {
    val isJarvis = message.sender == "Jarvis"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = Shapes8px,
        colors = CardDefaults.cardColors(
            containerColor = if (isJarvis) MaterialTheme.colorScheme.surface
            else TealAccent.copy(alpha = 0.08f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isJarvis) MaterialTheme.colorScheme.outline else TealAccent.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = message.sender,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.dp.value.sp,
                    color = if (isJarvis) TealAccent else MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.text,
                fontSize = 14.dp.value.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Visual action chips if actions were executed
            if (message.executedActions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    message.executedActions.forEach { actionNote ->
                        Surface(
                            shape = Shapes8px.small,
                            color = TealAccent.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "Action: $actionNote",
                                fontSize = 11.dp.value.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TealAccent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
