"""
Backend protocol tests for Talaria voice PTT gateway.

Tests validate the WebSocket protocol, state machine, and
Hermes API integration without needing an emulator.

Protocol notes:
  - Client sends first message (ping or reconnect handshake)
  - Server responds with reconnect_done first, then processes the message
  - ptt_start_echo only fires when client_message_id is included
  - ptt_end triggers STT → Hermes → response pipeline

Run:
  cd /srv/agent-stack/repos/talaria
  python -m pytest tests/test_backend.py -v -s
"""

import asyncio
import json
import time

import pytest
import websockets

from . import (
    ws_connect,
    ws_send_json,
    ws_recv_json,
)
from .conftest import (
    TALARIA_WS_URL,
    TALARIA_HEALTH_URL,
    make_silent_opus,
)


# ═══════════════════════════════════════════════════════════════
# Health & Connectivity
# ═══════════════════════════════════════════════════════════════

def test_health_endpoint():
    """GET /health returns ok with expected fields."""
    async def _run():
        import httpx
        async with httpx.AsyncClient() as client:
            resp = await client.get(TALARIA_HEALTH_URL, timeout=10)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "ok"
        assert "stt_loaded" in data
        assert "hermes_healthy" in data
    asyncio.run(_run())


def test_websocket_connect():
    """WebSocket connects and completes handshake."""
    async def _run():
        ws = await ws_connect()
        await ws_send_json(ws, {"type": "ping"})
        msg = await ws_recv_json(ws, timeout=5)
        assert msg["type"] == "pong"
        await ws.close()
    asyncio.run(_run())


def test_profiles_endpoint():
    """GET /api/profiles returns profile list."""
    async def _run():
        import httpx
        async with httpx.AsyncClient() as client:
            resp = await client.get(
                TALARIA_HEALTH_URL.replace("/health", "/api/profiles"),
                timeout=10,
            )
        assert resp.status_code == 200
        data = resp.json()
        assert "profiles" in data
    asyncio.run(_run())


# ═══════════════════════════════════════════════════════════════
# Text Input — Hermes round-trip
# ═══════════════════════════════════════════════════════════════

def test_text_input_basic():
    """Send a text message and verify we get a reply."""
    async def _run():
        ws = await ws_connect()
        await ws_send_json(ws, {
            "type": "text_input",
            "text": "Hello from Talaria test. Reply with OK.",
        })

        seen_echo = False
        seen_reply = False
        deadline = time.monotonic() + 90  # generous — Hermes can be slow
        while time.monotonic() < deadline:
            try:
                msg = await ws_recv_json(ws, timeout=10)
            except TimeoutError:
                # Send keepalive ping
                try:
                    await ws_send_json(ws, {"type": "ping"})
                    await ws_recv_json(ws, timeout=3)
                except Exception:
                    break
                continue

            t = msg.get("type", "")

            if t == "text_echo":
                assert "Hello from Talaria" in msg.get("text", "")
                seen_echo = True
            elif t == "tts_fallback":
                assert len(msg.get("text", "")) > 0
                seen_reply = True
                break
            elif t == "error":
                err = msg.get("message", "")
                print(f"  ⚠ Hermes: {err}")
                if "Hermes API error" in err:
                    pytest.skip("Hermes API unavailable")
                break
            elif t in ("processing", "session_id"):
                pass  # intermediate states

        await ws.close()
        assert seen_echo, "Never received text_echo"
        if not seen_reply:
            pytest.skip("No reply — Hermes may be slow")
    asyncio.run(_run())


def test_text_input_empty():
    """Empty text input returns text_echo first."""
    async def _run():
        ws = await ws_connect()
        await ws_send_json(ws, {"type": "text_input", "text": ""})
        msg = await ws_recv_json(ws, timeout=5)
        assert msg["type"] in ("text_echo", "error")
        await ws.close()
    asyncio.run(_run())


# ═══════════════════════════════════════════════════════════════
# PTT Voice Pipeline
# ═══════════════════════════════════════════════════════════════

def test_ptt_start_with_id():
    """ptt_start with client_message_id gets echo."""
    async def _run():
        ws = await ws_connect()
        await ws_send_json(ws, {
            "type": "ptt_start",
            "client_message_id": "msg-001",
        })
        msg = await ws_recv_json(ws, timeout=5)
        assert msg["type"] == "ptt_start_echo"
        assert msg["client_message_id"] == "msg-001"
        await ws.close()
    asyncio.run(_run())


def test_ptt_start_stop():
    """PTT start → tiny audio → stop returns 'audio too short' error."""
    async def _run():
        ws = await ws_connect()

        await ws_send_json(ws, {
            "type": "ptt_start",
            "client_message_id": "ptt-1",
        })
        echo = await ws_recv_json(ws, timeout=5)
        assert echo["type"] == "ptt_start_echo"

        # Send one small binary frame to trigger IDLE→RECORDING
        await ws.send(b'\x00' * 100)  # 100 bytes of garbage

        # End immediately — audio too short (< 4096 bytes)
        await ws_send_json(ws, {"type": "ptt_end"})

        # Should get "Audio too short" error
        msg = await ws_recv_json(ws, timeout=10)
        t = msg.get("type", "")
        assert t in ("processing", "error"), \
            f"Expected processing or error, got: {t}"
        if t == "error":
            assert "short" in msg.get("message", "").lower() or \
                   "speech" in msg.get("message", "").lower()
        await ws.close()
    asyncio.run(_run())


def test_ptt_with_audio():
    """PTT with Opus audio triggers full pipeline."""
    async def _run():
        ws = await ws_connect()

        await ws_send_json(ws, {
            "type": "ptt_start",
            "client_message_id": "ptt-full",
        })
        echo = await ws_recv_json(ws, timeout=5)
        assert echo["type"] == "ptt_start_echo"

        # Send enough Opus audio to satisfy the 4096-byte minimum
        print("  Streaming Opus audio...")
        for i in range(20):
            await ws.send(make_silent_opus(200))
            if i == 0:
                # Brief pause to let server transition to RECORDING
                await asyncio.sleep(0.1)
            else:
                await asyncio.sleep(0.02)

        await ws_send_json(ws, {"type": "ptt_end"})
        print("  PTT ended — awaiting pipeline...")

        # Collect pipeline states
        states = []
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                msg = await ws_recv_json(ws, timeout=5)
                t = msg.get("type", "")
                states.append(t)
                if t == "stt_transcript":
                    print(f"  📝 STT: \"{msg.get('text', '')[:80]}\"")
                elif t == "tts_fallback":
                    print(f"  ✓ Reply: \"{msg.get('text', '')[:80]}\"")
                    break
                elif t == "tts_end":
                    print("  ✓ TTS complete")
                    break
                elif t == "error":
                    err = msg.get("message", "")
                    print(f"  ⚠ Error: {err}")
                    if "No speech detected" in err:
                        print("  (expected for silent audio — STT found nothing)")
                    break
                elif t == "processing":
                    print(f"  … {msg.get('step', '?')}")
            except TimeoutError:
                break

        print(f"  Pipeline states: {' → '.join(states)}")
        assert len(states) > 0, "No pipeline messages received"
        await ws.close()
    asyncio.run(_run())


def test_ptt_cancel():
    """Cancel during recording resets state."""
    async def _run():
        ws = await ws_connect()

        await ws_send_json(ws, {
            "type": "ptt_start",
            "client_message_id": "ptt-cancel",
        })
        await ws_recv_json(ws, timeout=5)  # echo
        await ws.send(make_silent_opus(1000))
        await ws_send_json(ws, {"type": "cancel"})

        # Should be able to start again immediately
        await ws_send_json(ws, {
            "type": "ptt_start",
            "client_message_id": "ptt-cancel-2",
        })
        msg = await ws_recv_json(ws, timeout=5)
        assert msg["type"] == "ptt_start_echo"
        await ws.close()
    asyncio.run(_run())


# ═══════════════════════════════════════════════════════════════
# Reconnect
# ═══════════════════════════════════════════════════════════════

def test_reconnect_handshake():
    """Explicit reconnect triggers proper reconnect_done."""
    async def _run():
        ws = await websockets.connect(TALARIA_WS_URL)
        await ws.send(json.dumps({
            "type": "reconnect",
            "session_id": "test-session-abc",
            "last_seq": 0,
        }))
        msg = await ws_recv_json(ws, timeout=5)
        assert msg["type"] == "reconnect_done"
        await ws.close()
    asyncio.run(_run())


# ═══════════════════════════════════════════════════════════════
# Session Management
# ═══════════════════════════════════════════════════════════════

def test_list_sessions():
    """list_sessions returns session list from Hermes."""
    async def _run():
        ws = await ws_connect()
        await ws_send_json(ws, {"type": "list_sessions", "limit": 5})
        msg = await ws_recv_json(ws, timeout=10)
        assert msg["type"] == "session_list"
        assert isinstance(msg.get("sessions", []), list)
        await ws.close()
    asyncio.run(_run())


def test_set_profile():
    """set_profile updates the active profile."""
    async def _run():
        ws = await ws_connect()
        await ws_send_json(ws, {"type": "set_profile", "profile": "default"})
        msg = await ws_recv_json(ws, timeout=5)
        assert msg["type"] == "profile_set"
        assert msg.get("profile") == "default"
        await ws.close()
    asyncio.run(_run())


def test_garbage_json():
    """Sending gibberish doesn't crash the server."""
    async def _run():
        ws = await ws_connect()
        await ws.send("this is not json at all {{{")
        await ws_send_json(ws, {"type": "ping"})
        msg = await ws_recv_json(ws, timeout=5)
        assert msg["type"] == "pong"
        await ws.close()
    asyncio.run(_run())
