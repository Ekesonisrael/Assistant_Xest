package com.xestlab.assistant.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xestlab.assistant.ui.theme.BgDark
import com.xestlab.assistant.ui.theme.BorderDark
import com.xestlab.assistant.ui.theme.CardShape8
import com.xestlab.assistant.ui.theme.SurfaceDark
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.TextMutedDark
import com.xestlab.assistant.ui.theme.TextPrimaryDark
import com.xestlab.assistant.ui.theme.WarningRed
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: AssistantViewModel,
    onOpenSetup: () -> Unit
) {
    val context = LocalContext.current
    val prefs by viewModel.prefsState.collectAsState()
    val goals by viewModel.goals.collectAsState()
    val reminders by viewModel.reminders.collectAsState()
    val facts by viewModel.allFacts.collectAsState()

    var nameField by remember(prefs.userName) { mutableStateOf(prefs.userName) }
    var wakeField by remember(prefs.wakeUpTime) { mutableStateOf(prefs.wakeUpTime) }
    var quietStartField by remember(prefs.quietHoursStart) { mutableStateOf(prefs.quietHoursStart) }
    var quietEndField by remember(prefs.quietHoursEnd) { mutableStateOf(prefs.quietHoursEnd) }
    var quietEnabledField by remember(prefs.quietHoursEnabled) { mutableStateOf(prefs.quietHoursEnabled) }
    var voiceSpeedField by remember(prefs.voiceSpeed) { mutableFloatStateOf(prefs.voiceSpeed) }
    var autoReadField by remember(prefs.autoReadPlanOnLaunch) { mutableStateOf(prefs.autoReadPlanOnLaunch) }

    var yearlyGoalField by remember(goals.yearlyGoal) { mutableStateOf(goals.yearlyGoal.toString()) }
    var customBriefTitle by remember { mutableStateOf("") }
    var customBriefTime by remember { mutableStateOf("15:00") }

    var showMemoryDialog by remember { mutableStateOf(false) }

    // Optional IMAP fields
    var imapEnabled by remember { mutableStateOf(viewModel.securePrefs.isImapEnabled) }
    var imapHost by remember { mutableStateOf(viewModel.securePrefs.imapHost) }
    var imapPort by remember { mutableStateOf(viewModel.securePrefs.imapPort.toString()) }
    var imapUser by remember { mutableStateOf(viewModel.securePrefs.imapUser) }
    var imapPassword by remember { mutableStateOf("") }
    var imapWifiOnly by remember { mutableStateOf(viewModel.securePrefs.isImapWifiOnly) }

    // Optional Gemini fields
    var aiEnabled by remember { mutableStateOf(viewModel.securePrefs.isAiEnabled) }
    var geminiModel by remember { mutableStateOf(viewModel.securePrefs.geminiModel) }
    var geminiKeyInput by remember { mutableStateOf("") }

    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson
        if (uri != null && json != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                        writer.write(json)
                    }
                }
                viewModel.setStatusBanner("Exported backup JSON successfully.")
            } catch (e: Exception) {
                viewModel.setStatusBanner("Export error: ${e.message}")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                }
                if (!content.isNullOrBlank()) {
                    viewModel.importBackupJson(content)
                }
            } catch (e: Exception) {
                viewModel.setStatusBanner("Import error: ${e.message}")
            }
        }
    }

    if (showMemoryDialog) {
        MemoryDialog(
            viewModel = viewModel,
            onDismiss = { showMemoryDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium
            )
        }

        // 1. DataStore Profile, Greeting Name & TextToSpeech Voice Settings
        item {
            SettingsCard(title = "PROFILE & VOICE (DATASTORE)") {
                SettingsTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    label = "Your Name (DataStore)"
                )
                SettingsTextField(
                    value = wakeField,
                    onValueChange = { wakeField = it },
                    label = "Morning Wake-Up Briefing (HH:mm)"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsTextField(
                        value = quietStartField,
                        onValueChange = { quietStartField = it },
                        label = "Quiet Start",
                        modifier = Modifier.weight(1f)
                    )
                    SettingsTextField(
                        value = quietEndField,
                        onValueChange = { quietEndField = it },
                        label = "Quiet End",
                        modifier = Modifier.weight(1f)
                    )
                }

                SettingsSwitchRow(
                    label = "Enforce Quiet Hours",
                    checked = quietEnabledField,
                    onCheckedChange = { quietEnabledField = it }
                )

                SettingsSwitchRow(
                    label = "Auto-Read Daily Plan on App Launch",
                    checked = autoReadField,
                    onCheckedChange = { autoReadField = it }
                )

                Text(
                    text = "TextToSpeech Speed: ${String.format(Locale.US, "%.1fx", voiceSpeedField)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )
                Slider(
                    value = voiceSpeedField,
                    onValueChange = { voiceSpeedField = it },
                    valueRange = 0.6f..1.8f,
                    colors = SliderDefaults.colors(
                        thumbColor = TealAccent,
                        activeTrackColor = TealAccent
                    )
                )

                Button(
                    onClick = {
                        viewModel.updateProfilePreferences(
                            userName = nameField,
                            wakeUpTime = wakeField,
                            quietStart = quietStartField,
                            quietEnd = quietEndField,
                            quietEnabled = quietEnabledField,
                            voiceSpeed = voiceSpeedField,
                            autoReadOnLaunch = autoReadField
                        )
                    },
                    shape = CardShape8,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Profile to DataStore", color = BgDark, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // 2. Scheduled Daily Briefings
        item {
            SettingsCard(title = "PROACTIVE DAILY BRIEFINGS") {
                reminders.forEach { schedule ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(schedule.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${schedule.timeString} (${schedule.daysOfWeek})",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMutedDark
                            )
                        }
                        Switch(
                            checked = schedule.isActive,
                            onCheckedChange = { active -> viewModel.toggleReminderSchedule(schedule, active) },
                            colors = SwitchDefaults.colors(checkedThumbColor = BgDark, checkedTrackColor = TealAccent)
                        )
                        if (!schedule.isBuiltIn) {
                            OutlinedButton(
                                onClick = { viewModel.deleteReminderSchedule(schedule) },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, BorderDark),
                                modifier = Modifier.padding(start = 6.dp)
                            ) {
                                Text("Del", color = WarningRed)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsTextField(
                        value = customBriefTitle,
                        onValueChange = { customBriefTitle = it },
                        label = "Custom Briefing",
                        modifier = Modifier.weight(1f)
                    )
                    SettingsTextField(
                        value = customBriefTime,
                        onValueChange = { customBriefTime = it },
                        label = "HH:mm",
                        modifier = Modifier.weight(0.55f)
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (customBriefTitle.isNotBlank()) {
                            viewModel.addCustomReminderSchedule(customBriefTitle, customBriefTime)
                            customBriefTitle = ""
                        }
                    },
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Daily Briefing", color = TealAccent)
                }
            }
        }

        // 3. Memory Facts, Backup/Import & Setup Diagnostics
        item {
            SettingsCard(title = "MEMORY, BACKUP & RELIABILITY") {
                OutlinedButton(
                    onClick = { showMemoryDialog = true },
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage Assistant Memory (${facts.size} facts)", color = TealAccent)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.exportBackupJson { json ->
                                pendingExportJson = json
                                exportLauncher.launch("xestlab-assistant-backup.json")
                            }
                        },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export JSON", color = TextPrimaryDark)
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import JSON", color = TextPrimaryDark)
                    }
                }

                SettingsTextField(
                    value = yearlyGoalField,
                    onValueChange = { yearlyGoalField = it },
                    label = "Annual Goal Target (NGN)"
                )
                OutlinedButton(
                    onClick = {
                        val parsed = yearlyGoalField.toLongOrNull()
                        if (parsed != null && parsed > 0) {
                            viewModel.updateGoals(goals.copy(yearlyGoal = parsed))
                        }
                    },
                    shape = CardShape8,
                    border = BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Update Annual Goal", color = TextPrimaryDark)
                }

                OutlinedButton(
                    onClick = onOpenSetup,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Permissions & 60s Alarm Test", color = TealAccent)
                }
            }
        }

        // 4. Optional Read-Only IMAP Email Checks
        item {
            SettingsCard(title = "OPTIONAL: READ-ONLY IMAP EMAIL CHECK") {
                SettingsSwitchRow(
                    label = "Enable Background Unread Checks",
                    checked = imapEnabled,
                    onCheckedChange = { imapEnabled = it }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsTextField(
                        value = imapHost,
                        onValueChange = { imapHost = it },
                        label = "IMAP Host",
                        modifier = Modifier.weight(1f)
                    )
                    SettingsTextField(
                        value = imapPort,
                        onValueChange = { imapPort = it },
                        label = "Port",
                        modifier = Modifier.weight(0.45f)
                    )
                }
                SettingsTextField(
                    value = imapUser,
                    onValueChange = { imapUser = it },
                    label = "Email Address"
                )
                OutlinedTextField(
                    value = imapPassword,
                    onValueChange = { imapPassword = it },
                    label = { Text("App Password (stored locally only)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = CardShape8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                SettingsSwitchRow(
                    label = "Wi-Fi Only",
                    checked = imapWifiOnly,
                    onCheckedChange = { imapWifiOnly = it }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.testImapConnection(
                                host = imapHost,
                                port = imapPort.toIntOrNull() ?: 993,
                                user = imapUser,
                                password = imapPassword
                            )
                        },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Test IMAP", color = TextPrimaryDark)
                    }
                    Button(
                        onClick = {
                            viewModel.saveImapConfig(
                                enabled = imapEnabled,
                                host = imapHost,
                                port = imapPort.toIntOrNull() ?: 993,
                                user = imapUser,
                                password = imapPassword,
                                wifiOnly = imapWifiOnly
                            )
                            imapPassword = ""
                        },
                        shape = CardShape8,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save IMAP", color = BgDark, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 5. Optional Cloud Reasoning (Strict Offline Fallback)
        item {
            SettingsCard(title = "OPTIONAL: CLOUD REASONING (OFFLINE BY DEFAULT)") {
                SettingsSwitchRow(
                    label = "Enable Optional Gemini Q&A",
                    checked = aiEnabled,
                    onCheckedChange = { aiEnabled = it }
                )
                SettingsTextField(
                    value = geminiModel,
                    onValueChange = { geminiModel = it },
                    label = "Model Name"
                )
                OutlinedTextField(
                    value = geminiKeyInput,
                    onValueChange = { geminiKeyInput = it },
                    label = {
                        Text(
                            if (viewModel.securePrefs.hasGeminiKey()) "Replace API Key (saved)" else "API Key (optional)"
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = CardShape8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.testGeminiConnection(geminiKeyInput, geminiModel) },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Test Key", color = TextPrimaryDark)
                    }
                    Button(
                        onClick = {
                            viewModel.saveGeminiConfig(aiEnabled, geminiKeyInput, geminiModel)
                            geminiKeyInput = ""
                        },
                        shape = CardShape8,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save", color = BgDark, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TealAccent
            )
            content()
        }
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = CardShape8,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TealAccent,
            unfocusedBorderColor = BorderDark,
            focusedTextColor = TextPrimaryDark,
            unfocusedTextColor = TextPrimaryDark,
            cursorColor = TealAccent,
            focusedLabelColor = TealAccent,
            unfocusedLabelColor = TextMutedDark
        ),
        modifier = modifier
    )
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = BgDark, checkedTrackColor = TealAccent)
        )
    }
}
