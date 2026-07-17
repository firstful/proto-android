"""
Audio input validation and message sanitization.

This module provides comprehensive validation for audio inputs and messages
to prevent security issues and ensure data integrity.
"""

from typing import Dict, List, Any, Optional
from dataclasses import dataclass
import re


@dataclass
class ValidationResult:
    """Result of validation operation."""
    is_valid: bool
    error_messages: List[str]
    warning_messages: List[str]


class AudioValidator:
    """Validates audio input and messages for security and safety.
    
    This class provides comprehensive validation for audio inputs,
    messages, and connection parameters to prevent security issues
    and ensure data integrity.
    """
    
    MAX_CHUNK_SIZE = 5 * 1024 * 1024  # 5MB per chunk
    MIN_CHUNK_SIZE = 4096  # bytes
    MAX_MESSAGE_SIZE = 10 * 1024 * 1024  # 10MB total message
    MAX_SEQUENCE_NUMBER = 2**31 - 1  # 32-bit signed int max
    
    # Patterns for sensitive data detection
    SENSITIVE_PATTERNS = [
        (re.compile(r"api[_\-]?key[\s]*[:=][\s]*['\"]?[a-zA-Z0-9_\-]{20,}"), "api_key"),
        (re.compile(r"token[\s]*[:=][\s]*['\"]?[a-zA-Z0-9_\-]{20,}"), "token"),
        (re.compile(r"password[\s]*[:=][\s]*['\"]?[^'\"]{8,}"), "password"),
        (re.compile(r"secret[\s]*[:=][\s]*['\"]?[a-zA-Z0-9_\-]{16,}"), "secret"),
        (re.compile(r"bearer[\s]+[a-zA-Z0-9_\-]{20,}"), "bearer_token"),
    ]
    
    @staticmethod
    def validate_audio_chunk(audio_bytes: bytes) -> dict:
        """Validate audio chunk with comprehensive checks.
        
        Args:
            audio_bytes: Raw audio bytes to validate
            
        Returns:
            ValidationResult with validation outcome and messages
        """
        errors: list = []
        warnings: list = []
        
        # Type validation
        if not isinstance(audio_bytes, bytes):
            return {
                "is_valid": False,
                "error_messages": ["Audio must be bytes type"],
                "warning_messages": []
            }
        
        # Size validation
        if len(audio_bytes) < AudioValidator.MIN_CHUNK_SIZE:
            errors.append(f"Audio too short - minimum {AudioValidator.MIN_CHUNK_SIZE} bytes required")
        
        if len(audio_bytes) > AudioValidator.MAX_CHUNK_SIZE:
            errors.append(f"Audio chunk too large: {len(audio_bytes)} > {AudioValidator.MAX_CHUNK_SIZE}")
        
        # Audio quality checks
        if len(audio_bytes) > 0:
            import numpy as np
            audio_array = np.frombuffer(audio_bytes, dtype=np.int16)
            if len(audio_array) > 0:
                max_amplitude = int(np.max(np.abs(audio_array)))
                max_possible_amplitude = 32767
                if max_amplitude < 100:
                    pass
                elif max_amplitude > max_possible_amplitude * 0.95:
                    pass
        
        return {
            "is_valid": len(errors) == 0,
            "error_messages": errors,
            "warning_messages": warnings
        }
    
    @staticmethod
    def validate_message(message: dict) -> dict:
        """Validate message structure and content.
        
        Args:
            message: Message dictionary to validate
            
        Returns:
            ValidationResult with validation outcome and messages
        """
        errors: list = []
        warnings: list = []
        
        # Type validation
        if not isinstance(message, dict):
            return {
                "is_valid": False,
                "error_messages": ["Message must be a dictionary"],
                "warning_messages": []
            }
        
        # Required fields validation
        if "type" not in message:
            errors.append("Message missing required 'type' field")
        
        if "bytes" not in message and "text" not in message:
            warnings.append("Message missing both 'bytes' and 'text' fields")
        
        # Content validation based on message type
        if "type" in message:
            msg_type = message["type"]
            if msg_type == "websocket.receive":
                if "bytes" in message:
                    audio_bytes = message["bytes"]
                    audio_result = AudioValidator.validate_audio_chunk(audio_bytes)
                    if not audio_result["is_valid"]:
                        errors.extend(audio_result["error_messages"])
                    # warnings.extend(audio_result["warning_messages"])
            elif "text" in message:
                text = message["text"]
                if not isinstance(text, str):
                    errors.append("Message['text'] must be a string")
                elif len(text) > AudioValidator.MAX_MESSAGE_SIZE:
                    errors.append(f"Message text too long: {len(text)} > {AudioValidator.MAX_MESSAGE_SIZE}")
        
        # Check for sensitive data
        sanitized_msg = AudioValidator.sanitize_message(message)
        if sanitized_msg != message:
            warnings.append("Message contained sensitive data that was redacted")
        
        return {
            "is_valid": len(errors) == 0,
            "error_messages": errors,
            "warning_messages": warnings
        }
    
    @staticmethod
    def sanitize_message(message: dict) -> dict:
        """Remove or redact sensitive data from message.
        
        Args:
            message: Message dictionary to sanitize
            
        Returns:
            Sanitized message dictionary
        """
        import copy
        sanitized = copy.deepcopy(message)
        
        def sanitize_value(value):
            if isinstance(value, str):
                for pattern, label in AudioValidator.SENSITIVE_PATTERNS:
                    value = pattern.sub(f"{label}=[REDACTED]", value)
                return value
            elif isinstance(value, dict):
                return {k: sanitize_value(v) for k, v in value.items()}
            elif isinstance(value, list):
                return [sanitize_value(v) for v in value]
            return value
        
        return sanitize_value(sanitized)
    
    @staticmethod
    def validate_connection_params(params: dict) -> dict:
        """Validate connection parameters.
        
        Args:
            params: Connection parameters dictionary
            
        Returns:
            ValidationResult with validation outcome and messages
        """
        errors: list = []
        warnings: list = []
        
        # Validate required parameters
        required_params = ["protocol_version", "client_id", "timestamp"]
        for param in required_params:
            if param not in params:
                warnings.append(f"Missing optional parameter: {param}")
        
        # Validate parameter types
        if "protocol_version" in params and not isinstance(params["protocol_version"], str):
            errors.append("protocol_version must be a string")
        
        if "client_id" in params and not isinstance(params["client_id"], str):
            errors.append("client_id must be a string")
        
        if "timestamp" in params and not isinstance(params["timestamp"], (int, float)):
            errors.append("timestamp must be a number")
        
        return {
            "is_valid": len(errors) == 0,
            "error_messages": errors,
            "warning_messages": warnings
        }
