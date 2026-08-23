"""Security primitives: argon2id password hashing, JWT access tokens,
opaque rotating refresh tokens (sha256-stored), HMAC-signed media URLs."""

from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import uuid
from datetime import UTC, datetime, timedelta

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError

from app.core.config import get_settings

_hasher = PasswordHasher(memory_cost=64 * 1024, time_cost=3, parallelism=4)  # argon2id, TRD §5


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(password_hash: str | None, password: str) -> bool:
    if not password_hash:
        return False
    try:
        return _hasher.verify(password_hash, password)
    except VerifyMismatchError:
        return False
    except Exception:  # malformed hash etc.
        return False


# --- JWT access tokens -------------------------------------------------------

def create_access_token(user_id: str, device_id: str, minutes: int | None = None) -> str:
    settings = get_settings()
    now = datetime.now(UTC)
    payload = {
        "sub": user_id,
        "did": device_id,
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(minutes=minutes or settings.access_token_minutes)).timestamp()),
        "jti": uuid.uuid4().hex,
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def decode_access_token(token: str) -> dict:
    """Raises jwt.PyJWTError on invalid/expired tokens."""
    settings = get_settings()
    return jwt.decode(token, settings.jwt_secret, algorithms=[settings.jwt_algorithm])


# --- Refresh tokens ----------------------------------------------------------

def new_refresh_token() -> tuple[str, str]:
    """Returns (opaque_token, sha256_hex_to_store)."""
    token = secrets.token_urlsafe(32)  # 256-bit
    return token, hashlib.sha256(token.encode()).hexdigest()


def hash_refresh_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


# --- Signed local media URLs (local storage backend has no S3 presign) -------

def sign_media_path(sha256: str, expires_at_ts: int) -> str:
    settings = get_settings()
    msg = f"{sha256}:{expires_at_ts}".encode()
    sig = hmac.new(settings.jwt_secret.encode(), msg, hashlib.sha256).digest()
    return base64.urlsafe_b64encode(sig).decode().rstrip("=")


def verify_media_signature(sha256: str, expires_at_ts: int, signature: str) -> bool:
    if expires_at_ts < int(datetime.now(UTC).timestamp()):
        return False
    expected = sign_media_path(sha256, expires_at_ts)
    return hmac.compare_digest(expected, signature)


def signed_media_url(path_prefix: str, sha256: str, filename: str) -> tuple[str, int]:
    """Build a short-lived URL for the local storage backend."""
    from urllib.parse import quote

    settings = get_settings()
    exp = int((datetime.now(UTC) + timedelta(seconds=settings.presign_expiry_seconds)).timestamp())
    sig = sign_media_path(sha256, exp)
    quoted = quote(filename, safe="")
    return (
        f"{settings.public_base_url}/api/v1/storage/local/{path_prefix}/{sha256}/{quoted}"
        f"?expires={exp}&signature={sig}",
        settings.presign_expiry_seconds,
    )
