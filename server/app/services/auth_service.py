"""Authentication service: register / login / google / refresh / logout."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.security import (
    create_access_token,
    hash_password,
    hash_refresh_token,
    new_refresh_token,
    verify_password,
)
from app.models import Device, RefreshToken, User
from app.schemas.auth import DeviceInfoIn, TokensOut, UserOut


def _issue_tokens(db: Session, user: User, device: Device) -> TokensOut:
    settings = get_settings()
    access = create_access_token(user.id, device.id)
    opaque, token_hash = new_refresh_token()
    rt = RefreshToken(
        user_id=user.id,
        device_id=device.id,
        token_hash=token_hash,
        expires_at=datetime.now(UTC) + timedelta(days=settings.refresh_token_days),
    )
    db.add(rt)
    device.last_seen_at = datetime.now(UTC)
    db.commit()
    return TokensOut(
        access_token=access,
        refresh_token=opaque,
        expires_in=settings.access_token_minutes * 60,
        user=UserOut.model_validate(user),
        device=_device_out(db, device.id),
    )


def _device_out(db: Session, device_id: str):
    device = db.get(Device, device_id)
    from app.schemas.auth import DeviceOut

    return DeviceOut.model_validate(device)


def _get_or_create_device(db: Session, user: User, info: DeviceInfoIn, device_id: str | None) -> Device:
    if device_id:
        device = db.get(Device, device_id)
        if device and device.user_id == user.id and device.revoked_at is None:
            return device
    device = Device(user_id=user.id, name=info.device_name, platform=info.platform, app_version=info.app_version)
    db.add(device)
    db.flush()
    return device


def register(db: Session, email: str, password: str, display_name: str, info: DeviceInfoIn) -> TokensOut:
    email = email.strip().lower()
    existing = db.scalar(select(User).where(User.email == email))
    if existing:
        raise ApiError(409, "email_exists", "An account with this email already exists.")
    user = User(
        email=email,
        password_hash=hash_password(password),
        display_name=display_name or email.split("@")[0],
    )
    db.add(user)
    try:
        db.flush()
    except IntegrityError as exc:
        db.rollback()  # lost a concurrent-registration race → clean 409, not 500
        raise ApiError(409, "email_exists", "An account with this email already exists.") from exc
    device = _get_or_create_device(db, user, info, None)
    db.commit()
    db.refresh(user)
    return _issue_tokens(db, user, device)


def login(db: Session, email: str, password: str, info: DeviceInfoIn, device_id: str | None = None) -> TokensOut:
    email = email.strip().lower()
    user = db.scalar(select(User).where(User.email == email))
    if not user or not user.is_active or not verify_password(user.password_hash, password):
        raise ApiError(401, "invalid_credentials", "Incorrect email or password.")
    device = _get_or_create_device(db, user, info, device_id)
    db.commit()
    return _issue_tokens(db, user, device)


def google_login(db: Session, id_token: str, info: DeviceInfoIn, device_id: str | None = None) -> TokensOut:
    claims = _verify_google_id_token(id_token)
    email = (claims.get("email") or "").strip().lower()
    if not email or not claims.get("email_verified", False):
        raise ApiError(401, "google_email_unverified", "Google account email is not verified.")
    sub = claims["sub"]
    user = db.scalar(select(User).where(User.email == email))
    if not user:
        user = User(email=email, display_name=claims.get("name") or email.split("@")[0])
        db.add(user)
        try:
            db.flush()
        except IntegrityError:  # concurrent first-login with the same Google email
            db.rollback()
            user = db.scalar(select(User).where(User.email == email))
            if user is None:
                raise ApiError(409, "email_exists", "An account with this email already exists.") from None
    elif not user.is_active:
        raise ApiError(403, "account_disabled", "This account is disabled.")

    from app.models import OAuthIdentity

    identity = db.scalar(
        select(OAuthIdentity).where(
            OAuthIdentity.provider == "google", OAuthIdentity.provider_account_id == sub
        )
    )
    if not identity:
        db.add(OAuthIdentity(user_id=user.id, provider="google", provider_account_id=sub))
    device = _get_or_create_device(db, user, info, device_id)
    db.commit()
    return _issue_tokens(db, user, device)


def _verify_google_id_token(id_token: str) -> dict:
    """Verify signature against Google JWKS (TRD §5)."""
    settings = get_settings()
    if not settings.google_client_id:
        raise ApiError(
            501, "google_not_configured",
            "Google sign-in is not configured on this server (set GOOGLE_CLIENT_ID).",
        )
    import jwt
    from jwt import PyJWKClient

    jwks = PyJWKClient("https://www.googleapis.com/oauth2/v3/certs")
    try:
        signing_key = jwks.get_signing_key_from_jwt(id_token)
        return jwt.decode(
            id_token,
            signing_key.key,
            algorithms=["RS256"],
            audience=settings.google_client_id,
            options={"require": ["exp", "sub"]},
        )
    except jwt.PyJWTError as exc:
        raise ApiError(401, "invalid_google_token", f"Invalid Google ID token: {exc}") from exc


def rotate_refresh(db: Session, opaque_token: str) -> TokensOut:
    """Rotate: old token revoked, new pair issued. Reuse detection revokes the device family."""
    token_hash = hash_refresh_token(opaque_token)
    rt = db.scalar(select(RefreshToken).where(RefreshToken.token_hash == token_hash))
    if not rt:
        raise ApiError(401, "invalid_refresh_token", "Refresh token not recognised.")
    now = datetime.now(UTC)
    if rt.revoked_at is not None:
        _revoke_device_tokens(db, rt.device_id)  # reuse → revoke whole family
        raise ApiError(401, "refresh_token_reused", "Refresh token was already used; sign in again.")
    if rt.expires_at < now:
        raise ApiError(401, "refresh_token_expired", "Refresh token expired; sign in again.")

    device = db.get(Device, rt.device_id)
    if device is None or device.revoked_at is not None:
        raise ApiError(401, "device_revoked", "This device has been removed; sign in again.")

    user = db.get(User, rt.user_id)
    if user is None or not user.is_active:
        raise ApiError(403, "account_disabled", "This account is disabled.")

    # Atomic claim: exactly ONE concurrent refresh may flip revoked_at NULL→now.
    claimed = db.execute(
        update(RefreshToken)
        .where(RefreshToken.id == rt.id, RefreshToken.revoked_at.is_(None))
        .values(revoked_at=now)
    )
    if claimed.rowcount != 1:
        db.rollback()
        _revoke_device_tokens(db, rt.device_id)  # concurrent reuse detected
        raise ApiError(401, "refresh_token_reused", "Refresh token was already used; sign in again.")
    db.commit()
    return _issue_tokens(db, user, device)


def _revoke_device_tokens(db: Session, device_id: str) -> None:
    now = datetime.now(UTC)
    for rt in db.scalars(
        select(RefreshToken).where(RefreshToken.device_id == device_id, RefreshToken.revoked_at.is_(None))
    ):
        rt.revoked_at = now
    device = db.get(Device, device_id)
    if device:
        device.revoked_at = now
    db.commit()


def logout(db: Session, opaque_token: str | None, current_device_id: str) -> None:
    if opaque_token:
        rt = db.scalar(select(RefreshToken).where(RefreshToken.token_hash == hash_refresh_token(opaque_token)))
        if rt:
            rt.revoked_at = datetime.now(UTC)
    _revoke_device_tokens(db, current_device_id)


def revoke_device(db: Session, user_id: str, device_id: str) -> None:
    device = db.get(Device, device_id)
    if not device or device.user_id != user_id:
        raise ApiError(404, "not_found", "Device not found.")
    _revoke_device_tokens(db, device_id)
