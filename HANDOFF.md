# Talaria — Handoff

**Date:** 2026-07-05
**Status:** Refactored, modular architecture implemented, new features added

---

## What Is Talaria

Real-time voice PTT (Push-to-Talk) gateway for Hermes Agent. Android app → Cloudflare Tunnel → Talaria server (STT/TTS) → Hermes API.

---

## Architecture (Modular)

```
repos/talaria/
├── server.py                    # Main FastAPI server (278 lines)
├── src/
│   ├── core/                    # Core components
│   │   ├── types.py            # Type definitions (276 lines)
│   │   ├── state_machine.py    # PTT state machine (77 lines)
│   │   ├── connection.py       # WebSocket connection (228 lines)
│   │   ├── audio_buffer.py     # Audio buffer management (119 lines)
│   │   ├── replay_store.py     # Connection replay storage (121 lines)
│   │   └── audio_validator.py  # Input validation (206 lines)
│   ├── audio/                   # Audio processing
│   │   ├── stt.py              # Speech-to-text (143 lines)
│   │   └── tts.py              # Text-to-speech (180 lines)
│   ├── protocol/                # Protocol handling
│   │   └── websocket_server.py # WebSocket handler (298 lines)
│   └── services/                # External services
│       └── hermes_client.py    # Hermes API client (215 lines)
├── hermes_api_server.py.unused # Hermes API proxy (unused, 208 lines)
├── agent-stack.json            # Deployment config
├── requirements.txt            # Python dependencies
├── pyproject.toml             # Project configuration
├── mypy.ini                   # Type checking config
├── pyrightconfig.json         # Pyright config
├── Dockerfile                 # Container build
├── README.md                  # Documentation
└── HANDOFF.md                 # This file
```

---

## Components

### Core (src/core/)
- **types.py**: Type definitions, enums, validation utilities
- **state_machine.py**: PTT state machine with validated transitions
- **connection.py**: WebSocket connection lifecycle management
- **audio_buffer.py**: Bounded audio storage with size limits
- **replay_store.py**: In-memory run storage for connection replay
- **audio_validator.py**: Input validation and sanitization

### Audio (src/audio/)
- **stt.py**: Speech-to-text using faster-whisper (STTProcessor + StreamingSTTProcessor)
- **tts.py**: Text-to-speech using edge-tts

### Protocol (src/protocol/)
- **websocket_server.py**: WebSocket message handling and PTT protocol

### Services (src/services/)
- **hermes_client.py**: Hermes API client with retry logic, session management, and profile support

---

## New Features

### Streaming STT
- **StreamingSTTProcessor** in `src/audio/stt.py` provides partial transcripts during recording
- Uses LocalAgreement-2 algorithm: text emitted only when two consecutive Whisper outputs agree on a prefix
- Trades latency for stability — avoids showing unstable intermediate results
- Emits `stt_partial` messages to the client during recording

### Playback Speed
- Android client supports 1x/1.25x/1.5x/1.75x/2x playback speeds
- Speed toggle appears in TopAppBar during playback or when speed ≠ 1x
- Persisted in DataStore via `playbackSpeed` setting
- Media3 handles pitch correction automatically — no chipmunk voice at 2x

### Deep Hermes Integration
- **Session Listing**: `list_sessions` WebSocket command returns recent Hermes sessions
- **Session Forking**: `fork_session` WebSocket command resumes a session via `POST /api/sessions/{id}/fork`
- **Session History**: `get_session_history` WebSocket command retrieves transcript messages
- **Profile Selection**: `set_profile` WebSocket command switches active Hermes profile
- **File Reading**: `GET /api/file?path=...` endpoint reads files from allowed roots (`ALLOWED_FILE_ROOTS` env var)

### Rich Content Rendering
- Android ChatBubble uses `RichContentText` composable for bot messages
- Supports code blocks (```), inline code (`), bold text (**), and clickable file paths
- File viewer dialog shows file content when tapping on file paths
- Code blocks rendered with dark background and monospace font

### Hermes Profiles
- Server supports multiple Hermes profiles via `HERMES_PROFILES` env var (format: `name=url,...`)
- Profiles can have different base URLs or instruction overrides
- `GET /api/profiles` endpoint lists available profiles
- Settings screen includes profile picker dropdown

---

## Configuration

Environment variables:
- `HOST`: Server host (default: 0.0.0.0)
- `PORT`: Server port (default: 8321)
- `HERMES_API_URL`: Hermes API URL
- `HERMES_API_KEY`: Hermes API key (use `{SECRETS.HERMES_API_KEY}`)
- `HERMES_PROFILES`: Profile definitions (format: `name=url,...`)
- `HERMES_PROFILE_INSTRUCTIONS`: Profile instruction overrides (format: `name=instr,...`)
- `ALLOWED_FILE_ROOTS`: Comma-separated list of allowed file paths (default: `/srv,/home,/tmp`)
- `STT_MODEL`: STT model name (default: base)
- `TTS_VOICE`: TTS voice (default: en-US-ChristopherNeural)

---

## Deployment

```bash
# Build and deploy via agent-stack
./controller build talaria
./controller deploy talaria
```

---

## Key Design Decisions

1. **Modular Architecture**: Split monolithic server into focused modules
2. **Type Safety**: Comprehensive type annotations throughout
3. **Bounded Buffers**: Audio buffers have size limits to prevent OOM
4. **Input Validation**: All inputs validated before processing
5. **Connection Replay**: Support for resuming after disconnections
6. **Streaming STT**: LocalAgreement-2 for stable partial transcripts
7. **Fork over Resume**: Session continuation uses Hermes fork API to avoid concurrent access issues
8. **File Reading Server-side**: Files read server-side for security, restricted to allowed roots

---

## Security

- Hardcoded API key replaced with `{SECRETS.HERMES_API_KEY}` pattern
- Input validation prevents injection attacks
- Sensitive data redacted in logs
- File reading restricted to `ALLOWED_FILE_ROOTS`
- Profiles isolated via Hermes home directories

---

## Testing

```bash
# Type checking
mypy src/
pyright src/

# Run tests (when implemented)
pytest tests/
```
