"""Regression tests for the auth hardening.

Each test here pins a defect that was live in the code, not a general property.
"""

from __future__ import annotations

import datetime as dt
import uuid

import jwt
import pytest

from app.core.config import generate_jwt_secret, get_settings, jwt_secret_problem
from app.core.security import create_access_token, decode_access_token
from app.services.ratelimit import blind_index, reset_rate_limiter


# --- the signing key ---------------------------------------------------------

def test_the_secret_docker_compose_ships_is_rejected():
    """The single most important test in this file.

    main.py used to refuse only "dev-secret-change-me" while docker-compose.yml
    deployed "change-me-in-production", so the documented `docker compose up`
    started a publicly-known signing key and the guard said nothing.
    """
    assert jwt_secret_problem("change-me-in-production") is not None


@pytest.mark.parametrize(
    "secret",
    ["", "dev-secret-change-me", "change-me", "changeme", "secret", "jwt-secret", "test"],
)
def test_known_placeholders_are_rejected(secret: str):
    assert jwt_secret_problem(secret) is not None


def test_a_short_but_unfamiliar_secret_is_rejected():
    """A 23-byte key is what compose shipped; PyJWT warns about exactly this."""
    assert jwt_secret_problem("a-perfectly-unique-secret!") is not None


def test_a_low_entropy_secret_of_the_right_length_is_rejected():
    assert jwt_secret_problem("a" * 48) is not None
    assert jwt_secret_problem("ab" * 24) is not None


def test_a_generated_secret_passes():
    assert jwt_secret_problem(generate_jwt_secret()) is None


def test_generated_secrets_differ():
    assert len({generate_jwt_secret() for _ in range(5)}) == 5


# --- token shape -------------------------------------------------------------

def test_a_token_without_exp_is_rejected(monkeypatch):
    """PyJWT only validates a claim that is present, so this used to decode
    successfully and then never expire."""
    settings = get_settings()
    uid, did = str(uuid.uuid4()), str(uuid.uuid4())
    forever = jwt.encode({"sub": uid, "did": did}, settings.jwt_secret,
                          algorithm=settings.jwt_algorithm)
    with pytest.raises(jwt.MissingRequiredClaimError):
        decode_access_token(forever)


def test_a_token_without_device_binding_is_rejected():
    settings = get_settings()
    uid = str(uuid.uuid4())
    no_device = jwt.encode(
        {
            "sub": uid,
            "iat": int(dt.datetime.now(dt.timezone.utc).timestamp()),
            "exp": int((dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=5)).timestamp()),
        },
        settings.jwt_secret,
        algorithm=settings.jwt_algorithm,
    )
    with pytest.raises(jwt.MissingRequiredClaimError):
        decode_access_token(no_device)


def test_a_token_signed_with_another_algorithm_is_rejected():
    settings = get_settings()
    uid, did = str(uuid.uuid4()), str(uuid.uuid4())
    now = dt.datetime.now(dt.timezone.utc)
    swapped = jwt.encode(
        {"sub": uid, "did": did, "iat": int(now.timestamp()),
         "exp": int((now + dt.timedelta(minutes=5)).timestamp())},
        settings.jwt_secret,
        algorithm="HS512",
    )
    with pytest.raises(jwt.InvalidAlgorithmError):
        decode_access_token(swapped)


def test_an_expired_token_is_rejected():
    settings = get_settings()
    uid, did = str(uuid.uuid4()), str(uuid.uuid4())
    past = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=5)
    stale = jwt.encode(
        {"sub": uid, "did": did, "iat": int(past.timestamp()),
         "exp": int((past + dt.timedelta(minutes=1)).timestamp())},
        settings.jwt_secret,
        algorithm=settings.jwt_algorithm,
    )
    with pytest.raises(jwt.ExpiredSignatureError):
        decode_access_token(stale)


def test_a_correctly_issued_token_still_decodes():
    uid, did = str(uuid.uuid4()), str(uuid.uuid4())
    assert decode_access_token(create_access_token(uid, did))["sub"] == uid


# --- rate limiting ------------------------------------------------------------

def test_blind_index_is_stable_and_does_not_leak_the_input():
    a = blind_index("Reader@Example.com ")
    b = blind_index("reader@example.com")
    assert a == b, "case and surrounding space must not create a fresh budget"
    assert "reader" not in a
    assert a != blind_index("someone-else@example.com")


def test_rate_limit_still_trips():
    from app.core.errors import ApiError
    from app.services.ratelimit import check_rate_pair

    reset_rate_limiter()
    for _ in range(3):
        check_rate_pair("t_ip", "1.2.3.4", "t_acct", "abc", limit=3, window_seconds=60)
    with pytest.raises(ApiError) as exc:
        check_rate_pair("t_ip", "1.2.3.4", "t_acct", "abc", limit=3, window_seconds=60)
    assert exc.value.status_code == 429
    reset_rate_limiter()


def test_a_shared_proxy_address_does_not_lock_out_other_accounts():
    """The deployment fronts uvicorn with Caddy, so without forwarded headers every
    request looks like it came from the proxy. A hard IP limit would then be one
    global budget and the first morning users would lock out everyone else."""
    from app.api.v1.auth import _is_routable_client
    from app.core.errors import ApiError
    from app.services.ratelimit import check_rate_pair

    # A private address is a proxy/container bridge, not a caller identity.
    assert not _is_routable_client("172.18.0.5")
    assert not _is_routable_client("127.0.0.1")
    assert not _is_routable_client("10.0.0.7")
    assert not _is_routable_client("not-an-ip")
    # 8.8.8.8 is a real global address. (203.0.113.x is TEST-NET-3 and the
    # ipaddress module correctly treats the documentation ranges as non-global.)
    assert _is_routable_client("8.8.8.8")

    reset_rate_limiter()
    for _ in range(3):
        check_rate_pair("s_ip", None, "s_acct", blind_index("a@x.com"),
                        limit=3, window_seconds=60)
    with pytest.raises(ApiError):
        check_rate_pair("s_ip", None, "s_acct", blind_index("a@x.com"),
                        limit=3, window_seconds=60)
    # A different account is unaffected, because the shared address is not a budget.
    check_rate_pair("s_ip", None, "s_acct", blind_index("b@x.com"),
                    limit=3, window_seconds=60)
    reset_rate_limiter()


def test_a_routable_client_is_still_limited_by_its_address():
    from app.core.errors import ApiError
    from app.services.ratelimit import check_rate_pair

    reset_rate_limiter()
    key = blind_index("8.8.8.8")
    for _ in range(2):
        check_rate_pair("r_ip", key, "r_acct", blind_index(f"{_}@x.com"),
                        limit=2, window_seconds=60)
    with pytest.raises(ApiError):
        check_rate_pair("r_ip", key, "r_acct", blind_index("other@x.com"),
                        limit=2, window_seconds=60)
    reset_rate_limiter()
