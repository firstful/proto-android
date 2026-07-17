# Architecture

## System Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android Phone                            │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                   Talaria App                               │  │
│  │                                                           │  │
│  │  [PTT Button (hold)]  → AudioRecord (16kHz PCM)          │  │
│  │       ↓                                                  │  │
│  │  Opus Encode → OkHttp WebSocket → wss://talaria.ffilt.us  │  │
│  │       ↑                                                  │  │
│  │  Media3 ExoPlayer ← Opus frames ← WebSocket receive     │  │
│  │                                                           │  │
│  │  Headers: CF-Access-Client-Id, CF-Access-Client-Secret    │  │
│  └───────────────────────────────────────────────────────────┘  │
└──────────────────────┬──────────────────────────────────────────┘
                       │ wss://talaria.ffilt.us/v1/ptt
                       │ (via Cloudflare Tunnel)
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Cloudflare (Edge)                              │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Validate CF-Access Service Token (JWT)                   │  │
│  │  Forward to cloudflared tunnel → slot-proxy               │  │
│  └───────────────────────────────────────────────────────────┘  │
└──────────────────────┬──────────────────────────────────────────┘
                       │ 127.0.0.1:8321
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Talaria Container (agent-stack)                │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              FastAPI WebSocket Server (server.py)         │  │
│  │                                                           │  │
│  │  1. Receive Opus frames → decode to PCM                  │  │
│  │  2. STT: faster-whisper → text transcript                │  │
│  │  3. Send transcript to Hermes API via HTTP               │  │
│  │  4. Receive reply text from Hermes                       │  │
│  │  5. TTS: edge-tts → ffmpeg → Opus/OGG stream             │  │
│  │  6. Stream Opus frames back to client                     │  │
│  │                                                           │  │
│  │  Outbound: POST /v1/runs → Hermes API Server             │  │
│  │             SSE  /v1/runs/{id}/events                     │  │
│  └───────────────────────────────────────────────────────────┘  │
└──────────────────────┬──────────────────────────────────────────┘
                       │ http://hermes--api:9119 (stack-net DNS)
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│              Hermes API Server Container                        │
│                                                                 │
│  POST /v1/runs           → start agent run                     │
│  GET  /v1/runs/{id}      → poll status                         │
│  GET  /v1/runs/{id}/events → SSE lifecycle events              │
│                                                                 │
│  Runs the Hermes AI agent loop with full tool access, memory,   │
│  skills — as if you'd typed the query in Telegram.              │
└─────────────────────────────────────────────────────────────────┘
```

## Network Flow

1. **Android → Cloudflare**: WebSocket upgrade request with `CF-Access-Client-Id` and `CF-Access-Client-Secret` headers
2. **Cloudflare → Tunnel**: Validates service token, forwards to cloudflared daemon on the homelab
3. **Tunnel → slot-proxy**: Routes to `talaria--slot{b}:8321` based on runtime/services.json
4. **slot-proxy → Talaria**: WebSocket upgrade proxied to Talaria container
5. **Talaria → Hermes API**: HTTP POST `/v1/runs` and SSE `/v1/runs/{id}/events` — local traffic on stack-net
6. **Response flows back**: Audio bytes streamed over WebSocket through the reverse path

## Audio Pipeline

```
Analog mic → AudioRecord PCM 16-bit 16kHz mono
    → Opus encoder (libopus, 24kbps)
    → WebSocket binary frame
    → Talaria Opus decoder
    → float32 numpy array
    → faster-whisper transcribe
    → text string
    → Hermes POST /v1/runs
    → reply text
    → edge-tts MP3 stream
    → ffmpeg libopus encoder
    → Opus/OGG packets
    → WebSocket binary frames
    → Android ExoPlayer
    → Speaker
```

## Components

| Component | Language | Runtime | Dependencies |
|-----------|----------|---------|-------------|
| Talaria server | Python 3.12 | Podman container | FastAPI, faster-whisper, edge-tts, ffmpeg |
| Android app | Kotlin | Android 8+ (API 26) | OkHttp, Media3 ExoPlayer |
| slot-proxy | Node.js | Podman container | Built into agent-stack |
| Hermes API Server | Python 3.12 | systemd service | Hermes Agent itself |

## Ports

| Service | Port | Protocol | Bound to |
|---------|------|----------|----------|
| Talaria | 8321 | WebSocket/HTTP | 0.0.0.0 (container) |
| Hermes API | 9119 | HTTP | hermes--api (stack-net) |
| slot-proxy | 80/443 | TCP | 0.0.0.0 (container) |
