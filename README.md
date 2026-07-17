# Talaria — Real-time Voice Gateway for Hermes Agent

## Overview

Talaria is a real-time voice PTT (Push-to-Talk) gateway for Hermes Agent. It handles WebSocket connections from Android clients for voice interactions with AI agents.

## Features

- **WebSocket PTT Protocol**: Binary Opus in, binary Opus + JSON events out
- **Speech-to-Text**: Faster-whisper based STT with configurable models
- **Text-to-Speech**: Edge TTS for streaming audio responses
- **Connection Replay**: Resume playback after disconnections
- **State Management**: PTT state machine with validated transitions
- **Thread Safety**: Safe concurrent audio processing

## Architecture

```
repos/talaria/
├── server.py                 # Main FastAPI server
├── hermes_api_server.py      # Hermes API proxy (unused)
├── src/                      # Modular source code
│   ├── core/                 # Core components
│   │   ├── types.py         # Type definitions and enums
│   │   ├── state_machine.py # PTT state machine
│   │   ├── connection.py    # WebSocket connection manager
│   │   ├── audio_buffer.py  # Audio buffer management
│   │   ├── replay_store.py  # Connection replay storage
│   │   └── audio_validator.py # Input validation
│   ├── audio/               # Audio processing
│   │   ├── stt.py          # Speech-to-text
│   │   └── tts.py          # Text-to-speech
│   ├── protocol/            # Protocol handling
│   │   └── websocket_server.py # WebSocket handler
│   └── services/            # External services
│       └── hermes_client.py # Hermes API client
├── requirements.txt         # Python dependencies
├── pyproject.toml          # Project configuration
├── mypy.ini                # Type checking config
├── pyrightconfig.json      # Pyright config
├── Dockerfile              # Container build
├── agent-stack.json        # Deployment config
└── README.md               # This file
```

## Quick Start

### Development

```bash
# Install dependencies
pip install -r requirements.txt

# Run the server
python server.py
```

### Docker

```bash
# Build the container
docker build -t talaria .

# Run the container
docker run -p 8321:8321 talaria
```

## Configuration

Environment variables:

- `HOST`: Server host (default: 0.0.0.0)
- `PORT`: Server port (default: 8321)
- `HERMES_API_URL`: Hermes API URL
- `HERMES_API_KEY`: Hermes API key
- `STT_MODEL`: STT model name (default: base)
- `TTS_VOICE`: TTS voice (default: en-US-ChristopherNeural)
- `MAX_AUDIO_CHUNK_SIZE`: Max audio chunk size in bytes (default: 5MB)
- `MAX_AUDIO_BUFFER_SIZE`: Max audio buffer size in bytes (default: 50MB)

## API

### Health Check

```
GET /health
```

### WebSocket PTT

```
WS /v1/ptt
```

## License

MIT
