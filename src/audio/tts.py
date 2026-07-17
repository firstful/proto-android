"""
Text-to-Speech processing with type safety and streaming support.

This module provides comprehensive TTS processing using edge-tts
with streaming support and comprehensive error handling.
"""

import asyncio
import subprocess
from typing import Optional, Dict, Any, AsyncGenerator
from dataclasses import dataclass
import edge_tts
import logging

logger = logging.getLogger(__name__)


@dataclass
class TTSConfig:
    """Configuration for Text-to-Speech processing."""
    voice: str = "en-US-ChristopherNeural"
    rate: str = "+0%"
    volume: str = "+0%"
    pitch: str = "+0Hz"
    output_format: str = "opus"
    sample_rate: int = 16000
    bitrate: str = "24k"
    
    @classmethod
    def from_env(cls) -> 'TTSConfig':
        """Create configuration from environment variables."""
        import os
        return cls(
            voice=os.getenv("TTS_VOICE", "en-US-ChristopherNeural"),
            rate=os.getenv("TTS_RATE", "+0%"),
            volume=os.getenv("TTS_VOLUME", "+0%"),
            pitch=os.getenv("TTS_PITCH", "+0Hz"),
            output_format=os.getenv("TTS_FORMAT", "opus"),
            sample_rate=int(os.getenv("TTS_SAMPLE_RATE", "16000")),
            bitrate=os.getenv("TTS_BITRATE", "24k")
        )


class TTSProcessor:
    """Handles text-to-speech processing with streaming support."""
    
    def __init__(self, config: Optional["TTSConfig"] = None) -> None:
        self.config = config or TTSConfig.from_env()
        self._tts_available: bool = False
        self._init_lock = asyncio.Lock()
    
    async def ensure_tts_available(self) -> None:
        """Check if TTS is available."""
        if self._tts_available:
            return
        
        async with self._init_lock:
            if self._tts_available:
                return
            
            try:
                voices = await edge_tts.list_voices()
                self._tts_available = len(voices) > 0
                logger.info(f"TTS available: {len(voices)} voices")
            except Exception as e:
                logger.warning(f"Edge TTS not available: {e}")
                self._tts_available = False
    
    async def synthesize(
        self, 
        text: str, 
        cancel_event: Optional[asyncio.Event] = None,
        config_override: Optional[Dict[str, Any]] = None
    ) -> AsyncGenerator[bytes, None]:
        """Stream TTS audio as Opus chunks.
        
        Args:
            text: Text to synthesize
            cancel_event: Optional event to cancel synthesis
            config_override: Optional configuration override
            
        Yields:
            Opus-encoded audio chunks
            
        Yields:
            Opus audio chunks as bytes
        """
        await self.ensure_tts_available()
        
        if not self._tts_available:
            logger.warning("TTS not available, yielding empty")
            return
        
        if not text or not text.strip():
            logger.warning("Empty text provided to TTS")
            return
        
        # Apply configuration
        config = self.config
        if config_override:
            # Apply overrides
            pass
        
        communicate = edge_tts.Communicate(
            text=text.strip(),
            voice=config.voice,
            rate=config.rate,
            volume=config.volume,
            pitch=config.pitch
        )
        
        # Start ffmpeg process for Opus encoding
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg",
            "-i", "pipe:0",
            "-acodec", "libopus",
            "-ac", "1",
            "-ar", "16000",
            "-b:a", "24k",
            "-f", "opus",
            "-page_duration", "20000",
            "pipe:1",
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        
        async def feed_tts():
            """Feed TTS audio to ffmpeg."""
            buffer = b""
            async for chunk in communicate.stream():
                if cancel_event and cancel_event.is_set():
                    break
                if chunk["type"] == "audio":
                    buffer += chunk["data"]
            if buffer and not (cancel_event and cancel_event.is_set()):
                proc.stdin.write(buffer)
            proc.stdin.close()
        
        feed_task = asyncio.create_task(feed_tts())
        
        try:
            while True:
                if cancel_event and cancel_event.is_set():
                    proc.kill()
                    break
                
                chunk = await asyncio.wait_for(proc.stdout.read(4096), timeout=0.5)
                if not chunk:
                    break
                yield chunk
            
            await proc.wait()
        except asyncio.TimeoutError:
            pass  # Normal — no data yet, continue loop
        except Exception:
            proc.kill()
            raise
        finally:
            feed_task.cancel()
            try:
                await feed_task
            except asyncio.CancelledError:
                pass
    
    def is_available(self) -> bool:
        """Check if TTS is available."""
        return self._tts_available
    
    def get_config_info(self) -> dict:
        """Get TTS configuration information."""
        return {
            "voice": self.config.voice,
            "rate": self.config.rate,
            "volume": self.config.volume,
            "pitch": self.config.pitch,
            "output_format": self.config.output_format,
            "sample_rate": self.config.sample_rate,
            "bitrate": self.config.bitrate,
        }
