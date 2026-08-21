#!/usr/bin/env python3
"""Shared Tavily API helpers without third-party dependencies."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


API_BASE_URL = "https://api.tavily.com"
DEFAULT_KEY_FILE = Path("~/.config/iris/secrets/tavily-api-key").expanduser()


class TavilyError(RuntimeError):
    """Safe, user-facing Tavily request error."""


def api_key() -> str:
    value = os.environ.get("TAVILY_API_KEY", "").strip()
    if value:
        return value

    key_path = Path(
        os.environ.get("TAVILY_API_KEY_FILE", str(DEFAULT_KEY_FILE))
    ).expanduser()
    try:
        value = key_path.read_text(encoding="utf-8").strip()
    except FileNotFoundError as exc:
        raise TavilyError(
            f"Tavily API key missing: set TAVILY_API_KEY or create {key_path}"
        ) from exc
    except OSError as exc:
        raise TavilyError(f"Cannot read Tavily API key file {key_path}: {exc}") from exc

    if not value:
        raise TavilyError(f"Tavily API key file is empty: {key_path}")
    return value


def post_json(endpoint: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
    request = urllib.request.Request(
        f"{API_BASE_URL}{endpoint}",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key()}",
            "Content-Type": "application/json",
            "User-Agent": "iris-tavily-skill/1.0",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            data = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:1000]
        raise TavilyError(f"Tavily HTTP {exc.code}: {detail}") from exc
    except urllib.error.URLError as exc:
        raise TavilyError(f"Tavily request failed: {exc.reason}") from exc
    except TimeoutError as exc:
        raise TavilyError(f"Tavily request timed out after {timeout:g}s") from exc
    except json.JSONDecodeError as exc:
        raise TavilyError("Tavily returned invalid JSON") from exc

    if not isinstance(data, dict):
        raise TavilyError("Tavily returned a non-object JSON response")
    return data


def print_json(value: Any) -> None:
    json.dump(value, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")


def fail(exc: Exception) -> int:
    print_json({"error": type(exc).__name__, "detail": str(exc)})
    return 1
