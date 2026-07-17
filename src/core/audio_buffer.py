"""
Audio buffer management with size limits and thread safety.

This module provides bounded audio storage with proper thread safety
to prevent memory exhaustion during long audio sessions.
"""

import asyncio
from typing import List, Dict, Any
from dataclasses import dataclass


@dataclass
class AudioBufferConfig:
    """Configuration for audio buffer management."""
    max_size: int = 10 * 1024 * 1024  # 10MB total buffer
    max_chunk_size: int = 5 * 1024 * 1024  # 5MB per chunk
    max_chunks: int = 1000  # Maximum number of chunks


class AudioBufferManager:
    """Manages audio buffers with size limits and thread safety.
    
    This class provides bounded audio storage to prevent memory exhaustion
    during long audio sessions or when clients disconnect and reconnect.
    """
    
    def __init__(self, config: "AudioBufferConfig" = None) -> None:
        self.config = config or AudioBufferConfig()
        self.chunks: List[bytes] = []
        self._lock = asyncio.Lock()
        self._validate_config()
    
    def _validate_config(self) -> None:
        """Validate buffer configuration."""
        if self.config.max_size <= 0:
            raise ValueError("max_size must be positive")
        if self.config.max_chunk_size <= 0:
            raise ValueError("max_chunk_size must be positive")
        if self.config.max_chunk_size > self.config.max_size:
            raise ValueError("max_chunk_size cannot exceed max_size")
        if self.config.max_chunks <= 0:
            raise ValueError("max_chunks must be positive")
    
    async def add_chunk(self, chunk: bytes) -> bool:
        """Add audio chunk with size validation and bounds checking.
        
        Args:
            chunk: Raw audio bytes to add to buffer
            
        Returns:
            True if chunk was added, False if rejected due to size
        """
        # Type checking and validation
        if not isinstance(chunk, bytes):
            raise TypeError("chunk must be bytes")
        
        if len(chunk) > self.config.max_chunk_size:
            return False
        
        async with self._lock:
            current_size = sum(len(c) for c in self.chunks)
            
            if current_size + len(chunk) > self.config.max_size:
                space_needed = (current_size + len(chunk)) - self.config.max_size
                while space_needed > 0 and self.chunks:
                    oldest_chunk = self.chunks.pop(0)
                    space_needed -= len(oldest_chunk)
            
            # Also enforce chunk count limit
            if len(self.chunks) >= self.config.max_chunks:
                self.chunks.pop(0)
            
            self.chunks.append(chunk)
            return True
    
    async def add_chunks(self, chunks: List[bytes]) -> int:
        """Add multiple audio chunks.
        
        Args:
            chunks: List of audio bytes to add
            
        Returns:
            Number of chunks successfully added
        """
        added = 0
        for chunk in chunks:
            if await self.add_chunk(chunk):
                added += 1
        return added
    
    async def get_all(self) -> bytes:
        """Get all audio data as single bytes object."""
        async with self._lock:
            return b"".join(self.chunks)
    
    async def get_chunks(self, max_chunks: int = None) -> List[bytes]:
        """Get audio chunks, optionally limited by count."""
        async with self._lock:
            if max_chunks is None:
                return self.chunks.copy()
            return self.chunks[-max_chunks:].copy()
    
    async def clear(self) -> None:
        """Clear all audio chunks."""
        async with self._lock:
            self.chunks.clear()
    
    def get_stats(self) -> dict:
        """Get buffer statistics."""
        total_size = sum(len(c) for c in self.chunks)
        return {
            "chunk_count": len(self.chunks),
            "total_size": total_size,
            "max_size": self.config.max_size,
            "max_chunk_size": self.config.max_chunk_size,
            "max_chunks": self.config.max_chunks,
            "utilization": total_size / self.config.max_size,
        }
