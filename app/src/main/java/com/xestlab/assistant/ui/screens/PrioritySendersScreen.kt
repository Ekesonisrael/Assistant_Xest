@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.xestlab.assistant.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xestlab.assistant.data.model.EmailSenderRuleEntity
import com.xestlab.assistant.data.model.learnedKeywordsList
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

@Composable
fun PrioritySendersScreen(
    viewModel: AssistantViewModel,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    val senderRules by viewModel.senderRules.collectAsState()
    val customTags by viewModel.customEmailTags.collectAsState()

    var senderInput by rememberSaveable { mutableStateOf("") }
    var displayNameInput by rememberSaveable { mutableStateOf("") }
    var selectedPriority by rememberSaveable { mutableStateOf("HIGH") }
    var editingSenderKey by rememberSaveable { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val senderBringIntoView = remember { BringIntoViewRequester() }
    val nameBringIntoView = remember { BringIntoViewRequester() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Priority Senders & Rules",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimaryDark
                    )
                    Text(
                        text = "Edit, raise, or lower any sender and remove learned keywords or custom tags.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMutedDark
                    )
                }
                OutlinedButton(
                    onClick = onClose,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    modifier = Modifier.padding(start = 12.dp)
                ) {
                    Text("Back", color = TealAccent)
                }
            }
        }

        // Add or Edit Sender Rule Card
        item {
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
                        text = if (editingSenderKey != null) {
                            "EDIT SENDER RULE"
                        } else {
                            "ADD OR UPDATE SENDER PRIORITY"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TealAccent
                    )

                    OutlinedTextField(
                        value = senderInput,
                        onValueChange = { senderInput = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
                        label = { Text("Sender Email or Name") },
                        placeholder = { Text("ceo@client.com", color = TextMutedDark) },
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
                            .bringIntoViewRequester(senderBringIntoView)
                            .onFocusEvent { state ->
                                if (state.isFocused) {
                                    coroutineScope.launch {
                                        delay(160)
                                        senderBringIntoView.bringIntoView()
                                    }
                                }
                            }
                    )

                    OutlinedTextField(
                        value = displayNameInput,
                        onValueChange = { displayNameInput = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
                        label = { Text("Display Name (Optional)") },
                        placeholder = { Text("Client Name", color = TextMutedDark) },
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
                            .bringIntoViewRequester(nameBringIntoView)
                            .onFocusEvent { state ->
                                if (state.isFocused) {
                                    coroutineScope.launch {
                                        delay(160)
                                        nameBringIntoView.bringIntoView()
                                    }
                                }
                            }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("HIGH", "NORMAL", "LOW").forEach { level ->
                            val selected = selectedPriority == level
                            if (selected) {
                                Button(
                                    onClick = { selectedPriority = level },
                                    shape = CardShape8,
                                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(level, color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { selectedPriority = level },
                                    shape = CardShape8,
                                    border = BorderStroke(1.dp, BorderDark),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(level, color = TextPrimaryDark)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (senderInput.isNotBlank()) {
                                    viewModel.saveOrUpdateSenderRule(
                                        senderInput = senderInput,
                                        displayName = displayNameInput,
                                        priority = selectedPriority
                                    )
                                    senderInput = ""
                                    displayNameInput = ""
                                    editingSenderKey = null
                                }
                            },
                            shape = CardShape8,
                            colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (editingSenderKey != null) "Update Sender" else "Save Sender Rule",
                                color = TextPrimaryDark,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (editingSenderKey != null) {
                            OutlinedButton(
                                onClick = {
                                    editingSenderKey = null
                                    senderInput = ""
                                    displayNameInput = ""
                                },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, BorderDark)
                            ) {
                                Text("Cancel", color = TextMutedDark)
                            }
                        }
                    }
                }
            }
        }

        // Custom Tags Management Card
        if (customTags.isNotEmpty()) {
            item {
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
                            text = "CUSTOM EMAIL TAGS (${customTags.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = TealAccent
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            customTags.forEach { customTag ->
                                Surface(
                                    color = SurfaceSubtleDark,
                                    shape = CardShape8,
                                    border = BorderStroke(1.dp, BorderDark),
                                    modifier = Modifier.clickable {
                                        viewModel.deleteCustomEmailTag(customTag.tagName)
                                    }
                                ) {
                                    Text(
                                        text = "#${customTag.tagName}  ✕",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextPrimaryDark,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Learned & Manual Sender Rules Header
        item {
            Text(
                text = "SENDERS & LEARNED RULES (${senderRules.size})",
                style = MaterialTheme.typography.labelMedium,
                color = TealAccent
            )
            if (senderRules.isEmpty()) {
                Text(
                    text = "No sender rules yet. Add a sender above, or tag / add emails to tasks on Home so the assistant learns your priorities.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        items(senderRules, key = { it.senderKey }) { rule ->
            SenderRuleRowCard(
                rule = rule,
                onSetPriority = { newPriority -> viewModel.setSenderRulePriority(rule, newPriority) },
                onEdit = {
                    editingSenderKey = rule.senderKey
                    senderInput = rule.senderAddress.ifBlank { rule.senderKey }
                    displayNameInput = rule.senderDisplayName
                    selectedPriority = if (rule.priorityOverride in setOf("HIGH", "NORMAL", "LOW")) {
                        rule.priorityOverride
                    } else {
                        "HIGH"
                    }
                },
                onDeleteRule = { viewModel.deleteSenderRule(rule) },
                onRemoveKeyword = { kw -> viewModel.removeLearnedKeyword(rule, kw) },
                onClearKeywords = { viewModel.clearLearnedKeywords(rule) }
            )
        }
    }
}

@Composable
private fun SenderRuleRowCard(
    rule: EmailSenderRuleEntity,
    onSetPriority: (String) -> Unit,
    onEdit: () -> Unit,
    onDeleteRule: () -> Unit,
    onRemoveKeyword: (String) -> Unit,
    onClearKeywords: () -> Unit
) {
    val keywords = rule.learnedKeywordsList
    val titleText = rule.senderDisplayName.ifBlank { rule.senderAddress.ifBlank { rule.senderKey } }
    val subtitleText = if (rule.senderAddress.isNotBlank() && !rule.senderAddress.equals(titleText, ignoreCase = true)) {
        rule.senderAddress
    } else {
        rule.senderKey
    }
    val badgeColor = when (rule.priorityOverride) {
        "HIGH" -> TealAccent
        "LOW" -> WarningRed
        else -> TextPrimaryDark
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderDark)
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimaryDark
                    )
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMutedDark
                    )
                }
                Surface(
                    color = if (rule.priorityOverride == "HIGH") TealAccentContainer else SurfaceSubtleDark,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, badgeColor)
                ) {
                    Text(
                        text = rule.priorityOverride,
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            val statsParts = buildList {
                if (rule.hasEmailedBefore) add("Emailed by you")
                if (rule.positiveCount > 0) add("Tasks/Tags: ${rule.positiveCount}")
                if (rule.dismissCount > 0) add("Dismissed: ${rule.dismissCount}")
            }
            if (statsParts.isNotEmpty()) {
                Text(
                    text = statsParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMutedDark
                )
            }

            if (keywords.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Learned keywords (tap to remove):",
                        style = MaterialTheme.typography.labelMedium,
                        color = TealAccent
                    )
                    Text(
                        text = "Clear all",
                        style = MaterialTheme.typography.labelMedium,
                        color = WarningRed,
                        modifier = Modifier.clickable { onClearKeywords() }
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    keywords.forEach { kw ->
                        Surface(
                            color = SurfaceSubtleDark,
                            shape = CardShape8,
                            border = BorderStroke(1.dp, BorderDark),
                            modifier = Modifier.clickable { onRemoveKeyword(kw) }
                        ) {
                            Text(
                                text = "$kw  ✕",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimaryDark,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Raise / Lower Priority Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("HIGH" to "Raise High", "NORMAL" to "Normal", "LOW" to "Lower Low").forEach { (level, label) ->
                    val isCurrent = rule.priorityOverride == level
                    if (isCurrent) {
                        Button(
                            onClick = { onSetPriority(level) },
                            shape = CardShape8,
                            colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "$level ✓",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimaryDark,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSetPriority(level) },
                            shape = CardShape8,
                            border = BorderStroke(1.dp, BorderDark),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimaryDark
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, TealAccent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Edit Sender", style = MaterialTheme.typography.labelMedium, color = TealAccent)
                }
                OutlinedButton(
                    onClick = onDeleteRule,
                    shape = CardShape8,
                    border = BorderStroke(1.dp, BorderDark),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Remove Rule", style = MaterialTheme.typography.labelMedium, color = WarningRed)
                }
            }
        }
    }
}
