"""Storage routes: local-backend media serving (signed URLs), usage stats."""

from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db
from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.security import verify_media_signature
from app.models import Annotation, Book, Bookmark, User
from app.schemas.sync import StorageStats
from app.services.storage import book_key, get_storage, sha256_bytes

router = APIRouter(prefix="/storage", tags=["storage"])

_MEDIA_TYPES = {
    "epub": "application/epub+zip",
    "pdf": "application/pdf",
    "cbz": "application/vnd.comicbook+zip",
    "cbr": "application/vnd.comicbook-rar",
}


@router.get("/local/files/{sha256}/{filename}")
def local_file(sha256: str, filename: str, expires: int, signature: str) -> Response:
    """Signed, token-less download for the local backend (Readium fetches raw URLs)."""
    if not verify_media_signature(sha256, expires, signature):
        raise ApiError(403, "invalid_signature", "Media link expired or invalid.")
    ext = filename.rsplit(".", 1)[-1].lower()
    storage = get_storage()
    data = storage.get(f"files/{sha256}.{ext}")
    if data is None:
        raise ApiError(404, "not_found", "File not found.")
    # quote() keeps CR/LF/quotes out of the header (header-injection safe).
    quoted = quote(filename, safe="")
    return Response(
        content=data,
        media_type=_MEDIA_TYPES.get(ext, "application/octet-stream"),
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quoted}"},
    )


@router.put("/local/upload/{sha256}/{filename}")
async def local_upload(sha256: str, filename: str, expires: int, signature: str, request: Request) -> dict:
    """Signed, token-less upload sink mirroring the S3 presigned-PUT flow."""
    if not verify_media_signature(sha256, expires, signature):
        raise ApiError(403, "invalid_signature", "Upload link expired or invalid.")
    # Hard cap on ACTUAL bytes — the signed URL does not trust Content-Length.
    from app.api.v1.books import read_capped

    data = await read_capped(request, get_settings().upload_max_bytes)
    if sha256_bytes(data) != sha256.lower():
        raise ApiError(400, "hash_mismatch", "Uploaded bytes do not match the declared sha256.")
    ext = filename.rsplit(".", 1)[-1].lower()
    storage = get_storage()
    storage.put(f"files/{sha256.lower()}.{ext}", data, request.headers.get("Content-Type", "application/octet-stream"))
    return {"stored": len(data)}


@router.get("/stats", response_model=StorageStats)
def storage_stats(
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> StorageStats:
    storage = get_storage()
    book_rows = db.scalars(
        select(Book).where(Book.user_id == user.id, Book.deleted_at.is_(None), Book.file_sha256.is_not(None))
    ).all()

    total_bytes = 0
    for b in book_rows:
        size = storage.size(book_key(b.file_sha256, b.format))
        if size:
            total_bytes += size
    book_count = db.scalar(
        select(func.count(Book.id)).where(Book.user_id == user.id, Book.deleted_at.is_(None))
    )
    annotation_count = db.scalar(
        select(func.count(Annotation.id)).where(Annotation.user_id == user.id, Annotation.deleted_at.is_(None))
    )
    bookmark_count = db.scalar(
        select(func.count(Bookmark.id)).where(Bookmark.user_id == user.id, Bookmark.deleted_at.is_(None))
    )
    return StorageStats(
        total_bytes=total_bytes,
        book_count=book_count or 0,
        annotation_count=annotation_count or 0,
        bookmark_count=bookmark_count or 0,
    )
