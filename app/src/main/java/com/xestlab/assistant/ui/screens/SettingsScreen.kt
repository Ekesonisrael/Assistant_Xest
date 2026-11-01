package com.xestlab.assistant.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xestlab.assistant.data.model.ReminderSchedule
import com.xestlab.assistant.ui.theme.Shapes8px
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.WarningRed
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: AssistantViewModel,
    onNavigateToSetup: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsState()
    val reminders by viewModel.reminders.collectAsState()
    val secure = viewModel.securePreferences

    var nameInput by remember(settings.userName) { mutableStateOf(settings.userName) }
    var wakeUpInput by remember(settings.wakeUpTime) { mutableStateOf(settings.wakeUpTime) }
    var quietStart by remember(settings.quietHoursStart) { mutableStateOf(settings.quietHoursStart) }
    var quietEnd by remember(settings.quietHoursEnd) { mutableStateOf(settings.quietHoursEnd) }
    var quietEnabled by remember(settings.quietHoursEnabled) { mutableStateOf(settings.quietHoursEnabled) }
    var voiceSpeed by remember(settings.voiceSpeed) { mutableFloatStateOf(settings.voiceSpeed) }

    // AI settings
    var aiEnabled by remember { mutableStateOf(secure.isAiEnabled) }
    var geminiKeyInput by remember { mutableStateOf(secure.geminiApiKey) }
    var geminiModelInput by remember { mutableStateOf(secure.geminiModel) }
    var aiTestResult by remember { mutableStateOf<String?>(null) }
    var isTestingAi by remember { mutableStateOf(false) }

    // Fish Audio settings
    var fishAudioEnabled by remember { mutableStateOf(secure.isFishAudioEnabled) }
    var fishKeyInput by remember { mutableStateOf(secure.fishAudioApiKey) }
    var fishVoiceInput by remember { mutableStateOf(secure.fishAudioVoiceId) }

    // IMAP Email settings
    var imapEnabled by remember { mutableStateOf(secure.isImapEnabled) }
    var imapHostInput by remember { mutableStateOf(secure.imapHost) }
    var imapPortInput by remember { mutableStateOf(secure.imapPort.toString()) }
    var imapUserInput by remember { mutableStateOf(secure.imapUser) }
    var imapPassInput by remember { mutableStateOf(secure.imapPassword) }
    var imapWifiOnly by remember { mutableStateOf(secure.isImapWifiOnly) }
    var imapTestResult by remember { mutableStateOf<String?>(null) }
    var isTestingImap by remember { mutableStateOf(false) }

    // Dialog states
    var showMemoryDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showAddReminderDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Wake-up Alarm & Proactive Routine
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Wake-up Alarm & Briefing",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Uses AlarmManager.setAlarmClock. Sounds an alarm, speaks the day's plan, and repeats until dismissed or snoozed.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = wakeUpInput,
                            onValueChange = { wakeUpInput = it },
                            label = { Text("Wake time (HH:mm)") },
                            modifier = Modifier.weight(1f),
                            shape = Shapes8px.small,
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                viewModel.saveUserSettings(settings.copy(wakeUpTime = wakeUpInput))
                                viewModel.updateWakeUpTime(wakeUpInput)
                            },
                            shape = Shapes8px.small,
                            colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                        ) {
                            Text("Set")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.testWakeUpInOneMinute() },
                        shape = Shapes8px.small,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Test wake-up in 1 minute")
                    }
                }
            }
        }

        // 2. Data Safety, Memory & Backup
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Data Safety, Memory & Backup",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Keys and credentials are stored strictly in app-private storage and never exported. Back up regularly every Sunday.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val json = viewModel.repository.exportJson()
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, json)
                                        type = "text/plain"
                                    }
                                    val shareIntent = Intent.createChooser(sendIntent, "Back up assistant data to...")
                                    context.startActivity(shareIntent)
                                }
                            },
                            shape = Shapes8px.small,
                            colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Back up now")
                        }

                        Button(
                            onClick = { showImportDialog = true },
                            shape = Shapes8px.small,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Restore / Import")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showMemoryDialog = true },
                            shape = Shapes8px.small,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Manage Memory (Facts)")
                        }

                        OutlinedButton(
                            onClick = { viewModel.clearConversation() },
                            shape = Shapes8px.small,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Clear Chat History", color = WarningRed, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // 3. AI Layer (Gemini Coach)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "AI Layer (Gemini)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Optional online coach. When disabled or offline, all alarms and check-ins fall back to local scripted voice reminders.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Enable Gemini AI", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = aiEnabled,
                            onCheckedChange = {
                                aiEnabled = it
                                viewModel.saveAiSettings(it, geminiKeyInput, geminiModelInput)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = geminiKeyInput,
                        onValueChange = { geminiKeyInput = it },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("Paste key here") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = Shapes8px.small,
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = geminiModelInput,
                        onValueChange = { geminiModelInput = it },
                        label = { Text("Model Name") },
                        placeholder = { Text("gemini-2.5-flash") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = Shapes8px.small,
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.saveAiSettings(aiEnabled, geminiKeyInput, geminiModelInput)
                            },
                            shape = Shapes8px.small,
                            colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save AI Settings")
                        }

                        OutlinedButton(
                            onClick = {
                                isTestingAi = true
                                aiTestResult = "Testing connection..."
                                viewModel.saveAiSettings(aiEnabled, geminiKeyInput, geminiModelInput)
                                viewModel.testAiConnection { success, msg ->
                                    isTestingAi = false
                                    aiTestResult = msg
                                }
                            },
                            enabled = !isTestingAi && geminiKeyInput.isNotBlank(),
                            shape = Shapes8px.small,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isTestingAi) "Testing..." else "Test AI")
                        }
                    }

                    if (aiTestResult != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = aiTestResult!!,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (aiTestResult!!.contains("success", ignoreCase = true)) TealAccent else WarningRed
                        )
                    }
                }
            }
        }

        // 4. Fish Audio Neural TTS (Optional)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Neural Voice (Fish Audio)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("Optional high-fidelity cloud voice. Falls back to Android TTS on failure.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                        Switch(
                            checked = fishAudioEnabled,
                            onCheckedChange = {
                                fishAudioEnabled = it
                                secure.isFishAudioEnabled = it
                                secure.fishAudioApiKey = fishKeyInput
                                secure.fishAudioVoiceId = fishVoiceInput
                                viewModel.showToast("Voice settings updated")
                            }
                        )
                    }

                    if (fishAudioEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = fishKeyInput,
                            onValueChange = {
                                fishKeyInput = it
                                secure.fishAudioApiKey = it
                            },
                            label = { Text("Fish Audio API Key") },
                            shape = Shapes8px.small,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = fishVoiceInput,
                            onValueChange = {
                                fishVoiceInput = it
                                secure.fishAudioVoiceId = it
                            },
                            label = { Text("Voice ID (reference_id)") },
                            shape = Shapes8px.small,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 5. Read-Only IMAP Email Check (Optional)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Email Check (Read-Only IMAP)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("WorkManager 30-min background check. Alerts priority senders. Silent during quiet hours.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                        Switch(
                            checked = imapEnabled,
                            onCheckedChange = {
                                imapEnabled = it
                                secure.isImapEnabled = it
                                if (it) {
                                    com.xestlab.assistant.service.EmailHelper.scheduleWork(context, imapWifiOnly)
                                } else {
                                    com.xestlab.assistant.service.EmailHelper.cancelWork(context)
                                }
                                viewModel.showToast("Email check toggled ${if (it) "on" else "off"}")
                            }
                        )
                    }

                    if (imapEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = imapHostInput,
                                onValueChange = { imapHostInput = it; secure.imapHost = it },
                                label = { Text("IMAP Host") },
                                shape = Shapes8px.small,
                                modifier = Modifier.weight(2f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = imapPortInput,
                                onValueChange = { imapPortInput = it; secure.imapPort = it.toIntOrNull() ?: 993 },
                                label = { Text("Port") },
                                shape = Shapes8px.small,
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = imapUserInput,
                            onValueChange = { imapUserInput = it; secure.imapUser = it },
                            label = { Text("Email Address") },
                            shape = Shapes8px.small,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = imapPassInput,
                            onValueChange = { imapPassInput = it; secure.imapPassword = it },
                            label = { Text("App Password") },
                            shape = Shapes8px.small,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Wi-Fi Only (saves mobile data)", fontSize = 12.sp)
                            Switch(
                                checked = imapWifiOnly,
                                onCheckedChange = {
                                    imapWifiOnly = it
                                    secure.isImapWifiOnly = it
                                    com.xestlab.assistant.service.EmailHelper.scheduleWork(context, it)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                isTestingImap = true
                                imapTestResult = "Connecting to IMAP..."
                                viewModel.testImapConnection { success, msg ->
                                    isTestingImap = false
                                    imapTestResult = msg
                                }
                            },
                            enabled = !isTestingImap && imapUserInput.isNotBlank() && imapPassInput.isNotBlank(),
                            shape = Shapes8px.small,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isTestingImap) "Testing..." else "Test IMAP Connection")
                        }

                        if (imapTestResult != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = imapTestResult!!,
                                fontSize = 11.sp,
                                color = if (imapTestResult!!.contains("success", ignoreCase = true)) TealAccent else WarningRed
                            )
                        }
                    }
                }
            }
        }

        // 6. Scheduled Accountability Briefings
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Scheduled Briefings",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Button(
                            onClick = { showAddReminderDialog = true },
                            shape = Shapes8px.small,
                            colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("+ Add")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    reminders.forEach { reminder ->
                        ReminderRow(
                            reminder = reminder,
                            onToggleActive = { viewModel.toggleReminderActive(reminder) },
                            onToggleVoice = { viewModel.toggleReminderVoice(reminder) },
                            onUpdateTime = { newTime -> viewModel.updateReminderTime(reminder, newTime) },
                            onDelete = { viewModel.deleteCustomReminder(reminder) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        // 7. Profile & Quiet Hours
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Profile & Quiet Hours", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Your Name (spoken by assistant)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = Shapes8px.small,
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Quiet Hours Active", fontSize = 13.sp)
                        Switch(
                            checked = quietEnabled,
                            onCheckedChange = {
                                quietEnabled = it
                                viewModel.saveUserSettings(settings.copy(quietHoursEnabled = it, userName = nameInput))
                            }
                        )
                    }

                    if (quietEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = quietStart,
                                onValueChange = { quietStart = it },
                                label = { Text("Start (HH:mm)") },
                                modifier = Modifier.weight(1f),
                                shape = Shapes8px.small,
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = quietEnd,
                                onValueChange = { quietEnd = it },
                                label = { Text("End (HH:mm)") },
                                modifier = Modifier.weight(1f),
                                shape = Shapes8px.small,
                                singleLine = true
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            viewModel.saveUserSettings(
                                settings.copy(
                                    userName = nameInput,
                                    quietHoursStart = quietStart,
                                    quietHoursEnd = quietEnd,
                                    quietHoursEnabled = quietEnabled
                                )
                            )
                        },
                        shape = Shapes8px.small,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Profile & Hours")
                    }
                }
            }
        }

        // 8. Reopen Setup & Autostart
        item {
            Button(
                onClick = onNavigateToSetup,
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px.medium,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
            ) {
                Text("Reopen Setup, Permissions & Autostart Guide")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Memory Dialog
    if (showMemoryDialog) {
        MemoryDialog(
            viewModel = viewModel,
            onDismiss = { showMemoryDialog = false }
        )
    }

    // Import / Restore Dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Restore / Import JSON", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Paste your exported JSON (from web goal tracker index.html or assistant backup). Data is merged idempotently without duplicates:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        shape = Shapes8px.small,
                        placeholder = { Text("{\n  \"settings\": ...,\n  \"pipeline\": ...\n}") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val ok = viewModel.repository.importJson(importText)
                            if (ok) {
                                viewModel.showToast("Data imported successfully")
                                viewModel.refreshWeeklySummary()
                                showImportDialog = false
                            } else {
                                viewModel.showToast("Failed to parse JSON")
                            }
                        }
                    },
                    shape = Shapes8px.small,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showImportDialog = false },
                    shape = Shapes8px.small
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add Custom Reminder Dialog
    if (showAddReminderDialog) {
        var newTitle by remember { mutableStateOf("") }
        var newTime by remember { mutableStateOf("15:00") }
        var newRepeating by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showAddReminderDialog = false },
            title = { Text("Add Custom Briefing", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Title") },
                        shape = Shapes8px.small,
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newTime,
                        onValueChange = { newTime = it },
                        label = { Text("Time (HH:mm)") },
                        shape = Shapes8px.small,
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Repeat daily", fontSize = 13.sp)
                        Switch(checked = newRepeating, onCheckedChange = { newRepeating = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTitle.isNotBlank() && newTime.isNotBlank()) {
                            viewModel.addCustomReminder(newTitle, newTime, newRepeating)
                            showAddReminderDialog = false
                        }
                    },
                    shape = Shapes8px.small,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showAddReminderDialog = false },
                    shape = Shapes8px.small
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
