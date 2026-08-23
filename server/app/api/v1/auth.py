"""Auth & account routes: /auth/*, /me, /devices."""

from __future__ import annotations

from fastapi import APIRouter, BackgroundTasks, Depends, Request
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db, get_device_id
from app.schemas.auth import (
    DeviceOut,
    GoogleAuthIn,
    LoginIn,
    LogoutIn,
    RefreshIn,
    RegisterIn,
    TokensOut,
    UserOut,
    UserPatchIn,
)
from app.services import auth_service

router = APIRouter(tags=["auth"])


@router.post("/auth/register", response_model=TokensOut, status_code=201)
def register(body: RegisterIn, db: Session = Depends(get_db)) -> TokensOut:
    return auth_service.register(db, body.email, body.password, body.display_name or "", body)


@router.post("/auth/login", response_model=TokensOut)
def login(
    body: LoginIn,
    request: Request,
    db: Session = Depends(get_db),
) -> TokensOut:
    client_ip = request.client.host if request.client else "unknown"
    from app.services.ratelimit import check_rate

    check_rate("login_ip", client_ip, limit=10, window_seconds=60)
    return auth_service.login(db, body.email, body.password, body, device_id=None)


@router.post("/auth/google", response_model=TokensOut)
def google_auth(body: GoogleAuthIn, db: Session = Depends(get_db)) -> TokensOut:
    return auth_service.google_login(db, body.id_token, body)


@router.post("/auth/refresh", response_model=TokensOut)
def refresh(body: RefreshIn, db: Session = Depends(get_db)) -> TokensOut:
    return auth_service.rotate_refresh(db, body.refresh_token)


@router.post("/auth/logout", status_code=204)
def logout(
    body: LogoutIn,
    db: Session = Depends(get_db),
    user=Depends(get_current_user),
    device_id: str | None = Depends(get_device_id),
) -> None:
    if not device_id:
        return None
    auth_service.logout(db, body.refresh_token, device_id)
    db.commit()
    return None


# --- Me -----------------------------------------------------------------------

me_router = APIRouter(tags=["me"])


@me_router.get("/me", response_model=UserOut)
def get_me(user=Depends(get_current_user)) -> UserOut:
    return UserOut.model_validate(user)


@me_router.patch("/me", response_model=UserOut)
def patch_me(
    body: UserPatchIn,
    db: Session = Depends(get_db),
    user=Depends(get_current_user),
) -> UserOut:
    if body.display_name is not None:
        user.display_name = body.display_name.strip()[:200]
    if body.avatar_url is not None:
        user.avatar_url = body.avatar_url
    db.commit()
    db.refresh(user)
    return UserOut.model_validate(user)


# --- Devices --------------------------------------------------------------------

devices_router = APIRouter(prefix="/devices", tags=["devices"])


@devices_router.get("", response_model=list[DeviceOut])
def list_devices(db: Session = Depends(get_db), user=Depends(get_current_user)) -> list[DeviceOut]:
    devices = sorted(user.devices, key=lambda d: d.created_at)
    return [DeviceOut.model_validate(d) for d in devices]


@devices_router.delete("/{device_id}", status_code=204)
def delete_device(
    device_id: str,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    user=Depends(get_current_user),
) -> None:
    auth_service.revoke_device(db, user.id, device_id)
