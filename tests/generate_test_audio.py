#!/usr/bin/env python3
"""
Generate test audio files for Talaria voice pipeline testing.

Creates WAV files with known speech content using Microsoft Edge TTS.
Each file contains a distinct phrase that the STT engine should recognize.
The output files can be:
  1. Played to the virtual microphone (snd-aloop) for emulator tests
  2. Sent directly as WebSocket binary frames in backend tests
  3. Used as ground truth for STT accuracy evaluation

Requirements:
  pip install edge-tts

Usage:
  python3 generate_test_audio.py              # all test files
  python3 generate_test_audio.py --list       # list available phrases
  python3 generate_test_audio.py --phrase "hello"  # single custom phrase
"""

import argparse
import asyncio
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

# ── Config ──────────────────────────────────────────────────────

OUTPUT_DIR = Path(__file__).parent / "audio"
SAMPLE_RATE = 16000  # Hz — matches Talaria's default
CHANNELS = 1  # mono

# Known test phrases — each is a distinct utterance with clear enunciation
TEST_PHRASES: dict[str, str] = {
    "hello": "Hello, this is a test of the voice gateway.",
    "weather": "What is the weather like today?",
    "reminder": "Remind me to check the server logs at five PM.",
    "count": "One, two, three, four, five.",
    "question": "How many services are currently running?",
    "short": "Test.",
}

# ── Helpers ─────────────────────────────────────────────────────

async def generate_tts(text: str, output_wav: Path) -> bool:
    """Generate a WAV file from text using edge-tts."""
    try:
        import edge_tts
    except ImportError:
        print("edge-tts not installed. Run: pip install edge-tts", file=sys.stderr)
        return False

    mp3_path = output_wav.with_suffix(".mp3")
    try:
        # Use a neutral US voice
        communicate = edge_tts.Communicate(text, "en-US-AriaNeural")
        await communicate.save(str(mp3_path))
    except Exception as e:
        print(f"  edge-tts failed: {e}", file=sys.stderr)
        return False

    # Convert MP3 → WAV (16kHz mono) using ffmpeg
    try:
        result = subprocess.run(
            [
                "ffmpeg",
                "-v", "quiet",
                "-i", str(mp3_path),
                "-ar", str(SAMPLE_RATE),
                "-ac", str(CHANNELS),
                "-sample_fmt", "s16",
                str(output_wav),
            ],
            capture_output=True,
            timeout=30,
        )
        mp3_path.unlink(missing_ok=True)

        if result.returncode != 0:
            print(f"  ffmpeg conversion failed: {result.stderr.decode()}", file=sys.stderr)
            return False

        return True
    except Exception as e:
        print(f"  ffmpeg error: {e}", file=sys.stderr)
        mp3_path.unlink(missing_ok=True)
        return False


def wav_duration(wav_path: Path) -> float:
    """Get duration of a WAV file in seconds."""
    try:
        result = subprocess.run(
            ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(wav_path)],
            capture_output=True, text=True,
        )
        return float(result.stdout.strip())
    except Exception:
        return 0.0


# ── Main ────────────────────────────────────────────────────────

async def main():
    parser = argparse.ArgumentParser(description="Generate Talaria test audio files")
    parser.add_argument("--list", action="store_true", help="List available test phrases")
    parser.add_argument("--phrase", type=str, help="Single custom phrase to generate")
    parser.add_argument("--all", action="store_true", default=True,
                        help="Generate all test phrases (default if no args)")
    args = parser.parse_args()

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    if args.list:
        print("Available test phrases:")
        for name, text in TEST_PHRASES.items():
            print(f"  {name:12s} → \"{text}\"")
        return

    if args.phrase:
        phrases = {"custom": args.phrase}
    else:
        phrases = TEST_PHRASES

    # Generate each phrase
    manifest = {}
    for name, text in phrases.items():
        wav_path = OUTPUT_DIR / f"{name}.wav"
        print(f"Generating: {name} → \"{text}\"")

        if wav_path.exists():
            # Check if existing file is valid
            dur = wav_duration(wav_path)
            if dur > 0.5:
                print(f"  ✓ Already exists ({dur:.1f}s) — skipping")
                manifest[name] = {"text": text, "file": str(wav_path), "duration": dur}
                continue

        ok = await generate_tts(text, wav_path)
        if ok:
            dur = wav_duration(wav_path)
            sha = hashlib.sha256(wav_path.read_bytes()).hexdigest()[:12]
            print(f"  ✓ Generated ({dur:.1f}s, sha256={sha})")
            manifest[name] = {
                "text": text,
                "file": str(wav_path),
                "duration": dur,
                "sha256": sha,
            }
        else:
            print(f"  ✗ Failed")
            manifest[name] = {"text": text, "file": None, "error": "generation failed"}

    # Write manifest
    manifest_path = OUTPUT_DIR / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"\nManifest written to {manifest_path}")

    # Summary
    generated = sum(1 for v in manifest.values() if v.get("file"))
    print(f"Generated {generated}/{len(phrases)} audio files in {OUTPUT_DIR}")


if __name__ == "__main__":
    asyncio.run(main())
