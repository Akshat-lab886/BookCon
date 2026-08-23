"""Pydantic DTOs — books & upload."""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class BookBase(BaseModel):
    title: str | None = Field(default=None, max_length=500)
    authors: list[str] | None = None
    description: str | None = None
    language: str | None = None
    publisher: str | None = None
    published_date: str | None = None
    series_id: str | None = None
    series_index: float | None = None


class BookPatchIn(BookBase):
    series_name: str | None = None  # convenience: get-or-create by name
    tag_ids: list[str] | None = None
    shelf_ids: list[str] | None = None


class InitiateUploadIn(BaseModel):
    filename: str = Field(min_length=1, max_length=500)
    size_bytes: int = Field(gt=0)
    sha256: str = Field(min_length=64, max_length=64, pattern=r"^[0-9a-fA-F]{64}$")
    content_type: str = "application/octet-stream"


class InitiateUploadOut(BaseModel):
    outcome: str  # upload_required | duplicate
    book_id: str
    upload_url: str | None = None
    method: str | None = None
    headers: dict[str, str] | None = None
    expires_in: int | None = None


class BookOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    format: str
    status: str
    status_message: str | None = None
    title: str
    authors: list[str] = []
    description: str = ""
    language: str | None = None
    publisher: str | None = None
    published_date: str | None = None
    series_id: str | None = None
    series_index: float | None = None
    cover_url: str | None = None
    file_size_bytes: int | None = None
    page_count: int | None = None
    word_count: int | None = None
    tag_ids: list[str] = []
    shelf_ids: list[str] = []
    added_at: datetime
    updated_at: datetime
    deleted_at: datetime | None = None


class BookPage(BaseModel):
    items: list[BookOut]
    next_cursor: str | None = None


class FileUrlOut(BaseModel):
    url: str
    expires_in: int
