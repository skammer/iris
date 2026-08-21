#!/usr/bin/env python3
"""Extract clean Markdown or text from URLs through Tavily."""

from __future__ import annotations

import argparse
import sys
from typing import Any

from tavily_common import TavilyError, fail, post_json, print_json


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Extract URL content with Tavily")
    parser.add_argument("--url", action="append", required=True, dest="urls")
    parser.add_argument("--query", help="Rerank extracted chunks for this intent")
    parser.add_argument("--chunks-per-source", type=int, choices=range(1, 6))
    parser.add_argument("--depth", choices=("basic", "advanced"), default="basic")
    parser.add_argument("--format", choices=("markdown", "text"), default="markdown")
    parser.add_argument("--include-images", action="store_true")
    parser.add_argument("--max-chars", type=int, default=7000)
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()
    if len(args.urls) > 20:
        parser.error("Tavily accepts at most 20 URLs per extract request")
    if args.chunks_per_source is not None and not args.query:
        parser.error("--chunks-per-source requires --query")
    if args.max_chars < 0:
        parser.error("--max-chars must be zero or positive")
    if not 1.0 <= args.timeout <= 60.0:
        parser.error("--timeout must be between 1 and 60 seconds")
    return args


def normalize_result(result: dict[str, Any], max_chars: int) -> dict[str, Any]:
    content = result.get("raw_content")
    if content is None:
        content = result.get("content", "")
    if not isinstance(content, str):
        content = str(content)
    original_chars = len(content)
    truncated = max_chars > 0 and original_chars > max_chars
    if truncated:
        content = content[:max_chars]

    normalized = {
        "url": result.get("url"),
        "title": result.get("title"),
        "content": content,
        "chars": original_chars,
        "returned_chars": len(content),
        "truncated": truncated,
        "images": result.get("images"),
        "favicon": result.get("favicon"),
    }
    return {key: value for key, value in normalized.items() if value not in (None, [], "")}


def main() -> int:
    args = parse_args()
    payload: dict[str, Any] = {
        "urls": args.urls,
        "extract_depth": args.depth,
        "format": args.format,
        "include_images": args.include_images,
        "timeout": args.timeout,
    }
    if args.query:
        payload["query"] = args.query
        payload["chunks_per_source"] = args.chunks_per_source or 3

    try:
        response = post_json("/extract", payload, args.timeout + 5.0)
    except TavilyError as exc:
        return fail(exc)

    output = {
        "results": [
            normalize_result(result, args.max_chars)
            for result in response.get("results", [])
            if isinstance(result, dict)
        ],
        "failed_results": response.get("failed_results", []),
        "response_time": response.get("response_time"),
        "request_id": response.get("request_id"),
        "usage": response.get("usage"),
    }
    print_json({key: value for key, value in output.items() if value not in (None, [], "")})
    return 0


if __name__ == "__main__":
    sys.exit(main())
