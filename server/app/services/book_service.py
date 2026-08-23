"""Book service: upload flow, dedupe, metadata pipeline, serialization."""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session, selectinload

from app.core.config import get_settings
from app.core.errors import ApiError
from app.core.security import signed_media_url
from app.models import Book, BookShelf, BookTag, FileBlob, Series, Shelf, Tag
from app.schemas.book import BookOut
from app.services import metadata as mdsvc
from app.services.storage import book_key, cover_key, get_storage, sha256_bytes, thumb_key

ALLOWED_EXTENSIONS = {"epub": "epub", "pdf": "pdf", "cbz": "cbz", "cbr": "cbr"}


def format_from_filename(filename: str) -> str:
    ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    if ext not in ALLOWED_EXTENSIONS:
        raise ApiError(
            422, "unsupported_format",
            f"Unsupported file type '.{ext}'. Supported: epub, pdf, cbz, cbr.",
        )
    return ext


def get_book_or_404(db: Session, user_id: str, book_id: str, include_deleted: bool = False) -> Book:
    book = (
        db.query(Book)
        .options(selectinload(Book.shelf_links), selectinload(Book.tag_links))
        .filter(Book.id == book_id, Book.user_id == user_id)
        .first()
    )
    if not book or (book.deleted_at is not None and not include_deleted):
        raise ApiError(404, "not_found", "Book not found.")
    return book


def serialize_book(db: Session, book: Book) -> BookOut:
    settings = get_settings()
    storage = get_storage()
    file_size = storage.size(book_key(book.file_sha256, book.format)) if book.file_sha256 else None

    cover_url = None
    if book.thumb_sha256 or book.cover_sha256:
        cover_url = f"{settings.public_base_url}/api/v1/books/{book.id}/cover-image"

    return BookOut(
        id=book.id,
        format=book.format,
        status=book.status,
        status_message=book.status_message,
        title=book.title,
        authors=book.authors,
        description=book.description or "",
        language=book.language,
        publisher=book.publisher,
        published_date=book.published_date,
        series_id=book.series_id,
        series_index=book.series_index,
        cover_url=cover_url,
        file_size_bytes=file_size,
        page_count=book.page_count,
        word_count=book.word_count,
        tag_ids=[link.tag_id for link in book.tag_links],
        shelf_ids=[link.shelf_id for link in book.shelf_links],
        added_at=book.added_at,
        updated_at=book.updated_at,
        deleted_at=book.deleted_at,
    )


def initiate_upload(db: Session, user_id: str, filename: str, size_bytes: int, sha: str, content_type: str) -> dict:
    from app.services.ratelimit import check_rate

    settings = get_settings()
    if size_bytes > settings.upload_max_bytes:
        raise ApiError(413, "file_too_large", f"Files up to {settings.upload_max_bytes // (1024 * 1024)} MB supported.")
    check_rate("upload", user_id, limit=30, window_seconds=3600)

    sha = sha.lower()
    fmt = format_from_filename(filename)

    # Dedupe: same user already has this exact content (TRD §4.1)
    existing = db.scalar(select(Book).where(Book.user_id == user_id, Book.file_sha256 == sha))
    if existing and existing.deleted_at is None:
        if existing.status == "processing":
            # Interrupted upload of THIS book: hand back a fresh signed URL for
            # the same book id instead of "duplicate" (clients resume safely).
            storage0 = get_storage()
            key0 = book_key(sha, existing.format)
            if storage_backend_is_s3():
                url0, headers0 = storage0.presign_put(key0, content_type, settings.presign_expiry_seconds)
                db.commit()
                return {
                    "outcome": "upload_required",
                    "book_id": existing.id,
                    "upload_url": url0,
                    "method": "PUT",
                    "headers": headers0,
                    "expires_in": settings.presign_expiry_seconds,
                }
            url0, expires0 = signed_media_url("upload", sha, filename)
            db.commit()
            return {
                "outcome": "upload_required",
                "book_id": existing.id,
                "upload_url": url0,
                "method": "PUT",
                "headers": {"Content-Type": content_type},
                "expires_in": expires0,
            }
        return {"outcome": "duplicate", "book_id": existing.id}
    if existing and existing.deleted_at is not None:  # re-import of tombstoned book → restore
        existing.deleted_at = None
        existing.status = "processing"
        existing.updated_at = utcnow()
        if existing.file_sha256:
            blob = db.get(FileBlob, existing.file_sha256)
            if blob is not None:  # undo the delete-time release
                blob.ref_count += 1
        db.commit()
        return {"outcome": "duplicate", "book_id": existing.id}

    storage = get_storage()
    key = book_key(sha, fmt)

    # Blob registry row must exist before the book row (FK files.sha256).
    # Checked regardless of disk state: the DB may be newer/older than storage
    # (e.g. restart with existing bucket, or re-init).
    blob = db.get(FileBlob, sha)
    if blob is None:
        db.add(FileBlob(sha256=sha, size_bytes=size_bytes, content_type=content_type, kind="book", ref_count=1))
        db.flush()
    else:
        blob.ref_count += 1  # honest sharing count: this new book references the blob too
        db.flush()

    book = Book(user_id=user_id, file_sha256=sha, format=fmt, title=filename, status="processing")
    db.add(book)
    db.flush()
    _ = key  # used by S3 presign below

    if storage_backend_is_s3():
        url, headers = storage.presign_put(key, content_type, settings.presign_expiry_seconds)
        db.commit()
        return {
            "outcome": "upload_required",
            "book_id": book.id,
            "upload_url": url,
            "method": "PUT",
            "headers": headers,
            "expires_in": settings.presign_expiry_seconds,
        }
    db.commit()
    # Local backend: client PUTs through the API endpoint (signed URL below).
    url, expires = signed_media_url("upload", sha, filename)
    return {
        "outcome": "upload_required",
        "book_id": book.id,
        "upload_url": url,
        "method": "PUT",
        "headers": {"Content-Type": content_type},
        "expires_in": expires,
    }


def storage_backend_is_s3() -> bool:
    from app.core.config import get_settings

    return get_settings().storage_backend == "s3"


def store_upload_bytes(db: Session, sha: str, fmt: str, data: bytes, content_type: str) -> None:
    actual = sha256_bytes(data)
    if actual != sha.lower():
        raise ApiError(400, "hash_mismatch", "Uploaded bytes do not match the declared sha256.")
    storage = get_storage()
    blob = db.get(FileBlob, sha.lower())
    size = len(data)
    if blob:
        blob.size_bytes = size
        blob.content_type = content_type
    else:
        db.add(FileBlob(sha256=sha.lower(), size_bytes=size, content_type=content_type, kind="book"))
    db.commit()
    storage.put(book_key(sha.lower(), fmt), data, content_type)


def complete_upload(db: Session, user_id: str, book_id: str) -> Book:
    book = get_book_or_404(db, user_id, book_id)
    if book.status == "ready":
        return book
    if not book.file_sha256:
        raise ApiError(409, "upload_incomplete", "Upload has not been stored yet.")
    storage = get_storage()
    key = book_key(book.file_sha256, book.format)
    if not storage.exists(key):
        raise ApiError(409, "upload_incomplete", "No stored file for this book; upload first.")
    book.status = "processing"
    book.status_message = None
    book.updated_at = utcnow()
    db.commit()
    return book


def process_book_metadata(db: Session, book_id: str) -> None:
    """Runs in the worker: extract metadata + cover/thumb, set status."""
    book = db.get(Book, book_id)
    if not book or not book.file_sha256:
        return
    storage = get_storage()
    try:
        data = storage.get(book_key(book.file_sha256, book.format))
        if data is None:
            raise ValueError("stored file missing")
        meta = mdsvc.extract_metadata(book.format, data, book.title or "book")
        book.title = meta.title or book.title
        book.authors = meta.authors or []
        book.description = meta.description or ""
        book.language = meta.language
        book.publisher = meta.publisher
        book.published_date = meta.published_date
        book.page_count = meta.page_count or book.page_count
        book.word_count = meta.word_count or book.word_count
        if meta.cover_bytes:
            cover_sha = sha256_bytes(meta.cover_bytes)
            storage.put(cover_key(cover_sha), meta.cover_bytes, meta.cover_content_type or "image/jpeg")
            thumb = mdsvc.make_thumb(meta.cover_bytes)
            thumb_sha = sha256_bytes(thumb)
            storage.put(thumb_key(thumb_sha), thumb, "image/webp")
            # Register blob rows (books.cover_sha256/thumb_sha256 are FKs to files).
            for blob_sha, kind in ((cover_sha, "cover"), (thumb_sha, "thumb")):
                if db.get(FileBlob, blob_sha) is None:
                    db.add(FileBlob(sha256=blob_sha, size_bytes=0, content_type="image/webp", kind=kind))
            db.flush()
            book.cover_sha256 = cover_sha
            book.thumb_sha256 = thumb_sha
        book.status = "ready"
        book.status_message = None
    except Exception as exc:  # noqa: BLE001 — worker must record, never crash
        book.status = "failed"
        book.status_message = str(exc)[:500]
    book.updated_at = utcnow()
    db.commit()


def apply_book_patch(db: Session, user_id: str, book: Book, patch: dict) -> Book:
    """Metadata edit incl. series/tags/shelf membership (PRD LIB-3/4/5/6)."""
    simple_fields = ("title", "description", "language", "publisher", "published_date", "series_index")
    for field_name in simple_fields:
        if field_name in patch and patch[field_name] is not None:
            setattr(book, field_name, patch[field_name])
    if patch.get("authors") is not None:
        book.authors = [str(a).strip() for a in patch["authors"] if str(a).strip()]

    if patch.get("series_id") or patch.get("series_name"):
        series = None
        if patch.get("series_id"):
            series = db.scalar(
                select(Series).where(Series.id == patch["series_id"], Series.user_id == user_id)
            )
        elif patch.get("series_name"):
            name = patch["series_name"].strip()
            series = db.scalar(select(Series).where(Series.user_id == user_id, Series.name == name))
            if not series:
                series = Series(user_id=user_id, name=name)
                db.add(series)
                db.flush()
        if series is None:
            raise ApiError(404, "not_found", "Series not found.")
        book.series_id = series.id
        series.updated_at = utcnow()
    elif "series_id" in patch and patch["series_id"] is None:
        book.series_id = None

    if patch.get("tag_ids") is not None:
        book.tag_links.clear()
        for tag_id in patch["tag_ids"]:
            tag = db.scalar(select(Tag).where(Tag.id == tag_id, Tag.user_id == user_id))
            if not tag:
                raise ApiError(404, "not_found", f"Tag {tag_id} not found.")
            link = BookTag(book_id=book.id, tag_id=tag.id)
            book.tag_links.append(link)  # append (not db.add) so the in-memory collection stays authoritative

    if patch.get("shelf_ids") is not None:
        book.shelf_links.clear()
        for shelf_id in patch["shelf_ids"]:
            shelf = db.scalar(select(Shelf).where(Shelf.id == shelf_id, Shelf.user_id == user_id))
            if not shelf:
                raise ApiError(404, "not_found", f"Shelf {shelf_id} not found.")
            link = BookShelf(book_id=book.id, shelf_id=shelf.id, position=next_position(db, shelf_id))
            book.shelf_links.append(link)

    book.updated_at = utcnow()
    db.commit()
    return book


def next_position(db: Session, shelf_id: str) -> int:
    max_pos = db.scalar(select(func.max(BookShelf.position)).where(BookShelf.shelf_id == shelf_id))
    return (max_pos or 0) + 1


def delete_book(db: Session, user_id: str, book_id: str, hard_delete_files: bool = False) -> None:
    book = get_book_or_404(db, user_id, book_id)
    book.deleted_at = utcnow()
    book.updated_at = utcnow()
    _release_blob_refs(db, book)
    db.commit()


def _release_blob_refs(db: Session, book: Book) -> None:
    """Decrement FileBlob.ref_count for every blob this book references."""
    for sha in (book.file_sha256, book.cover_sha256, book.thumb_sha256):
        if sha:
            blob = db.get(FileBlob, sha)
            if blob is not None and blob.ref_count > 0:
                blob.ref_count -= 1


def utcnow():
    return datetime.now(UTC)
