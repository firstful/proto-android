# Android App Specification

## Overview

The Talaria Android app is a single-purpose push-to-talk client. It connects to `wss://talaria.ffilt.us/v1/ptt` via WebSocket with Cloudflare Access authentication, captures audio while the user holds a button, streams it to Talaria, and plays the response audio.

The app supports **barge-in** — pressing the PTT button while the agent is processing or while audio is playing immediately interrupts the current turn and starts a new recording.

## Requirements

- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 34
- **Permissions**: `RECORD_AUDIO`, `INTERNET`, `MODIFY_AUDIO_SETTINGS`
- **Orientation**: Portrait-locked (the PTT button is designed for thumb reach)
- **Wake lock**: Partial wake lock while PTT is active (prevents CPU sleep during audio processing)

## UI Specification

### Layout

```
┌──────────────────────────────────────┐
│         Status Bar Area              │
│                                      │
│              ●                        │
│         (connection indicator)       │
│                                      │
│       ┌──────────────────────┐       │
│       │                      │       │
│       │   C H A T  L O G     │       │
│       │   (scrollable)       │       │
│       │   auto-scrolls ▼     │       │
│       │                      │       │
│       │  ┌──────────────┐    │       │
│       │  │ 🎤 You said… │    │       │
│       │  │ 12:34:01     │    │       │
│       │  └──────────────┘    │       │
│       │  ┌──────────────┐    │       │
│       │  │ 🤖 Response… │    │       │
│       │  │ 12:34:05     │    │       │
│       │  └──────────────┘    │       │
│       │                      │       │
│       └──────────────────────┘       │
│                                      │
│          ┌──────────────┐           │
│          │              │           │
│          │   🎤 PTT    │           │
│          │   (hold)    │           │
│          │              │           │
│          └──────────────┘           │
│                                      │
└──────────────────────────────────────┘
```

### PTT Button

- **Size**: 80dp diameter circle
- **Position**: Centered horizontally, 48dp above bottom safe area
- **Color**:
  - Idle: `#6366F1` (Indigo 500) — ready to talk
  - Pressed: `#EF4444` (Red 500) with 90% scale animation — recording
  - Processing: `#F59E0B` (Amber 500) with pulsing animation — waiting for reply
  - Playing: `#22C55E` (Green 500) with ripple — response playing
- **Haptic**: Short tick on touch down AND touch up
- **Interaction**:
  - Touch down → barge-in check → start recording
  - Touch up → stop recording, send PTT_END
  - Swipe down-to-cancel → discard audio (optional, future)

### Barge-in Behaviour

When the user presses the PTT button while a turn is in progress, the app interrupts immediately:

| Current State | Barge-in Action |
|---------------|-----------------|
| **PLAYING** (agent audio playing back) | Stop ExoPlayer instantly. Send `interrupt` to Talaria. Start new recording. |
| **PROCESSING** (Hermes still thinking / TTS generating) | Send `interrupt` to Talaria. Talaria POSTs to `/v1/runs/{id}/stop` to kill the active run. Start new recording. |
| **RECORDING** (already holding button) | No action — already recording. |
| **IDLE / ERROR** | Normal PTT behaviour — start recording. |

On release after a barge-in, the new audio is sent as a **fresh PTT turn**. The old interrupted run's context is discarded; the new run starts with continuity from the last completed turn's session_id.

### Chat Log (Transcript Area)

- Single unified scrollable column (RecyclerView / LazyColumn)
- Shows the conversation: user utterances and assistant replies interleaved
- Each message:
  - User: 🎤 *transcript text* (right-aligned, indigo background)
  - Assistant: 🤖 *reply text* (left-aligned, slate background)
  - Timestamp: HH:MM:SS below each message
- Caps at 50 messages before auto-popping the oldest
- **Auto-scrolls to bottom** on every new message
- **During barge-in**: the interrupted assistant message is shown with a ✂️ "— interrupted" badge appended
- **During processing**: shows a "•••" animated dots indicator in the assistant slot

### Status Indicator

- Circle in top-left corner
  - Green: connected
  - Yellow: connecting / reconnecting
  - Red: disconnected / error
  - Pulse yellow: recording / processing
- Tap to reconnect

### State Machine

```
                              ┌──[touch down barge-in]──┐
                              │                           │
                              ▼                           │
IDLE ──[touch down]──> RECORDING <──┐                    │
  ▲                       │         │                    │
  │                  [touch up] [barge-in]                │
  │                       ▼         │                    │
  │                    SENDING ─────┘                    │
  │                       │                              │
  │                  [interrupt]                         │
  │                       │                              │
  │                       ▼                              │
  │                    INTERRUPTED ──[auto]──┘            │
  │                                                       │
  │                  [stt_received]                       │
  │                       │                              │
  │                       ▼                              │
  │                    PROCESSING ───[barge-in]───────────┘
  │                       │
  │                  [reply received]
  │                       │
  │                       ▼
  │                    PLAYING ─────[barge-in]────────────┘
  │                       │
  │                  [playback done]
  │                       │
  └───────────────────────┘
```

## Audio Specification

### Capture

| Property | Value |
|----------|-------|
| Source | `MediaRecorder.AudioSource.MIC` |
| Sample rate | 16000 Hz |
| Encoding | PCM 16-bit |
| Channels | 1 (mono) |
| Buffer size | 4096 bytes |
| Opus bitrate | 24 kbps |
| Opus frame size | 20ms |

**Implementation:**
- `AudioRecord` with `READ_BLOCKING` mode
- Worker thread reads PCM buffers in a loop
- Each buffer: encode with Opus (via JNI / libopus Android NDK), wrap in OGG page, send over WebSocket
- On button release: flush remaining PCM, send final Ogg page, then `ptt_end` JSON
- On barge-in interrupt: discard any partial PCM buffer, start fresh recording

### Playback

| Property | Value |
|----------|-------|
| Engine | Media3 `ExoPlayer` |
| Stream type | `AudioManager.STREAM_VOICE_CALL` (for PTT-like audio routing) |
| Buffer type | Ring buffer feeding ExoPlayer |
| Codec | Opus in OGG container (pass-through) |

**Implementation:**
- ExoPlayer configured with a custom `DataSource` backed by a blocking ring buffer
- WebSocket binary frames are written to the ring buffer
- ExoPlayer reads from ring buffer and decodes Opus/OGG natively
- On `tts_end`: the ring buffer is closed (signal end-of-stream)
- **On barge-in**: ExoPlayer is `stop()`ed immediately, ring buffer is drained

## WebSocket Client

### Library: OkHttp

```kotlin
val client = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)  // no read timeout for streaming
    .pingInterval(30, TimeUnit.SECONDS)      // keepalive
    .build()

val request = Request.Builder()
    .url("wss://talaria.ffilt.us/v1/ptt")
    .addHeader("CF-Access-Client-Id", BuildConfig.CF_ACCESS_CLIENT_ID)
    .addHeader("CF-Access-Client-Secret", BuildConfig.CF_ACCESS_CLIENT_SECRET)
    .build()
```

### Message Handling

- **onOpen**: Update status indicator to green, enable PTT button
- **onMessage (text)**: Parse JSON, dispatch:
  - `stt_transcript` → append to transcript (user side, update the pending bubble)
  - `thinking` → show "•••" animated indicator in assistant slot
  - `tts_start` → finalize assistant message text in transcript, begin playback
  - `tts_end` → signal end of audio stream
  - `tts_fallback` → show reply text with 🔊 icon (text-only, no audio)
  - `session_id` → store for continuity across turns
  - `error` → show error toast, return to IDLE
- **onMessage (binary)**: Write bytes to ExoPlayer ring buffer
- **onFailure/onClosing**: Update status indicator, attempt reconnect with backoff
- **Barge-in handling**: On touch-down during PLAYING or PROCESSING:
  1. Send `{"type": "interrupt"}` JSON text frame
  2. Stop ExoPlayer / discard ring buffer
  3. Cancel any pending TTS state
  4. Start audio capture fresh

## Configuration

Build properties (via `local.properties`):

| Property | Default | Description |
|----------|---------|-------------|
| `cfAccessClientId` | `""` | Cloudflare Access service token client ID |
| `cfAccessClientSecret` | `""` | Cloudflare Access service token secret |
| `talariaWsUrl` | `wss://talaria.ffilt.us/v1/ptt` | WebSocket endpoint URL |

## Permissions Handling

- On first launch, request `RECORD_AUDIO` with Android's standard rationale dialog
- If denied, show a permanent disabled state on the PTT button with a tooltip
- `INTERNET` and `MODIFY_AUDIO_SETTINGS` are auto-granted at install time

## Error Handling

| Scenario | Behaviour |
|----------|-----------|
| Network unavailable | PTT button disabled, status shows "Offline" |
| WebSocket disconnected mid-recording | Cancel recording, show error toast, auto-reconnect |
| WebSocket disconnected mid-playback | ExoPlayer finishes buffered audio, status shows "Disconnected" |
| Audio too short | Show error toast "Hold longer to speak", return to IDLE |
| No speech detected | Show error toast "No speech detected", return to IDLE |
| Hermes error | Show error toast with Hermes error message |
| Barge-in while idle | No-op (already idle) |
| Permission denied | PTT button disabled permanently until permission granted |

## Build Config

`cfAccessClientId` and `cfAccessClientSecret` are injected at build time via `local.properties`:

```properties
cfAccessClientId=b71f3d1a2b3c4d5e6f7a8b9c0d1e2f3a.access
cfAccessClientSecret=b71f3d1a2b3c4d5e6f7a8b9c0d1e2f3a
talariaWsUrl=wss://talaria.ffilt.us/v1/ptt
```
