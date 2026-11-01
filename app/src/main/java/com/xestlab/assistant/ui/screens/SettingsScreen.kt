package com.xestlab.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xestlab.assistant.data.LlmProvider
import com.xestlab.assistant.ui.theme.BgDark
import com.xestlab.assistant.ui.theme.BorderDark
import com.xestlab.assistant.ui.theme.CardShape8
import com.xestlab.assistant.ui.theme.SurfaceDark
import com.xestlab.assistant.ui.theme.SurfaceSubtleDark
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
    val aiEnabledState by viewModel.aiEnabledState.collectAsState()
    // Gemini temporarily disabled: val savedGeminiSummary by viewModel.savedGeminiSummary.collectAsState()
    val savedGroqSummary by viewModel.savedGroqSummary.collectAsState()
    val llmDiagnosticText by viewModel.llmTestDiagnosticText.collectAsState()
    val llmDiagnosticIsError by viewModel.llmTestIsError.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()

    val wakeWordEnabledState by viewModel.wakeWordEnabledState.collectAsState()
    val wakeWordStatusText by viewModel.wakeWordStatusText.collectAsState()
    val wakeWordStatusIsError by viewModel.wakeWordStatusIsError.collectAsState()

    val savedElevenLabsSummary by viewModel.savedElevenLabsSummary.collectAsState()
    val voiceStatusText by viewModel.voiceStatusText.collectAsState()
    val voiceStatusIsError by viewModel.voiceStatusIsError.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshSavedKeySummaries()
    }

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

    // openWakeWord ONNX ("Hey Jarvis") Toggle State
    var wakeWordEnabled by remember(wakeWordEnabledState) { mutableStateOf(wakeWordEnabledState) }

    // Primary Groq Encrypted Credentials (model hardcoded to "openai/gpt-oss-120b" in AiClient)
    var selectedProvider by remember { mutableStateOf(LlmProvider.GROQ) }
    var aiEnabled by remember(aiEnabledState) { mutableStateOf(aiEnabledState) }
    // Gemini temporarily disabled: var geminiKeyInput by remember { mutableStateOf("") }
    var groqKeyInput by remember { mutableStateOf("") }

    // ElevenLabs Encrypted Voice Key
    var elevenLabsKeyInput by remember { mutableStateOf("") }
    val hasElevenSaved = savedElevenLabsSummary != "None saved"

    // Smart IMAP Encrypted Credentials
    var imapEnabled by remember { mutableStateOf(viewModel.securePrefs.isImapEnabled) }
    var imapHost by remember { mutableStateOf(viewModel.securePrefs.imapHost) }
    var imapPort by remember { mutableStateOf(viewModel.securePrefs.imapPort.toString()) }
    var imapUser by remember { mutableStateOf(viewModel.securePrefs.imapUser) }
    var imapPassword by remember { mutableStateOf("") }
    var imapWifiOnly by remember { mutableStateOf(viewModel.securePrefs.isImapWifiOnly) }

    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    val wakePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            wakeWordEnabled = true
            viewModel.setWakeWordEnabled(true)
        } else {
            wakeWordEnabled = false
            Toast.makeText(
                context,
                "RECORD_AUDIO permission is required for 'Hey Jarvis' wake word listening.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

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

        // 0. openWakeWord ONNX ("Hey Jarvis") Background Listener
        item {
            SettingsCard(title = "WAKE WORD — 'HEY JARVIS' (OPENWAKEWORD ONNX PIPELINE)") {
                SettingsSwitchRow(
                    label = "Listen for 'Hey Jarvis' in Background",
                    checked = wakeWordEnabled,
                    onCheckedChange = { wantEnabled ->
                        if (wantEnabled) {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                            } else {
                                true
                            }

                            if (hasMic && hasNotif) {
                                wakeWordEnabled = true
                                viewModel.setWakeWordEnabled(true)
                            } else {
                                val perms = buildList {
                                    add(Manifest.permission.RECORD_AUDIO)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                wakePermissionLauncher.launch(perms.toTypedArray())
                            }
                        } else {
                            wakeWordEnabled = false
                            viewModel.setWakeWordEnabled(false)
                        }
                    }
                )

                Text(
                    text = "100% On-Device 3-Model ONNX Pipeline: melspectrogram.onnx -> embedding_model.onnx -> hey_jarvis_v0.1.onnx (triggers when score > 0.20).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )

                OutlinedButton(
                    onClick = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasMic) {
                            val perms = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            wakePermissionLauncher.launch(perms.toTypedArray())
                        } else {
                            viewModel.testJarvisLoopFromSettings()
                        }
                    },
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test 'Hey Jarvis' Conversation Loop", color = TealAccent)
                }

                Surface(
                    color = SurfaceSubtleDark,
                    shape = CardShape8,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (wakeWordStatusIsError) WarningRed else BorderDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val errorColorArgb = WarningRed.toArgb()
                    val okColorArgb = TealAccent.toArgb()
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        factory = { ctx ->
                            TextView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                                typeface = Typeface.MONOSPACE
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                                setTextIsSelectable(true)
                            }
                        },
                        update = { textView ->
                            textView.text = wakeWordStatusText
                            textView.setTextColor(
                                if (wakeWordStatusIsError) errorColorArgb else okColorArgb
                            )
                        }
                    )
                }
            }
        }

        // 1. Primary Groq AI Brain (Gemini Temporarily Disabled)
        item {
            SettingsCard(title = "AI BRAIN — GROQ PRIMARY (OPENAI/GPT-OSS-120B)") {
                SettingsSwitchRow(
                    label = "Route Chat & Voice to Groq AI Coach",
                    checked = aiEnabled,
                    onCheckedChange = {
                        aiEnabled = it
                        viewModel.setAiRoutingEnabled(it)
                    }
                )

                Text(
                    text = "Active LLM Provider (Default: Groq — openai/gpt-oss-120b)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            selectedProvider = LlmProvider.GROQ
                            viewModel.setLlmProvider(LlmProvider.GROQ)
                        },
                        shape = CardShape8,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Groq (Active)", color = BgDark, fontWeight = FontWeight.SemiBold)
                    }

                    // Gemini kept in UI as disabled until cloud permission issues are resolved
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        shape = CardShape8,
                        border = BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Gemini (Disabled)", color = TextMutedDark)
                    }
                }

                /*
                 * Gemini API Key field commented out while Groq is the primary engine:
                 * SecretKeyTextField(
                 *     value = geminiKeyInput,
                 *     onValueChange = { geminiKeyInput = it },
                 *     label = "Gemini API Key"
                 * )
                 */

                SecretKeyTextField(
                    value = groqKeyInput,
                    onValueChange = { groqKeyInput = it },
                    label = if (savedGroqSummary != "None saved") {
                        "Groq API Key (Saved: $savedGroqSummary)"
                    } else {
                        "Groq API Key (Bearer)"
                    }
                )
                Text(
                    text = "SecurePrefs Groq Key: $savedGroqSummary",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (savedGroqSummary != "None saved") TealAccent else WarningRed
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = !isThinking,
                        onClick = {
                            val resolvedKey = viewModel.resolveApiKeyForTest(
                                provider = LlmProvider.GROQ,
                                groqKeyDraft = groqKeyInput
                            )
                            if (resolvedKey.isNullOrEmpty()) {
                                Toast.makeText(
                                    context,
                                    "No API key found. Save it first.",
                                    Toast.LENGTH_LONG
                                ).show()
                                viewModel.setDiagnosticError("No API key found. Save it first.")
                                return@OutlinedButton
                            }

                            if (groqKeyInput.isNotBlank()) {
                                groqKeyInput = ""
                            }

                            viewModel.testActiveLlmConnection(
                                provider = LlmProvider.GROQ,
                                resolvedKey = resolvedKey
                            )
                        },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, TealAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isThinking) "Testing..." else "Test ${selectedProvider.name}",
                            color = TealAccent
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.saveLlmSettings(
                                aiEnabled = aiEnabled,
                                provider = LlmProvider.GROQ,
                                groqKeyInput = groqKeyInput
                            )
                            if (elevenLabsKeyInput.isNotBlank()) {
                                viewModel.saveElevenLabsKey(elevenLabsKeyInput)
                                elevenLabsKeyInput = ""
                            }
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

                // Native Android TextView directly under the Test buttons displaying the exact
                // HTTP status code (e.g. 200, 401, 400) or Exception (UnknownHostException, etc.)
                Surface(
                    color = SurfaceSubtleDark,
                    shape = CardShape8,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (llmDiagnosticIsError) WarningRed else BorderDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val errorColorArgb = WarningRed.toArgb()
                    val okColorArgb = TealAccent.toArgb()
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        factory = { ctx ->
                            TextView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                                typeface = Typeface.MONOSPACE
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                                setTextIsSelectable(true)
                            }
                        },
                        update = { textView ->
                            textView.text = llmDiagnosticText
                            textView.setTextColor(
                                if (llmDiagnosticIsError) errorColorArgb else okColorArgb
                            )
                        }
                    )
                }
            }
        }

        // 2. Proper Voice: ElevenLabs + Natural Android TTS Fallback (0.9x, Pitch 1.0)
        item {
            SettingsCard(title = "VOICE ENGINE (ELEVENLABS + 0.9X NATURAL FALLBACK)") {
                Text(
                    text = if (hasElevenSaved) {
                        "ElevenLabs active (Voice ID: 21m00Tcm4TlvDq8ikWAM). Automatically falls back to Android TTS (0.9x) if offline or on API error."
                    } else {
                        "Using Android TTS natural fallback (Speech rate 0.9x, Pitch 1.0). Save your ElevenLabs API key below to route all speech through ElevenLabs."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )

                SecretKeyTextField(
                    value = elevenLabsKeyInput,
                    onValueChange = { elevenLabsKeyInput = it },
                    label = if (hasElevenSaved) {
                        "ElevenLabs API Key (Saved: $savedElevenLabsSummary)"
                    } else {
                        "ElevenLabs API Key (sk_...)"
                    }
                )

                Text(
                    text = "SecurePrefs ElevenLabs Key: $savedElevenLabsSummary",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasElevenSaved) TealAccent else WarningRed
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (elevenLabsKeyInput.isBlank() && !viewModel.securePrefs.hasElevenLabsKey()) {
                                Toast.makeText(
                                    context,
                                    "No ElevenLabs key saved — testing fallback Android TTS.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            viewModel.testVoicePlayback(elevenLabsKeyInput)
                            if (elevenLabsKeyInput.isNotBlank()) {
                                elevenLabsKeyInput = ""
                            }
                        },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, TealAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isSpeaking) "Speaking..." else "Test Voice",
                            color = TealAccent
                        )
                    }

                    Button(
                        onClick = {
                            if (elevenLabsKeyInput.isNotBlank()) {
                                viewModel.saveElevenLabsKey(elevenLabsKeyInput)
                                elevenLabsKeyInput = ""
                            } else if (viewModel.securePrefs.hasElevenLabsKey()) {
                                Toast.makeText(
                                    context,
                                    "ElevenLabs key already saved ($savedElevenLabsSummary).",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Paste your ElevenLabs API key first.",
                                    Toast.LENGTH_SHORT
                                ).show()
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
                        },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear ElevenLabs Key (Use Android TTS 0.9x)", color = WarningRed)
                    }
                }

                // Live diagnostic TextView showing ElevenLabs HTTP status / MediaPlayer playback or fallback error
                Surface(
                    color = SurfaceSubtleDark,
                    shape = CardShape8,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (voiceStatusIsError) WarningRed else BorderDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val errorColorArgb = WarningRed.toArgb()
                    val okColorArgb = TealAccent.toArgb()
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        factory = { ctx ->
                            TextView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                                typeface = Typeface.MONOSPACE
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                                setTextIsSelectable(true)
                            }
                        },
                        update = { textView ->
                            textView.text = voiceStatusText
                            textView.setTextColor(
                                if (voiceStatusIsError) errorColorArgb else okColorArgb
                            )
                        }
                    )
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
                        if (elevenLabsKeyInput.isNotBlank()) {
                            viewModel.saveElevenLabsKey(elevenLabsKeyInput)
                            elevenLabsKeyInput = ""
                        }
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
