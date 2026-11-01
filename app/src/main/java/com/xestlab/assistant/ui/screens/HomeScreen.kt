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
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.ui.theme.Shapes8px
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.WarningRed
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel
import com.xestlab.assistant.ui.viewmodel.ChatMessage
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(viewModel: AssistantViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val messages by viewModel.chatMessages.collectAsState()
    val activeProposal by viewModel.activeProposal.collectAsState()
    val pendingItems by viewModel.pendingItems.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()

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
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Offline speech recognition active."
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
                    viewModel.handleUniversalInput(finalSpoken, isSpoken = true)
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

    // Next Up item
    val nextUpItem = remember(pendingItems) {
        pendingItems.firstOrNull { it.dueAt != null && it.dueAt > System.currentTimeMillis() }
            ?: pendingItems.firstOrNull()
    }

    // Animated pulse scale for mic ring
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // System serif greeting at 32sp per specification
        val greetingText = remember(settings.userName) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val timeWord = when {
                hour < 12 -> "Good morning"
                hour < 17 -> "Good afternoon"
                else -> "Good evening"
            }
            "$timeWord, ${settings.userName}"
        }

        Text(
            text = greetingText,
            fontSize = 32.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground,
            lineHeight = 38.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Next Up Card
        if (nextUpItem != null) {
            NextUpCard(
                item = nextUpItem,
                onComplete = { viewModel.completeItem(nextUpItem) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Live transcript / Listening indicator
        if (isListening || liveTranscript.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .border(1.dp, TealAccent, Shapes8px.small),
                shape = Shapes8px.small,
                color = TealAccent.copy(alpha = 0.12f)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isListening) "Listening: " else "Heard: ",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = TealAccent
                    )
                    Text(
                        text = if (liveTranscript.isNotBlank()) liveTranscript else "Say anything...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (micError != null) {
            Text(
                text = micError!!,
                color = WarningRed,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // Stop speech control when assistant is speaking
        if (isSpeaking) {
            Button(
                onClick = { viewModel.stopSpeaking() },
                shape = Shapes8px.small,
                colors = ButtonDefaults.buttonColors(containerColor = WarningRed),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text("Stop Speech", fontWeight = FontWeight.SemiBold)
            }
        }

        // Conversational Feed
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageCard(message = msg)
            }

            // Interactive Planner Recommendation Proposal (Now or Later)
            if (activeProposal != null) {
                item {
                    val prop = activeProposal!!
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = Shapes8px,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, TealAccent)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Planner Recommendation",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = TealAccent
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = prop.rationale,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.acceptProposal(prop, isSpoken = false) },
                                    modifier = Modifier.weight(1f),
                                    shape = Shapes8px.small,
                                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                                ) {
                                    Text("Accept (${prop.suggestedTime ?: "Now"})", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.rejectProposal(isSpoken = false) },
                                    modifier = Modifier.weight(1f),
                                    shape = Shapes8px.small
                                ) {
                                    Text("Drop", fontSize = 12.sp, color = WarningRed)
                                }
                            }
                        }
                    }
                }
            }

            if (isProcessing) {
                item {
                    Text(
                        text = "Assistant is thinking...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Center Voice Microphone button with pulsing ring
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isListening) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(TealAccent.copy(alpha = 0.25f))
                )
            }
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(if (isListening) WarningRed else TealAccent)
                    .clickable {
                        if (isListening) stopRecording() else startRecording()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isListening) "STOP" else "MIC",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Fixed bottom universal text bar: "Tell me anything."
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { Text("Tell me anything...") },
                modifier = Modifier.weight(1f),
                shape = Shapes8px.small,
                singleLine = true
            )

            Button(
                onClick = {
                    val msg = textInput
                    textInput = ""
                    viewModel.handleUniversalInput(msg, isSpoken = false)
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
private fun NextUpCard(item: Item, onComplete: () -> Unit) {
    val dueStr = remember(item.dueAt) {
        if (item.dueAt != null) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.dueAt))
        } else "Next"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = Shapes8px,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "NEXT UP",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TealAccent
                    )
                    Text(
                        text = " · $dueStr",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Button(
                onClick = onComplete,
                shape = Shapes8px.small,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Done", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MessageCard(message: ChatMessage) {
    val isAssistant = message.sender == "Assistant"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = Shapes8px,
        colors = CardDefaults.cardColors(
            containerColor = if (isAssistant) MaterialTheme.colorScheme.surface
            else TealAccent.copy(alpha = 0.08f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isAssistant) MaterialTheme.colorScheme.outline else TealAccent.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = message.sender,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (isAssistant) TealAccent else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

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
                                fontSize = 11.sp,
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
