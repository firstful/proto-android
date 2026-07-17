# Talaria — Specification

## Overview

Talaria is a real-time voice PTT (Push-to-Talk) gateway for Hermes Agent. It handles WebSocket connections from Android clients for voice interactions with AI agents.

## Protocol

### WebSocket Messages

#### Client → Server
- `ptt_start`: PTT button pressed
- `ptt_end`: PTT button released
- `interrupt`: Barge-in mid-turn
- `cancel`: Cancel current operation
- `ping`: Keepalive
- `ack`: Acknowledge receipt of sequence number
- `text_input`: Text input (v2)
- `switch_session`: Switch session
- `list_sessions`: List recent Hermes sessions
- `fork_session`: Resume a session via fork (payload: `{ session_id: string }`)
- `get_session_history`: Get transcript messages (payload: `{ session_id: string }`)
- `set_profile`: Switch active Hermes profile (payload: `{ profile: string }`)

#### Server → Client
- `ptt_start_echo`: PTT start acknowledged
- `processing`: Status update (stt, thinking, tts)
- `stt_transcript`: Speech-to-text result (final)
- `stt_partial`: Partial transcript during recording (streaming STT)
- `tts_start`: TTS streaming started
- `tts_end`: TTS streaming completed
- `tts_fallback`: Text fallback for unavailable TTS
- `session_id`: Hermes session identifier
- `error`: Error occurred
- `interrupted`: Barge-in acknowledged
- `pong`: Ping response
- `reconnect_done`: Reconnection completed
- `session_switched`: Session switch event
- `sessions_list`: List of recent Hermes sessions
- `session_history`: Transcript messages for a session
- `file_content`: File content from `/api/file` endpoint

### Binary Messages
- Audio data: Opus-encoded audio chunks

## State Machine

```
IDLE → RECORDING → TRANSCRIBING → HERMES_RUNNING → TTS_STREAMING → IDLE
                                        ↓
                                    INTERRUPTED → IDLE
```

## Configuration

### Environment Variables
- `HOST`: Server host (default: 0.0.0.0)
- `PORT`: Server port (default: 8321)
- `HERMES_API_URL`: Hermes API URL
- `HERMES_API_KEY`: Hermes API key (use `{SECRETS.HERMES_API_KEY}`)
- `HERMES_PROFILES`: Profile definitions (format: `name=url,...`)
- `HERMES_PROFILE_INSTRUCTIONS`: Profile instruction overrides (format: `name=instr,...`)
- `ALLOWED_FILE_ROOTS`: Comma-separated list of allowed file paths (default: `/srv,/home,/tmp`)
- `STT_MODEL`: STT model name (default: base)
- `TTS_VOICE`: TTS voice (default: en-US-ChristopherNeural)
- `MAX_AUDIO_CHUNK_SIZE`: Max audio chunk size (default: 5MB)
- `MAX_AUDIO_BUFFER_SIZE`: Max audio buffer size (default: 50MB)

## API

### Health Check
```
GET /health
Response: { status, stt_loaded, tts_available, hermes_api, pending_replays }
```

### Profiles
```
GET /api/profiles
Response: { profiles: [{ name, url }], models: [...] }
```

### File Reading
```
GET /api/file?path=/path/to/file.txt
Response: { content: "file contents..." }
Error: 403 if path not in ALLOWED_FILE_ROOTS
Error: 404 if file not found
```

### WebSocket PTT
```
WS /v1/ptt
```

## Features

### Streaming STT
- Partial transcripts emitted during recording using LocalAgreement-2 algorithm
- Text only emitted when two consecutive Whisper outputs agree on a prefix
- Trades latency for stability — avoids showing unstable intermediate results
- Emits `stt_partial` messages to client during recording

### Playback Speed
- Android client supports 1x/1.25x/1.5x/1.75x/2x playback speeds
- Speed toggle appears in TopAppBar during playback or when speed ≠ 1x
- Persisted in DataStore via `playbackSpeed` setting
- Media3 handles pitch correction automatically

### Hermes Session Management
- Session listing via `list_sessions` WebSocket command
- Session forking via `fork_session` (creates snapshot branch, avoids concurrent access)
- Session history retrieval via `get_session_history`
- Profile switching via `set_profile`

### Rich Content Rendering
- Code blocks (```) rendered with dark background and monospace font
- Inline code (`) rendered with subtle background
- Bold text (**) rendered with bold font weight
- Clickable file paths open file viewer dialog
- File viewer shows file content with syntax highlighting

### Hermes Profiles
- Multiple Hermes profiles supported via `HERMES_PROFILES` env var
- Profiles can have different base URLs or instruction overrides
- Profile picker in Settings screen
- Profile selection persisted in DataStore

## Security

- API key injected via `{SECRETS.HERMES_API_KEY}` pattern
- Input validation prevents injection attacks
- Sensitive data redacted in logs
- File reading restricted to `ALLOWED_FILE_ROOTS`
- Profiles isolated via Hermes home directories
