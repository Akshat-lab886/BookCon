"""Books routes: library CRUD, upload flow, file/cover access."""

from __future__ import annotations

import base64
from datetime import datetime

from fastapi import APIRouter, BackgroundTasks, Depends, Query, Request
from fastapi.responses import Response
from sqlalchemy import String, and_, func, or_, orm
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db
from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.security import signed_media_url
from app.models import Book, BookShelf, BookTag, User
from app.schemas.book import (
    BookOut,
    BookPage,
    BookPatchIn,
    FileUrlOut,
    InitiateUploadIn,
    InitiateUploadOut,
)
from app.services import book_service
from app.services.storage import book_key, get_storage
from app.worker import run_metadata_now

router = APIRouter(tags=["books"])


def _serialize_many(db: Session, rows: list[Book]) -> list[BookOut]:
    return [book_service.serialize_book(db, b) for b in rows]


@router.get("/books", response_model=BookPage)
def list_books(
    cursor: str | None = None,
    limit: int = Query(default=50, ge=1, le=500),
    q: str | None = None,
    shelf_id: str | None = None,
    tag_id: str | None = None,
    series_id: str | None = None,
    format: str | None = Query(default=None, pattern="^(epub|pdf|cbz|cbr)$"),
    sort: str = Query(default="recent", pattern="^(recent|added|title|author|progress)$"),
    include_deleted: bool = False,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> BookPage:
    query = db.query(Book).options(orm.selectinload(Book.shelf_links), orm.selectinload(Book.tag_links))
    query = query.filter(Book.user_id == user.id)
    if not include_deleted:
        query = query.filter(Book.deleted_at.is_(None))
    if q:
        # Escape LIKE wildcards so user input "%" / "_" matches literally.
        escaped = q.lower().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        like = f"%{escaped}%"
        query = query.filter(
            or_(
                func.lower(Book.title).like(like),
                func.lower(Book.authors_json.cast(String)).like(like),
                func.lower(func.coalesce(Book.description, "")).like(like),
            )
        )
    if shelf_id:
        query = query.join(BookShelf, BookShelf.book_id == Book.id).filter(BookShelf.shelf_id == shelf_id)
    if tag_id:
        query = query.join(BookTag, BookTag.book_id == Book.id).filter(BookTag.tag_id == tag_id)
    if series_id:
        query = query.filter(Book.series_id == series_id)
    if format:
        query = query.filter(Book.format == format)

    title_sort = sort in ("title", "author")
    if title_sort:
        query = query.order_by(func.lower(Book.title).asc(), Book.id.asc())
    elif sort == "added":
        query = query.order_by(Book.added_at.desc(), Book.id.desc())
    else:
        query = query.order_by(Book.updated_at.desc(), Book.id.desc())

    # Keyset pagination: opaque "<sortvalue>|<id>" cursor.
    if cursor:
        try:
            encoded_value, last_id = cursor.split("|", 1)
            value = base64.urlsafe_b64decode(encoded_value.encode()).decode()
            if title_sort:
                cond = or_(
                    func.lower(Book.title) > value,
                    and_(func.lower(Book.title) == value, Book.id > last_id),
                )
            else:
                ts = datetime.fromisoformat(value)
                anchor_col = Book.added_at if sort == "added" else Book.updated_at
                desc = sort != "title"
                if desc:
                    cond = or_(
                        anchor_col < ts,
                        and_(anchor_col == ts, Book.id < last_id),
                    )
                else:
                    cond = or_(
                        anchor_col > ts,
                        and_(anchor_col == ts, Book.id > last_id),
                    )
            query = query.filter(cond)
        except (ValueError, KeyError) as exc:
            raise ApiError(422, "invalid_cursor", "Malformed pagination cursor.") from exc

    rows = query.limit(limit + 1).all()
    has_more = len(rows) > limit
    page_rows = rows[:limit]
    next_cursor = None
    if has_more and page_rows:
        last = page_rows[-1]
        value = (last.title or "").lower() if title_sort else (
            last.added_at if sort == "added" else last.updated_at
        ).isoformat()
        next_cursor = f"{base64.urlsafe_b64encode(value.encode()).decode()}|{last.id}"
    return BookPage(items=_serialize_many(db, page_rows), next_cursor=next_cursor)


@router.post("/books/initiate-upload", response_model=InitiateUploadOut, status_code=201)
def initiate_upload(
    body: InitiateUploadIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> InitiateUploadOut:
    result = book_service.initiate_upload(
        db, user.id, body.filename, body.size_bytes, body.sha256, body.content_type
    )
    return InitiateUploadOut(**result)


@router.put("/books/{book_id}/file")
async def put_file(
    book_id: str,
    request: Request,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> dict:
    """Local-storage backend upload sink (S3 backend uses presigned PUTs instead)."""
    book = book_service.get_book_or_404(db, user.id, book_id)
    if not book.file_sha256:
        raise ApiError(409, "upload_incomplete", "Initiate upload first.")
    # Hard cap on ACTUAL bytes received — a lying Content-Length must not OOM us.
    data = await read_capped(request, get_settings().upload_max_bytes)
    content_type = request.headers.get("Content-Type", "application/octet-stream")
    book_service.store_upload_bytes(db, book.file_sha256, book.format, data, content_type)
    return {"stored": len(data)}


async def read_capped(request: Request, cap: int) -> bytes:
    """Stream the request body enforcing a hard byte cap."""
    buf = bytearray()
    async for chunk in request.stream():
        buf.extend(chunk)
        if len(buf) > cap:
            raise ApiError(413, "file_too_large", f"Upload exceeds the {cap // (1024 * 1024)} MB limit.")
    return bytes(buf)


@router.post("/books/{book_id}/complete-upload", response_model=BookOut, status_code=202)
def complete_upload(
    book_id: str,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> BookOut:
    book = book_service.complete_upload(db, user.id, book_id)
    background_tasks.add_task(run_metadata_now, book.id)
    return book_service.serialize_book(db, book)


@router.get("/books/{book_id}", response_model=BookOut)
def get_book(
    book_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> BookOut:
    book = book_service.get_book_or_404(db, user.id, book_id)
    return book_service.serialize_book(db, book)


@router.patch("/books/{book_id}", response_model=BookOut)
def patch_book(
    book_id: str,
    body: BookPatchIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> BookOut:
    book = book_service.get_book_or_404(db, user.id, book_id)
    patch = body.model_dump(exclude_unset=True)
    book = book_service.apply_book_patch(db, user.id, book, patch)
    return book_service.serialize_book(db, book)


@router.delete("/books/{book_id}", status_code=204)
def delete_book(
    book_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> None:
    book_service.delete_book(db, user.id, book_id)


@router.get("/books/{book_id}/file-url", response_model=FileUrlOut)
def file_url(
    book_id: str,
    download: bool = False,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> FileUrlOut:
    book = book_service.get_book_or_404(db, user.id, book_id)
    if not book.file_sha256:
        raise ApiError(409, "upload_incomplete", "File not uploaded yet.")
    settings = get_settings()
    key = book_key(book.file_sha256, book.format)
    storage = get_storage()
    safe_name = (book.title or "book").replace("/", "_")
    filename = f"{safe_name}.{book.format}"
    if book_service.storage_backend_is_s3():
        url = storage.presign_get(
            key, settings.presign_expiry_seconds, download_filename=filename if download else None
        )
        return FileUrlOut(url=url, expires_in=settings.presign_expiry_seconds)
    url, expires = signed_media_url("files", book.file_sha256, filename)
    return FileUrlOut(url=url, expires_in=expires)


@router.post("/books/{book_id}/cover")
async def replace_cover(
    book_id: str,
    request: Request,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> dict:
    from app.models import FileBlob
    from app.services import metadata as mdsvc
    from app.services.storage import cover_key, sha256_bytes, thumb_key

    book = book_service.get_book_or_404(db, user.id, book_id)
    data = await read_capped(request, 20 * 1024 * 1024)  # covers: hard 20 MB cap
    try:
        thumb = mdsvc.make_thumb(data)
    except Exception as exc:  # noqa: BLE001
        raise ApiError(422, "invalid_image", "Cover is not a readable image.") from exc
    storage = get_storage()
    cover_sha = sha256_bytes(data)
    thumb_sha = sha256_bytes(thumb)
    storage.put(cover_key(cover_sha), data, "image/jpeg")
    storage.put(thumb_key(thumb_sha), thumb, "image/webp")
    # cover_sha256/thumb_sha256 are FKs to files — register blobs (real sizes)
    # BEFORE assigning, or the UPDATE violates the constraint and 500s.
    for blob_sha, blob_size, kind in ((cover_sha, len(data), "cover"), (thumb_sha, len(thumb), "thumb")):
        if db.get(FileBlob, blob_sha) is None:
            db.add(FileBlob(sha256=blob_sha, size_bytes=blob_size, content_type="image/webp", kind=kind))
    db.flush()
    book.cover_sha256 = cover_sha
    book.thumb_sha256 = thumb_sha
    book.updated_at = book_service.utcnow()
    db.commit()
    return {"cover_url": f"{get_settings().public_base_url}/api/v1/books/{book_id}/cover-image"}


@router.get("/books/{book_id}/cover-image")
def cover_image(
    book_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> Response:
    """Streams the cover thumbnail (auth: Bearer token; image loaders should attach it)."""
    book = book_service.get_book_or_404(db, user.id, book_id)
    sha = book.thumb_sha256 or book.cover_sha256
    if not sha:
        raise ApiError(404, "not_found", "No cover available.")
    storage = get_storage()
    data = storage.get(f"covers/{sha}.webp") or storage.get(f"covers/{sha}.cover")
    if data is None:
        raise ApiError(404, "not_found", "No cover available.")
    media_type = "image/webp" if data[:4] == b"RIFF" else "image/jpeg"
    return Response(content=data, media_type=media_type, headers={"Cache-Control": "private, max-age=86400"})
