package com.xestlab.assistant.ui.screens

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xestlab.assistant.ui.theme.Shapes8px
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.WarningRed
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel

@Composable
fun SetupScreen(
    viewModel: AssistantViewModel,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val alarmManager = remember { context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager }

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    var hasExactAlarmPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager?.canScheduleExactAlarms() == true
            } else true
        )
    }

    var isBatteryIgnoring by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
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
                        text = "First-Run Device Setup",
                        fontSize = 18.dp.value.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "To ensure reminders speak aloud reliably with the screen turned off, enable the permissions below.",
                        fontSize = 13.dp.value.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // 1. Notification Permission
        item {
            SetupStepCard(
                title = "1. Notifications",
                description = "Required to display alarms, check-ins, and notification actions.",
                isDone = hasNotificationPermission,
                buttonText = if (hasNotificationPermission) "Enabled" else "Grant Notification Permission",
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )
        }

        // 2. Exact Alarms
        item {
            SetupStepCard(
                title = "2. Exact Alarms",
                description = "Required for precise scheduled times (07:30, 12:30, 19:30).",
                isDone = hasExactAlarmPermission,
                buttonText = if (hasExactAlarmPermission) "Enabled" else "Allow Exact Alarms in Settings",
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_SETTINGS)
                            context.startActivity(intent)
                        }
                    }
                }
            )
        }

        // 3. Battery Optimisation
        item {
            SetupStepCard(
                title = "3. Disable Battery Optimisation",
                description = "Prevents Android from freezing the speech engine when the phone is sleeping.",
                isDone = isBatteryIgnoring,
                buttonText = if (isBatteryIgnoring) "Optimisation Disabled" else "Open Battery Settings",
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            )
        }

        // 4. Autostart Instructions for OEM devices
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Phone Autostart Steps (Crucial in Nigeria)",
                        fontSize = 15.dp.value.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OemInstructionBlock("Tecno / Infinix (HiOS / XOS)", "Go to Phone Master > Auto-start Management > Find Xestlab Assistant and turn ON. Also allow background pop-up windows.")
                    Spacer(modifier = Modifier.height(6.dp))
                    OemInstructionBlock("Xiaomi / Redmi (MIUI / HyperOS)", "Go to Settings > Apps > Manage Apps > Xestlab Assistant > Autostart: Set to ON. Battery saver: No restrictions.")
                    Spacer(modifier = Modifier.height(6.dp))
                    OemInstructionBlock("Samsung (One UI)", "Go to Settings > Battery and device care > Battery > Background usage limits > Never sleeping apps > Add Xestlab Assistant.")
                }
            }
        }

        // 5. Test Reminder in 1 minute
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Test Alarms and Spoken Voice",
                        fontSize = 15.dp.value.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap below, then lock your screen. In 1 minute the phone will wake, post a notification, and speak aloud.",
                        fontSize = 12.dp.value.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.testReminderInOneMinute() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = Shapes8px.small,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onSurface)
                    ) {
                        Text("Test reminder in 1 minute")
                    }
                }
            }
        }

        // Finish button
        item {
            Button(
                onClick = {
                    viewModel.completeFirstRun()
                    onComplete()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = Shapes8px.medium,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent, contentColor = Color.White)
            ) {
                Text(text = "Complete Setup and Continue", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SetupStepCard(
    title: String,
    description: String,
    isDone: Boolean,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = Shapes8px,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isDone) TealAccent else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 14.dp.value.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isDone) "Allowed" else "Action needed",
                    fontSize = 12.dp.value.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDone) TealAccent else WarningRed
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 12.dp.value.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onClick,
                shape = Shapes8px.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDone) MaterialTheme.colorScheme.primaryContainer else TealAccent,
                    contentColor = if (isDone) MaterialTheme.colorScheme.onSurface else Color.White
                )
            ) {
                Text(text = buttonText, fontSize = 12.dp.value.sp)
            }
        }
    }
}

@Composable
fun OemInstructionBlock(oemName: String, steps: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, Shapes8px.small)
            .padding(10.dp)
    ) {
        Text(text = oemName, fontSize = 12.dp.value.sp, fontWeight = FontWeight.Bold, color = TealAccent)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = steps, fontSize = 11.dp.value.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
    }
}
