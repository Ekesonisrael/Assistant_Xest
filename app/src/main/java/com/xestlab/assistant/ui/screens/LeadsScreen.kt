package com.xestlab.assistant.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xestlab.assistant.data.model.Lead
import com.xestlab.assistant.data.model.LeadEvent
import com.xestlab.assistant.service.DueFollowUpUiModel
import com.xestlab.assistant.service.LeadCadenceEngine
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LeadsScreen(viewModel: AssistantViewModel) {
    val context = LocalContext.current
    val leads by viewModel.leads.collectAsState()
    val allEvents by viewModel.allLeadEvents.collectAsState()
    val dueFollowUps by viewModel.dueFollowUpsToday.collectAsState()
    val needsAttention by viewModel.needsAttentionLeads.collectAsState()

    var selectedLeadId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAddLeadDialog by rememberSaveable { mutableStateOf(false) }
    var observationPromptTarget by remember { mutableStateOf<Pair<Lead, String?>?>(null) }

    val selectedLead = remember(selectedLeadId, leads) {
        selectedLeadId?.let { id -> leads.firstOrNull { it.id == id } }
    }

    if (observationPromptTarget != null) {
        val (targetLead, targetStep) = observationPromptTarget!!
        ObservationPromptDialog(
            leadName = targetLead.name,
            initialObservation = targetLead.observation,
            onConfirmAndOpenWhatsApp = { enteredObs ->
                observationPromptTarget = null
                viewModel.openLeadWhatsAppWithDraft(
                    activityContext = context,
                    lead = targetLead,
                    stepKey = targetStep,
                    updatedObservation = enteredObs
                )
            },
            onDismiss = { observationPromptTarget = null }
        )
    }

    if (showAddLeadDialog) {
        AddLeadDialog(
            onSave = { name, service, phone, observation, source, referredBy, notes, startContacted ->
                viewModel.createLead(
                    name = name,
                    service = service,
                    phone = phone,
                    source = source,
                    referredBy = referredBy,
                    observation = observation,
                    notes = notes,
                    startContactedCadence = startContacted
                )
                showAddLeadDialog = false
            },
            onDismiss = { showAddLeadDialog = false }
        )
    }

    if (selectedLead != null) {
        val leadEvents = remember(selectedLead.id, allEvents) {
            allEvents.filter { it.leadId == selectedLead.id }.sortedByDescending { it.at }
        }
        LeadDetailContent(
            lead = selectedLead,
            events = leadEvents,
            viewModel = viewModel,
            onRequireObservationForDay0 = { l, step -> observationPromptTarget = l to step },
            onBack = { selectedLeadId = null }
        )
        return
    }

    val contactedThisWeekCount = remember(leads, allEvents) {
        val weekStartMs = LeadCadenceEngine.startOfDayMillis(
            System.currentTimeMillis() - 6L * 86_400_000L
        )
        val eventLeadIds = allEvents
            .filter {
                it.at >= weekStartMs &&
                    it.type in setOf("CONTACTED", "MESSAGE_SENT")
            }
            .map { it.leadId }
            .toMutableSet()
        leads.forEach { l ->
            val touched = l.lastContactAt ?: l.createdAt
            if (touched >= weekStartMs && l.stage != LeadCadenceEngine.STAGE_NEW) {
                eventLeadIds.add(l.id)
            }
        }
        eventLeadIds.size
    }
    val repliedCount = remember(leads) { leads.count { it.stage == LeadCadenceEngine.STAGE_REPLIED } }
    val quotedCount = remember(leads) { leads.count { it.stage == LeadCadenceEngine.STAGE_QUOTED } }
    val wonCount = remember(leads) { leads.count { it.stage == LeadCadenceEngine.STAGE_WON } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header + Add Lead Button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LEADS & CADENCE",
                        style = MaterialTheme.typography.labelMedium,
                        color = TealAccent
                    )
                    Text(
                        text = "Pipeline (${leads.size})",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimaryDark
                    )
                }
                Button(
                    onClick = { showAddLeadDialog = true },
                    shape = CardShape8,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                ) {
                    Text("+ Add Lead", color = BgDark, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Top Counter Strip: Contacted this week, Replied, Quoted, Won
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LeadStatPill(
                    label = "Contacted (7d)",
                    value = contactedThisWeekCount.toString(),
                    highlight = contactedThisWeekCount > 0,
                    modifier = Modifier.weight(1f)
                )
                LeadStatPill(
                    label = "Replied",
                    value = repliedCount.toString(),
                    highlight = repliedCount > 0,
                    modifier = Modifier.weight(1f)
                )
                LeadStatPill(
                    label = "Quoted",
                    value = quotedCount.toString(),
                    highlight = quotedCount > 0,
                    modifier = Modifier.weight(1f)
                )
                LeadStatPill(
                    label = "Won",
                    value = wonCount.toString(),
                    highlight = wonCount > 0,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Due Today Follow-Ups
        if (dueFollowUps.isNotEmpty()) {
            item {
                Text(
                    text = "DUE TODAY (${dueFollowUps.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(dueFollowUps, key = { "due_${it.lead.id}_${it.stepKey}" }) { due ->
                DueFollowUpActionCard(
                    due = due,
                    onOpenWhatsApp = {
                        if (due.stepKey == LeadCadenceEngine.STEP_DAY_0_MESSAGE &&
                            due.lead.observation.isBlank()
                        ) {
                            observationPromptTarget = due.lead to due.stepKey
                        } else {
                            viewModel.openLeadWhatsAppWithDraft(
                                activityContext = context,
                                lead = due.lead,
                                stepKey = due.stepKey
                            )
                        }
                    },
                    onCall = { viewModel.callLead(context, due.lead) },
                    onMarkDone = { viewModel.completeDueFollowUp(due) },
                    onSnooze = { viewModel.snoozeDueFollowUp(due) },
                    onReplied = { viewModel.markLeadReplied(due.lead.id) },
                    onLost = { viewModel.markLeadLost(due.lead.id) },
                    onOpenLeadDetail = { selectedLeadId = due.lead.id }
                )
            }
        }

        // Needs Attention (> 7 days with no nextActionAt or stale)
        if (needsAttention.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape8,
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, WarningRed)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "NEEDS ATTENTION (${needsAttention.size}) — OVER 7 DAYS WITHOUT NEXT ACTION",
                            style = MaterialTheme.typography.labelMedium,
                            color = WarningRed,
                            fontWeight = FontWeight.SemiBold
                        )
                        needsAttention.forEach { staleLead ->
                            val daysAgo = (((System.currentTimeMillis() - (staleLead.lastContactAt ?: staleLead.createdAt))
                                .coerceAtLeast(0L)) / 86_400_000L)
                            Surface(
                                color = SurfaceSubtleDark,
                                shape = CardShape8,
                                border = BorderStroke(1.dp, BorderDark),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedLeadId = staleLead.id }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${staleLead.name} (${staleLead.stage})",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimaryDark
                                        )
                                        Text(
                                            text = "${staleLead.service} · Last touched ${daysAgo}d ago",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TextMutedDark
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.markLeadContacted(staleLead.id) },
                                        shape = CardShape8,
                                        border = BorderStroke(1.dp, TealAccent),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("Restart Cadence", color = TealAccent, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (leads.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape8,
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No leads yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "Tap '+ Add Lead', say \"add lead Tola, website, 0803...\" on Home, add from an unread email card, or import your web tracker JSON in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMutedDark
                        )
                    }
                }
            }
        } else {
            LeadCadenceEngine.ALL_STAGES.forEach { stage ->
                val stageLeads = leads.filter { it.stage == stage }
                if (stageLeads.isNotEmpty()) {
                    item(key = "header_$stage") {
                        Text(
                            text = "$stage (${stageLeads.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = when (stage) {
                                LeadCadenceEngine.STAGE_WON -> TealAccent
                                LeadCadenceEngine.STAGE_LOST -> TextMutedDark
                                else -> TextPrimaryDark
                            },
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(stageLeads, key = { "lead_${it.id}" }) { lead ->
                        LeadSummaryCard(
                            lead = lead,
                            onClick = { selectedLeadId = lead.id },
                            onMarkContacted = { viewModel.markLeadContacted(lead.id) },
                            onOpenWhatsApp = {
                                val step = lead.nextActionType ?: LeadCadenceEngine.STEP_DAY_0_MESSAGE
                                if (step == LeadCadenceEngine.STEP_DAY_0_MESSAGE && lead.observation.isBlank()) {
                                    observationPromptTarget = lead to step
                                } else {
                                    viewModel.openLeadWhatsAppWithDraft(
                                        activityContext = context,
                                        lead = lead,
                                        stepKey = step
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LeadStatPill(
    label: String,
    value: String,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SurfaceDark,
        shape = CardShape8,
        border = BorderStroke(1.dp, if (highlight) TealAccent else BorderDark),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (highlight) TealAccent else TextPrimaryDark
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextMutedDark,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DueFollowUpActionCard(
    due: DueFollowUpUiModel,
    onOpenWhatsApp: () -> Unit,
    onCall: () -> Unit,
    onMarkDone: () -> Unit,
    onSnooze: () -> Unit,
    onReplied: () -> Unit,
    onLost: () -> Unit,
    onOpenLeadDetail: (() -> Unit)? = null
) {
    val dueClock = remember(due.dueAt) {
        SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(due.dueAt))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onOpenLeadDetail != null) {
                    Modifier.clickable { onOpenLeadDetail() }
                } else {
                    Modifier
                }
            ),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, TealAccent)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${due.lead.stage} · ${due.lead.service}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = dueClock,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )
            }

            Text(
                text = due.displayLine,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimaryDark
            )

            if (due.lead.observation.isNotBlank()) {
                Text(
                    text = "Observation: ${due.lead.observation}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark,
                    maxLines = 2
                )
            } else if (due.stepKey == LeadCadenceEngine.STEP_DAY_0_MESSAGE) {
                Text(
                    text = "Day 0 rule: Add 1 specific observation before sending first message.",
                    style = MaterialTheme.typography.labelMedium,
                    color = WarningRed
                )
            }

            // Primary Row: Open WhatsApp with draft + Call
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenWhatsApp,
                    shape = CardShape8,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text(
                        text = "Open WhatsApp with draft",
                        style = MaterialTheme.typography.labelMedium,
                        color = BgDark,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OutlinedButton(
                    onClick = onCall,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(0.6f)
                ) {
                    Text(
                        text = "Call",
                        style = MaterialTheme.typography.labelMedium,
                        color = TealAccent
                    )
                }
            }

            // Secondary Row: Mark done, Snooze, Replied, Lost
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onMarkDone,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Mark done", style = MaterialTheme.typography.labelMedium, color = TealAccent)
                }
                OutlinedButton(
                    onClick = onSnooze,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, BorderDark),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Snooze", style = MaterialTheme.typography.labelMedium, color = TextPrimaryDark)
                }
                OutlinedButton(
                    onClick = onReplied,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Replied", style = MaterialTheme.typography.labelMedium, color = TealAccent)
                }
                OutlinedButton(
                    onClick = onLost,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, BorderDark),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Lost", style = MaterialTheme.typography.labelMedium, color = WarningRed)
                }
            }
        }
    }
}

@Composable
private fun LeadSummaryCard(
    lead: Lead,
    onClick: () -> Unit,
    onMarkContacted: () -> Unit,
    onOpenWhatsApp: () -> Unit
) {
    val nextDueStr = remember(lead.nextActionAt, lead.nextActionType) {
        if (lead.nextActionAt != null) {
            val dateStr = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault()).format(Date(lead.nextActionAt))
            "${LeadCadenceEngine.stepShortLabel(lead.nextActionType)} · $dateStr"
        } else {
            null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
                    text = lead.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimaryDark,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = lead.service,
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
            }

            val metaParts = buildList {
                if (lead.phone.isNotBlank()) add(lead.phone)
                if (lead.quoteAmount != null) add("Quote: ₦${LeadCadenceEngine.formatWithCommas(lead.quoteAmount)}")
                if (lead.source.isNotBlank()) add("Source: ${lead.source}")
            }
            if (metaParts.isNotEmpty()) {
                Text(
                    text = metaParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )
            }

            if (lead.observation.isNotBlank()) {
                Text(
                    text = "Obs: ${lead.observation}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark,
                    maxLines = 1
                )
            }

            if (nextDueStr != null) {
                Text(
                    text = "Next: $nextDueStr",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (lead.stage == LeadCadenceEngine.STAGE_NEW) {
                    Button(
                        onClick = onMarkContacted,
                        shape = CardShape8,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "Mark Contacted (Start Cadence)",
                            style = MaterialTheme.typography.labelMedium,
                            color = BgDark,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (lead.stage != LeadCadenceEngine.STAGE_WON && lead.stage != LeadCadenceEngine.STAGE_LOST) {
                    OutlinedButton(
                        onClick = onOpenWhatsApp,
                        shape = CardShape8,
                        border = BorderStroke(1.dp, TealAccent),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("WhatsApp Draft", style = MaterialTheme.typography.labelMedium, color = TealAccent)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun LeadDetailContent(
    lead: Lead,
    events: List<LeadEvent>,
    viewModel: AssistantViewModel,
    onRequireObservationForDay0: (Lead, String?) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    var nameField by remember(lead.id, lead.name) { mutableStateOf(lead.name) }
    var serviceField by remember(lead.id, lead.service) { mutableStateOf(lead.service) }
    var phoneField by remember(lead.id, lead.phone) { mutableStateOf(lead.phone) }
    var observationField by remember(lead.id, lead.observation) { mutableStateOf(lead.observation) }
    var sourceField by remember(lead.id, lead.source) { mutableStateOf(lead.source) }
    var referredByField by remember(lead.id, lead.referredBy) { mutableStateOf(lead.referredBy) }
    var notesField by remember(lead.id, lead.notes) { mutableStateOf(lead.notes) }

    var draftPreview by remember(lead.id) { mutableStateOf("") }
    var isDraftLoading by remember { mutableStateOf(false) }
    var historyNoteInput by remember { mutableStateOf("") }
    var showQuoteDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val activeStep = lead.nextActionType ?: when (lead.stage) {
        LeadCadenceEngine.STAGE_REPLIED -> LeadCadenceEngine.STEP_SEND_QUOTE
        LeadCadenceEngine.STAGE_QUOTED -> LeadCadenceEngine.STEP_QUOTE_DAY_2
        else -> LeadCadenceEngine.STEP_DAY_0_MESSAGE
    }

    LaunchedEffect(lead.id, lead.stage, lead.observation, lead.quoteAmount, activeStep) {
        viewModel.loadDraftPreview(
            lead = lead,
            stepKey = activeStep,
            useAiRewrite = false
        ) { loaded ->
            draftPreview = loaded
        }
    }

    if (showQuoteDialog) {
        LogQuoteAmountDialog(
            leadName = lead.name,
            initialAmount = lead.quoteAmount?.toString().orEmpty(),
            onSaveQuote = { rawAmount, quoteNote ->
                viewModel.logLeadQuote(lead.id, rawAmount, quoteNote)
                showQuoteDialog = false
            },
            onDismiss = { showQuoteDialog = false }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = SurfaceDark,
            title = { Text("Delete ${lead.name}?", color = TextPrimaryDark) },
            text = {
                Text(
                    "This deletes the lead, its event history, and cancels any scheduled follow-up alarms.",
                    color = TextMutedDark
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteLead(lead)
                        onBack()
                    }
                ) {
                    Text("Delete", color = WarningRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextPrimaryDark)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .imePadding()
    ) {
        Surface(
            color = SurfaceDark,
            border = BorderStroke(1.dp, BorderDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${lead.stage} · ${lead.service}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TealAccent
                    )
                    Text(
                        text = lead.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimaryDark
                    )
                }
                OutlinedButton(
                    onClick = onBack,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent)
                ) {
                    Text("Back", color = TealAccent)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Stage & Cadence Actions Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape8,
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, TealAccent)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "STAGE & AUTOMATIC CADENCE",
                            style = MaterialTheme.typography.labelMedium,
                            color = TealAccent
                        )
                        val nextInfo = if (lead.nextActionAt != null) {
                            val fmt = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault())
                                .format(Date(lead.nextActionAt))
                            "Next scheduled: ${LeadCadenceEngine.stepShortLabel(lead.nextActionType)} at $fmt"
                        } else {
                            "No active cadence alarm scheduled"
                        }
                        Text(
                            text = nextInfo,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimaryDark
                        )
                        if (lead.quoteAmount != null) {
                            Text(
                                text = "Logged Quote: ₦${LeadCadenceEngine.formatWithCommas(lead.quoteAmount)}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TealAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.markLeadContacted(lead.id) },
                                shape = CardShape8,
                                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                            ) {
                                Text("Mark Contacted", color = BgDark, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = { viewModel.markLeadReplied(lead.id) },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, TealAccent)
                            ) {
                                Text("Replied (Quote 24h)", color = TealAccent)
                            }
                            Button(
                                onClick = { showQuoteDialog = true },
                                shape = CardShape8,
                                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                            ) {
                                Text("Log Quote Amount", color = BgDark, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = { viewModel.markLeadWon(lead.id) },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, TealAccent)
                            ) {
                                Text("Won ✓", color = TealAccent)
                            }
                            OutlinedButton(
                                onClick = { viewModel.markLeadLost(lead.id) },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, BorderDark)
                            ) {
                                Text("Lost", color = WarningRed)
                            }
                        }
                    }
                }
            }

            // WhatsApp Draft & Call Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape8,
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "DRAFT (${LeadCadenceEngine.stepShortLabel(activeStep).uppercase(Locale.US)})",
                                style = MaterialTheme.typography.labelMedium,
                                color = TealAccent
                            )
                            TextButton(
                                onClick = {
                                    isDraftLoading = true
                                    viewModel.loadDraftPreview(
                                        lead = lead.copy(observation = observationField),
                                        stepKey = activeStep,
                                        useAiRewrite = true
                                    ) { generated ->
                                        draftPreview = generated
                                        isDraftLoading = false
                                    }
                                }
                            ) {
                                Text(
                                    text = if (isDraftLoading) "Rewriting..." else "AI Rewrite",
                                    color = TealAccent
                                )
                            }
                        }

                        LeadFocusableTextField(
                            value = draftPreview,
                            onValueChange = { draftPreview = it },
                            label = "WhatsApp Draft (You tap Send in WhatsApp)",
                            singleLine = false,
                            minLines = 3
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val obsCandidate = observationField.trim().ifBlank { lead.observation.trim() }
                                    if (activeStep == LeadCadenceEngine.STEP_DAY_0_MESSAGE && obsCandidate.isBlank()) {
                                        onRequireObservationForDay0(lead, activeStep)
                                    } else {
                                        viewModel.openLeadWhatsAppWithDraft(
                                            activityContext = context,
                                            lead = lead,
                                            stepKey = activeStep,
                                            updatedObservation = obsCandidate,
                                            customDraftOverride = draftPreview
                                        )
                                    }
                                },
                                shape = CardShape8,
                                colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                                modifier = Modifier.weight(1.4f)
                            ) {
                                Text(
                                    "Open WhatsApp with draft",
                                    color = BgDark,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.callLead(context, lead.copy(phone = phoneField.trim()))
                                },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, TealAccent),
                                modifier = Modifier.weight(0.6f)
                            ) {
                                Text("Call", color = TealAccent)
                            }
                        }
                    }
                }
            }

            // Editable Lead Profile Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape8,
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "LEAD DETAILS",
                            style = MaterialTheme.typography.labelMedium,
                            color = TealAccent
                        )
                        LeadFocusableTextField(
                            value = nameField,
                            onValueChange = { nameField = it },
                            label = "Name *"
                        )

                        Text(
                            text = "Service *",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMutedDark
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LeadCadenceEngine.ALL_SERVICES.forEach { svc ->
                                val selected = serviceField == svc
                                if (selected) {
                                    Button(
                                        onClick = { serviceField = svc },
                                        shape = CardShape8,
                                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(svc, color = BgDark, fontWeight = FontWeight.SemiBold)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { serviceField = svc },
                                        shape = CardShape8,
                                        border = BorderStroke(1.dp, BorderDark),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(svc, color = TextPrimaryDark)
                                    }
                                }
                            }
                        }

                        LeadFocusableTextField(
                            value = phoneField,
                            onValueChange = { phoneField = it },
                            label = "Phone (e.g. 0803...)"
                        )
                        LeadFocusableTextField(
                            value = observationField,
                            onValueChange = { observationField = it },
                            label = "Observation (1 specific thing about their page/site)",
                            singleLine = false,
                            minLines = 2
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LeadFocusableTextField(
                                value = sourceField,
                                onValueChange = { sourceField = it },
                                label = "Source",
                                modifier = Modifier.weight(1f)
                            )
                            LeadFocusableTextField(
                                value = referredByField,
                                onValueChange = { referredByField = it },
                                label = "Referred By",
                                modifier = Modifier.weight(1f)
                            )
                        }
                        LeadFocusableTextField(
                            value = notesField,
                            onValueChange = { notesField = it },
                            label = "Notes",
                            singleLine = false,
                            minLines = 2
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showDeleteConfirm = true }) {
                                Text("Delete Lead", color = WarningRed)
                            }
                            Button(
                                onClick = {
                                    if (nameField.isNotBlank()) {
                                        viewModel.updateLeadDetails(
                                            lead.copy(
                                                name = nameField.trim(),
                                                service = serviceField,
                                                phone = phoneField.trim(),
                                                observation = observationField.trim(),
                                                source = sourceField.trim(),
                                                referredBy = referredByField.trim(),
                                                notes = notesField.trim()
                                            )
                                        )
                                    }
                                },
                                shape = CardShape8,
                                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                            ) {
                                Text("Save Details", color = BgDark, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Lead History Timeline
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape8,
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "FULL LEAD HISTORY (${events.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = TealAccent
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LeadFocusableTextField(
                                value = historyNoteInput,
                                onValueChange = { historyNoteInput = it },
                                label = "Log interaction note...",
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (historyNoteInput.isNotBlank()) {
                                        viewModel.addLeadNote(lead.id, historyNoteInput)
                                        historyNoteInput = ""
                                    }
                                },
                                shape = CardShape8,
                                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                            ) {
                                Text("Log", color = BgDark, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        events.forEach { ev ->
                            val stamp = remember(ev.at) {
                                SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(ev.at))
                            }
                            Surface(
                                color = SurfaceSubtleDark,
                                shape = CardShape8,
                                border = BorderStroke(1.dp, BorderDark),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = ev.type,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TealAccent,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = stamp,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMutedDark
                                        )
                                    }
                                    Text(
                                        text = ev.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextPrimaryDark
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ObservationPromptDialog(
    leadName: String,
    initialObservation: String = "",
    onConfirmAndOpenWhatsApp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var obsDraft by remember(initialObservation) { mutableStateOf(initialObservation) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text(
                text = "First Message Rule — Add Observation",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimaryDark
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Day 0 outreach to $leadName must include one specific thing you noticed about their page or website.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )
                OutlinedTextField(
                    value = obsDraft,
                    onValueChange = { obsDraft = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                    placeholder = {
                        Text(
                            "e.g. your Instagram bio has no direct menu or booking link",
                            color = TextMutedDark
                        )
                    },
                    minLines = 2,
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (obsDraft.trim().isNotEmpty()) {
                        onConfirmAndOpenWhatsApp(obsDraft.trim())
                    }
                },
                enabled = obsDraft.trim().isNotEmpty(),
                shape = CardShape8,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
            ) {
                Text("Save & Open WhatsApp", color = BgDark, fontWeight = FontWeight.SemiBold)
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
private fun LogQuoteAmountDialog(
    leadName: String,
    initialAmount: String,
    onSaveQuote: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var amountInput by remember(initialAmount) { mutableStateOf(initialAmount) }
    var noteInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text("Log Quote for $leadName", color = TextPrimaryDark)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Marks lead QUOTED and schedules Day 2 and Day 5 follow-up alarms.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
                    label = { Text("Quote Amount (e.g. 250k or 250,000)") },
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
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                    label = { Text("Optional scope / note") },
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
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveQuote(amountInput, noteInput) },
                enabled = amountInput.isNotBlank(),
                shape = CardShape8,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
            ) {
                Text("Save Quote", color = BgDark, fontWeight = FontWeight.SemiBold)
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
private fun AddLeadDialog(
    onSave: (
        name: String,
        service: String,
        phone: String,
        observation: String,
        source: String,
        referredBy: String,
        notes: String,
        startContacted: Boolean
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var service by remember { mutableStateOf(LeadCadenceEngine.SERVICE_WEBSITE) }
    var phone by remember { mutableStateOf("") }
    var observation by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var referredBy by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var startContacted by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text("Add Lead", color = TextPrimaryDark)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Only Name and Service are required.",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
                    label = { Text("Lead Name *") },
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LeadCadenceEngine.ALL_SERVICES.forEach { svc ->
                        val selected = service == svc
                        if (selected) {
                            Button(
                                onClick = { service = svc },
                                shape = CardShape8,
                                colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(svc, color = BgDark, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { service = svc },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, BorderDark),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(svc, color = TextPrimaryDark, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                    label = { Text("Phone (optional, e.g. 0803...)") },
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
                    value = observation,
                    onValueChange = { observation = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                    label = { Text("Observation (1 specific thing noticed)") },
                    singleLine = false,
                    maxLines = 2,
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = source,
                        onValueChange = { source = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                        label = { Text("Source") },
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
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = referredBy,
                        onValueChange = { referredBy = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                        label = { Text("Referred By") },
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
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { startContacted = !startContacted },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = startContacted,
                        onCheckedChange = { startContacted = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = TealAccent,
                            uncheckedColor = TextMutedDark,
                            checkmarkColor = BgDark
                        )
                    )
                    Text(
                        text = "Mark CONTACTED & start Day 0/2/5/10 cadence now",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimaryDark
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.trim().isNotEmpty()) {
                        onSave(
                            name.trim(),
                            service,
                            phone.trim(),
                            observation.trim(),
                            source.trim(),
                            referredBy.trim(),
                            notes.trim(),
                            startContacted
                        )
                    }
                },
                enabled = name.trim().isNotEmpty(),
                shape = CardShape8,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
            ) {
                Text("Save Lead", color = BgDark, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMutedDark)
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LeadFocusableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
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
                    scope.launch {
                        delay(180)
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
    )
}
