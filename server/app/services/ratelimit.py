"""Minimal in-process sliding-window rate limiter (TRD §5).

login: 10/min/IP · uploads: 30/h/user. Single-process only — acceptable for
the self-host single-container deployment this project targets.
"""

from __future__ import annotations

import time
from collections import defaultdict, deque

from app.core.errors import ApiError

_buckets: dict[str, deque[float]] = defaultdict(deque)


def check_rate(bucket: str, key: str, limit: int, window_seconds: int) -> None:
    now = time.monotonic()
    ident = f"{bucket}:{key}"
    q = _buckets[ident]
    while q and q[0] <= now - window_seconds:
        q.popleft()
    if len(q) >= limit:
        raise ApiError(429, "rate_limited", "Too many requests. Try again later.")
    q.append(now)


def reset_rate_limiter() -> None:
    _buckets.clear()
