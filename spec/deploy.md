# Deployment Specification

## Agent-Stack Integration

Talaria is a container service in the agent-stack monorepo at `repos/talaria/`.

### agent-stack.json

```json
{
  "name": "talaria",
  "version": 1,
  "container": {
    "port": 8321,
    "auto_port": true,
    "env": {
      "HOST": "0.0.0.0",
      "PORT": "8321",
      "HERMES_API_URL": "http://hermes--api:9119",
      "STT_MODEL": "base",
      "LOG_LEVEL": "info"
    }
  },
  "expose": {
    "enabled": true,
    "hostname": "talaria.ffilt.us",
    "tunnel": "ca308a1b-557a-4a18-9350-6eec27465bdf"
  },
  "strategy": { "type": "blue-green", "queue": true },
  "build": {
    "podman_args": "--memory 2g --network stack-net"
  }
}
```

### Build

The controller builds from `repos/talaria/Dockerfile` using podman with:
- `--memory 2g` — faster-whisper needs ~1GB RAM for the `base` model
- `--network stack-net` — to reach `hermes--api:9119` on the internal network

### Health Check

Talaria exposes `GET /health` on port 8321. The controller checks this at 30s intervals with a 15s timeout and a 120s initial grace period (STT model download on first boot).

### Slot Swap

Standard blue-green. Build goes to idle slot, health check passes, traffic swaps. No special considerations — Talaria is stateless across deployments (STT model is cached in a named volume `talaria-cache`).

## Cloudflare Access

Talaria sits behind the existing `ca308a1b-557a-4a18-9350-6eec27465bdf` tunnel (same as all other `*.ffilt.us` services).

### Authentication

All WebSocket and HTTP requests to `talaria.ffilt.us` require Cloudflare Access service token authentication:

| Header | Value |
|--------|-------|
| `CF-Access-Client-Id` | `<access-client-id>` |
| `CF-Access-Client-Secret` | `<access-client-secret>` |

### Hardcoded Tokens

Since the only consumer is the operator (Reed), the CF-Access service token credentials are **hardcoded** in the Android app source at build time via `local.properties`.

## Network

| Direction | From | To | Protocol | Port |
|-----------|------|----|----------|------|
| Inbound | Cloudflare | Talaria | WebSocket/WSS | 8321 |
| Outbound | Talaria | Hermes API | HTTP | 9119 (stack-net) |

Talaria makes **no outbound internet calls**. All dependencies are local:
- Hermes API Server at `http://hermes--api:9119` (stack-net DNS)
- STT model cached in `talaria-cache` volume (downloaded once on first boot)

## Runtime Files

| Path | Purpose |
|------|---------|
| `repos/talaria/agent-stack.json` | Agent-stack service definition |
| `repos/talaria/Dockerfile` | Container build |
| `repos/talaria/server.py` | Talaria server source |
| `repos/talaria/requirements.txt` | Python dependencies |
| `repos/talaria/android/` | Android app project |
| `repos/talaria/spec/` | Specification documents |

## Logging

Talaria logs to stdout (captured by podman/controller). Structured JSON format at INFO level by default. Key log events:

- `Talaria starting up...` / `Talaria ready on 0.0.0.0:8321` — startup
- `STT model loaded: base` — model loaded
- `WebSocket connected` / `WebSocket disconnected` — client lifecycle
- `Processing PTT: ...` — audio processing begin
- `Sending to Hermes: ...` — API call
- `TTS start: ...` — synthesis begin
- `Interrupt received — stopping run ...` — barge-in
