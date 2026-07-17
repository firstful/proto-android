package com.reedsutliff.talaria.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reedsutliff.talaria.model.ChatMessage
import com.reedsutliff.talaria.model.ConnectionState
import com.reedsutliff.talaria.model.HermesSession
import com.reedsutliff.talaria.model.MessageFeedback
import com.reedsutliff.talaria.model.MessageModality
import com.reedsutliff.talaria.model.MessageStatus
import com.reedsutliff.talaria.model.PttButtonState
import com.reedsutliff.talaria.model.TranscriptionState
import com.reedsutliff.talaria.UpdateInfo
import kotlinx.coroutines.launch

// ── Haptic helpers ────────────────────────────────────────────

fun hapticLightClick(context: android.content.Context) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: SecurityException) {
        // VIBRATE permission not available — haptics silently disabled
    }
}

fun hapticHeavyClick(context: android.content.Context) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: SecurityException) {
        // VIBRATE permission not available — haptics silently disabled
    }
}

/**
 * Top-level composable for Talaria.
 * Hosts the TalariaViewModel and assembles the full UI:
 * TopAppBar → ThreadSwitcher → ChatTranscript → TextInputBar/PTTButton.
 *
 * All state lives in [TalariaViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalariaApp(
    viewModel: TalariaViewModel = viewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val pttState by viewModel.pttState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val currentThread by viewModel.currentThread.collectAsState()
    val threadList by viewModel.threadList.collectAsState()
    val inputMode by viewModel.inputMode.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val partialTranscript by viewModel.partialTranscript.collectAsState()
    val liveTranscript by viewModel.liveTranscript.collectAsState()
    val hermesSessions by viewModel.hermesSessions.collectAsState()
    val latencyMs by viewModel.latencyMs.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val updateDownloading by viewModel.updateDownloading.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var showSettings by remember { mutableStateOf(false) }
    var viewingFilePath by remember { mutableStateOf<String?>(null) }
    var viewingFileContent by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // Auto-connect on first composition
    LaunchedEffect(Unit) {
        viewModel.connect()
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose { viewModel.disconnect() }
    }

    // Auto-scroll to bottom when new messages arrive, but only if
    // user is already near the bottom (not reading history)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            val nearBottom = lastVisible == null ||
                lastVisible.index >= messages.size - 3
            if (nearBottom) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    // Check for updates on first composition
    LaunchedEffect(Unit) {
        viewModel.checkForUpdate()
    }

    // ── Connection dot color ──────────────────────────────────
    val dotColor = when (connectionState) {
        ConnectionState.CONNECTED -> Color(0xFF22C55E)
        ConnectionState.CONNECTING -> Color(0xFFEAB308)
        ConnectionState.DISCONNECTED -> Color(0xFFEF4444)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            TalariaDrawerContent(
                threads = threadList,
                currentThreadId = currentThread?.id,
                connectionState = connectionState,
                settings = settings,
                hermesSessions = hermesSessions,
                onSwitchThread = { viewModel.switchThread(it) },
                onCreateThread = { viewModel.createThread("New Thread") },
                onDeleteThread = { viewModel.deleteThread(it) },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    showSettings = true
                },
                onReconnect = { viewModel.connect() },
                onDisconnect = { viewModel.disconnect() },
                onShareLogs = { viewModel.shareLogs(context) },
                onForkSession = { sessionId ->
                    scope.launch { viewModel.forkHermesSession(sessionId) }
                },
            )
        },
    ) {
        if (showSettings) {
            SettingsScreen(
                settings = settings,
                onUpdateSettings = { viewModel.updateSettings(it) },
                onBack = { showSettings = false },
                onCheckForUpdate = { viewModel.checkForUpdate() },
                hermesProfiles = hermesSessions.map { it.title }.distinct(),
            )
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = "Talaria",
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                scope.launch { drawerState.open() }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Open navigation drawer",
                                )
                            }
                        },
                        actions = {
                            // Connection status dot with latency
                            if (connectionState == ConnectionState.CONNECTED && latencyMs > 0) {
                                Text(
                                    text = "${latencyMs}ms",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Spacer(Modifier.width(12.dp))
                            // Playback speed toggle
                            if (pttState == PttButtonState.PLAYING || playbackSpeed != 1.0f) {
                                TextButton(onClick = { viewModel.cyclePlaybackSpeed() }) {
                                    Text(
                                        text = "${playbackSpeed}x",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            // Settings icon
                            IconButton(onClick = {
                                scope.launch { drawerState.open() }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                ) {
                    // ThreadSwitcher chip
                    ThreadSwitcher(
                        threads = threadList,
                        currentThread = currentThread,
                        onSwitchThread = { viewModel.switchThread(it) },
                        onCreateThread = { viewModel.createThread(it) },
                        onDeleteThread = { viewModel.deleteThread(it) },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    Spacer(Modifier.height(8.dp))

                    // Chat transcript
                    ChatTranscript(
                        messages = messages,
                        listState = listState,
                        isThinking = pttState == PttButtonState.PROCESSING,
                        onFilePathClick = { path ->
                            viewingFilePath = path
                            viewingFileContent = null
                            viewModel.readRemoteFile(path) { content ->
                                viewingFileContent = content ?: "Error: Could not read file"
                            }
                        },
                        onFeedback = { msgId, feedback ->
                            viewModel.sendFeedback(msgId, feedback)
                        },
                        modifier = Modifier.weight(1f),
                    )

                    Spacer(Modifier.height(16.dp))

                    // Live on-device transcript preview (during recording)
                    if (pttState == PttButtonState.RECORDING && liveTranscript.isNotEmpty()) {
                        Text(
                            text = liveTranscript,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .animateContentSize(),
                        )
                    }

                    // Audio playback controls (during TTS)
                    val isPaused by viewModel.isPaused.collectAsState()
                    val playbackPosition by viewModel.playbackPosition.collectAsState()
                    val playbackDuration by viewModel.playbackDuration.collectAsState()

                    if (pttState == PttButtonState.PLAYING) {
                        PlaybackControls(
                            isPaused = isPaused,
                            positionMs = playbackPosition,
                            durationMs = playbackDuration,
                            onTogglePause = { viewModel.togglePlayback() },
                            onSeek = { viewModel.seekPlayback(it) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // Input area
                    when (inputMode) {
                        InputMode.VOICE -> {
                            PTTButton(
                                state = pttState,
                                onDown = { viewModel.startPtt() },
                                onUp = { viewModel.stopPtt() },
                            )
                        }
                        InputMode.TEXT -> {
                            TextInputBar(
                                onSend = { viewModel.sendText(it) },
                                onToggleMode = {
                                    viewModel.setInputMode(InputMode.VOICE)
                                },
                                isTextMode = true,
                            )
                        }
                    }

                    // Input mode toggle hint — tappable to switch modes
                    Text(
                        text = when (inputMode) {
                            InputMode.VOICE -> "Tap to type"
                            InputMode.TEXT -> "Hold to talk"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clickable {
                                viewModel.setInputMode(
                                    if (inputMode == InputMode.VOICE) InputMode.TEXT
                                    else InputMode.VOICE
                                )
                            },
                        textAlign = TextAlign.Center,
                    )

                    // Version footer
                    Text(
                        "Talaria v2",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    // ── Update Available Dialog ─────────────────────────────────
    if (updateInfo != null) {
        val info = updateInfo!!
        val currentVersion = com.reedsutliff.talaria.BuildConfig.VERSION_NAME
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdate() },
            title = {
                Text(
                    text = "Update Available",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            },
            text = {
                Column {
                    Text(
                        text = "A new version of Talaria is available.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    // Version info block
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = "Current: $currentVersion",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Latest:   ${info.versionName}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF22C55E),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (info.contentLength > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Size:     ${formatFileSize(info.contentLength)}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.downloadAndInstall(context) },
                    enabled = !updateDownloading,
                ) {
                    if (updateDownloading) {
                        Text(
                            text = "Downloading…",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        )
                    } else {
                        Text(
                            text = "Download & Install",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdate() }) {
                    Text("Later")
                }
            },
        )
    }

    // ── File Viewer Dialog ──────────────────────────────────────
    if (viewingFilePath != null) {
        AlertDialog(
            onDismissRequest = {
                viewingFilePath = null
                viewingFileContent = null
            },
            title = {
                Text(
                    text = viewingFilePath?.substringAfterLast('/') ?: "File",
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
            },
            text = {
                SelectionContainer {
                    Text(
                        text = viewingFileContent ?: "Loading...",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewingFilePath = null
                        viewingFileContent = null
                    }
                ) {
                    Text("Close")
                }
            },
        )
    }
}

// ── Chat Transcript ──────────────────────────────────────────

@Composable
fun ChatTranscript(
    messages: List<ChatMessage>,
    listState: LazyListState,
    isThinking: Boolean = false,
    onFilePathClick: ((String) -> Unit)? = null,
    onFeedback: ((String, MessageFeedback) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(
                    msg,
                    onFilePathClick = onFilePathClick,
                    onFeedback = onFeedback,
                )
            }

            // Thinking indicator as the next chat bubble
            if (isThinking) {
                item(key = "__thinking__") {
                    ThinkingBubble()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    msg: ChatMessage,
    onFilePathClick: ((String) -> Unit)? = null,
    onFeedback: ((String, MessageFeedback) -> Unit)? = null,
) {
    val context = LocalContext.current
    val alignment = if (msg.isUser) Alignment.End else Alignment.Start
    val bgColor = if (msg.isUser)
        Color(0xFF6366F1) else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (msg.isUser) Color.White else MaterialTheme.colorScheme.onSurface

    val timeText = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(msg.timestamp))

    val modalityBadge = when (msg.modality) {
        MessageModality.VOICE -> "\uD83C\uDFA4"
        MessageModality.TEXT -> "\u2328"
    }

    val statusIcon = when (msg.status) {
        MessageStatus.QUEUED -> "\u23F3"
        MessageStatus.SENDING -> "\u25CB"
        MessageStatus.SENT -> "\u2713"
        MessageStatus.REFINING -> "\u2699\uFE0F"
        MessageStatus.DELIVERED -> "\u2713\u2713"
        MessageStatus.ERROR -> "\u274C"
    }
    val statusColor = when (msg.status) {
        MessageStatus.QUEUED -> Color(0xFFF59E0B)
        MessageStatus.SENDING -> Color(0xFF94A3B8)
        MessageStatus.SENT -> Color(0xFF22C55E)
        MessageStatus.REFINING -> Color(0xFF8B5CF6)
        MessageStatus.DELIVERED -> Color(0xFF22C55E)
        MessageStatus.ERROR -> Color(0xFFEF4444)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 2.dp),
        ) {
            Text(
                text = "$modalityBadge ${if (msg.isUser) "You" else "Hermes"}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = " $timeText",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            if (msg.isUser) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = statusIcon,
                    fontSize = 10.sp,
                    color = statusColor,
                )
            }
        }
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .then(
                    if (msg.transcriptionState == TranscriptionState.ON_DEVICE)
                        Modifier.border(
                            width = 1.5.dp,
                            color = Color(0xFF8B5CF6).copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp),
                        )
                    else Modifier
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        hapticLightClick(context)
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val clip = ClipData.newPlainText("message", msg.text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            val displayText = if (msg.interrupted) "${msg.text} \u2702\uFE0F" else msg.text
            Column {
                if (!msg.isUser) {
                    RichContentText(
                        text = displayText,
                        onFilePathClick = onFilePathClick,
                        textColor = textColor,
                    )
                } else {
                    Text(
                        text = displayText,
                        color = textColor,
                        fontSize = 15.sp,
                    )
                }
                // Transcription state hint
                if (msg.isUser && msg.modality == MessageModality.VOICE &&
                    msg.transcriptionState == TranscriptionState.ON_DEVICE
                ) {
                    Text(
                        text = if (msg.status == MessageStatus.REFINING) "⚙️ Refining with Whisper…" else "🎙️ On-device",
                        fontSize = 10.sp,
                        color = Color(0xFF8B5CF6).copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // Feedback buttons for bot messages
        if (!msg.isUser && msg.status == MessageStatus.DELIVERED && onFeedback != null) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        hapticLightClick(context)
                        onFeedback(msg.id, MessageFeedback.THUMBS_UP)
                    },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ThumbUp,
                        contentDescription = "Thumbs up",
                        tint = if (msg.feedback == MessageFeedback.THUMBS_UP)
                            Color(0xFF22C55E)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(14.dp),
                    )
                }
                IconButton(
                    onClick = {
                        hapticLightClick(context)
                        onFeedback(msg.id, MessageFeedback.THUMBS_DOWN)
                    },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ThumbDown,
                        contentDescription = "Thumbs down",
                        tint = if (msg.feedback == MessageFeedback.THUMBS_DOWN)
                            Color(0xFFEF4444)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

// ── Thinking Indicator Bubble ──────────────────────────────────

@Composable
fun ThinkingBubble() {
    val transition = rememberInfiniteTransition(label = "think")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinkAlpha",
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 2.dp),
        ) {
            Text(
                text = "🤔 Hermes",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { i ->
                    val dotDelay = i * 160
                    val dotAlpha by rememberInfiniteTransition(label = "dot$i").animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 480, delayMillis = dotDelay),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dotAlpha$i",
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dotAlpha)),
                    )
                }
            }
        }
    }
}

// ── PTT Button ───────────────────────────────────────────────

@Composable
fun PTTButton(
    state: PttButtonState,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val context = LocalContext.current
    val buttonColor = when (state) {
        PttButtonState.RECORDING -> Color(0xFFEF4444)
        PttButtonState.PROCESSING -> Color(0xFFF59E0B)
        PttButtonState.PLAYING -> Color(0xFF22C55E)
        else -> Color(0xFF6366F1)
    }

    // ── Pulse animation ──────────────────────────────────────
    val isActive = state == PttButtonState.RECORDING ||
            state == PttButtonState.PROCESSING ||
            state == PttButtonState.PLAYING

    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = if (isActive) 0.9f else 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.12f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val labelText = when (state) {
        PttButtonState.IDLE -> "Hold to Talk"
        PttButtonState.RECORDING -> "Recording\u2026"
        PttButtonState.PROCESSING -> "Thinking\u2026"
        PttButtonState.PLAYING -> "Playing\u2026"
        PttButtonState.INTERRUPTED -> "Interrupted"
    }

    // Icon changes with state
    val iconText = when (state) {
        PttButtonState.RECORDING -> "\uD83D\uDD34"   // red circle
        PttButtonState.PROCESSING -> "\u23F3"          // hourglass
        PttButtonState.PLAYING -> "\uD83D\uDD0A"       // speaker
        PttButtonState.INTERRUPTED -> "\u270B"         // raised hand
        else -> "\uD83C\uDFA4"                         // microphone
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(buttonColor)
                .border(3.dp, buttonColor.copy(alpha = pulseAlpha), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            hapticHeavyClick(context)
                            onDown()
                            tryAwaitRelease()
                            hapticLightClick(context)
                            onUp()
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(iconText, fontSize = 32.sp)
        }

        Spacer(Modifier.height(8.dp))

        Text(
            labelText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // Extra visual meter during recording
        if (state == PttButtonState.RECORDING) {
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Animated audio level bars
                val barAlpha by rememberInfiniteTransition(label = "bar").animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 400),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "barAlpha",
                )
                repeat(3) { i ->
                    val delay = i * 133
                    val barAnim by rememberInfiniteTransition(label = "bar$i").animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 400, delayMillis = delay),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "barAnim$i",
                    )
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(20.dp * barAnim)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFFEF4444).copy(alpha = barAnim)),
                    )
                }
            }
        }
    }
}

// ── Audio Playback Controls ───────────────────────────────────

@Composable
fun PlaybackControls(
    isPaused: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePause: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val context = LocalContext.current
    val maxSliderValue = durationMs.coerceAtLeast(1).toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(
                onClick = {
                    hapticLightClick(context)
                    onTogglePause()
                },
                modifier = Modifier.size(32.dp),
            ) {
                Text(
                    text = if (isPaused) "\u25B6" else "\u23F8",
                    fontSize = 16.sp,
                )
            }

            Slider(
                value = positionMs.toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..maxSliderValue,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )

            Text(
                text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}

// ── Theme ────────────────────────────────────────────────────

@Composable
fun TalariaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6366F1),
            onPrimary = Color.White,
            surface = Color(0xFF1C1C1E),
            onSurface = Color(0xFFF2F2F7),
            surfaceVariant = Color(0xFF2C2C2E),
            onSurfaceVariant = Color(0xFFAEAEB2),
            background = Color(0xFF000000),
            onBackground = Color(0xFFF2F2F7),
        ),
        content = content,
    )
}
