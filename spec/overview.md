# Overview

## What is Talaria?

Talaria is a real-time voice gateway between an Android app and Hermes Agent. It runs as a container service in the agent-stack homelab, exposed via Cloudflare Tunnel at `talaria.ffilt.us`. Talaria provides the audio processing pipeline (STT + TTS) that Hermes does not natively include for custom clients, and bridges the Android app to the Hermes API Server via WebSocket.

## Why Talaria?

Hermes Agent supports voice messages on Telegram (record → transcribe → process → reply with TTS bubble) and Discord voice channels natively. However, there is no first-party mechanism for:

1. A **custom mobile client** (not a messaging platform) to do push-to-talk
2. **Low-latency streaming** from Android microphone → STT → Hermes → TTS → Android speaker
3. **Session continuity** across PTT turns while walking around the house

Talaria fills this gap by providing a lightweight, focused WebSocket gateway that does exactly one thing: relay audio from a PTT client through the STT→Hermes→TTS pipeline and stream the response back.

## Functional Goals

1. **PTT voice → Hermes** — User presses a button, speaks, releases. Audio is transcribed and sent to Hermes.
2. **Hermes reply → voice** — Hermes text response is synthesized to speech and streamed to the phone.
3. **Low latency** — STT→Hermes→TTS roundtrip under 5 seconds for typical queries.
4. **Session continuity** — Multiple PTT turns maintain the Hermes conversation context.
5. **Secure** — Cloudflare Access service tokens authenticate every request.

## Non-Goals

1. Not a multi-turn streaming conversation (no barge-in, no duplex STT stream)
2. Not a replacement for Hermes's built-in Telegram/Discord voice support
3. Not a general-purpose voice assistant SDK
4. No wake-word detection (explicit PTT only)
5. No voice activity detection (start/stop controlled by user's button)
