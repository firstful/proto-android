"""
Hermes API Server — lightweight /v1/runs proxy for Talaria.

Exposes the three endpoints Talaria needs:
  POST /v1/runs                    — start a run, returns {run_id, session_id}
  GET  /v1/runs/{run_id}/events    — SSE stream of agent response events
  POST /v1/runs/{run_id}/stop      — interrupt a running agent

Runs the Hermes agent in-process via subprocess (hermes -z oneshot mode)
and feeds the reply text back through an SSE stream.
"""
import asyncio
import json
import logging
import os
import subprocess
import time
import uuid
from contextlib import asynccontextmanager
from typing import Optional

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

logger = logging.getLogger("hermes-api")

# ── Config ────────────────────────────────────────────────────

HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "9119"))
HERMES_BIN = os.environ.get("HERMES_BIN", "/root/.local/bin/hermes")
MODEL = os.environ.get("HERMES_MODEL", "deepseek-v4-flash")

# ── In-memory run store ──────────────────────────────────────


class RunState:
    """Tracks a single Hermes run for SSE streaming and stop support."""

    def __init__(self, run_id: str):
        self.run_id = run_id
        self.created = time.monotonic()
        self.queue: asyncio.Queue[dict | None] = asyncio.Queue()
        self.task: Optional[asyncio.Task] = None
        self.process: Optional[subprocess.Popen] = None


_run_store: dict[str, RunState] = {}
_run_store_lock = asyncio.Lock()


async def _get_run(run_id: str) -> RunState:
    async with _run_store_lock:
        state = _run_store.get(run_id)
        if state is None:
            raise HTTPException(404, f"Run {run_id} not found")
        return state


# ── Request schemas ──────────────────────────────────────────


class StartRunRequest(BaseModel):
    messages: list[dict]
    stream: bool = True
    session_id: Optional[str] = None


# ── Background runner ────────────────────────────────────────


async def _run_hermes(state: RunState, prompt: str):
    """Run hermes -z in a subprocess, feed result text into the SSE queue."""
    try:
        proc = await asyncio.create_subprocess_exec(
            HERMES_BIN,
            "-z", prompt,
            "-m", MODEL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        state.process = proc

        stdout, stderr = await asyncio.wait_for(
            proc.communicate(), timeout=300.0
        )

        if proc.returncode != 0:
            err = stderr.decode().strip() or f"exit code {proc.returncode}"
            await state.queue.put({"type": "error", "message": err})
            await state.queue.put(None)
            return

        reply = stdout.decode().strip()
        logger.info("Hermes reply (%d chars): %s", len(reply), reply[:100])

        # Feed reply text as SSE events
        # Send a single message event with the full reply
        await state.queue.put({
            "type": "message",
            "content": {"type": "text", "text": reply},
        })
        await state.queue.put({"type": "done"})
        await state.queue.put(None)

    except asyncio.TimeoutError:
        await state.queue.put({"type": "error", "message": "Hermes timeout (300s)"})
        await state.queue.put(None)
    except Exception as e:
        logger.exception("Hermes runner failed")
        await state.queue.put({"type": "error", "message": str(e)})
        await state.queue.put(None)
    finally:
        state.process = None


# ── FastAPI app ──────────────────────────────────────────────


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Hermes API server starting on %s:%s", HOST, PORT)
    yield
    # Cancel all active runs on shutdown
    async with _run_store_lock:
        for run_id, state in _run_store.items():
            if state.task and not state.task.done():
                state.task.cancel()
            if state.process:
                try:
                    state.process.kill()
                except Exception:
                    pass
        _run_store.clear()


app = FastAPI(title="Hermes API Server", lifespan=lifespan)


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/v1/runs")
async def start_run(req: StartRunRequest):
    # Extract the last user message as the prompt
    prompt = ""
    for msg in reversed(req.messages):
        if msg.get("role") == "user":
            prompt = msg.get("content", "")
            break

    if not prompt:
        raise HTTPException(400, "No user message found")

    run_id = uuid.uuid4().hex[:12]
    session_id = req.session_id or f"talaria-{uuid.uuid4().hex[:8]}"

    state = RunState(run_id)
    async with _run_store_lock:
        _run_store[run_id] = state

    # Start background Hermes runner
    state.task = asyncio.create_task(_run_hermes(state, prompt))

    logger.info("Run %s started: %s", run_id, prompt[:80])
    return {"run_id": run_id, "session_id": session_id, "status": "running"}


@app.get("/v1/runs/{run_id}/events")
async def stream_events(run_id: str):
    state = await _get_run(run_id)

    async def event_stream():
        while True:
            event = await state.queue.get()
            if event is None:
                break
            yield f"data: {json.dumps(event)}\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")


@app.post("/v1/runs/{run_id}/stop")
async def stop_run(run_id: str):
    state = await _get_run(run_id)
    if state.task and not state.task.done():
        state.task.cancel()
    if state.process:
        try:
            state.process.kill()
        except Exception:
            pass
    logger.info("Run %s stopped", run_id)
    return {"status": "stopped"}


# ── Entry point ──────────────────────────────────────────────

if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    )
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
