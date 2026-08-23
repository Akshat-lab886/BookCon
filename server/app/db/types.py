"""Portable UTC timestamp type: stores naive UTC, returns tz-aware."""

from __future__ import annotations

from datetime import UTC

from sqlalchemy import DateTime
from sqlalchemy.types import TypeDecorator


class UTCDateTime(TypeDecorator):
    """TimeZone-aware UTC datetime that works identically on SQLite & PostgreSQL."""

    impl = DateTime
    cache_ok = True

    def process_bind_param(self, value, dialect):  # noqa: ANN001
        if value is not None and value.tzinfo is not None:
            return value.astimezone(UTC).replace(tzinfo=None)  # store naive UTC
        return value

    def process_result_value(self, value, dialect):  # noqa: ANN001
        if value is not None and value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value
