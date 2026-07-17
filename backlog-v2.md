# Talaria — Implementation Backlog

## Overview

Backlog for Talaria: multimodal Android app with text chat, threads, settings drawer, and seamless modality switching. Server protocol extensions for text input and multi-thread support.

Tasks are ordered by dependency and grouped into milestones. Each task includes estimated complexity: **S** (1–3 hours), **M** (3–8 hours), **L** (8–20 hours).

---

## Milestone 1: Foundation — Data Layer & Settings

The v2 app needs local persistence and user-configurable settings before any UI work begins.

### 1.1 Add Room dependencies to build.gradle
**Complexity:** S | **Depends on:** —  
Add `room-runtime`, `room-ktx`, `room-compiler` (KSP) to `android/app/build.gradle.kts`. Configure KSP plugin. Verify build succeeds.

### 1.2 Define Room entities: Thread + Message
**Complexity:** M | **Depends on:** 1.1  
Create `ThreadEntity` and `MessageEntity` data classes with Room annotations. Include all fields from the v2 data model: `id`, `threadId`, `text`, `isUser`, `modality`, `timestamp`, `status`, `interrupted`, `runId`. Map `MessageModality` and `MessageStatus` enums via TypeConverters.

### 1.3 Create Room DAOs
**Complexity:** M | **Depends on:** 1.2  
`ThreadDao`: insert, update, delete, getAll (ordered by lastActiveAt desc), getById, getActive (non-archived).  
`MessageDao`: insert, getByThreadId (ordered by timestamp asc), getLatestByThreadId (preview), updateStatus, deleteByThreadId, getCount.

### 1.4 Create TalariaDatabase (Room database class)
**Complexity:** S | **Depends on:** 1.3  
`@Database(entities = [ThreadEntity, MessageEntity], version = 1)`. Provide singleton via companion object. Include migration strategy (destructive on version change for v2).

### 1.5 Add DataStore Preferences dependency
**Complexity:** S | **Depends on:** —  
Add `datasource-preferences` to `build.gradle.kts`. Create `TalariaPreferences` class wrapping DataStore with typed keys for all `AppSettings` fields.

### 1.6 Implement Settings repository
**Complexity:** S | **Depends on:** 1.5  
`SettingsRepository` class with `Flow<AppSettings>` and `suspend fun update(settings: AppSettings)`. Reads/writes via `TalariaPreferences`.

### 1.7 Implement Thread repository
**Complexity:** S | **Depends on:** 1.4  
`ThreadRepository` class wrapping ThreadDao and MessageDao with coroutine-safe operations. Methods: `createThread`, `deleteThread`, `switchThread`, `getMessages`, `insertMessage`, `updateMessageStatus`.

---

## Milestone 2: Server Protocol Extensions

The Talaria server must be extended to handle text input and multi-thread awareness.

### 2.1 Add `text_input` message handler to server.py
**Complexity:** M | **Depends on:** —  
In the WebSocket handler's `handle_text()`: parse `{"type":"text_input","text":"...","session_id":"...","thread_id":"..."}`. Skip STT (text is already transcribed). Create Hermes run with `input=text`. Stream response back via existing TTS/fallback path. Emit `text_echo` to client with `message_id`.

### 2.2 Implement `text_echo` server → client message
**Complexity:** S | **Depends on:** 2.1  
New JSON message type. Sent immediately after receiving `text_input` so the client can display the user's message in the transcript. Includes `text`, `message_id`, `seq`.

### 2.3 Extend RunStore for thread-aware storage
**Complexity:** M | **Depends on:** 2.1  
Change RunStore key from `session_id` only to `(session_id, thread_id)` tuple. Update `put`, `get`, `mark_consumed` methods. The reconnect handshake now matches on both session and thread.

### 2.4 Implement `switch_session` message handler
**Complexity:** S | **Depends on:** 2.3  
Parse `{"type":"switch_session","session_id":"...","thread_id":"..."}`. Validate session exists or create one. Respond with `{"type":"session_switched","session_id":"...","previous_session_id":"..."}`. Update server's active session tracking.

### 2.5 Extend reconnect handshake with thread context
**Complexity:** S | **Depends on:** 2.3  
Parse new optional fields in reconnect message: `thread_id`, `active_threads`. When `thread_id` is present, use it alongside `session_id` to locate undelivered runs in RunStore.

### 2.6 Add `thread_info` server → client message
**Complexity:** S | **Depends on:** 2.5  
Send after reconnect_done: `{"type":"thread_info","thread_id":"...","session_id":"...","message_count":N}`. Gives the client authoritative server-side state for the reconnected thread.

### 2.7 Add concurrent-request guard per session
**Complexity:** M | **Depends on:** 2.1  
If a Hermes run is already in progress for a given `session_id`, reject new `text_input` or `ptt_end` for that session with `{"type":"error","message":"Session busy"}`. Use an `asyncio.Lock` per session to serialize. Text input on a different thread/session is allowed concurrently.

### 2.8 Backward compatibility smoke test
**Complexity:** S | **Depends on:** 2.1–2.7  
Connect a v1 client (current APK) to the updated server. Verify: PTT recording, transcription, TTS playback, barge-in, and reconnect all work without regression.

---

## Milestone 3: Android UI — Chat Controls & Modality Switching

The core v2 UX: text input alongside PTT, mode switching, and upgraded chat bubbles.

### 3.1 Implement TalariaViewModel
**Complexity:** L | **Depends on:** 1.7, 2.1  
Central ViewModel managing all app state. Holds: `connectionState`, `currentThread`, `messages`, `allThreads`, `settings`, `pttState`, `inputMode`. Methods: `connect`, `disconnect`, `sendTextMessage`, `startRecording`, `stopRecording`, `interrupt`, `switchThread`, `createThread`, `deleteThread`, `setInputMode`, `retryMessage`. Coordinates between WebSocket client, repositories, and UI state.

### 3.2 Refactor PTTWebSocketClient for text + thread support
**Complexity:** L | **Depends on:** 2.1, 2.4  
Extend `PTTWebSocketClient` (rename to `TalariaWebSocketClient`) to:
- Accept and send `text_input` messages
- Handle `text_echo`, `session_switched`, `thread_info` server messages
- Include `thread_id` in reconnect handshake
- Track per-message status (SENDING → SENT → DELIVERED)
- Emit typed callbacks for new message types
- Maintain backward compatibility with v1 protocol paths

### 3.3 Build InputArea composable
**Complexity:** M | **Depends on:** 3.1  
`InputArea` composable with:
- `AttachButton` (disabled stub, shows toast "Coming soon")
- `MessageTextField` — `OutlinedTextField`, multiline, auto-growing up to 4 lines
- `SendButton` — visible when text is non-empty, sends text via ViewModel
- `PTTButton` — extracted from v1, now lives inside InputArea. Long-press → record. Tap → toggle `inputMode` (text ↔ voice).
- IME integration: "Send" action triggers `sendTextMessage`

### 3.4 Build ChatBubble v2 composable
**Complexity:** M | **Depends on:** 3.1  
Upgraded `ChatBubble`:
- `ModalityBadge`: 🎤 for VOICE, ⌨ for TEXT (icon + small label)
- `StatusIcon`: checkmark for SENT, double-check for DELIVERED, ⚠️ for ERROR (with retry tappable)
- `MessageTimestamp`: HH:MM:SS below bubble
- Maintain existing left/right alignment and color scheme from v1

### 3.5 Build ThinkingIndicator composable
**Complexity:** S | **Depends on:** 3.1  
Animated "⋯⋯⋯" dots indicator shown in the assistant slot while `pttState == PROCESSING` or `messageStatus == SENDING` for text messages. Uses `rememberInfiniteTransition` with staggered dot animations.

### 3.6 Build ScrollToBottomFAB
**Complexity:** S | **Depends on:** 3.1  
Floating action button that appears when the user scrolls up in the transcript. Tapping it animates scroll to the bottom. Hidden when already at the bottom.

### 3.7 Wire modality switching state machine
**Complexity:** M | **Depends on:** 3.1, 3.3  
Implement the TEXT ↔ VOICE mode toggle logic:
- Tap mic button → switch to text mode (show keyboard)
- Tap keyboard icon → switch to voice mode (hide keyboard)
- Long-press mic → always triggers PTT recording regardless of current mode
- The current thread's `sessionId` persists across mode switches
- On mode switch, the text field is preserved; nothing is lost

---

## Milestone 4: Android UI — Navigation & Settings

### 4.1 Add Navigation Compose dependency
**Complexity:** S | **Depends on:** —  
Add `navigation-compose` and `lifecycle-viewmodel-compose` to `build.gradle.kts`.

### 4.2 Build NavigationDrawer composable
**Complexity:** M | **Depends on:** 3.1  
`ModalNavigationDrawer` with:
- `DrawerHeader`: Talaria logo area, version string
- `NavItem("Home")` — closes drawer, goes to active thread
- `NavItem("Threads")` — expands to recent 5 threads, "View all →" at bottom
- `Divider`
- `NavItem("Settings")` — navigates to SettingsScreen
- `NavItem("Share Logs")` — triggers existing log sharing intent
- `NavItem("About")` — shows About dialog
- `ConnectionStatusFooter`: connection dot, server URL, thread/message counts

### 4.3 Build ThreadSwitcher composable
**Complexity:** M | **Depends on:** 3.1, 4.2  
Chip/dropdown below TopAppBar:
- Shows current thread name with 💬 icon
- Tapping opens `ModalBottomSheet` with thread list
- Thread list: `LazyColumn` of `ThreadRow` items (name, last message preview, timestamp)
- Swipe-to-delete on thread rows (with confirmation AlertDialog)
- "＋ New Thread" button at top → prompts for name → creates via ViewModel

### 4.4 Build SettingsScreen
**Complexity:** L | **Depends on:** 1.6, 4.1  
Full-screen settings page navigated to from drawer. Sections built as `SettingsGroup` composables:
- **Server**: WS URL (OutlinedTextField), CF Client ID (OutlinedTextField, masked), CF Secret (OutlinedTextField, masked). Save triggers reconnect with new URL.
- **Connection**: Auto-reconnect Switch, Reconnect delay slider, Manual reconnect button
- **Audio** (info only for v2): STT model display, TTS voice preference dropdown, Audio ducking Switch
- **Chat**: Show timestamps Switch, Max history slider (50–500), Auto-scroll Switch
- **Threads**: Default thread name field, Auto-create thread Switch
- **About**: Version, build date, licenses link
All fields backed by `SettingsRepository` Flow. Changes persist immediately.

### 4.5 Build TopAppBar v2
**Complexity:** S | **Depends on:** 4.2  
Replace v1's status bar row with a `TopAppBar`:
- Navigation icon (☰) opens drawer
- Title "Talaria"
- Actions: Connection dot (color-coded, tappable for reconnect) + Settings gear icon
- Remove the standalone `StatusBar` composable from v1

### 4.6 Wire navigation graph
**Complexity:** M | **Depends on:** 4.2, 4.4  
Set up `NavHost` with routes: `"home"` (main chat screen), `"settings"` (SettingsScreen). The drawer and TopAppBar gear icon navigate to settings. Back from settings returns to home.

---

## Milestone 5: Thread Persistence & Lifecycle

### 5.1 Implement thread auto-creation on first message
**Complexity:** S | **Depends on:** 1.7, 3.1  
When `autoCreateThread=true` in settings and no active thread exists, `sendTextMessage` or `stopRecording` calls `createThread(defaultThreadName)` automatically. The default name comes from settings.

### 5.2 Implement thread switching with message reload
**Complexity:** M | **Depends on:** 1.7, 3.1  
`switchThread(threadId)` in ViewModel: update `currentThread`, reload messages from Room for the new thread via `MessageDao.getByThreadId()`, clear UI transcript, send `switch_session` if the thread has an existing `sessionId`, update ThreadSwitcher UI.

### 5.3 Implement soft-delete for threads
**Complexity:** S | **Depends on:** 1.7, 4.3  
Swipe-to-delete in ThreadListSheet sets `isArchived=true` on the Thread entity. Archived threads are hidden from the thread list by default. A "Show archived" toggle in the sheet reveals them with an "Unarchive" option. Messages are not deleted.

### 5.4 Implement hard-delete with confirmation
**Complexity:** S | **Depends on:** 5.3  
Long-press on an archived thread → "Delete permanently" option. Deletes all messages for that thread from Room, then deletes the thread. Confirmation dialog with count of messages being deleted.

### 5.5 Thread session_id lifecycle
**Complexity:** M | **Depends on:** 1.7, 2.4  
On first Hermes response for a new thread, capture `session_id` from `session_id` server message and persist to `Thread.sessionId`. On thread switch, include the stored `session_id` in the next `reconnect` or `text_input` message. If server responds with `session_switched` and a new session ID (meaning the old one expired), update the local Thread record.

### 5.6 Cold-start thread selection
**Complexity:** S | **Depends on:** 1.7, 3.1  
On app launch, query `ThreadDao.getAll()` ordered by `lastActiveAt desc`. Select the first non-archived thread as `currentThread`. If no threads exist and `autoCreateThread=true`, create a default thread. Load its messages.

---

## Milestone 6: Seamless Modality Switching & Resilience

### 6.1 Preserve session across PTT → text and text → PTT transitions
**Complexity:** M | **Depends on:** 3.1, 3.7, 5.5  
Ensure that `currentThread.sessionId` is included in all outgoing messages (`text_input`, `ptt_end` flow, `reconnect` handshake) regardless of which modality is used. Test: start PTT → get response → type text message → verify both use same session_id in server logs. Then reverse: text → PTT.

### 6.2 Handle network handoff during modality switch
**Complexity:** M | **Depends on:** 3.1, 5.5  
Scenario: User starts PTT, network drops, auto-reconnect fires, user switches to text on reconnect. Verify:
1. `reconnect` handshake includes correct `session_id` and `thread_id`
2. Any undelivered PTT response is replayed
3. Text message sent after reconnect uses the same `session_id`
4. Thread history shows all messages in order

### 6.3 Graceful degradation when session expires
**Complexity:** S | **Depends on:** 5.5  
If the server responds to a `text_input` or `ptt_end` with a new `session_id` (different from stored), update the local Thread record silently. The user shouldn't notice — Hermes may have lost context, but the conversation can continue. Log the event for debugging.

### 6.4 Message retry on error
**Complexity:** M | **Depends on:** 3.4  
When `MessageStatus.ERROR` is set (server returned error, or send failed), show ⚠️ icon on the failed bubble. Tapping ⚠️ calls `retryMessage(id)` which re-sends the text via `sendTextMessage`. For voice, retry is not applicable (audio is ephemeral). Limit retries to 3 per message.

### 6.5 Concurrent input handling
**Complexity:** S | **Depends on:** 3.1, 3.7  
If the user sends a text message while a PTT turn is PROCESSING, queue the text message until the server returns to IDLE. Show a subtle "queued" state on the text bubble. If the server returns `{"type":"error","message":"Session busy"}`, mark the queued message as ERROR for manual retry.

---

## Milestone 7: Polish & Testing

### 7.1 Add loading/skeleton states
**Complexity:** S | **Depends on:** 3.1  
Show a `CircularProgressIndicator` while initial Room queries load on cold start. Show skeleton chat bubbles (shimmer effect) for 300ms while thread switch message load completes.

### 7.2 Add empty states
**Complexity:** S | **Depends on:** 3.1  
- **No threads**: Illustration + "Tap ＋ to start a new conversation" text
- **No messages in thread**: "Send a message or hold 🎤 to speak" prompt
- **Disconnected**: "Trying to reconnect…" with manual reconnect button

### 7.3 Accessibility pass
**Complexity:** M | **Depends on:** 3.3, 3.4, 4.2  
Add `contentDescription` to all icons and buttons. Ensure TalkBack reads message content, modality, and timestamp in a sensible order. PTT button: "Hold to talk. Double-tap and hold to record voice message."

### 7.4 Landscape / tablet layout
**Complexity:** M | **Depends on:** 3.3, 4.2  
For landscape and tablets (>600dp width): show the navigation drawer as a permanent rail on the left. Thread list becomes a side panel. Chat takes the remaining space. Settings can open as a dialog instead of full-screen.

### 7.5 End-to-end integration test
**Complexity:** L | **Depends on:** All above  
Manual test pass through all v2 features:
1. Install fresh APK → auto-creates default thread
2. Send text message → verify response in transcript
3. PTT → verify voice response with audio playback
4. Create new thread → switch → send messages → verify isolation
5. Switch back to first thread → verify messages persisted
6. Network airplane mode → auto-reconnect → resume on same thread
7. Settings: change WS URL → reconnect → verify new endpoint
8. Delete thread → verify removal from list
9. Barge-in during TTS playback → verify interrupt works
10. Modality switch: PTT → text → PTT → verify session continuity

### 7.6 Performance profiling
**Complexity:** M | **Depends on:** 7.5  
Profile with Android Studio:
- Message list scroll performance with 500+ messages (Room paging if needed)
- Room query times (ensure indexes on `thread_id`, `timestamp`)
- WebSocket send/receive latency
- Memory usage during long PTT recordings

---

## Summary: Task Breakdown by Milestone

| Milestone | Tasks | Total Complexity |
|-----------|-------|-----------------|
| M1: Foundation | 7 (1.1–1.7) | 6×S + 2×M ≈ 20h |
| M2: Server Extensions | 8 (2.1–2.8) | 5×S + 3×M ≈ 26h |
| M3: Chat & Modality | 7 (3.1–3.7) | 2×S + 2×M + 2×L ≈ 30h |
| M4: Navigation & Settings | 6 (4.1–4.6) | 2×S + 3×M + 1×L ≈ 22h |
| M5: Thread Lifecycle | 6 (5.1–5.6) | 4×S + 2×M ≈ 14h |
| M6: Resilience | 5 (6.1–6.5) | 2×S + 3×M ≈ 20h |
| M7: Polish & Testing | 6 (7.1–7.6) | 3×S + 2×M + 1×L ≈ 22h |
| **Total** | **45 tasks** | **~154 hours** |

---

## Dependency Graph

```
M1 (Foundation)
 ├─ 1.1 → 1.2 → 1.3 → 1.4 → 1.7
 ├─ 1.5 → 1.6
 └─ (1.6, 1.7) → M3, M4

M2 (Server) — parallel to M1
 ├─ 2.1 → 2.2, 2.3, 2.7
 ├─ 2.3 → 2.4, 2.5 → 2.6
 └─ 2.1..2.7 → 2.8 → M3 (3.2 depends on server protocol)

M3 (Chat & Modality) — depends on M1.7 + M2.1
 ├─ 3.1 (ViewModel) → 3.3, 3.4, 3.5, 3.6, 3.7
 ├─ 3.2 (WS Client) → 3.1
 └─ (3.3, 3.4, 3.5, 3.6, 3.7) → M4, M5, M6

M4 (Navigation & Settings) — depends on M3.1 + M1.6
 ├─ 4.1 → 4.2, 4.4, 4.6
 ├─ 4.2 → 4.3, 4.5
 └─ 4.6 (Navigation) → M7

M5 (Thread Lifecycle) — depends on M1.7 + M3.1 + M2.4
 └─ 5.1..5.6

M6 (Resilience) — depends on M3.1 + M3.7 + M5.5
 └─ 6.1..6.5

M7 (Polish & Testing) — depends on ALL above
 └─ 7.1..7.6
```

## Suggested Execution Order

1. **Week 1**: M1 (Foundation) + M2 (Server) in parallel — data layer and protocol ready
2. **Week 2**: M3 (Chat & Modality) — core UX, text+voice coexistence working end-to-end
3. **Week 3**: M4 (Navigation & Settings) + M5 (Thread Lifecycle) — drawer, settings, thread CRUD
4. **Week 4**: M6 (Resilience) — modality switching edge cases, error handling
5. **Week 5**: M7 (Polish & Testing) — accessibility, landscape, performance, integration tests
