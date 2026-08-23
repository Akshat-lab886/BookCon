from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    database_url: str = "sqlite:///./bookcon.db"
    jwt_secret: str = "dev-secret-change-me"
    jwt_algorithm: str = "HS256"
    access_token_minutes: int = 15
    refresh_token_days: int = 30

    debug: bool = False
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
