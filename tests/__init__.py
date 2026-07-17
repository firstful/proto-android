"""Talaria test suite."""

import asyncio
import json
from typing import Optional

import websockets
from websockets.asyncio.client import ClientConnection


# ── WebSocket helpers ───────────────────────────────────────────

async def ws_send_json(ws: ClientConnection, data: dict) -> None:
    """Send a JSON control message to the Talaria server."""
    await ws.send(json.dumps(data))


async def ws_recv_json(ws: ClientConnection, timeout: float = 15.0) -> dict:
    """Receive the next JSON text message from the server."""
    try:
        raw = await asyncio.wait_for(ws.recv(), timeout=timeout)
    except asyncio.TimeoutError:
        raise TimeoutError(f"No message received within {timeout}s")

    if isinstance(raw, bytes):
        raise ValueError(f"Expected text frame, got {len(raw)} bytes")
    return json.loads(raw)


async def ws_handshake(ws: ClientConnection) -> dict:
    """
    Perform the Talaria WebSocket handshake.

    The server expects the client to send the first message.
    We send a ping; the server responds with reconnect_done + pong.
    Returns the reconnect_done message.
    """
    await ws_send_json(ws, {"type": "ping"})
    reconnect_msg = await ws_recv_json(ws, timeout=10)
    if reconnect_msg.get("type") != "reconnect_done":
        raise RuntimeError(f"Expected reconnect_done, got {reconnect_msg.get('type')}")
    # The pong follows — consume it
    pong = await ws_recv_json(ws, timeout=5)
    if pong.get("type") != "pong":
        # If not pong, push it back? We can't. Just log.
        pass
    return reconnect_msg


async def ws_connect(url: str = "ws://localhost:4004/v1/ptt", retries: int = 3) -> ClientConnection:
    """Connect to Talaria and complete the handshake. Returns ready WebSocket.

    Retries on connection failure (server may be recovering from keepalive closes).
    """
    last_error = None
    for attempt in range(retries):
        try:
            ws = await websockets.connect(url, open_timeout=10)
            await ws_handshake(ws)
            return ws
        except Exception as e:
            last_error = e
            if attempt < retries - 1:
                await asyncio.sleep(1.0 * (attempt + 1))
    raise last_error or RuntimeError("Failed to connect")
