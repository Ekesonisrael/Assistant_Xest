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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xestlab.assistant.ai.PlannerProposal
import com.xestlab.assistant.data.model.ConversationTurn
import com.xestlab.assistant.data.model.CustomEmailTagEntity
import com.xestlab.assistant.data.model.EmailSenderRuleEntity
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.model.Lead
import com.xestlab.assistant.data.model.UnreadEmailEntity
import com.xestlab.assistant.data.model.tagsList
import com.xestlab.assistant.service.EmailPriorityEngine
import com.xestlab.assistant.service.LeadCadenceEngine
import com.xestlab.assistant.service.TtsGreetingHelper
import com.xestlab.assistant.service.WakeWordService
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
import com.xestlab.assistant.ui.viewmodel.PendingConfirmation
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(viewModel: AssistantViewModel) {
    val context = LocalContext.current
    val prefs by viewModel.prefsState.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val aiEnabled by viewModel.aiEnabledState.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val dailyPlanSummary by viewModel.dailyPlanSummary.collectAsState()
    val pendingItems by viewModel.pendingItems.collectAsState()
    val checklistItems by viewModel.checklistItems.collectAsState()
    val todayLogs by viewModel.todayLogs.collectAsState()
    val activeProposal by viewModel.activeProposal.collectAsState()
    val conversationTurns by viewModel.conversationTurns.collectAsState()
    val unreadEmails by viewModel.unreadEmails.collectAsState()
    val senderRules by viewModel.senderRules.collectAsState()
    val customEmailTags by viewModel.customEmailTags.collectAsState()
    val dueFollowUpsToday by viewModel.dueFollowUpsToday.collectAsState()
    val revenueGauge by viewModel.revenueGauge.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var partialSpeech by remember { mutableStateOf("") }
    var voiceFeedbackError by remember { mutableStateOf<String?>(null) }
    var sentMessageCounter by remember { mutableIntStateOf(0) }
    var lowPriorityExpanded by rememberSaveable { mutableStateOf(false) }
    var taggingEmailId by rememberSaveable { mutableStateOf<String?>(null) }
    var leadFromEmailId by rememberSaveable { mutableStateOf<String?>(null) }
    var observationPromptTarget by remember { mutableStateOf<Pair<Lead, String?>?>(null) }

    val listState = rememberLazyListState()
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    val isKeyboardOpen = imeBottomPx > 0
    val latestTurnId = conversationTurns.firstOrNull()?.id ?: -1L

    LaunchedEffect(prefs.userName, prefs.isFirstLaunchComplete) {
        if (prefs.isFirstLaunchComplete && prefs.userName.isNotBlank()) {
            viewModel.onHomeScreenDisplayed()
        }
    }

    var settledTurnBaseline by remember { mutableStateOf<Long?>(null) }
    var lastHandledSendCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300L)
        if (settledTurnBaseline == null) {
            settledTurnBaseline = latestTurnId
        }
    }

    LaunchedEffect(isKeyboardOpen, latestTurnId, sentMessageCounter) {
        val didJustSend = sentMessageCounter > lastHandledSendCount
        if (didJustSend) {
            lastHandledSendCount = sentMessageCounter
        }

        val currentBaseline = settledTurnBaseline
        val hasNewTurn = if (currentBaseline == null) {
            if (latestTurnId > 0L) {
                settledTurnBaseline = latestTurnId
            }
            false
        } else if (latestTurnId > currentBaseline && latestTurnId > 0L) {
            settledTurnBaseline = latestTurnId
            true
        } else {
            false
        }

        if (conversationTurns.isNotEmpty() && (isKeyboardOpen || didJustSend || hasNewTurn)) {
            kotlinx.coroutines.delay(60L)
            val hasVisibleEmails = unreadEmails.any { !it.dismissedInApp }
            val hasNextUpItem = pendingItems.any {
                !it.sourceText.startsWith("web_pipeline_") &&
                    !it.sourceText.startsWith("lead_followup:")
            }
            val conversationHeaderIndex = 3 +
                (if (pendingConfirmation != null) 1 else 0) +
                1 +
                (if (hasVisibleEmails) 1 else 0) +
                (if (activeProposal != null) 1 else 0) +
                (if (dueFollowUpsToday.isNotEmpty()) (1 + dueFollowUpsToday.size) else 0) +
                (if (hasNextUpItem) 1 else 0)
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                listState.animateScrollToItem(conversationHeaderIndex.coerceIn(0, totalItems - 1))
            }
            if (isKeyboardOpen) {
                kotlinx.coroutines.delay(160L)
                val settledTotal = listState.layoutInfo.totalItemsCount
                if (settledTotal > 0) {
                    listState.animateScrollToItem(conversationHeaderIndex.coerceIn(0, settledTotal - 1))
                }
            }
        }
    }

    val latestHandleInput by rememberUpdatedState<(String) -> Unit> { recognized ->
        viewModel.handleUniversalInput(recognized, isSpoken = true)
    }

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
                WakeWordService.resumeTemporarily(context)
                voiceFeedbackError = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Tap the microphone to try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech network unavailable. Check connection or offline speech pack."
                    else -> "Voice capture stopped (code $error)."
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                partialSpeech = ""
                WakeWordService.resumeTemporarily(context)
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
            WakeWordService.resumeTemporarily(context)
        }
    }

    fun beginSpeechRecognition() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            voiceFeedbackError = "SpeechRecognizer service is not available on this device."
            return
        }
        WakeWordService.pauseTemporarily(context)
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
            WakeWordService.resumeTemporarily(context)
            voiceFeedbackError = "Unable to start SpeechRecognizer: ${e.message}"
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginSpeechRecognition()
        } else {
            voiceFeedbackError = "Microphone permission is required for voice input."
        }
    }

    val greetingPrefix = TtsGreetingHelper.getGreetingWord()
    val displayName = prefs.userName.ifBlank { "King Israel" }
    val doneChecklistCount = remember(checklistItems, todayLogs) {
        val logMap = todayLogs.associateBy { it.itemId }
        checklistItems.count { logMap[it.id]?.isDone == true }
    }
    val nextUpItem = remember(pendingItems) {
        pendingItems.firstOrNull {
            !it.sourceText.startsWith("web_pipeline_") &&
                !it.sourceText.startsWith("lead_followup:")
        }
    }
    val visibleEmails = remember(unreadEmails) {
        unreadEmails.filter { !it.dismissedInApp }
    }
    val highEmails = remember(visibleEmails) {
        visibleEmails.filter { it.priority == "HIGH" }.sortedByDescending { it.receivedAt }
    }
    val normalEmails = remember(visibleEmails) {
        visibleEmails.filter { it.priority == "NORMAL" }.sortedByDescending { it.receivedAt }
    }
    val lowEmails = remember(visibleEmails) {
        visibleEmails.filter { it.priority == "LOW" }.sortedByDescending { it.receivedAt }
    }
    val senderRuleMap = remember(senderRules) {
        senderRules.associateBy { it.senderKey }
    }

    val activeTagEmail = remember(taggingEmailId, unreadEmails) {
        taggingEmailId?.let { targetId -> unreadEmails.firstOrNull { it.id == targetId } }
    }
    if (activeTagEmail != null) {
        EmailTagDialog(
            email = activeTagEmail,
            customTags = customEmailTags,
            onToggleTag = { tag -> viewModel.toggleEmailTag(activeTagEmail, tag) },
            onCreateCustomTag = { customTag -> viewModel.createAndApplyCustomTag(activeTagEmail, customTag) },
            onDismiss = { taggingEmailId = null }
        )
    }

    val activeLeadEmail = remember(leadFromEmailId, unreadEmails) {
        leadFromEmailId?.let { targetId -> unreadEmails.firstOrNull { it.id == targetId } }
    }
    if (activeLeadEmail != null) {
        AddLeadFromEmailDialog(
            email = activeLeadEmail,
            onSave = { name, service, phone, observation ->
                viewModel.createLeadFromEmail(
                    email = activeLeadEmail,
                    name = name,
                    service = service,
                    phone = phone,
                    observation = observation
                )
                leadFromEmailId = null
            },
            onDismiss = { leadFromEmailId = null }
        )
    }

    if (observationPromptTarget != null) {
        val (targetLead, targetStep) = observationPromptTarget!!
        ObservationPromptDialog(
            leadName = targetLead.name,
            initialObservation = targetLead.observation,
            onConfirmAndOpenWhatsApp = { enteredObs ->
                observationPromptTarget = null
                viewModel.openLeadWhatsAppWithDraft(
                    activityContext = context,
                    lead = targetLead,
                    stepKey = targetStep,
                    updatedObservation = enteredObs
                )
            },
            onDismiss = { observationPromptTarget = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Greeting Header
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault())
                                .format(Date())
                                .uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelMedium,
                            color = TealAccent
                        )
                        Text(
                            text = if (aiEnabled) "BRAIN: ${activeProvider.name}" else "OFFLINE MODE",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (aiEnabled) TealAccent else TextMutedDark
                        )
                    }
                    Text(
                        text = "$greetingPrefix, $displayName",
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text(
                        text = "Daily targets: $doneChecklistCount of ${checklistItems.size} complete | Wake alarm: ${prefs.wakeUpTime}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMutedDark
                    )
                }
            }

            // 1b. 10M Revenue Gauge Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape8,
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "10 MILLION GAUGE",
                            style = MaterialTheme.typography.labelMedium,
                            color = TealAccent
                        )
                        Text(
                            text = revenueGauge.homeGaugeLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimaryDark
                        )
                        LinearProgressIndicator(
                            progress = { (revenueGauge.percentOfGoal.toFloat() / 100f).coerceIn(0f, 1f) },
                            color = TealAccent,
                            trackColor = SurfaceSubtleDark,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 2. Centered Microphone Hero Section
            item {
                CenteredMicrophoneHero(
                    isListening = isListening,
                    isThinking = isThinking,
                    isSpeaking = isSpeaking,
                    partialTranscript = partialSpeech,
                    errorMessage = voiceFeedbackError,
                    onToggleListen = {
                        if (isListening) {
                            try {
                                speechRecognizer?.stopListening()
                            } catch (_: Exception) {
                            }
                            isListening = false
                            WakeWordService.resumeTemporarily(context)
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
                    },
                    onStopSpeech = { viewModel.stopSpeaking() }
                )
            }

            // 2b. Voice / Chat Action Confirmation Card (Leads & Payments)
            pendingConfirmation?.let { pending ->
                item {
                    PendingActionConfirmationCard(
                        pending = pending,
                        onConfirm = { viewModel.confirmPendingAction(alsoMarkLeadWon = false) },
                        onConfirmAndMarkWon = { viewModel.confirmPendingAction(alsoMarkLeadWon = true) },
                        onCancel = { viewModel.cancelPendingConfirmation() }
                    )
                }
            }

            // 3. Daily Plan Briefing Card
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
                                    text = "SPEAKING",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TealAccent
                                )
                            }
                        }

                        Text(
                            text = dailyPlanSummary.ifBlank {
                                "Tap Read Daily Plan to hear today's priorities."
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
                                    color = TextPrimaryDark,
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

            // 4. Ranked Unread Emails Card (High -> Normal -> Collapsed "Low priority (N)")
            if (visibleEmails.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CardShape8,
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(
                            1.dp,
                            if (highEmails.isNotEmpty()) TealAccent else BorderDark
                        )
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
                                    text = "UNREAD EMAILS (${visibleEmails.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TealAccent
                                )
                                Text(
                                    text = "${highEmails.size} High · ${normalEmails.size} Normal · ${lowEmails.size} Low",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextMutedDark
                                )
                            }

                            if (highEmails.isNotEmpty()) {
                                Text(
                                    text = "HIGH PRIORITY (${highEmails.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TealAccent,
                                    fontWeight = FontWeight.SemiBold
                                )
                                highEmails.forEach { email ->
                                    val sKey = EmailPriorityEngine.normalizeSenderKey(email.senderAddress, email.sender)
                                    val isPrioritySender = senderRuleMap[sKey]?.priorityOverride == "HIGH"
                                    RankedUnreadEmailCard(
                                        email = email,
                                        isPrioritySender = isPrioritySender,
                                        onAddToTasks = { viewModel.promoteEmailToTask(email) },
                                        onAddLead = { leadFromEmailId = email.id },
                                        onOpenTagPicker = { taggingEmailId = email.id },
                                        onTogglePrioritySender = { viewModel.togglePrioritySender(email) },
                                        onDismissInApp = { viewModel.dismissEmailInAppOnly(email) },
                                        onSchedule30MinReminder = { viewModel.scheduleEmail30MinReminder(email) }
                                    )
                                }
                            }

                            if (normalEmails.isNotEmpty()) {
                                Text(
                                    text = "NORMAL (${normalEmails.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimaryDark,
                                    fontWeight = FontWeight.SemiBold
                                )
                                normalEmails.forEach { email ->
                                    val sKey = EmailPriorityEngine.normalizeSenderKey(email.senderAddress, email.sender)
                                    val isPrioritySender = senderRuleMap[sKey]?.priorityOverride == "HIGH"
                                    RankedUnreadEmailCard(
                                        email = email,
                                        isPrioritySender = isPrioritySender,
                                        onAddToTasks = { viewModel.promoteEmailToTask(email) },
                                        onAddLead = { leadFromEmailId = email.id },
                                        onOpenTagPicker = { taggingEmailId = email.id },
                                        onTogglePrioritySender = { viewModel.togglePrioritySender(email) },
                                        onDismissInApp = { viewModel.dismissEmailInAppOnly(email) },
                                        onSchedule30MinReminder = { viewModel.scheduleEmail30MinReminder(email) }
                                    )
                                }
                            }

                            if (lowEmails.isNotEmpty()) {
                                Surface(
                                    color = SurfaceSubtleDark,
                                    shape = CardShape8,
                                    border = BorderStroke(1.dp, BorderDark),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { lowPriorityExpanded = !lowPriorityExpanded }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Low priority (${lowEmails.size})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextMutedDark
                                        )
                                        Text(
                                            text = if (lowPriorityExpanded) "Collapse ▲" else "Expand ▼",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TealAccent
                                        )
                                    }
                                }

                                if (lowPriorityExpanded) {
                                    lowEmails.forEach { email ->
                                        val sKey = EmailPriorityEngine.normalizeSenderKey(email.senderAddress, email.sender)
                                        val isPrioritySender = senderRuleMap[sKey]?.priorityOverride == "HIGH"
                                        RankedUnreadEmailCard(
                                            email = email,
                                            isPrioritySender = isPrioritySender,
                                            onAddToTasks = { viewModel.promoteEmailToTask(email) },
                                            onAddLead = { leadFromEmailId = email.id },
                                            onOpenTagPicker = { taggingEmailId = email.id },
                                            onTogglePrioritySender = { viewModel.togglePrioritySender(email) },
                                            onDismissInApp = { viewModel.dismissEmailInAppOnly(email) },
                                            onSchedule30MinReminder = { viewModel.scheduleEmail30MinReminder(email) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Planner Recommendation Card
            activeProposal?.let { proposal ->
                item {
                    ProposalDecisionCard(
                        proposal = proposal,
                        onAccept = { viewModel.acceptProposal(proposal) },
                        onDrop = { viewModel.rejectProposal(proposal) }
                    )
                }
            }

            // 6. Next Up Section (Lead Follow-Ups Due Today by Name + Scheduled Task/Reminder)
            if (dueFollowUpsToday.isNotEmpty()) {
                item {
                    Text(
                        text = "NEXT UP — FOLLOW-UPS DUE TODAY (${dueFollowUpsToday.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = TealAccent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(dueFollowUpsToday, key = { "home_due_${it.lead.id}_${it.stepKey}" }) { due ->
                    DueFollowUpActionCard(
                        due = due,
                        onOpenWhatsApp = {
                            if (due.stepKey == LeadCadenceEngine.STEP_DAY_0_MESSAGE &&
                                due.lead.observation.isBlank()
                            ) {
                                observationPromptTarget = due.lead to due.stepKey
                            } else {
                                viewModel.openLeadWhatsAppWithDraft(
                                    activityContext = context,
                                    lead = due.lead,
                                    stepKey = due.stepKey
                                )
                            }
                        },
                        onCall = { viewModel.callLead(context, due.lead) },
                        onMarkDone = { viewModel.completeDueFollowUp(due) },
                        onSnooze = { viewModel.snoozeDueFollowUp(due) },
                        onReplied = { viewModel.markLeadReplied(due.lead.id) },
                        onLost = { viewModel.markLeadLost(due.lead.id) }
                    )
                }
            }

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

            // 7. Coach Conversation Feed (Newest First)
            if (conversationTurns.isNotEmpty()) {
                item(key = "coach_conversation_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "COACH CONVERSATION",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMutedDark
                        )
                        Text(
                            text = "Clear History",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMutedDark,
                            modifier = Modifier.clickable { viewModel.clearConversationHistory() }
                        )
                    }
                }
                items(conversationTurns.take(24), key = { it.id }) { turn ->
                    ConversationTurnBubble(
                        turn = turn,
                        onSpeakAgain = { viewModel.speakText(turn.text) }
                    )
                }
            }
        }

        // Bottom Universal Chat Bar — sits directly above the keyboard via Modifier.imePadding()
        Surface(
            color = SurfaceDark,
            border = BorderStroke(1.dp, BorderDark),
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
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
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
                    placeholder = {
                        Text(
                            "Ask your coach, check email, or 'Note: ...'",
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
                                sentMessageCounter++
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        focusedContainerColor = SurfaceSubtleDark,
                        unfocusedContainerColor = SurfaceSubtleDark,
                        cursorColor = TealAccent
                    ),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.handleUniversalInput(inputText, isSpoken = false)
                            inputText = ""
                            sentMessageCounter++
                        }
                    },
                    shape = CardShape8,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("Send", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CenteredMicrophoneHero(
    isListening: Boolean,
    isThinking: Boolean,
    isSpeaking: Boolean,
    partialTranscript: String,
    errorMessage: String?,
    onToggleListen: () -> Unit,
    onStopSpeech: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) 1.15f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_scale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                isListening -> WarningRed
                isThinking -> TealAccent
                else -> BorderDark
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 22.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(88.dp)
                    .scale(if (isListening) pulseScale else 1f)
                    .background(
                        color = if (isListening) WarningRed.copy(alpha = 0.2f) else TealAccentContainer,
                        shape = CircleShape
                    )
                    .border(
                        width = 2.dp,
                        color = if (isListening) WarningRed else TealAccent,
                        shape = CircleShape
                    )
                    .clickable { onToggleListen() }
            ) {
                MicrophoneVectorIcon(
                    tint = if (isListening) WarningRed else TealAccent,
                    modifier = Modifier.size(36.dp)
                )
            }

            Text(
                text = when {
                    isListening -> "Listening... Tap Mic to Stop"
                    isThinking -> "Coach Brain Thinking..."
                    isSpeaking -> "Speaking Reply..."
                    else -> "Tap Microphone to Speak"
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            Text(
                text = when {
                    isListening && partialTranscript.isNotBlank() -> "\"$partialTranscript\""
                    isListening -> "Ask anything, say 'What did I get in my email today?', or 'Note: ...'"
                    !errorMessage.isNullOrBlank() -> errorMessage
                    else -> "Talk to your coach, check today's emails, or schedule reminders."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (!errorMessage.isNullOrBlank() && !isListening) WarningRed else TextMutedDark,
                textAlign = TextAlign.Center
            )

            if (isSpeaking) {
                OutlinedButton(
                    onClick = onStopSpeech,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, WarningRed)
                ) {
                    Text("Stop Speaking", color = WarningRed)
                }
            }
        }
    }
}

@Composable
private fun MicrophoneVectorIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.085f

        // Mic capsule
        val capsuleWidth = w * 0.34f
        val capsuleHeight = h * 0.48f
        val capsuleLeft = (w - capsuleWidth) / 2f
        val capsuleTop = h * 0.08f
        drawRoundRect(
            color = tint,
            topLeft = Offset(capsuleLeft, capsuleTop),
            size = Size(capsuleWidth, capsuleHeight),
            cornerRadius = CornerRadius(capsuleWidth / 2f, capsuleWidth / 2f),
            style = Stroke(width = strokeWidth)
        )

        // U-shaped cradle arc
        val arcWidth = w * 0.62f
        val arcHeight = h * 0.48f
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset((w - arcWidth) / 2f, h * 0.26f),
            size = Size(arcWidth, arcHeight),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Vertical stem
        drawLine(
            color = tint,
            start = Offset(w / 2f, h * 0.74f),
            end = Offset(w / 2f, h * 0.90f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Base bar
        drawLine(
            color = tint,
            start = Offset(w * 0.32f, h * 0.90f),
            end = Offset(w * 0.68f, h * 0.90f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RankedUnreadEmailCard(
    email: UnreadEmailEntity,
    isPrioritySender: Boolean,
    onAddToTasks: () -> Unit,
    onAddLead: () -> Unit,
    onOpenTagPicker: () -> Unit,
    onTogglePrioritySender: () -> Unit,
    onDismissInApp: () -> Unit,
    onSchedule30MinReminder: () -> Unit
) {
    val priorityBadgeColor = when (email.priority) {
        "HIGH" -> TealAccent
        "LOW" -> TextMutedDark
        else -> TextPrimaryDark
    }
    val receivedLabel = remember(email.receivedAt) {
        SimpleDateFormat("d MMM h:mm a", Locale.getDefault()).format(Date(email.receivedAt))
    }
    val tags = email.tagsList
    val hasInviteOrDateTime = email.isCalendarInvite || email.eventTimeMillis != null
    val formattedEventTime = remember(email.eventTimeMillis) {
        email.eventTimeMillis?.let {
            SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault()).format(Date(it))
        }
    }

    Surface(
        color = SurfaceSubtleDark,
        shape = CardShape8,
        border = BorderStroke(
            width = 1.dp,
            color = if (email.priority == "HIGH") TealAccent.copy(alpha = 0.7f) else BorderDark
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = if (email.priority == "HIGH") TealAccentContainer else BgDark,
                        shape = CardShape8,
                        border = BorderStroke(1.dp, priorityBadgeColor)
                    ) {
                        Text(
                            text = email.priority,
                            style = MaterialTheme.typography.labelMedium,
                            color = priorityBadgeColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = email.sender,
                        style = MaterialTheme.typography.labelMedium,
                        color = TealAccent,
                        maxLines = 1
                    )
                }
                Text(
                    text = receivedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )
            }

            Text(
                text = email.subject,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimaryDark
            )

            if (email.snippet.isNotBlank()) {
                Text(
                    text = email.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark,
                    maxLines = 2
                )
            }

            // Short single-line "Why this priority"
            Text(
                text = "Why this priority: ${email.priorityReason.ifBlank { "Standard unread message" }}",
                style = MaterialTheme.typography.labelMedium,
                color = if (email.priority == "HIGH") TealAccent else TextMutedDark,
                maxLines = 1
            )

            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tags.forEach { tag ->
                        Surface(
                            color = TealAccentContainer,
                            shape = CardShape8,
                            border = BorderStroke(1.dp, TealAccent)
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelMedium,
                                color = TealAccent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            // Offer "Remind me 30 minutes before" when email contains a date+time or calendar invite
            if (hasInviteOrDateTime) {
                val buttonText = when {
                    email.reminderCreated -> "Reminder set (30m before${formattedEventTime?.let { " $it" }.orEmpty()}) ✓"
                    formattedEventTime != null -> "Remind me 30 minutes before ($formattedEventTime)"
                    else -> "Remind me 30 minutes before (Calendar invite)"
                }
                OutlinedButton(
                    onClick = onSchedule30MinReminder,
                    enabled = !email.reminderCreated,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = buttonText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (email.reminderCreated) TextMutedDark else TealAccent
                    )
                }
            }

            // 4 Actions on every email (including Low): Add to Tasks, Tag, Priority sender, Dismiss
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAddToTasks,
                    enabled = !email.addedToTasks,
                    shape = CardShape8,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (email.addedToTasks) "Added to Tasks ✓" else "Add to Tasks",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (email.addedToTasks) TextMutedDark else TextPrimaryDark,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onOpenTagPicker,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(0.8f)
                ) {
                    Text(
                        text = if (tags.isEmpty()) "Tag" else "Tag (${tags.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = TealAccent
                    )
                }

                OutlinedButton(
                    onClick = onAddLead,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(0.9f)
                ) {
                    Text(
                        text = "Add Lead",
                        style = MaterialTheme.typography.labelMedium,
                        color = TealAccent
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onTogglePrioritySender,
                    shape = CardShape8,
                    border = BorderStroke(
                        1.dp,
                        if (isPrioritySender) TealAccent else BorderDark
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isPrioritySender) "Priority sender ✓" else "Priority sender",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isPrioritySender) TealAccent else TextPrimaryDark
                    )
                }

                OutlinedButton(
                    onClick = onDismissInApp,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, BorderDark),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Dismiss",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMutedDark
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingActionConfirmationCard(
    pending: PendingConfirmation,
    onConfirm: () -> Unit,
    onConfirmAndMarkWon: () -> Unit,
    onCancel: () -> Unit
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
                text = "CONFIRM VOICE / CHAT ACTION",
                style = MaterialTheme.typography.labelMedium,
                color = TealAccent,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = pending.promptText,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimaryDark
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onConfirm,
                    shape = CardShape8,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                ) {
                    Text("Confirm", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
                }
                if (pending is PendingConfirmation.LogPayment && pending.canMarkWon) {
                    Button(
                        onClick = onConfirmAndMarkWon,
                        shape = CardShape8,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                    ) {
                        Text(
                            "Confirm & Mark ${pending.matchedLeadName ?: "Lead"} Won",
                            color = TextPrimaryDark,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                OutlinedButton(
                    onClick = onCancel,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, WarningRed)
                ) {
                    Text("Cancel", color = WarningRed)
                }
            }
        }
    }
}

@Composable
private fun AddLeadFromEmailDialog(
    email: UnreadEmailEntity,
    onSave: (name: String, service: String, phone: String, observation: String) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultName = remember(email.sender) {
        email.sender.substringBefore("<").trim().trim('"').ifBlank { email.sender }
    }
    var name by remember(defaultName) { mutableStateOf(defaultName) }
    var service by remember { mutableStateOf(LeadCadenceEngine.SERVICE_WEBSITE) }
    var phone by remember { mutableStateOf("") }
    var observation by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text("Add Lead from Email", color = TextPrimaryDark)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Only Name and Service are required.",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                    label = { Text("Lead Name *") },
                    singleLine = true,
                    shape = CardShape8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceSubtleDark,
                        unfocusedContainerColor = SurfaceSubtleDark,
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        cursorColor = TealAccent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LeadCadenceEngine.ALL_SERVICES.forEach { svc ->
                        val selected = service == svc
                        if (selected) {
                            Button(
                                onClick = { service = svc },
                                shape = CardShape8,
                                colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(svc, color = TextPrimaryDark, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { service = svc },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, BorderDark),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(svc, color = TextPrimaryDark, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                    label = { Text("Phone (optional)") },
                    singleLine = true,
                    shape = CardShape8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceSubtleDark,
                        unfocusedContainerColor = SurfaceSubtleDark,
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        cursorColor = TealAccent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = observation,
                    onValueChange = { observation = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                    label = { Text("Observation (optional)") },
                    singleLine = false,
                    maxLines = 2,
                    shape = CardShape8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceSubtleDark,
                        unfocusedContainerColor = SurfaceSubtleDark,
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        cursorColor = TealAccent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.trim().isNotEmpty()) {
                        onSave(name.trim(), service, phone.trim(), observation.trim())
                    }
                },
                enabled = name.trim().isNotEmpty(),
                shape = CardShape8,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
            ) {
                Text("Add Lead", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMutedDark)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmailTagDialog(
    email: UnreadEmailEntity,
    customTags: List<CustomEmailTagEntity>,
    onToggleTag: (String) -> Unit,
    onCreateCustomTag: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var customTagDraft by remember { mutableStateOf("") }
    val activeTags = email.tagsList
    val allAvailableTags = remember(customTags) {
        val builtIn = EmailPriorityEngine.BUILT_IN_TAGS
        val extra = customTags.map { it.tagName }.filter { custom ->
            builtIn.none { it.equals(custom, ignoreCase = true) }
        }
        builtIn + extra
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text(
                text = "Tag Email",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimaryDark
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = email.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark,
                    maxLines = 2
                )
                Text(
                    text = "Tap to toggle tags (tagging raises sender priority & remembers keywords; 'Low' marks email Low):",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allAvailableTags.forEach { tag ->
                        val isSelected = activeTags.any { it.equals(tag, ignoreCase = true) }
                        if (isSelected) {
                            Button(
                                onClick = { onToggleTag(tag) },
                                shape = CardShape8,
                                colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "$tag ✓",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimaryDark,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onToggleTag(tag) },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, BorderDark),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimaryDark
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customTagDraft,
                        onValueChange = { customTagDraft = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                        placeholder = { Text("Create custom tag...", color = TextMutedDark) },
                        singleLine = true,
                        shape = CardShape8,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TealAccent,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextPrimaryDark,
                            unfocusedTextColor = TextPrimaryDark,
                            focusedContainerColor = SurfaceSubtleDark,
                            unfocusedContainerColor = SurfaceSubtleDark,
                            cursorColor = TealAccent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val trimmed = customTagDraft.trim()
                            if (trimmed.isNotEmpty()) {
                                onCreateCustomTag(trimmed)
                                customTagDraft = ""
                            }
                        },
                        shape = CardShape8,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                    ) {
                        Text("Add", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = TealAccent)
            }
        }
    )
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
                    Text("Accept Slot", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
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
        SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(it))
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
                    text = if (isUser) "YOU" else "COACH",
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
