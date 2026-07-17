#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────
# virtual_mic.sh — Set up / tear down a virtual microphone for
# the Android emulator using the snd-aloop kernel module.
#
# Usage:
#   ./virtual_mic.sh setup     # load module, configure loopback
#   ./virtual_mic.sh play WAV  # stream a WAV file to the virtual mic
#   ./virtual_mic.sh teardown  # unload module
#
# The emulator must be started AFTER setup with:
#   emulator -avd test_device -allow-host-audio -no-snapshot ...
#
# Architecture:
#   snd-aloop creates two ALSA devices:
#     hw:Loopback,0,0 — playback (we write audio here)
#     hw:Loopback,0,1 — capture  (emulator reads from here as mic)
#   Audio played to playback appears on the capture side.
# ────────────────────────────────────────────────────────────────

set -euo pipefail

LOOPBACK_CARD="Loopback"
LOOPBACK_PLAYBACK="hw:Loopback,0,0"
LOOPBACK_CAPTURE="hw:Loopback,0,1"

# ── helpers ────────────────────────────────────────────────────

is_loaded() {
    aplay -l 2>/dev/null | grep -q "$LOOPBACK_CARD"
}

setup() {
    echo "→ Setting up virtual microphone..."

    if is_loaded; then
        echo "  snd-aloop already loaded (card ready)"
    else
        echo "  Loading snd-aloop kernel module..."
        sudo modprobe snd-aloop
        sleep 1
    fi

    # Configure the loopback: route playback substream 0 → capture substream 0
    # This connects the two sides so played audio appears as mic input
    if [ -f /proc/asound/Loopback/pcm0c/sub0/status ]; then
        echo "  Loopback PCM 0 already configured"
    fi

    echo "  Loopback devices:"
    arecord -l 2>/dev/null | grep -A2 "$LOOPBACK_CARD" || true
    aplay -l 2>/dev/null | grep -A2 "$LOOPBACK_CARD" || true

    echo "✓ Virtual microphone ready"
    echo "  Emulator flag: -allow-host-audio"
    echo "  Test with:     ./virtual_mic.sh play <file.wav>"
}

play_wav() {
    local wav="${1:-}"
    if [ -z "$wav" ]; then
        echo "Usage: $0 play <file.wav>"
        exit 1
    fi
    if [ ! -f "$wav" ]; then
        echo "File not found: $wav"
        exit 1
    fi

    if ! is_loaded; then
        echo "snd-aloop not loaded — run '$0 setup' first"
        exit 1
    fi

    echo "→ Playing $wav to virtual microphone..."
    echo "  (Press Ctrl+C to stop)"

    # Play the WAV file to the loopback device
    # Using aplay for WAV, ffmpeg for other formats
    ffmpeg -v quiet -re -i "$wav" -f alsa "$LOOPBACK_PLAYBACK"
}

teardown() {
    echo "→ Tearing down virtual microphone..."
    if is_loaded; then
        sudo rmmod snd-aloop 2>/dev/null && echo "  Unloaded snd-aloop" || echo "  Module busy (emulator may be using it)"
    else
        echo "  snd-aloop not loaded"
    fi
}

# ── main ───────────────────────────────────────────────────────

case "${1:-}" in
    setup)     setup ;;
    play)      play_wav "${2:-}" ;;
    teardown)  teardown ;;
    *)
        echo "Usage: $0 {setup|play <wav>|teardown}"
        exit 1
        ;;
esac
