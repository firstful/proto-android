package com.reedsutliff.talaria.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reedsutliff.talaria.model.Thread
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A clickable chip showing the current thread name and a dropdown arrow.
 * Tapping opens a ModalBottomSheet listing all threads with swipe-to-delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadSwitcher(
    threads: List<Thread>,
    currentThread: Thread?,
    onSwitchThread: (String) -> Unit,
    onCreateThread: (String) -> Unit,
    onDeleteThread: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    val threadName = currentThread?.name ?: "No thread"

    // ── Chip ──────────────────────────────────────────────────
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { showSheet = true }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = "💬 $threadName",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = "Open thread list",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(0.dp),
        )
    }

    // ── Bottom Sheet ──────────────────────────────────────────
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            BottomSheetContent(
                threads = threads,
                currentThread = currentThread,
                onSwitchThread = { id ->
                    onSwitchThread(id)
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        showSheet = false
                    }
                },
                onCreateThread = onCreateThread,
                onDeleteThread = onDeleteThread,
            )
        }
    }
}

// ── Bottom Sheet Content ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomSheetContent(
    threads: List<Thread>,
    currentThread: Thread?,
    onSwitchThread: (String) -> Unit,
    onCreateThread: (String) -> Unit,
    onDeleteThread: (String) -> Unit,
) {
    var showNewThreadDialog by remember { mutableStateOf(false) }
    var deleteConfirmThreadId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
    ) {
        // Title
        Text(
            text = "Threads",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // "+ New Thread" button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showNewThreadDialog = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New thread",
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "＋ New Thread",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Thread list
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(threads.filter { !it.isArchived }, key = { it.id }) { thread ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { dismissValue ->
                        if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                            deleteConfirmThreadId = thread.id
                            false // Don't actually dismiss — show confirmation first
                        } else {
                            false
                        }
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = true,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    MaterialTheme.colorScheme.errorContainer,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete thread",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    },
                    content = {
                        ThreadRow(
                            thread = thread,
                            isSelected = thread.id == currentThread?.id,
                            onTap = { onSwitchThread(thread.id) },
                        )
                    },
                )
            }
        }
    }

    // ── New Thread Dialog ─────────────────────────────────────
    if (showNewThreadDialog) {
        var threadName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewThreadDialog = false },
            title = { Text("New Thread") },
            text = {
                OutlinedTextField(
                    value = threadName,
                    onValueChange = { threadName = it },
                    singleLine = true,
                    placeholder = { Text("Thread name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = threadName.trim()
                        if (name.isNotBlank()) {
                            onCreateThread(name)
                        }
                        showNewThreadDialog = false
                    },
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewThreadDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Delete Confirmation Dialog ────────────────────────────
    if (deleteConfirmThreadId != null) {
        val threadId = deleteConfirmThreadId!!
        AlertDialog(
            onDismissRequest = { deleteConfirmThreadId = null },
            title = { Text("Delete Thread") },
            text = { Text("Are you sure you want to delete this thread? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteThread(threadId)
                        deleteConfirmThreadId = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmThreadId = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Thread Row ────────────────────────────────────────────────

@Composable
private fun ThreadRow(
    thread: Thread,
    isSelected: Boolean,
    onTap: () -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.US) }
    val bgColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    else
        MaterialTheme.colorScheme.surface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable { onTap() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thread.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!thread.lastMessagePreview.isNullOrBlank()) {
                Text(
                    text = thread.lastMessagePreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = timeFmt.format(Date(thread.lastMessageTimestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
