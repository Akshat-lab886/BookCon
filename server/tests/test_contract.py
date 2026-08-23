"""Contract test: implemented routes must match api-spec/openapi.yaml paths.

TRD §7: "contract tests asserting responses match openapi.yaml". This checks
the path surface; response schemas are covered by the integration suite.
"""

from __future__ import annotations

import pathlib

import yaml


def _spec_paths() -> set[str]:
    root = pathlib.Path(__file__).resolve().parents[2]
    spec = yaml.safe_load((root / "api-spec" / "openapi.yaml").read_text())
    return set(spec["paths"].keys())


def test_every_openapi_path_is_implemented(client):
    from app.main import app

    # FastAPI 0.14x wraps routers; derive from OpenAPI schema instead.
    schema_paths = set(app.openapi()["paths"])
    missing = []
    for spec_path in _spec_paths():
        candidates = {f"/api/v1{spec_path}", f"/api/v1{spec_path.rstrip('/')}", spec_path}
        if not candidates & schema_paths:
            missing.append(spec_path)
    assert missing == [], f"openapi.yaml paths not implemented: {missing}"


def test_healthz_matches_spec(client):
    resp = client.get("/healthz")
    assert resp.status_code == 200
