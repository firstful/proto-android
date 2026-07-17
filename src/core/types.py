"""
Core type definitions and enums for the Talaria project.

This module provides comprehensive type annotations for the Talaria voice
PTT (Push-to-Talk) gateway, enabling better IDE support, static type
checking, and improved code maintainability.

The types defined here are used throughout the Talaria system for:
- State machine management (PTTState)
- Message handling (MessageType, AudioChunk, ProcessingResult)
- Connection management (ConnectionInfo, ErrorInfo)
- Async operation results
"""

from typing import Any, TypedDict, Dict, List, Optional, Union, TypeVar, Generic
from enum import Enum

# Type variable for generic operations
T = TypeVar("T")
K = TypeVar("K")
V = TypeVar("V")

# =======================
# Core Enums and State Types
# =======================

class PTTState(str, Enum):
    """PTT (Push-to-Talk) state machine states.

    Represents the current state of a PTT interaction session.
    Each state defines what actions can be taken and what processing
    is occurring.
    """

    IDLE = "idle"              # Waiting for PTT start
    RECORDING = "recording"    # Audio being captured
    TRANSCRIBING = "transcribing"  # Converting audio to text
    HERMES_RUNNING = "hermes_running"  # Agent is processing
    TTS_STREAMING = "tts_streaming"   # Audio response being streamed
    INTERRUPTED = "interrupted"      # Current processing interrupted

    def __str__(self) -> str:
        return self.value

    def __repr__(self) -> str:
        return f"PTTState.{self.name}({self.value})"

    def can_transition_to(self, target_state: "PTTState") -> bool:
        """Check if this state can transition to target state."""
        # State transition rules
        transition_rules: Dict[PTTState, List[PTTState]] = {
            PTTState.IDLE: [PTTState.RECORDING, PTTState.IDLE],
            PTTState.RECORDING: [PTTState.TRANSCRIBING, PTTState.IDLE],
            PTTState.TRANSCRIBING: [PTTState.HERMES_RUNNING, PTTState.IDLE],
            PTTState.HERMES_RUNNING: [PTTState.TTS_STREAMING, PTTState.INTERRUPTED],
            PTTState.TTS_STREAMING: [PTTState.IDLE, PTTState.INTERRUPTED],
            PTTState.INTERRUPTED: [PTTState.IDLE, PTTState.RECORDING],
        }

        valid_transitions = transition_rules.get(self, [])
        return target_state in valid_transitions

class MessageType(str, Enum):
    """Enumeration of WebSocket message types.

    Defines the protocol for communication between the Talaria server
    and its clients (Android app and web interfaces).
    """

    WEBSOCKET_RECEIVE = "websocket.receive"  # Audio or text data received
    WEBSOCKET_DISCONNECT = "websocket.disconnect"  # Connection closed
    WEBSOCKET_CONNECT = "websocket.connect"  # New connection established
    PTT_START = "ptt_start"  # PTT button pressed
    PTT_END = "ptt_end"  # PTT button released
    INTERRUPT = "interrupt"  # Barge-in mid-turn
    CANCEL = "cancel"  # Cancel current operation
    PING = "ping"  # Keepalive ping
    ACK = "ack"  # Acknowledge receipt of sequence number
    PONG = "pong"  # Ping response
    CONNECT = "connect"  # Establish connection
    SESSION_ID = "session_id"  # Hermes session identifier
    PROCESSING = "processing"  # Processing status update
    STT_TRANSCRIPT = "stt_transcript"  # Speech-to-text result
    TTS_START = "tts_start"  # TTS streaming started
    TTS_END = "tts_end"  # TTS streaming completed
    TTS_FALLBACK = "tts_fallback"  # Text fallback for unavailable TTS
    ERROR = "error"  # Error occurred
    RECONNECT = "reconnect"  # Reconnection handshake
    RECONNECT_DONE = "reconnect_done"  # Reconnection completed
    SESSION_SWITCHED = "session_switched"  # Session switch event
    TEXT_INPUT = "text_input"  # Text input from client

    def __str__(self) -> str:
        return self.value

    def __repr__(self) -> str:
        return f"MessageType.{self.name}({self.value})"

# =======================
# Typed Data Structures
# =======================

class AudioChunk(TypedDict):
    """Represents an audio chunk with comprehensive metadata.

    Used for streaming audio data between components and for replay
    functionality when connections are interrupted.
    """

    # Unique identifier for this chunk
    id: str
    # Raw audio data in bytes
    data: bytes
    # Timestamp when chunk was captured
    timestamp: float
    # Audio sample rate (typically 16000 Hz for Talaria)
    sample_rate: int
    # Audio format encoding
    format: str
    # Duration of audio in seconds
    duration: float

class ProcessingResult(TypedDict):
    """Result of audio processing operations.

    Contains the processed speech-to-text results and metadata
    for logging and analytics.
    """

    # Transcribed text from speech
    transcript: str
    # Confidence score (0.0 to 1.0)
    confidence: float
    # Optional speaker identifier (for multi-speaker scenarios)
    speaker_id: Optional[str]
    # Processing latency in seconds
    processing_time: float

    # Optional additional metadata
    metadata: Dict[str, Any]

class ConnectionInfo(TypedDict):
    """Connection state information for diagnostics and monitoring."""

    # Unique session identifier
    session_id: Optional[str]
    # Current connection state
    state: str
    # Size of audio buffer in bytes
    audio_buffer_size: int
    # Whether there's an active Hermes run
    has_run: bool

    # Connection health metrics
    latency: Optional[float]
    packet_loss: Optional[float]
    uptime: Optional[float]

class ErrorInfo(TypedDict):
    """Structured error information for consistent error handling."""

    # Error type/category
    error_type: str
    # Human-readable error message
    message: str
    # Additional context about the error
    details: Optional[Dict[str, Any]]
    # Error code for programmatic handling
    error_code: Optional[str]
    # Whether the error is recoverable
    recoverable: bool

class EventData(TypedDict):
    """Generic event data structure for Talaria event system."""

    # Event timestamp
    timestamp: float
    # Event source
    source: str
    # Event type
    event_type: str
    # Event data payload
    data: Dict[str, Any]
    # Optional metadata
    metadata: Optional[Dict[str, Any]]

# =======================
# Generic Type Definitions
# =======================

T = TypeVar("T")  # Generic type variable
K = TypeVar("K")  # Key type variable
V = TypeVar("V")  # Value type variable

class AsyncResult(TypedDict, Generic[T]):
    """Generic result type for async operations."""

    # Whether the operation succeeded
    success: bool
    # Result data (if success)
    data: Optional[T]
    # Error information (if failed)
    error: Optional[ErrorInfo]

class StreamResult(TypedDict, Generic[T]):
    """Result for streaming operations."""

    # Stream identifier
    stream_id: str
    # Current stream state
    state: str
    # Stream progress (0.0 to 1.0)
    progress: float
    # Stream data chunks
    chunks: List[T]
    # Total expected chunks
    total_chunks: int

class SessionConfig(TypedDict):
    """Configuration for PTT session."""

    # Session identifier
    session_id: str
    # User identifier
    user_id: Optional[str]
    # Audio processing configuration
    audio_config: Dict[str, Any]
    # Hermes API configuration
    hermes_config: Dict[str, Any]
    # Session timeout in seconds
    timeout: float
    # Maximum audio buffer size
    max_audio_size: int

# =======================
# Utility Functions
# =======================

def validate_audio_chunk(audio_bytes: bytes) -> bool:
    """Validate audio chunk for required properties."""
    return (isinstance(audio_bytes, bytes) and
            len(audio_bytes) > 0 and
            len(audio_bytes) <= 10 * 1024 * 1024)  # 10MB max

def sanitize_error_message(message: str) -> str:
    """Sanitize error message for safe logging."""
    # Remove potentially sensitive information
    import re
    # Remove API keys, tokens, passwords
    sanitized = re.sub(r"api_key[=:][^&\\s]*", "api_key=[REDACTED]", message)
    sanitized = re.sub(r"token[=:][^&\\s]*", "token=[REDACTED]", sanitized)
    sanitized = re.sub(r"password[=:][^&\\s]*", "password=[REDACTED]", sanitized)
    return sanitized

# Public API export
__all__ = [
    # Core types
    "PTTState",
    "MessageType",
    "AudioChunk",
    "ProcessingResult",
    "ConnectionInfo",
    "ErrorInfo",
    "EventData",
    # Type variables
    "T",
    "K",
    "V",
    # Generic types
    "AsyncResult",
    "StreamResult",
    "SessionConfig",
    # Utility functions
    "validate_audio_chunk",
    "sanitize_error_message",
]
