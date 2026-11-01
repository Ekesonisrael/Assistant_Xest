package com.xestlab.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.xestlab.assistant.data.model.ChecklistItem
import com.xestlab.assistant.data.model.DailyLog
import com.xestlab.assistant.service.SpeechCommand
import com.xestlab.assistant.service.SpeechHelper
import com.xestlab.assistant.ui.theme.Shapes8px
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.WarningRed
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel

@Composable
fun CheckInScreen(viewModel: AssistantViewModel) {
    val context = LocalContext.current
    val items by viewModel.checklistItems.collectAsState()
    val logs by viewModel.todayLogs.collectAsState()
    val logsMap = remember(logs) { logs.associateBy { it.itemId } }

    var activeMicItem by remember { mutableStateOf<ChecklistItem?>(null) }
    var isListening by remember { mutableStateOf(false) }
    var micFeedback by remember { mutableStateOf<String?>(null) }

    val speechHelper = remember {
        SpeechHelper(
            context = context,
            onResult = { command ->
                isListening = false
                val target = activeMicItem
                if (target != null) {
                    when (command) {
                        is SpeechCommand.Done -> {
                            viewModel.markItemDone(target.id, if (target.isCountType) 10 else 0)
                            micFeedback = "Recognized: Done"
                        }
                        is SpeechCommand.NotDone -> {
                            viewModel.markItemNotDone(target.id, "other")
                            micFeedback = "Recognized: Not done"
                        }
                        is SpeechCommand.Count -> {
                            viewModel.markItemDone(target.id, command.value)
                            micFeedback = "Logged: ${command.value} count"
                        }
                        is SpeechCommand.Unrecognized -> {
                            micFeedback = "Heard: \"${command.raw}\". Say Done, Not done, or a number."
                        }
                    }
                }
            },
            onError = { err ->
                isListening = false
                micFeedback = err
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isListening = true
            micFeedback = "Listening... say Done, Not done, or number"
            speechHelper.startListening()
        } else {
            viewModel.showToast("Microphone permission needed for voice input.")
        }
    }

    val doneCount = items.count { logsMap[it.id]?.isDone == true }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Daily Execution Check-in",
                        fontSize = 18.dp.value.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$doneCount of ${items.size} items completed today",
                        fontSize = 13.dp.value.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (items.isNotEmpty()) doneCount.toFloat() / items.size else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(Shapes8px.small),
                        color = TealAccent,
                        trackColor = MaterialTheme.colorScheme.primaryContainer
                    )

                    if (micFeedback != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = micFeedback ?: "",
                            fontSize = 12.dp.value.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TealAccent
                        )
                    }
                }
            }
        }

        items(items) { item ->
            val log = logsMap[item.id]
            CheckInItemRow(
                item = item,
                log = log,
                isListeningThisItem = isListening && activeMicItem?.id == item.id,
                onDone = { count -> viewModel.markItemDone(item.id, count) },
                onNotDone = { reason -> viewModel.markItemNotDone(item.id, reason) },
                onMicClick = {
                    activeMicItem = item
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        isListening = true
                        micFeedback = "Listening for '${item.title}'..."
                        speechHelper.startListening()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
        }
    }
}

@Composable
fun CheckInItemRow(
    item: ChecklistItem,
    log: DailyLog?,
    isListeningThisItem: Boolean,
    onDone: (Int) -> Unit,
    onNotDone: (String) -> Unit,
    onMicClick: () -> Unit
) {
    var showReasonSheet by remember { mutableStateOf(false) }
    var countInput by remember(log) { mutableStateOf((log?.countValue ?: if (item.isCountType) 10 else 0).toString()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = Shapes8px,
        colors = CardDefaults.cardColors(
            containerColor = if (log?.isDone == true) MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
            else MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (log?.isDone == true) TealAccent else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = item.title,
                    fontSize = 14.dp.value.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (log?.isDone == true) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onMicClick,
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (isListeningThisItem) WarningRed else MaterialTheme.colorScheme.primaryContainer,
                            Shapes8px.small
                        )
                ) {
                    Text(
                        text = if (isListeningThisItem) "REC" else "MIC",
                        fontSize = 10.dp.value.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isListeningThisItem) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (item.isCountType) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Messages sent: ",
                        fontSize = 13.dp.value.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    OutlinedTextField(
                        value = countInput,
                        onValueChange = { countInput = it },
                        modifier = Modifier
                            .width(80.dp)
                            .height(48.dp),
                        shape = Shapes8px.small,
                        singleLine = true
                    )
                }
            }

            if (log?.isDone == false && log.reasonNotDone != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Reason: ${log.reasonNotDone}",
                    fontSize = 12.dp.value.sp,
                    color = WarningRed,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action buttons (8px radius, not pill shaped)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val count = countInput.toIntOrNull() ?: if (item.isCountType) 10 else 0
                        onDone(count)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = Shapes8px.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (log?.isDone == true) TealAccent else TealAccent.copy(alpha = 0.85f),
                        contentColor = Color.White
                    )
                ) {
                    Text(text = if (log?.isDone == true) "Done" else "Done", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { showReasonSheet = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = Shapes8px.medium,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (log?.isDone == false) WarningRed else MaterialTheme.colorScheme.onSurface
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (log?.isDone == false) WarningRed else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Text(text = "Not done", fontWeight = FontWeight.SemiBold)
                }
            }

            // Reason selector popup
            if (showReasonSheet) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer, Shapes8px.small)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Why not done? (Tap one reason)",
                        fontSize = 12.dp.value.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val reasons = listOf("busy", "forgot", "avoided it", "other")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        reasons.forEach { r ->
                            Button(
                                onClick = {
                                    showReasonSheet = false
                                    onNotDone(r)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                shape = Shapes8px.small,
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text(text = r, fontSize = 11.dp.value.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
