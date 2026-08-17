#!/usr/bin/env python3

from __future__ import annotations

import subprocess
import sys
from collections import defaultdict


SQLITE_MAGIC = b"SQLite format 3\x00"
SQLITE_SUFFIXES = (".db", ".db-shm", ".db-wal", ".sqlite", ".sqlite3")


def reachable_objects() -> tuple[list[str], dict[str, list[str]]]:
    output = subprocess.check_output(
        ["git", "rev-list", "--objects", "--all"],
        text=True,
        encoding="utf-8",
        errors="surrogateescape",
    )
    object_ids: list[str] = []
    paths: dict[str, list[str]] = defaultdict(list)
    seen: set[str] = set()

    for line in output.splitlines():
        object_id, separator, path = line.partition(" ")
        if object_id not in seen:
            seen.add(object_id)
            object_ids.append(object_id)
        if separator and path not in paths[object_id]:
            paths[object_id].append(path)

    return object_ids, paths


def read_batch_object(
    stream: subprocess.Popen[bytes], object_id: str
) -> tuple[str, bytes]:
    assert stream.stdin is not None
    assert stream.stdout is not None
    stream.stdin.write(f"{object_id}\n".encode("ascii"))
    stream.stdin.flush()

    header = stream.stdout.readline().decode("ascii").rstrip("\n")
    fields = header.split(" ")
    if len(fields) != 3:
        raise RuntimeError(f"unexpected git cat-file header: {header!r}")

    returned_id, object_type, size_text = fields
    if returned_id != object_id:
        raise RuntimeError(f"git cat-file returned {returned_id} for {object_id}")

    size = int(size_text)
    content = stream.stdout.read(size)
    if len(content) != size or stream.stdout.read(1) != b"\n":
        raise RuntimeError(f"truncated git object: {object_id}")
    return object_type, content


def main() -> int:
    object_ids, object_paths = reachable_objects()
    findings: set[tuple[str, str]] = set()
    batch = subprocess.Popen(
        ["git", "cat-file", "--batch"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
    )

    try:
        for object_id in object_ids:
            object_type, content = read_batch_object(batch, object_id)
            if object_type != "blob":
                continue

            paths = object_paths.get(object_id, [])
            for path in paths:
                if path.lower().endswith(SQLITE_SUFFIXES):
                    findings.add((object_id, path))

            if content.startswith(SQLITE_MAGIC):
                if paths:
                    findings.update((object_id, path) for path in paths)
                else:
                    findings.add((object_id, "<unknown-path>"))
    finally:
        if batch.stdin is not None:
            batch.stdin.close()
        batch.wait()

    if batch.returncode != 0:
        raise RuntimeError(f"git cat-file failed with exit code {batch.returncode}")

    if findings:
        for object_id, path in sorted(findings):
            print(
                f"SQLite file in reachable git history: {object_id} {path}",
                file=sys.stderr,
            )
        return 1

    print("no SQLite files found in reachable git history")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
