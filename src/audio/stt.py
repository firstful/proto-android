"""
Speech-to-Text processing with type safety and error handling.

This module provides comprehensive STT processing using faster-whisper
with type safety, comprehensive error handling, and streaming partial
transcription support.
"""

import asyncio
import numpy as np
from typing import Optional, Dict, Any, List
from dataclasses import dataclass, field
from faster_whisper import WhisperModel
import logging

logger = logging.getLogger(__name__)


@dataclass
class STTConfig:
    """Configuration for Speech-to-Text processing."""
    model: str = "base"
    language: Optional[str] = None
    beam_size: int = 5
    vad_filter: bool = True
    vad_parameters: Optional[Dict[str, Any]] = None

    @classmethod
    def from_env(cls) -> 'STTConfig':
        """Create configuration from environment variables."""
        import os
        return cls(
            model=os.getenv("STT_MODEL", "base"),
            language=os.getenv("STT_LANGUAGE"),
            beam_size=int(os.getenv("STT_BEAM_SIZE", "5")),
            vad_filter=os.getenv("STT_VAD_FILTER", "true").lower() == "true",
        )


class STTProcessor:
    """Handles speech-to-text processing with comprehensive type safety."""

    def __init__(self, config: Optional["STTConfig"] = None) -> None:
        self.config = config or STTConfig.from_env()
        self.model: Optional[WhisperModel] = None
        self.is_loaded: bool = False
        self._init_lock = asyncio.Lock()

    async def ensure_model_loaded(self) -> None:
        """Lazy-load the STT model when first needed."""
        if self.is_loaded:
            return

        async with self._init_lock:
            if self.is_loaded:
                return

            try:
                logger.info("Loading STT model: %s", self.config.model)
                self.model = WhisperModel(
                    self.config.model,
                    device="cpu",
                    compute_type="int8",
                )
                self.is_loaded = True
                logger.info("STT model loaded: %s", self.config.model)
            except Exception as e:
                logger.error("Failed to load STT model: %s", e)
                raise

    async def transcribe(
        self,
        audio_bytes: bytes,
        options: Optional[Dict[str, Any]] = None,
    ) -> str:
        """Transcribe audio to text with error handling."""
        if not audio_bytes:
            raise ValueError("Audio bytes cannot be empty")

        if len(audio_bytes) < 16000:
            raise ValueError("Audio too short - minimum 1 second required")

        if len(audio_bytes) > 10 * 1024 * 1024:
            raise ValueError("Audio too large - maximum 10MB")

        await self.ensure_model_loaded()

        if self.model is None:
            raise RuntimeError("STT model failed to initialize")

        try:
            audio_array = np.frombuffer(audio_bytes, dtype=np.int16)
            audio_float = audio_array.astype(np.float32) / 32768.0

            segments, info = self.model.transcribe(
                audio_float,
                beam_size=self.config.beam_size,
                language=self.config.language,
                word_timestamps=True,
                vad_filter=self.config.vad_filter,
                vad_parameters=self.config.vad_parameters,
            )

            transcript = " ".join(segment.text for segment in segments)

            logger.info("Transcription completed: %d chars, language: %s", len(transcript), info.language)
            return transcript.strip()

        except Exception as e:
            logger.error("STT processing failed: %s", e)
            raise RuntimeError(f"Transcription failed: {e}")

    def get_model_info(self) -> Dict[str, Any]:
        """Get comprehensive model information."""
        return {
            "model_name": self.config.model,
            "loaded": self.is_loaded,
            "device": "cpu",
            "compute_type": "int8",
            "language": self.config.language,
            "beam_size": self.config.beam_size,
            "vad_filter": self.config.vad_filter,
        }

    def is_ready(self) -> bool:
        """Check if the STT processor is ready to process audio."""
        return self.is_loaded and self.model is not None


class StreamingSTTProcessor:
    """Streaming STT with LocalAgreement-2 for partial transcripts.

    Maintains an audio buffer that grows as chunks arrive during recording.
    Periodically runs Whisper on the accumulated buffer and emits stable
    partial text using LocalAgreement-2: text is only emitted when two
    consecutive transcriptions agree on a prefix.
    """

    MIN_AUDIO_BYTES = 16000 * 2  # ~0.5s at 16kHz s16le

    def __init__(self, stt_processor: STTProcessor) -> None:
        self.stt = stt_processor
        self._buffer = bytearray()
        self._last_output = ""
        self._confirmed_prefix = ""
        self._lock = asyncio.Lock()

    async def add_audio(self, chunk: bytes) -> Optional[str]:
        """Add audio chunk and return new partial text if available.

        Returns the newly confirmed text fragment, or None if nothing
        new is stable yet.
        """
        async with self._lock:
            self._buffer.extend(chunk)

            if len(self._buffer) < self.MIN_AUDIO_BYTES:
                return None

            if self.stt.model is None:
                return None

            try:
                audio_array = np.frombuffer(bytes(self._buffer), dtype=np.int16)
                audio_float = audio_array.astype(np.float32) / 32768.0

                segments, _ = self.stt.model.transcribe(
                    audio_float,
                    beam_size=3,
                    language=self.stt.config.language,
                    word_timestamps=True,
                    vad_filter=self.stt.config.vad_filter,
                )

                current_text = " ".join(s.text for s in segments).strip()

                if not current_text:
                    return None

                # LocalAgreement-2: find the common prefix between
                # the last output and current output
                stable = self._common_prefix(self._last_output, current_text)
                self._last_output = current_text

                # Emit new confirmed text beyond what we already sent
                if len(stable) > len(self._confirmed_prefix):
                    new_text = stable[len(self._confirmed_prefix):]
                    self._confirmed_prefix = stable
                    return new_text.strip()

            except Exception as e:
                logger.warning("Streaming STT partial failed: %s", e)

            return None

    async def finalize(self) -> str:
        """Run final high-accuracy transcription on the full buffer."""
        async with self._lock:
            if not self._buffer:
                return ""

            if self.stt.model is None:
                return ""

            try:
                audio_bytes = bytes(self._buffer)
                self._buffer.clear()
                self._last_output = ""
                self._confirmed_prefix = ""

                return await self.stt.transcribe(audio_bytes)

            except Exception as e:
                logger.error("Streaming STT finalize failed: %s", e)
                return ""

    async def clear(self) -> None:
        """Reset the buffer and state."""
        async with self._lock:
            self._buffer.clear()
            self._last_output = ""
            self._confirmed_prefix = ""

    @staticmethod
    def _common_prefix(a: str, b: str) -> str:
        """Find the longest common prefix of two strings."""
        i = 0
        min_len = min(len(a), len(b))
        while i < min_len and a[i] == b[i]:
            i += 1
        return a[:i]
