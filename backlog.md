# Talaria — Backlog

## Overview

Real-time voice gateway for Hermes Agent. Android PTT app → Cloudflare Tunnel → Talaria (STT/TTS) → Hermes API Server. Full-duplex audio pipeline with push-to-talk. Renamed from Apollo on 2026-07-04.

## Architecture

```
[Android App] ──PTT audio──→ Cloudflare Tunnel (CF-Access auth)
    → slot-proxy → Talaria (STT → Hermes API → TTS)
    ← audio response ← Talaria ───────────────┘
```

## Status

- **Completion**: 5/9 deliverables done (Android app, WebSocket PTT, Cloudflare Access, Hermes API client, OTA update infra complete)
- **Phase/Stage**: Alpha — Android app functional with PTT, async audio pipeline, message history, OTA self-updater, and feedback command
- **Last updated**: 2026-07-05

## Deliverables

### Android App: Kotlin/Jetpack Compose PTT (5/5 complete)
- [x] PTT push-to-talk button with pulse animation, icon change, recording meter
- [x] Text input mode with tappable hint and proper toggle
- [x] Message history with status indicators (queued/sending/sent/delivered), offline queue, client_message_id
- [x] Thread-safe persistence (Room DB — fix FK race on thread+message creation)
- [x] App icon from user-provided image

### WebSocket PTT Endpoint (3/3 complete)
- [x] Async pipeline for audio — status tracking (QUEUED/SENDING/SENT/DELIVERED) + offline queueing
- [x] Background model init — server starts serving immediately
- [x] Fix container network host mode — health check port mapping

### Cloudflare Access (1/1 complete)
- [x] CF-Access-Client-Id/Secret headers on Android app; fix default creds handling
- [x] App icon from user-provided image (already tracked above)

### Hermes API Client (3/3 complete)
- [x] `/v1/runs` + SSE event streaming integration
- [x] Thread-safe conversation management with default thread creation
- [x] Add feedback command and update WebSocket protocol spec

### OTA Self-Updater (2/2 complete)
- [x] Add self-updater: OTA update via install.ffilt.us
- [x] OTA CF headers, thread safety in v182

### Talaria Container Service (0/1 complete)
- [ ] Containerized Python FastAPI server with agent-stack service definition — builds blocked by systemic `podman build` failures

### STT Pipeline (0/1 complete)
- [ ] faster-whisper local transcription — not yet implemented

### TTS Pipeline (0/1 complete)
- [ ] EdgeTTS / configurable provider — not yet implemented

### Deploy (0/1 complete)
- [ ] Blue-green slot swap via agent-stack controller — blocked by build failures

## Open Questions / Blockers

- [ ] **BLOCKING**: All container builds failing systemically — `podman build --memory 4g` fails identically for all services (engramic, vault, etc.). Build infrastructure needs investigation before Talaria container can deploy.
- [ ] Hermes API server URL and auth (service token or local network)
- [ ] CF-Access service token credentials to embed in Android app

## Notes

- STT: faster-whisper with `base` model for low latency (planned)
- TTS: EdgeTTS (free, local, no API key) with ffmpeg for Opus conversion (planned)
- Audio format: Opus in OGG container (native Telegram/Android support)
- Android uses ExoPlayer/Media3 for streaming playback
- WebSocket protocol: binary audio frames, JSON control messages
- Hermes API uses `/v1/runs` with SSE for structured lifecycle events
- Renamed from Apollo → Talaria on 2026-07-04 (package, repo dir, domain, all references)
- 25 commits in the past 24h covering all Android app features
- Crash handler, log buffer, share logs all implemented
