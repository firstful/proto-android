"""
WebSocket connection management with type safety and lifecycle handling.

This module provides comprehensive WebSocket connection management
including state tracking, message handling, and connection lifecycle.
"""

import asyncio
import time
import uuid
from typing import Optional, Dict, Any, List, Set
from enum import Enum
from dataclasses import dataclass, field

from .types import PTTState


class ConnectionState(Enum):
    """WebSocket connection lifecycle states."""
    IDLE = "idle"
    HANDSHAKE = "handshake"
    ACTIVE = "active"
    CLOSING = "closing"
    CLOSED = "closed"


@dataclass
class ConnectionStats:
    """Connection statistics and health metrics."""
    connection_id: str
    state: str
    session_id: Optional[str] = None
    protocols: List[str] = field(default_factory=list)
    message_queue_size: int = 0
    send_queue_size: int = 0
    active_tasks: int = 0
    is_healthy: bool = True
    uptime: float = 0.0
    last_heartbeat: float = 0.0
    messages_sent: int = 0
    messages_received: int = 0
    bytes_sent: int = 0
    bytes_received: int = 0


class WebSocketConnection:
    """Manages a single WebSocket connection with type safety and lifecycle handling.
    
    This class provides comprehensive WebSocket connection management
    including state tracking, message handling, and connection lifecycle.
    """
    
    def __init__(
        self,
        websocket,
        connection_id: str = None,
        protocols: Optional[List[str]] = None
    ) -> None:
        self.websocket = websocket
        self.connection_id = connection_id or str(uuid.uuid4())
        self.protocols = protocols or []
        self.state: ConnectionState = ConnectionState.HANDSHAKE
        self.session_id: Optional[str] = None
        self.thread_id: Optional[str] = None
        self.last_heartbeat: float = 0.0
        self.message_queue: asyncio.Queue = asyncio.Queue()
        self.send_queue: asyncio.Queue = asyncio.Queue()
        self.tasks: List[asyncio.Task] = []
        self.sequence_number: int = 0
        self.ack_sequence: int = 0
        
        # PTT state machine
        self.ptt_state: PTTState = PTTState.IDLE
        self.audio_buffer: List[bytes] = []
        self.current_run_id: Optional[str] = None
        self.current_session_id: Optional[str] = None
        self.current_profile: Optional[str] = None
        self.current_client_message_id: Optional[str] = None
        self.cancel_event: asyncio.Event = asyncio.Event()
        
        # Metrics
        self.messages_sent: int = 0
        self.messages_received: int = 0
        self.bytes_sent: int = 0
        self.bytes_received: int = 0
        self.created_at: float = 0
        self.last_activity: float = 0
    
    async def accept_connection(self, subprotocols: Optional[List[str]] = None) -> None:
        """Accept the WebSocket connection."""
        await self.websocket.accept(subprotocols or [])
        self.state = ConnectionState.ACTIVE
        import time
        self.created_at = time.time()
        self.last_heartbeat = time.time()
        self.last_activity = time.time()
    
    async def send_message(self, message: dict, *, seq: Optional[int] = None) -> int:
        """Send a message with sequence number.
        
        Args:
            message: Message dictionary to send
            seq: Optional sequence number (auto-generated if not provided)
            
        Returns:
            Sequence number of the sent message
        """
        if seq is None:
            seq = self._get_next_sequence()
        message["seq"] = seq
        try:
            await self.websocket.send_json(message)
            self.messages_sent += 1
            self.last_activity = time.time()
            return seq
        except Exception as e:
            self.state = ConnectionState.CLOSED
            raise
    
    async def send_binary(self, data: bytes) -> int:
        """Send binary data with sequence number.
        
        Args:
            data: Binary audio data to send
            
        Returns:
            Sequence number of the sent data
        """
        seq = self._get_next_sequence()
        
        try:
            await self.websocket.send_bytes(data)
            self.messages_sent += 1
            self.bytes_sent += len(data)
            self.last_activity = time.time()
            return seq
        except Exception as e:
            self.state = ConnectionState.CLOSED
            raise
    
    def _get_next_sequence(self) -> int:
        """Get the next sequence number for messages."""
        self.sequence_number += 1
        if self.sequence_number > 2**31 - 1:  # Prevent overflow
            self.sequence_number = 1
        return self.sequence_number
    
    async def close(self, code: int = 1000, reason: Optional[str] = None) -> None:
        """Close the WebSocket connection gracefully.
        
        Args:
            code: WebSocket close code
            reason: Optional close reason
        """
        if self.state != ConnectionState.CLOSED:
            self.state = ConnectionState.CLOSING
            try:
                await self.websocket.close(code, reason or "Normal closure")
            except Exception:
                pass
            finally:
                self.state = ConnectionState.CLOSED
        
        # Cancel all background tasks
        for task in self.tasks:
            task.cancel()
        
        # Wait for tasks to complete
        if self.tasks:
            await asyncio.gather(*self.tasks, return_exceptions=True)
    
    def update_heartbeat(self) -> None:
        """Update the heartbeat timestamp."""
        import time
        self.last_heartbeat = time.time()
        self.last_activity = time.time()
    
    def is_healthy(self, timeout_seconds: float = 30.0) -> bool:
        """Check if connection is healthy based on heartbeat.
        
        Args:
            timeout_seconds: Maximum time since last heartbeat
            
        Returns:
            True if connection is healthy
        """
        import time
        current_time = time.time()
        return (current_time - self.last_heartbeat) < timeout_seconds
    
    def get_stats(self) -> dict:
        """Get connection statistics."""
        import time
        return {
            "connection_id": self.connection_id,
            "state": self.state.value,
            "ptt_state": self.ptt_state.value,
            "session_id": self.session_id,
            "thread_id": self.thread_id,
            "protocols": self.protocols,
            "message_queue_size": 0,  # Placeholder
            "send_queue_size": 0,  # Placeholder
            "active_tasks": len([t for t in self.tasks if not t.done()]),
            "is_healthy": self.is_healthy(),
            "uptime": time.time() - self.created_at,
            "last_heartbeat": self.last_heartbeat,
            "messages_sent": self.messages_sent,
            "messages_received": self.messages_received,
            "bytes_sent": self.bytes_sent,
            "bytes_received": self.bytes_received,
        }
    
    def update_ack(self, ack_seq: int) -> None:
        """Update acknowledged sequence number."""
        if ack_seq > self.ack_sequence:
            self.ack_sequence = ack_seq
    
    def get_unacknowledged_count(self) -> int:
        """Get count of unacknowledged messages."""
        return self.sequence_number - self.ack_sequence
