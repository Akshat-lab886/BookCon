"""Books: upload pipeline, dedupe, metadata extraction, CRUD, pagination (FR-LIB)."""

from __future__ import annotations

from tests.conftest import auth_headers, make_cbz, make_epub, register, sha256_hex


def _init(client, headers, data, filename="Ada Lovelace - The Test Book.epub", content_type="application/epub+zip"):
    resp = client.post(
        "/api/v1/books/initiate-upload",
        headers=headers,
        json={"filename": filename, "size_bytes": len(data), "sha256": sha256_hex(data), "content_type": content_type},
    )
    assert resp.status_code == 201
    return resp.json()


def test_full_upload_and_metadata_extraction(client):
    """M1 milestone: upload EPUB → metadata appears → second GET shows it."""
    tokens = register(client)
    headers = auth_headers(tokens)
    epub = make_epub(title="The Test Book", author="Ada Lovelace")
    body = _init(client, headers, epub)

    # PUT the bytes through the signed local upload URL (no auth header needed).
    put = client.put(body["upload_url"], content=epub, headers={"Content-Type": "application/epub+zip"})
    assert put.status_code == 200

    complete = client.post(f"/api/v1/books/{body['book_id']}/complete-upload", headers=headers)
    assert complete.status_code == 202

    first = client.get(f"/api/v1/books/{body['book_id']}", headers=headers).json()
    assert first["status"] == "ready"
    assert first["title"] == "The Test Book"
    assert first["authors"] == ["Ada Lovelace"]
    assert first["language"] == "en"
    assert first["publisher"] == "Test Press"
    assert first["published_date"] == "2024-01-01"
    assert first["cover_url"] is not None
    assert first["word_count"] and first["word_count"] > 0

    second = client.get(f"/api/v1/books/{body['book_id']}", headers=headers).json()
    assert second == first


def test_duplicate_upload_detected_by_hash(client):
    tokens = register(client)
    headers = auth_headers(tokens)
    epub = make_epub()
    first = _init(client, headers, epub)
    client.put(first["upload_url"], content=epub)
    client.post(f"/api/v1/books/{first['book_id']}/complete-upload", headers=headers)

    dup = _init(client, headers, epub)
    assert dup["outcome"] == "duplicate"
    assert dup["book_id"] == first["book_id"]


def test_hash_mismatch_rejected(client):
    tokens = register(client)
    headers = auth_headers(tokens)
    epub = make_epub()
    body = _init(client, headers, epub)
    resp = client.put(body["upload_url"], content=b"not-the-declared-content", headers={"Content-Type": "application/epub+zip"})
    assert resp.status_code == 400
    assert resp.json()["error"]["code"] == "hash_mismatch"


def test_unsupported_format_422(client):
    tokens = register(client)
    headers = auth_headers(tokens)
    resp = client.post(
        "/api/v1/books/initiate-upload",
        headers=headers,
        json={"filename": "book.mobi", "size_bytes": 10, "sha256": "a" * 64, "content_type": "application/x-mobipocket-ebook"},
    )
    assert resp.status_code == 422


def test_cbz_comicinfo_extraction(client):
    tokens = register(client)
    headers = auth_headers(tokens)
    cbz = make_cbz()
    body = _init(client, headers, cbz, filename="Test Comic #1.cbz", content_type="application/vnd.comicbook+zip")
    client.put(body["upload_url"], content=cbz, headers={"Content-Type": "application/vnd.comicbook+zip"})
    client.post(f"/api/v1/books/{body['book_id']}/complete-upload", headers=headers)
    book = client.get(f"/api/v1/books/{body['book_id']}", headers=headers).json()
    assert book["format"] == "cbz"
    assert book["status"] == "ready"
    assert book["title"] == "Test Comic #1"
    assert book["authors"] == ["Alan Moore"]
    assert book["publisher"] == "Test Comics"
    assert book["page_count"] == 3


def test_file_url_signed_download_roundtrip(client, uploaded_book):
    book, data = uploaded_book()
    headers = auth_headers(_login(client))

    url_resp = client.get(f"/api/v1/books/{book['id']}/file-url", headers=headers)
    assert url_resp.status_code == 200
    payload = url_resp.json()
    assert payload["expires_in"] > 0

    got = client.get(payload["url"])  # token-less signed URL
    assert got.status_code == 200
    assert got.content == data

    # Tampered signature → rejected.
    broken = payload["url"].replace("signature=", "signature=x")
    tampered = client.get(broken)
    assert tampered.status_code in (403, 404)


def test_cover_image_endpoint(client, uploaded_book):
    book, _ = uploaded_book()
    headers = auth_headers(_login(client))
    cover = client.get(f"/api/v1/books/{book['id']}/cover-image", headers=headers)
    assert cover.status_code == 200
    assert cover.headers["content-type"].startswith("image/webp")


def test_patch_metadata_series_tags_shelves(client, uploaded_book):
    book, _ = uploaded_book()
    headers = auth_headers(_login(client))

    shelf = client.post("/api/v1/shelves", headers=headers, json={"name": "To Read"}).json()
    tag = client.post("/api/v1/tags", headers=headers, json={"name": "sci-fi"}).json()

    patched = client.patch(
        f"/api/v1/books/{book['id']}",
        headers=headers,
        json={
            "description": "Edited description.",
            "series_name": "Foundation",
            "series_index": 2.5,
            "shelf_ids": [shelf["id"]],
            "tag_ids": [tag["id"]],
        },
    )
    assert patched.status_code == 200
    body = patched.json()
    assert body["description"] == "Edited description."
    assert body["series_index"] == 2.5
    assert body["shelf_ids"] == [shelf["id"]]
    assert body["tag_ids"] == [tag["id"]]

    series_list = client.get("/api/v1/series", headers=headers).json()
    assert any(s["name"] == "Foundation" for s in series_list)


def test_delete_is_tombstone(client, uploaded_book):
    book, _ = uploaded_book()
    headers = auth_headers(_login(client))
    deleted = client.delete(f"/api/v1/books/{book['id']}", headers=headers)
    assert deleted.status_code == 204

    gone = client.get(f"/api/v1/books/{book['id']}", headers=headers)
    assert gone.status_code == 404

    listed = client.get("/api/v1/books", headers=headers).json()
    assert all(b["id"] != book["id"] for b in listed["items"])

    tombstones = client.get("/api/v1/books?include_deleted=true", headers=headers).json()
    assert any(b["id"] == book["id"] and b["deleted_at"] for b in tombstones["items"])


def test_pagination_and_filters(client):
    register(client)
    headers = auth_headers(_login(client))
    for i in range(7):
        epub = make_epub(title=f"Book {i:02d}", author=f"Author {i % 3}")
        body = _init(client, headers, epub, filename=f"Author {i % 3} - Book {i:02d}.epub")
        client.put(body["upload_url"], content=epub)
        client.post(f"/api/v1/books/{body['book_id']}/complete-upload", headers=headers)

    page1 = client.get("/api/v1/books?limit=3&sort=title", headers=headers).json()
    assert len(page1["items"]) == 3 and page1["next_cursor"]
    page2 = client.get(f"/api/v1/books?limit=3&sort=title&cursor={page1['next_cursor']}", headers=headers).json()
    all_titles = [b["title"] for b in page1["items"]] + [b["title"] for b in page2["items"]]
    assert all_titles == sorted(all_titles)
    assert len(set(all_titles)) == len(all_titles), "no duplicates across pages"

    searched = client.get("/api/v1/books?q=Author 1", headers=headers).json()
    assert searched["items"] and all("1" in (b["authors"] or [""])[0] for b in searched["items"])

    by_format = client.get("/api/v1/books?format=pdf", headers=headers).json()
    assert by_format["items"] == []


def _login(client):
    resp = client.post(
        "/api/v1/auth/login", json={"email": "reader@example.com", "password": "hunter2boogaloo", "device_name": "CLI"}
    )
    if resp.status_code != 200:
        raise AssertionError(resp.text)
    return resp.json()
