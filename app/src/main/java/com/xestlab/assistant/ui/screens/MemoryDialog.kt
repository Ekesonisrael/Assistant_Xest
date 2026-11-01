package com.xestlab.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xestlab.assistant.data.model.Fact
import com.xestlab.assistant.ui.theme.Shapes8px
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.WarningRed
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel

@Composable
fun MemoryDialog(
    viewModel: AssistantViewModel,
    onDismiss: () -> Unit
) {
    val facts by viewModel.allFacts.collectAsState()
    var newFactInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Assistant Memory (Facts)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (facts.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearAllFacts() }) {
                        Text("Clear all", fontSize = 11.sp, color = WarningRed)
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                Text(
                    text = "Things you asked the assistant to remember ('remember that...'). Injected into AI context to personalize responses.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newFactInput,
                        onValueChange = { newFactInput = it },
                        placeholder = { Text("Add fact manually...") },
                        modifier = Modifier.weight(1f),
                        shape = Shapes8px.small,
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newFactInput.isNotBlank()) {
                                viewModel.handleUniversalInput("remember that ${newFactInput.trim()}", isSpoken = false)
                                newFactInput = ""
                            }
                        },
                        shape = Shapes8px.small,
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                    ) {
                        Text("Add")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (facts.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No facts remembered yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(facts, key = { it.id }) { fact ->
                            FactRow(
                                fact = fact,
                                onDelete = { viewModel.deleteFact(fact) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = Shapes8px.small,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun FactRow(fact: Fact, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = Shapes8px.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(fact.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "Category: ${fact.category}",
                    fontSize = 10.sp,
                    color = TealAccent
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Text("✕", color = WarningRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
