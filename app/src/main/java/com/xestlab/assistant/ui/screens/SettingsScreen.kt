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
import com.xestlab.assistant.data.LlmProvider
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
    val activeProvider by viewModel.activeProvider.collectAsState()
    val aiEnabledState by viewModel.aiEnabledState.collectAsState()

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

    // Dual LLM Encrypted Credentials (Gemini & Groq)
    var selectedProvider by remember(activeProvider) { mutableStateOf(activeProvider) }
    var aiEnabled by remember(aiEnabledState) { mutableStateOf(aiEnabledState) }
    var geminiModel by remember { mutableStateOf(viewModel.securePrefs.geminiModel) }
    var geminiKeyInput by remember { mutableStateOf("") }
    var groqModel by remember { mutableStateOf(viewModel.securePrefs.groqModel) }
    var groqKeyInput by remember { mutableStateOf("") }

    // ElevenLabs Encrypted Voice Key
    var elevenLabsKeyInput by remember { mutableStateOf("") }
    var hasElevenSaved by remember { mutableStateOf(viewModel.securePrefs.hasElevenLabsKey()) }

    // Smart IMAP Encrypted Credentials
    var imapEnabled by remember { mutableStateOf(viewModel.securePrefs.isImapEnabled) }
    var imapHost by remember { mutableStateOf(viewModel.securePrefs.imapHost) }
    var imapPort by remember { mutableStateOf(viewModel.securePrefs.imapPort.toString()) }
    var imapUser by remember { mutableStateOf(viewModel.securePrefs.imapUser) }
    var imapPassword by remember { mutableStateOf("") }
    var imapWifiOnly by remember { mutableStateOf(viewModel.securePrefs.isImapWifiOnly) }

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
            Text(
                text = "All API keys and IMAP passwords are encrypted with AES-256 EncryptedSharedPreferences.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMutedDark
            )
        }

        // 1. Dual LLM Routing (Gemini & Groq)
        item {
            SettingsCard(title = "DUAL AI BRAIN (GEMINI & GROQ - ENCRYPTED)") {
                SettingsSwitchRow(
                    label = "Route Chat & Voice to AI Coach",
                    checked = aiEnabled,
                    onCheckedChange = {
                        aiEnabled = it
                        viewModel.setAiRoutingEnabled(it)
                    }
                )

                Text(
                    text = "Active LLM Provider",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isGemini = selectedProvider == LlmProvider.GEMINI
                    if (isGemini) {
                        Button(
                            onClick = {
                                selectedProvider = LlmProvider.GEMINI
                                viewModel.setLlmProvider(LlmProvider.GEMINI)
                            },
                            shape = CardShape8,
                            colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Gemini", color = BgDark, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                selectedProvider = LlmProvider.GEMINI
                                viewModel.setLlmProvider(LlmProvider.GEMINI)
                            },
                            shape = CardShape8,
                            border = BorderStroke(1.dp, BorderDark),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Gemini", color = TextPrimaryDark)
                        }
                    }

                    val isGroq = selectedProvider == LlmProvider.GROQ
                    if (isGroq) {
                        Button(
                            onClick = {
                                selectedProvider = LlmProvider.GROQ
                                viewModel.setLlmProvider(LlmProvider.GROQ)
                            },
                            shape = CardShape8,
                            colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Groq (Llama 3 70B)", color = BgDark, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                selectedProvider = LlmProvider.GROQ
                                viewModel.setLlmProvider(LlmProvider.GROQ)
                            },
                            shape = CardShape8,
                            border = BorderStroke(1.dp, BorderDark),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Groq (Llama 3 70B)", color = TextPrimaryDark)
                        }
                    }
                }

                if (selectedProvider == LlmProvider.GEMINI) {
                    SettingsTextField(
                        value = geminiModel,
                        onValueChange = { geminiModel = it },
                        label = "Gemini Model"
                    )
                    SecretKeyTextField(
                        value = geminiKeyInput,
                        onValueChange = { geminiKeyInput = it },
                        label = if (viewModel.securePrefs.hasGeminiKey()) {
                            "Gemini API Key (Encrypted & Saved - enter new to replace)"
                        } else {
                            "Gemini API Key"
                        }
                    )
                } else {
                    SettingsTextField(
                        value = groqModel,
                        onValueChange = { groqModel = it },
                        label = "Groq Model (default: llama3-70b-8192)"
                    )
                    SecretKeyTextField(
                        value = groqKeyInput,
                        onValueChange = { groqKeyInput = it },
                        label = if (viewModel.securePrefs.hasGroqKey()) {
                            "Groq API Key (Encrypted & Saved - enter new to replace)"
                        } else {
                            "Groq API Key (Bearer)"
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.testActiveLlmConnection(
                                provider = selectedProvider,
                                geminiKeyDraft = geminiKeyInput,
                                geminiModelDraft = geminiModel,
                                groqKeyDraft = groqKeyInput,
                                groqModelDraft = groqModel
                            )
                        },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, TealAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Test ${selectedProvider.name}", color = TealAccent)
                    }

                    Button(
                        onClick = {
                            viewModel.saveLlmSettings(
                                aiEnabled = aiEnabled,
                                provider = selectedProvider,
                                geminiKeyInput = geminiKeyInput,
                                geminiModel = geminiModel,
                                groqKeyInput = groqKeyInput,
                                groqModel = groqModel
                            )
                            geminiKeyInput = ""
                            groqKeyInput = ""
                            aiEnabled = viewModel.securePrefs.isAiEnabled
                        },
                        shape = CardShape8,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Encrypted", color = BgDark, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 2. Proper Voice: ElevenLabs + Natural Android TTS Fallback (0.9x, Pitch 1.0)
        item {
            SettingsCard(title = "VOICE ENGINE (ELEVENLABS + 0.9X NATURAL FALLBACK)") {
                Text(
                    text = if (hasElevenSaved) {
                        "ElevenLabs active (Voice ID: 21m00Tcm4TlvDq8ikWAM). Falls back to Android TTS (0.9x) if offline."
                    } else {
                        "Using Android TTS natural fallback (Speech rate 0.9x, Pitch 1.0). Add an ElevenLabs key below for studio neural voice."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )

                SecretKeyTextField(
                    value = elevenLabsKeyInput,
                    onValueChange = { elevenLabsKeyInput = it },
                    label = if (hasElevenSaved) {
                        "ElevenLabs API Key (Encrypted & Saved - enter new to replace)"
                    } else {
                        "ElevenLabs API Key (Optional)"
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.testVoicePlayback(elevenLabsKeyInput)
                            if (elevenLabsKeyInput.isNotBlank()) {
                                hasElevenSaved = true
                                elevenLabsKeyInput = ""
                            }
                        },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, TealAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Test Voice", color = TealAccent)
                    }

                    Button(
                        onClick = {
                            if (elevenLabsKeyInput.isNotBlank()) {
                                viewModel.saveElevenLabsKey(elevenLabsKeyInput)
                                hasElevenSaved = true
                                elevenLabsKeyInput = ""
                            }
                        },
                        shape = CardShape8,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Voice Key", color = BgDark, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (hasElevenSaved) {
                    OutlinedButton(
                        onClick = {
                            viewModel.saveElevenLabsKey("")
                            hasElevenSaved = false
                        },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear ElevenLabs Key (Use Android TTS 0.9x)", color = WarningRed)
                    }
                }

                Text(
                    text = "Fallback Android TTS Speed: ${String.format(Locale.US, "%.2fx", voiceSpeedField)} (Default 0.90x, Pitch 1.0)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )
                Slider(
                    value = voiceSpeedField,
                    onValueChange = { voiceSpeedField = it },
                    valueRange = 0.6f..1.4f,
                    colors = SliderDefaults.colors(
                        thumbColor = TealAccent,
                        activeTrackColor = TealAccent
                    )
                )
            }
        }

        // 3. Smart IMAP Email Checker (Every 30 Minutes -> Task/Invoice)
        item {
            SettingsCard(title = "SMART EMAIL CHECKER (IMAP TO TASK - 30M)") {
                SettingsSwitchRow(
                    label = "Check Unread Emails Every 30 Minutes",
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
                SecretKeyTextField(
                    value = imapPassword,
                    onValueChange = { imapPassword = it },
                    label = if (viewModel.securePrefs.imapPassword.isNotBlank()) {
                        "IMAP App Password (Encrypted & Saved - enter new to replace)"
                    } else {
                        "IMAP App Password"
                    }
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
                            viewModel.syncEmailsNow(
                                hostDraft = imapHost,
                                portDraft = imapPort.toIntOrNull() ?: 993,
                                userDraft = imapUser,
                                passDraft = imapPassword
                            )
                            imapPassword = ""
                        },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, TealAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Sync Unread Now", color = TealAccent)
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

        // 4. Profile & 7:30 AM Wake-Up Alarm (DataStore)
        item {
            SettingsCard(title = "PROFILE & 7:30 AM WAKE-UP ALARM (DATASTORE)") {
                SettingsTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    label = "Your Name"
                )
                SettingsTextField(
                    value = wakeField,
                    onValueChange = { wakeField = it },
                    label = "Morning Wake-Up Alarm (HH:mm, default 07:30)"
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
                    Text("Save Profile & Wake-Up Alarm", color = BgDark, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // 5. Scheduled Daily Briefings
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

        // 6. Memory, JSON Pipeline Import & Diagnostics
        item {
            SettingsCard(title = "MEMORY, JSON PIPELINE IMPORT & BACKUP") {
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
                        Text("Import Pipeline JSON", color = TextPrimaryDark)
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
                    Text("Permissions & 60s Wake-Up Alarm Test", color = TealAccent)
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
private fun SecretKeyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
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
        modifier = Modifier.fillMaxWidth()
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
