package com.xestlab.assistant.ui.screens

import androidx.compose.foundation.border
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
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.ui.theme.Shapes8px
import com.xestlab.assistant.ui.theme.TealAccent
import com.xestlab.assistant.ui.theme.WarningRed
import com.xestlab.assistant.ui.viewmodel.AssistantViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NotesScreen(viewModel: AssistantViewModel) {
    val searchQuery by viewModel.notesSearchQuery.collectAsState()
    val notes by viewModel.searchNotes.collectAsState()

    var showAddNoteDialog by remember { mutableStateOf(false) }
    var activeReminderNote by remember { mutableStateOf<Item?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Notes & Thoughts",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Searchable, spoken aloud, with reminder attachments.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Button(
                onClick = { showAddNoteDialog = true },
                shape = Shapes8px.small,
                colors = ButtonDefaults.buttonColors(containerColor = TealAccent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("+ New Note", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.setNotesSearch(it) },
            placeholder = { Text("Search notes...") },
            modifier = Modifier.fillMaxWidth(),
            shape = Shapes8px.small,
            singleLine = true
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isNotBlank()) "No notes matching '$searchQuery'." else "No notes saved yet. Say 'note: ...' on Home or tap '+ New Note'.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(notes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onReadAloud = { viewModel.readItemAloud(note) },
                        onAttachReminder = { activeReminderNote = note },
                        onDelete = { viewModel.deleteItem(note) }
                    )
                }
            }
        }
    }

    // Add Note Dialog
    if (showAddNoteDialog) {
        var titleInput by remember { mutableStateOf("") }
        var detailsInput by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddNoteDialog = false },
            title = { Text("New Note", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Title") },
                        shape = Shapes8px.small,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = detailsInput,
                        onValueChange = { detailsInput = it },
                        label = { Text("Details (optional)") },
                        shape = Shapes8px.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (titleInput.isNotBlank()) {
                            viewModel.handleUniversalInput("note: $titleInput ${detailsInput.trim()}", isSpoken = false)
                            showAddNoteDialog = false
                        }
                    },
                    shape = Shapes8px.small,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showAddNoteDialog = false },
                    shape = Shapes8px.small
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Attach Reminder Dialog
    if (activeReminderNote != null) {
        var reminderTimeInput by remember { mutableStateOf("16:00") }
        val targetNote = activeReminderNote!!

        AlertDialog(
            onDismissRequest = { activeReminderNote = null },
            title = { Text("Attach Reminder to Note", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Set a time for the assistant to remind and read this note aloud:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reminderTimeInput,
                        onValueChange = { reminderTimeInput = it },
                        label = { Text("Time (HH:mm)") },
                        shape = Shapes8px.small,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.attachReminderToNote(targetNote, reminderTimeInput)
                        activeReminderNote = null
                    },
                    shape = Shapes8px.small,
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent)
                ) {
                    Text("Set Reminder")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { activeReminderNote = null },
                    shape = Shapes8px.small
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun NoteCard(
    note: Item,
    onReadAloud: () -> Unit,
    onAttachReminder: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(note.createdAt) {
        SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(note.createdAt))
    }
    val reminderStr = remember(note.dueAt) {
        if (note.dueAt != null) {
            SimpleDateFormat("dd MMM at HH:mm", Locale.getDefault()).format(Date(note.dueAt))
        } else null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = Shapes8px,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = note.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = dateStr,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            if (note.details.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = note.details,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            }

            if (reminderStr != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = Shapes8px.small,
                    color = TealAccent.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "Reminder: $reminderStr",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TealAccent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onReadAloud,
                    shape = Shapes8px.small,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onSurface),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Read aloud", fontSize = 11.sp)
                }

                Button(
                    onClick = onAttachReminder,
                    shape = Shapes8px.small,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onSurface),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (reminderStr != null) "Edit reminder" else "+ Reminder", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = onDelete,
                    shape = Shapes8px.small,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Delete", fontSize = 11.sp, color = WarningRed)
                }
            }
        }
    }
}
