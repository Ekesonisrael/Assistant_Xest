package com.xestlab.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xestlab.assistant.data.model.ChecklistItem
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.ui.theme.Shapes8px
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.WarningRed
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel
import java.text.SimpleDateFormat
import java.util.*

private enum class PlanViewTab(val label: String) {
    TODAY("Today"),
    WEEK("Week"),
    MONTH("Month"),
    YEAR("Year"),
    PIPELINE("Pipeline")
}

@Composable
fun PlanScreen(viewModel: AssistantViewModel) {
    var selectedTab by remember { mutableStateOf(PlanViewTab.TODAY) }

    val allItems by viewModel.allItems.collectAsState()
    val checklistItems by viewModel.checklistItems.collectAsState()
    val todayLogs by viewModel.todayLogs.collectAsState()
    val reminders by viewModel.reminders.collectAsState()
    val goals by viewModel.goals.collectAsState()
    val weeklySummary by viewModel.weeklySummary.collectAsState()
    val settings by viewModel.settings.collectAsState()

    val logsMap = remember(todayLogs) { todayLogs.associateBy { it.itemId } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Header
        Text(
            text = "Execution Plan",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Daily schedule, proactive alarms, and milestone checklists.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // View Tabs (8px rounded chips, no pills)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PlanViewTab.values().forEach { tab ->
                val isSelected = selectedTab == tab
                Surface(
                    shape = Shapes8px.small,
                    color = if (isSelected) TealAccent else MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { selectedTab = tab }
                        .border(
                            1.dp,
                            if (isSelected) TealAccent else MaterialTheme.colorScheme.outline,
                            Shapes8px.small
                        )
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (selectedTab) {
            PlanViewTab.TODAY -> {
                TodayScheduleView(
                    viewModel = viewModel,
                    checklistItems = checklistItems,
                    logsMap = logsMap,
                    reminders = reminders.filter { it.isActive },
                    pendingTasks = allItems.filter { it.type != "NOTE" && it.status == "PENDING" },
                    settings = settings
                )
            }
            PlanViewTab.WEEK -> {
                WeekScreen(viewModel = viewModel)
            }
            PlanViewTab.MONTH -> {
                MonthView(goals = goals, items = allItems.filter { it.type == "GOAL" })
            }
            PlanViewTab.YEAR -> {
                YearView(goals = goals)
            }
            PlanViewTab.PIPELINE -> {
                PipelineView(
                    pipelineItems = allItems.filter { it.sourceText.startsWith("web_pipeline_") || it.details.contains("Stage:") },
                    onToggleComplete = { item ->
                        if (item.status == "COMPLETED") {
                            viewModel.repository.let {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    it.updateItemStatus(item.id, "PENDING")
                                }
                            }
                        } else {
                            viewModel.completeItem(item)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TodayScheduleView(
    viewModel: AssistantViewModel,
    checklistItems: List<ChecklistItem>,
    logsMap: Map<String, com.xestlab.assistant.data.model.DailyLog>,
    reminders: List<com.xestlab.assistant.data.model.ReminderSchedule>,
    pendingTasks: List<Item>,
    settings: com.xestlab.assistant.data.model.UserSettings
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Wake-up and Quiet hours card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Wake-up Alarm", fontSize = 11.sp, color = TealAccent, fontWeight = FontWeight.Bold)
                        Text(settings.wakeUpTime, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Quiet Hours", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Text("${settings.quietHoursStart} - ${settings.quietHoursEnd}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Checklist Items Section
        item {
            Text("Core Daily Checklist", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TealAccent)
        }

        items(checklistItems) { item ->
            val log = logsMap[item.id]
            val isDone = log?.isDone == true
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(
                    containerColor = if (isDone) MaterialTheme.colorScheme.surface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDone) TealAccent else MaterialTheme.colorScheme.outline
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDone) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface
                        )
                        if (log?.countValue != null && log.countValue > 0) {
                            Text(
                                text = "${log.countValue} logged",
                                fontSize = 11.sp,
                                color = TealAccent
                            )
                        }
                    }
                    Button(
                        onClick = {
                            if (isDone) {
                                viewModel.markItemNotDone(item.id, "unmarked")
                            } else {
                                viewModel.markItemDone(item.id, if (item.isCountType) 10 else 0)
                            }
                        },
                        shape = Shapes8px.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDone) TealAccent else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isDone) Color.White else MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(if (isDone) "Done" else "Mark Done", fontSize = 11.sp)
                    }
                }
            }
        }

        // Active Alarms & Reminders
        if (reminders.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Scheduled Accountability Briefings", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TealAccent)
            }
            items(reminders) { rem ->
                Surface(
                    shape = Shapes8px,
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(rem.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(rem.timeString, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TealAccent)
                    }
                }
            }
        }

        // Generic Tasks & Goals
        if (pendingTasks.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Upcoming Tasks & Goals", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TealAccent)
            }
            items(pendingTasks) { task ->
                val dailyReq = task.computeDailyRequired()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = Shapes8px,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(task.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (!task.details.isBlank()) {
                                Text(task.details, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                            }
                            if (dailyReq != null && task.unit != null) {
                                Text(
                                    text = "Required pace: ${String.format(Locale.US, "%.1f", dailyReq)} ${task.unit}/day",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TealAccent
                                )
                            }
                        }
                        Button(
                            onClick = { viewModel.completeItem(task) },
                            shape = Shapes8px.small,
                            colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Complete", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthView(goals: com.xestlab.assistant.data.model.Goal, items: List<Item>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Quarterly Targets", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TealAccent)
                    Spacer(modifier = Modifier.height(8.dp))
                    QuarterRow("Q1 (Nov 2026 - Jan 2027)", goals.q1Target)
                    QuarterRow("Q2 (Feb 2027 - Apr 2027)", goals.q2Target)
                    QuarterRow("Q3 (May 2027 - Jul 2027)", goals.q3Target)
                    QuarterRow("Q4 (Aug 2027 - Oct 2027)", goals.q4Target)
                }
            }
        }

        if (items.isNotEmpty()) {
            item {
                Text("Monthly Milestones", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TealAccent)
            }
            items(items) { goalItem ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = Shapes8px,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(goalItem.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (!goalItem.details.isBlank()) {
                            Text(goalItem.details, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuarterRow(quarter: String, amount: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(quarter, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        Text("₦$amount", fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun YearView(goals: com.xestlab.assistant.data.model.Goal) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = Shapes8px,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Annual Goal Target", fontSize = 13.sp, color = TealAccent, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("₦${goals.yearlyGoal}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "12-month studio run targeting 10,000,000 naira. Start date: ${goals.startDate}.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineView(
    pipelineItems: List<Item>,
    onToggleComplete: (Item) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "Studio Sales & Delivery Pipeline",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TealAccent
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Systemized checklist steps for client acquisition, onboarding, and project delivery.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (pipelineItems.isEmpty()) {
            item {
                Surface(
                    shape = Shapes8px,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                ) {
                    Text(
                        text = "No pipeline items loaded yet. Use Settings > 'Import from Web Goal Tracker' to load your web pipeline.",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            items(pipelineItems, key = { it.id }) { item ->
                val isCompleted = item.status == "COMPLETED"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = Shapes8px,
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCompleted) MaterialTheme.colorScheme.surface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isCompleted) TealAccent else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isCompleted) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface
                            )
                            if (item.details.isNotBlank()) {
                                Text(
                                    text = item.details,
                                    fontSize = 10.sp,
                                    color = TealAccent
                                )
                            }
                        }
                        Checkbox(
                            checked = isCompleted,
                            onCheckedChange = { onToggleComplete(item) }
                        )
                    }
                }
            }
        }
    }
}
