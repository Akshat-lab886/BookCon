"""Organize endpoints: shelves/tags/series (FR-LIB 4-6)."""

from __future__ import annotations

from tests.conftest import auth_headers, register


def _login(client, email="reader@example.com"):
    resp = client.post(
        "/api/v1/auth/login", json={"email": email, "password": "hunter2boogaloo", "device_name": "T"}
    )
    assert resp.status_code == 200, resp.text
    return resp.json()


def test_shelf_crud_and_duplicate_name(client):
    tokens = register(client)
    headers = auth_headers(tokens)

    s1 = client.post("/api/v1/shelves", headers=headers, json={"name": "Favourites"})
    assert s1.status_code == 201
    dup = client.post("/api/v1/shelves", headers=headers, json={"name": "Favourites"})
    assert dup.status_code == 409

    patched = client.patch(f"/api/v1/shelves/{s1.json()['id']}", headers=headers, json={"name": "Favorites", "sort_position": 5})
    assert patched.json()["name"] == "Favorites"
    assert patched.json()["sort_position"] == 5

    listed = client.get("/api/v1/shelves", headers=headers).json()
    assert [s["name"] for s in listed] == ["Favorites"]

    deleted = client.delete(f"/api/v1/shelves/{s1.json()['id']}", headers=headers)
    assert deleted.status_code == 204
    after = client.get("/api/v1/shelves", headers=headers).json()
    assert after == []


def test_shelves_are_user_scoped(client):
    register(client)
    mine = client.post("/api/v1/shelves", headers=auth_headers(_login(client)), json={"name": "Mine"}).json()

    other = client.post(
        "/api/v1/auth/register",
        json={"email": "other@example.com", "password": "different-pass", "device_name": "O"},
    ).json()
    theirs = client.get("/api/v1/shelves", headers=auth_headers(other))
    assert theirs.json() == []
    cross = client.delete(f"/api/v1/shelves/{mine['id']}", headers=auth_headers(other))
    assert cross.status_code == 404


def test_tag_rename_and_delete_cascades(client, uploaded_book):
    register(client)
    headers = auth_headers(_login(client))
    book, _ = uploaded_book()

    tag = client.post("/api/v1/tags", headers=headers, json={"name": "old-name"}).json()
    client.patch(f"/api/v1/books/{book['id']}", headers=headers, json={"tag_ids": [tag["id"]]})
    renamed = client.patch(f"/api/v1/tags/{tag['id']}", headers=headers, json={"name": "new-name"})
    assert renamed.status_code == 200

    detail = client.get(f"/api/v1/books/{book['id']}", headers=headers).json()
    assert detail["tag_ids"] == [tag["id"]]

    # Delete cascades membership removal.
    client.delete(f"/api/v1/tags/{tag['id']}", headers=headers)
    after = client.get(f"/api/v1/books/{book['id']}", headers=headers).json()
    assert after["tag_ids"] == []


def test_series_grouping_sorted_by_index(client, uploaded_book):
    from tests.conftest import make_epub

    register(client)
    headers = auth_headers(_login(client))
    b1, _ = uploaded_book(filename="A - Book One.epub", data=make_epub(title="Book One", author="A"))
    b2, _ = uploaded_book(filename="B - Book Two.epub", data=make_epub(title="Book Two", author="B"))

    client.patch(f"/api/v1/books/{b2['id']}", headers=headers, json={"series_name": "Expanse", "series_index": 2.0})
    client.patch(f"/api/v1/books/{b1['id']}", headers=headers, json={"series_name": "Expanse", "series_index": 1.0})

    series = client.get("/api/v1/series", headers=headers).json()
    expanse = next(s for s in series if s["name"] == "Expanse")
    books_in_series = client.get(f"/api/v1/series/{expanse['id']}/books", headers=headers).json()
    assert [b["title"] for b in books_in_series] == ["Book One", "Book Two"]
