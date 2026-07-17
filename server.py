"""
Talaria — Real-time voice gateway for Hermes Agent.

Single consumer (Reed), hardcoded for the homelab.
WebSocket PTT protocol: binary Opus in, binary Opus + JSON events out.
Supports barge-in: pressing PTT mid-turn interrupts the agent.
"""

import asyncio
import json
import logging
import os
import time
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Query
from fastapi.responses import JSONResponse

from src.core.connection import WebSocketConnection, ConnectionState, PTTState
from src.core.state_machine import StateMachine
from src.core.audio_buffer import AudioBufferManager, AudioBufferConfig
from src.core.replay_store import ReplayStore, StoredRun
from src.core.audio_validator import AudioValidator
from src.audio.stt import STTProcessor, STTConfig, StreamingSTTProcessor
from src.audio.tts import TTSProcessor, TTSConfig
from src.services.hermes_client import HermesClient, HermesConfig
from src.protocol.websocket_server import MessageHandler

logger = logging.getLogger("talaria")

# ── Config ────────────────────────────────────────────────────

HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8321"))

MAX_AUDIO_CHUNK_SIZE = int(os.environ.get("MAX_AUDIO_CHUNK_SIZE", "5242880"))
MAX_AUDIO_BUFFER_SIZE = int(os.environ.get("MAX_AUDIO_BUFFER_SIZE", "52428800"))

ALLOWED_FILE_ROOTS = os.environ.get("ALLOWED_FILE_ROOTS", "/srv,/home,/tmp").split(",")

# ── Globals ───────────────────────────────────────────────────

run_store = ReplayStore()
stt = STTProcessor(STTConfig.from_env())
tts = TTSProcessor(TTSConfig.from_env())
hermes = HermesClient(HermesConfig.from_env())

_outbound_seq: int = 0


def next_seq() -> int:
    global _outbound_seq
    _outbound_seq += 1
    return _outbound_seq


# ── FastAPI app ───────────────────────────────────────────────

from contextlib import asynccontextmanager


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Talaria starting up...")
    run_store.start_background_cleanup()

    # Pre-load STT model in background (non-blocking — health check returns immediately)
    async def _preload_stt():
        try:
            await stt.ensure_model_loaded()
            logger.info("STT model pre-loaded: %s", stt.config.model)
        except Exception as e:
            logger.warning("STT model pre-load failed (will retry on first PTT): %s", e)

    asyncio.create_task(_preload_stt())

    logger.info("Talaria ready on %s:%s", HOST, PORT)
    yield
    await hermes.close()
    await run_store.shutdown()
    logger.info("Talaria shutting down")


app = FastAPI(title="Talaria Voice Gateway", version="2.0.0", lifespan=lifespan)


@app.get("/health")
async def health():
    hermes_ok = await hermes.health_check()
    return {
        "status": "ok",
        "stt_loaded": stt.is_ready(),
        "tts_available": tts.is_available(),
        "hermes_api": hermes.config.base_url,
        "hermes_healthy": hermes_ok,
        "pending_replays": run_store.pending_count,
    }


# ── File reading endpoint ────────────────────────────────────

@app.get("/api/file")
async def read_file(path: str = Query(..., description="Absolute file path")):
    """Read a file from the host filesystem. Restricted to ALLOWED_FILE_ROOTS."""
    try:
        resolved = Path(path).resolve()
    except Exception:
        return JSONResponse({"error": "Invalid path"}, status_code=400)

    allowed = False
    for root in ALLOWED_FILE_ROOTS:
        root = root.strip()
        if root and str(resolved).startswith(root):
            allowed = True
            break

    if not allowed:
        return JSONResponse({"error": "Path not in allowed roots"}, status_code=403)

    if not resolved.exists():
        return JSONResponse({"error": "File not found"}, status_code=404)

    if not resolved.is_file():
        return JSONResponse({"error": "Not a file"}, status_code=400)

    try:
        size = resolved.stat().st_size
        if size > 1024 * 1024:  # 1MB limit
            return JSONResponse({"error": "File too large (>1MB)"}, status_code=413)

        content = resolved.read_text(errors="replace")
        return {
            "path": str(resolved),
            "size": size,
            "content": content,
        }
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


# ── Profiles endpoint ────────────────────────────────────────

@app.get("/api/profiles")
async def list_profiles():
    """List available Hermes profiles."""
    profiles = list(hermes.config.profiles.keys()) if hermes.config.profiles else ["default"]
    models = await hermes.list_models()
    return {
        "profiles": profiles,
        "models": [m.get("id", "") for m in models],
    }


# ── WebSocket PTT handler ─────────────────────────────────────

@app.websocket("/v1/ptt")
async def ptt_websocket(ws: WebSocket):
    await ws.accept()
    logger.info("WebSocket connected")

    conn = WebSocketConnection(ws)
    handler = MessageHandler(conn, run_store)
    audio_buffer = AudioBufferManager(AudioBufferConfig(
        max_size=MAX_AUDIO_BUFFER_SIZE,
        max_chunk_size=MAX_AUDIO_CHUNK_SIZE,
    ))
    streaming_stt = StreamingSTTProcessor(stt)
    cancel_event = asyncio.Event()
    active_tasks = []

    async def send_json(msg: dict, *, seq: Optional[int] = None) -> int:
        if seq is None:
            seq = next_seq()
        msg["seq"] = seq
        try:
            await ws.send_json(msg)
            return seq
        except Exception:
            return -1

    async def send_bytes_with_seq(data: bytes) -> int:
        seq = next_seq()
        try:
            await ws.send_bytes(data)
            return seq
        except Exception:
            return -1

    # ── Reconnect handshake ─────────────────────────────────

    replayed = False
    first_pending = None
    try:
        first_msg = await ws.receive()
    except WebSocketDisconnect:
        logger.info("Client disconnected during handshake")
        return

    if first_msg.get("type") == "websocket.receive" and "text" in first_msg:
        try:
            handshake = json.loads(first_msg["text"])
            if handshake.get("type") == "reconnect":
                conn_session_id = handshake.get("session_id", "")
                conn_thread_id = handshake.get("thread_id", "")
                conn_last_seq = handshake.get("last_seq", 0)
                conn_profile = handshake.get("profile", "")
                conn_profile = conn_profile or None

                if conn_session_id:
                    conn.current_session_id = conn_session_id
                if conn_thread_id:
                    conn.thread_id = conn_thread_id
                if conn_profile:
                    conn.current_profile = conn_profile

                logger.info(
                    "Reconnect handshake: session=%s thread=%s last_seq=%d profile=%s",
                    conn_session_id, conn_thread_id, conn_last_seq, conn_profile,
                )
                lookup_key = (conn_session_id, conn_thread_id) if conn_thread_id else conn_session_id
                if conn_session_id and lookup_key in run_store:
                    result = run_store.get(conn_session_id, conn_thread_id or "")
                    if result and result.created_at:
                        replayed = True
                        await send_json({
                            "type": "reconnect_done",
                            "replayed": True,
                            "complete": result.complete,
                        })
                    else:
                        await send_json({
                            "type": "reconnect_done",
                            "replayed": False,
                        })
                else:
                    await send_json({
                        "type": "reconnect_done",
                        "replayed": False,
                    })
            else:
                await send_json({
                    "type": "reconnect_done",
                    "replayed": False,
                })
                first_pending = first_msg
        except json.JSONDecodeError:
            await send_json({
                "type": "reconnect_done",
                "replayed": False,
            })
            first_pending = first_msg
    else:
        await send_json({
            "type": "reconnect_done",
            "replayed": False,
        })
        first_pending = first_msg

    # ── Main loop ───────────────────────────────────────────

    try:
        while True:
            if not replayed and first_pending is not None:
                message = first_pending
                first_pending = None
            else:
                message = await ws.receive()

            if message["type"] == "websocket.disconnect":
                break

            if message["type"] == "websocket.receive":
                # ── Binary: audio data ──
                if "bytes" in message:
                    if conn.ptt_state == PTTState.IDLE:
                        conn.ptt_state = PTTState.RECORDING
                        await streaming_stt.clear()
                    if conn.ptt_state == PTTState.RECORDING:
                        chunk = message["bytes"]
                        await audio_buffer.add_chunk(chunk)

                        # Stream partial transcripts
                        partial = await streaming_stt.add_audio(chunk)
                        if partial:
                            await send_json({
                                "type": "stt_partial",
                                "text": partial,
                                "final": False,
                            })

                # ── Text: control message ──
                elif "text" in message:
                    try:
                        cmd = json.loads(message["text"])
                    except json.JSONDecodeError:
                        continue

                    cmd_type = cmd.get("type", "")

                    if cmd_type == "ptt_start":
                        await handler.handle_ptt_start(cmd)

                    elif cmd_type == "ptt_end":
                        task = asyncio.create_task(
                            handler.handle_ptt_end(
                                audio_buffer, stt, hermes, tts, cancel_event,
                                streaming_stt=streaming_stt,
                            )
                        )
                        active_tasks.append(task)

                    elif cmd_type == "interrupt":
                        task = asyncio.create_task(handler.handle_interrupt(hermes))
                        active_tasks.append(task)

                    elif cmd_type == "cancel":
                        await audio_buffer.clear()
                        await streaming_stt.clear()
                        conn.ptt_state = PTTState.IDLE

                    elif cmd_type == "ping":
                        await send_json({"type": "pong"})

                    elif cmd_type == "ack":
                        ack_seq = cmd.get("seq", 0)
                        conn.update_ack(ack_seq)

                    elif cmd_type == "text_input":
                        # Send echo immediately from main loop (avoids WS send hang in background tasks)
                        echo_msg = {"type": "text_echo", "text": cmd.get("text", "")}
                        client_msg_id = cmd.get("client_message_id")
                        if client_msg_id:
                            echo_msg["client_message_id"] = client_msg_id
                        await send_json(echo_msg)
                        
                        task = asyncio.create_task(
                            handler.handle_text_input(cmd, hermes, run_store)
                        )
                        active_tasks.append(task)

                    elif cmd_type == "switch_session":
                        await handler.handle_switch_session(cmd)

                    elif cmd_type == "list_sessions":
                        task = asyncio.create_task(
                            handler.handle_list_sessions(cmd, hermes)
                        )
                        active_tasks.append(task)

                    elif cmd_type == "fork_session":
                        task = asyncio.create_task(
                            handler.handle_fork_session(cmd, hermes)
                        )
                        active_tasks.append(task)

                    elif cmd_type == "get_session_history":
                        task = asyncio.create_task(
                            handler.handle_get_session_history(cmd, hermes)
                        )
                        active_tasks.append(task)

                    elif cmd_type == "set_profile":
                        await handler.handle_set_profile(cmd)

                    elif cmd_type == "feedback":
                        await handler.handle_feedback(cmd)

    except WebSocketDisconnect:
        logger.info("WebSocket disconnected")
    except Exception as e:
        logger.exception("WebSocket error")
        try:
            await send_json({"type": "error", "message": str(e)})
        except Exception:
            pass
    finally:
        cancel_event.set()
        if conn.current_run_id and conn.ptt_state in (PTTState.HERMES_RUNNING, PTTState.TTS_STREAMING):
            try:
                await hermes.stop_run(conn.current_run_id, profile=conn.current_profile)
            except Exception:
                pass
        for t in active_tasks:
            t.cancel()
        if active_tasks:
            await asyncio.gather(*active_tasks, return_exceptions=True)
        logger.info(
            "Connection closed (session=%s, profile=%s, runs_in_store=%d)",
            conn.current_session_id, conn.current_profile, run_store.pending_count,
        )


# ── Entry point ────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
        force=True,
    )
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
