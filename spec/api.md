# API Specification

## WebSocket Endpoint: `/v1/ptt`

### Connection

```
wss://talaria.ffilt.us/v1/ptt
```

**Required headers:**
```
CF-Access-Client-Id: <access-client-id>
CF-Access-Client-Secret: <access-client-secret>
```

### Protocol

The protocol is asymmetric: client sends audio, server sends control messages and audio.

#### Client → Server (binary frames)

Opus audio packets in OGG container. The client captures PCM from the microphone, encodes with libopus at 24kbps, and sends the resulting OGG pages as binary WebSocket frames.

The client SHOULD send frames frequently (every 20-60ms of audio) for smooth streaming, but Talaria buffers until it receives a PTT_END control message before processing.

#### Client → Server (text frames)

JSON control messages:

| Type | Fields | Description |
|------|--------|-------------|
| `ptt_end` | `{}` | User released PTT button. Talaria processes the accumulated audio. |
| `cancel` | `{}` | Discard accumulated audio, no processing. |
| `interrupt` | `{}` | Barge-in: user pressed PTT mid-turn. Talaria stops the active Hermes run and audio processing. Client starts new recording. |
| `ping` | `{}` | Keepalive. Server responds with `pong`. |
| `reconnect` | `{"session_id":"...", "last_seq": N}` | **Async replay handshake.** Sent immediately after WebSocket open. Declares the client's last-known session ID and the highest sequence number it has processed. Server replays any undelivered run or responds with `reconnect_done`. |
| `ack` | `{"seq": N}` | Periodic acknowledgment (every 5s). Client reports the highest sequence number it has processed. Used for server-side delivery tracking. |

#### Server → Client (text frames)

JSON control messages — **every message includes a monotonically increasing `seq` field** for delivery tracking:

| Type | Fields | Description |
|------|--------|-------------|
| `processing` | `{"step": "stt" \| "thinking" \| "tts"}` | Status update for UI |
| `stt_transcript` | `{"text": "..."}` | User's speech recognized as text |
| `tts_start` | `{"text": "..."}` | Hermes reply text, about to synthesize |
| `tts_end` | `{}` | TTS stream complete |
| `tts_fallback` | `{"text": "..."}` | TTS unavailable; display the text instead |
| `interrupted` | `{"run_id": "..."}` | Barge-in acknowledged — previous Hermes run stopped |
| `error` | `{"message": "..."}` | Processing error |
| `pong` | `{}` | Response to `ping` |
| `session_id` | `{"id": "..."}` | Hermes session ID for continuity |
| `reconnect_done` | `{"replayed": bool, "complete": bool?, "interrupted": bool?}` | Response to `reconnect` handshake. `replayed=true` means the server delivered an undelivered previous run. `complete` indicates whether the replayed TTS was fully streamed. |

#### Server → Client (binary frames)

Opus audio packets in OGG container. The client feeds these directly to the audio decoder for playback. Frames arrive sequentially; the first frame after `tts_start` begins the response audio.

### Audio Format

Since the only consumer is the operator (Reed), the protocol uses raw PCM for maximum simplicity on the Android side. No Opus encoding on the device.

| Property | Client→Server | Server→Client |
|----------|---------------|---------------|
| Format | Raw PCM s16le | Opus in OGG container |
| Sample rate | 16 kHz | 16 kHz |
| Channels | 1 (mono) | 1 (mono) |
| Bit depth | 16-bit signed integer | 24 kbps Opus |
| Frame size | 4096 bytes (256 samples) | 20ms Opus frames |

The asymmetry is intentional: the phone sends raw PCM (zero encoding cost, zero latency on the capture path), and the server handles the expensive Opus encoding only for the response path where bandwidth matters.

### Client→Server Audio Processing

1. Android `AudioRecord` captures 16-bit PCM at 16kHz mono into 4096-byte buffers
2. Each buffer is sent immediately as a binary WebSocket frame (streaming during recording)
3. On PTT release (`ptt_end`), Android sends any remaining buffered frames
4. Talaria concatenates all received PCM buffers into a single float32 array
5. faster-whisper transcribes the PCM audio to text

### Server→Client Audio Processing

1. Hermes reply text is fed to edge-tts
2. edge-tts produces MP3 stream
3. ffmpeg transcodes MP3 → Opus/OGG (16kHz mono, 24kbps)
4. Opus pages are sent as binary WebSocket frames
5. Android ExoPlayer decodes and plays them in sequence

### Session Continuity

After each PTT→response cycle, Talaria reports the Hermes session ID:

```json
{"type": "session_id", "id": "sess_abc123..."}
```

The Android client stores this and includes it in the next PTT request via a JSON control message before sending audio:

```json
{"type": "session", "id": "sess_abc123..."}
```

This preserves conversation context across PTT turns.

### Error Responses

All errors are delivered as JSON control messages:

```json
{"type": "error", "message": "Audio too short — less than 0.5 seconds"}
{"type": "error", "message": "No speech detected in audio"}
{"type": "error", "message": "Hermes API error: Connection refused"}
```

The WebSocket connection remains open after errors unless the error is fatal.

### Interrupt / Barge-in Flow

When the user presses PTT mid-turn, the client sends `{"type": "interrupt"}` and the server:

1. If a Hermes run is active (PROCESSING state): `POST /v1/runs/{run_id}/stop` to abort it
2. If TTS is being streamed (PLAYING state): abort the TTS generation and ffmpeg process
3. Save the partial run result to the RunStore for potential replay
4. Drain any buffered audio, discard partial state
5. Respond with `{"type": "interrupted", "run_id": "..."}` to confirm
6. Wait for the client to start a new PTT recording

The client then begins fresh audio capture. The old run's context is **discarded** — the new PTT turn starts as a new `/v1/runs` call. Session continuity is maintained via the `session_id` from the last *completed* turn (interrupted runs are not completed, so the session rolls back to the last clean turn).

### Async Replay (Connection Resilience)

Talaria survives WebSocket interruptions by decoupling Hermes run completion from connection lifetime. Every run result (transcript, reply text, TTS audio chunks) is stored in an in-memory **RunStore** keyed by Hermes session ID.

#### Sequence Numbers

Every server→client message (JSON text or binary audio) carries a monotonically increasing `seq` number. JSON messages embed it in the `seq` field. Binary audio frames are implicitly sequenced by order of delivery; the server's TTS loop assigns seqs to each chunk.

The client tracks `lastSeq` — the highest seq it has fully processed (rendered to screen or played through speaker). It periodically reports this to the server via `ack` messages.

#### Reconnect Flow

```
Client                              Server
  │                                   │
  ├─ WebSocket open ──────────────────┤
  ├─ {"type":"reconnect",             │
  │    "session_id":"sess_abc",       │
  │    "last_seq":42} ────────────────┤
  │                                   ├─ Check RunStore for session "sess_abc"
  │                                   │
  │  ┌── If undelivered run found:    │
  │  │  {"type":"session_id",...}  ←──┤
  │  │  {"type":"stt_transcript",..}←─┤
  │  │  {"type":"tts_start",...}   ←──┤
  │  │  [binary audio chunks N..M] ←──┤  replay saved audio
  │  │  {"type":"tts_end"}        ←──┤
  │  │  {"type":"reconnect_done",     │
  │  │   "replayed":true}         ←──┤
  │  │                                │
  │  └── If nothing to replay:        │
  │     {"type":"reconnect_done",     │
  │      "replayed":false}        ←──┤
  │                                   │
  ├─ {"type":"ack","seq":N} ──────────┤  periodic (every 5s)
```

#### RunStore Lifecycle

- **Created**: When a Hermes run completes and TTS starts. The reply text and first audio chunk create the entry.
- **Populated**: Audio chunks are appended during TTS streaming.
- **Completed**: When `tts_end` is sent, `complete=true`.
- **Consumed**: When a reconnecting client successfully receives the full replay, the run is evicted.
- **Expired**: Runs older than 300 seconds (5 minutes) are purged by a background cleanup task.

#### Client Auto-Reconnect

The Android client implements exponential backoff on WebSocket `onFailure` and `onClosed`:

| Attempt | Delay |
|---------|-------|
| 1st | 1s |
| 2nd | 2s |
| 3rd | 4s |
| 4th | 8s |
| 5th+ | 16s (capped at 30s) |

Session ID and last processed sequence number persist across reconnects within the app's lifetime, ensuring the client can pick up where it left off.

#### Audio Replay Buffering

During reconnect replay, the server may send audio chunks before the client has set up its audio pipeline (ExoPlayer). The client buffers these chunks in a `replayAudioBuffer` deque. When `tts_start` arrives, it drains the buffer into the freshly initialized player before accepting live chunks, producing seamless playback.

## Hermes API Integration

Talaria connects to Hermes API Server via its internal stack-net hostname:

```
http://hermes--api:9119
```

### `/v1/runs` — Start a run

```http
POST /v1/runs
Content-Type: application/json

{
  "messages": [
    {"role": "user", "content": "<transcribed text>"}
  ],
  "stream": true,
  "session_id": "<optional-session-id>"
}
```

**Response (202):**
```json
{
  "run_id": "run_abc123",
  "session_id": "sess_def456",
  "status": "queued"
}
```

### `/v1/runs/{run_id}/events` — SSE stream

```http
GET /v1/runs/{run_id}/events
Accept: text/event-stream
```

**Events:**
```
data: {"type": "message", "content": {"type": "text", "text": "Hello..."}}
data: {"type": "delta", "content": {"type": "text", "text": " world"}}
data: {"type": "done"}
data: {"type": "error", "message": "..."}
```

Talaria accumulates `message` and `delta` events into the final reply text, then triggers TTS when `done` is received.

### Connection Pool

Talaria maintains a single HTTP/2 connection pool to the Hermes API server to avoid connection overhead per PTT turn.

## Health Endpoint

```
GET /health
```

**Response:**
```json
{
  "status": "ok",
  "stt_loaded": true,
  "tts_available": true,
  "hermes_api": "http://hermes--api:9119",
  "version": "0.1.0"
}
```

This endpoint is NOT behind Cloudflare Access (used by agent-stack health checks).
