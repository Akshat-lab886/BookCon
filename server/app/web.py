"""BookCon Web UI — serves the single-page app.

The SPA is intentionally thin: it only calls endpoints that already exist
under ``/api/v1`` (auth, books CRUD, upload flow, bookmarks, shelves, tags,
series, devices), so every surface in the UI is backed by a working API.
"""

from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles

STATIC_DIR = Path(__file__).resolve().parent.parent / "static"


def mount_web(app: FastAPI) -> None:
    """Mount the web client at ``/app`` with its assets at ``/static``."""
    if not STATIC_DIR.is_dir():  # pragma: no cover - packaging guard
        return

    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="bookcon-static")

    @app.get("/app", include_in_schema=False)
    @app.get("/app/", include_in_schema=False)
    def web_app() -> FileResponse:
        return FileResponse(STATIC_DIR / "index.html")

    @app.get("/ui", include_in_schema=False)
    def web_redirect() -> RedirectResponse:
        return RedirectResponse("/app", status_code=307)
