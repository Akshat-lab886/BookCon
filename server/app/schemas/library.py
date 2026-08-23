"""Pydantic DTOs — shelves, tags, series, annotations, bookmarks, positions."""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class _NameIn(BaseModel):
    name: str = Field(min_length=1, max_length=300)


class ShelfCreateIn(_NameIn):
    pass


class ShelfPatchIn(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=300)
    sort_position: int | None = None


class ShelfOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    sort_position: int
    updated_at: datetime
    deleted_at: datetime | None = None


class TagCreateIn(_NameIn):
    pass


class TagPatchIn(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=300)


class TagOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    updated_at: datetime
    deleted_at: datetime | None = None


class SeriesCreateIn(_NameIn):
    pass


class SeriesPatchIn(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=300)


class SeriesOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    updated_at: datetime
    deleted_at: datetime | None = None


# --- Annotations --------------------------------------------------------------


class LocatorModel(BaseModel):
    model_config = ConfigDict(extra="allow")
    href: str
    type: str = "application/xhtml+xml"
    title: str | None = None
    locations: dict | None = None
    text: dict | None = None


class AnnotationIn(BaseModel):
    book_id: str
    type: str = Field(pattern="^(highlight|underline|area)$")
    locator: LocatorModel
    color: str = "yellow"
    note: str = ""
    annotation_tags: list[str] = []
    excerpt: str = ""
    client_updated_at: datetime | None = None


class AnnotationPatchIn(BaseModel):
    color: str | None = None
    note: str | None = None
    annotation_tags: list[str] | None = None


class AnnotationOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    book_id: str
    type: str
    locator: dict
    color: str
    note: str
    annotation_tags: list[str] = []
    excerpt: str = ""
    device_id: str | None = None
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None = None


class BookmarkIn(BaseModel):
    book_id: str
    locator: LocatorModel
    label: str = ""
    client_updated_at: datetime | None = None


class BookmarkPatchIn(BaseModel):
    label: str | None = None


class BookmarkOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    book_id: str
    locator: dict
    label: str
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None = None


class PositionIn(BaseModel):
    locator: LocatorModel
    progress_percent: float | None = None
    client_updated_at: datetime | None = None


class PositionOut(BaseModel):
    book_id: str
    locator: dict
    progress_percent: float | None = None
    updated_at: datetime
