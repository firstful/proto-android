# Talaria Implementation Guide - Phase 1: Critical Security Fixes

## Immediate Actions Required (Priority 1-3)

This guide outlines the essential fixes to address the most critical security, stability, and maintainability issues in the Talaria codebase.

---

## Phase 1: SECURITY - Fix Hardcoded Secrets (HIGH PRIORITY)

### Problem
API keys are hardcoded in `agent-stack.json` - **CRITICAL SECURITY RISK**

**Current Location**: `/host/srv/agent-stack/repos/talaria/agent-stack.json:13`

```json
{
  "name": "talaria",
  "container": {
    "env": {
      "HERMES_API_KEY": "GCAQyy2CIlnEwNa-7ykl3stA4xJ-hO2oTo_x_bmNhWw"  // ❌ SECURITY VULNERABILITY
    }
  }
}
```

### Solution
Replace hardcoded keys with environment variable pattern that controller resolves at deploy time

```json
{
  "name": "talaria",
  "container": {
    "env": {
      "HERMES_API_KEY": "{SECRETS.HERMES_API_KEY}"  // ✅ Secure pattern
    }
  }
}
```

### Required Actions
1. **Update agent-stack.json line 13** - Replace hardcoded API key with `{SECRETS.HERMES_API_KEY}`
2. **Verify controller integration** - Ensure controller resolves `SECRETS.*` patterns correctly

### Commands to Execute
```bash
# Update the JSON file
sed -i 's/HERMES_API_KEY": "GCAQyy2CIlnEwNa-7ykl3stA4xJ-hO2oTo_x_bmNhWw"/HERMES_API_KEY": "{SECRETS.HERMES_API_KEY}"/g' repos/talaria/agent-stack.json

# Verify the change
cat repos/talaria/agent-stack.json | grep HERMES_API_KEY
```

---

## Phase 2: ARCHITECTURE - Extract Monolithic Server Function (HIGH PRIORITY)

### Problem: Single 197-line function violates Single Responsibility Principle

**Current Function**: `ptt_websocket` at `/host/srv/agent-stack/repos/talaria/server.py:572-770`

```python
@ptt_websocket(ws: WebSocket)  # Entry point Line 387
async def ptt_websocket(ws: WebSocket):  # Main loop Line 572
    """This function handles ALL of the following:
    - Connection management
    - State transitions
    - Message parsing  
    - Audio processing (STT/TTS)
    - Error handling
    - ALL IN ONE 197-LINE FUNCTION!
    """
```

### Solution: Extract into Modular Architecture

```text
RECOMMENDED STRUCTURE:
talaria/src/
├── core/
│   ├── connection.py        # WebSocket lifecycle
│   ├── state_machine.py     # PTTState enum and transitions
│   └── errors.py            # Custom exceptions
├── audio/
│   ├── stt.py              # Speech-to-text processing
│   ├── tts.py              # Text-to-speech processing
│   └── audio_processor.py  # Audio utilities and validation
├── protocol/
│   ├── websocket_server.py # Main WebSocket handler
│   └── message_handlers.py # Command message processing
└── services/
    ├── hermes_client.py    # Hermes API integration
    └── replay_store.py     # Connection replay storage
```

### Required Actions
1. **Create src/ directory structure** with specified subdirectories
2. **Extract ConnectionManager class** from `ptt_websocket` function
3. **Extract AudioProcessor class** for STT/TTS operations
4. **Extract StateMachine class** for PTT state management
5. **Extract message handlers** into separate classes
6. **Update imports** and dependencies

### Code Refactoring Example

```python
# BEFORE: Everything in one 197-line function
@ptt_websocket(ws: WebSocket)  # Line 387
async def ptt_websocket(ws: WebSocket):  # Line 572
    # 197 lines doing everything...

# AFTER: Modular architecture
class ConnectionManager:
    """Handles WebSocket connection lifecycle"""
    def __init__(self, ws: WebSocket, run_store: RunStore):
        self.ws = ws
        self.run_store = run_store
        self.state = PTTState.IDLE
    
    async def handle_connection(self) -> None:
        """Entry point - delegates to specialized handlers"""
        await self._setup_connection()
        await self._main_message_loop()

class AudioProcessor:
    """Handles audio STT/TTS processing"""
    def __init__(self, config: dict):
        self.max_audio_size = config.get('max_audio_size', 5 * 1024 * 1024)
        self.buffer = AudioBufferManager(self.max_audio_size)
    
    async def process_audio(self, audio_bytes: bytes) -> str:
        # Validate input
        if not isinstance(audio_bytes, bytes):
            raise ValueError("Audio must be bytes type")
        if len(audio_bytes) > self.max_audio_size:
            raise ValueError(f"Audio chunk exceeds max size: {len(audio_bytes)} > {self.max_audio_size}")
        
        # Process with STT
        return await self._transcribe(audio_bytes)

class HermesClient:
    """Manages Hermes API interactions"""
    def __init__(self, api_key: str, base_url: str):
        self.client = httpx.AsyncClient(timeout=120.0, headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}"
        })
        self.base_url = base_url
    
    async def start_run(self, text: str, session_id: Optional[str] = None):
        payload = {
            "input": text,
            "stream": True,
            "session_id": session_id
        }
        response = await self.client.post(f"{self.base_url}/v1/runs", json=payload)
        response.raise_for_status()
        return response.json()
```

### Implementation Steps
```bash
# Step 1: Create directory structure
mkdir -p repos/talaria/src/{core,audio,protocol,services}/tests/{unit,integration}

# Step 2: Move existing source files
mv repos/talaria/server.py repos/talaria/src/protocol/websocket_server.py
mv repos/talaria/hermes_api_server.py repos/talaria/src/services/hermes_client.py

# Step 3: Create modular components
# (Create connection.py, state_machine.py, audio_processor.py, etc.)
```

---

## Phase 3: STABILITY - Fix Memory Management Issues (HIGH PRIORITY)

### Problem 1: Unbounded Audio Buffer Growth

**Current Code** (Potential memory exhaustion):
```python
# ❌ NO LIMITS - Can grow to crash server
audio_buffer: list[bytes] = []
audio_buffer.append(message["bytes"])  # Unlimited growth!

# Later in TTS handler
current_result.audio_chunks.append(chunk)  # No size validation!
```

**Solution**: Implement bounded buffers with size limits

```python
# ✅ IMPLEMENTATION: Audio buffer management
class AudioBufferManager:
    def __init__(self, max_size: int = 10 * 1024 * 1024):  # 10MB limit
        self.chunks = []
        self.max_size = max_size
        self._lock = asyncio.Lock()
    
    async def add_chunk(self, chunk: bytes) -> bool:
        """Add audio chunk, return True if successful"""
        async with self._lock:
            # Validate chunk size
            if len(chunk) > self.max_size:
                return False
            
            # Make room if needed
            current_size = sum(len(c) for c in self.chunks)
            space_needed = (current_size + len(chunk)) - self.max_size
            while space_needed > 0 and self.chunks:
                oldest_chunk = self.chunks.pop(0)
                space_needed -= len(oldest_chunk)
            
            self.chunks.append(chunk)
            return True
    
    def get_all(self) -> bytes:
        """Get all audio data as single bytes object"""
        return b"".join(self.chunks)
    
    def clear(self):
        self.chunks.clear()

# Usage in server code:
# OLD: audio_buffer.append(message["bytes"])
# NEW: buffer_manager.add_chunk(message["bytes"])
```

### Problem 2: Input Validation Missing

**Current Code** (No validation - security and stability issues):
```python
# ❌ NO VALIDATION - accepts anything
if message["type"] == "websocket.receive":
    if "bytes" in message:
        audio_bytes = message["bytes"]  # Could be None, wrong type
        await process_audio(audio_bytes)  # Crashes if invalid!
```

**Solution**: Comprehensive input validation

```python
# ✅ IMPLEMENTATION: Input validation
class AudioValidator:
    MAX_CHUNK_SIZE = 5 * 1024 * 1024  # 5MB
    
    @staticmethod
    def validate_message(message: dict) -> None:
        """Validate message structure and contents"""
        if not isinstance(message, dict):
            raise ValueError("Message must be a dictionary")
        
        if "type" not in message:
            raise ValueError("Message missing required 'type' field")
        
        if message["type"] == "websocket.receive":
            if "bytes" not in message:
                raise ValueError("websocket.receive message missing 'bytes' field")
            
            # Validate byte type and size
            audio_bytes = message["bytes"]
            if not isinstance(audio_bytes, bytes):
                raise ValueError("Message['bytes'] must be bytes type")
            if len(audio_bytes) > AudioValidator.MAX_CHUNK_SIZE:
                raise ValueError(f"Audio chunk too large: {len(audio_bytes)} > {AudioValidator.MAX_CHUNK_SIZE}")
```

### Problem 3: Concurrency Race Conditions

**Current Code** (Shared state modification without locking):
```python
# ❌ RACE CONDITION - Multiple tasks modify shared data
async for chunk in synthesize(reply, cancel_event=cancel_event):
    if cancel_event.is_set():
        break
    seq = await send_bytes_with_seq(chunk)
    if current_result is not None:
        current_result.audio_chunks.append(chunk)  # RACE!
        current_result.seq_start = seq or current_result.seq_start  # RACE!
        current_result.seq_end = seq + 1  # RACE!
        if current_session_id:
            run_store.put(current_result)  # RACE!
```

**Solution**: Thread-safe data structures

```python
# ✅ IMPLEMENTATION: Thread-safe audio processing
class ThreadSafeAudioProcessor:
    def __init__(self, run_store: RunStore, max_buffer_size: int = 10 * 1024 * 1024):
        self.run_store = run_store
        self._audio_processor = AudioProcessor()
        self._buffer_manager = AudioBufferManager(max_buffer_size)
        self._lock = asyncio.Lock()
        self._current_result: Optional[RunResult] = None
        self._session_id: Optional[str] = None
    
    async def process_audio_chunk(self, chunk: bytes, seq: int):
        """Process audio chunk thread-safely"""
        async with self._lock:
            # Validate input first
            if not isinstance(chunk, bytes):
                raise ValueError(f"Invalid chunk type: {type(chunk)}")
            if len(chunk) > self._buffer_manager.max_size:
                raise ValueError(f"Chunk too large: {len(chunk)}")
            
            # Add to buffer safely
            if not await self._buffer_manager.add_chunk(chunk):
                return  # Buffer full - reject chunk
            
            # Update processing state
            if self._current_result is None:
                self._current_result = RunResult(
                    run_id="temp_run",
                    session_id=self._session_id,
                    transcript="",
                    reply_text="",
                    created_at=time.monotonic()
                )
            
            # Update result state
            self._current_result.audio_chunks.append(chunk)
            if self._current_result.seq_start == 0:
                self._current_result.seq_start = seq
            self._current_result.seq_end = seq + 1
            
            # Persist to store
            if self._session_id:
                self.run_store.put(self._current_result)
```

---

## Phase 4: DEPENDENCY MANAGEMENT - Clean Up Unused Code (MEDIUM PRIORITY)

### Problem 1: Unused API Server
**File**: `hermes_api_server.py` - Marked as "NOT in use" in `HANDOFF.md`

**Status**: Experimental duplicate of functionality already in main server

**Solution Options**:
1. **Integrate into main server** - Remove duplicate code
2. **Delete entirely** - Remove unused file
3. **Keep for reference** - Mark as deprecated

**Recommended Action**: Delete unused file (simple, low risk)
```bash
rm repos/talaria/hermes_api_server.py
```

### Problem 2: Dead Code
**File**: Unused `_replay_run` function inside `ptt_websocket`

```python
async def _replay_run(result: RunResult) -> bool:
    """Unused replay function - 57 lines of dead code"""
    # ... implementation ...
    # === NEVER CALLED ===
```

**Solution**: Remove function and clean up any references

### Problem 3: Build Tools Cleanup

Current directory contains build artifacts in `android/`:
```text
android/
├── .gradle/              # Gradle cache
├── app/build/           # Android build artifacts  
├── gradle/              # Gradle wrapper
└── gradlew*             # Gradle wrapper scripts
```

**Action**: Exclude build artifacts from version control (`.gitignore`)

---

## Phase 5: OPERATIONAL - Add Monitoring and Configuration (MEDIUM PRIORITY)

### Problem 1: No Configuration Management

**Current**: Hardcoded values throughout code

```python
# ❌ Hardcoded values
AUDIO_SAMPLE_RATE = 16000
OPUS_BITRATE = "24k"
MAX_AUDIO_CHUNK_SIZE = 5242880  # 5MB
```

**Solution**: Environment-based configuration

```python
# ✅ Environment-based config
import os

class Config:
    AUDIO_SAMPLE_RATE = int(os.environ.get("AUDIO_SAMPLE_RATE", "16000"))
    OPUS_BITRATE = os.environ.get("OPUS_BITRATE", "24k")
    MAX_AUDIO_CHUNK_SIZE = int(os.environ.get("MAX_AUDIO_CHUNK_SIZE", str(5 * 1024 * 1024)))
    MAX_AUDIO_BUFFER_SIZE = int(os.environ.get("MAX_AUDIO_BUFFER_SIZE", str(50 * 1024 * 1024)))
    LOG_LEVEL = os.environ.get("LOG_LEVEL", "info")
```

### Problem 2: Limited Observability

**Current**: Basic health check only

```python
@app.get("/health")
async def health():
    return {
        "status": "ok",
        "stt_loaded": _stt_pipeline is not None,
        "tts_available": _tts_available,
        "hermes_api": HERMES_API_URL,
        "pending_replays": run_store.pending_count,
    }
```

**Solution**: Enhanced monitoring with metrics

```python
import asyncio
from prometheus_client import Counter, Histogram, generate_latest

# Metrics
REQUEST_COUNT = Counter('talaria_requests_total', 'Total requests', ['method', 'endpoint'])
REQUEST_DURATION = Histogram('talaria_request_duration_seconds', 'Request duration')
AUDIO_CHUNK_COUNT = Counter('talaria_audio_chunks_total', 'Processed audio chunks')
MEMORY_USAGE = Gauge('talaria_memory_usage_bytes', 'Memory usage')

@app.get("/health")
async def health():
    return {
        "status": "ok",
        "stt_loaded": _stt_pipeline is not None,
        "tts_available": _tts_available,
        "hermes_api": HERMES_API_URL,
        "pending_replays": run_store.pending_count,
        "metrics": {
            "requests_total": REQUEST_COUNT._value.get(),
            "audio_chunks_processed": AUDIO_CHUNK_COUNT._value.get(),
        }
    }

@app.get("/metrics")
async def metrics():
    return generate_latest()
```

---

## IMPLEMENTATION CHECKLIST

### Before Starting Code Changes
- [ ] **Backup current code** - `git stash` or create backup copies
- [ ] **Set up test environment** - Ensure ability to run tests
- [ ] **Create documentation** - Update `architecture.md` and `code-review.md`
- [ ] **Plan rollback strategy** - Know how to revert changes

### During Implementation
- [ ] **Commit changes incrementally** - Small, focused commits
- [ ] **Run tests after each change** - Verify functionality
- [ ] **Update documentation** - Keep docs in sync
- [ ] **Check for regressions** - Ensure existing features still work
- [ ] **Validate security fixes** - Confirm vulnerabilities are resolved

### After Implementation
- [ ] **Run full test suite** - All tests pass
- [ ] **Perform manual testing** - Test all user scenarios
- [ ] **Validate performance** - Ensure no regression
- [ ] **Update deployment scripts** - New configurations
- [ ] **Train team** - Document changes and best practices

---

## PRIORITY MATRIX

| Priority | Issue | Impact | Effort | Status |
|----------|-------|--------|--------|--------|
| **CRITICAL** | Hardcoded API key in agent-stack.json | Security breach | Low | ✗ NOT FIXED |
| **CRITICAL** | Monolithic 197-line function | Maintenance nightmare | High | ✗ NOT STARTED |
| **HIGH** | Unbounded audio buffer growth | Memory exhaustion | Medium | ✗ NOT STARTED |
| **HIGH** | Missing input validation | Stability issues | Medium | ✗ NOT STARTED |
| **HIGH** | Concurrency race conditions | Data corruption | High | ✗ NOT STARTED |
| **MEDIUM** | Dead code (_replay_run function) | Code bloat | Low | ✗ NOT ADDRESSED |
| **MEDIUM** | Duplicate API server (hermes_api_server.py) | Confusion/maintenance | Low | ✗ NOT ADDRESSED |

---

## NEXT STEPS

1. **Immediate**: Fix hardcoded secrets in `agent-stack.json`
2. **Week 1**: Begin modular refactoring of monolithic function
3. **Week 2**: Implement audio buffer size limits and validation
4. **Week 3**: Add concurrency safety and testing infrastructure
5. **Week 4**: Clean up dead code and add monitoring

**This implementation guide provides a clear roadmap for addressing the most critical issues in the Talaria codebase, moving from the highest-priority security fixes to architectural improvements and long-term maintainability.**