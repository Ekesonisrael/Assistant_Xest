package com.xestlab.assistant.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xestlab.assistant.data.model.MessageTemplateEntity
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
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MessageTemplatesScreen(
    viewModel: AssistantViewModel,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    val storedTemplates by viewModel.messageTemplates.collectAsState()
    val effectiveTemplates = remember(storedTemplates) {
        if (storedTemplates.isNotEmpty()) {
            storedTemplates.sortedBy { it.orderIndex }
        } else {
            LeadCadenceEngine.defaultTemplates()
        }
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
                        text = "MESSAGE TEMPLATES",
                        style = MaterialTheme.typography.labelMedium,
                        color = TealAccent
                    )
                    Text(
                        text = "Offline Cadence Drafts",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimaryDark
                    )
                }
                OutlinedButton(
                    onClick = onClose,
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
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape8,
                    colors = CardDefaults.cardColors(containerColor = TealAccentContainer),
                    border = BorderStroke(1.dp, TealAccent)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "AVAILABLE PLACEHOLDERS",
                            style = MaterialTheme.typography.labelMedium,
                            color = TealAccent,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "{name} · {service} · {observation} · {quoteAmount}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimaryDark,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Offline: placeholders are filled automatically. With AI on: Groq rewrites each draft in a short, friendly Nigerian business tone using the lead's observation. Day 0 always enforces the lead's observation.",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMutedDark
                        )
                        OutlinedButton(
                            onClick = { viewModel.resetMessageTemplates() },
                            shape = CardShape8,
                            border = BorderStroke(1.dp, BorderDark),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text("Reset All to Defaults", color = TextPrimaryDark)
                        }
                    }
                }
            }

            items(effectiveTemplates, key = { it.stepKey }) { template ->
                MessageTemplateEditorCard(
                    template = template,
                    onSave = { updatedBody ->
                        viewModel.saveMessageTemplate(template.copy(bodyTemplate = updatedBody.trim()))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageTemplateEditorCard(
    template: MessageTemplateEntity,
    onSave: (String) -> Unit
) {
    var bodyDraft by remember(template.stepKey, template.bodyTemplate) {
        mutableStateOf(template.bodyTemplate)
    }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val isDay0 = template.stepKey == LeadCadenceEngine.STEP_DAY_0_MESSAGE
    val isModified = bodyDraft.trim() != template.bodyTemplate.trim()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape8,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, if (isModified) TealAccent else BorderDark)
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
                    text = template.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimaryDark,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = template.stepKey,
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
            }

            if (isDay0 && !bodyDraft.contains("{observation}", ignoreCase = true)) {
                Text(
                    text = "Tip: Include {observation} so Day 0 weaves your specific observation smoothly.",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealAccent
                )
            }

            OutlinedTextField(
                value = bodyDraft,
                onValueChange = { bodyDraft = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                label = { Text("Template Text") },
                minLines = 3,
                maxLines = 6,
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
                            scope.launch {
                                delay(180)
                                bringIntoViewRequester.bringIntoView()
                            }
                        }
                    }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = {
                        if (bodyDraft.isNotBlank()) {
                            onSave(bodyDraft)
                        }
                    },
                    enabled = bodyDraft.isNotBlank(),
                    shape = CardShape8,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                ) {
                    Text(
                        text = if (isModified) "Save Template" else "Saved ✓",
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
