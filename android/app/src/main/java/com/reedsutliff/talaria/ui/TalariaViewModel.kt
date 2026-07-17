package com.reedsutliff.talaria.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reedsutliff.talaria.TalariaApplication
import com.reedsutliff.talaria.LogBuffer
import com.reedsutliff.talaria.UpdateInfo
import com.reedsutliff.talaria.UpdateManager
import com.reedsutliff.talaria.data.AppDatabase
import com.reedsutliff.talaria.data.entities.MessageEntity
import com.reedsutliff.talaria.data.entities.ThreadEntity
import com.reedsutliff.talaria.data.preferences.TalariaPreferences
import com.reedsutliff.talaria.data.repository.SettingsRepository
import com.reedsutliff.talaria.data.repository.ThreadRepository
import com.reedsutliff.talaria.model.AppSettings
import com.reedsutliff.talaria.model.ChatMessage
import com.reedsutliff.talaria.model.ConnectionState
import com.reedsutliff.talaria.model.HermesSession
import com.reedsutliff.talaria.model.MessageFeedback
import com.reedsutliff.talaria.model.MessageModality
import com.reedsutliff.talaria.model.MessageStatus
import com.reedsutliff.talaria.model.PttButtonState
import com.reedsutliff.talaria.model.Thread
import com.reedsutliff.talaria.model.TranscriptionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

// ── Constants ─────────────────────────────────────────────────

private const val LOG_TAG = "TalariaVM"
private const val SAMPLE_RATE = 16000
private const val AUDIO_BUFFER_SIZE = 4096
private const val MAX_RECONNECT_DELAY_MS = 30_000L
private const val BASE_RECONNECT_DELAY_MS = 1_000L
private const val SERVER_ERROR_BASE_DELAY_MS = 5_000L
private const val SERVER_ERROR_MAX_DELAY_MS = 60_000L

// ── Input mode ────────────────────────────────────────────────

enum class InputMode { TEXT, VOICE }

// ── ViewModel ─────────────────────────────────────────────────

class TalariaViewModel(application: Application) : AndroidViewModel(application) {

    // ── StateFlows ──────────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pttState = MutableStateFlow(PttButtonState.IDLE)
    val pttState: StateFlow<PttButtonState> = _pttState.asStateFlow()

    private val _threadList = MutableStateFlow<List<Thread>>(emptyList())
    val threadList: StateFlow<List<Thread>> = _threadList.asStateFlow()

    private val _currentThread = MutableStateFlow<Thread?>(null)
    val currentThread: StateFlow<Thread?> = _currentThread.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _inputMode = MutableStateFlow(InputMode.VOICE)
    val inputMode: StateFlow<InputMode> = _inputMode.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _hermesSessions = MutableStateFlow<List<HermesSession>>(emptyList())
    val hermesSessions: StateFlow<List<HermesSession>> = _hermesSessions.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _playbackDuration = MutableStateFlow(0L)
    val playbackDuration: StateFlow<Long> = _playbackDuration.asStateFlow()

    /** Live on-device transcription preview (not a message — visual only).
     *  Updated by SpeechRecognizer partial results, cleared on stopRecording.
     *  The server's stt_transcript creates the actual voice message. */
    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    // ── Update management ────────────────────────────────────
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _updateDownloading = MutableStateFlow(false)
    val updateDownloading: StateFlow<Boolean> = _updateDownloading.asStateFlow()

    // ── Internal ────────────────────────────────────────────

    private var wsClient: TalariaWebSocketClient? = null
    private var sessionId: String? = null
    private val appContext get() = getApplication<Application>()

    // ── Data layer ──────────────────────────────────────────

    private val db = AppDatabase.getInstance(appContext)
    private val threadDao = db.threadDao()
    private val messageDao = db.messageDao()
    private val threadRepository = ThreadRepository(threadDao, messageDao)
    private val settingsRepository = SettingsRepository(TalariaPreferences(appContext))
    private var messagesJob: Job? = null

    /** Monotonically-increasing generation counter for loadMessagesForThread.
     *  When a new load is initiated, the old generation is invalidated so a
     *  stale coroutine that completes after the new one started won't overwrite
     *  the state. */
    private val loadGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    // ── Offline message queue ──────────────────────────────────
    private data class PendingMessage(
        val id: String,
        val threadId: String,
        val text: String,
        val sessionId: String,
    )
    /** Thread-safe offline queues (accessed from main thread, audio thread,
     *  and OkHttp WebSocket callback thread concurrently). */
    private val pendingMessages = java.util.Collections.synchronizedList(
        mutableListOf<PendingMessage>()
    )

    private data class PendingAudioMessage(
        val id: String,
        val threadId: String,
        val audioData: ByteArray,
        val sessionId: String,
    )
    private val pendingAudioMessages = java.util.Collections.synchronizedList(
        mutableListOf<PendingAudioMessage>()
    )

    // Serialises thread creation so concurrent calls from sendText/startPtt
    // before init completes all return the same default thread.
    private val defaultThreadMutex = java.util.concurrent.locks.ReentrantLock()

    init {
        // Load persisted settings from DataStore (reactive)
        viewModelScope.launch {
            settingsRepository.settings.collect { _settings.value = it }
        }

        // Load persisted threads from Room and set up current thread
        // on the first emission.  Using the collect here only for the
        // initial thread-list population — messages are loaded via
        // one-shot query (not reactive) to avoid the race where a
        // Room Flow emission overwrites in-memory messages that were
        // added optimistically but not yet persisted.
        viewModelScope.launch {
            threadRepository.allThreads.collect { entities ->
                val threads = entities.map { it.toModel() }
                _threadList.value = threads

                // First emission: pick the current thread
                if (_currentThread.value == null) {
                    val lastId = _settings.value.lastThreadId
                    val thread = if (lastId != null) {
                        threads.find { it.id == lastId }
                    } else {
                        threads.firstOrNull()
                    }
                    if (thread != null) {
                        _currentThread.value = thread
                        loadMessagesForThread(thread.id)
                    } else {
                        createDefaultThread()
                    }
                }
            }
        }
    }

    // ── Public API ──────────────────────────────────────────

    fun connect() {
        Log.i(LOG_TAG, "connect()")
        val s = _settings.value
        wsClient?.disconnect()
        wsClient = TalariaWebSocketClient(
            wsUrl = s.serverUrl,
            cfClientId = s.cfClientId,
            cfClientSecret = s.cfClientSecret,
        ).also { it.connect() }
    }

    fun disconnect() {
        Log.i(LOG_TAG, "disconnect()")
        wsClient?.disconnect()
        wsClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        val thread = _currentThread.value ?: run {
            val t = createDefaultThread()
            t
        }

        val messageId = UUID.randomUUID().toString()
        val isConnected = _connectionState.value == ConnectionState.CONNECTED

        val message = ChatMessage(
            id = messageId,
            threadId = thread.id,
            text = text.trim(),
            isUser = true,
            modality = MessageModality.TEXT,
            status = if (isConnected) MessageStatus.SENDING else MessageStatus.QUEUED,
        )

        // Add message to local list immediately (instant UI update)
        _messages.value = _messages.value + message

        // Update thread preview
        val updatedThread = thread.copy(
            lastMessagePreview = text.trim().take(100),
            lastMessageTimestamp = System.currentTimeMillis(),
        )
        _threadList.value = _threadList.value.map {
            if (it.id == thread.id) updatedThread else it
        }

        // Persist to Room in one sequential block (thread first, then message)
        viewModelScope.launch {
            threadDao.insert(updatedThread.toEntity())
            messageDao.insert(message.toEntity())
        }

        if (isConnected) {
            // Send via WebSocket immediately with client_message_id for tracking
            wsClient?.sendMessage(
                JSONObject().apply {
                    put("type", "text_input")
                    put("thread_id", thread.id)
                    put("session_id", sessionId ?: "")
                    put("text", text.trim())
                    put("client_message_id", messageId)
                }.toString()
            )
            Log.i(LOG_TAG, "sendText: ${text.take(50)}...")
        } else {
            // Queue for later delivery when connection resumes
            pendingMessages.add(PendingMessage(messageId, thread.id, text.trim(), sessionId ?: ""))
            Log.i(LOG_TAG, "sendText: queued (offline) — ${text.take(50)}...")
        }
    }

    fun startPtt() {
        Log.i(LOG_TAG, "startPtt() — conn=${_connectionState.value}")
        val connected = _connectionState.value == ConnectionState.CONNECTED

        // If bot is speaking or thinking, interrupt and start recording.
        // No /steer text_input — the interrupt cancels the current run, and
        // the new PTT recording starts a fresh run. Sending /steer as text_input
        // would start a NEW Hermes run (double response bug).
        val botActive = _pttState.value == PttButtonState.PROCESSING ||
                _pttState.value == PttButtonState.PLAYING
        if (botActive) {
            interrupt()
            // Small delay for the interrupt to propagate before recording
            viewModelScope.launch {
                delay(200)
                doStartPttRecording()
            }
        } else {
            doStartPttRecording()
        }
    }

    private fun doStartPttRecording() {
        val connected = _connectionState.value == ConnectionState.CONNECTED
        Log.i(LOG_TAG, "doStartPttRecording() — conn=$connected")

        val messageId = UUID.randomUUID().toString()
        val thread = _currentThread.value ?: run {
            val t = createDefaultThread()
            t
        }

        // Create placeholder voice message for on-device STT preview (italic/temporary)
        // Updated by SpeechRecognizer partials, replaced by server stt_transcript.
        val msg = ChatMessage(
            id = messageId,
            threadId = thread.id,
            text = "",
            isUser = true,
            modality = MessageModality.VOICE,
            status = MessageStatus.SENDING,
            transcriptionState = TranscriptionState.ON_DEVICE,
        )
        _messages.value = _messages.value + msg
        viewModelScope.launch {
            threadDao.insert(thread.toEntity())
            messageDao.insert(msg.toEntity())
        }

        _liveTranscript.value = ""

        wsClient?.startRecording(messageId, connected)
    }

    fun stopPtt() {
        if (_pttState.value == PttButtonState.RECORDING) {
            Log.i(LOG_TAG, "stopPtt()")
            wsClient?.stopRecording()
        }
    }

    fun interrupt() {
        Log.i(LOG_TAG, "interrupt()")
        wsClient?.interrupt()
    }

    fun switchThread(threadId: String) {
        Log.i(LOG_TAG, "switchThread($threadId)")
        val thread = _threadList.value.find { it.id == threadId } ?: return
        _currentThread.value = thread
        sessionId = thread.sessionId

        // Update lastThreadId in settings
        updateSettings(_settings.value.copy(lastThreadId = threadId))

        // Send session switch to server if connected
        wsClient?.sendMessage(JSONObject().apply {
            put("type", "switch_session")
            put("session_id", sessionId ?: "")
        }.toString())

        loadMessagesForThread(threadId)
    }

    fun createThread(name: String): Thread {
        val thread = Thread(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Thread ${_threadList.value.size + 1}" },
        )
        _threadList.value = _threadList.value + thread

        // Persist to Room
        viewModelScope.launch {
            threadDao.insert(thread.toEntity())
        }

        Log.i(LOG_TAG, "createThread: ${thread.name} (${thread.id})")

        // Auto-switch to new thread
        switchThread(thread.id)
        return thread
    }

    fun deleteThread(threadId: String) {
        val thread = _threadList.value.find { it.id == threadId } ?: return
        Log.i(LOG_TAG, "deleteThread: ${thread.name}")

        // Soft-delete: mark archived
        _threadList.value = _threadList.value.map {
            if (it.id == threadId) it.copy(isArchived = true) else it
        }

        // Persist delete to Room
        viewModelScope.launch {
            messageDao.deleteByThreadId(threadId)
            threadDao.delete(thread.toEntity())
        }

        // If deleting the current thread, switch to another
        if (_currentThread.value?.id == threadId) {
            val next = _threadList.value.firstOrNull { !it.isArchived }
            if (next != null) {
                switchThread(next.id)
            } else {
                _currentThread.value = null
                _messages.value = emptyList()
            }
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings

        // Persist to DataStore
        viewModelScope.launch {
            settingsRepository.update(newSettings)
        }

        Log.i(LOG_TAG, "updateSettings: serverUrl=${newSettings.serverUrl}")
    }

    fun setInputMode(mode: InputMode) {
        _inputMode.value = mode
    }

    /**
     * Generates a combined log report (recent in-memory logs + crash files)
     * and launches the system share sheet. The report is written to a temp
     * file in cacheDir and shared via FileProvider content URI.
     *
     * @param context Any context (typically the Activity) used to start the intent.
     */
    fun shareLogs(context: Context) {
        Log.i(LOG_TAG, "Share logs requested")
        val app = getApplication<TalariaApplication>()
        app.shareLogs(context)
    }

    // ── Update management ──────────────────────────────────────

    /**
     * Check install.ffilt.us for a newer APK version.
     * Updates [_updateInfo] with the result — null means up to date.
     */
    fun checkForUpdate() {
        viewModelScope.launch {
            try {
                val info = UpdateManager.checkForUpdate()
                _updateInfo.value = info
            } catch (e: Exception) {
                Log.e(LOG_TAG, "checkForUpdate failed: ${e.message}")
            }
        }
    }

    /**
     * Download the APK and launch the system installer.
     * Called when the user taps "Download & Install" in the update dialog.
     */
    fun downloadAndInstall(context: Context) {
        viewModelScope.launch {
            try {
                _updateDownloading.value = true
                val apkFile = UpdateManager.downloadApk(context.applicationContext)
                UpdateManager.installApk(context.applicationContext, apkFile)
                _updateInfo.value = null  // dismiss dialog after launching installer
            } catch (e: Exception) {
                Log.e(LOG_TAG, "downloadAndInstall failed: ${e.message}")
            } finally {
                _updateDownloading.value = false
            }
        }
    }

    /**
     * Dismiss the update dialog (user tapped "Later").
     */
    fun dismissUpdate() {
        _updateInfo.value = null
    }

    // ── Internal helpers ────────────────────────────────────

    private fun createDefaultThread(): Thread {
        // Guard against concurrent calls (sendText / startPtt racing with init).
        // If the current thread is already set, return it directly rather than
        // creating a duplicate "General" thread.
        defaultThreadMutex.lock()
        try {
            _currentThread.value?.let { return it }

            val thread = Thread(name = "General")
            _threadList.value = listOf(thread)
            _currentThread.value = thread
            // Persist to Room so subsequent message inserts don't violate FK
            viewModelScope.launch {
                threadDao.insert(thread.toEntity())
            }
            // Load messages (thread is new, so this will be empty)
            loadMessagesForThread(thread.id)
            return thread
        } finally {
            defaultThreadMutex.unlock()
        }
    }

    private fun loadMessagesForThread(threadId: String) {
        messagesJob?.cancel()
        val gen = loadGeneration.incrementAndGet()
        messagesJob = viewModelScope.launch {
            // One-shot load — NOT a persistent Flow. All subsequent mutations
            // update _messages in-memory directly (optimistic UI) and persist
            // to Room asynchronously. This avoids the race where a Room Flow
            // emission overwrites messages that were added in-memory but not
            // yet written to Room.
            val entities = threadRepository.getMessagesOnce(threadId)
            // Only apply if this generation is still current (guards against
            // stale coroutine arriving after a newer switchThread call).
            if (loadGeneration.get() == gen) {
                _messages.value = entities.map { it.toModel() }
            }
        }
        Log.i(LOG_TAG, "loadMessagesForThread($threadId)")
    }

    // ── Playback speed ───────────────────────────────────────

    fun cyclePlaybackSpeed() {
        val speeds = floatArrayOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val current = _playbackSpeed.value
        val idx = speeds.indexOfFirst { it == current }
        _playbackSpeed.value = speeds[(idx + 1) % speeds.size]
        wsClient?.applySpeed(_playbackSpeed.value)
        viewModelScope.launch {
            _settings.value = _settings.value.copy(playbackSpeed = _playbackSpeed.value)
            settingsRepository.update(_settings.value)
        }
        Log.i(LOG_TAG, "Playback speed: ${_playbackSpeed.value}x")
    }

    fun togglePlayback() {
        wsClient?.togglePause()
    }

    fun seekPlayback(positionMs: Long) {
        wsClient?.seekTo(positionMs)
    }

    // ── Hermes sessions ──────────────────────────────────────

    fun listHermesSessions(limit: Int = 20) {
        wsClient?.sendMessage(JSONObject().apply {
            put("type", "list_sessions")
            put("limit", limit)
        }.toString())
    }

    fun forkHermesSession(sessionId: String) {
        wsClient?.sendMessage(JSONObject().apply {
            put("type", "fork_session")
            put("session_id", sessionId)
        }.toString())
    }

    fun getSessionHistory(sessionId: String) {
        wsClient?.sendMessage(JSONObject().apply {
            put("type", "get_session_history")
            put("session_id", sessionId)
            put("limit", 50)
        }.toString())
    }

    fun setProfile(profile: String) {
        wsClient?.sendMessage(JSONObject().apply {
            put("type", "set_profile")
            put("profile", profile)
        }.toString())
        viewModelScope.launch {
            _settings.value = _settings.value.copy(selectedProfile = profile)
            settingsRepository.update(_settings.value)
        }
    }

    // ── Message feedback ──────────────────────────────────────

    fun sendFeedback(messageId: String, feedback: MessageFeedback) {
        // Update local message state
        _messages.value = _messages.value.map {
            if (it.id == messageId) it.copy(feedback = feedback) else it
        }
        // Persist to Room
        viewModelScope.launch {
            val msg = _messages.value.find { it.id == messageId }
            if (msg != null) {
                messageDao.insert(msg.toEntity())
            }
        }
        // Send to server
        wsClient?.sendMessage(JSONObject().apply {
            put("type", "feedback")
            put("client_message_id", messageId)
            put("feedback", feedback.name.lowercase())
        }.toString())
        Log.i(LOG_TAG, "sendFeedback: $messageId → $feedback")
    }

    // ── File reading ─────────────────────────────────────────

    fun readRemoteFile(path: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val url = _settings.value.serverUrl.replace("wss://", "https://").replace("ws://", "http://")
                    .replace("/v1/ptt", "")
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder()
                    .url("$url/api/file?path=$path")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    onResult(json.optString("content", null))
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "readRemoteFile failed: ${e.message}")
                onResult(null)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        messagesJob?.cancel()
        wsClient?.disconnect()
        wsClient = null
        Log.i(LOG_TAG, "ViewModel cleared")
    }

    // ── Inner: TalariaWebSocketClient ─────────────────────────

    /**
     * WebSocket + Audio client, owned by the ViewModel.
     * Mirrors the PTTWebSocketClient from v1 but routes state changes
     * through ViewModel StateFlows instead of lambdas.
     */
    private inner class TalariaWebSocketClient(
        private val wsUrl: String,
        private val cfClientId: String,
        private val cfClientSecret: String,
    ) {
        private val cookieJar = object : CookieJar {
            private val cookies = mutableListOf<Cookie>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                this.cookies.addAll(cookies)
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookies.toList()
            }
        }
        private val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .cookieJar(cookieJar)
            .build()

        private var ws: WebSocket? = null
        private var audioRecord: AudioRecord? = null
        private var speechRecognizer: SpeechRecognizer? = null
        private var recordingJob: Job? = null
        private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private var exoPlayer: androidx.media3.exoplayer.ExoPlayer? = null
        private var isPlaying = false
        private var ttsEngine: TextToSpeech? = null

        // Async replay state
        private var lastSeq: Int = 0
        private var reconnectAttempt: Int = 0
        private var reconnectJob: Job? = null
        private val replayAudioBuffer = ArrayDeque<ByteArray>()

        // Audio status tracking
        private var currentAudioMsgId: String = ""
        private var offlineAudioBuffer: ByteArrayOutputStream? = null

        private fun log(level: String, msg: String) {
            // Route through centralized in-memory ring buffer for crash log sharing
            Log.d("TalariaWS", "[$level] $msg")
            LogBuffer.log(level, "TalariaWS", msg)
        }

        /** Warm CF Access cookie jar before WebSocket handshake.
         *  Runs on Dispatchers.IO to avoid blocking the main thread. */
        private fun preAuth() {
            if (cfClientId.isBlank()) return

            scope.launch(Dispatchers.IO) {
                val preAuthUrl = wsUrl
                    .replace("wss://", "https://")
                    .replace("ws://", "http://")
                    .replace("/v1/ptt", "/")

                val request = Request.Builder()
                    .url(preAuthUrl)
                    .header("CF-Access-Client-Id", cfClientId)
                    .header("CF-Access-Client-Secret", cfClientSecret)
                    .build()

                try {
                    val response = client.newCall(request).execute()
                    log("D", "preAuth → ${response.code}")
                    response.close()
                } catch (e: IOException) {
                    log("D", "preAuth failed: ${e.message}")
                }
            }
        }

        fun connect() {
            log("I", "connect() — url=$wsUrl")
            _connectionState.value = ConnectionState.CONNECTING
            
            // Initialize Android TTS engine for fallback audio
            if (ttsEngine == null) {
                ttsEngine = TextToSpeech(this@TalariaViewModel.getApplication()) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        log("I", "Android TTS engine ready")
                    } else {
                        log("W", "Android TTS engine init failed: $status")
                    }
                }
            }

            // Warm CF Access cookie jar — prevents "Expected HTTP 101 but was 200"
            preAuth()

            val request = Request.Builder()
                .url(wsUrl)
                .apply {
                    if (cfClientId.isNotBlank()) addHeader("CF-Access-Client-Id", cfClientId)
                    if (cfClientSecret.isNotBlank()) addHeader("CF-Access-Client-Secret", cfClientSecret)
                }
                .build()

            ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    log("I", "WebSocket OPEN — code=${response.code}")
                    _connectionState.value = ConnectionState.CONNECTED
                    reconnectAttempt = 0

                    val handshake = JSONObject().apply {
                        put("type", "reconnect")
                        put("session_id", sessionId ?: "")
                        put("last_seq", lastSeq)
                        put("thread_id", _currentThread.value?.id ?: "")
                        put("profile", _settings.value.selectedProfile)
                    }
                    val msg = handshake.toString()
                    log("I", "Sending handshake: $msg")
                    ws.send(msg)
                    
                    // Flush any queued messages from offline period
                    flushPendingMessages()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    log("D", "TEXT ← ${text.take(200)}")
                    handleText(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    log("D", "BINARY ← ${bytes.size} bytes")
                    handleBinary(bytes.toByteArray())
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    val httpCode = response?.code
                    val isServerError = httpCode != null && httpCode in 500..599
                    log("E", "WebSocket FAILURE — ${t.message}${if (isServerError) " (HTTP $httpCode)" else ""}")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _pttState.value = PttButtonState.IDLE
                    stopPlayback()
                    // Mark any SENDING messages as ERROR (transmission failed)
                    _messages.value = _messages.value.map {
                        if (it.status == MessageStatus.SENDING) it.copy(status = MessageStatus.ERROR) else it
                    }
                    scheduleReconnect(serverError = isServerError)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    log("I", "WebSocket CLOSED — code=$code reason=$reason")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _pttState.value = PttButtonState.IDLE
                    // Mark any SENDING messages as ERROR
                    _messages.value = _messages.value.map {
                        if (it.status == MessageStatus.SENDING) it.copy(status = MessageStatus.ERROR) else it
                    }
                    scheduleReconnect()
                }
            })
        }

        fun disconnect() {
            log("I", "disconnect()")
            stopRecording()
            stopPlayback()
            reconnectJob?.cancel()
            reconnectJob = null
            ws?.close(1000, "User disconnect")
            ws = null
            // Shutdown Android TTS
            try { ttsEngine?.stop(); ttsEngine?.shutdown() } catch (_: Exception) {}
            ttsEngine = null
            scope.cancel()
        }

        // ── Send raw message ─────────────────────────────────

        fun sendMessage(text: String) {
            log("D", "SEND → ${text.take(200)}")
            ws?.send(text)
        }

        // ── Flush offline queue ────────────────────────────────

        private fun flushPendingMessages() {
            // Flush in-memory pending queue
            val toFlush = pendingMessages.toList()
            pendingMessages.clear()

            // Also check for QUEUED messages loaded from Room (app restart case)
            val roomQueued = _messages.value.filter { it.status == MessageStatus.QUEUED }
            val allQueued = toFlush + roomQueued.map {
                PendingMessage(it.id, it.threadId, it.text, sessionId ?: "")
            }

            if (allQueued.isNotEmpty()) {
                log("I", "Flushing ${allQueued.size} queued text messages")
                for (pm in allQueued.distinctBy { it.id }) {
                    val payload = JSONObject().apply {
                        put("type", "text_input")
                        put("thread_id", pm.threadId)
                        put("session_id", pm.sessionId)
                        put("text", pm.text)
                        put("client_message_id", pm.id)
                    }
                    log("D", "SEND → ${payload.toString().take(200)}")
                    ws?.send(payload.toString())

                    // Update status QUEUED → SENDING in local state
                    val current = _messages.value.find { it.id == pm.id } ?: continue
                    _messages.value = _messages.value.map {
                        if (it.id == pm.id) it.copy(status = MessageStatus.SENDING) else it
                    }
                    // Persist status change
                    viewModelScope.launch {
                        messageDao.insert(current.copy(status = MessageStatus.SENDING).toEntity())
                    }
                }
            }

            // Flush pending audio messages
            val audioToFlush = pendingAudioMessages.toList()
            pendingAudioMessages.clear()
            if (audioToFlush.isNotEmpty()) {
                log("I", "Flushing ${audioToFlush.size} queued audio messages")
                for (am in audioToFlush.distinctBy { it.id }) {
                    // Send ptt_start with client_message_id for status tracking
                    ws?.send(JSONObject().apply {
                        put("type", "ptt_start")
                        put("client_message_id", am.id)
                    }.toString())

                    // Stream the full audio in chunks
                    val chunkSize = AUDIO_BUFFER_SIZE
                    var offset = 0
                    val data = am.audioData
                    while (offset < data.size) {
                        val end = minOf(offset + chunkSize, data.size)
                        val chunk = data.copyOfRange(offset, end)
                        val ob = okio.Buffer()
                        ob.write(chunk)
                        ws?.send(ob.readByteString())
                        offset = end
                    }

                    // Send ptt_end to trigger processing
                    ws?.send(JSONObject().apply { put("type", "ptt_end") }.toString())

                    // Update status QUEUED → SENDING in local state
                    _messages.value = _messages.value.map {
                        if (it.id == am.id) it.copy(status = MessageStatus.SENDING) else it
                    }
                    // Persist status change
                    viewModelScope.launch {
                        val existing = _messages.value.find { it.id == am.id }
                        if (existing != null) {
                            messageDao.insert(existing.toEntity())
                        }
                    }
                    log("I", "Flushed audio message ${am.id} (${data.size} bytes)")
                }
            }
        }

        // ── Auto-reconnect ───────────────────────────────────

        private fun scheduleReconnect(serverError: Boolean = false) {
            reconnectJob?.cancel()
            val baseDelay = if (serverError) SERVER_ERROR_BASE_DELAY_MS else BASE_RECONNECT_DELAY_MS
            val maxDelay = if (serverError) SERVER_ERROR_MAX_DELAY_MS else MAX_RECONNECT_DELAY_MS
            val maxShift = if (serverError) 4 else 5
            val delay = minOf(
                baseDelay * (1L shl reconnectAttempt.coerceAtMost(maxShift)),
                maxDelay,
            )
            reconnectAttempt++
            // ±25% jitter to avoid thundering herd on server recovery
            val jitter = (delay * 0.25 * (Math.random() * 2 - 1)).toLong()
            val finalDelay = (delay + jitter).coerceAtLeast(0)
            log("I", "scheduleReconnect() — attempt=$reconnectAttempt delay=${finalDelay}ms" +
                if (serverError) " (server error backoff)" else "")
            reconnectJob = scope.launch {
                delay(finalDelay)
                connect()
            }
        }

        // ── Seq ack tick ────────────────────────────────────

        private var ackJob: Job? = null

        private fun startAckTicker() {
            log("D", "startAckTicker()")
            ackJob?.cancel()
            ackJob = scope.launch {
                while (isActive) {
                    delay(5_000)
                    sendAck()
                }
            }
        }

        private fun stopAckTicker() {
            ackJob?.cancel()
            ackJob = null
        }

        private fun sendAck() {
            val ack = JSONObject().apply {
                put("type", "ack")
                put("seq", lastSeq)
                put("ts", System.currentTimeMillis())
            }
            log("D", "ACK → seq=$lastSeq")
            ws?.send(ack.toString())
        }

        // ── Barge-in ────────────────────────────────────────

        fun interrupt() {
            log("I", "interrupt() — session=$sessionId")
            stopPlayback()
            ws?.send(JSONObject().apply { put("type", "interrupt") }.toString())
            _pttState.value = PttButtonState.INTERRUPTED
        }

        // ── Recording ───────────────────────────────────────

        fun startRecording(clientMessageId: String, connected: Boolean) {
            log("I", "startRecording() clientMessageId=$clientMessageId connected=$connected")
            if (audioRecord != null) {
                log("W", "Already recording, returning")
                return
            }

            currentAudioMsgId = clientMessageId

            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ),
                AUDIO_BUFFER_SIZE * 4,
            )
            log("I", "AudioRecord bufferSize=$bufferSize")

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                ).apply {
                    startRecording()
                }

                _pttState.value = PttButtonState.RECORDING

                // Start on-device SpeechRecognizer for live transcription preview
                try {
                    val recognizer = SpeechRecognizer.createSpeechRecognizer(
                        this@TalariaViewModel.getApplication()
                    )
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}

                        override fun onPartialResults(partials: Bundle?) {
                            val matches = partials?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull()?.trim() ?: return
                            if (text.isNotEmpty()) {
                                _liveTranscript.value = text
                                _messages.value = _messages.value.map {
                                    if (it.id == clientMessageId) it.copy(
                                        text = text,
                                        transcriptionState = TranscriptionState.ON_DEVICE,
                                    ) else it
                                }
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull()?.trim() ?: ""
                            if (text.isNotEmpty()) {
                                _liveTranscript.value = text
                                _messages.value = _messages.value.map {
                                    if (it.id == clientMessageId && it.transcriptionState == TranscriptionState.ON_DEVICE) it.copy(
                                        text = text.ifEmpty { it.text },
                                        transcriptionState = TranscriptionState.ON_DEVICE,
                                    ) else it
                                }
                            }
                        }

                        override fun onError(error: Int) {
                            log("W", "SpeechRecognizer error: $error")
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    recognizer.startListening(intent)
                    speechRecognizer = recognizer
                    log("I", "SpeechRecognizer started")
                } catch (e: Exception) {
                    log("W", "SpeechRecognizer unavailable (falling back to backend-only): ${e.message}")
                    speechRecognizer = null
                }

                // If connected, announce recording start with client_message_id
                if (connected) {
                    ws?.send(JSONObject().apply {
                        put("type", "ptt_start")
                        put("client_message_id", clientMessageId)
                    }.toString())
                } else {
                    // Offline: buffer audio for later queued send
                    offlineAudioBuffer = ByteArrayOutputStream()
                }

                val isOnline = connected
                val buf = ByteArray(AUDIO_BUFFER_SIZE)
                var totalBytes = 0
                var frames = 0

                recordingJob = scope.launch {
                    while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val bytesRead = audioRecord?.read(buf, 0, AUDIO_BUFFER_SIZE) ?: -1
                        if (bytesRead > 0) {
                            if (isOnline) {
                                val ob = okio.Buffer()
                                ob.write(buf, 0, bytesRead)
                                ws?.send(ob.readByteString())
                            } else {
                                offlineAudioBuffer?.write(buf, 0, bytesRead)
                            }
                            totalBytes += bytesRead
                            frames++
                        } else if (bytesRead < 0) {
                            log("W", "AudioRecord.read returned $bytesRead")
                        }
                    }
                    log("I", "Recording loop ended: $frames frames, $totalBytes bytes")
                }
            } catch (e: Exception) {
                log("E", "startRecording failed: ${e.message}")
                _pttState.value = PttButtonState.IDLE
                audioRecord?.release()
                audioRecord = null
                // Mark placeholder message as ERROR
                _messages.value = _messages.value.map {
                    if (it.id == clientMessageId) it.copy(status = MessageStatus.ERROR) else it
                }
            }
        }

        fun stopRecording() {
            log("I", "stopRecording()")
            recordingJob?.cancel()
            recordingJob = null
            _liveTranscript.value = ""  // clear on-device preview

            // Stop SpeechRecognizer (onResults callback fires asynchronously)
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            speechRecognizer?.destroy()
            speechRecognizer = null

            try { audioRecord?.stop() } catch (_: Exception) {}
            audioRecord?.release()
            audioRecord = null

            // If recording was offline, queue for later delivery
            if (offlineAudioBuffer != null) {
                val audioData = offlineAudioBuffer?.toByteArray() ?: ByteArray(0)
                offlineAudioBuffer?.close()
                offlineAudioBuffer = null

                if (audioData.isNotEmpty()) {
                    val thread = _currentThread.value ?: return
                    pendingAudioMessages.add(PendingAudioMessage(
                        id = currentAudioMsgId,
                        threadId = thread.id,
                        audioData = audioData,
                        sessionId = sessionId ?: "",
                    ))
                    log("I", "Queued audio message for later delivery: ${audioData.size} bytes")
                }
                _pttState.value = PttButtonState.IDLE
                return
            }

            ws?.send(JSONObject().apply { put("type", "ptt_end") }.toString())
            _pttState.value = PttButtonState.PROCESSING
        }

        // ── Text message handling ───────────────────────────

        private fun handleText(text: String) {
            try {
                val json = JSONObject(text)
                val type = json.optString("type", "?")

                // Track sequence number
                val seq = json.optInt("seq", 0)
                if (seq > lastSeq) lastSeq = seq

                when (type) {
                    "reconnect_done" -> {
                        val replayed = json.optBoolean("replayed", false)
                        log("I", "reconnect_done — replayed=$replayed")
                        startAckTicker()
                    }
                    "stt_transcript" -> {
                        val t = json.optString("text", "")
                        val clientMsgId = json.optString("client_message_id", "")
                        log("I", "STT transcript (${t.length} chars) client_message_id=$clientMsgId")
                        _partialTranscript.value = ""
                        _liveTranscript.value = ""  // clear on-device preview

                        if (clientMsgId.isNotEmpty()) {
                            // Replace on-device placeholder with authoritative server transcript
                            _messages.value = _messages.value.map {
                                if (it.id == clientMsgId) it.copy(
                                    text = t.ifEmpty { it.text },
                                    status = MessageStatus.SENT,
                                    transcriptionState = TranscriptionState.BACKEND_FINAL,
                                ) else it
                            }
                            viewModelScope.launch {
                                val existing = _messages.value.find { it.id == clientMsgId }
                                if (existing != null) {
                                    messageDao.insert(existing.toEntity())
                                }
                            }
                        } else {
                            // Fallback: add as new message (legacy path — server didn't propagate client_message_id)
                            addMessage(ChatMessage(
                                id = UUID.randomUUID().toString(),
                                threadId = _currentThread.value?.id ?: "",
                                text = t,
                                isUser = true,
                                modality = MessageModality.VOICE,
                                status = MessageStatus.SENT,
                                transcriptionState = TranscriptionState.BACKEND_FINAL,
                            ))
                        }
                        _transcript.value = t
                    }
                    "ptt_start_echo" -> {
                        // Server acknowledged ptt_start
                        val clientMsgId = json.optString("client_message_id", "")
                        log("D", "ptt_start_echo: client_message_id=$clientMsgId")
                    }
                    "tts_start" -> {
                        val t = json.optString("text", "")
                        log("I", "TTS start (${t.length} chars)")
                        _pttState.value = PttButtonState.PLAYING

                        addMessage(ChatMessage(
                            id = UUID.randomUUID().toString(),
                            threadId = _currentThread.value?.id ?: "",
                            text = t,
                            isUser = false,
                            modality = MessageModality.VOICE,
                            status = MessageStatus.DELIVERED,
                        ))
                        drainReplayBuffer()
                    }
                    "tts_end" -> {
                        log("I", "TTS end")
                        stopPlayback()
                        replayAudioBuffer.clear()
                        _pttState.value = PttButtonState.IDLE
                    }
                    "tts_fallback" -> {
                        val fullText = json.optString("text", "")
                        log("I", "TTS fallback (${fullText.length} chars)")
                        _pttState.value = PttButtonState.IDLE

                        // Speak via Android TTS
                        ttsEngine?.speak(fullText, TextToSpeech.QUEUE_FLUSH, null, "talaria_tts_fallback_${System.currentTimeMillis()}")

                        addMessage(ChatMessage(
                            id = UUID.randomUUID().toString(),
                            threadId = _currentThread.value?.id ?: "",
                            text = fullText,
                            isUser = false,
                            modality = MessageModality.VOICE,
                            status = MessageStatus.DELIVERED,
                        ))
                        replayAudioBuffer.clear()
                    }
                    "processing" -> {
                        val step = json.optString("step", "")
                        log("D", "processing step=$step")
                    }
                    "session_id" -> {
                        sessionId = json.optString("id", "")
                        log("I", "session_id received: $sessionId")

                        // Update current thread's session ID
                        _currentThread.value?.let { thread ->
                            val updated = thread.copy(sessionId = sessionId)
                            _currentThread.value = updated
                            _threadList.value = _threadList.value.map {
                                if (it.id == thread.id) updated else it
                            }
                            // Persist session ID to Room
                            viewModelScope.launch {
                                threadDao.update(updated.toEntity())
                            }
                        }
                    }
                    "interrupted" -> {
                        log("I", "Server confirmed interrupt")
                        stopPlayback()
                        replayAudioBuffer.clear()
                        _pttState.value = PttButtonState.IDLE
                    }
                    "error" -> {
                        val msg = json.optString("message", "Unknown error")
                        log("E", "Server error: $msg")
                        _pttState.value = PttButtonState.IDLE
                    }
                    "text_echo" -> {
                        // Server echoes user text — update status to SENT
                        val clientMsgId = if (json.has("client_message_id")) json.getString("client_message_id") else null
                        log("D", "text_echo: client_message_id=$clientMsgId")

                        if (clientMsgId != null) {
                            // Mark the matching message as SENT (server confirmed receipt)
                            _messages.value = _messages.value.map {
                                if (it.id == clientMsgId) it.copy(status = MessageStatus.SENT) else it
                            }
                            // Persist status update to Room
                            val updatedMsg = _messages.value.find { it.id == clientMsgId }
                            if (updatedMsg != null) {
                                viewModelScope.launch {
                                    messageDao.insert(updatedMsg.toEntity())
                                }
                            }
                        }
                    }
                    "ping" -> { /* keepalive */ }
                    "pong" -> {
                        val sentAt = json.optLong("ts", 0)
                        if (sentAt > 0) {
                            _latencyMs.value = System.currentTimeMillis() - sentAt
                        }
                    }
                    "stt_partial" -> {
                        val t = json.optString("text", "")
                        log("D", "STT partial: ${t.take(50)}")
                        _partialTranscript.value = t
                    }
                    "session_list" -> {
                        val sessionsJson = json.optJSONArray("sessions")
                        if (sessionsJson != null) {
                            val sessions = mutableListOf<HermesSession>()
                            for (i in 0 until sessionsJson.length()) {
                                val obj = sessionsJson.optJSONObject(i) ?: continue
                                sessions.add(HermesSession.fromJson(obj))
                            }
                            _hermesSessions.value = sessions
                            log("I", "Received ${sessions.size} Hermes sessions")
                        }
                    }
                    "session_forked" -> {
                        val newId = json.optString("new_session_id", "")
                        val title = json.optString("title", "")
                        log("I", "Session forked: $newId (title=$title)")
                        if (newId.isNotEmpty()) {
                            sessionId = newId
                            _currentThread.value?.let { thread ->
                                val updated = thread.copy(sessionId = newId)
                                _currentThread.value = updated
                                _threadList.value = _threadList.value.map {
                                    if (it.id == thread.id) updated else it
                                }
                                viewModelScope.launch {
                                    threadDao.update(updated.toEntity())
                                }
                            }
                        }
                    }
                    "session_history" -> {
                        val histSessionId = json.optString("session_id", "")
                        val messagesJson = json.optJSONArray("messages")
                        if (messagesJson != null && messagesJson.length() > 0) {
                            log("I", "Received ${messagesJson.length()} history messages for $histSessionId")
                            val history = mutableListOf<ChatMessage>()
                            for (i in 0 until messagesJson.length()) {
                                val msg = messagesJson.optJSONObject(i) ?: continue
                                val role = msg.optString("role", "")
                                val content = msg.optString("content", "")
                                if (content.isBlank()) continue
                                history.add(ChatMessage(
                                    id = "hist_${histSessionId}_$i",
                                    threadId = _currentThread.value?.id ?: "",
                                    text = content,
                                    isUser = role == "user",
                                    modality = MessageModality.TEXT,
                                    status = MessageStatus.DELIVERED,
                                ))
                            }
                            _messages.value = history
                        }
                    }
                    "profile_set" -> {
                        val profile = json.optString("profile", "")
                        log("I", "Profile set: $profile")
                    }
                    else -> {
                        log("W", "Unknown message type: $type")
                    }
                }
            } catch (e: Exception) {
                log("E", "handleText parse error: ${e.message}")
            }
        }

        // ── Audio playback ─────────────────────────────────

        /** Post to main thread — ExoPlayer requires a Looper.
         *  Called from OkHttp callback thread (via handleText → drainReplayBuffer). */
        private fun startPlayback() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                startPlaybackInternal()
            }
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun startPlaybackInternal() {
            log("I", "startPlayback()")
            if (isPlaying) return
            isPlaying = true
            _isPaused.value = false

            try {
                // If no audio was buffered (server TTS unavailable), skip playback
                if (replayAudioBuffer.isEmpty()) {
                    log("I", "startPlayback: no audio data — skipping (server TTS unavailable)")
                    isPlaying = false
                    return
                }

                val tempFile = java.io.File(
                    appContext.cacheDir, "talaria_audio_${System.currentTimeMillis()}.ogg"
                )
                val fos = java.io.FileOutputStream(tempFile)
                while (replayAudioBuffer.isNotEmpty()) {
                    fos.write(replayAudioBuffer.removeFirst())
                }
                fos.close()

                exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(appContext).build().apply {
                    val mi = androidx.media3.common.MediaItem.fromUri(tempFile.toURI().toString())
                    setMediaItem(mi)
                    prepare()
                    setPlaybackSpeed(_playbackSpeed.value)
                    playWhenReady = true

                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == androidx.media3.common.Player.STATE_READY) {
                                _playbackDuration.value = duration.coerceAtLeast(0)
                            }
                        }
                        override fun onIsPlayingChanged(playing: Boolean) {
                            _isPaused.value = !playing
                        }
                    })

                    // Poll position every 200ms
                    scope.launch {
                        while (isActive && exoPlayer != null) {
                            _playbackPosition.value = currentPosition.coerceAtLeast(0)
                            delay(200)
                        }
                    }

                    log("I", "ExoPlayer prepared: ${tempFile.absolutePath} (speed=${_playbackSpeed.value}x)")
                }
            } catch (e: Exception) {
                log("E", "startPlayback failed: ${e.message}")
                isPlaying = false
                exoPlayer = null
            }
        }

        fun togglePause() {
            if (exoPlayer == null) return
            if (_isPaused.value) {
                exoPlayer?.play()
                log("I", "Playback resumed")
            } else {
                exoPlayer?.pause()
                log("I", "Playback paused")
            }
        }

        fun seekTo(positionMs: Long) {
            exoPlayer?.seekTo(positionMs)
            log("I", "Seek to ${positionMs}ms")
        }

        private fun handleBinary(data: ByteArray) {
            // Binary audio chunks are always buffered. When tts_start arrives,
            // drainReplayBuffer writes them to a temp file and starts playback.
            replayAudioBuffer.addLast(data)
        }

        private fun stopPlayback() {
            log("I", "stopPlayback()")
            isPlaying = false
            _isPaused.value = false
            _playbackPosition.value = 0
            _playbackDuration.value = 0
            // Post to main thread — ExoPlayer must be released on the same thread it was created on
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    exoPlayer?.stop()
                    exoPlayer?.release()
                } catch (_: Exception) {}
                exoPlayer = null
            }
        }

        private fun drainReplayBuffer() {
            // startPlayback now handles consuming the replay buffer (writes to temp file)
            startPlayback()
        }

        fun applySpeed(speed: Float) {
            exoPlayer?.setPlaybackSpeed(speed)
        }
    }

    // ── Private helpers ─────────────────────────────────────

    private fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message

        // Persist message to Room
        viewModelScope.launch {
            messageDao.insert(message.toEntity())
        }

        // Update thread preview
        _currentThread.value?.let { thread ->
            val updated = thread.copy(
                lastMessagePreview = message.text.take(100),
                lastMessageTimestamp = message.timestamp,
            )
            _threadList.value = _threadList.value.map {
                if (it.id == thread.id) updated else it
            }

            // Persist thread update to Room
            viewModelScope.launch {
                threadDao.update(updated.toEntity())
            }
        }
    }
}

// ── Model ↔ Entity mappings ────────────────────────────────────

fun Thread.toEntity(): ThreadEntity = ThreadEntity(
    id = id,
    name = name,
    sessionId = sessionId,
    lastMessagePreview = lastMessagePreview,
    lastMessageTimestamp = lastMessageTimestamp,
    createdAt = createdAt,
    isArchived = isArchived,
)

fun ThreadEntity.toModel(): Thread = Thread(
    id = id,
    name = name,
    sessionId = sessionId,
    lastMessagePreview = lastMessagePreview,
    lastMessageTimestamp = lastMessageTimestamp,
    createdAt = createdAt,
    isArchived = isArchived,
)

fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    threadId = threadId,
    text = text,
    isUser = isUser,
    modality = modality.name,
    status = status.name,
    timestamp = timestamp,
    interrupted = interrupted,
    runId = runId,
)

fun MessageEntity.toModel(): ChatMessage = ChatMessage(
    id = id,
    threadId = threadId,
    text = text,
    isUser = isUser,
    modality = try { MessageModality.valueOf(modality) } catch (_: Exception) { MessageModality.TEXT },
    status = try { MessageStatus.valueOf(status) } catch (_: Exception) { MessageStatus.SENT },
    timestamp = timestamp,
    interrupted = interrupted,
    runId = runId,
)
