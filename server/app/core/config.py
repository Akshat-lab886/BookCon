from functools import lru_cache
import secrets

from pydantic_settings import BaseSettings, SettingsConfigDict

# Values that must never be allowed to sign a real token. `docker-compose.yml`
# shipped "change-me-in-production" while this list only held
# "dev-secret-change-me", so the documented `docker compose up` path deployed a
# publicly-known signing key that the startup guard happily accepted.
INSECURE_JWT_SECRETS = frozenset({
    "",
    "dev-secret-change-me",
    "change-me-in-production",
    "change-me",
    "changeme",
    "secret",
    "jwt-secret",
    "your-secret-here",
    "please-change",
    "insecure",
    "test",
    "development",
})


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    database_url: str = "sqlite:///./bookcon.db"
    jwt_secret: str = "dev-secret-change-me"
    jwt_algorithm: str = "HS256"
    access_token_minutes: int = 15
    refresh_token_days: int = 30

    debug: bool = False
    # Allows the weak/placeholder JWT secret during local development only.
    allow_insecure_jwt_secret: bool = False
    # Trust X-Forwarded-For for rate limiting. Enable ONLY when a proxy you
    # control is the sole path to the app, since the header is client-forgeable.
    trust_proxy_headers: bool = False
    storage_backend: str = "local"  # local | s3
    local_storage_dir: str = "./data/storage"
    s3_endpoint_url: str | None = None
    s3_bucket: str = "bookcon"
    s3_access_key: str | None = None
    s3_secret_key: str | None = None
    s3_region: str = "us-east-1"
    presign_expiry_seconds: int = 900

    public_base_url: str = "http://localhost:8000"
    google_client_id: str | None = None
    google_android_client_id: str | None = None

    upload_max_bytes: int = 200 * 1024 * 1024
    default_page_size: int = 50
    sync_batch_size: int = 500


@lru_cache
def get_settings() -> Settings:
    return Settings()


def generate_jwt_secret() -> str:
    """A 256-bit secret suitable for HS256 (RFC 7518 §3.2 wants >= 32 bytes)."""
    return secrets.token_urlsafe(48)


def jwt_secret_problem(secret: str) -> str | None:
    """Why [secret] is unusable for signing tokens, or None when it is fine.

    Checked in layers rather than by equality with one constant, because the
    placeholder that actually shipped in docker-compose.yml was not the one the
    startup guard compared against:

    * membership in the known-placeholder set,
    * length, since a short HMAC key is brute-forceable (the shipped value was
      23 bytes, and PyJWT itself warns about exactly this),
    * distinct characters, which catches "aaaaaaaaaaaaaaaa" as well as a real word
      that merely happens to pad to 32 bytes.
    """
    if secret in INSECURE_JWT_SECRETS:
        return "it is a known placeholder value"
    if len(secret.encode()) < 32:
        return f"it is only {len(secret.encode())} bytes; HS256 needs at least 32"
    if len(set(secret)) < 8:
        return "it has too few distinct characters to be random"
    return None

