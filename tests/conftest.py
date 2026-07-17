"""
Shared test fixtures and helpers for Talaria tests.

Talaria WebSocket protocol (see server.py /v1/ptt):
  - Client MUST send first message (ping, reconnect, etc.)
  - Server responds with reconnect_done first
  - Then processes the client message normally

Protocol sequence for a voice run:
  1. Client connects → sends ping
  2. Server sends reconnect_done → pong
  3. Client sends {"type":"ptt_start"}
  4. Server sends {"type":"ptt_start_echo"}
  5. Client streams binary Opus audio (recording)
  6. Client sends {"type":"ptt_end"}
  7. Server sends {"type":"processing","step":"stt"}
  8. Server sends {"type":"stt_transcript","text":"...","final":true}
  9. Server → Hermes → response
  10. Server sends {"type":"tts_fallback","text":"..."} or TTS audio
"""

import os
import subprocess
import time
from pathlib import Path
from typing import Optional

import pytest

from . import (
    ws_connect,
    ws_send_json,
    ws_recv_json,
)

# ── Paths ───────────────────────────────────────────────────────

TESTS_DIR = Path(__file__).parent
REPO_ROOT = TESTS_DIR.parent
AUDIO_DIR = TESTS_DIR / "audio"

# ── Target URLs ─────────────────────────────────────────────────

TALARIA_WS_URL = os.environ.get(
    "TALARIA_WS_URL",
    "ws://localhost:4004/v1/ptt",
)
TALARIA_HEALTH_URL = os.environ.get(
    "TALARIA_HEALTH_URL",
    "http://localhost:4004/health",
)


# ── Audio helpers ───────────────────────────────────────────────

def make_silent_opus(duration_ms: int = 200, sample_rate: int = 16000) -> bytes:
    """Generate a minimal 'silent' Opus frame for testing.

    Uses ffmpeg with libopus encoder. Falls back to raw PCM if unavailable.
    """
    try:
        result = subprocess.run(
            [
                "ffmpeg",
                "-f", "lavfi", "-i", f"anoisesrc=d={duration_ms / 1000}:c=pink",
                "-ar", str(sample_rate),
                "-ac", "1",
                "-c:a", "libopus",
                "-frame_duration", str(min(duration_ms // 20, 120)),
                "-f", "opus",
                "-v", "quiet",
                "pipe:1",
            ],
            capture_output=True,
            timeout=5,
        )
        if result.returncode == 0 and len(result.stdout) > 0:
            return result.stdout
    except Exception:
        pass

    # Raw PCM fallback
    num_samples = int(sample_rate * duration_ms / 1000)
    return b'\x00' * (num_samples * 2)
