package com.xestlab.assistant.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

@Composable
fun MemoryDialog(
    viewModel: AssistantViewModel,
    onDismiss: () -> Unit
) {
    val facts by viewModel.allFacts.collectAsState()
    var newFactText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = CardShape8,
        title = {
            Text("Assistant Memory Facts", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Facts remembered from 'Remember that...' voice commands or added manually.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedDark
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newFactText,
                        onValueChange = { newFactText = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
                        placeholder = { Text("Add fact...", color = TextMutedDark) },
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
                    Button(
                        onClick = {
                            if (newFactText.isNotBlank()) {
                                viewModel.addFact(newFactText)
                                newFactText = ""
                            }
                        },
                        shape = CardShape8,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                    ) {
                        Text("Add", color = BgDark, fontWeight = FontWeight.SemiBold)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (facts.isEmpty()) {
                        item {
                            Text(
                                text = "No stored facts.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMutedDark
                            )
                        }
                    } else {
                        items(facts, key = { it.id }) { fact ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = CardShape8,
                                colors = CardDefaults.cardColors(containerColor = SurfaceSubtleDark),
                                border = BorderStroke(1.dp, BorderDark)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = fact.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                                    )
                                    Text(
                                        text = "Remove",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = WarningRed,
                                        modifier = Modifier.clickable { viewModel.deleteFact(fact) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = CardShape8,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
            ) {
                Text("Done", color = BgDark, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            if (facts.isNotEmpty()) {
                OutlinedButton(
                    onClick = { viewModel.clearAllFacts() },
                    shape = CardShape8,
                    border = BorderStroke(1.dp, WarningRed)
                ) {
                    Text("Clear All", color = WarningRed)
                }
            }
        }
    )
}
