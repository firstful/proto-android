"""
Hermes API client with type safety and error handling.

This module provides a comprehensive client for interacting with the
Hermes API server including run management, event streaming,
session management, and profile support.
"""

import asyncio
import httpx
import json
from typing import Optional, Dict, Any, List, AsyncGenerator
from dataclasses import dataclass, field
import logging

logger = logging.getLogger(__name__)


@dataclass
class HermesConfig:
    """Configuration for Hermes API client."""
    base_url: str = "http://host.containers.internal:8643"
    api_key: str = ""
    timeout: float = 120.0
    max_retries: int = 3
    retry_delay: float = 1.0
    profiles: Dict[str, str] = field(default_factory=dict)
    profile_instructions: Dict[str, str] = field(default_factory=dict)

    @classmethod
    def from_env(cls) -> 'HermesConfig':
        """Create configuration from environment variables."""
        import os

        profiles: Dict[str, str] = {}
        profile_instructions: Dict[str, str] = {}

        profiles_raw = os.getenv("HERMES_PROFILES", "")
        if profiles_raw:
            for pair in profiles_raw.split(","):
                if "=" in pair:
                    name, url = pair.split("=", 1)
                    profiles[name.strip()] = url.strip()

        instructions_raw = os.getenv("HERMES_PROFILE_INSTRUCTIONS", "")
        if instructions_raw:
            for pair in instructions_raw.split(","):
                if "=" in pair:
                    name, instr = pair.split("=", 1)
                    profile_instructions[name.strip()] = instr.strip()

        return cls(
            base_url=os.getenv("HERMES_API_URL", "http://host.containers.internal:8643"),
            api_key=os.getenv("HERMES_API_KEY", ""),
            timeout=float(os.getenv("HERMES_TIMEOUT", "120.0")),
            profiles=profiles,
            profile_instructions=profile_instructions,
        )


class HermesClient:
    """Client for Hermes API Server — runs, sessions, and profiles."""

    def __init__(self, config: Optional["HermesConfig"] = None) -> None:
        self.config = config or HermesConfig.from_env()
        self.client: Optional[httpx.AsyncClient] = None
        self._init_lock = asyncio.Lock()

    async def _ensure_client(self) -> None:
        """Initialize HTTP client if not already created."""
        if self.client is not None:
            return

        async with self._init_lock:
            if self.client is not None:
                return

            headers = {"Content-Type": "application/json"}
            if self.config.api_key:
                headers["Authorization"] = f"Bearer {self.config.api_key}"

            self.client = httpx.AsyncClient(
                timeout=self.config.timeout,
                headers=headers,
            )

    def _base_url_for_profile(self, profile: Optional[str] = None) -> str:
        """Get the base URL for a given profile."""
        if profile and profile in self.config.profiles:
            return self.config.profiles[profile]
        return self.config.base_url

    async def start_run(
        self,
        text: str,
        session_id: Optional[str] = None,
        thread_id: Optional[str] = None,
        profile: Optional[str] = None,
    ) -> Dict[str, Any]:
        """POST /v1/runs — start a run, returns {run_id, session_id, status}."""
        await self._ensure_client()

        base_url = self._base_url_for_profile(profile)
        payload: Dict[str, Any] = {
            "input": text,
            "stream": True,
        }
        if session_id:
            payload["session_id"] = session_id
        if thread_id:
            payload["thread_id"] = thread_id

        instructions = self.config.profile_instructions.get(profile or "", "")
        if instructions:
            payload["instructions"] = instructions

        logger.info("Hermes POST /v1/runs (profile=%s): %s", profile, text[:100])

        for attempt in range(self.config.max_retries):
            try:
                resp = await self.client.post(
                    f"{base_url}/v1/runs",
                    json=payload,
                )
                resp.raise_for_status()
                result = resp.json()
                logger.info("Run started: %s", result.get("run_id"))
                return result
            except httpx.HTTPStatusError as e:
                logger.warning("Hermes API error (attempt %d): %s", attempt + 1, e)
                if attempt == self.config.max_retries - 1:
                    raise
                await asyncio.sleep(self.config.retry_delay * (attempt + 1))
            except Exception as e:
                logger.error("Hermes start_run failed: %s", e)
                if attempt == self.config.max_retries - 1:
                    raise
                await asyncio.sleep(self.config.retry_delay * (attempt + 1))

        raise RuntimeError("Failed to start Hermes run after retries")

    async def stream_events(
        self,
        run_id: str,
        cancel_event: Optional[asyncio.Event] = None,
        profile: Optional[str] = None,
    ) -> str:
        """GET /v1/runs/{run_id}/events — SSE stream. Returns accumulated reply text."""
        await self._ensure_client()

        base_url = self._base_url_for_profile(profile)
        reply_text = ""

        async with self.client.stream(
            "GET",
            f"{base_url}/v1/runs/{run_id}/events",
        ) as event_stream:
            async for line in event_stream.aiter_lines():
                if cancel_event and cancel_event.is_set():
                    break

                if not line.startswith("data: "):
                    continue

                try:
                    data = json.loads(line[6:])
                except json.JSONDecodeError:
                    continue

                ev = data.get("event", "")
                if ev == "message.delta":
                    reply_text += data.get("delta", "")
                elif ev in ("run.completed", "done"):
                    break
                elif ev == "error":
                    raise RuntimeError(f"Hermes error: {data.get('message', '')}")

                ev_type = data.get("type", "")
                if ev_type in ("message", "delta"):
                    content = data.get("content", {})
                    if content.get("type") == "text":
                        reply_text += content.get("text", "")

        return reply_text

    async def stop_run(self, run_id: str, profile: Optional[str] = None) -> bool:
        """POST /v1/runs/{run_id}/stop — interrupt a running agent."""
        await self._ensure_client()

        base_url = self._base_url_for_profile(profile)
        try:
            logger.info("Hermes POST stop: %s", run_id)
            resp = await self.client.post(f"{base_url}/v1/runs/{run_id}/stop")
            resp.raise_for_status()
            return True
        except Exception as e:
            logger.warning("Stop run failed (non-fatal): %s", e)
            return False

    # ── Session management ───────────────────────────────────

    async def list_sessions(
        self,
        limit: int = 20,
        offset: int = 0,
        source: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """GET /api/sessions — list recent sessions."""
        await self._ensure_client()

        params: Dict[str, Any] = {"limit": limit, "offset": offset}
        if source:
            params["source"] = source

        try:
            resp = await self.client.get(
                f"{self.config.base_url}/api/sessions",
                params=params,
            )
            resp.raise_for_status()
            data = resp.json()
            return data if isinstance(data, list) else data.get("data", data.get("sessions", []))
        except Exception as e:
            logger.warning("list_sessions failed: %s", e)
            return []

    async def get_session(self, session_id: str) -> Optional[Dict[str, Any]]:
        """GET /api/sessions/{id} — get session metadata."""
        await self._ensure_client()

        try:
            resp = await self.client.get(
                f"{self.config.base_url}/api/sessions/{session_id}",
            )
            resp.raise_for_status()
            return resp.json()
        except Exception as e:
            logger.warning("get_session failed: %s", e)
            return None

    async def get_session_messages(
        self,
        session_id: str,
        limit: int = 50,
    ) -> List[Dict[str, Any]]:
        """GET /api/sessions/{id}/messages — get message history."""
        await self._ensure_client()

        try:
            resp = await self.client.get(
                f"{self.config.base_url}/api/sessions/{session_id}/messages",
                params={"limit": limit},
            )
            resp.raise_for_status()
            data = resp.json()
            return data if isinstance(data, list) else data.get("messages", [])
        except Exception as e:
            logger.warning("get_session_messages failed: %s", e)
            return []

    async def fork_session(self, session_id: str) -> Optional[Dict[str, Any]]:
        """POST /api/sessions/{id}/fork — branch a session, returns new session metadata."""
        await self._ensure_client()

        try:
            resp = await self.client.post(
                f"{self.config.base_url}/api/sessions/{session_id}/fork",
            )
            resp.raise_for_status()
            return resp.json()
        except Exception as e:
            logger.warning("fork_session failed: %s", e)
            return None

    async def delete_session(self, session_id: str) -> bool:
        """DELETE /api/sessions/{id} — delete a session."""
        await self._ensure_client()

        try:
            resp = await self.client.delete(
                f"{self.config.base_url}/api/sessions/{session_id}",
            )
            resp.raise_for_status()
            return True
        except Exception as e:
            logger.warning("delete_session failed: %s", e)
            return False

    # ── Health / capabilities ────────────────────────────────

    async def health_check(self) -> bool:
        """Check Hermes API health."""
        await self._ensure_client()

        try:
            resp = await self.client.get(f"{self.config.base_url}/health")
            return resp.status_code == 200
        except Exception:
            return False

    async def get_capabilities(self) -> Dict[str, Any]:
        """GET /v1/capabilities — discover available features."""
        await self._ensure_client()

        try:
            resp = await self.client.get(f"{self.config.base_url}/v1/capabilities")
            resp.raise_for_status()
            return resp.json()
        except Exception as e:
            logger.warning("get_capabilities failed: %s", e)
            return {}

    async def list_models(self) -> List[Dict[str, Any]]:
        """GET /v1/models — list available agent profiles."""
        await self._ensure_client()

        try:
            resp = await self.client.get(f"{self.config.base_url}/v1/models")
            resp.raise_for_status()
            data = resp.json()
            return data if isinstance(data, list) else data.get("data", [])
        except Exception as e:
            logger.warning("list_models failed: %s", e)
            return []

    # ── Lifecycle ────────────────────────────────────────────

    async def close(self) -> None:
        """Close the HTTP client."""
        if self.client:
            await self.client.aclose()
            self.client = None

    async def __aenter__(self) -> 'HermesClient':
        await self._ensure_client()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        await self.close()
