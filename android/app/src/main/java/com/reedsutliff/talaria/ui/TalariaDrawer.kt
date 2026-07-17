package com.reedsutliff.talaria.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reedsutliff.talaria.model.AppSettings
import com.reedsutliff.talaria.model.ConnectionState
import com.reedsutliff.talaria.model.HermesSession
import com.reedsutliff.talaria.model.Thread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Content for the modal navigation drawer.
 *
 * Displays the app header, connection status with controls, a scrollable thread
 * list with swipe-to-delete, recent Hermes sessions, and a Settings navigation item.
 *
 * Designed to be passed as the [drawerContent] parameter of
 * [androidx.compose.material3.ModalNavigationDrawer].
 */
@Composable
fun TalariaDrawerContent(
    threads: List<Thread>,
    currentThreadId: String?,
    connectionState: ConnectionState,
    settings: AppSettings,
    hermesSessions: List<HermesSession> = emptyList(),
    onSwitchThread: (String) -> Unit,
    onCreateThread: () -> Unit,
    onDeleteThread: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onShareLogs: () -> Unit,
    onForkSession: ((String) -> Unit)? = null,
) {
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
    ) {
        DrawerHeader()

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        ConnectionSection(
            connectionState = connectionState,
            onReconnect = onReconnect,
            onDisconnect = onDisconnect,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        ThreadsSection(
            threads = threads,
            currentThreadId = currentThreadId,
            onSwitchThread = onSwitchThread,
            onCreateThread = onCreateThread,
            onDeleteThread = onDeleteThread,
        )

        if (hermesSessions.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            RecentSessionsSection(
                sessions = hermesSessions,
                onForkSession = onForkSession,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        SettingsSection(
            onOpenSettings = onOpenSettings,
            onShareLogs = onShareLogs,
        )
    }
}

// ── Header ────────────────────────────────────────────────────

@Composable
private fun DrawerHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text(
            text = "Talaria",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "v${com.reedsutliff.talaria.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

// ── Connection ─────────────────────────────────────────────────

@Composable
private fun ConnectionSection(
    connectionState: ConnectionState,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val dotColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.CONNECTED -> Color(0xFF22C55E)
            ConnectionState.CONNECTING -> Color(0xFFEAB308)
            ConnectionState.DISCONNECTED -> Color(0xFFEF4444)
        },
        label = "connDot",
    )

    val statusText = when (connectionState) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.CONNECTING -> "Connecting…"
        ConnectionState.DISCONNECTED -> "Disconnected"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // Status row
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onReconnect,
                enabled = connectionState != ConnectionState.CONNECTED,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF22C55E).copy(alpha = 0.15f),
                    contentColor = Color(0xFF22C55E),
                    disabledContainerColor = Color(0xFF22C55E).copy(alpha = 0.06f),
                    disabledContentColor = Color(0xFF22C55E).copy(alpha = 0.3f),
                ),
            ) {
                Text("Reconnect", fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = onDisconnect,
                enabled = connectionState != ConnectionState.DISCONNECTED,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF4444).copy(alpha = 0.15f),
                    contentColor = Color(0xFFEF4444),
                    disabledContainerColor = Color(0xFFEF4444).copy(alpha = 0.06f),
                    disabledContentColor = Color(0xFFEF4444).copy(alpha = 0.3f),
                ),
            ) {
                Text("Disconnect", fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── Threads ────────────────────────────────────────────────────

@Composable
private fun ThreadsSection(
    threads: List<Thread>,
    currentThreadId: String?,
    onSwitchThread: (String) -> Unit,
    onCreateThread: () -> Unit,
    onDeleteThread: (String) -> Unit,
) {
    val activeThreads = threads.filter { !it.isArchived }

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Section header
        Text(
            text = "Threads",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )

        if (activeThreads.isEmpty()) {
            Text(
                text = "No threads yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            ) {
                items(
                    items = activeThreads,
                    key = { it.id },
                ) { thread ->
                    SwipeableThreadRow(
                        thread = thread,
                        isSelected = thread.id == currentThreadId,
                        onClick = { onSwitchThread(thread.id) },
                        onDelete = { onDeleteThread(thread.id) },
                    )
                }
            }
        }

        // "New Thread" button
        TextButton(
            onClick = onCreateThread,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New Thread",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text("New Thread", fontWeight = FontWeight.Medium)
        }
    }
}

// ── Swipeable thread row ───────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableThreadRow(
    thread: Thread,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                false // Don't actually dismiss — handled by callback
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFEF4444).copy(alpha = 0.85f))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.White,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
    ) {
        val bgColor = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            Color.Transparent
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thread icon
            Text(
                text = "💬",
                fontSize = 16.sp,
            )

            Spacer(Modifier.width(12.dp))

            // Thread info
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = thread.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val preview = thread.lastMessagePreview
                if (!preview.isNullOrBlank()) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Timestamp
            val timeText = SimpleDateFormat("HH:mm", Locale.US)
                .format(Date(thread.lastMessageTimestamp))
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

// ── Recent Sessions ───────────────────────────────────────────

@Composable
private fun RecentSessionsSection(
    sessions: List<HermesSession>,
    onForkSession: ((String) -> Unit)?,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Recent Sessions",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        sessions.take(5).forEach { session ->
            NavigationDrawerItem(
                icon = {
                    Text(
                        text = session.sourceEmoji,
                        fontSize = 16.sp,
                    )
                },
                label = {
                    Column {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = session.displaySource,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                        )
                    }
                },
                selected = false,
                onClick = {
                    onForkSession?.invoke(session.id)
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}

// ── Settings ───────────────────────────────────────────────────

@Composable
private fun SettingsSection(
    onOpenSettings: () -> Unit,
    onShareLogs: () -> Unit,
) {
    Column {
        NavigationDrawerItem(
            icon = {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                )
            },
            label = { Text("Settings") },
            selected = false,
            onClick = onOpenSettings,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )

        // Share logs button
        NavigationDrawerItem(
            icon = {
                Icon(
                    imageVector = Icons.Default.IosShare,
                    contentDescription = "Share Logs",
                )
            },
            label = { Text("Share Logs") },
            selected = false,
            onClick = onShareLogs,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
    }
}
