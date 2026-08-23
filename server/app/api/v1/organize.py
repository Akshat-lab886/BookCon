"""Organize routes: shelves, tags, series CRUD (tombstone deletes)."""

from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db
from app.core.errors import ApiError
from app.models import Book, BookShelf, BookTag, Series, Shelf, Tag, User
from app.schemas.library import (
    SeriesCreateIn,
    SeriesOut,
    SeriesPatchIn,
    ShelfCreateIn,
    ShelfOut,
    ShelfPatchIn,
    TagCreateIn,
    TagOut,
    TagPatchIn,
)
from app.services.book_service import utcnow

router = APIRouter(tags=["organize"])


def _get_owned(db: Session, model, entity_id: str, user_id: str, label: str):
    obj = db.scalar(select(model).where(model.id == entity_id, model.user_id == user_id))
    if not obj:
        raise ApiError(404, "not_found", f"{label} not found.")
    return obj


def _unique_name(db: Session, model, user: User, name: str, exclude_id: str | None = None):
    """409 on a live duplicate; a TOMBSTONED duplicate is revived in place
    (the DB UNIQUE(user_id,name) spans soft-deleted rows, so INSERT would 500)."""
    q = select(model).where(model.user_id == user.id, model.name == name)
    if exclude_id:
        from sqlalchemy import and_

        q = q.where(and_(model.id != exclude_id))
    existing = db.scalar(q)
    if existing and existing.deleted_at is None:
        raise ApiError(409, "already_exists", f"'{name}' already exists.")
    return existing


# --- Shelves -------------------------------------------------------------------

@router.get("/shelves", response_model=list[ShelfOut])
def list_shelves(
    include_deleted: bool = False,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> list[ShelfOut]:
    q = select(Shelf).where(Shelf.user_id == user.id).order_by(Shelf.sort_position.asc(), Shelf.name.asc())
    if not include_deleted:
        q = q.where(Shelf.deleted_at.is_(None))
    return [ShelfOut.model_validate(s) for s in db.scalars(q)]


@router.post("/shelves", response_model=ShelfOut, status_code=201)
def create_shelf(
    body: ShelfCreateIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> ShelfOut:
    revived = _unique_name(db, Shelf, user, body.name)
    if revived is not None:  # tombstoned duplicate → undelete in place
        revived.deleted_at = None
        revived.updated_at = utcnow()
        db.commit()
        db.refresh(revived)
        return ShelfOut.model_validate(revived)
    max_pos = db.scalar(
        select(Shelf.sort_position).where(Shelf.user_id == user.id)
        .order_by(Shelf.sort_position.desc()).limit(1)
    )
    shelf = Shelf(user_id=user.id, name=body.name.strip(), sort_position=(max_pos or 0) + 1)
    db.add(shelf)
    db.commit()
    db.refresh(shelf)
    return ShelfOut.model_validate(shelf)


@router.patch("/shelves/{shelf_id}", response_model=ShelfOut)
def patch_shelf(
    shelf_id: str,
    body: ShelfPatchIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> ShelfOut:
    shelf = _get_owned(db, Shelf, shelf_id, user.id, "Shelf")
    if body.name is not None:
        _unique_name(db, Shelf, user, body.name, exclude_id=shelf_id)
        shelf.name = body.name.strip()
    if body.sort_position is not None:
        shelf.sort_position = body.sort_position
    shelf.updated_at = utcnow()
    db.commit()
    db.refresh(shelf)
    return ShelfOut.model_validate(shelf)


@router.delete("/shelves/{shelf_id}", status_code=204)
def delete_shelf(
    shelf_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> None:
    from sqlalchemy import delete as sa_delete

    shelf = _get_owned(db, Shelf, shelf_id, user.id, "Shelf")
    db.execute(sa_delete(BookShelf).where(BookShelf.shelf_id == shelf.id))  # membership cleared, books kept
    shelf.deleted_at = utcnow()
    shelf.updated_at = utcnow()
    db.commit()


# --- Tags ----------------------------------------------------------------------

@router.get("/tags", response_model=list[TagOut])
def list_tags(
    include_deleted: bool = False,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> list[TagOut]:
    q = select(Tag).where(Tag.user_id == user.id).order_by(Tag.name.asc())
    if not include_deleted:
        q = q.where(Tag.deleted_at.is_(None))
    return [TagOut.model_validate(t) for t in db.scalars(q)]


@router.post("/tags", response_model=TagOut, status_code=201)
def create_tag(
    body: TagCreateIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> TagOut:
    revived = _unique_name(db, Tag, user, body.name)
    if revived is not None:  # tombstoned duplicate → undelete in place
        revived.deleted_at = None
        revived.updated_at = utcnow()
        db.commit()
        db.refresh(revived)
        return TagOut.model_validate(revived)
    tag = Tag(user_id=user.id, name=body.name.strip())
    db.add(tag)
    db.commit()
    db.refresh(tag)
    return TagOut.model_validate(tag)


@router.patch("/tags/{tag_id}", response_model=TagOut)
def patch_tag(
    tag_id: str,
    body: TagPatchIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> TagOut:
    tag = _get_owned(db, Tag, tag_id, user.id, "Tag")
    if body.name is not None:
        _unique_name(db, Tag, user, body.name, exclude_id=tag_id)
        tag.name = body.name.strip()
    tag.updated_at = utcnow()
    db.commit()
    db.refresh(tag)
    return TagOut.model_validate(tag)


@router.delete("/tags/{tag_id}", status_code=204)
def delete_tag(
    tag_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> None:
    from sqlalchemy import delete as sa_delete

    tag = _get_owned(db, Tag, tag_id, user.id, "Tag")
    db.execute(sa_delete(BookTag).where(BookTag.tag_id == tag.id))  # cascade membership removal
    tag.deleted_at = utcnow()
    tag.updated_at = utcnow()
    db.commit()


# --- Series ----------------------------------------------------------------------

@router.get("/series", response_model=list[SeriesOut])
def list_series(
    include_deleted: bool = False,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> list[SeriesOut]:
    q = select(Series).where(Series.user_id == user.id).order_by(Series.name.asc())
    if not include_deleted:
        q = q.where(Series.deleted_at.is_(None))
    out = []
    for s in db.scalars(q):
        out.append(SeriesOut.model_validate(s))
    return out


@router.get("/series/{series_id}/books")
def series_books(
    series_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> list:
    """Books of a series ordered by series_index (PRD LIB-5 view)."""
    _get_owned(db, Series, series_id, user.id, "Series")
    books = db.scalars(
        select(Book)
        .where(Book.user_id == user.id, Book.series_id == series_id, Book.deleted_at.is_(None))
        .order_by(Book.series_index.asc().nulls_last(), Book.title.asc())  # type: ignore[union-attr]
    ).all()
    from app.services.book_service import serialize_book

    return [serialize_book(db, b).model_dump(mode="json") for b in books]


@router.post("/series", response_model=SeriesOut, status_code=201)
def create_series(
    body: SeriesCreateIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> SeriesOut:
    revived = _unique_name(db, Series, user, body.name)
    if revived is not None:  # tombstoned duplicate → undelete in place
        revived.deleted_at = None
        revived.updated_at = utcnow()
        db.commit()
        db.refresh(revived)
        return SeriesOut.model_validate(revived)
    series = Series(user_id=user.id, name=body.name.strip())
    db.add(series)
    db.commit()
    db.refresh(series)
    return SeriesOut.model_validate(series)


@router.patch("/series/{series_id}", response_model=SeriesOut)
def patch_series(
    series_id: str,
    body: SeriesPatchIn,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> SeriesOut:
    series = _get_owned(db, Series, series_id, user.id, "Series")
    if body.name is not None:
        _unique_name(db, Series, user, body.name, exclude_id=series_id)
        series.name = body.name.strip()
    series.updated_at = utcnow()
    db.commit()
    db.refresh(series)
    return SeriesOut.model_validate(series)


@router.delete("/series/{series_id}", status_code=204)
def delete_series(
    series_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> None:
    series = _get_owned(db, Series, series_id, user.id, "Series")
    series.deleted_at = utcnow()
    series.updated_at = utcnow()
    # Books keep their metadata but lose the FK (ON DELETE SET NULL semantics
    # are handled at DB level; tombstoned series rows keep books attached).
    db.commit()
