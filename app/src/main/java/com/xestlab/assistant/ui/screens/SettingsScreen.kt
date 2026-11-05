package com.xestlab.assistant.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.xestlab.assistant.data.LlmProvider
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.service.AlarmScheduler
import com.xestlab.assistant.service.WakeWordService
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: AssistantViewModel,
    onOpenSetup: () -> Unit,
    onOpenPriorityRules: () -> Unit = {},
    onOpenMessageTemplates: () -> Unit = {}
) {
    val context = LocalContext.current
    val settingsListState = rememberLazyListState()
    val settingsScope = rememberCoroutineScope()

    val prefs by viewModel.prefsState.collectAsState()
    val goals by viewModel.goals.collectAsState()
    val reminders by viewModel.reminders.collectAsState()
    val facts by viewModel.allFacts.collectAsState()
    val senderRules by viewModel.senderRules.collectAsState()
    val aiEnabledState by viewModel.aiEnabledState.collectAsState()
    // Gemini temporarily disabled: val savedGeminiSummary by viewModel.savedGeminiSummary.collectAsState()
    val savedGroqSummary by viewModel.savedGroqSummary.collectAsState()
    val llmDiagnosticText by viewModel.llmTestDiagnosticText.collectAsState()
    val llmDiagnosticIsError by viewModel.llmTestIsError.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()

    val wakeWordEnabledState by viewModel.wakeWordEnabledState.collectAsState()
    val wakeWordIsRunning by viewModel.wakeWordIsRunning.collectAsState()
    val wakeDiagnostics by viewModel.wakeWordDiagnostics.collectAsState()
    val wakeThresholdState by viewModel.wakeWordThreshold.collectAsState()
    val wakeWordStatusText by viewModel.wakeWordStatusText.collectAsState()
    val wakeWordStatusIsError by viewModel.wakeWordStatusIsError.collectAsState()

    val isContinuousAudioRunning by viewModel.isContinuousAudioRunning.collectAsState()
    val continuousMicLevel by viewModel.continuousAudioMicLevel.collectAsState()
    val thoughtDraft by viewModel.thoughtDraft.collectAsState()

    val lastEmailCheckTime by viewModel.lastEmailCheckTime.collectAsState()
    val lastEmailCheckResult by viewModel.lastEmailCheckResult.collectAsState()
    val lastEmailCheckIsError by viewModel.lastEmailCheckIsError.collectAsState()
    val lastAlarmFiredAt by viewModel.lastAlarmFiredAt.collectAsState()
    val lastAlarmFiredTitle by viewModel.lastAlarmFiredTitle.collectAsState()
    val nextAlarmAt by viewModel.nextAlarmAt.collectAsState()
    val nextAlarmTitle by viewModel.nextAlarmTitle.collectAsState()

    val savedElevenLabsSummary by viewModel.savedElevenLabsSummary.collectAsState()
    val voiceStatusText by viewModel.voiceStatusText.collectAsState()
    val voiceStatusIsError by viewModel.voiceStatusIsError.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val installedTtsVoices by viewModel.installedTtsVoices.collectAsState()
    val selectedAndroidVoiceName by viewModel.selectedAndroidVoiceName.collectAsState()
    val monthlyElevenLabsChars by viewModel.monthlyElevenLabsChars.collectAsState()
    val elevenLabsPausedUntil by viewModel.elevenLabsPausedUntil.collectAsState()
    val elevenLabsPauseMessage by viewModel.elevenLabsPauseMessage.collectAsState()

    var healthRefreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        viewModel.refreshSavedKeySummaries()
    }

    DisposableEffect(context) {
        val lifecycleOwner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                healthRefreshTick++
                viewModel.refreshSavedKeySummaries()
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }

    var nameField by remember(prefs.userName) { mutableStateOf(prefs.userName) }
    var wakeField by remember(prefs.wakeUpTime) { mutableStateOf(prefs.wakeUpTime) }
    var quietStartField by remember(prefs.quietHoursStart) { mutableStateOf(prefs.quietHoursStart) }
    var quietEndField by remember(prefs.quietHoursEnd) { mutableStateOf(prefs.quietHoursEnd) }
    var quietEnabledField by remember(prefs.quietHoursEnabled) { mutableStateOf(prefs.quietHoursEnabled) }
    var voiceSpeedField by remember(prefs.voiceSpeed) { mutableFloatStateOf(prefs.voiceSpeed) }
    var autoReadField by remember(prefs.autoReadPlanOnLaunch) { mutableStateOf(prefs.autoReadPlanOnLaunch) }
    var wakeThresholdSlider by remember(wakeThresholdState) { mutableFloatStateOf(wakeThresholdState) }
    var voiceDropdownExpanded by remember { mutableStateOf(false) }

    var yearlyGoalField by remember(goals.yearlyGoal) { mutableStateOf(goals.yearlyGoal.toString()) }
    var startDateField by remember(goals.startDate) { mutableStateOf(goals.startDate) }
    var monthlyTargetField by remember(goals.monthlyTarget) { mutableStateOf(goals.monthlyTarget.toString()) }
    var websitePriceField by remember(goals.websitePrice) { mutableStateOf(goals.websitePrice.toString()) }
    var retainerPriceField by remember(goals.retainerPrice) { mutableStateOf(goals.retainerPrice.toString()) }
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

    val healthNotifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        healthRefreshTick++
        if (!granted) {
            openNotificationSettings(context)
        }
    }

    val healthMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        healthRefreshTick++
        if (!granted) {
            openAppDetailsSettings(context)
        }
    }

    if (showMemoryDialog) {
        MemoryDialog(
            viewModel = viewModel,
            onDismiss = { showMemoryDialog = false }
        )
    }

    val notifPermissionOk = remember(healthRefreshTick) {
        val systemEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        systemEnabled && runtimeGranted
    }
    val exactAlarmOk = remember(healthRefreshTick) {
        AlarmScheduler.canScheduleExactAlarms(context)
    }
    val batteryExemptOk = remember(healthRefreshTick) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    val micPermissionOk = remember(healthRefreshTick) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    LazyColumn(
        state = settingsListState,
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .imePadding(),
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

        // System Health Card (8 Live Checks + One-Tap Fix Buttons)
        item {
            SettingsCard(title = "SYSTEM HEALTH") {
                val nowMs = System.currentTimeMillis()

                // 1. Notification permission
                HealthCheckRow(
                    title = "1. Notification Permission",
                    detail = if (notifPermissionOk) {
                        "Granted & enabled"
                    } else {
                        "Blocked or not granted — alarms and High email alerts cannot show"
                    },
                    isProblem = !notifPermissionOk,
                    fixLabel = "Grant",
                    onFix = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            healthNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openNotificationSettings(context)
                        }
                    }
                )

                // 2. Exact alarm permission
                HealthCheckRow(
                    title = "2. Exact Alarm Permission",
                    detail = if (exactAlarmOk) {
                        "Allowed (exact wake-up & reminder scheduling active)"
                    } else {
                        "Not allowed — 7:30 AM wake-up and reminders may be delayed"
                    },
                    isProblem = !exactAlarmOk,
                    fixLabel = "Allow",
                    onFix = { openExactAlarmSettings(context) }
                )

                // 3. Battery optimisation exemption
                HealthCheckRow(
                    title = "3. Battery Optimisation Exemption",
                    detail = if (batteryExemptOk) {
                        "Exempt (Unrestricted background execution)"
                    } else {
                        "Optimised — OS may throttle background wake-up or email sync"
                    },
                    isProblem = !batteryExemptOk,
                    fixLabel = "Exempt",
                    onFix = { openBatteryExemptionPrompt(context) }
                )

                // 4. Microphone permission
                HealthCheckRow(
                    title = "4. Microphone Permission",
                    detail = if (micPermissionOk) {
                        "Granted"
                    } else {
                        "Not granted — voice input and 'Hey Jarvis' cannot listen"
                    },
                    isProblem = !micPermissionOk,
                    fixLabel = "Grant",
                    onFix = {
                        healthMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )

                // 5. Wake-word service state
                val wakeHasIssue = !wakeWordEnabledState ||
                    !wakeWordIsRunning ||
                    wakeDiagnostics.isMicSilentWarning ||
                    wakeDiagnostics.lastError != "None" ||
                    wakeWordStatusIsError
                val wakeHealthDetail = when {
                    !wakeWordEnabledState -> "Disabled"
                    !wakeWordIsRunning -> "Stopped (${wakeDiagnostics.serviceState})"
                    wakeDiagnostics.isMicSilentWarning -> "Running, but mic level has been 0 for 5s"
                    wakeDiagnostics.lastError != "None" -> "Error: ${wakeDiagnostics.lastError}"
                    else -> "Active (${wakeDiagnostics.serviceState} · Mic ${wakeDiagnostics.micLevel}/100)"
                }
                val wakeFixLabel = when {
                    !wakeWordEnabledState -> "Enable"
                    !wakeWordIsRunning -> "Start"
                    else -> "Restart"
                }
                HealthCheckRow(
                    title = "5. Wake-Word Service State",
                    detail = wakeHealthDetail,
                    isProblem = wakeHasIssue,
                    fixLabel = wakeFixLabel,
                    onFix = {
                        if (!micPermissionOk) {
                            val perms = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            wakePermissionLauncher.launch(perms.toTypedArray())
                        } else {
                            wakeWordEnabled = true
                            viewModel.startOrRestartWakeWordFromHealth()
                        }
                    }
                )

                // 6. Last alarm fired and next alarm time
                val lastFiredFormatted = if (lastAlarmFiredAt > 0L) {
                    "${formatHealthTime(lastAlarmFiredAt)} (${lastAlarmFiredTitle.ifBlank { "Alarm" }})"
                } else {
                    "None recorded yet"
                }
                val hasUpcomingAlarm = nextAlarmAt > nowMs
                val nextAlarmFormatted = if (hasUpcomingAlarm) {
                    "${formatHealthTime(nextAlarmAt)} (${nextAlarmTitle.ifBlank { "Scheduled" }})"
                } else {
                    "No upcoming alarm scheduled"
                }
                HealthCheckRow(
                    title = "6. Alarms (Last Fired & Next)",
                    detail = "Last fired: $lastFiredFormatted\nNext alarm: $nextAlarmFormatted",
                    isProblem = !hasUpcomingAlarm,
                    fixLabel = "Reschedule",
                    onFix = { viewModel.rescheduleAllAlarmsNow() }
                )

                // 7. Last email check time and result
                val hasImapCreds = viewModel.securePrefs.imapUser.isNotBlank() &&
                    viewModel.securePrefs.imapPassword.isNotBlank()
                val emailProblem = !hasImapCreds || !imapEnabled || lastEmailCheckIsError || lastEmailCheckTime == 0L
                val emailHealthDetail = when {
                    !hasImapCreds -> "IMAP credentials not configured"
                    !imapEnabled -> "Background checks paused · Last: ${if (lastEmailCheckTime > 0L) formatHealthTime(lastEmailCheckTime) else "Never"}"
                    lastEmailCheckTime == 0L -> "Never checked yet"
                    else -> "${formatHealthTime(lastEmailCheckTime)} — $lastEmailCheckResult"
                }
                val emailFixLabel = when {
                    !hasImapCreds -> "Configure"
                    !imapEnabled -> "Enable"
                    lastEmailCheckIsError -> "Retry"
                    else -> "Check Now"
                }
                HealthCheckRow(
                    title = "7. Last Email Check & Result",
                    detail = emailHealthDetail,
                    isProblem = emailProblem,
                    fixLabel = emailFixLabel,
                    onFix = {
                        when {
                            !hasImapCreds -> {
                                settingsScope.launch { settingsListState.animateScrollToItem(5) }
                            }
                            !imapEnabled -> {
                                imapEnabled = true
                                viewModel.enableAndSyncImapNow()
                            }
                            else -> {
                                viewModel.syncEmailsNow()
                            }
                        }
                    }
                )

                // 8. Last cloud voice status
                val isCloudPausedNow = elevenLabsPausedUntil > nowMs && elevenLabsPauseMessage.isNotBlank()
                val isQuotaExceeded = monthlyElevenLabsChars >= SecurePreferences.MONTHLY_ELEVENLABS_CHAR_LIMIT
                val cloudVoiceProblem = isCloudPausedNow || !hasElevenSaved || voiceStatusIsError || isQuotaExceeded
                val cloudVoiceDetail = when {
                    isCloudPausedNow -> "Paused 24h: $elevenLabsPauseMessage"
                    !hasElevenSaved -> "No ElevenLabs key saved (using Android TTS 1.0x fallback)"
                    isQuotaExceeded -> "Monthly 10,000 char budget reached (${monthlyElevenLabsChars}/10,000)"
                    voiceStatusIsError -> "Error: $voiceStatusText"
                    else -> "Active (${monthlyElevenLabsChars}/10,000 chars) · $voiceStatusText"
                }
                val cloudVoiceFixLabel = when {
                    isCloudPausedNow -> "Resume"
                    !hasElevenSaved -> "Add Key"
                    isQuotaExceeded -> "Use TTS"
                    else -> "Test Voice"
                }
                HealthCheckRow(
                    title = "8. Cloud Voice Status",
                    detail = cloudVoiceDetail,
                    isProblem = cloudVoiceProblem,
                    fixLabel = cloudVoiceFixLabel,
                    onFix = {
                        when {
                            isCloudPausedNow -> viewModel.clearCloudVoicePauseAndTest()
                            !hasElevenSaved -> settingsScope.launch { settingsListState.animateScrollToItem(4) }
                            else -> viewModel.testVoicePlayback("")
                        }
                    }
                )
            }
        }

        // 0. openWakeWord ONNX ("Hey Jarvis") Listener & Live 5Hz Diagnostics
        item {
            SettingsCard(title = "WAKE WORD — 'HEY JARVIS' (OPENWAKEWORD ONNX PIPELINE)") {
                SettingsSwitchRow(
                    label = "Listen for 'Hey Jarvis' (while app visible or from tap)",
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
                    text = "100% On-Device 3-Model ONNX Pipeline (MIC -> VOICE_RECOGNITION -> UNPROCESSED). Triggers when 2 of the last 3 scores exceed threshold.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )

                Text(
                    text = "Sensitivity Threshold: ${String.format(Locale.US, "%.2f", wakeThresholdSlider)} (Default 0.35 — 2 of last 3 scores)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
                Slider(
                    value = wakeThresholdSlider,
                    onValueChange = {
                        wakeThresholdSlider = it
                        viewModel.setWakeWordThreshold(it)
                    },
                    valueRange = 0.10f..0.80f,
                    colors = SliderDefaults.colors(
                        thumbColor = TealAccent,
                        activeTrackColor = TealAccent
                    )
                )

                // Live 5Hz Wake-Word Diagnostics Panel
                Surface(
                    color = SurfaceSubtleDark,
                    shape = CardShape8,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (wakeDiagnostics.isMicSilentWarning || wakeWordStatusIsError) WarningRed else BorderDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val lastDetectionFormatted = if (wakeDiagnostics.lastDetectionTimeMillis > 0L) {
                        SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault())
                            .format(Date(wakeDiagnostics.lastDetectionTimeMillis))
                    } else {
                        "Never"
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Microphone Level (5x/sec)",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMutedDark
                            )
                            Text(
                                text = "${wakeDiagnostics.micLevel} / 100",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = if (wakeDiagnostics.micLevel > 0) TealAccent else TextMutedDark
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (wakeDiagnostics.micLevel / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = TealAccent,
                            trackColor = BorderDark
                        )

                        if (wakeDiagnostics.isMicSilentWarning) {
                            Surface(
                                color = BgDark,
                                shape = CardShape8,
                                border = BorderStroke(1.dp, WarningRed),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = WakeWordService.SILENT_MIC_WARNING_MESSAGE,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = WarningRed,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        WakeDiagnosticRow("Audio Frames Received", wakeDiagnostics.audioFramesReceived.toString())
                        WakeDiagnosticRow(
                            "Current Score",
                            String.format(Locale.US, "%.3f", wakeDiagnostics.currentScore)
                        )
                        WakeDiagnosticRow(
                            "Peak Score",
                            String.format(Locale.US, "%.3f", wakeDiagnostics.peakScore)
                        )
                        WakeDiagnosticRow("Last Detection Time", lastDetectionFormatted)
                        WakeDiagnosticRow(
                            "Service State",
                            "${wakeDiagnostics.serviceState} [Source: ${wakeDiagnostics.audioSource}]"
                        )
                        WakeDiagnosticRow(
                            label = "Last Error",
                            value = wakeDiagnostics.lastError,
                            isError = wakeDiagnostics.lastError != "None"
                        )
                    }
                }

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

        // 0b. Continuous Audio & Thought Buffer Engine
        item {
            SettingsCard(title = "CONTINUOUS AUDIO & THOUGHT BUFFER (ZERO-TIMEOUT)") {
                SettingsSwitchRow(
                    label = "Continuous 16kHz Stream & Thought Buffer",
                    checked = isContinuousAudioRunning,
                    onCheckedChange = { viewModel.toggleContinuousAudio() }
                )

                Text(
                    text = "Replaces aggressive 2-second VAD silence cutoff with a rolling memory ring buffer. The assistant stays completely silent while you think, pause, or ramble, executing only when an explicit trigger keyword is voiced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMutedDark
                )

                // Live Mic Energy Bar
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isContinuousAudioRunning) "Microphone Stream Active (16kHz Mono 16-bit PCM)" else "Continuous Service Idle",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isContinuousAudioRunning) TealAccent else TextMutedDark
                        )
                        Text(
                            text = "$continuousMicLevel / 100",
                            style = MaterialTheme.typography.labelSmall,
                            color = TealAccent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (continuousMicLevel / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = TealAccent,
                        trackColor = SurfaceSubtleDark
                    )
                }

                // Current Accumulated Thought Buffer Draft
                Surface(
                    color = SurfaceSubtleDark,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Accumulated Thought Buffer Draft:",
                            style = MaterialTheme.typography.labelSmall,
                            color = TealAccent,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (thoughtDraft.isNotBlank()) "\"$thoughtDraft\"" else "(Buffer empty — speak or ramble to accumulate thoughts)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (thoughtDraft.isNotBlank()) TextPrimaryDark else TextMutedDark,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Text(
                    text = "Supported Explicit Trigger Keywords:\n• \"Execute\" / \"Process\" -> Dispatches full thought payload\n• \"Save note\" / \"Take note\" -> Saves directly to notes\n• \"Add task\" / \"Create task\" -> Adds to today's plan\n• \"Done thinking\" / \"Over to you\" / \"Go ahead\" -> Dispatches payload",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMutedDark
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.forceFinalizeThought() },
                        enabled = thoughtDraft.isNotBlank(),
                        shape = CardShape8,
                        border = BorderStroke(1.dp, if (thoughtDraft.isNotBlank()) TealAccent else BorderDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Force Dispatch Buffer", color = if (thoughtDraft.isNotBlank()) TealAccent else TextMutedDark)
                    }
                    Button(
                        onClick = { viewModel.toggleContinuousAudio() },
                        shape = CardShape8,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (isContinuousAudioRunning) "Stop Service" else "Start Service",
                            color = TextPrimaryDark,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
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
                        Text("Groq (Active)", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
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
                        Text("Save Encrypted", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
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

        // 2. Proper Voice: ElevenLabs (EXAVITQu4vr4xnSDxMaL) + Android TTS Fallback (1.0x, Quality Voice Picker)
        item {
            SettingsCard(title = "VOICE ENGINE (ELEVENLABS + 1.0X ANDROID FALLBACK)") {
                val isCloudPaused = elevenLabsPausedUntil > System.currentTimeMillis() &&
                    elevenLabsPauseMessage.isNotBlank()
                val monthlyPercent = (monthlyElevenLabsChars * 100) / SecurePreferences.MONTHLY_ELEVENLABS_CHAR_LIMIT
                val isMonthlyNearQuota =
                    monthlyElevenLabsChars >= SecurePreferences.MONTHLY_ELEVENLABS_WARN_THRESHOLD

                Text(
                    text = if (hasElevenSaved) {
                        "Default ElevenLabs Voice ID: ${SecurePreferences.DEFAULT_ELEVENLABS_VOICE_ID}. Cached by text hash and used only for wake-up briefings and replies under 200 characters (longer replies use Android TTS 1.0x)."
                    } else {
                        "Using Android TTS fallback (Speech rate 1.0x, short sentences with pauses). Save an ElevenLabs API key below to enable cloud voice (${SecurePreferences.DEFAULT_ELEVENLABS_VOICE_ID}) for wake-up briefings and replies under 200 characters."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )

                // Single clear 24-hour pause banner on HTTP 401 or 402
                if (isCloudPaused) {
                    Surface(
                        color = SurfaceSubtleDark,
                        shape = CardShape8,
                        border = BorderStroke(1.dp, WarningRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = elevenLabsPauseMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = WarningRed,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // Monthly Cloud Voice Character Counter & 80% of 10,000 Warning
                Surface(
                    color = SurfaceSubtleDark,
                    shape = CardShape8,
                    border = BorderStroke(
                        1.dp,
                        if (isMonthlyNearQuota) WarningRed else BorderDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Cloud Characters Used This Month",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMutedDark
                            )
                            Text(
                                text = "${String.format(Locale.US, "%,d", monthlyElevenLabsChars)} / 10,000 ($monthlyPercent%)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = if (isMonthlyNearQuota) WarningRed else TealAccent
                            )
                        }
                        LinearProgressIndicator(
                            progress = {
                                (monthlyElevenLabsChars.toFloat() / SecurePreferences.MONTHLY_ELEVENLABS_CHAR_LIMIT)
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = if (isMonthlyNearQuota) WarningRed else TealAccent,
                            trackColor = BorderDark
                        )
                        if (isMonthlyNearQuota) {
                            Text(
                                text = "Warning: You have reached $monthlyPercent% of the 10,000 monthly cloud character budget (${String.format(Locale.US, "%,d", monthlyElevenLabsChars)} / 10,000).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WarningRed
                            )
                        }
                    }
                }

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
                    text = "SecurePrefs ElevenLabs Key: $savedElevenLabsSummary (Voice ID: ${SecurePreferences.DEFAULT_ELEVENLABS_VOICE_ID})",
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
                        Text("Save Voice Key", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
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
                        Text("Clear ElevenLabs Key (Use Android TTS 1.0x)", color = WarningRed)
                    }
                }

                // Live playback diagnostic (suppressed when 24h pause banner is already displayed above)
                if (!isCloudPaused) {
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
                }

                // Android Fallback Voice Picker (Installed Voices Sorted by Quality)
                val selectedVoiceObj = installedTtsVoices.firstOrNull { it.name == selectedAndroidVoiceName }
                val currentPickerLabel = when {
                    selectedVoiceObj != null -> selectedVoiceObj.displayLabel
                    selectedAndroidVoiceName.isNotBlank() -> selectedAndroidVoiceName
                    installedTtsVoices.isNotEmpty() ->
                        "Auto Highest Quality (${installedTtsVoices.first().displayLabel})"
                    else -> "System Default Voice"
                }

                Text(
                    text = "Android Fallback Voice (Sorted by Quality):",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            viewModel.refreshSavedKeySummaries()
                            voiceDropdownExpanded = true
                        },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, TealAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = currentPickerLabel,
                            color = TextPrimaryDark,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    DropdownMenu(
                        expanded = voiceDropdownExpanded,
                        onDismissRequest = { voiceDropdownExpanded = false },
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .heightIn(max = 320.dp)
                            .background(SurfaceDark)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Auto (Highest Quality Installed)",
                                    color = if (selectedAndroidVoiceName.isBlank()) TealAccent else TextPrimaryDark
                                )
                            },
                            onClick = {
                                viewModel.selectAndroidTtsVoice("")
                                voiceDropdownExpanded = false
                            }
                        )
                        installedTtsVoices.forEach { voiceInfo ->
                            val isSelected = voiceInfo.name == selectedAndroidVoiceName
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = voiceInfo.displayLabel,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isSelected) TealAccent else TextPrimaryDark
                                        )
                                        Text(
                                            text = "${voiceInfo.name} | Quality ${voiceInfo.quality}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TextMutedDark
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.selectAndroidTtsVoice(voiceInfo.name)
                                    voiceDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Text(
                    text = "Fallback Android TTS Speed: ${String.format(Locale.US, "%.2fx", voiceSpeedField)} (Default 1.00x, short sentences with pauses)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )
                Slider(
                    value = voiceSpeedField,
                    onValueChange = { voiceSpeedField = it },
                    onValueChangeFinished = {
                        viewModel.setFallbackVoiceSpeed(voiceSpeedField)
                    },
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
                        Text("Save IMAP", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
                    }
                }

                OutlinedButton(
                    onClick = onOpenPriorityRules,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Priority Senders & Rules (${senderRules.size})",
                        color = TealAccent,
                        fontWeight = FontWeight.SemiBold
                    )
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
                    Text("Save Profile & Wake-Up Alarm", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
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
                            colors = SwitchDefaults.colors(checkedThumbColor = TextPrimaryDark, checkedTrackColor = TealAccent)
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

        // 6. Memory, Message Templates, JSON Pipeline Import & 10M Goal Pricing
        item {
            SettingsCard(title = "TEMPLATES, MEMORY, JSON PIPELINE IMPORT & 10M GOAL") {
                OutlinedButton(
                    onClick = onOpenMessageTemplates,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Message Templates (Leads Cadence)",
                        color = TealAccent,
                        fontWeight = FontWeight.SemiBold
                    )
                }

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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsTextField(
                        value = yearlyGoalField,
                        onValueChange = { yearlyGoalField = it },
                        label = "Annual Goal (NGN)",
                        modifier = Modifier.weight(1f)
                    )
                    SettingsTextField(
                        value = startDateField,
                        onValueChange = { startDateField = it },
                        label = "Cycle Start (YYYY-MM-DD)",
                        modifier = Modifier.weight(1f)
                    )
                }

                SettingsTextField(
                    value = monthlyTargetField,
                    onValueChange = { monthlyTargetField = it },
                    label = "Monthly Cash Target (NGN)"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsTextField(
                        value = websitePriceField,
                        onValueChange = { websitePriceField = it },
                        label = "Website Price (NGN)",
                        modifier = Modifier.weight(1f)
                    )
                    SettingsTextField(
                        value = retainerPriceField,
                        onValueChange = { retainerPriceField = it },
                        label = "Retainer Price (NGN)",
                        modifier = Modifier.weight(1f)
                    )
                }

                Button(
                    onClick = {
                        val parsedYear = yearlyGoalField.replace(",", "").trim().toLongOrNull() ?: goals.yearlyGoal
                        val parsedMonth = monthlyTargetField.replace(",", "").trim().toLongOrNull() ?: goals.monthlyTarget
                        val parsedWeb = websitePriceField.replace(",", "").trim().toLongOrNull() ?: goals.websitePrice
                        val parsedRetainer = retainerPriceField.replace(",", "").trim().toLongOrNull() ?: goals.retainerPrice
                        val cleanStart = startDateField.trim().ifBlank { goals.startDate }
                        if (parsedYear > 0L) {
                            viewModel.updateGoals(
                                goals.copy(
                                    yearlyGoal = parsedYear,
                                    startDate = cleanStart,
                                    monthlyTarget = parsedMonth.coerceAtLeast(1L),
                                    websitePrice = parsedWeb.coerceAtLeast(1L),
                                    retainerPrice = parsedRetainer.coerceAtLeast(1L)
                                )
                            )
                        }
                    },
                    shape = CardShape8,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Save 10M Goal & Service Prices",
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.SemiBold
                    )
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
private fun WakeDiagnosticRow(
    label: String,
    value: String,
    isError: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextMutedDark,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (isError) WarningRed else TextPrimaryDark
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
        label = { Text(label) },
        singleLine = true,
        shape = CardShape8,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = SurfaceSubtleDark,
            unfocusedContainerColor = SurfaceSubtleDark,
            focusedBorderColor = TealAccent,
            unfocusedBorderColor = BorderDark,
            focusedTextColor = TextPrimaryDark,
            unfocusedTextColor = TextPrimaryDark,
            cursorColor = TealAccent,
            focusedLabelColor = TealAccent,
            unfocusedLabelColor = TextMutedDark
        ),
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusEvent { state ->
                if (state.isFocused) {
                    coroutineScope.launch {
                        delay(180)
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SecretKeyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        shape = CardShape8,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = SurfaceSubtleDark,
            unfocusedContainerColor = SurfaceSubtleDark,
            focusedBorderColor = TealAccent,
            unfocusedBorderColor = BorderDark,
            focusedTextColor = TextPrimaryDark,
            unfocusedTextColor = TextPrimaryDark,
            cursorColor = TealAccent,
            focusedLabelColor = TealAccent,
            unfocusedLabelColor = TextMutedDark
        ),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusEvent { state ->
                if (state.isFocused) {
                    coroutineScope.launch {
                        delay(180)
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
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
            colors = SwitchDefaults.colors(checkedThumbColor = TextPrimaryDark, checkedTrackColor = TealAccent)
        )
    }
}

@Composable
private fun HealthCheckRow(
    title: String,
    detail: String,
    isProblem: Boolean,
    fixLabel: String,
    onFix: () -> Unit
) {
    Surface(
        color = SurfaceSubtleDark,
        shape = CardShape8,
        border = BorderStroke(1.dp, if (isProblem) WarningRed else BorderDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isProblem) "●" else "✓",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isProblem) WarningRed else TealAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimaryDark
                    )
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isProblem) WarningRed else TextMutedDark
                )
            }

            if (isProblem) {
                Button(
                    onClick = onFix,
                    shape = CardShape8,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = fixLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun formatHealthTime(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return "Never"
    return SimpleDateFormat("d MMM h:mm a", Locale.getDefault()).format(Date(timestampMillis))
}

private fun openNotificationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        openAppDetailsSettings(context)
    }
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
    openAppDetailsSettings(context)
}

private fun openBatteryExemptionPrompt(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            openAppDetailsSettings(context)
        }
    }
}

private fun openAppDetailsSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
