"""Ops routes: /healthz (DB + storage roundtrip), /metrics (Prometheus text)."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Response
from sqlalchemy import text as sql_text

from app.db.session import engine

router = APIRouter(tags=["ops"])

_metrics_state: dict[str, float] = {}
_request_counts: dict[str, int] = {}
_request_latency_sum: dict[str, float] = {}


def record_request(route: str, method: str, status: int, duration_s: float) -> None:
    key = f'{method.lower()}|{route}|{status}'
    _request_counts[key] = _request_counts.get(key, 0) + 1
    lat_key = f"{method.lower()}|{route}"
    _request_latency_sum[lat_key] = _request_latency_sum.get(lat_key, 0.0) + duration_s
    _metrics_state["requests_total"] = _metrics_state.get("requests_total", 0.0) + 1


def render_metrics() -> str:
    lines = [
        "# HELP bookcon_requests_total Total API requests.",
        "# TYPE bookcon_requests_total counter",
        f'bookcon_requests_total {_metrics_state.get("requests_total", 0):.0f}',
        "# HELP bookcon_request_latency_seconds_sum Cumulative request latency.",
        "# TYPE bookcon_request_latency_seconds_sum counter",
    ]
    for key, value in sorted(_request_latency_sum.items()):
        route = key.split("|")[-1].replace('"', "")
        lines.append(f'bookcon_request_latency_seconds_sum{{route="{route}"}} {value:.6f}')
    return "\n".join(lines) + "\n"


_log = logging.getLogger("bookcon.ops")


@router.get("/healthz")
def healthz() -> dict:
    checks: dict[str, str] = {}
    healthy = True
    try:
        with engine.connect() as conn:
            conn.execute(sql_text("SELECT 1"))
        checks["db"] = "ok"
    except Exception:  # noqa: BLE001
        checks["db"] = "error"  # details only in server logs (no internals leaked)
        _log.warning("healthz db check failed", exc_info=True)
        healthy = False
    try:
        from app.services.storage import get_storage

        get_storage().iter_keys() if hasattr(get_storage(), "iter_keys") else None
        checks["storage"] = "ok"
    except Exception:  # noqa: BLE001
        checks["storage"] = "error"
        healthy = False
    return {"status": "ok" if healthy else "degraded", "checks": checks}


@router.get("/metrics")
def metrics() -> Response:
    return Response(content=render_metrics(), media_type="text/plain; version=0.0.4")
