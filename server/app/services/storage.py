"""Storage abstraction: content-addressed blobs in local FS or S3-compatible store.

Bucket layout (TRD §1): files/{sha256}.{ext} · covers/{sha256}.webp
"""

from __future__ import annotations

import hashlib
import logging
import os
import uuid
from dataclasses import dataclass
from pathlib import Path

logger = logging.getLogger("bookcon.storage")


@dataclass
class StoredBlob:
    sha256: str
    size_bytes: int
    content_type: str


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class LocalStorage:
    """Filesystem backend under a single root dir; layout mirrors bucket keys."""

    def __init__(self, root: str) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, key: str) -> Path:
        return self.root / key

    def put(self, key: str, data: bytes, content_type: str = "application/octet-stream") -> StoredBlob:
        path = self._path(key)
        if not path.exists():  # content-addressed → identical bytes are skippable
            path.parent.mkdir(parents=True, exist_ok=True)
            # Unique per-call tmp name: two concurrent uploads of the same sha
            # must not interleave writes on one shared ".tmp" file.
            tmp = path.with_suffix(f".{os.getpid()}-{uuid.uuid4().hex[:8]}.tmp")
            try:
                tmp.write_bytes(data)
                os.replace(tmp, path)
            finally:
                tmp.unlink(missing_ok=True)
        return StoredBlob(sha256_bytes(data), len(data), content_type)

    def put_file(self, key: str, src: Path, content_type: str) -> StoredBlob:
        return self.put(key, src.read_bytes(), content_type)

    def get(self, key: str) -> bytes | None:
        path = self._path(key)
        if not path.exists():
            return None
        return path.read_bytes()

    def exists(self, key: str) -> bool:
        return self._path(key).exists()

    def size(self, key: str) -> int | None:
        path = self._path(key)
        return path.stat().st_size if path.exists() else None

    def delete(self, key: str) -> None:
        path = self._path(key)
        if path.exists():
            path.unlink(missing_ok=True)

    def iter_keys(self) -> list[str]:
        out: list[str] = []
        for dirpath, _, filenames in os.walk(self.root):
            for name in filenames:
                full = Path(dirpath) / name
                out.append(str(full.relative_to(self.root)))
        return out


class S3Storage:
    """S3-compatible backend (MinIO/AWS) via boto3."""

    def __init__(
        self, endpoint_url: str | None, bucket: str,
        access_key: str, secret_key: str, region: str = "us-east-1",
    ) -> None:
        import boto3

        self.bucket = bucket
        self.client = boto3.client(
            "s3",
            endpoint_url=endpoint_url,
            aws_access_key_id=access_key,
            aws_secret_access_key=secret_key,
            region_name=region,
        )

    def put(self, key: str, data: bytes, content_type: str = "application/octet-stream") -> StoredBlob:
        self.client.put_object(Bucket=self.bucket, Key=key, Body=data, ContentType=content_type)
        return StoredBlob(sha256_bytes(data), len(data), content_type)

    def put_file(self, key: str, src: Path, content_type: str) -> StoredBlob:
        data = src.read_bytes()
        return self.put(key, data, content_type)

    def get(self, key: str) -> bytes | None:
        try:
            resp = self.client.get_object(Bucket=self.bucket, Key=key)
            return resp["Body"].read()
        except self.client.exceptions.NoSuchKey:
            return None

    def exists(self, key: str) -> bool:
        try:
            self.client.head_object(Bucket=self.bucket, Key=key)
            return True
        except Exception:
            return False

    def size(self, key: str) -> int | None:
        try:
            return self.client.head_object(Bucket=self.bucket, Key=key)["ContentLength"]
        except Exception:
            return None

    def delete(self, key: str) -> None:
        self.client.delete_object(Bucket=self.bucket, Key=key)

    def iter_keys(self) -> list[str]:
        out: list[str] = []
        paginator = self.client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=self.bucket):
            for obj in page.get("Contents", []):
                out.append(obj["Key"])
        return out

    # Presigned URLs only exist for the S3 backend.
    def presign_put(self, key: str, content_type: str, expires: int) -> tuple[str, dict[str, str]]:
        url = self.client.generate_presigned_url(
            "put_object",
            Params={"Bucket": self.bucket, "Key": key, "ContentType": content_type},
            ExpiresIn=expires,
        )
        return url, {"Content-Type": content_type}

    def presign_get(self, key: str, expires: int, download_filename: str | None = None) -> str:
        params: dict = {"Bucket": self.bucket, "Key": key}
        if download_filename:
            params["ResponseContentDisposition"] = f'attachment; filename="{download_filename}"'
        return self.client.generate_presigned_url("get_object", Params=params, ExpiresIn=expires)


_storage: LocalStorage | S3Storage | None = None


def get_storage() -> LocalStorage | S3Storage:
    global _storage
    if _storage is not None:
        return _storage
    from app.core.config import get_settings

    s = get_settings()
    if s.storage_backend == "s3":
        if not (s.s3_access_key and s.s3_secret_key):
            raise RuntimeError("STORAGE_BACKEND=s3 requires S3_ACCESS_KEY/S3_SECRET_KEY")
        _storage = S3Storage(s.s3_endpoint_url, s.s3_bucket, s.s3_access_key, s.s3_secret_key, s.s3_region)
    else:
        _storage = LocalStorage(s.local_storage_dir)
    return _storage


def reset_storage_cache() -> None:
    """Test helper."""
    global _storage
    _storage = None


def book_key(sha256: str, fmt: str) -> str:
    return f"files/{sha256}.{fmt}"


def cover_key(sha256: str) -> str:
    return f"covers/{sha256}.cover"


def thumb_key(sha256: str) -> str:
    return f"covers/{sha256}.webp"
