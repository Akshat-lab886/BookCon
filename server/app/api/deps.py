"""Shared FastAPI dependencies: DB session, current user/device."""

from __future__ import annotations

from datetime import UTC, datetime

import jwt as pyjwt
from fastapi import Depends, Request
from sqlalchemy.orm import Session

from app.core.errors import ApiError
from app.core.security import decode_access_token
from app.db.session import get_db
from app.models import Device, User


def get_current_user(request: Request, db: Session = Depends(get_db)) -> User:
    auth = request.headers.get("Authorization", "")
    if not auth.lower().startswith("bearer "):
        raise ApiError(401, "unauthorized", "Missing bearer token.")
    token = auth.split(" ", 1)[1].strip()
    try:
        payload = decode_access_token(token)
    except pyjwt.ExpiredSignatureError:
        raise ApiError(401, "token_expired", "Access token expired; refresh.") from None
    except pyjwt.PyJWTError:
        raise ApiError(401, "unauthorized", "Invalid access token.") from None

    user = db.get(User, payload.get("sub", ""))
    if user is None or not user.is_active:
        raise ApiError(401, "unauthorized", "Unknown or disabled account.")
    # Fail CLOSED: tokens must carry a live, unrevoked device. A missing or
    # dangling `did` no longer bypasses the revocation check.
    device_id = payload.get("did")
    if not device_id:
        raise ApiError(401, "unauthorized", "Access token missing device binding.")
    device = db.get(Device, device_id)
    if device is None or device.user_id != user.id:
        raise ApiError(401, "unauthorized", "Unknown device.")
    if device.revoked_at is not None:
        raise ApiError(401, "device_revoked", "This device was removed.")
    request.state.user_id = user.id
    request.state.device_id = device_id
    return user


def get_device_id(request: Request) -> str | None:
    return getattr(request.state, "device_id", None)


def touch_device(db: Session, device_id: str | None) -> None:
    if device_id:
        device = db.get(Device, device_id)
        if device:
            device.last_seen_at = datetime.now(UTC)


__all__ = ["get_current_user", "get_db", "get_device_id", "touch_device"]
