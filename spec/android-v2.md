# Talaria Android v2 — Specification

## Overview

Talaria v2 expands the Android PTT app into a **multimodal chat interface** for Hermes Agent. It adds text chat alongside push-to-talk, a settings/navigation drawer, multi-thread conversation support, and seamless modality switching — so the user can start a turn with voice, drop into text after a network handoff, and pick up right where they left off.

The server protocol is extended with new message types while remaining backward-compatible with v1 PTT-only clients.

---

## 1. UI Design

### 1.1 Screen Layout

```
┌──────────────────────────────────────────────────┐
│  ☰ Talaria                    ● connected  ⚙     │  ← TopAppBar
├──────────────────────────────────────────────────┤
│                                                  │
│  ┌ Thread: "Home improvement"        [▼] ───┐   │  ← ThreadSwitcher chip
│  └──────────────────────────────────────────┘   │
│                                                  │
│  ┌────────────────────────────────────────────┐  │
│  │                                            │  │
│  │  🎤 You (voice)              12:34:01     │  │  ← Chat bubble with modality badge
│  │  ┌──────────────────────────────┐          │  │
│  │  │ What's the weather like?     │          │  │
│  │  └──────────────────────────────┘          │  │
│  │                                            │  │
│  │        12:34:05  🤖 Hermes                │  │
│  │         ┌──────────────────────────────┐   │  │
│  │         │ It's 72°F and sunny today.   │   │  │
│  │         └──────────────────────────────┘   │  │
│  │                                            │  │
│  │  ⌨ You (text)                 12:35:22    │  │
│  │  ┌──────────────────────────────┐          │  │
│  │  │ What about tomorrow?         │          │  │
│  │  └──────────────────────────────┘          │  │
│  │                                            │  │
│  │  [⋯⋯⋯ Hermes is thinking ⋯⋯⋯]             │  │  ← Animated thinking indicator
│  │                                            │  │
│  └────────────────────────────────────────────┘  │
│                                                  │
│  ┌────────────────────────────────────────────┐  │  ← Input area
│  │  [📎]  [Type a message...          ]  [🎤] │  │     Attach / TextField / PTT
│  └────────────────────────────────────────────┘  │
│                                                  │
└──────────────────────────────────────────────────┘
```

### 1.2 TopAppBar

| Element | Position | Behavior |
|---------|----------|----------|
| ☰ hamburger | Left | Opens the navigation drawer |
| "Talaria" title | Center | App name (tappable to go home) |
| ● connection dot | Right (inline with title) | Green/yellow/red; tappable for reconnect |
| ⚙ gear icon | Right | Opens settings (same as drawer → Settings) |

### 1.3 ThreadSwitcher

A tappable chip/dropdown below the TopAppBar showing the current thread name:

```
┌──────────────────────────────────────┐
│  💬  Home improvement          [▼]   │
└──────────────────────────────────────┘
```

- Tapping the chip opens a **thread list bottom sheet**
- The thread list shows all threads with last-message previews and timestamps
- Swipe-to-delete on threads (with confirmation)
- "＋ New Thread" button at the top of the sheet

### 1.4 Chat Transcript

The `ChatTranscript` composable is upgraded with:

- **Modality badges**: Each user message shows how it was sent — 🎤 for voice, ⌨ for text
- **Thinking indicators**: An animated "⋯⋯⋯" placeholder while Hermes processes
- **Loading states**: Per-message status: sending → delivered → acknowledged
- **Error states**: Failed messages shown with a ⚠️ retry button
- **Scroll-to-bottom FAB**: Appears when the user scrolls up; tap to jump to latest

### 1.5 Input Area

```
┌───────────────────────────────────────────────┐
│  [📎]  [Type a message...              ]  [🎤] │
└───────────────────────────────────────────────┘
```

| Element | Description |
|---------|-------------|
| 📎 attach button | Future: share images/files (stub in v2) |
| TextField | Type-and-send chat input. Multiline, auto-growing up to 4 lines |
| Send/PTT toggle button | Contextual: shows 🎤 in text-mode (tap to switch to voice), shows ⌨ in voice-mode (tap to switch to text) |
| Send action | IME action "Send" or dedicated send icon when text is non-empty |

**Behavior:**
- Tapping the text field raises the keyboard
- Pressing "Send" (IME action or icon) sends the text message via WebSocket
- Long-pressing 🎤 activates PTT recording (same hold-to-talk as v1)
- A quick tap on 🎤 toggles the input mode between voice and text (no recording started)
- When the keyboard is visible, the PTT button shrinks to the send button; long-press still works for PTT

### 1.6 Navigation Drawer

The drawer slides in from the left (or bottom sheet on phones — configurable). 

```
┌──────────────────────────────┐
│  Talaria                      │
│  v2.0.0                      │
│  ─────────────────────────── │
│                              │
│  🏠  Home                    │  ← Go to active thread
│  💬  Threads                 │
│     ├─ Home improvement      │  ← Recent threads (max 5 shown)
│     ├─ Project ideas         │
│     ├─ General chat          │
│     └─ View all threads →   │
│                              │
│  ─────────────────────────── │
│                              │
│  ⚙  Settings                │  ← Opens settings sub-screen
│  📊  Connection              │
│  📋  Share Logs              │
│  ❓  About                   │
│                              │
│  ─────────────────────────── │
│                              │
│  ● Connected                 │  ← Status + tap to reconnect
│  ws://talaria.ffilt.us/v1/ptt │
│  3 threads, 142 messages     │
│                              │
└──────────────────────────────┘
```

### 1.7 Settings Screen

Navigated to from the drawer (or via ⚙ in TopAppBar). A full-screen settings page:

| Section | Items |
|---------|-------|
| **Server** | WebSocket URL (editable), CF-Access Client ID (editable, masked), CF-Access Secret (editable, masked) |
| **Connection** | Auto-reconnect toggle, Reconnect delay multiplier, Manual reconnect button |
| **Audio** | STT model preference (info only — server-side), TTS voice preference, Audio ducking toggle |
| **Chat** | Show timestamps toggle, Max message history (50–500), Auto-scroll on new message toggle |
| **Threads** | Default thread name, Auto-create thread on first message, Delete threads after N days |
| **About** | Version, Build date, Open source licenses |

All settings are persisted locally via Android DataStore (Preferences).

---

## 2. Component Tree

```
TalariaApp (composable root)
├── TalariaTheme
│   └── Scaffold
│       ├── TopAppBar
│       │   ├── HamburgerIcon (→ openDrawer)
│       │   ├── Title "Talaria"
│       │   ├── ConnectionDot (color state)
│       │   └── SettingsIcon (→ navigateToSettings)
│       │
│       ├── NavigationDrawer / ModalDrawerSheet
│       │   ├── DrawerHeader (logo, version)
│       │   ├── NavItem("Home")
│       │   ├── NavItem("Threads") + RecentThreadList
│       │   ├── Divider
│       │   ├── NavItem("Settings")
│       │   ├── NavItem("Share Logs")
│       │   ├── NavItem("About")
│       │   └── ConnectionStatusFooter
│       │
│       ├── Column (main content)
│       │   ├── ThreadSwitcher (chip/dropdown)
│       │   ├── ChatTranscript (LazyColumn, weight=1f)
│       │   │   ├── ChatBubble (per message)
│       │   │   │   ├── ModalityBadge (🎤/⌨)
│       │   │   │   ├── SenderLabel + Timestamp
│       │   │   │   ├── BubbleContent (text, colored by sender)
│       │   │   │   └── StatusIcon (sent/delivered/error)
│       │   │   ├── ThinkingIndicator (animated dots)
│       │   │   └── ScrollToBottomFAB
│       │   │
│       │   └── InputArea
│       │       ├── AttachButton (stub)
│       │       ├── MessageTextField
│       │       ├── SendButton (visible when text non-empty)
│       │       └── PTTButton (long-press → record, tap → toggle mode)
│       │
│       └── ThreadListSheet (ModalBottomSheet)
│           ├── NewThreadButton
│           └── LazyColumn of ThreadRow items
│               └── SwipeToDismiss (delete with confirm)
│
├── SettingsScreen (navigation destination)
│   ├── BackArrow + "Settings" title
│   └── LazyColumn of SettingsGroup composables
│       ├── ServerSettings
│       ├── ConnectionSettings
│       ├── AudioSettings
│       ├── ChatSettings
│       └── AboutSection
│
└── ThreadDetailScreen (if full-screen thread view desired — optional v2)
```

---

## 3. Data Models

### 3.1 ChatMessage (expanded)

```kotlin
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val threadId: String,           // FK to Thread
    val text: String,
    val isUser: Boolean,
    val modality: MessageModality,  // VOICE or TEXT
    val timestamp: Long,            // epoch millis
    val status: MessageStatus,      // SENDING, SENT, DELIVERED, ERROR
    val interrupted: Boolean = false,
    val runId: String? = null,      // Hermes run ID for this exchange
)
enum class MessageModality { VOICE, TEXT }
enum class MessageStatus { SENDING, SENT, DELIVERED, ERROR }
```

### 3.2 Thread

```kotlin
data class Thread(
    val id: String = UUID.randomUUID().toString(),
    val name: String,               // user-visible name
    val sessionId: String? = null,  // Hermes session ID for continuity
    val createdAt: Long,            // epoch millis
    val lastActiveAt: Long,         // epoch millis
    val messageCount: Int = 0,
    val lastMessagePreview: String = "",
    val isArchived: Boolean = false,
)
```

### 3.3 AppSettings

```kotlin
data class AppSettings(
    val wsUrl: String = "wss://talaria.ffilt.us/v1/ptt",
    val cfClientId: String = "",
    val cfClientSecret: String = "",
    val autoReconnect: Boolean = true,
    val maxReconnectDelayMs: Long = 30_000L,
    val voiceInputEnabled: Boolean = true,
    val textInputEnabled: Boolean = true,
    val showTimestamps: Boolean = true,
    val maxMessageHistory: Int = 200,
    val autoScrollOnNewMessage: Boolean = true,
    val autoCreateThread: Boolean = true,
    val defaultThreadName: String = "General",
)
```

---

## 4. State Management

### 4.1 ViewModel Architecture

```
TalariaViewModel (shared across screens)
├── connectionState: StateFlow<ConnectionState>
├── currentThread: StateFlow<Thread?>
├── messages: StateFlow<List<ChatMessage>>      // filtered by currentThread
├── allThreads: StateFlow<List<Thread>>
├── settings: StateFlow<AppSettings>
├── pttState: StateFlow<PttButtonState>
├── inputMode: StateFlow<InputMode>              // TEXT or VOICE
│
├── fun connect()
├── fun disconnect()
├── fun sendTextMessage(text: String)
├── fun startRecording()
├── fun stopRecording()
├── fun interrupt()
├── fun switchThread(threadId: String)
├── fun createThread(name: String)
├── fun deleteThread(threadId: String)
├── fun renameThread(threadId: String, name: String)
├── fun updateSettings(newSettings: AppSettings)
├── fun setInputMode(mode: InputMode)
└── fun retryMessage(messageId: String)

enum class InputMode { TEXT, VOICE }
```

### 4.2 Persistence Layer

- **Room database** (SQLite) for threads and messages
- **DataStore (Preferences)** for app settings
- Schema:

```
Threads table:
  id TEXT PRIMARY KEY
  name TEXT NOT NULL
  session_id TEXT
  created_at INTEGER NOT NULL
  last_active_at INTEGER NOT NULL
  is_archived INTEGER DEFAULT 0

Messages table:
  id TEXT PRIMARY KEY
  thread_id TEXT NOT NULL (FK → Threads.id)
  text TEXT NOT NULL
  is_user INTEGER NOT NULL
  modality TEXT NOT NULL           -- "VOICE" or "TEXT"
  timestamp INTEGER NOT NULL
  status TEXT NOT NULL             -- "SENDING", "SENT", "DELIVERED", "ERROR"
  interrupted INTEGER DEFAULT 0
  run_id TEXT
```

### 4.3 Modality State Transitions

```
                    ┌────────────────────────┐
                    │                        │
                    ▼                        │
             ┌──────────┐  long-press 🎤   ┌─┴────────┐
    tap ⌨    │  TEXT    │ ───────────────→ │  VOICE   │
  ───────────│  MODE    │                  │  MODE    │
             │          │ ←─────────────── │          │
             └──────────┘  release 🎤      └──────────┘
                    │          (or tap ⌨)        │
                    │                            │
              type + Enter              hold 🎤 + speak
                    │                            │
                    ▼                            ▼
             ┌──────────┐               ┌──────────────┐
             │ SEND     │               │ RECORDING    │
             │ (ws send)│               │ (AudioRecord)│
             └──────────┘               └──────┬───────┘
                                               │ release
                                               ▼
                                        ┌──────────────┐
                                        │ PROCESSING   │
                                        │ (wait server)│
                                        └──────────────┘
```

Key: The thread's `sessionId` persists across both TEXT and VOICE turns. Whether the user sends via text or PTT, the message goes to the same Hermes session, maintaining conversation context.

---

## 5. Data Flow Diagrams

### 5.1 Text Message Flow

```
User types message → taps Send
     │
     ▼
[TalariaViewModel.sendTextMessage()]
     │
     ├─ Persist to Room DB (status=SENDING)
     ├─ Update messages StateFlow → UI shows sent bubble
     │
     ▼
[TalariaWebSocketClient.sendText()]
     │  JSON: {"type":"text_input","thread_id":"...","session_id":"...","text":"..."}
     ▼
── WebSocket ──→ [Talaria Server]
     │               │
     │               ├─ POST /v1/runs with input=text, session_id
     │               ├─ SSE events → accumulate reply
     │               ├─ TTS (optional, configurable)
     │               │
     │  ←────────────┤
     │   JSON:       │
     │   stt_transcript (echoes user text for transcript)
     │   processing   │
     │   tts_start (or tts_fallback for text-only)
     │   tts_end      │
     │   session_id   │
     ▼
[handleText() in client]
     ├─ Update message status → DELIVERED
     ├─ Append Hermes reply to messages
     └─ Update thread's sessionId + lastActiveAt
```

### 5.2 Voice Message Flow

```
User long-presses 🎤 → startRecording()
     │
     ▼
[AudioRecord] → raw PCM (16kHz mono) → binary WS frames
     │
     ... streaming during hold ...
     │
User releases 🎤 → stopRecording()
     │  JSON: {"type":"ptt_end"}
     ▼
── WebSocket ──→ [Talaria Server]
     │               │
     │               ├─ Concatenate PCM → faster-whisper → text
     │               ├─ POST /v1/runs with input=text, session_id
     │               ├─ SSE events → accumulate reply
     │               ├─ edge-tts → ffmpeg → Opus/OGG
     │               │
     │  ←────────────┤
     │   JSON: stt_transcript, tts_start, etc.
     │   BINARY: Opus audio chunks
     ▼
[Client handles response]
     ├─ Show STT transcript as user message (modality=VOICE)
     ├─ Play Opus audio via ExoPlayer
     ├─ Show Hermes reply text
     └─ Update thread's sessionId
```

### 5.3 Modality Switch Flow (Seamless)

```
1. User starts with PTT:
   🎤 "What's the weather?"
   → Server creates session_id="sess_A"
   → Client stores Thread.sessionId = "sess_A"

2. Network drops (WiFi → 5G):
   → WebSocket disconnects
   → Client auto-reconnects with {"type":"reconnect","session_id":"sess_A","last_seq":42}
   → Server replays any undelivered run
   → Reconnect complete — same thread, same session

3. User switches to text:
   Taps ⌨ → keyboard appears
   Types: "What about tomorrow?"
   → Client sends: {"type":"text_input","session_id":"sess_A","thread_id":"...","text":"What about tomorrow?"}
   → Server uses same session_id → Hermes remembers "weather" context
   → Response appears in same thread

4. Result:
   ChatTranscript shows:
     🎤 You:     "What's the weather?"    12:00:01
     🤖 Hermes:  "It's 72°F and sunny."   12:00:05
     ⌨ You:      "What about tomorrow?"   12:01:30
     🤖 Hermes:  "Tomorrow: 68°F, partly cloudy." 12:01:35
```

---

## 6. Thread Lifecycle

### 6.1 Creation
- Auto-created on first message if `autoCreateThread=true` (default name from settings)
- Manual creation via ThreadListSheet → "＋ New Thread" → name prompt
- Each new thread starts with `sessionId=null`; the first Hermes response populates it

### 6.2 Switching
- Tap thread in ThreadListSheet → `switchThread(threadId)`
- If the target thread has a `sessionId`, it's sent on the next reconnect
- Messages reload from Room DB filtered by `threadId`
- Input area clears, focus returns to text field

### 6.3 Deletion
- Swipe-to-delete in ThreadListSheet → confirmation dialog
- Soft-delete: set `isArchived=true` (can be restored)
- Hard-delete: remove all messages + thread from Room (after 30-day grace period)

### 6.4 Persistence
- Threads and messages survive app restarts via Room
- On cold start, the most recently active thread is selected
- `sessionId` values persist in Room (server-side sessions may expire — handled gracefully)

---

## 7. Protocol Extensions (Server-Side)

### 7.1 New Client → Server Messages

| Type | Fields | Description |
|------|--------|-------------|
| `text_input` | `{"thread_id":"...","session_id":"...","text":"..."}` | User sent a text message. Server creates a Hermes run with this text. `session_id` optional — if absent, server creates a new session and returns it. |
| `switch_session` | `{"session_id":"..."}` | Client declares it's switching to a different Hermes session. Server acknowledges with `session_switched`. Use case: thread switch where the new thread has an existing session. |
| `create_session` | `{}` | Request a fresh Hermes session. Server creates one and returns `session_id`. |

### 7.2 New Server → Client Messages

| Type | Fields | Description |
|------|--------|-------------|
| `text_echo` | `{"text":"...","message_id":"..."}` | Server echoes the user's text input back to the client for transcript display. The `message_id` links it to the client-sent message. |
| `session_switched` | `{"session_id":"...","previous_session_id":"..."}` | Acknowledges session switch. Previous session may still be alive on the server for replay purposes. |
| `thread_info` | `{"thread_id":"...","session_id":"...","message_count":N}` | Metadata about a thread/session. Used during reconnect to sync state. |

### 7.3 Updated Message Flow

The `reconnect` handshake is extended with thread context:

```json
{
  "type": "reconnect",
  "session_id": "sess_abc",
  "last_seq": 42,
  "thread_id": "thread-123",
  "active_threads": ["thread-123", "thread-456"]
}
```

The server uses `thread_id` to determine which session's undelivered runs to replay. `active_threads` lets the server know which sessions to keep warm.

### 7.4 Backward Compatibility

- v1 clients only send `ptt_end`, `interrupt`, `cancel`, `ping`, `reconnect`, `ack` — all still supported
- v2 clients additionally send `text_input`, `switch_session`, `create_session`
- Server detects client version by presence of `text_input` or `switch_session` in any message during the connection lifecycle
- The `/v1/ptt` endpoint remains the single WebSocket endpoint; no separate endpoint for text

### 7.5 Server State Machine Extension

The existing state machine (IDLE → RECORDING → TRANSCRIBING → HERMES_RUNNING → TTS_STREAMING → IDLE) is extended with a parallel text input path:

```
IDLE ──[text_input received]──→ HERMES_RUNNING (text)
  │                                   │
  │                              [reply collected]
  │                                   │
  │                                   ├─ TTS enabled → TTS_STREAMING → IDLE
  │                                   └─ TTS disabled → IDLE (text fallback)
  │
  └──[ptt_end received]──→ RECORDING → TRANSCRIBING → HERMES_RUNNING → TTS_STREAMING → IDLE
```

Text inputs skip RECORDING and TRANSCRIBING states entirely. The server processes them concurrently with any ongoing PTT turn (single Hermes API call at a time per session; subsequent requests are queued or rejected with `{"type":"error","message":"Session busy — agent is processing"}`).

### 7.6 Multi-Thread Server Support

- The RunStore is keyed by `(session_id, thread_id)` instead of just `session_id`
- The server maintains a map of `thread_id → session_id` for active connections
- On `switch_session`, the server validates the session exists (or creates one)
- Session TTL (5 min) is tracked per session; idle sessions are cleaned up

---

## 8. Migration Path from v1

### 8.1 Android App

| v1 Component | v2 Change |
|-------------|-----------|
| `MainActivity.kt` (single file) | Split into `MainActivity.kt` + `TalariaViewModel.kt` + `TalariaWebSocketClient.kt` + UI composables in `ui/` package |
| `ChatMessage` (simple data class) | Extended with `threadId`, `modality`, `status`, `messageId` |
| Single `transcript` list | Per-thread message lists backed by Room |
| Hardcoded WS URL + CF creds | DataStore-backed settings, editable in Settings screen |
| `PTTButton` only | Replaced by `InputArea` with `TextField` + `PTTButton` + `SendButton` |
| Single `Scaffold` layout | Scaffold + ModalNavigationDrawer + thread switching |
| No persistence | Room database for threads + messages |
| No settings | DataStore + SettingsScreen |

### 8.2 Server

| v1 Feature | v2 Change |
|-----------|-----------|
| `/v1/ptt` endpoint only | Same endpoint, extended protocol |
| RunStore keyed by `session_id` | Keyed by `(session_id, thread_id)` |
| No text input handling | New `text_input` message handler |
| No thread concept | Thread registry (in-memory map, ephemeral) |
| Single active session per connection | Multiple sessions per connection (switched via protocol) |

### 8.3 Upgrade Path

1. Deploy updated server (backward-compatible with v1 client)
2. Build and distribute v2 APK
3. v1 clients continue to work during transition
4. v2 client uses new features; old sessions are migrated on first text_input

---

## 9. Non-Goals for v2

- Multi-user support (still single-consumer: Reed)
- Image/file attachments (📎 is a stub)
- Voice activity detection (VAD) for automatic turn-taking
- Wake-word detection
- Offline mode / local STT fallback
- End-to-end encryption beyond CF-Access
- iOS app
- Push notifications

---

## 10. Key Design Decisions

1. **Single WebSocket, extended protocol** — No new endpoints. Text and voice share the same connection. Keeps the architecture simple and avoids multiplexing complexity.

2. **Server remains stateless about threads** — Thread management (names, ordering, archiving) is purely client-side in Room. The server only knows about `(thread_id, session_id)` pairs for replay purposes.

3. **Session continuity is the bridge** — The Hermes `session_id` is the invariant that ties text and voice turns together. Whether the user speaks or types, the same session ID is sent to Hermes, preserving context.

4. **PTT remains hold-to-talk** — Voice input is still push-to-talk, not tap-to-toggle. The long-press model is preserved for muscle memory. The tap action on the mic button now toggles input mode (text ↔ voice) instead.

5. **Persist everything locally** — Room stores all messages. The server is ephemeral. If the server loses state, the client has the full history. Reconnection replays only undelivered runs, not the entire history.

6. **Settings are client-side** — STT model, TTS voice preferences are hints sent to the server. The server may ignore them if unsupported. This avoids tight coupling.

---

## 11. Dependencies (New)

| Library | Purpose |
|---------|---------|
| `androidx.room:room-runtime` + `room-ktx` + `room-compiler` | Local SQLite persistence for threads + messages |
| `androidx.datastore:datastore-preferences` | Key-value settings persistence |
| `androidx.navigation:navigation-compose` | Navigation between Home, Settings, and (future) screens |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | ViewModel integration with Compose |
| (Existing) OkHttp, Media3 ExoPlayer, kotlinx.coroutines | Unchanged from v1 |
