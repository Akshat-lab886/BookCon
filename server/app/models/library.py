"""Library models: books, series, shelves, tags and memberships."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import (
    JSON,
    BigInteger,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, new_uuid, utcnow
from app.db.types import UTCDateTime

BOOK_FORMATS = ("epub", "pdf", "cbz", "cbr")
BOOK_STATUSES = ("processing", "ready", "failed")


class Series(Base):
    __tablename__ = "series"
    __table_args__ = (UniqueConstraint("user_id", "name"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    user_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(300), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        UTCDateTime, default=utcnow, onupdate=utcnow, nullable=False
    )
    deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)


class Shelf(Base):
    __tablename__ = "shelves"
    __table_args__ = (UniqueConstraint("user_id", "name"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    user_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(300), nullable=False)
    sort_position: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        UTCDateTime, default=utcnow, onupdate=utcnow, nullable=False
    )
    deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)


class Tag(Base):
    __tablename__ = "tags"
    __table_args__ = (UniqueConstraint("user_id", "name"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    user_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(300), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        UTCDateTime, default=utcnow, onupdate=utcnow, nullable=False
    )
    deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)


class BookShelf(Base):
    __tablename__ = "book_shelves"

    book_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("books.id", ondelete="CASCADE"), primary_key=True
    )
    shelf_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("shelves.id", ondelete="CASCADE"), primary_key=True
    )
    position: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)


class BookTag(Base):
    __tablename__ = "book_tags"

    book_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("books.id", ondelete="CASCADE"), primary_key=True
    )
    tag_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("tags.id", ondelete="CASCADE"), primary_key=True
    )


class Book(Base):
    __tablename__ = "books"
    __table_args__ = (
        Index("books_user_updated_idx", "user_id", "updated_at"),
        UniqueConstraint("user_id", "file_sha256", name="uq_books_user_file"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    user_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    file_sha256: Mapped[str | None] = mapped_column(String(64), ForeignKey("files.sha256"), nullable=True)
    cover_sha256: Mapped[str | None] = mapped_column(String(64), ForeignKey("files.sha256"), nullable=True)
    thumb_sha256: Mapped[str | None] = mapped_column(String(64), ForeignKey("files.sha256"), nullable=True)
    format: Mapped[str] = mapped_column(String(8), nullable=False)  # epub|pdf|cbz|cbr
    status: Mapped[str] = mapped_column(String(16), default="processing", nullable=False)
    status_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    title: Mapped[str] = mapped_column(String(500), nullable=False)
    authors_json: Mapped[list] = mapped_column(JSON, default=list, nullable=False)
    description: Mapped[str] = mapped_column(Text, default="", nullable=False)
    language: Mapped[str | None] = mapped_column(String(32), nullable=True)
    publisher: Mapped[str | None] = mapped_column(String(300), nullable=True)
    published_date: Mapped[str | None] = mapped_column(String(64), nullable=True)
    series_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("series.id", ondelete="SET NULL"), nullable=True
    )
    series_index: Mapped[float | None] = mapped_column(Float, nullable=True)
    word_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    page_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    added_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        UTCDateTime, default=utcnow, onupdate=utcnow, nullable=False
    )
    deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)

    series: Mapped[Series | None] = relationship()
    shelf_links: Mapped[list[BookShelf]] = relationship(cascade="all, delete-orphan")
    tag_links: Mapped[list[BookTag]] = relationship(cascade="all, delete-orphan")

    @property
    def authors(self) -> list[str]:
        return list(self.authors_json or [])

    @authors.setter
    def authors(self, value: list[str]) -> None:
        self.authors_json = list(value or [])
