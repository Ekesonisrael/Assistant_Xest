package com.xestlab.assistant.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.xestlab.assistant.ai.MoneyAndLeadParser
import com.xestlab.assistant.data.model.ChecklistItem
import com.xestlab.assistant.data.model.DailyLog
import com.xestlab.assistant.data.model.Goal
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.model.Payment
import com.xestlab.assistant.service.LeadCadenceEngine
import com.xestlab.assistant.service.RevenueGaugeMetrics
import com.xestlab.assistant.ui.theme.BgDark
import com.xestlab.assistant.ui.theme.BorderDark
import com.xestlab.assistant.ui.theme.CardShape8
import com.xestlab.assistant.ui.theme.SurfaceDark
import com.xestlab.assistant.ui.theme.SurfaceSubtleDark
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.TealAccentContainer
import com.xestlab.assistant.ui.theme.TextMutedDark
import com.xestlab.assistant.ui.theme.TextPrimaryDark
import com.xestlab.assistant.ui.theme.WarningRed
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlanScreen(viewModel: AssistantViewModel) {
    val subTabs = listOf("Today", "Week", "Month", "Year", "Payments", "Pipeline")
    var selectedSubTab by remember { mutableIntStateOf(0) }

    val checklistItems by viewModel.checklistItems.collectAsState()
    val todayLogs by viewModel.todayLogs.collectAsState()
    val pendingItems by viewModel.pendingItems.collectAsState()
    val allItems by viewModel.allItems.collectAsState()
    val goals by viewModel.goals.collectAsState()
    val payments by viewModel.payments.collectAsState()
    val revenueGauge by viewModel.revenueGauge.collectAsState()

    var showAddPaymentDialog by remember { mutableStateOf(false) }
    var editingPayment by remember { mutableStateOf<Payment?>(null) }

    if (showAddPaymentDialog) {
        AddOrEditPaymentDialog(
            existing = null,
            onSaveNew = { clientName, rawAmount, payType, note, markWon ->
                viewModel.addManualPayment(
                    clientName = clientName,
                    rawAmountInput = rawAmount,
                    type = payType,
                    note = note,
                    alsoMarkMatchedLeadWon = markWon
                )
                showAddPaymentDialog = false
            },
            onSaveEdited = {},
            onDismiss = { showAddPaymentDialog = false }
        )
    }

    if (editingPayment != null) {
        AddOrEditPaymentDialog(
            existing = editingPayment,
            onSaveNew = { _, _, _, _, _ -> },
            onSaveEdited = { updated ->
                viewModel.updatePayment(updated)
                editingPayment = null
            },
            onDismiss = { editingPayment = null }
        )
    }

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
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
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
                        MonthRevenueSummaryCard(gauge = revenueGauge)
                    }
                    item {
                        Text(
                            text = "30-DAY GOALS & DEADLINE PACE",
                            style = MaterialTheme.typography.labelMedium,
                            color = TealAccent
                        )
                    }
                    val horizonItems = pendingItems.filter {
                        (it.type == "GOAL" || it.dueAt != null) &&
                            !it.sourceText.startsWith("lead_followup:")
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
                        YearlyTargetsCard(goals = goals, gauge = revenueGauge)
                    }
                }

                4 -> {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "PAYMENTS LEDGER (${payments.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TealAccent
                                )
                                Text(
                                    text = "Single source of truth for Year, Month & Week cash",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMutedDark
                                )
                            }
                            Button(
                                onClick = { showAddPaymentDialog = true },
                                shape = CardShape8,
                                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                            ) {
                                Text("+ Log Payment", color = BgDark, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    item {
                        PaymentsKpiStrip(gauge = revenueGauge)
                    }

                    if (payments.isEmpty()) {
                        item {
                            EmptyPlanCard(
                                "No payments logged yet. Say 'Kaizy paid 150k' or 'Udofe paid 80,000 for retainer' on Home, or tap '+ Log Payment'."
                            )
                        }
                    } else {
                        items(payments, key = { "pay_${it.id}" }) { payment ->
                            PaymentRowCard(
                                payment = payment,
                                onEdit = { editingPayment = payment },
                                onDelete = { viewModel.deletePayment(payment) }
                            )
                        }
                    }
                }

                5 -> {
                    val pipelineItems = allItems.filter {
                        it.sourceText.startsWith("web_pipeline_") ||
                            it.details.contains("Pipeline", ignoreCase = true)
                    }
                    item {
                        Text(
                            text = "STUDIO PIPELINE & IMPORTED RECORDS",
                            style = MaterialTheme.typography.labelMedium,
                            color = TealAccent
                        )
                    }
                    if (pipelineItems.isEmpty()) {
                        item {
                            EmptyPlanCard("No pipeline records imported yet. Use Settings -> Import Pipeline JSON to bring in web tracker leads and revenue.")
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
private fun MonthRevenueSummaryCard(gauge: RevenueGaugeMetrics) {
    val monthPct = ((gauge.monthCash.toFloat() / gauge.monthTarget.coerceAtLeast(1L).toFloat()))
        .coerceIn(0f, 1f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, TealAccent)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "MONTH CASH VS MONTH TARGET",
                style = MaterialTheme.typography.labelMedium,
                color = TealAccent
            )
            Text(
                text = "₦${LeadCadenceEngine.formatWithCommas(gauge.monthCash)} / ₦${LeadCadenceEngine.formatWithCommas(gauge.monthTarget)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
            LinearProgressIndicator(
                progress = { monthPct },
                color = TealAccent,
                trackColor = SurfaceSubtleDark,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = if (gauge.monthGap > 0L) {
                    "Month gap remaining: ₦${LeadCadenceEngine.formatWithCommas(gauge.monthGap)} | Logged today: ₦${LeadCadenceEngine.formatWithCommas(gauge.todayCash)}"
                } else {
                    "Month target achieved! Logged today: ₦${LeadCadenceEngine.formatWithCommas(gauge.todayCash)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (gauge.monthGap > 0L) TextMutedDark else TealAccent
            )
        }
    }
}

@Composable
private fun YearlyTargetsCard(goals: Goal, gauge: RevenueGaugeMetrics) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape8,
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, TealAccent)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "10 MILLION GAUGE — YEAR TO DATE",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
                Text(
                    text = "₦${LeadCadenceEngine.formatWithCommas(gauge.ytdCash)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TealAccent
                )
                Text(
                    text = gauge.homeGaugeLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimaryDark
                )
                LinearProgressIndicator(
                    progress = { (gauge.percentOfGoal.toFloat() / 100f).coerceIn(0f, 1f) },
                    color = TealAccent,
                    trackColor = SurfaceSubtleDark,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Annual Goal: ₦${LeadCadenceEngine.formatWithCommas(goals.yearlyGoal)} · Cycle Start: ${goals.startDate}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )

                HorizontalDivider(color = BorderDark)

                QuarterRow("YTD Cash Logged", gauge.ytdCash)
                QuarterRow("Pro-Rated Target for Today", gauge.proRatedTargetToday)
                QuarterRow("Today's Pro-Rated Gap", gauge.proRatedGapToday)
                QuarterRow("Remaining Full-Year Gap", gauge.remainingYearGap)

                HorizontalDivider(color = BorderDark)

                Text(
                    text = "HOW TO CLOSE THE GAP (AT CONFIGURED PRICES)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
                Text(
                    text = "Configured prices: Website = ₦${LeadCadenceEngine.formatWithCommas(gauge.websitePrice)} · Retainer = ₦${LeadCadenceEngine.formatWithCommas(gauge.retainerPrice)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )

                Surface(
                    color = SurfaceSubtleDark,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (gauge.proRatedGapToday > 0L) {
                            Text(
                                text = "To close today's pro-rated gap (₦${LeadCadenceEngine.formatWithCommas(gauge.proRatedGapToday)}):",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMutedDark
                            )
                            Text(
                                text = "${gauge.websitesForTodayGap} websites OR ${gauge.retainersForTodayGap} retainers",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimaryDark
                            )
                        } else {
                            Text(
                                text = "You are ahead of today's pro-rated target!",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TealAccent
                            )
                        }

                        HorizontalDivider(color = BorderDark)

                        Text(
                            text = "To close the remaining full-year gap (₦${LeadCadenceEngine.formatWithCommas(gauge.remainingYearGap)}):",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMutedDark
                        )
                        val mixedWebsites = (gauge.remainingYearGap / (gauge.websitePrice * 2L).coerceAtLeast(1L)).toInt()
                        val remainingAfterMixedWeb = (gauge.remainingYearGap - mixedWebsites * gauge.websitePrice).coerceAtLeast(0L)
                        val mixedRetainers = kotlin.math.ceil(
                            remainingAfterMixedWeb.toDouble() / gauge.retainerPrice.coerceAtLeast(1L).toDouble()
                        ).toInt()
                        Text(
                            text = "${gauge.websitesForYearGap} websites OR ${gauge.retainersForYearGap} retainers",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = TealAccent
                        )
                        if (gauge.remainingYearGap > 0L) {
                            Text(
                                text = "Balanced mix example: $mixedWebsites websites + $mixedRetainers retainer months",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMutedDark
                            )
                        }
                    }
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "QUARTERLY MILESTONES",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
                QuarterRow("Q1 Target", goals.q1Target)
                QuarterRow("Q2 Target", goals.q2Target)
                QuarterRow("Q3 Target", goals.q3Target)
                QuarterRow("Q4 Target", goals.q4Target)
            }
        }
    }
}

@Composable
private fun PaymentsKpiStrip(gauge: RevenueGaugeMetrics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PaymentStatTile(
            label = "Today",
            amount = gauge.todayCash,
            modifier = Modifier.weight(1f)
        )
        PaymentStatTile(
            label = "7 Days",
            amount = gauge.weekCash,
            modifier = Modifier.weight(1f)
        )
        PaymentStatTile(
            label = "Month",
            amount = gauge.monthCash,
            modifier = Modifier.weight(1f)
        )
        PaymentStatTile(
            label = "YTD",
            amount = gauge.ytdCash,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PaymentStatTile(
    label: String,
    amount: Long,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SurfaceDark,
        shape = CardShape8,
        border = BorderStroke(1.dp, BorderDark),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = LeadCadenceEngine.formatCompactNaira(amount),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (amount > 0L) TealAccent else TextPrimaryDark,
                maxLines = 1
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextMutedDark
            )
        }
    }
}

@Composable
private fun PaymentRowCard(
    payment: Payment,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateLabel = remember(payment.paidAt) {
        SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(payment.paidAt))
    }

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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = payment.clientName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimaryDark,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "₦${LeadCadenceEngine.formatWithCommas(payment.amount)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TealAccent
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = buildString {
                        append(payment.type)
                        if (payment.leadId != null) append(" · Linked Lead #${payment.leadId}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )
            }
            if (payment.note.isNotBlank()) {
                Text(
                    text = payment.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onEdit,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Edit", color = TealAccent, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onDelete,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, BorderDark),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Delete", color = WarningRed, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun AddOrEditPaymentDialog(
    existing: Payment?,
    onSaveNew: (clientName: String, rawAmount: String, type: String, note: String, markWon: Boolean) -> Unit,
    onSaveEdited: (Payment) -> Unit,
    onDismiss: () -> Unit
) {
    var clientName by remember(existing?.id) { mutableStateOf(existing?.clientName.orEmpty()) }
    var amountField by remember(existing?.id) { mutableStateOf(existing?.amount?.toString().orEmpty()) }
    var paymentType by remember(existing?.id) {
        mutableStateOf(existing?.type ?: LeadCadenceEngine.PAYMENT_WEBSITE)
    }
    var noteField by remember(existing?.id) { mutableStateOf(existing?.note.orEmpty()) }
    var markWon by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text(
                text = if (existing == null) "Log Payment" else "Edit Payment",
                color = TextPrimaryDark
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = clientName,
                    onValueChange = { clientName = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                    label = { Text("Client Name *") },
                    singleLine = true,
                    shape = CardShape8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceSubtleDark,
                        unfocusedContainerColor = SurfaceSubtleDark,
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        cursorColor = TealAccent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = amountField,
                    onValueChange = { amountField = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                    label = { Text("Amount (e.g. 150k, 80,000, 1.5m) *") },
                    singleLine = true,
                    shape = CardShape8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceSubtleDark,
                        unfocusedContainerColor = SurfaceSubtleDark,
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        cursorColor = TealAccent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LeadCadenceEngine.ALL_PAYMENT_TYPES.forEach { t ->
                        val selected = paymentType == t
                        val shortTitle = if (t == "MAINTENANCE") "MAINT" else t
                        if (selected) {
                            Button(
                                onClick = { paymentType = t },
                                shape = CardShape8,
                                colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(shortTitle, color = BgDark, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { paymentType = t },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, BorderDark),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(shortTitle, color = TextPrimaryDark, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = noteField,
                    onValueChange = { noteField = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    shape = CardShape8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceSubtleDark,
                        unfocusedContainerColor = SurfaceSubtleDark,
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        cursorColor = TealAccent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (existing == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { markWon = !markWon },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = markWon,
                            onCheckedChange = { markWon = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = TealAccent,
                                uncheckedColor = TextMutedDark,
                                checkmarkColor = BgDark
                            )
                        )
                        Text(
                            text = "Also mark matching lead WON",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimaryDark
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (existing == null) {
                        onSaveNew(clientName.trim(), amountField.trim(), paymentType, noteField.trim(), markWon)
                    } else {
                        val parsed = MoneyAndLeadParser.parseNairaAmount(amountField)
                            ?: amountField.replace(",", "").trim().toLongOrNull()
                        if (parsed != null && parsed > 0L && clientName.isNotBlank()) {
                            onSaveEdited(
                                existing.copy(
                                    clientName = clientName.trim(),
                                    amount = parsed,
                                    type = paymentType,
                                    note = noteField.trim()
                                )
                            )
                        }
                    }
                },
                enabled = clientName.isNotBlank() && amountField.isNotBlank(),
                shape = CardShape8,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
            ) {
                Text("Save", color = BgDark, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMutedDark)
            }
        }
    )
}

@Composable
private fun QuarterRow(label: String, amount: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextMutedDark)
        Text(
            text = "₦${String.format(Locale.US, "%,d", amount)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimaryDark
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
