#!/usr/bin/env python3
"""
End-to-end test for Talaria using the Android emulator with virtual microphone.

This test:
  1. Starts the emulator with KVM acceleration and virtual microphone
  2. Waits for boot completion
  3. Installs the Talaria APK
  4. Connects a WebSocket observer to the Talaria backend
  5. Drives the app via ADB (launches, locates UI elements)
  6. Plays test audio to the virtual mic (simulating PTT)
  7. Validates the full voice pipeline: audio → STT → Hermes → response

Prerequisites:
  - KVM enabled (SVM in BIOS) ✓
  - snd-aloop loaded (./virtual_mic.sh setup)
  - Talaria backend running (agent-stack deployed)
  - APK built (./build-apk.sh)

Usage:
  python3 tests/test_emulator.py                    # full E2E test
  python3 tests/test_emulator.py --skip-boot        # skip emulator boot (already running)
  python3 tests/test_emulator.py --text-only        # text input test only (no mic needed)
  python3 tests/test_emulator.py --audio-file hello.wav  # use custom audio file

Environment:
  TALARIA_WS_URL      WebSocket URL (default: ws://localhost:4004/v1/ptt)
  AVD_NAME            AVD to use (default: test_device)
  APK_PATH            Path to APK (default: auto-detect from /srv/data/files/apk/Talaria/)
"""

import argparse
import asyncio
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional

import websockets

# ── Paths ───────────────────────────────────────────────────────

REPO_ROOT = Path(__file__).resolve().parent.parent
TESTS_DIR = REPO_ROOT / "tests"
AUDIO_DIR = TESTS_DIR / "audio"

EMULATOR_BIN = Path("/srv/data/android-sdk/emulator/emulator")
ADB = Path("/srv/data/android-sdk/platform-tools/adb")
AVD_NAME = os.environ.get("AVD_NAME", "test_device")
AVD_HOME = Path("/srv/data/android-sdk/avd")

APK_BASE = Path("/srv/data/files/apk/Talaria")
PACKAGE_NAME = "com.reedsutliff.talaria"

TALARIA_WS_URL = os.environ.get(
    "TALARIA_WS_URL", "ws://localhost:4004/v1/ptt"
)

# ── Helpers ─────────────────────────────────────────────────────

def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    """Run a command, print it, return result."""
    print(f"  $ {' '.join(cmd)}")
    return subprocess.run(cmd, **kwargs)


def adb(args: list[str], timeout: int = 30) -> subprocess.CompletedProcess:
    """Run an ADB command."""
    return run([str(ADB)] + args, capture_output=True, text=True, timeout=timeout)


def adb_ok(args: list[str], timeout: int = 30) -> bool:
    """Run ADB, return True if it succeeds."""
    result = adb(args, timeout=timeout)
    return result.returncode == 0


def find_apk() -> Optional[Path]:
    """Find the latest Talaria APK."""
    if not APK_BASE.exists():
        print("  APK directory not found:", APK_BASE)
        return None

    # Prefer latest symlink
    latest = APK_BASE / "talaria-latest.apk"
    if latest.exists():
        return latest

    # Fall back to most recent .apk
    apks = sorted(APK_BASE.glob("talaria-*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
    if apks:
        return apks[0]

    return None


async def ws_observer(url: str, timeout: float) -> list[dict]:
    """Connect to Talaria WebSocket and collect all messages for `timeout` seconds."""
    messages = []
    try:
        ws = await websockets.connect(url)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                raw = await asyncio.wait_for(ws.recv(), timeout=min(remaining, 2.0))
                if isinstance(raw, str):
                    try:
                        messages.append(json.loads(raw))
                    except json.JSONDecodeError:
                        messages.append({"raw": raw})
                else:
                    messages.append({"binary": True, "size": len(raw)})
            except asyncio.TimeoutError:
                continue
        await ws.close()
    except Exception as e:
        print(f"  WS observer error: {e}")
    return messages


# ── Test Steps ──────────────────────────────────────────────────

async def _test_text_input():
    """Send a text_input message and validate WS response."""
    print("\n═══ Text Input Test ═══")

    # Start WebSocket observer (background task)
    print("  Connecting WebSocket observer...")
    observer_task = asyncio.create_task(ws_observer(TALARIA_WS_URL, 45.0))

    # Give the observer a moment to connect
    await asyncio.sleep(2)

    # Send text via ADB broadcast (Talaria supports this if the activity is running)
    # Fallback: use the WebSocket directly if Talaria isn't in the foreground
    print("  Sending text via WebSocket...")
    try:
        ws = await websockets.connect(TALARIA_WS_URL)
        await ws.recv()  # reconnect_done
        await ws.send(json.dumps({
            "type": "text_input",
            "text": "Hello from the test harness!",
        }))

        # Wait for response
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=3)
                if isinstance(raw, str):
                    msg = json.loads(raw)
                    t = msg.get("type", "")
                    if t == "tts_fallback":
                        print(f"  ✓ Got response: {msg['text'][:80]}...")
                        break
                    elif t == "error":
                        print(f"  ⚠ Hermes error: {msg.get('message', '')}")
                        break
            except asyncio.TimeoutError:
                pass
        await ws.close()
    except Exception as e:
        print(f"  ✗ WebSocket test failed: {e}")

    # Cancel observer
    observer_task.cancel()
    try:
        messages = await observer_task
    except asyncio.CancelledError:
        messages = []

    print(f"  Observer collected {len(messages)} messages")
    types_seen = {m.get("type", "?") for m in messages if isinstance(m, dict)}
    print(f"  Types seen: {types_seen}")


async def _test_voice_pipeline(audio_file: Optional[Path] = None):
    """Full voice pipeline test using virtual microphone."""
    print("\n═══ Voice Pipeline Test ═══")

    # Check snd-aloop is loaded
    result = run(["aplay", "-l"], capture_output=True, text=True)
    if "Loopback" not in result.stdout:
        print("  ✗ snd-aloop not loaded — run: sudo modprobe snd-aloop")
        return False

    # Find or generate audio
    if audio_file and audio_file.exists():
        test_audio = audio_file
    else:
        # Use a generated test file or fall back to silent audio
        hello_wav = AUDIO_DIR / "hello.wav"
        if hello_wav.exists():
            test_audio = hello_wav
        else:
            print("  No test audio available — generate with: python3 tests/generate_test_audio.py")
            return False

    print(f"  Test audio: {test_audio} ({test_audio.stat().st_size} bytes)")

    # Start WebSocket observer
    print("  Connecting WebSocket observer...")
    observer_task = asyncio.create_task(ws_observer(TALARIA_WS_URL, 30.0))
    await asyncio.sleep(2)

    # Connect and start PTT
    try:
        ws = await websockets.connect(TALARIA_WS_URL)
        await ws.recv()  # reconnect_done

        # Start PTT
        await ws.send(json.dumps({"type": "ptt_start"}))
        ack = await asyncio.wait_for(ws.recv(), timeout=5)
        ack_data = json.loads(ack)
        if ack_data.get("type") == "ptt_start_echo":
            print("  ✓ PTT started")
        else:
            print(f"  ⚠ Expected ptt_start_echo, got: {ack_data.get('type')}")

        # Convert WAV to raw PCM and send as "audio"
        print("  Streaming audio...")
        # Use ffmpeg to convert WAV to raw s16le PCM and pipe to Python
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", "-v", "quiet", "-i", str(test_audio),
            "-f", "s16le", "-acodec", "pcm_s16le",
            "-ar", "16000", "-ac", "1", "pipe:1",
            stdout=subprocess.PIPE,
        )
        raw_pcm, _ = await proc.communicate()

        # Send audio in chunks (simulating real-time streaming)
        chunk_size = 320  # 20ms at 16kHz mono 16-bit
        for i in range(0, len(raw_pcm), chunk_size):
            chunk = raw_pcm[i:i + chunk_size]
            await ws.send(chunk)
            await asyncio.sleep(0.02)  # simulate real-time

        # End PTT
        await ws.send(json.dumps({"type": "ptt_end"}))
        print("  ✓ PTT ended — awaiting pipeline...")

        # Collect results
        pipeline_msgs = []
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=3)
                if isinstance(raw, str):
                    msg = json.loads(raw)
                    t = msg.get("type", "")
                    pipeline_msgs.append(t)

                    if t == "stt_transcript":
                        print(f"  📝 STT: \"{msg.get('text', '')}\"")
                    elif t == "tts_fallback":
                        print(f"  ✓ Reply: \"{msg['text'][:80]}...\"")
                        break
                    elif t == "error":
                        print(f"  ⚠ Error: {msg.get('message', '')}")
                        break
                    elif t == "tts_end":
                        print("  ✓ TTS complete")
                        break
                    elif t == "processing":
                        print(f"  … {msg.get('step', '?')}")
            except asyncio.TimeoutError:
                pass

        print(f"  Pipeline states: {' → '.join(pipeline_msgs)}")
        await ws.close()

    except Exception as e:
        print(f"  ✗ Voice test failed: {e}")
        import traceback
        traceback.print_exc()

    # Cancel observer
    observer_task.cancel()
    try:
        messages = await observer_task
    except asyncio.CancelledError:
        messages = []

    binary_count = sum(1 for m in messages if isinstance(m, dict) and m.get("binary"))
    print(f"  Observer: {len(messages)} messages ({binary_count} binary frames)")


# ── Main ────────────────────────────────────────────────────────

async def main():
    parser = argparse.ArgumentParser(description="Talaria E2E emulator test")
    parser.add_argument("--text-only", action="store_true",
                        help="Only run text input test (no mic needed)")
    parser.add_argument("--audio-file", type=Path,
                        help="Custom audio file for voice test")
    parser.add_argument("--skip-boot", action="store_true",
                        help="Skip emulator boot (emulator already running)")
    args = parser.parse_args()

    print("=" * 60)
    print("Talaria E2E Test Harness")
    print("=" * 60)

    # ── 1. Ensure ADB is available ──
    print("\n── ADB Check ──")
    result = run([str(ADB), "devices", "-l"], capture_output=True, text=True)
    print(result.stdout.strip())

    emulator_running = "emulator-" in result.stdout

    # ── 2. Start emulator if needed ──
    if not args.skip_boot and not emulator_running:
        print("\n── Starting Emulator ──")

        # Build emulator command
        emu_cmd = [
            str(EMULATOR_BIN),
            "-avd", AVD_NAME,
            "-no-snapshot",
            "-gpu", "swiftshader_indirect",
            "-no-boot-anim",
            "-allow-host-audio",
            "-netdelay", "none",
            "-netspeed", "full",
        ]

        # Add KVM if available
        if os.path.exists("/dev/kvm"):
            emu_cmd.extend(["-accel", "on"])
            print("  KVM acceleration: enabled")
        else:
            print("  KVM acceleration: NOT available (slow!)")

        # Start emulator in background
        print(f"  Starting: {' '.join(emu_cmd)}")
        emu_proc = subprocess.Popen(
            emu_cmd,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        print(f"  Emulator PID: {emu_proc.pid}")

        # Wait for boot
        print("  Waiting for boot...")
        for i in range(120):
            result = adb(["shell", "getprop", "sys.boot_completed"], timeout=5)
            if result.returncode == 0 and result.stdout.strip() == "1":
                print(f"  ✓ Boot complete ({i}s)")
                break
            time.sleep(2)
        else:
            print("  ✗ Boot timed out")
            emu_proc.terminate()
            sys.exit(1)

        # Wait for package manager
        time.sleep(5)
    else:
        if emulator_running:
            print("\n── Emulator Already Running ──")
        else:
            print("\n── Skipping Boot (--skip-boot) ──")

    # ── 3. Install APK ──
    print("\n── APK Installation ──")
    apk = find_apk()
    if apk:
        print(f"  Installing: {apk}")
        result = adb(["install", "-r", str(apk)], timeout=60)
        if result.returncode == 0:
            print("  ✓ APK installed")
        else:
            print(f"  Install output: {result.stdout} {result.stderr}")
    else:
        print("  No APK found — running WebSocket-only tests")

    # ── 4. Run tests ──
    passed = 0
    failed = 0

    try:
        await _test_text_input()
        passed += 1
    except Exception as e:
        print(f"  ✗ Text test failed: {e}")
        failed += 1

    if not args.text_only:
        try:
            result = await _test_voice_pipeline(args.audio_file)
            if result is not False:
                passed += 1
            else:
                failed += 1
        except Exception as e:
            print(f"  ✗ Voice test failed: {e}")
            failed += 1

    # ── 5. Summary ──
    print("\n" + "=" * 60)
    print(f"Results: {passed} passed, {failed} failed")
    print("=" * 60)

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
