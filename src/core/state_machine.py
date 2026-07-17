"""
PTT state machine with type safety and transition validation.

This module provides the PTT (Push-to-Talk) state machine for managing
connection states and validating state transitions.
"""

from typing import Dict, List
import logging

from .types import PTTState

logger = logging.getLogger(__name__)


# Valid state transitions
TRANSITIONS: Dict[PTTState, List[PTTState]] = {
    PTTState.IDLE: [PTTState.RECORDING, PTTState.IDLE],
    PTTState.RECORDING: [PTTState.TRANSCRIBING, PTTState.IDLE],
    PTTState.TRANSCRIBING: [PTTState.HERMES_RUNNING, PTTState.IDLE],
    PTTState.HERMES_RUNNING: [PTTState.TTS_STREAMING, PTTState.INTERRUPTED],
    PTTState.TTS_STREAMING: [PTTState.IDLE, PTTState.INTERRUPTED],
    PTTState.INTERRUPTED: [PTTState.IDLE, PTTState.RECORDING],
}


class StateMachine:
    """PTT state machine with transition validation."""
    
    def __init__(self) -> None:
        self.current_state: PTTState = PTTState.IDLE
        self._history: List[PTTState] = [PTTState.IDLE]
    
    def can_transition(self, new_state: PTTState) -> bool:
        """Check if transition to new_state is valid."""
        valid_transitions = TRANSITIONS.get(self.current_state, [])
        return new_state in valid_transitions
    
    def transition(self, new_state: PTTState) -> bool:
        """Transition to new_state if valid.
        
        Returns:
            True if transition was successful
        """
        if self.can_transition(new_state):
            old_state = self.current_state
            self.current_state = new_state
            self._history.append(new_state)
            logger.debug(f"State transition: {old_state} -> {new_state}")
            return True
        else:
            logger.warning(f"Invalid state transition: {self.current_state} -> {new_state}")
            return False
    
    def get_history(self) -> List[PTTState]:
        """Get state transition history."""
        return self._history.copy()
    
    def reset(self) -> None:
        """Reset state machine to IDLE."""
        self.current_state = PTTState.IDLE
        self._history = [PTTState.IDLE]
