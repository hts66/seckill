"""Observability for the customer-service agent.

Two layers that compose:
1. A custom LangChain callback handler that records per-request LLM latency,
   token usage, and every tool call (name / duration / ok) as one structured
   JSON log line — always on, no external service required.
2. Optional LangSmith tracing: when enabled in settings we export the
   LANGCHAIN_* env vars so LangChain/LangGraph auto-trace the same run.
"""

from __future__ import annotations

import json
import logging
import os
import time
from typing import Any
from uuid import UUID

from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.outputs import LLMResult

from .config import Settings

logger = logging.getLogger("ai.obs")


def setup_observability(settings: Settings) -> None:
    logging.basicConfig(
        level=getattr(logging, settings.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    if settings.langchain_tracing_v2 and settings.langchain_api_key:
        os.environ["LANGCHAIN_TRACING_V2"] = "true"
        os.environ["LANGCHAIN_API_KEY"] = settings.langchain_api_key
        os.environ["LANGCHAIN_PROJECT"] = settings.langchain_project
        os.environ["LANGCHAIN_ENDPOINT"] = settings.langchain_endpoint
        logger.info("LangSmith tracing enabled: project=%s", settings.langchain_project)


def _extract_tokens(response: LLMResult) -> dict[str, int]:
    out = response.llm_output or {}
    usage = out.get("token_usage") or out.get("usage") or {}
    prompt = usage.get("prompt_tokens", 0)
    completion = usage.get("completion_tokens", 0)
    total = usage.get("total_tokens", 0)
    if not total:  # streamed responses expose counts on the message instead
        try:
            meta = getattr(response.generations[0][0].message, "usage_metadata", None) or {}
            prompt = meta.get("input_tokens", prompt)
            completion = meta.get("output_tokens", completion)
            total = meta.get("total_tokens", total)
        except (AttributeError, IndexError):
            pass
    return {"prompt": prompt or 0, "completion": completion or 0, "total": total or 0}


class MetricsCallbackHandler(BaseCallbackHandler):
    """Aggregates one request's LLM + tool activity into a log record."""

    def __init__(self) -> None:
        self.llm_calls = 0
        self.llm_ms = 0.0
        self.tokens = {"prompt": 0, "completion": 0, "total": 0}
        self.tool_calls: list[dict[str, Any]] = []
        self._llm_start: dict[UUID, float] = {}
        self._tool_start: dict[UUID, tuple[str, float]] = {}

    # --- LLM ---
    def on_chat_model_start(self, serialized, messages, *, run_id: UUID, **kwargs: Any) -> None:
        self._llm_start[run_id] = time.perf_counter()

    def on_llm_start(self, serialized, prompts, *, run_id: UUID, **kwargs: Any) -> None:
        self._llm_start[run_id] = time.perf_counter()

    def on_llm_end(self, response: LLMResult, *, run_id: UUID, **kwargs: Any) -> None:
        start = self._llm_start.pop(run_id, None)
        if start is not None:
            self.llm_ms += (time.perf_counter() - start) * 1000
        self.llm_calls += 1
        for key, value in _extract_tokens(response).items():
            self.tokens[key] += value

    # --- Tools ---
    def on_tool_start(self, serialized, input_str, *, run_id: UUID, **kwargs: Any) -> None:
        name = (serialized or {}).get("name") or kwargs.get("name") or "tool"
        self._tool_start[run_id] = (name, time.perf_counter())

    def on_tool_end(self, output, *, run_id: UUID, **kwargs: Any) -> None:
        self._finish_tool(run_id, ok=True)

    def on_tool_error(self, error, *, run_id: UUID, **kwargs: Any) -> None:
        self._finish_tool(run_id, ok=False, error=str(error))

    def _finish_tool(self, run_id: UUID, *, ok: bool, error: str | None = None) -> None:
        name, start = self._tool_start.pop(run_id, ("tool", time.perf_counter()))
        record = {"name": name, "ms": round((time.perf_counter() - start) * 1000, 1), "ok": ok}
        if error:
            record["error"] = error[:200]
        self.tool_calls.append(record)

    def summary(self) -> dict[str, Any]:
        return {
            "llmCalls": self.llm_calls,
            "llmMs": round(self.llm_ms, 1),
            "tokens": self.tokens,
            "toolCalls": self.tool_calls,
        }


def log_request(
    *, conversation_id: str, user_id: int, mode: str, handler: MetricsCallbackHandler,
    total_ms: float, ok: bool = True, error: str | None = None,
) -> None:
    record = {
        "event": "cs_request",
        "mode": mode,
        "conversationId": conversation_id,
        "userId": user_id,
        "ok": ok,
        "totalMs": round(total_ms, 1),
        **handler.summary(),
    }
    if error:
        record["error"] = error[:300]
    logger.info(json.dumps(record, ensure_ascii=False))
