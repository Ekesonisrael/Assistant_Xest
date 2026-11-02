@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.xestlab.assistant.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotesScreen(viewModel: AssistantViewModel) {
    val searchQuery by viewModel.noteSearchQuery.collectAsState()
    val notes by viewModel.filteredNotes.collectAsState()
    var quickNoteInput by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val searchBringIntoView = remember { BringIntoViewRequester() }
    val addNoteBringIntoView = remember { BringIntoViewRequester() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .imePadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Captured Notes",
                style = MaterialTheme.typography.headlineMedium
            )
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setNoteSearchQuery(it) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
                placeholder = { Text("Search notes...", color = TextMutedDark) },
                singleLine = true,
                shape = CardShape8,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextPrimaryDark,
                    unfocusedTextColor = TextPrimaryDark,
                    focusedContainerColor = SurfaceSubtleDark,
                    unfocusedContainerColor = SurfaceSubtleDark,
                    cursorColor = TealAccent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(searchBringIntoView)
                    .onFocusEvent { focusState ->
                        if (focusState.isFocused) {
                            scope.launch {
                                delay(120L)
                                searchBringIntoView.bringIntoView()
                            }
                        }
                    }
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = quickNoteInput,
                    onValueChange = { quickNoteInput = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimaryDark),
                    placeholder = { Text("Add a quick note...", color = TextMutedDark) },
                    singleLine = true,
                    shape = CardShape8,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (quickNoteInput.isNotBlank()) {
                                viewModel.handleUniversalInput("note: $quickNoteInput", isSpoken = false)
                                quickNoteInput = ""
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark,
                        focusedContainerColor = SurfaceSubtleDark,
                        unfocusedContainerColor = SurfaceSubtleDark,
                        cursorColor = TealAccent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .bringIntoViewRequester(addNoteBringIntoView)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                scope.launch {
                                    delay(120L)
                                    addNoteBringIntoView.bringIntoView()
                                }
                            }
                        }
                )
                Button(
                    onClick = {
                        if (quickNoteInput.isNotBlank()) {
                            viewModel.handleUniversalInput("note: $quickNoteInput", isSpoken = false)
                            quickNoteInput = ""
                        }
                    },
                    shape = CardShape8,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("Save", color = BgDark, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (notes.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape8,
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, BorderDark)
                ) {
                    Text(
                        text = "No matching notes yet. Speak or type 'Note: ...' on Home or save one above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMutedDark,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(notes, key = { it.id }) { note ->
                val createdStr = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
                    .format(Date(note.createdAt))
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
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = note.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = createdStr,
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMutedDark
                            )
                        }
                        if (note.details.isNotBlank() && note.details != note.title) {
                            Text(
                                text = note.details,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMutedDark
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.speakText(
                                        note.details.ifBlank { note.title }
                                    )
                                },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, TealAccent)
                            ) {
                                Text("Read Aloud", color = TealAccent)
                            }
                            OutlinedButton(
                                onClick = { viewModel.attachReminderToNote(note, 60) },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, BorderDark)
                            ) {
                                Text("Remind +1h", color = TextPrimaryDark)
                            }
                            OutlinedButton(
                                onClick = { viewModel.deleteItem(note) },
                                shape = CardShape8,
                                border = BorderStroke(1.dp, BorderDark)
                            ) {
                                Text("Delete", color = WarningRed)
                            }
                        }
                    }
                }
            }
        }
    }
}
