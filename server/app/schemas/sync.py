"""Pydantic DTOs — sync pull/push and storage stats."""

from __future__ import annotations

from pydantic import BaseModel

from app.schemas.book import BookOut
from app.schemas.library import AnnotationOut, BookmarkOut, PositionOut, SeriesOut, ShelfOut, TagOut


class SyncPullIn(BaseModel):
    # Opaque per-entity watermarks: "<iso-updated-at>|<last-id>" (or a bare
    # timestamp for legacy clients). Parsed server-side, never by pydantic,
    # so the composite tie-break format stays wire-compatible.
    cursors: dict[str, str] = {}
    limit: int = 500


class SyncPullOut(BaseModel):
    cursors: dict[str, str]
    has_more: bool
    books: list[BookOut] = []
    annotations: list[AnnotationOut] = []
    bookmarks: list[BookmarkOut] = []
    positions: list[PositionOut] = []
    shelves: list[ShelfOut] = []
    tags: list[TagOut] = []
    series: list[SeriesOut] = []


class SyncPushIn(BaseModel):
    books: list[dict] = []
    annotations: list[dict] = []
    bookmarks: list[dict] = []
    positions: list[dict] = []
    shelves: list[dict] = []
    tags: list[dict] = []
    series: list[dict] = []


class SyncPushOut(BaseModel):
    accepted: dict[str, list[str]]
    rejected: dict[str, list[str]]
    authoritative: dict[str, list[dict]]


class StorageStats(BaseModel):
    total_bytes: int
    book_count: int
    annotation_count: int
    bookmark_count: int = 0
