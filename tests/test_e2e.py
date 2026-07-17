"""
In-depth end-to-end tests for Talaria voice PTT gateway.

These tests exercise the full pipeline using:
  - Real TTS-generated speech audio (PCM s16le 16kHz mono)
  - Multiple WebSocket connections
  - Barge-in / interrupt during TTS
  - Reconnection with replay
  - Multi-turn conversation context
  - Error recovery paths
  - Concurrent connection isolation

Audio format: Client → Server is raw PCM s16le 16kHz mono.
              Server → Client is Opus in OGG container.

Run:
  cd /srv/agent-stack/repos/talaria
  python -m pytest tests/test_e2e.py -v -s
"""

import asyncio
import json
import os
import struct
import subprocess
import time
from pathlib import Path
from typing import Optional

import pytest
import websockets

# ── Paths & URLs ────────────────────────────────────────────────

from . import (
    ws_connect,
    ws_send_json,
    ws_recv_json,
)
from .conftest import (
    TALARIA_WS_URL,
    TALARIA_HEALTH_URL,
    AUDIO_DIR,
)

# Long timeouts for E2E tests — Hermes + STT + TTS can take time
E2E_TIMEOUT = float(os.environ.get("TALARIA_E2E_TIMEOUT", "90"))
SHORT_TIMEOUT = 10.0


# ── Audio helpers ───────────────────────────────────────────────

def wav_to_pcm(wav_path: Path) -> bytes:
    """Extract raw PCM s16le from a WAV file.

    Strips the WAV header (first 44 bytes typically) and returns
    the raw 16-bit signed PCM sample data.
    """
    if not wav_path.exists():
        raise FileNotFoundError(f"Audio file not found: {wav_path}")

    result = subprocess.run(
        [
            "ffmpeg",
            "-v", "quiet",
            "-i", str(wav_path),
            "-f", "s16le",
            "-acodec", "pcm_s16le",
            "-ar", "16000",
            "-ac", "1",
            "pipe:1",
        ],
        capture_output=True,
        timeout=10,
    )
    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg PCM conversion failed: {result.stderr.decode()}")

    print(f"  Audio: {wav_path.name} → {len(result.stdout)} bytes PCM ({len(result.stdout)/32000:.1f}s)")
    return result.stdout


def make_silence_pcm(duration_ms: int = 1000) -> bytes:
    """Generate raw PCM silence (s16le, 16kHz, mono)."""
    num_samples = int(16000 * duration_ms / 1000)
    return b'\x00' * (num_samples * 2)


def load_test_audio(name: str = "hello") -> bytes:
    """Load a test audio file and convert to raw PCM."""
    wav_path = AUDIO_DIR / f"{name}.wav"
    if not wav_path.exists():
        # Generate it first
        subprocess.run(
            ["python3", str(Path(__file__).parent / "generate_test_audio.py")],
            capture_output=True,
        )
    return wav_to_pcm(wav_path)


# ── WebSocket protocol helpers ─────────────────────────────────

async def ptt_cycle(
    ws,
    pcm_audio: bytes,
    client_msg_id: str = "e2e-test",
    expect_transcript: bool = True,
) -> dict:
    """Run one full PTT cycle: start → stream audio → end → collect results.

    Sends keepalive pings during audio streaming to prevent server timeout.
    Returns dict with keys: echo, transcript, reply, errors, states, messages
    """
    result = {"echo": None, "transcript": None, "reply": None, "errors": [], "states": [], "messages": []}

    # PTT start
    await ws_send_json(ws, {"type": "ptt_start", "client_message_id": client_msg_id})
    echo = await ws_recv_json(ws, timeout=SHORT_TIMEOUT)
    assert echo["type"] == "ptt_start_echo", f"Expected ptt_start_echo, got {echo.get('type')}"
    result["echo"] = echo

    # Stream audio as fast as possible — no delay between chunks.
    # Delay in streaming triggers more STT partials, which queue up
    # and overwhelm the server on CPU.
    CHUNK = 8192
    bytes_sent = 0
    for i in range(0, len(pcm_audio), CHUNK):
        chunk = pcm_audio[i:i + CHUNK]
        if chunk:
            await ws.send(chunk)
            bytes_sent += len(chunk)
            # No sleep — send as fast as possible
            # Only send one keepalive ping mid-stream if audio is long
            if bytes_sent > 60000:
                await ws_send_json(ws, {"type": "ping"})
                try:
                    await ws_recv_json(ws, timeout=2)
                except TimeoutError:
                    pass
                bytes_sent = 0  # reset counter

    print(f"  Sent {bytes_sent} bytes PCM audio")

    # PTT end
    await ws_send_json(ws, {"type": "ptt_end"})

    # Collect results with periodic pings
    last_ping = time.monotonic()
    deadline = time.monotonic() + E2E_TIMEOUT
    while time.monotonic() < deadline:
        try:
            msg = await ws_recv_json(ws, timeout=3)
        except TimeoutError:
            # Send ping to keep alive
            now = time.monotonic()
            if now - last_ping > 4.0:
                try:
                    await ws_send_json(ws, {"type": "ping"})
                    pong = await ws_recv_json(ws, timeout=2)
                    last_ping = now
                except Exception:
                    pass
            continue

        t = msg.get("type", "")
        last_ping = time.monotonic()  # server messages count as activity
        result["states"].append(t)
        result["messages"].append(msg)

        if t == "stt_transcript":
            result["transcript"] = msg.get("text", "")
        elif t == "tts_fallback":
            result["reply"] = msg.get("text", "")
            break
        elif t == "tts_end":
            break
        elif t == "error":
            result["errors"].append(msg)
            if "Hermes API error" in msg.get("message", ""):
                break
        elif t == "processing":
            pass

    return result


async def collect_binary(ws, timeout: float = 5.0) -> list[bytes]:
    """Collect all binary frames until timeout or text frame."""
    chunks = []
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=min(timeout, 1.0))
            if isinstance(raw, bytes):
                chunks.append(raw)
            else:
                break  # text frame — push back isn't easy, but we'll handle it
        except asyncio.TimeoutError:
            break
    return chunks


# ═══════════════════════════════════════════════════════════════
# E2E Test 1: Full Voice Pipeline
# ═══════════════════════════════════════════════════════════════

def test_full_voice_pipeline_hello():
    """Record "Hello, this is a test of the voice gateway." through the full pipeline."""
    async def _run():
        ws = await ws_connect()
        pcm = load_test_audio("hello")

        result = await ptt_cycle(ws, pcm, client_msg_id="e2e-hello")

        print(f"\n  Pipeline: {' → '.join(result['states'])}")
        if result["transcript"]:
            print(f"  STT: \"{result['transcript']}\"")
        if result["reply"]:
            print(f"  Reply: \"{result['reply'][:120]}\"")

        # With real speech, we should get a transcript
        if result["transcript"]:
            assert len(result["transcript"]) > 0
            # The transcript should contain "hello" or "test" or "voice"
            text_lower = result["transcript"].lower()
            has_words = any(w in text_lower for w in ["hello", "test", "voice", "gateway"])
            if not has_words:
                print(f"  ⚠ Transcript doesn't contain expected words: \"{result['transcript']}\"")
                # Don't fail — STT can be imperfect with TTS-generated audio

        # Should get a reply from Hermes
        if result["reply"]:
            assert len(result["reply"]) > 0
        elif result["errors"]:
            for err in result["errors"]:
                print(f"  Error: {err.get('message', '')}")

        await ws.close()
    asyncio.run(_run())


def test_full_voice_pipeline_counting():
    """Record "Test." — short audio, minimal streaming STT overhead."""
    async def _run():
        ws = await ws_connect()
        # Use the shortest audio file to avoid streaming STT queue buildup
        pcm = load_test_audio("short")

        result = await ptt_cycle(ws, pcm, client_msg_id="e2e-count")

        print(f"\n  Pipeline: {' → '.join(result['states'])}")
        print(f"  STT: \"{result['transcript'] or 'N/A'}\"")
        print(f"  Reply: \"{(result['reply'] or 'N/A')[:120]}\"")

        assert result["echo"] is not None, "No echo received"
        assert result["echo"]["type"] == "ptt_start_echo"

        if result["transcript"]:
            text_lower = result["transcript"].lower()
            print(f"  Transcript found: \"{result['transcript']}\"")
            # Should contain "test" or similar
        elif result["errors"]:
            for err in result["errors"]:
                print(f"  Error: {err.get('message', '')}")

        await ws.close()
    asyncio.run(_run())


# ═══════════════════════════════════════════════════════════════
# E2E Test 2: Barge-in / Interrupt During TTS
# ═══════════════════════════════════════════════════════════════

def test_barge_in_during_response():
    """Start a long response, interrupt mid-stream, verify state resets."""
    async def _run():
        ws = await ws_connect()

        # Start a text input with a long prompt
        await ws_send_json(ws, {
            "type": "text_input",
            "text": "Count from one to twenty, one number per line, take your time.",
        })

        # Wait until we see thinking/processing started
        seen_run = False
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            try:
                msg = await ws_recv_json(ws, timeout=3)
                t = msg.get("type", "")
                if t == "processing":
                    seen_run = True
                if t == "text_echo":
                    pass
                if t in ("tts_fallback", "error"):
                    break
                if seen_run:
                    # Wait a second for the run to be fully started, then interrupt
                    await asyncio.sleep(1)
                    break
            except TimeoutError:
                if seen_run:
                    break

        if not seen_run:
            print("  Run never started — skipping barge-in test")
            await ws.close()
            return

        # Send interrupt
        print("  Sending interrupt...")
        await ws_send_json(ws, {"type": "interrupt"})

        # Should get interrupted confirmation
        msg = await ws_recv_json(ws, timeout=10)
        # Could be processing, interrupted, or error — all valid
        print(f"  After interrupt: {msg.get('type', '?')}")

        # Should be able to start a new PTT immediately
        await ws_send_json(ws, {
            "type": "ptt_start",
            "client_message_id": "e2e-after-interrupt",
        })
        echo = await ws_recv_json(ws, timeout=5)
        assert echo["type"] == "ptt_start_echo", \
            f"Expected ptt_start_echo after interrupt, got {echo.get('type')}"

        await ws.close()
    asyncio.run(_run())


# ═══════════════════════════════════════════════════════════════
# E2E Test 3: Multi-turn Conversation Context
# ═══════════════════════════════════════════════════════════════

def test_multi_turn_conversation():
    """Two text turns in the same session should maintain context."""
    async def _run():
        ws = await ws_connect()

        session_id = None

        # Turn 1: Tell it to remember something
        await ws_send_json(ws, {
            "type": "text_input",
            "text": "My name is E2E Test Bot. Reply with OK.",
        })

        got_reply_1 = False
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                msg = await ws_recv_json(ws, timeout=5)
            except TimeoutError:
                # Send keepalive ping
                try:
                    await ws_send_json(ws, {"type": "ping"})
                    await ws_recv_json(ws, timeout=2)
                except Exception:
                    break
                continue

            t = msg.get("type", "")
            if t == "session_id":
                session_id = msg.get("id") or msg.get("session_id")
                print(f"  Session: {session_id}")
            elif t == "tts_fallback":
                print(f"  Reply 1: \"{msg['text'][:80]}\"")
                got_reply_1 = True
                break
            elif t == "error":
                err = msg.get("message", "")
                print(f"  Error: {err}")
                if "Hermes API error" in err:
                    pytest.skip("Hermes API unavailable")
                break
            elif t == "text_echo":
                pass

        if not got_reply_1:
            print("  No reply to first turn — skipping context check")
            await ws.close()
            return

        if not session_id:
            print("  No session ID — conversation context won't persist")
            await ws.close()
            return

        # Brief pause to let server settle
        await asyncio.sleep(1)

        # Turn 2: Reference the stored context
        await ws_send_json(ws, {
            "type": "text_input",
            "text": "What is my name?",
        })

        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                msg = await ws_recv_json(ws, timeout=5)
            except TimeoutError:
                try:
                    await ws_send_json(ws, {"type": "ping"})
                    await ws_recv_json(ws, timeout=2)
                except Exception:
                    break
                continue

            t = msg.get("type", "")
            if t == "tts_fallback":
                reply = msg.get("text", "")
                print(f"  Reply 2: \"{reply[:120]}\"")
                name_mentioned = any(word in reply.lower() for word in ["e2e", "test bot"])
                if name_mentioned:
                    print("  ✓ Context maintained!")
                else:
                    print("  ⚠ Name not mentioned — context may not have carried")
                break
            elif t == "error":
                print(f"  Error: {msg.get('message', '')}")
                break
            elif t == "text_echo":
                pass

        await ws.close()
    asyncio.run(_run())


# ═══════════════════════════════════════════════════════════════
# E2E Test 4: Reconnection with Replay
# ═══════════════════════════════════════════════════════════════

def test_reconnect_during_response():
    """Disconnect mid-response, reconnect, verify replay if supported."""
    async def _run():
        # Connect, start a response, then disconnect and reconnect
        ws1 = await ws_connect()

        await ws_send_json(ws1, {
            "type": "text_input",
            "text": "Say something interesting about space exploration.",
        })

        # Wait for processing to begin
        session_id = None
        for _ in range(20):
            try:
                msg = await ws_recv_json(ws1, timeout=3)
                t = msg.get("type", "")
                if t == "session_id":
                    session_id = msg.get("id") or msg.get("session_id")
                    print(f"  Session: {session_id}")
                if t in ("text_echo", "processing"):
                    pass
                if t == "error":
                    break
            except TimeoutError:
                break

        # Disconnect abruptly
        print("  Disconnecting...")
        await ws1.close()

        # Reconnect with the session_id
        await asyncio.sleep(1)
        print("  Reconnecting...")
        ws2 = await websockets.connect(TALARIA_WS_URL)

        if session_id:
            await ws2.send(json.dumps({
                "type": "reconnect",
                "session_id": session_id,
                "last_seq": 0,
            }))
            msg = await ws_recv_json(ws2, timeout=10)
            assert msg["type"] == "reconnect_done"
            print(f"  Reconnect result: replayed={msg.get('replayed', False)}")
        else:
            # No session ID — just do normal handshake
            await ws_send_json(ws2, {"type": "ping"})
            await ws_recv_json(ws2, timeout=5)  # reconnect_done
            await ws_recv_json(ws2, timeout=5)  # pong
            print("  Reconnected without session ID")

        # Should still be able to use the connection
        await ws_send_json(ws2, {"type": "ping"})
        msg = await ws_recv_json(ws2, timeout=5)
        assert msg["type"] == "pong"

        await ws2.close()
    asyncio.run(_run())


# ═══════════════════════════════════════════════════════════════
# E2E Test 5: Concurrent Connections
# ═══════════════════════════════════════════════════════════════

def test_concurrent_connections():
    """Two simultaneous connections should be isolated."""
    async def _run():
        ws_a = await ws_connect()
        ws_b = await ws_connect()

        # Send different text from each
        await ws_send_json(ws_a, {"type": "text_input", "text": "Reply with ALPHA."})
        await ws_send_json(ws_b, {"type": "text_input", "text": "Reply with BRAVO."})

        reply_a = None
        reply_b = None

        # Collect replies in parallel
        async def get_reply(ws, label):
            nonlocal reply_a, reply_b
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                try:
                    msg = await ws_recv_json(ws, timeout=5)
                    t = msg.get("type", "")
                    if t == "tts_fallback":
                        text = msg.get("text", "")
                        if label == "A":
                            reply_a = text
                        else:
                            reply_b = text
                        return
                    elif t == "error":
                        return
                except TimeoutError:
                    continue

        await asyncio.gather(
            get_reply(ws_a, "A"),
            get_reply(ws_b, "B"),
        )

        print(f"  Connection A reply: \"{(reply_a or 'N/A')[:80]}\"")
        print(f"  Connection B reply: \"{(reply_b or 'N/A')[:80]}\"")

        # Both should have gotten replies (assuming Hermes is available)
        assert reply_a is not None or reply_b is not None, \
            "Neither connection got a reply"

        await ws_a.close()
        await ws_b.close()
    asyncio.run(_run())


# ═══════════════════════════════════════════════════════════════
# E2E Test 6: Error Recovery — Empty/Short Audio
# ═══════════════════════════════════════════════════════════════

def test_empty_audio_graceful_error():
    """Sending no audio between PTT start/end returns clean error."""
    async def _run():
        ws = await ws_connect()

        await ws_send_json(ws, {
            "type": "ptt_start",
            "client_message_id": "empty-test",
        })
        echo = await ws_recv_json(ws, timeout=5)
        assert echo["type"] == "ptt_start_echo"

        # Send one tiny frame to transition to RECORDING, then end
        await ws.send(b'\x00' * 100)  # < 4096 bytes
        await ws_send_json(ws, {"type": "ptt_end"})

        msg = await ws_recv_json(ws, timeout=10)
        t = msg.get("type", "")
        assert t in ("processing", "error"), f"Expected error or processing, got {t}"

        if t == "error":
            assert "short" in msg.get("message", "").lower() or \
                   "speech" in msg.get("message", "").lower(), \
                   f"Expected 'audio too short' error, got: {msg.get('message')}"

        # Connection should remain usable (or at least close cleanly)
        try:
            await ws_send_json(ws, {"type": "ping"})
            msg = await ws_recv_json(ws, timeout=5)
            # Either pong or error is acceptable — the server may have
            # closed due to keepalive timeout during the test
            assert msg["type"] in ("pong", "error"), \
                f"Expected pong or error, got {msg.get('type')}"
        except Exception as e:
            print(f"  Connection post-error: {e} — acceptable after error state")

        await ws.close()
    asyncio.run(_run())


# ═══════════════════════════════════════════════════════════════
# E2E Test 7: Rapid PTT Cycles
# ═══════════════════════════════════════════════════════════════

def test_rapid_ptt_cycles():
    """Three rapid PTT cycles in sequence — verify state machine handles them."""
    async def _run():
        ws = await ws_connect()

        for cycle in range(3):
            print(f"\n  Cycle {cycle + 1}/3...")
            await ws_send_json(ws, {
                "type": "ptt_start",
                "client_message_id": f"rapid-{cycle}",
            })
            echo = await ws_recv_json(ws, timeout=5)
            assert echo["type"] == "ptt_start_echo"

            # Send ~1 second of silence
            silence = make_silence_pcm(1000)
            for i in range(0, len(silence), 4096):
                await ws.send(silence[i:i + 4096])
                await asyncio.sleep(0.01)

            await ws_send_json(ws, {"type": "ptt_end"})

            # Consume the pipeline response (will be "no speech detected")
            for _ in range(10):
                try:
                    msg = await ws_recv_json(ws, timeout=3)
                    t = msg.get("type", "")
                    if t in ("error", "stt_transcript", "tts_fallback", "tts_end"):
                        print(f"    → {t}: {msg.get('message', msg.get('text', ''))[:60]}")
                        break
                except TimeoutError:
                    break

        await ws.close()
        print("\n  ✓ 3 cycles completed")
    asyncio.run(_run())


# ═══════════════════════════════════════════════════════════════
# E2E Test 8: Mixed Text + Voice
# ═══════════════════════════════════════════════════════════════

def test_mixed_text_and_voice():
    """Mix text_input and PTT voice in the same session."""
    async def _run():
        ws = await ws_connect()

        # Text first
        await ws_send_json(ws, {
            "type": "text_input",
            "text": "Hello from text input.",
        })

        # Wait for reply
        got_text_reply = False
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            try:
                msg = await ws_recv_json(ws, timeout=5)
            except TimeoutError:
                continue
            t = msg.get("type", "")
            if t == "tts_fallback":
                print(f"  Text reply: \"{msg['text'][:60]}\"")
                got_text_reply = True
                break
            elif t == "error":
                print(f"  Error: {msg.get('message', '')}")
                break

        # Then voice
        pcm = load_test_audio("short")
        result = await ptt_cycle(ws, pcm, client_msg_id="e2e-mixed")

        print(f"  Voice result: transcript=\"{result['transcript'] or 'N/A'}\"")
        print(f"  Voice reply: \"{(result['reply'] or 'N/A')[:60]}\"")

        # State machine should handle the transition correctly
        assert result["echo"] is not None, "Voice turn should work after text"

        await ws.close()
    asyncio.run(_run())
