package com.xestlab.assistant.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xestlab.assistant.ai.PlannerProposal
import com.xestlab.assistant.data.model.ConversationTurn
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.ui.theme.BgDark
import com.xestlab.assistant.ui.theme.BorderDark
import com.xestlab.assistant.ui.theme.CardShape8
import com.xestlab.assistant.ui.theme.SurfaceDark
import com.xestlab.assistant.ui.theme.SurfaceSubtleDark
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.TealAccentContainer
import com.xestlab.assistant.ui.theme.TextMutedDark
import com.xestlab.assistant.ui.theme.TextPrimaryDark
import com.xestlab.assistant.ui.theme.WarningRed
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(viewModel: AssistantViewModel) {
    val context = LocalContext.current
    val prefs by viewModel.prefsState.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val dailyPlanSummary by viewModel.dailyPlanSummary.collectAsState()
    val pendingItems by viewModel.pendingItems.collectAsState()
    val checklistItems by viewModel.checklistItems.collectAsState()
    val todayLogs by viewModel.todayLogs.collectAsState()
    val activeProposal by viewModel.activeProposal.collectAsState()
    val conversationTurns by viewModel.conversationTurns.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var partialSpeech by remember { mutableStateOf("") }
    var voiceFeedbackError by remember { mutableStateOf<String?>(null) }

    // Trigger session greeting + TextToSpeech daily plan readout when Home opens
    LaunchedEffect(prefs.userName, prefs.isFirstLaunchComplete) {
        if (prefs.isFirstLaunchComplete && prefs.userName.isNotBlank()) {
            viewModel.onHomeScreenDisplayed()
        }
    }

    val latestHandleInput by rememberUpdatedState<(String) -> Unit> { recognized ->
        viewModel.handleUniversalInput(recognized, isSpoken = true)
    }

    // Native Android SpeechRecognizer managed strictly on Main UI thread
    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    DisposableEffect(speechRecognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                voiceFeedbackError = null
            }

            override fun onBeginningOfSpeech() {
                isListening = true
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                partialSpeech = ""
                voiceFeedbackError = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Tap MIC to try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech network unavailable. Check offline speech pack or connection."
                    else -> "Voice input stopped (code $error)."
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                partialSpeech = ""
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val topUtterance = matches?.firstOrNull()?.trim().orEmpty()
                if (topUtterance.isNotEmpty()) {
                    voiceFeedbackError = null
                    latestHandleInput(topUtterance)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                partialSpeech = partials?.firstOrNull().orEmpty()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        speechRecognizer?.setRecognitionListener(listener)

        onDispose {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    fun beginSpeechRecognition() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            voiceFeedbackError = "Android SpeechRecognizer service is not available on this device."
            return
        }
        // Stop ongoing TTS before opening mic so assistant doesn't transcribe its own voice
        viewModel.stopSpeaking()
        partialSpeech = ""
        voiceFeedbackError = null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            recognizer.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            voiceFeedbackError = "Unable to start SpeechRecognizer: ${e.message}"
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginSpeechRecognition()
        } else {
            voiceFeedbackError = "Microphone permission is required for SpeechRecognizer voice input."
        }
    }

    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greetingPrefix = when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
    val displayName = prefs.userName.ifBlank { "Friend" }
    val doneChecklistCount = remember(checklistItems, todayLogs) {
        val logMap = todayLogs.associateBy { it.itemId }
        checklistItems.count { logMap[it.id]?.isDone == true }
    }
    val nextUpItem = remember(pendingItems) {
        pendingItems.firstOrNull { !it.sourceText.startsWith("web_pipeline_") }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Personalized DataStore Name Greeting Header + Daily Plan TextToSpeech Controls
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelMedium,
                        color = TealAccent
                    )
                    Text(
                        text = "$greetingPrefix, $displayName",
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text(
                        text = "Core checklist: $doneChecklistCount of ${checklistItems.size} completed today",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMutedDark
                    )
                }
            }

            // 2. Daily Plan TextToSpeech Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape8,
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, if (isSpeaking) TealAccent else BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "DAILY PLAN BRIEFING",
                                style = MaterialTheme.typography.labelMedium,
                                color = TealAccent
                            )
                            if (isSpeaking) {
                                Text(
                                    text = "SPEAKING VIA TTS",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TealAccent
                                )
                            }
                        }

                        Text(
                            text = dailyPlanSummary.ifBlank {
                                "$greetingPrefix, $displayName. Tap 'Read Daily Plan' to hear today's priorities."
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { viewModel.readDailyPlanAloud() },
                                shape = CardShape8,
                                colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (isSpeaking) "Restart Plan Readout" else "Read Daily Plan",
                                    color = BgDark,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (isSpeaking) {
                                OutlinedButton(
                                    onClick = { viewModel.stopSpeaking() },
                                    shape = CardShape8,
                                    border = BorderStroke(1.dp, WarningRed)
                                ) {
                                    Text("Stop", color = WarningRed)
                                }
                            }
                        }
                    }
                }
            }

            // 3. SpeechRecognizer Voice Command Center
            item {
                VoiceRecognizerCard(
                    isListening = isListening,
                    partialTranscript = partialSpeech,
                    errorMessage = voiceFeedbackError,
                    onToggleListen = {
                        if (isListening) {
                            try {
                                speechRecognizer?.stopListening()
                            } catch (_: Exception) {
                            }
                            isListening = false
                        } else {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                beginSpeechRecognition()
                            } else {
                                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                )
            }

            // 4. Active PlannerEngine Proposal (Now / Later Today / Tomorrow / Drop)
            activeProposal?.let { proposal ->
                item {
                    ProposalDecisionCard(
                        proposal = proposal,
                        onAccept = { viewModel.acceptProposal(proposal) },
                        onDrop = { viewModel.rejectProposal(proposal) }
                    )
                }
            }

            // 5. Next Up Card
            if (nextUpItem != null) {
                item {
                    NextUpCard(
                        item = nextUpItem,
                        onMarkDone = { viewModel.markTaskComplete(nextUpItem) },
                        onOpenChat = { contact ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Hi $contact, ")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Message $contact"))
                        }
                    )
                }
            }

            // 6. Recent Assistant Conversation Feed
            if (conversationTurns.isNotEmpty()) {
                item {
                    Text(
                        text = "RECENT CONVERSATION",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMutedDark
                    )
                }
                items(conversationTurns.take(12).reversed(), key = { it.id }) { turn ->
                    ConversationTurnBubble(
                        turn = turn,
                        onSpeakAgain = { viewModel.speakText(turn.text) }
                    )
                }
            }
        }

        // Bottom Universal Text + Voice Input Bar
        Surface(
            color = SurfaceDark,
            border = BorderStroke(1.dp, BorderDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            "Tell me anything (tasks, reminders, notes)...",
                            color = TextMutedDark
                        )
                    },
                    singleLine = true,
                    shape = CardShape8,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank()) {
                                viewModel.handleUniversalInput(inputText, isSpoken = false)
                                inputText = ""
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        cursorColor = TealAccent
                    ),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.handleUniversalInput(inputText, isSpoken = false)
                            inputText = ""
                        }
                    },
                    shape = CardShape8,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("Send", color = BgDark, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun VoiceRecognizerCard(
    isListening: Boolean,
    partialTranscript: String,
    errorMessage: String?,
    onToggleListen: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) 1.12f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_scale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, if (isListening) WarningRed else BorderDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .scale(if (isListening) pulseScale else 1f)
                    .background(
                        color = if (isListening) WarningRed else TealAccentContainer,
                        shape = CircleShape
                    )
                    .border(
                        width = 2.dp,
                        color = if (isListening) WarningRed else TealAccent,
                        shape = CircleShape
                    )
                    .clickable { onToggleListen() }
            ) {
                Text(
                    text = if (isListening) "STOP" else "MIC",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isListening) TextPrimaryDark else TealAccent
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (isListening) "Listening via SpeechRecognizer..." else "Tap MIC for Voice Input",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = when {
                        isListening && partialTranscript.isNotBlank() -> "\"$partialTranscript\""
                        isListening -> "Say 'Read my daily plan', 'Remind me to text Chidi at 4pm', or add a task."
                        !errorMessage.isNullOrBlank() -> errorMessage
                        else -> "Hands-free capture for tasks, person reminders, notes, and plan readouts."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (!errorMessage.isNullOrBlank() && !isListening) WarningRed else TextMutedDark
                )
            }
        }
    }
}

@Composable
private fun ProposalDecisionCard(
    proposal: PlannerProposal,
    onAccept: () -> Unit,
    onDrop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = TealAccentContainer),
        border = BorderStroke(1.dp, TealAccent)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "PLANNER RECOMMENDATION: ${proposal.recommendation.name.replace('_', ' ')}",
                style = MaterialTheme.typography.labelMedium,
                color = TealAccent
            )
            Text(
                text = proposal.candidateItem.title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = proposal.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimaryDark
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onAccept,
                    shape = CardShape8,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                ) {
                    Text("Accept Slot", color = BgDark, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onDrop,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, WarningRed)
                ) {
                    Text("Drop", color = WarningRed)
                }
            }
        }
    }
}

@Composable
private fun NextUpCard(
    item: Item,
    onMarkDone: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    val dueText = item.dueAt?.let {
        SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(it))
    } ?: "Flexible today"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "NEXT UP (${item.type})",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
                Text(
                    text = dueText,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )
            }
            Text(text = item.title, style = MaterialTheme.typography.titleMedium)
            if (item.details.isNotBlank()) {
                Text(text = item.details, style = MaterialTheme.typography.bodyMedium, color = TextMutedDark)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onMarkDone,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent)
                ) {
                    Text("Mark Done", color = TealAccent)
                }
                if (!item.contactName.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = { onOpenChat(item.contactName) },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, BorderDark)
                    ) {
                        Text("Open Chat (${item.contactName})", color = TextPrimaryDark)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationTurnBubble(
    turn: ConversationTurn,
    onSpeakAgain: () -> Unit
) {
    val isUser = turn.sender.equals("USER", ignoreCase = true)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) SurfaceSubtleDark else SurfaceDark
        ),
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isUser) "YOU" else "ASSISTANT",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isUser) TextMutedDark else TealAccent
                )
                if (!isUser) {
                    Text(
                        text = "Speak",
                        style = MaterialTheme.typography.labelMedium,
                        color = TealAccent,
                        modifier = Modifier.clickable { onSpeakAgain() }
                    )
                }
            }
            Text(
                text = turn.text,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
