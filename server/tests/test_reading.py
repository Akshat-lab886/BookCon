"""Annotations / bookmarks / positions CRUD (FR-ANN) + ops endpoints."""

from __future__ import annotations

from tests.conftest import auth_headers, register


def _setup(client, email="reader@example.com"):
    tokens = register(client, email=email)
    headers = auth_headers(tokens)
    epub_init = client.post(
        "/api/v1/books/initiate-upload",
        headers=headers,
        json={"filename": "A - B.epub", "size_bytes": 4, "sha256": "ab" * 32, "content_type": "application/epub+zip"},
    )
    book_id = epub_init.json()["book_id"]
    client.put(epub_init.json()["upload_url"], content=b"test")
    return tokens, headers, book_id


def _loc(href="ch1.xhtml") -> dict:
    return {"href": href, "type": "application/xhtml+xml"}


def test_annotation_lifecycle(client):
    tokens, headers, book_id = _setup(client)
    # Upload real bytes so complete-upload works; but annotations only need the row.
    created = client.post(
        "/api/v1/annotations",
        headers=headers,
        json={
            "book_id": book_id, "type": "highlight", "locator": _loc(),
            "color": "pink", "note": "insightful", "annotation_tags": ["theme"], "excerpt": "quoted text",
        },
    )
    assert created.status_code == 201
    ann = created.json()
    assert ann["color"] == "pink"
    assert ann["annotation_tags"] == ["theme"]

    patched = client.patch(f"/api/v1/annotations/{ann['id']}", headers=headers, json={"note": "edited", "color": "red"})
    assert patched.json()["note"] == "edited"
    assert patched.json()["color"] == "red"

    invalid_type = client.post(
        "/api/v1/annotations",
        headers=headers,
        json={"book_id": book_id, "type": "scribble", "locator": _loc()},
    )
    assert invalid_type.status_code == 422

    deleted = client.delete(f"/api/v1/annotations/{ann['id']}", headers=headers)
    assert deleted.status_code == 204
    patch_after_delete = client.patch(
        f"/api/v1/annotations/{ann['id']}", headers=headers, json={"note": "zombie"}
    )
    assert patch_after_delete.status_code == 410


def test_annotation_requires_known_book(client):
    tokens, headers, _ = _setup(client)
    resp = client.post(
        "/api/v1/annotations",
        headers=headers,
        json={"book_id": "00000000-0000-0000-0000-000000000000", "type": "highlight", "locator": _loc()},
    )
    assert resp.status_code == 404


def test_bookmark_lifecycle(client):
    tokens, headers, book_id = _setup(client)
    bm = client.post(
        "/api/v1/bookmarks",
        headers=headers,
        json={"book_id": book_id, "locator": _loc(), "label": "Chapter start"},
    )
    assert bm.status_code == 201
    renamed = client.patch(f"/api/v1/bookmarks/{bm.json()['id']}", headers=headers, json={"label": "Renamed"})
    assert renamed.json()["label"] == "Renamed"

    listed = client.get(f"/api/v1/bookmarks?book_id={book_id}", headers=headers).json()
    assert len(listed) == 1

    client.delete(f"/api/v1/bookmarks/{bm.json()['id']}", headers=headers)
    assert client.get(f"/api/v1/bookmarks?book_id={book_id}", headers=headers).json() == []


def test_positions_bulk_pull(client):
    tokens, headers, book_id = _setup(client)
    client.put(f"/api/v1/positions/{book_id}", headers=headers, json={"locator": _loc(), "progress_percent": 5.0})
    multi = client.get(f"/api/v1/positions?book_ids={book_id},00000000-0000-0000-0000-000000000001", headers=headers)
    assert multi.status_code == 200
    assert len(multi.json()) == 1


def test_healthz_and_metrics(client):
    health = client.get("/healthz")
    assert health.status_code == 200
    body = health.json()
    assert body["status"] in ("ok", "degraded")
    assert body["checks"]["db"] == "ok"

    client.get("/api/v1/me")  # generate at least one request
    metrics = client.get("/metrics")
    assert metrics.status_code == 200
    assert "bookcon_requests_total" in metrics.text


def test_storage_stats(client):
    tokens, headers, _ = _setup(client)
    stats = client.get("/api/v1/storage/stats", headers=headers)
    assert stats.status_code == 200
    body = stats.json()
    assert {"total_bytes", "book_count", "annotation_count"} <= set(body)


def test_error_envelope_shape(client):
    resp = client.get("/api/v1/books/does-not-exist")
    assert resp.status_code == 401  # no auth → unauthorized envelope first
    payload = resp.json()
    assert set(payload["error"].keys()) >= {"code", "message"}
