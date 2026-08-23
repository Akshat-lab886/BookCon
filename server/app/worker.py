"""In-process background task runner (TRD §4: zero extra infra for v1).

FastAPI BackgroundTasks execute after the response is sent — good enough at
self-host scale. This module boundary mirrors an arq/procrastinate worker so a
standalone queue can be swapped in later without touching call sites.
"""

from __future__ import annotations

import logging

logger = logging.getLogger("bookcon.worker")


def run_metadata_now(book_id: str) -> None:
    """Synchronous execution (router schedules this via BackgroundTasks;
    tests, the seed script and the CLI call it directly)."""
    from app.db.session import SessionLocal
    from app.services.book_service import process_book_metadata

    db = SessionLocal()
    try:
        process_book_metadata(db, book_id)
        logger.info("metadata processed for book %s", book_id)
    finally:
        db.close()


if __name__ == "__main__":  # pragma: no cover
    import sys

    if len(sys.argv) != 2:
        print("usage: python -m app.worker <book_id>")
        raise SystemExit(2)
    run_metadata_now(sys.argv[1])
