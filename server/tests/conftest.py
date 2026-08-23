"""Test fixtures: isolated SQLite DB + temp local storage per test.

Env vars are set BEFORE any app import so the engine binds to the temp DB.
"""

from __future__ import annotations

import os
import tempfile
import zipfile
from pathlib import Path

_TMP = Path(tempfile.mkdtemp(prefix="bookcon-tests-"))
os.environ["DATABASE_URL"] = f"sqlite:///{_TMP / 'test.db'}"
os.environ["STORAGE_BACKEND"] = "local"
os.environ["LOCAL_STORAGE_DIR"] = str(_TMP / "storage")
os.environ["JWT_SECRET"] = "test-secret-0123456789abcdef0123456789abcdef"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.db.base import Base  # noqa: E402
from app.db.session import SessionLocal, engine  # noqa: E402
from app.main import app  # noqa: E402
from app.services.ratelimit import reset_rate_limiter  # noqa: E402


@pytest.fixture(autouse=True)
def fresh_db():
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    reset_rate_limiter()
    # Wipe content-addressed storage so uploads never collide across tests.
    storage_dir = Path(os.environ["LOCAL_STORAGE_DIR"])
    if storage_dir.exists():
        import shutil

        shutil.rmtree(storage_dir)
    storage_dir.mkdir(parents=True, exist_ok=True)
    from app.services.storage import reset_storage_cache

    reset_storage_cache()
    yield
    SessionLocal().close()


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


@pytest.fixture
def db():
    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()


# --- helpers -------------------------------------------------------------------


def register(client: TestClient, email: str = "reader@example.com", password: str = "hunter2boogaloo",
             device: str = "Pixel 8") -> dict:
    resp = client.post(
        "/api/v1/auth/register",
        json={
            "email": email,
            "password": password,
            "device_name": device,
            "app_version": "1.0.0-test",
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


def login_or_register(client: TestClient, email: str = "reader@example.com",
                      password: str = "hunter2boogaloo") -> dict:
    """Tokens for the default test account; registers it on first use."""
    resp = client.post(
        "/api/v1/auth/login",
        json={"email": email, "password": password, "device_name": "Fixture"},
    )
    if resp.status_code == 200:
        return resp.json()
    return register(client, email=email, password=password)


def auth_headers(tokens: dict) -> dict:
    return {"Authorization": f"Bearer {tokens['access_token']}"}


def make_epub(title: str = "The Test Book", author: str = "Ada Lovelace",
              language: str = "en", cover_png: bytes | None = None) -> bytes:
    """Build a minimal but valid EPUB 3 in memory."""
    buf = __import__("io").BytesIO()
    container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
    opf = f"""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:1234</dc:identifier>
    <dc:title>{title}</dc:title>
    <dc:creator>{author}</dc:creator>
    <dc:language>{language}</dc:language>
    <dc:description>A book generated for automated testing.</dc:description>
    <dc:publisher>Test Press</dc:publisher>
    <dc:date>2024-01-01</dc:date>
    <meta name="cover" content="cover-image"/>
  </metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="cover-image" href="cover.png" media-type="image/png" properties="cover-image"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>"""
    ch1 = """<?xml version="1.0"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>c1</title></head>
<body><h1>Chapter One</h1><p>Hello wonderful reading world.</p></body></html>"""
    png = cover_png or _tiny_png()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("mimetype", "application/epub+zip")
        zf.writestr("META-INF/container.xml", container)
        zf.writestr("OEBPS/content.opf", opf)
        zf.writestr("OEBPS/ch1.xhtml", ch1)
        zf.writestr("OEBPS/cover.png", png)
    return buf.getvalue()


def _tiny_png() -> bytes:
    from PIL import Image

    img = Image.new("RGB", (8, 8), (200, 120, 40))
    out = __import__("io").BytesIO()
    img.save(out, format="PNG")
    return out.getvalue()


def make_cbz(title: str = "Test Comic #1", author: str = "Alan Moore") -> bytes:
    buf = __import__("io").BytesIO()
    comic_info = f"""<?xml version="1.0"?>
<ComicInfo><Title>{title}</Title><Series>Test Comic</Series><Number>1</Number>
<Writer>{author}</Writer><Publisher>Test Comics</Publisher><Year>2023</Year></ComicInfo>"""
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("ComicInfo.xml", comic_info)
        for i in range(3):
            zf.writestr(f"page_{i:03}.png", _tiny_png())
    return buf.getvalue()


def sha256_hex(data: bytes) -> str:
    import hashlib

    return hashlib.sha256(data).hexdigest()


@pytest.fixture
def uploaded_book(client: TestClient):
    """Factory fixture: uploads an EPUB **as the current test user**;
    returns (book_json, epub_bytes)."""
    def _upload(filename: str = "Ada Lovelace - The Test Book.epub",
                data: bytes | None = None) -> tuple[dict, bytes]:
        tokens = login_or_register(client)
        headers = auth_headers(tokens)
        data = data if data is not None else make_epub()
        digest = sha256_hex(data)
        init = client.post(
            "/api/v1/books/initiate-upload",
            headers=headers,
            json={
                "filename": filename,
                "size_bytes": len(data),
                "sha256": digest,
                "content_type": "application/epub+zip",
            },
        )
        assert init.status_code == 201, init.text
        body = init.json()
        assert body["outcome"] == "upload_required"
        put = client.put(body["upload_url"], content=data,
                         headers={"Content-Type": "application/epub+zip"})
        assert put.status_code == 200, put.text
        done = client.post(f"/api/v1/books/{body['book_id']}/complete-upload", headers=headers)
        assert done.status_code == 202, done.text
        detail = client.get(f"/api/v1/books/{body['book_id']}", headers=headers)
        assert detail.status_code == 200
        book = detail.json()
        assert book["status"] == "ready", book
        return book, data

    return _upload
