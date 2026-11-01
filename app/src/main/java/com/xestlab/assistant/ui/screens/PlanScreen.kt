package com.xestlab.assistant.ui.screens

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xestlab.assistant.data.model.ChecklistItem
import com.xestlab.assistant.data.model.DailyLog
import com.xestlab.assistant.data.model.Goal
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.ui.theme.BgDark
import com.xestlab.assistant.ui.theme.BorderDark
import com.xestlab.assistant.ui.theme.CardShape8
import com.xestlab.assistant.ui.theme.SurfaceDark
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.TextMutedDark
import com.xestlab.assistant.ui.theme.TextPrimaryDark
import com.xestlab.assistant.ui.theme.WarningRed
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlanScreen(viewModel: AssistantViewModel) {
    val subTabs = listOf("Today", "Week", "Month", "Year", "Pipeline")
    var selectedSubTab by remember { mutableIntStateOf(0) }

    val checklistItems by viewModel.checklistItems.collectAsState()
    val todayLogs by viewModel.todayLogs.collectAsState()
    val pendingItems by viewModel.pendingItems.collectAsState()
    val allItems by viewModel.allItems.collectAsState()
    val goals by viewModel.goals.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        ScrollableTabRow(
            selectedTabIndex = selectedSubTab,
            containerColor = SurfaceDark,
            contentColor = TealAccent,
            edgePadding = 16.dp
        ) {
            subTabs.forEachIndexed { index, label ->
                Tab(
                    selected = selectedSubTab == index,
                    onClick = { selectedSubTab = index },
                    text = {
                        Text(
                            text = label,
                            color = if (selectedSubTab == index) TealAccent else TextMutedDark,
                            fontWeight = if (selectedSubTab == index) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedSubTab) {
                0 -> {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CORE DAILY CHECKLIST",
                                style = MaterialTheme.typography.labelMedium,
                                color = TealAccent
                            )
                            OutlinedButton(
                                onClick = { viewModel.readDailyPlanAloud() },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, TealAccent)
                            ) {
                                Text("Read Aloud", color = TealAccent)
                            }
                        }
                    }

                    val logsMap = todayLogs.associateBy { it.itemId }
                    items(checklistItems, key = { it.id }) { checkItem ->
                        ChecklistRowCard(
                            item = checkItem,
                            log = logsMap[checkItem.id],
                            onToggle = { done -> viewModel.toggleChecklistDone(checkItem, !done) },
                            onSaveReason = { reason -> viewModel.logChecklistReasonNotDone(checkItem, reason) }
                        )
                    }

                    val activeToday = pendingItems.filter { !it.sourceText.startsWith("web_pipeline_") }
                    if (activeToday.isNotEmpty()) {
                        item {
                            Text(
                                text = "SCHEDULED TASKS & REMINDERS",
                                style = MaterialTheme.typography.labelMedium,
                                color = TealAccent,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items(activeToday, key = { it.id }) { task ->
                            TaskPlanCard(
                                item = task,
                                onComplete = { viewModel.markTaskComplete(task) },
                                onDelete = { viewModel.deleteItem(task) }
                            )
                        }
                    }
                }

                1 -> {
                    item {
                        WeekScreen(viewModel = viewModel)
                    }
                }

                2 -> {
                    item {
                        Text(
                            text = "30-DAY GOALS & DEADLINE PACE",
                            style = MaterialTheme.typography.labelMedium,
                            color = TealAccent
                        )
                    }
                    val horizonItems = pendingItems.filter {
                        it.type == "GOAL" || it.dueAt != null
                    }
                    if (horizonItems.isEmpty()) {
                        item {
                            EmptyPlanCard("No 30-day goals queued yet. Speak or type 'Read 300 pages by Friday' on Home.")
                        }
                    } else {
                        items(horizonItems, key = { it.id }) { item ->
                            TaskPlanCard(
                                item = item,
                                onComplete = { viewModel.markTaskComplete(item) },
                                onDelete = { viewModel.deleteItem(item) }
                            )
                        }
                    }
                }

                3 -> {
                    item {
                        YearlyTargetsCard(goals = goals)
                    }
                }

                4 -> {
                    val pipelineItems = allItems.filter {
                        it.sourceText.startsWith("web_pipeline_") ||
                            it.details.contains("Pipeline", ignoreCase = true)
                    }
                    item {
                        Text(
                            text = "STUDIO PIPELINE & IMPORTED LEADS",
                            style = MaterialTheme.typography.labelMedium,
                            color = TealAccent
                        )
                    }
                    if (pipelineItems.isEmpty()) {
                        item {
                            EmptyPlanCard("No pipeline records imported yet. Use Settings -> Import JSON to bring in web tracker leads and revenue.")
                        }
                    } else {
                        items(pipelineItems, key = { it.id }) { item ->
                            TaskPlanCard(
                                item = item,
                                onComplete = { viewModel.markTaskComplete(item) },
                                onDelete = { viewModel.deleteItem(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistRowCard(
    item: ChecklistItem,
    log: DailyLog?,
    onToggle: (Boolean) -> Unit,
    onSaveReason: (String) -> Unit
) {
    val isDone = log?.isDone == true
    var showBlockerInput by remember { mutableStateOf(false) }
    var blockerText by remember(log?.reasonNotDone) { mutableStateOf(log?.reasonNotDone.orEmpty()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, if (isDone) TealAccent else BorderDark)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isDone,
                    onCheckedChange = { checked -> onToggle(checked) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = TealAccent,
                        uncheckedColor = TextMutedDark,
                        checkmarkColor = BgDark
                    )
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isDone) TealAccent else TextPrimaryDark
                    )
                    if (!isDone && !log?.reasonNotDone.isNullOrBlank()) {
                        Text(
                            text = "Blocker: ${log?.reasonNotDone}",
                            style = MaterialTheme.typography.labelMedium,
                            color = WarningRed
                        )
                    }
                }
                if (!isDone) {
                    OutlinedButton(
                        onClick = { showBlockerInput = !showBlockerInput },
                        shape = CardShape8,
                        border = BorderStroke(1.dp, BorderDark)
                    ) {
                        Text("Why?", color = TextMutedDark)
                    }
                }
            }

            if (showBlockerInput && !isDone) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = blockerText,
                        onValueChange = { blockerText = it },
                        placeholder = { Text("Honest blocker or reason...") },
                        singleLine = true,
                        shape = CardShape8,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TealAccent,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextPrimaryDark,
                            unfocusedTextColor = TextPrimaryDark
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (blockerText.isNotBlank()) {
                                onSaveReason(blockerText)
                                showBlockerInput = false
                            }
                        },
                        shape = CardShape8,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                    ) {
                        Text("Log", color = BgDark, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskPlanCard(
    item: Item,
    onComplete: () -> Unit,
    onDelete: () -> Unit
) {
    val dueFormatted = item.dueAt?.let {
        SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(it))
    }
    val dailyPace = item.computeDailyRequired()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${item.type} - ${item.priority}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
                if (dueFormatted != null) {
                    Text(
                        text = dueFormatted,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMutedDark
                    )
                }
            }
            Text(text = item.title, style = MaterialTheme.typography.titleMedium)
            if (item.details.isNotBlank()) {
                Text(text = item.details, style = MaterialTheme.typography.bodyMedium, color = TextMutedDark)
            }
            if (dailyPace != null && item.unit != null) {
                Text(
                    text = "Required pace: ${String.format(Locale.US, "%.1f", dailyPace)} ${item.unit}/day",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!item.isCompleted) {
                    OutlinedButton(
                        onClick = onComplete,
                        shape = CardShape8,
                        border = BorderStroke(1.dp, TealAccent)
                    ) {
                        Text("Done", color = TealAccent)
                    }
                }
                OutlinedButton(
                    onClick = onDelete,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Text("Delete", color = WarningRed)
                }
            }
        }
    }
}

@Composable
private fun YearlyTargetsCard(goals: Goal) {
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
                text = "ANNUAL REVENUE & QUARTERLY TARGETS",
                style = MaterialTheme.typography.labelMedium,
                color = TealAccent
            )
            Text(
                text = "NGN ${String.format(Locale.US, "%,d", goals.yearlyGoal)}",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Cycle start date: ${goals.startDate}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMutedDark
            )
            QuarterRow("Q1 Target", goals.q1Target)
            QuarterRow("Q2 Target", goals.q2Target)
            QuarterRow("Q3 Target", goals.q3Target)
            QuarterRow("Q4 Target", goals.q4Target)
        }
    }
}

@Composable
private fun QuarterRow(label: String, amount: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextMutedDark)
        Text(
            text = "NGN ${String.format(Locale.US, "%,d", amount)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EmptyPlanCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMutedDark,
            modifier = Modifier.padding(16.dp)
        )
    }
}
