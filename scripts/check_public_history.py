#!/usr/bin/env python3

from __future__ import annotations

import subprocess
from pathlib import PurePosixPath

from check_no_sqlite_history import read_batch_object, reachable_objects


FORBIDDEN_MARKERS = (
    (b"agent.tailscale", "private hostname"),
    (b"100.81.169.122", "private IP"),
    (b"/Users/skammer", "private macOS path"),
    (b"/home/skammer", "private Linux path"),
    (b"api.neuraldeep.ru", "private provider hostname"),
    (b"git.anton.tail.staging.cat", "private Git hostname"),
    (b"Europe/Moscow", "personal timezone"),
    ("Макс".encode(), "personal fixture name"),
    (b".svc.cluster.local", "private cluster hostname"),
    (b"default-secret-change-in-production", "predictable signing secret"),
    (b"admin-secret-key", "example API secret"),
    (b"jwt-secret-key", "example JWT secret"),
    (b"postgres:password", "embedded database password"),
    (b"POSTGRES_PASSWORD=password", "database password"),
    (b"GF_SECURITY_ADMIN_PASSWORD=admin", "Grafana password"),
)
EXCLUDED_PATHS = {
    PurePosixPath("scripts/check-public-tree.sh"),
    PurePosixPath("scripts/check_public_history.py"),
}


def main() -> int:
    object_ids, object_paths = reachable_objects()
    findings: set[tuple[str, str, str]] = set()
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

            paths = [
                path
                for path in object_paths.get(object_id, [])
                if PurePosixPath(path) not in EXCLUDED_PATHS
            ]
            if not paths:
                continue

            for marker, description in FORBIDDEN_MARKERS:
                if marker in content:
                    findings.update((object_id, path, description) for path in paths)
    finally:
        if batch.stdin is not None:
            batch.stdin.close()
        batch.wait()

    if batch.returncode != 0:
        raise RuntimeError(f"git cat-file failed with exit code {batch.returncode}")

    if findings:
        for object_id, path, description in sorted(findings):
            print(f"{description} in git history: {object_id} {path}")
        return 1

    print("public-release metadata history check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
