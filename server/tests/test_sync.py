"""Sync engine tests: pull cursors + push LWW conflict matrix (TRD §3.2)."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from tests.conftest import auth_headers, register


def _login(client, email="reader@example.com"):
    resp = client.post("/api/v1/auth/login", json={"email": email, "password": "hunter2boogaloo", "device_name": "Sync"})
    assert resp.status_code == 200, resp.text
    return resp.json()


def _loc(href="ch1.xhtml") -> dict:
    return {"href": href, "type": "application/xhtml+xml", "locations": {"progression": 0.5}}


def test_pull_full_then_incremental(client, uploaded_book):
    register(client)
    headers = auth_headers(_login(client))
    book, _ = uploaded_book()

    # Full pull returns everything.
    full = client.post("/api/v1/sync/pull", headers=headers, json={"cursors": {}}).json()
    assert [b["id"] for b in full["books"]] == [book["id"]]
    assert full["cursors"]["books"]
    books_cursor = full["cursors"]["books"]

    # No changes → empty incremental pull.
    inc = client.post("/api/v1/sync/pull", headers=headers, json={"cursors": {"books": books_cursor}}).json()
    assert inc["books"] == []

    # A change shows up in the next pull.
    client.patch(f"/api/v1/books/{book['id']}", headers=headers, json={"title": "Renamed Remotely"})
    inc2 = client.post("/api/v1/sync/pull", headers=headers, json={"cursors": {"books": books_cursor}}).json()
    assert len(inc2["books"]) == 1
    assert inc2["books"][0]["title"] == "Renamed Remotely"


def test_pull_includes_annotations_and_positions(client, uploaded_book):
    tokens = register(client)
    headers = auth_headers(tokens)
    book, _ = uploaded_book()

    ann = client.post(
        "/api/v1/annotations",
        headers=headers,
        json={"book_id": book["id"], "type": "highlight", "locator": _loc(), "color": "green", "excerpt": "Hello"},
    )
    assert ann.status_code == 201
    pos = client.put(f"/api/v1/positions/{book['id']}", headers=headers, json={"locator": _loc(), "progress_percent": 42.0})
    assert pos.status_code == 200

    pulled = client.post("/api/v1/sync/pull", headers=headers, json={"cursors": {}}).json()
    assert len(pulled["annotations"]) == 1
    assert pulled["annotations"][0]["color"] == "green"
    assert len(pulled["positions"]) == 1
    assert pulled["positions"][0]["progress_percent"] == 42.0


def test_push_new_annotation_idempotent(client, uploaded_book):
    """SYN-4: pushes are idempotent — a replayed push leaves one unchanged row;
    the replay is LWW-ignored (authoritative returned), never duplicated."""
    register(client)
    headers = auth_headers(_login(client))
    book, _ = uploaded_book()
    ann_id = "11111111-2222-3333-4444-555555555555"
    payload = {
        "annotations": [{
            "id": ann_id, "book_id": book["id"], "type": "highlight", "locator": _loc(),
            "color": "blue", "note": "", "annotation_tags": [], "excerpt": "text",
            "client_updated_at": "2026-01-01T00:00:00+00:00",
        }]
    }
    first = client.post("/api/v1/sync/push", headers=headers, json=payload).json()
    assert ann_id in first["accepted"]["annotations"]
    second = client.post("/api/v1/sync/push", headers=headers, json=payload).json()
    # Replay is ignored by LWW but answered with the authoritative row:
    assert ann_id not in second["rejected"].get("annotations", [])
    assert any(a["id"] == ann_id for a in second["authoritative"]["annotations"])

    listed = client.get(f"/api/v1/annotations?book_id={book['id']}", headers=headers).json()
    assert len(listed) == 1
    assert listed[0]["excerpt"] == "text"


def test_lww_older_client_change_loses(client, uploaded_book):
    """Conflict matrix row 1: stale push is rejected, authoritative row returned."""
    register(client)
    headers = auth_headers(_login(client))
    book, _ = uploaded_book()

    server_row = client.post(
        "/api/v1/annotations",
        headers=headers,
        json={"book_id": book["id"], "type": "highlight", "locator": _loc(), "note": "server wins"},
    ).json()

    stale_ts = datetime.now(UTC) - timedelta(hours=1)
    pushed = client.post(
        "/api/v1/sync/push",
        headers=headers,
        json={
            "annotations": [{
                "id": server_row["id"], "book_id": book["id"], "type": "highlight",
                "locator": _loc(), "note": "stale device edit",
                "client_updated_at": stale_ts.isoformat(),
            }]
        },
    ).json()
    assert server_row["id"] not in pushed["accepted"].get("annotations", [])
    authoritative = pushed["authoritative"]["annotations"][0]
    assert authoritative["note"] == "server wins"

    final = client.get(f"/api/v1/annotations?book_id={book['id']}", headers=headers).json()[0]
    assert final["note"] == "server wins"


def test_lww_newer_client_change_wins(client, uploaded_book):
    """Conflict matrix row 2: newer push applies even over a newer server row."""
    register(client)
    headers = auth_headers(_login(client))
    book, _ = uploaded_book()

    server_row = client.post(
        "/api/v1/annotations",
        headers=headers,
        json={"book_id": book["id"], "type": "highlight", "locator": _loc(), "note": "old note"},
    ).json()

    future_ts = datetime.now(UTC) + timedelta(minutes=5)
    pushed = client.post(
        "/api/v1/sync/push",
        headers=headers,
        json={
            "annotations": [{
                "id": server_row["id"], "book_id": book["id"], "type": "highlight",
                "locator": _loc(), "note": "fresh offline edit",
                "client_updated_at": future_ts.isoformat(),
            }]
        },
    ).json()
    assert server_row["id"] in pushed["accepted"]["annotations"]

    final = client.get(f"/api/v1/annotations?book_id={book['id']}", headers=headers).json()[0]
    assert final["note"] == "fresh offline edit"


def test_position_single_row_lww(client, uploaded_book):
    """SYN-2: one row per (user,book); last write by timestamp wins."""
    register(client)
    headers = auth_headers(_login(client))
    book, _ = uploaded_book()

    t_old = datetime.now(UTC) - timedelta(hours=2)
    t_new = datetime.now(UTC)

    r1 = client.put(
        f"/api/v1/positions/{book['id']}",
        headers=headers,
        json={"locator": _loc(), "progress_percent": 10.0, "client_updated_at": t_new.isoformat()},
    )
    assert r1.status_code == 200
    r2 = client.put(
        f"/api/v1/positions/{book['id']}",
        headers=headers,
        json={"locator": _loc(), "progress_percent": 99.0, "client_updated_at": t_old.isoformat()},
    )
    stored = r2.json()
    assert stored["progress_percent"] == 10.0, "older position must not regress"

    t_newer = datetime.now(UTC) + timedelta(hours=3)
    client.put(
        f"/api/v1/positions/{book['id']}",
        headers=headers,
        json={"locator": {"href": "ch9.xhtml"}, "progress_percent": 88.5, "client_updated_at": t_newer.isoformat()},
    )
    positions = client.get(f"/api/v1/positions?book_ids={book['id']}", headers=headers).json()
    assert len(positions) == 1
    assert positions[0]["progress_percent"] == 88.5
    assert positions[0]["locator"]["href"] == "ch9.xhtml"


def test_tombstones_sync_and_prevent_resurrection(client, uploaded_book):
    """SYN-3: deletions propagate as tombstones; re-push of deleted row stays deleted."""
    register(client)
    headers = auth_headers(_login(client))
    book, _ = uploaded_book()

    ann = client.post(
        "/api/v1/annotations",
        headers=headers,
        json={"book_id": book["id"], "type": "underline", "locator": _loc()},
    ).json()
    client.delete(f"/api/v1/annotations/{ann['id']}", headers=headers)

    pulled = client.post("/api/v1/sync/pull", headers=headers, json={"cursors": {}}).json()
    tomb = next(a for a in pulled["annotations"] if a["id"] == ann["id"])
    assert tomb["deleted_at"] is not None

    # Offline device replays the creation → LWW keeps it deleted.
    replay = client.post(
        "/api/v1/sync/push",
        headers=headers,
        json={
            "annotations": [{
                **tomb, "deleted_at": None,
                "client_updated_at": (datetime.now(UTC) - timedelta(days=1)).isoformat(),
            }]
        },
    )
    assert replay.status_code == 200
    still = client.get(f"/api/v1/annotations?book_id={book['id']}", headers=headers).json()
    match = [a for a in still if a["id"] == ann["id"]][0]
    assert match["deleted_at"] is not None


def test_two_device_scenario_end_to_end(client, uploaded_book):
    """M2 server-side half: device A annotates → device B pulls it."""
    register(client)
    phone = auth_headers(_login(client))
    tablet = auth_headers(_login(client))  # same account, second session/device

    book, _ = uploaded_book()
    client.post(
        "/api/v1/annotations",
        headers=phone,
        json={"book_id": book["id"], "type": "highlight", "locator": _loc(), "note": "from phone"},
    )

    seen = client.post("/api/v1/sync/pull", headers=tablet, json={"cursors": {}}).json()
    notes = [a["note"] for a in seen["annotations"]]
    assert "from phone" in notes

    # Tablet edits its position; phone sees it on next pull.
    client.put(
        f"/api/v1/positions/{book['id']}",
        headers=tablet,
        json={"locator": {"href": "ch2.xhtml"}, "progress_percent": 15.0},
    )
    phone_pull = client.post("/api/v1/sync/pull", headers=phone, json={"cursors": {}}).json()
    assert any(p["progress_percent"] == 15.0 for p in phone_pull["positions"])


def test_push_shelves_tags_series_roundtrip(client, uploaded_book):
    register(client)
    headers = auth_headers(_login(client))
    uploaded_book()

    shelf_id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    tag_id = "11111111-2222-3333-4444-000000000000"
    series_id = "99999999-8888-7777-6666-555555555555"
    pushed = client.post(
        "/api/v1/sync/push",
        headers=headers,
        json={
            "shelves": [{"id": shelf_id, "name": "Offline Shelf", "sort_position": 1}],
            "tags": [{"id": tag_id, "name": "offline-tag"}],
            "series": [{"id": series_id, "name": "Offline Series"}],
        },
    ).json()
    assert shelf_id in pushed["accepted"]["shelves"]
    assert tag_id in pushed["accepted"]["tags"]
    assert series_id in pushed["accepted"]["series"]

    shelves = {s["name"] for s in client.get("/api/v1/shelves", headers=headers).json()}
    tags = {t["name"] for t in client.get("/api/v1/tags", headers=headers).json()}
    series = {s["name"] for s in client.get("/api/v1/series", headers=headers).json()}
    assert {"Offline Shelf"} <= shelves
    assert {"offline-tag"} <= tags
    assert {"Offline Series"} <= series
