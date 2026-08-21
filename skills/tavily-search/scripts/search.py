#!/usr/bin/env python3
"""Search the web through Tavily and emit compact JSON."""

from __future__ import annotations

import argparse
import sys
from typing import Any

from tavily_common import TavilyError, fail, post_json, print_json


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Search the web with Tavily")
    parser.add_argument("query", help="Search query")
    parser.add_argument("--max-results", type=int, default=5, choices=range(1, 21))
    parser.add_argument("--depth", choices=("basic", "advanced"), default="basic")
    parser.add_argument("--topic", choices=("general", "news", "finance"), default="general")
    parser.add_argument("--include-images", action="store_true")
    parser.add_argument("--include-answer", action="store_true")
    parser.add_argument("--raw-content", choices=("markdown", "text"))
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()
    if not 1.0 <= args.timeout <= 60.0:
        parser.error("--timeout must be between 1 and 60 seconds")
    return args


def compact_result(result: dict[str, Any], raw_content: str | None) -> dict[str, Any]:
    compact = {
        "title": result.get("title"),
        "url": result.get("url"),
        "content": result.get("content"),
        "score": result.get("score"),
    }
    if raw_content:
        compact["raw_content"] = result.get("raw_content")
    return {key: value for key, value in compact.items() if value is not None}


def main() -> int:
    args = parse_args()
    payload: dict[str, Any] = {
        "query": args.query,
        "max_results": args.max_results,
        "search_depth": args.depth,
        "topic": args.topic,
        "include_images": args.include_images,
        "include_answer": args.include_answer,
    }
    if args.raw_content:
        payload["include_raw_content"] = args.raw_content

    try:
        response = post_json("/search", payload, args.timeout)
    except TavilyError as exc:
        return fail(exc)

    output = {
        "query": response.get("query", args.query),
        "answer": response.get("answer"),
        "results": [
            compact_result(result, args.raw_content)
            for result in response.get("results", [])
            if isinstance(result, dict)
        ],
        "images": response.get("images", []) if args.include_images else [],
        "response_time": response.get("response_time"),
        "request_id": response.get("request_id"),
    }
    print_json({key: value for key, value in output.items() if value not in (None, [], "")})
    return 0


if __name__ == "__main__":
    sys.exit(main())
