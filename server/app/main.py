"""BookCon API — application factory (FastAPI)."""

from __future__ import annotations

import json
import logging
import time
import uuid
from contextvars import ContextVar

from fastapi import FastAPI, Request
from starlette.middleware.base import BaseHTTPMiddleware

from app.api.v1.ops import record_request
from app.core.config import get_settings
from app.core.errors import install_error_handlers

request_id_var: ContextVar[str] = ContextVar("request_id", default="-")


class JsonFormatter(logging.Formatter):
    EXTRA_KEYS = ("request_id", "user_id", "route", "latency_ms")

    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created)),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        for key in self.EXTRA_KEYS:
            if hasattr(record, key):
                payload[key] = getattr(record, key)
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return json.dumps(payload)


def _setup_logging() -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(logging.INFO)
    for name in ("uvicorn", "uvicorn.access", "uvicorn.error", "bookcon"):
        logging.getLogger(name).handlers = [handler]


class ObservabilityMiddleware(BaseHTTPMiddleware):
    """Structured request log + Prometheus counters (TRD §9)."""

    async def dispatch(self, request: Request, call_next):  # type: ignore[override]
        rid = uuid.uuid4().hex[:12]
        request_id_var.set(rid)
        start = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            duration = time.perf_counter() - start
            record_request(request.url.path, request.method, 500, duration)
            logging.getLogger("bookcon.http").exception(
                "%s %s crashed", request.method, request.url.path, extra={"request_id": rid}
            )
            raise
        duration = time.perf_counter() - start
        route = request.scope.get("route")
        path_template = getattr(route, "path", request.url.path)
        status = response.status_code
        user_id = getattr(request.state, "user_id", None) or "-"
        logging.getLogger("bookcon.http").info(
            "%s %s -> %s",
            request.method,
            path_template,
            status,
            extra={
                "request_id": rid,
                "user_id": user_id,
                "route": path_template,
                "latency_ms": round(duration * 1000, 2),
            },
        )
        record_request(path_template, request.method, status, duration)
        response.headers["X-Request-Id"] = rid
        return response


def create_app() -> FastAPI:
    settings = get_settings()
    if settings.jwt_secret == "dev-secret-change-me" and not settings.debug:
        raise RuntimeError(
            "Refusing to start: JWT_SECRET is the default development value. "
            "Set a long random JWT_SECRET (or DEBUG=1 for local development)."
        )
    settings = get_settings()
    _setup_logging()

    app = FastAPI(
        title="BookCon API",
        version="1.0.0",
        description="Self-hosted eBook library & sync server (docs/TRD.md §3).",
        docs_url="/api/v1/docs",
        openapi_url="/api/v1/openapi.json",
    )

    from app.api.v1.auth import devices_router, me_router
    from app.api.v1.auth import router as auth_router
    from app.api.v1.books import router as books_router
    from app.api.v1.ops import router as ops_router
    from app.api.v1.organize import router as organize_router
    from app.api.v1.reading import router as reading_router
    from app.api.v1.storage import router as storage_router
    from app.api.v1.sync import router as sync_router

    api_prefix = "/api/v1"
    app.include_router(auth_router, prefix=api_prefix)
    app.include_router(me_router, prefix=api_prefix)
    app.include_router(devices_router, prefix=api_prefix)
    app.include_router(books_router, prefix=api_prefix)
    app.include_router(organize_router, prefix=api_prefix)
    app.include_router(reading_router, prefix=api_prefix)
    app.include_router(sync_router, prefix=api_prefix)
    app.include_router(storage_router, prefix=api_prefix)

    # Ops endpoints at root per TRD (healthz/metrics outside /v1).
    app.include_router(ops_router)

    install_error_handlers(app)
    app.add_middleware(ObservabilityMiddleware)

    @app.get("/", include_in_schema=False)
    def root() -> dict:
        return {
            "name": "BookCon API",
            "version": "1.0.0",
            "docs": f"{settings.public_base_url}/api/v1/docs",
        }

    return app


app = create_app()
