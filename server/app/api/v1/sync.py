"""/sync/pull + /sync/push — LWW engine per TRD §3.2.

Pull:  per-entity `updated_at` watermark cursors; returns changed rows incl.
       tombstones since each cursor, capped at N=500/entity with has_more.
Push:  batch upsert/tombstone; each row carries client_updated_at; server
       applies only when strictly newer (server clock authoritative), else the
       stored row is returned as `authoritative` for the client to adopt.
"""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID

from fastapi import APIRouter, Depends
from sqlalchemy import select, tuple_
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db, get_device_id
from app.models import Annotation, Book, Bookmark, ReadingPosition, Series, Shelf, Tag, User
from app.schemas.sync import SyncPullIn, SyncPullOut, SyncPushIn, SyncPushOut
from app.services.book_service import serialize_book, utcnow

router = APIRouter(prefix="/sync", tags=["sync"])

ENTITY_MODELS = {
    "books": Book,
    "annotations": Annotation,
    "bookmarks": Bookmark,
    "positions": ReadingPosition,
    "shelves": Shelf,
    "tags": Tag,
    "series": Series,
}

# Cursor format: "<iso-updated-at>|<last-row-id>" so rows tied at the boundary
# timestamp are not skipped (strict `>` on ts alone lost them forever).
# A bare timestamp cursor (no "|") is still accepted for older clients.


def _valid_uuid(value) -> bool:
    if not isinstance(value, str):
        return False
    try:
        UUID(value)
    except ValueError:
        return False
    return True


def _serialize_entity(db: Session, entity: str, row) -> dict:
    if entity == "books":
        return serialize_book(db, row).model_dump(mode="json")
    if entity == "positions":  # composite-PK row: no id/created_at/deleted_at
        return {
            "book_id": row.book_id,
            "locator": row.locator,
            "progress_percent": row.progress_percent,
            "updated_at": row.updated_at.isoformat(),
        }
    common = {
        "id": row.id,
        "created_at": row.created_at.isoformat() if getattr(row, "created_at", None) else None,
        "updated_at": row.updated_at.isoformat(),
        "deleted_at": row.deleted_at.isoformat() if row.deleted_at else None,
    }
    if entity == "annotations":
        return {
            **common,
            "book_id": row.book_id,
            "type": row.type,
            "locator": row.locator,
            "color": row.color,
            "note": row.note,
            "annotation_tags": row.annotation_tags,
            "excerpt": row.excerpt,
            "device_id": row.device_id,
        }
    if entity == "bookmarks":
        return {**common, "book_id": row.book_id, "locator": row.locator, "label": row.label}
    if entity in ("shelves",):
        return {**common, "name": row.name, "sort_position": row.sort_position}
    if entity == "tags":
        return {**common, "name": row.name}
    return {**common, "name": row.name}


@router.post("/pull", response_model=SyncPullOut)
def sync_pull(
    body: SyncPullIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> SyncPullOut:
    limit = max(1, min(body.limit or 500, 500))
    out: dict[str, list] = {e: [] for e in ENTITY_MODELS}
    new_cursors: dict[str, str] = {}
    has_more = False

    for entity, model in ENTITY_MODELS.items():
        watermark = body.cursors.get(entity)
        if entity == "positions":
            base = select(ReadingPosition).where(ReadingPosition.user_id == user.id)
            q = base.order_by(ReadingPosition.updated_at.asc(), ReadingPosition.book_id.asc())
            col_ts, col_id = ReadingPosition.updated_at, ReadingPosition.book_id
        else:
            base = select(model).where(model.user_id == user.id)
            col_ts, col_id = model.updated_at, model.id
            q = base.order_by(col_ts.asc(), col_id.asc())
        if watermark:
            ts_part, sep, id_part = watermark.partition("|")
            anchor_ts = _parse_dt(ts_part)
            if anchor_ts is None:
                continue  # unparseable cursor: skip entity rather than resync everything
            if sep and id_part:
                # Composite (ts, id) tuple comparison: no boundary ties skipped.
                q = q.where(tuple_(col_ts, col_id) > (anchor_ts, id_part))
            else:
                # Legacy ts-only cursor from an older client.
                q = q.where(col_ts > anchor_ts)
        rows = list(db.scalars(q.limit(limit + 1)))
        if len(rows) > limit:
            rows = rows[:limit]
            has_more = True
        if rows:
            last = rows[-1]  # rows are ordered by (ts, id): the tie-break anchor
            new_cursors[entity] = f"{_iso(last.updated_at)}|{last.book_id if entity == 'positions' else last.id}"
            out[entity] = [_serialize_entity(db, entity, r) for r in rows]

    return SyncPullOut(
        cursors=new_cursors,
        has_more=has_more,
        books=out["books"],
        annotations=out["annotations"],
        bookmarks=out["bookmarks"],
        positions=out["positions"],
        shelves=out["shelves"],
        tags=out["tags"],
        series=out["series"],
    )


def _iso(dt: datetime) -> str:
    return dt.astimezone(UTC).isoformat()


@router.post("/push", response_model=SyncPushOut)
def sync_push(
    body: SyncPushIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
    device_id: str | None = Depends(get_device_id),
) -> SyncPushOut:
    accepted: dict[str, list[str]] = {}
    rejected: dict[str, list[str]] = {}
    authoritative: dict[str, list[dict]] = {}

    # Book content does not sync via push (uploads are the source of truth);
    # anything a client pushes here is explicitly rejected, never silently dropped.
    if body.books:
        rejected["books"] = [b.get("id", "?") for b in body.books if isinstance(b, dict)]

    handlers = {
        "annotations": _push_annotations,
        "bookmarks": _push_bookmarks,
        "positions": _push_positions,
        "shelves": _push_shelves,
        "tags": _push_tags,
        "series": _push_series,
    }
    for entity, handler in handlers.items():
        rows = getattr(body, entity) or []
        if not rows:
            continue
        acc: list[str] = []
        rej: list[str] = []
        auth_rows: list[dict] = []
        for item in rows:
            ref = item.get("id") or item.get("book_id") or "?"
            if not isinstance(item, dict):
                rej.append(ref)
                continue
            try:
                outcome = handler(db, user.id, device_id, item)
            except IntegrityError:
                db.rollback()
                outcome = None  # e.g. PK/type constraint violation → rejected, not 500
            if outcome is None:
                rej.append(ref)  # invalid (unknown book/missing id/constraint)
                continue
            applied, serialized = outcome
            if applied:
                acc.append(ref)  # server adopted the client's version
            else:
                # LWW-ignored (TRD §3.2): not accepted; client adopts authoritative row.
                pass
            auth_rows.append(serialized)
        accepted[entity] = acc
        rejected[entity] = rej
        if auth_rows:
            authoritative[entity] = auth_rows
        db.commit()

    return SyncPushOut(accepted=accepted, rejected=rejected, authoritative=authoritative)


def _client_dt(item: dict) -> datetime | None:
    raw = item.get("client_updated_at") or item.get("updated_at")
    if not raw:
        return None
    try:
        value = raw if isinstance(raw, datetime) else datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    except ValueError:
        return None


# --- push handlers --------------------------------------------------------------
# Each returns (accepted_ref, authoritative_payload) applied, or None if rejected.


def _apply_lww(existing, incoming_ts: datetime | None) -> bool:
    """True → apply incoming; False → server row wins."""
    if existing is None:
        return True
    if incoming_ts is None:
        return False
    stored = existing.updated_at
    if stored is None:  # unflushed/new row
        return True
    if stored.tzinfo is None:
        stored = stored.replace(tzinfo=UTC)
    return incoming_ts > stored


def _push_shelves(db: Session, user_id: str, device_id, item: dict):
    ts = _client_dt(item)
    if item.get("id") is not None and not _valid_uuid(item.get("id")):
        return None
    row = db.scalar(select(Shelf).where(Shelf.id == item.get("id"), Shelf.user_id == user_id))
    if row is None:
        name = (item.get("name") or "").strip()
        if not name:
            return None
        dup = db.scalar(select(Shelf).where(Shelf.user_id == user_id, Shelf.name == name))  # incl. tombstones
        if dup:
            row = dup
        else:
            new_id = item.get("id") or None
            row = Shelf(user_id=user_id, name=name)
            if new_id and _valid_uuid(new_id):
                row.id = new_id
            db.add(row)
    elif not _apply_lww(row, ts):
        return False, _serialize_entity(db, "shelves", row)
    row.name = (item.get("name") or row.name).strip() or row.name
    if item.get("sort_position") is not None:
        row.sort_position = int(item["sort_position"])
    deleted = _parse_dt(item.get("deleted_at"))
    if deleted:
        row.deleted_at = deleted
    row.updated_at = utcnow()
    db.add(row)
    return True, _serialize_entity(db, "shelves", row)


def _parse_dt(raw) -> datetime | None:
    if not raw:
        return None
    try:
        value = raw if isinstance(raw, datetime) else datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    except ValueError:
        return None


def _push_tags(db: Session, user_id: str, device_id, item: dict):
    ts = _client_dt(item)
    if item.get("id") is not None and not _valid_uuid(item.get("id")):
        return None
    row = db.scalar(select(Tag).where(Tag.id == item.get("id"), Tag.user_id == user_id))
    if row is None:
        name = (item.get("name") or "").strip()
        if not name:
            return None
        dup = db.scalar(select(Tag).where(Tag.user_id == user_id, Tag.name == name))  # incl. tombstones
        if dup:
            row = dup
        else:
            row = Tag(user_id=user_id, name=name)
            if item.get("id") and _valid_uuid(item["id"]):
                row.id = item["id"]
            db.add(row)
    elif not _apply_lww(row, ts):
        return False, _serialize_entity(db, "tags", row)
    row.name = (item.get("name") or row.name).strip() or row.name
    # Assign unconditionally: absent deleted_at on a newer client version ⇒ recreate.
    row.deleted_at = _parse_dt(item.get("deleted_at"))
    row.updated_at = utcnow()
    db.add(row)
    return True, _serialize_entity(db, "tags", row)


def _push_series(db: Session, user_id: str, device_id, item: dict):
    ts = _client_dt(item)
    if item.get("id") is not None and not _valid_uuid(item.get("id")):
        return None
    row = db.scalar(select(Series).where(Series.id == item.get("id"), Series.user_id == user_id))
    if row is None:
        name = (item.get("name") or "").strip()
        if not name:
            return None
        dup = db.scalar(
            select(Series).where(Series.user_id == user_id, Series.name == name)  # incl. tombstones
        )
        if dup:
            row = dup
        else:
            row = Series(user_id=user_id, name=name)
            if item.get("id") and _valid_uuid(item["id"]):
                row.id = item["id"]
            db.add(row)
    elif not _apply_lww(row, ts):
        return False, _serialize_entity(db, "series", row)
    row.name = (item.get("name") or row.name).strip() or row.name
    deleted = _parse_dt(item.get("deleted_at"))
    if deleted:
        row.deleted_at = deleted
    row.updated_at = utcnow()
    db.add(row)
    return True, _serialize_entity(db, "series", row)


def _push_annotations(db: Session, user_id: str, device_id, item: dict):
    ts = _client_dt(item)
    ann_id = item.get("id")
    if not _valid_uuid(ann_id):
        return None
    if item.get("type") is not None and (not isinstance(item["type"], str) or len(item["type"]) > 16):
        return None  # String(16) column — reject instead of a PG-level 500
    book = db.get(Book, item.get("book_id") or "")
    row = db.scalar(select(Annotation).where(Annotation.id == ann_id, Annotation.user_id == user_id))
    if row is None:
        if book is None or book.user_id != user_id:
            return None
        row = Annotation(id=ann_id, user_id=user_id, book_id=book.id, type=item.get("type", "highlight"),
                         locator=item.get("locator") or {})
        db.add(row)
    if not _apply_lww(row, ts):
        return False, _serialize_entity(db, "annotations", row)
    row.type = item.get("type", row.type)
    if item.get("locator"):
        row.locator = item["locator"]
    row.color = item.get("color", row.color)
    row.note = item.get("note", row.note)
    if item.get("annotation_tags") is not None:
        row.annotation_tags = [str(t) for t in item["annotation_tags"]]
    row.excerpt = item.get("excerpt", row.excerpt)
    deleted = _parse_dt(item.get("deleted_at"))
    if deleted:
        row.deleted_at = deleted
    row.device_id = device_id
    row.updated_at = utcnow()
    return True, _serialize_entity(db, "annotations", row)


def _push_bookmarks(db: Session, user_id: str, device_id, item: dict):
    ts = _client_dt(item)
    bm_id = item.get("id")
    if not _valid_uuid(bm_id):
        return None
    book = db.get(Book, item.get("book_id") or "")
    row = db.scalar(select(Bookmark).where(Bookmark.id == bm_id, Bookmark.user_id == user_id))
    if row is None:
        if book is None or book.user_id != user_id:
            return None
        row = Bookmark(id=bm_id, user_id=user_id, book_id=book.id, locator=item.get("locator") or {})
        db.add(row)
    if not _apply_lww(row, ts):
        return False, _serialize_entity(db, "bookmarks", row)
    if item.get("locator"):
        row.locator = item["locator"]
    row.label = item.get("label", row.label)
    deleted = _parse_dt(item.get("deleted_at"))
    if deleted:
        row.deleted_at = deleted
    row.device_id = device_id
    row.updated_at = utcnow()
    return True, _serialize_entity(db, "bookmarks", row)


def _push_positions(db: Session, user_id: str, device_id, item: dict):
    ts = _client_dt(item)
    book_id = item.get("book_id")
    if not book_id:
        return None
    book = db.get(Book, book_id)
    if book is None or book.user_id != user_id:
        return None
    row = db.get(ReadingPosition, (user_id, book_id))
    applied = row is None or _apply_lww(row, ts)
    if applied:
        if row is None:
            row = ReadingPosition(user_id=user_id, book_id=book_id)
            db.add(row)
        row.locator = item.get("locator") or {}
        row.progress_percent = item.get("progress_percent")
        row.device_id = device_id
        row.updated_at = utcnow()
    return applied, _serialize_entity(db, "positions", row)
