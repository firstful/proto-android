"""
Connection replay storage for handling disconnections and reconnections.

This module provides in-memory storage for completed/ interrupted runs
to allow clients to resume playback after a WebSocket disconnection.
"""

import asyncio
import time
from typing import Optional, Dict, Tuple, List, Any
from dataclasses import dataclass, field
from enum import Enum
import logging

logger = logging.getLogger(__name__)

RECONNECT_TTL = 300.0  # 5 minutes


class RunStatus(str, Enum):
    """Status of a stored run."""
    PENDING = "pending"
    COMPLETE = "complete"
    INTERRUPTED = "interrupted"
    CONSUMED = "consumed"


@dataclass
class StoredRun:
    """A stored run for replay after disconnection."""
    run_id: str
    session_id: str
    transcript: str
    reply_text: str
    audio_chunks: List[bytes] = field(default_factory=list)
    seq_start: int = 0
    seq_end: int = 0
    complete: bool = False
    interrupted: bool = False
    thread_id: str = ""
    is_text_input: bool = False
    status: RunStatus = RunStatus.PENDING
    created_at: float = 0.0


class ReplayStore:
    """Keeps completed/interrupted runs per session+thread for async replay.
    
    Each (session_id, thread_id) pair maps to its most recent unconsumed
    run. Once a run has been fully delivered to a freshly-connected client
    it is evicted. Old orphaned runs are purged by a background task.
    """
    
    def __init__(self, ttl: float = RECONNECT_TTL) -> None:
        self._runs: Dict[Tuple[str, str], StoredRun] = {}
        self._consumed: set = set()
        self._ttl = ttl
        self._cleanup_task: Optional[asyncio.Task] = None
    
    @staticmethod
    def _make_key(session_id: str, thread_id: str = "") -> Tuple[str, str]:
        return (session_id, thread_id)
    
    def start_background_cleanup(self) -> None:
        """Run periodic eviction of stale runs."""
        async def _clean() -> None:
            while True:
                await asyncio.sleep(self._ttl)
                now = time.monotonic()
                stale = [
                    key for key, run in self._runs.items()
                    if (now - run.created_at) > self._ttl
                ]
                for key in stale:
                    run_id = self._runs[key].run_id
                    self._runs.pop(key, None)
                    self._consumed.discard(run_id)
                if stale:
                    logger.info(f"Cleaned up {len(stale)} stale runs")
        
        self._cleanup_task = asyncio.create_task(_clean())
    
    def put(self, run: StoredRun) -> None:
        """Store a run for potential replay."""
        key = self._make_key(run.session_id, run.thread_id)
        self._runs[key] = run
    
    def get(self, session_id: str, thread_id: str = "") -> Optional[StoredRun]:
        """Get a stored run by session and thread ID."""
        return self._runs.get(self._make_key(session_id, thread_id))
    
    def mark_consumed(self, session_id: str, thread_id: str = "") -> None:
        """Mark a run as consumed (fully delivered to client)."""
        key = self._make_key(session_id, thread_id)
        if key in self._runs:
            self._consumed.add(self._runs[key].run_id)
            del self._runs[key]
    
    def __contains__(self, key) -> bool:
        """Supports both str (v1: session_id) and tuple (v2: (session_id, thread_id))."""
        if isinstance(key, str):
            key = self._make_key(key)
        return key in self._runs
    
    @property
    def pending_count(self) -> int:
        """Number of pending runs."""
        return len(self._runs)
    
    def get_all_pending(self) -> List[StoredRun]:
        """Get all pending runs."""
        return list(self._runs.values())
    
    async def shutdown(self) -> None:
        """Clean shutdown of background tasks."""
        if self._cleanup_task and not self._cleanup_task.done():
            self._cleanup_task.cancel()
            try:
                await self._cleanup_task
            except asyncio.CancelledError:
                pass
