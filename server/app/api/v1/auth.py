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


def _client_ip(request: Request) -> str:
    """The caller's address, honouring a reverse proxy's forwarded header.

    `request.client.host` is the address of whoever opened the TCP connection. The
    documented deployment terminates TLS at Caddy and proxies to uvicorn
    (`docs/DEPLOY.md`), and the container is started without `--proxy-headers`, so
    that value is the proxy's own address for *every* request. The login limiter
    keyed on it therefore became a single global budget: ten requests from anyone
    locked out every user.

    X-Forwarded-For is client-supplied and forgeable, so it is only trusted when
    TRUST_PROXY_HEADERS is on, which is the operator stating that a proxy they
    control is the only thing that can reach the app. `FORWARDED_ALLOW_IPS` must
    contain the proxy's address for uvicorn to populate request.client at all;
    uvicorn's own parsing of the header is the right one, and this reuses it.
    """
    from app.core.config import get_settings

    if get_settings().trust_proxy_headers:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            # Left-most entry is the original client; the rest are intermediaries.
            return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def _is_routable_client(ip: str) -> bool:
    """Whether [ip] can identify one caller rather than a whole deployment.

    Anything in a private, loopback or link-local range is a proxy, a container
    bridge, or the device itself — every request in the deployment shares it, so
    spending a rate-limit budget on it would deny service to users rather than
    protect them.
    """
    import ipaddress

    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return not (
        addr.is_private or addr.is_loopback or addr.is_link_local
        or addr.is_multicast or addr.is_unspecified
    )


@router.post("/auth/register", response_model=TokensOut, status_code=201)
def register(
    body: RegisterIn,
    request: Request,
    db: Session = Depends(get_db),
) -> TokensOut:
    from app.services.ratelimit import blind_index, check_rate

    client = _client_ip(request)
    if _is_routable_client(client):
        check_rate("register_ip", blind_index(client), limit=10, window_seconds=60)
    check_rate("register_acct", blind_index(body.email), limit=5, window_seconds=3600)
    return auth_service.register(db, body.email, body.password, body.display_name or "", body)


@router.post("/auth/login", response_model=TokensOut)
def login(
    body: LoginIn,
    request: Request,
    db: Session = Depends(get_db),
) -> TokensOut:
    from app.services.ratelimit import blind_index, check_rate_pair

    client = _client_ip(request)
    check_rate_pair(
        "login_ip", blind_index(client) if _is_routable_client(client) else None,
        "login_acct", blind_index(body.email),
        limit=10, window_seconds=60,
    )
    return auth_service.login(db, body.email, body.password, body, device_id=None)


@router.post("/auth/google", response_model=TokensOut)
def google_auth(
    body: GoogleAuthIn,
    request: Request,
    db: Session = Depends(get_db),
) -> TokensOut:
    from app.services.ratelimit import blind_index, check_rate

    client = _client_ip(request)
    if _is_routable_client(client):
        # Unverified token minting attempt: this is a credential oracle, so it gets
        # a tighter budget than password login, not a looser one.
        check_rate("google_ip", blind_index(client), limit=10, window_seconds=60)
    return auth_service.google_login(db, body.id_token, body)


@router.post("/auth/refresh", response_model=TokensOut)
def refresh(body: RefreshIn, request: Request, db: Session = Depends(get_db)) -> TokensOut:
    from app.services.ratelimit import blind_index, check_rate

    client = _client_ip(request)
    if _is_routable_client(client):
        check_rate("refresh_ip", blind_index(client), limit=30, window_seconds=60)
    # The refresh token is the bearer credential here. Without a limit, this is an
    # unauthenticated endpoint that hits the database and the token store on every
    # request, and the rotation path is worth hammering to force a user to
    # re-authenticate.
    check_rate("refresh_tok", blind_index(body.refresh_token), limit=10, window_seconds=60)
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
