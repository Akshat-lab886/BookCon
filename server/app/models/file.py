"""Content-addressed blob registry (metadata about stored files)."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import BigInteger, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, utcnow
from app.db.types import UTCDateTime


class FileBlob(Base):
    __tablename__ = "files"

    sha256: Mapped[str] = mapped_column(String(64), primary_key=True)
    size_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False)
    content_type: Mapped[str] = mapped_column(String(255), default="application/octet-stream", nullable=False)
    kind: Mapped[str] = mapped_column(String(16), nullable=False)  # book | cover | thumb
    width: Mapped[int | None] = mapped_column(Integer, nullable=True)  # covers only
    height: Mapped[int | None] = mapped_column(Integer, nullable=True)
    ref_count: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, nullable=False)

    def __init__(self, **kwargs):  # allow omitting ref_count
        kwargs.setdefault("ref_count", 1)
        super().__init__(**kwargs)
