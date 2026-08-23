"""SQLAlchemy declarative base with common conventions.

UUIDs are stored as CHAR(36) for cross-database portability (dev SQLite /
prod PostgreSQL). Timestamps are timezone-aware, set Python-side (UTC).
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from app.db.types import UTCDateTime


def utcnow() -> datetime:
    return datetime.now(UTC)


def new_uuid() -> str:
    return str(uuid.uuid4())


class Base(DeclarativeBase):
    pass


class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(
        UTCDateTime, default=utcnow, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        UTCDateTime, default=utcnow, onupdate=utcnow, nullable=False
    )


__all__ = ["Base", "TimestampMixin", "new_uuid", "utcnow"]
