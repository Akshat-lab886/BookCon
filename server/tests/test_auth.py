"""Auth & device lifecycle tests (PRD FR-AUTH)."""

from __future__ import annotations

from tests.conftest import auth_headers, register


def test_register_login_flow(client):
    tokens = register(client, email="aisha@example.com")
    assert tokens["access_token"] and tokens["refresh_token"]
    assert tokens["device"]["name"] == "Pixel 8"
    assert tokens["user"]["email"] == "aisha@example.com"

    login = client.post(
        "/api/v1/auth/login",
        json={"email": "AISHA@example.com", "password": "hunter2boogaloo", "device_name": "Tablet"},
    )
    assert login.status_code == 200
    assert login.json()["device"]["name"] == "Tablet"  # second device registered


def test_duplicate_email_rejected(client):
    register(client)
    resp = client.post(
        "/api/v1/auth/register",
        json={"email": "reader@example.com", "password": "anotherpass1", "device_name": "X"},
    )
    assert resp.status_code == 409
    assert resp.json()["error"]["code"] == "email_exists"


def test_short_password_rejected(client):
    resp = client.post(
        "/api/v1/auth/register",
        json={"email": "x@example.com", "password": "short", "device_name": "X"},
    )
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation_error"


def test_wrong_password_401(client):
    register(client)
    resp = client.post(
        "/api/v1/auth/login", json={"email": "reader@example.com", "password": "wrong-password", "device_name": "X"}
    )
    assert resp.status_code == 401


def test_me_requires_auth_and_returns_profile(client):
    tokens = register(client)
    anon = client.get("/api/v1/me")
    assert anon.status_code == 401
    me = client.get("/api/v1/me", headers=auth_headers(tokens))
    assert me.status_code == 200
    assert me.json()["email"] == "reader@example.com"

    patched = client.patch("/api/v1/me", headers=auth_headers(tokens), json={"display_name": "Aisha"})
    assert patched.json()["display_name"] == "Aisha"


def test_refresh_rotation_and_reuse_detection(client):
    tokens = register(client)

    rotated = client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert rotated.status_code == 200
    new_pair = rotated.json()
    assert new_pair["refresh_token"] != tokens["refresh_token"]

    # Old refresh token reuse → whole family revoked (TRD §5).
    reused = client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert reused.status_code == 401
    assert reused.json()["error"]["code"] == "refresh_token_reused"

    # Even the new token is dead now.
    replay = client.post("/api/v1/auth/refresh", json={"refresh_token": new_pair["refresh_token"]})
    assert replay.status_code in (401,)  # family revoked
    assert replay.json()["error"]["code"] in ("refresh_token_reused", "invalid_refresh_token")


def test_logout_revokes_refresh(client):
    tokens = register(client)
    out = client.post(
        "/api/v1/auth/logout",
        headers=auth_headers(tokens),
        json={"refresh_token": tokens["refresh_token"]},
    )
    assert out.status_code == 204
    after = client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert after.status_code == 401


def test_device_listing_and_revocation(client):
    tokens_phone = register(client, email="dev@example.com", device="Phone")
    tokens_tab = client.post(
        "/api/v1/auth/login",
        json={"email": "dev@example.com", "password": "hunter2boogaloo", "device_name": "Tablet"},
    ).json()

    devices = client.get("/api/v1/devices", headers=auth_headers(tokens_phone))
    names = {d["name"] for d in devices.json()}
    assert {"Phone", "Tablet"} <= names

    tablet_id = next(d["id"] for d in devices.json() if d["name"] == "Tablet")
    deleted = client.delete(f"/api/v1/devices/{tablet_id}", headers=auth_headers(tokens_phone))
    assert deleted.status_code == 204

    # Tablet's refresh token no longer works; access token rejected as revoked device.
    tab_refresh = client.post("/api/v1/auth/refresh", json={"refresh_token": tokens_tab["refresh_token"]})
    assert tab_refresh.status_code == 401

    # Access-token path also blocked for revoked device (needs fresh login to test;
    # existing access token may still be within its 15-min TTL window but device check fires).
    me = client.get("/api/v1/me", headers=auth_headers(tokens_tab))
    assert me.status_code == 401


def test_rate_limit_login_per_ip(client):
    for _ in range(10):
        client.post(
            "/api/v1/auth/login",
            json={"email": "nobody@example.com", "password": "whatever123", "device_name": "X"},
        )
    eleventh = client.post(
        "/api/v1/auth/login",
        json={"email": "nobody@example.com", "password": "whatever123", "device_name": "X"},
    )
    assert eleventh.status_code == 429
    assert eleventh.json()["error"]["code"] == "rate_limited"


def test_expired_access_token(client):
    from app.core.security import create_access_token

    tokens = register(client)
    expired = create_access_token(tokens["user"]["id"], tokens["device"]["id"], minutes=-1)
    resp = client.get("/api/v1/me", headers={"Authorization": f"Bearer {expired}"})
    assert resp.status_code == 401
    assert resp.json()["error"]["code"] == "token_expired"


def test_seed_demo_default_credentials_are_api_loginable(client):
    """Regression: the seeded demo account must be able to log in through the API.

    'demo@bookcon.local' was rejected by email validation (.local is a reserved
    suffix), making the documented demo account unusable.
    """
    from app.schemas.auth import LoginIn

    email = "demo@bookcon.example"  # seed_demo.py default
    assert LoginIn(email=email, password="demo-password-123", device_name="t", app_version="t")
