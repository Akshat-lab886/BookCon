"""Minimal in-process sliding-window rate limiter (TRD §5).

login: 10/min/IP + 10/min/account · uploads: 30/h/user. Single-process only —
acceptable for the self-host single-container deployment this project targets.
"""

from __future__ import annotations

import hmac
import time
from collections import defaultdict, deque

from app.core.errors import ApiError

_buckets: dict[str, deque[float]] = defaultdict(deque)


def check_rate(bucket: str, key: str, limit: int, window_seconds: int) -> None:
    """Count one request against `bucket:key`, raising 429 when over the limit.

    The key is compared by identity only, so callers may pass a hashed value: a
    raw email address must not end up sitting in this dict (and in a memory dump,
    or a log of a traced exception) just because someone was rate limited.
    """
    now = time.monotonic()
    ident = f"{bucket}:{key}"
    q = _buckets[ident]
    while q and q[0] <= now - window_seconds:
        q.popleft()
    if len(q) >= limit:
        raise ApiError(429, "rate_limited", "Too many requests. Try again later.")
    q.append(now)


def check_rate_pair(
    bucket_ip: str,
    ip_key: str | None,
    bucket_account: str,
    account_key: str,
    limit: int,
    window_seconds: int,
) -> None:
    """Enforce a per-account budget, and an IP budget only when the IP is an identity.

    The account half is the one that matters, and it is always applied. The IP half
    is conditional, and that condition is the whole reason this function exists.

    A deployment that fronts uvicorn with Caddy but does not forward
    `X-Forwarded-For` presents the *proxy's* address on every request. Keying a
    hard limit on that turns "10 logins a minute" into one global budget: the
    first few users of the morning exhaust it and everyone else is denied. The
    observed address is then not an identity at all, so the caller passes None
    (see `_is_routable_client`) and only the account budget applies. Distributed
    guessing against many accounts is still bounded by the per-account half, and
    the register/refresh endpoints keep their own IP limits for the cases where
    the address really is per-caller.

    A failure of *either* half rejects the request. The account budget is checked
    first so an email is hashed for a rate-limit key only after the IP budget is
    known to have room.
    """
    if ip_key:
        check_rate(bucket_ip, ip_key, limit, window_seconds)
    check_rate(bucket_account, account_key, limit, window_seconds)


def blind_index(value: str) -> str:
    """A stable, non-reversible tag for a value used as a rate-limit key."""
    import hashlib

    return hmac.new(b"ratelimit", value.strip().lower().encode(), hashlib.sha256).hexdigest()[:32]


def reset_rate_limiter() -> None:
    _buckets.clear()
