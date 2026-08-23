"""Reading routes: annotations, bookmarks, reading positions (LWW)."""

from __future__ import annotations

from datetime import UTC

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db
from app.core.errors import ApiError
from app.models import Annotation, Bookmark, ReadingPosition, User
from app.schemas.library import (
    AnnotationIn,
    AnnotationOut,
    AnnotationPatchIn,
    BookmarkIn,
    BookmarkOut,
    BookmarkPatchIn,
    PositionIn,
    PositionOut,
)
from app.services.book_service import get_book_or_404, utcnow

router = APIRouter(tags=["reading"])


def _owned(db: Session, model, entity_id: str, user_id: str, label: str):
    obj = db.scalar(select(model).where(model.id == entity_id, model.user_id == user_id))
    if not obj:
        raise ApiError(404, "not_found", f"{label} not found.")
    return obj


# --- Annotations ----------------------------------------------------------------

@router.get("/annotations", response_model=list[AnnotationOut])
def list_annotations(
    book_id: str | None = None,
    since: str | None = None,
    include_deleted: bool = True,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> list[AnnotationOut]:
    q = select(Annotation).where(Annotation.user_id == user.id).order_by(Annotation.updated_at.asc())
    if book_id:
        q = q.where(Annotation.book_id == book_id)
    if since:
        try:
            q = q.where(Annotation.updated_at > datetime_from_iso(since))
        except ValueError as exc:
            raise ApiError(422, "invalid_parameter", "`since` must be an ISO-8601 timestamp.") from exc
    if not include_deleted:
        q = q.where(Annotation.deleted_at.is_(None))
    return [AnnotationOut.model_validate(a) for a in db.scalars(q)]


@router.post("/annotations", response_model=AnnotationOut, status_code=201)
def create_annotation(
    body: AnnotationIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> AnnotationOut:
    get_book_or_404(db, user.id, body.book_id)
    ann = Annotation(
        user_id=user.id,
        book_id=body.book_id,
        type=body.type,
        locator=body.locator.model_dump(exclude_none=True),
        color=body.color,
        note=body.note,
        excerpt=body.excerpt,
        annotation_tags_json=list(body.annotation_tags),
    )
    if body.client_updated_at:
        created = body.client_updated_at
        ann.created_at = created
        ann.updated_at = created
    db.add(ann)
    db.commit()
    db.refresh(ann)
    return AnnotationOut.model_validate(ann)


@router.patch("/annotations/{annotation_id}", response_model=AnnotationOut)
def patch_annotation(
    annotation_id: str,
    body: AnnotationPatchIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> AnnotationOut:
    ann = _owned(db, Annotation, annotation_id, user.id, "Annotation")
    if ann.deleted_at is not None:
        raise ApiError(410, "deleted", "Annotation was deleted.")
    if body.color is not None:
        ann.color = body.color
    if body.note is not None:
        ann.note = body.note
    if body.annotation_tags is not None:
        ann.annotation_tags = list(body.annotation_tags)
    ann.updated_at = utcnow()
    db.commit()
    db.refresh(ann)
    return AnnotationOut.model_validate(ann)


@router.delete("/annotations/{annotation_id}", status_code=204)
def delete_annotation(
    annotation_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> None:
    ann = _owned(db, Annotation, annotation_id, user.id, "Annotation")
    ann.deleted_at = utcnow()  # tombstone (PRD ANN-5)
    ann.updated_at = utcnow()
    db.commit()


# --- Bookmarks -------------------------------------------------------------------

@router.get("/bookmarks", response_model=list[BookmarkOut])
def list_bookmarks(
    book_id: str | None = None,
    include_deleted: bool = False,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> list[BookmarkOut]:
    q = select(Bookmark).where(Bookmark.user_id == user.id).order_by(Bookmark.created_at.asc())
    if book_id:
        q = q.where(Bookmark.book_id == book_id)
    if not include_deleted:
        q = q.where(Bookmark.deleted_at.is_(None))
    return [BookmarkOut.model_validate(b) for b in db.scalars(q)]


@router.post("/bookmarks", response_model=BookmarkOut, status_code=201)
def create_bookmark(
    body: BookmarkIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> BookmarkOut:
    get_book_or_404(db, user.id, body.book_id)
    bm = Bookmark(
        user_id=user.id,
        book_id=body.book_id,
        locator=body.locator.model_dump(exclude_none=True),
        label=body.label,
    )
    db.add(bm)
    db.commit()
    db.refresh(bm)
    return BookmarkOut.model_validate(bm)


@router.patch("/bookmarks/{bookmark_id}", response_model=BookmarkOut)
def patch_bookmark(
    bookmark_id: str,
    body: BookmarkPatchIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> BookmarkOut:
    bm = _owned(db, Bookmark, bookmark_id, user.id, "Bookmark")
    if bm.deleted_at is not None:
        raise ApiError(410, "deleted", "Bookmark was deleted.")
    if body.label is not None:
        bm.label = body.label
    bm.updated_at = utcnow()
    db.commit()
    db.refresh(bm)
    return BookmarkOut.model_validate(bm)


@router.delete("/bookmarks/{bookmark_id}", status_code=204)
def delete_bookmark(
    bookmark_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> None:
    bm = _owned(db, Bookmark, bookmark_id, user.id, "Bookmark")
    bm.deleted_at = utcnow()
    bm.updated_at = utcnow()
    db.commit()


# --- Positions --------------------------------------------------------------------

@router.get("/positions", response_model=list[PositionOut])
def get_positions(
    book_ids: str = Query(..., description="Comma-separated book ids"),
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> list[PositionOut]:
    ids = [i.strip() for i in book_ids.split(",") if i.strip()]
    if not ids:
        return []
    rows = db.scalars(
        select(ReadingPosition).where(ReadingPosition.user_id == user.id, ReadingPosition.book_id.in_(ids))
    ).all()
    return [
        PositionOut(book_id=r.book_id, locator=r.locator, progress_percent=r.progress_percent, updated_at=r.updated_at)
        for r in rows
    ]


@router.put("/positions/{book_id}", response_model=PositionOut)
def put_position(
    book_id: str,
    body: PositionIn,
    device_id: str | None = None,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> PositionOut:
    get_book_or_404(db, user.id, book_id)
    row = db.get(ReadingPosition, (user.id, book_id))
    # LWW anchor: receipt time on the server clock. A client-supplied timestamp
    # is only honored up to "now" so a skewed/forged future clock cannot
    # permanently poison the anchor (all later legitimate writes would lose).
    incoming_ts = utcnow()
    if body.client_updated_at is not None:
        client_ts = _clamp_utc(body.client_updated_at)
        incoming_ts = min(client_ts, incoming_ts) if client_ts else incoming_ts
    if row is None:
        row = ReadingPosition(
            user_id=user.id,
            book_id=book_id,
            locator=body.locator.model_dump(exclude_none=True),
            progress_percent=body.progress_percent,
            device_id=device_id,
        )
        row.updated_at = incoming_ts  # LWW anchor from client clock when provided
        db.add(row)
    else:
        stored_cmp = _as_utc(row.updated_at)
        # LWW: only newer timestamps win (TRD §3.2); tz-safe comparison.
        if stored_cmp is None or incoming_ts > stored_cmp:
            row.locator = body.locator.model_dump(exclude_none=True)
            row.progress_percent = body.progress_percent
            row.device_id = device_id
            row.updated_at = incoming_ts
    db.commit()
    db.refresh(row)
    return PositionOut(
        book_id=book_id, locator=row.locator, progress_percent=row.progress_percent, updated_at=row.updated_at
    )


def _as_utc(value):
    """Normalize a possibly tz-naive stored datetime to aware UTC (None passes through)."""
    if value is None:
        return None
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def _clamp_utc(value):
    """Accept naive or aware datetimes; return aware UTC or None when unparseable."""
    try:
        if value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value.astimezone(UTC)
    except (AttributeError, ValueError, OverflowError):
        return None


def datetime_from_iso(value: str):
    from datetime import datetime

    return datetime.fromisoformat(value.replace("Z", "+00:00"))
