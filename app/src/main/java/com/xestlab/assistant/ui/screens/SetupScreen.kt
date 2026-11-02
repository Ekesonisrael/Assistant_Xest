@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.xestlab.assistant.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xestlab.assistant.service.AlarmScheduler
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

@Composable
fun SetupScreen(
    viewModel: AssistantViewModel,
    isReopenedFromSettings: Boolean = false,
    onClose: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs by viewModel.prefsState.collectAsState()

    var nameInput by remember(prefs.userName) {
        mutableStateOf(prefs.userName.ifBlank { "King Israel" })
    }
    var wakeUpInput by remember(prefs.wakeUpTime) {
        mutableStateOf(prefs.wakeUpTime.ifBlank { "07:30" })
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var notificationsGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
    }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
    }

    val manufacturer = remember { Build.MANUFACTURER.lowercase() }
    val oemTip = remember(manufacturer) {
        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
                "Xiaomi / MIUI detected: Enable Autostart and set Battery Saver to 'No restrictions' in App Info."
            manufacturer.contains("samsung") ->
                "Samsung One UI detected: Add Xestlab Assistant to 'Never sleeping apps' under Battery -> Background usage limits."
            manufacturer.contains("tecno") || manufacturer.contains("infinix") || manufacturer.contains("itel") ->
                "Tecno / Infinix (HiOS / XOS) detected: Enable Auto-start in Phone Master and lock app in recent tasks."
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") ->
                "ColorOS / OxygenOS detected: Allow background activity and auto-launch in App Battery Management."
            else ->
                "Keep battery optimization unrestricted so your 7:30 AM wake-up voice plan runs reliably."
        }
    }

    val scope = rememberCoroutineScope()
    val nameBringIntoView = remember { BringIntoViewRequester() }
    val wakeBringIntoView = remember { BringIntoViewRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = if (isReopenedFromSettings) "System Setup & Reliability" else "Welcome to Xestlab Assistant",
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = "Your executive business coach with encrypted Gemini/Groq routing, ElevenLabs + natural TTS voice, smart IMAP email checks, and 7:30 AM wake-up briefings.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMutedDark
        )

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
                    text = "1. Profile & 7:30 AM Wake-Up Alarm",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Saved locally in Jetpack DataStore and used to greet you every morning.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = {
                        nameInput = it
                        if (validationError != null) validationError = null
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
                    label = { Text("Your Name") },
                    placeholder = { Text("King Israel", color = TextMutedDark) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(nameBringIntoView)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                scope.launch {
                                    delay(120L)
                                    nameBringIntoView.bringIntoView()
                                }
                            }
                        },
                    shape = CardShape8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        focusedContainerColor = SurfaceSubtleDark,
                        unfocusedContainerColor = SurfaceSubtleDark,
                        cursorColor = TealAccent,
                        focusedLabelColor = TealAccent,
                        unfocusedLabelColor = TextMutedDark
                    )
                )

                OutlinedTextField(
                    value = wakeUpInput,
                    onValueChange = { wakeUpInput = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
                    label = { Text("Morning Wake-Up Alarm (HH:mm)") },
                    placeholder = { Text("07:30", color = TextMutedDark) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(wakeBringIntoView)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                scope.launch {
                                    delay(120L)
                                    wakeBringIntoView.bringIntoView()
                                }
                            }
                        },
                    shape = CardShape8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        focusedContainerColor = SurfaceSubtleDark,
                        unfocusedContainerColor = SurfaceSubtleDark,
                        cursorColor = TealAccent,
                        focusedLabelColor = TealAccent,
                        unfocusedLabelColor = TextMutedDark
                    )
                )

                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarningRed
                    )
                }
            }
        }

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
                    text = "2. Voice & Wake Permissions",
                    style = MaterialTheme.typography.titleMedium
                )

                PermissionActionRow(
                    title = "Microphone (SpeechRecognizer)",
                    status = if (micGranted) "Granted" else "Required for voice input",
                    buttonLabel = if (micGranted) "Ready" else "Grant",
                    enabled = !micGranted,
                    onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionActionRow(
                        title = "Heads-Up Notifications",
                        status = if (notificationsGranted) "Granted" else "Required for 7:30 AM wake-up & email alerts",
                        buttonLabel = if (notificationsGranted) "Ready" else "Allow",
                        enabled = !notificationsGranted,
                        onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val exactAllowed = AlarmScheduler.canScheduleExactAlarms(context)
                    PermissionActionRow(
                        title = "Exact Wake-Up Alarms",
                        status = if (exactAllowed) "Allowed" else "Open settings to enable",
                        buttonLabel = "Configure",
                        enabled = true,
                        onClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    )
                }

                PermissionActionRow(
                    title = "Unrestricted Battery",
                    status = if (isIgnoringBatteryOptimizations(context)) "Unrestricted" else "Recommended for 7:30 AM wake-up",
                    buttonLabel = "Optimize",
                    enabled = true,
                    onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                )
            }
        }

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
                Text(
                    text = "3. Device Reliability Check",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = oemTip,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )
                OutlinedButton(
                    onClick = { viewModel.scheduleOneMinuteTestAlarm() },
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test Wake-Up Voice Plan in 60 Seconds", color = TealAccent)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = {
                if (nameInput.trim().isEmpty()) {
                    validationError = "Please enter your name so I can save it to DataStore."
                } else {
                    viewModel.completeFirstLaunch(nameInput.trim(), wakeUpInput.trim())
                    if (isReopenedFromSettings) {
                        onClose()
                    }
                }
            },
            shape = CardShape8,
            colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(
                text = if (isReopenedFromSettings) "Save & Return" else "Save Profile & Open Assistant",
                fontWeight = FontWeight.SemiBold,
                color = BgDark
            )
        }

        if (isReopenedFromSettings) {
            OutlinedButton(
                onClick = onClose,
                shape = CardShape8,
                border = BorderStroke(1.dp, BorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel", color = TextPrimaryDark)
            }
        }
    }
}

@Composable
private fun PermissionActionRow(
    title: String,
    status: String,
    buttonLabel: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(text = status, style = MaterialTheme.typography.labelMedium, color = TextMutedDark)
        }
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = CardShape8,
            border = BorderStroke(1.dp, if (enabled) TealAccent else BorderDark)
        ) {
            Text(
                text = buttonLabel,
                color = if (enabled) TealAccent else TextMutedDark
            )
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
