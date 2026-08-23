"""Reading models: annotations, bookmarks, reading positions."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import JSON, Float, ForeignKey, Index, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, TimestampMixin, new_uuid, utcnow
from app.db.types import UTCDateTime

ANNOTATION_TYPES = ("highlight", "underline", "area")


class Annotation(Base):
    __tablename__ = "annotations"
    __table_args__ = (Index("annot_book_idx", "book_id", "updated_at"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    user_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    book_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("books.id", ondelete="CASCADE"), nullable=False
    )
    type: Mapped[str] = mapped_column(String(16), nullable=False)  # highlight|underline|area
    locator: Mapped[dict] = mapped_column(JSON, nullable=False)  # Readium Locator (+rect)
    color: Mapped[str] = mapped_column(String(32), default="yellow", nullable=False)
    note: Mapped[str] = mapped_column(Text, default="", nullable=False)
    annotation_tags_json: Mapped[list] = mapped_column(JSON, default=list, nullable=False)
    excerpt: Mapped[str] = mapped_column(Text, default="", nullable=False)
    device_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        UTCDateTime, default=utcnow, onupdate=utcnow, nullable=False
    )
    deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)

    @property
    def annotation_tags(self) -> list[str]:
        return list(self.annotation_tags_json or [])

    @annotation_tags.setter
    def annotation_tags(self, value: list[str]) -> None:
        self.annotation_tags_json = list(value or [])


class Bookmark(Base):
    __tablename__ = "bookmarks"
    __table_args__ = (Index("bookmark_book_idx", "book_id", "updated_at"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    user_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    book_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("books.id", ondelete="CASCADE"), nullable=False
    )
    locator: Mapped[dict] = mapped_column(JSON, nullable=False)
    label: Mapped[str] = mapped_column(Text, default="", nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        UTCDateTime, default=utcnow, onupdate=utcnow, nullable=False
    )
    deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)


class ReadingPosition(Base, TimestampMixin):
    """One row per (user, book); LWW by updated_at (server clock)."""

    __tablename__ = "reading_positions"

    user_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    book_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("books.id", ondelete="CASCADE"), primary_key=True
    )
    locator: Mapped[dict] = mapped_column(JSON, nullable=False)
    progress_percent: Mapped[float | None] = mapped_column(Float, nullable=True)
    device_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
