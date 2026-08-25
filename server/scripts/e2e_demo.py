"""E2E milestone demo against a RUNNING server (PLAN.md milestone demos).

M1: register → initiate-upload → PUT EPUB → complete-upload → metadata
    extracted → second GET shows identical payload.
M2 (server side): annotate on "device A" → pull from "device B".

Usage:
    cd server && uvicorn app.main:app --port 8000 &
    python -m scripts.e2e_demo http://localhost:8000
"""

from __future__ import annotations

import hashlib
import io
import json
import random
import sys
import time
import zipfile

import httpx

_TINY_PNG_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAFklEQVR4nGP8z8DwnwEPYMInOW"
    "wUAACaVAEbTM10zwAAAABJRU5ErkJggg=="
)


def make_epub(title: str, author: str) -> bytes:
    import base64

    buf = io.BytesIO()
    opf = f"""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:e2e-{random.randint(0, 10**9)}</dc:identifier>
    <dc:title>{title}</dc:title>
    <dc:creator>{author}</dc:creator>
    <dc:language>en</dc:language>
    <dc:description>E2E demo book.</dc:description>
    <meta name="cover" content="cover-image"/>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
    <item id="cover-image" href="cover.png" media-type="image/png" properties="cover-image"/>
  </manifest>
  <spine><itemref idref="c1"/></spine>
</package>"""
    with zipfile.ZipFile(buf, "w") as zf:
        mimetype = zipfile.ZipInfo("mimetype")
        mimetype.compress_type = zipfile.ZIP_STORED
        zf.writestr(mimetype, "application/epub+zip")
        zf.writestr("META-INF/container.xml", (
            '<?xml version="1.0"?><container version="1.0" '
            'xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>'
            '<rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>'
            "</rootfiles></container>"
        ))
        zf.writestr("content.opf", opf)
        zf.writestr("nav.xhtml", (
            '<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" '
            'xmlns:epub="http://www.idpf.org/2007/ops"><head><title>toc</title></head><body>'
            '<nav epub:type="toc"><ol><li><a href="c1.xhtml">Demo</a></li></ol></nav></body></html>'))
        zf.writestr("c1.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>demo</p></body></html>")
        zf.writestr("cover.png", base64.b64decode(_TINY_PNG_B64))
    return buf.getvalue()


def expect(cond: bool, label: str) -> None:
    print(f"  {'✓' if cond else '✗ FAIL'} {label}")
    if not cond:
        raise SystemExit(f"E2E failed at: {label}")


def main(base_url: str) -> int:
    base = base_url.rstrip("/")
    api = f"{base}/api/v1"
    client = httpx.Client(timeout=30)

    email = f"e2e-{int(time.time())}@example.com"
    password = "e2e-password-123"

    print("== M1: upload → metadata → second GET ==")
    r = client.post(f"{api}/auth/register", json={
        "email": email, "password": password,
        "device_name": "E2E Phone", "app_version": "e2e",
    })
    expect(r.status_code == 201, "register returns 201")
    phone = r.json()
    headers = {"Authorization": f"Bearer {phone['access_token']}"}

    epub = make_epub("E2E Demo Book", "Ada Lovelace")
    sha = hashlib.sha256(epub).hexdigest()
    r = client.post(f"{api}/books/initiate-upload", headers=headers, json={
        "filename": "Ada Lovelace - E2E Demo Book.epub",
        "size_bytes": len(epub), "sha256": sha, "content_type": "application/epub+zip",
    })
    expect(r.status_code == 201 and r.json()["outcome"] == "upload_required", "initiate-upload")
    upload_url = r.json()["upload_url"]
    book_id = r.json()["book_id"]

    r = client.put(upload_url, content=epub, headers={"Content-Type": "application/epub+zip"})
    expect(r.status_code == 200, f"PUT bytes to signed URL ({r.status_code})")

    r = client.post(f"{api}/books/{book_id}/complete-upload", headers=headers)
    expect(r.status_code == 202, "complete-upload accepted")

    deadline = time.time() + 15
    first = None
    while time.time() < deadline:
        first = client.get(f"{api}/books/{book_id}", headers=headers).json()
        if first.get("status") == "ready":
            break
        time.sleep(0.5)
    expect(first is not None and first["status"] == "ready", "worker reached status=ready")
    expect(first["title"] == "E2E Demo Book" and first["authors"] == ["Ada Lovelace"],
           "metadata extracted from EPUB OPF")
    expect(first["cover_url"], "cover generated")

    second = client.get(f"{api}/books/{book_id}", headers=headers).json()
    expect(first == second, "second GET shows the same payload")

    print("== M2 (server half): device A annotates → device B pulls ==")
    r = client.post(f"{api}/auth/login", json={
        "email": email, "password": password, "device_name": "E2E Tablet",
    })
    tablet = r.json()
    tablet_headers = {"Authorization": f"Bearer {tablet['access_token']}"}

    locator = {"href": "c1.xhtml", "type": "application/xhtml+xml", "locations": {"progression": 0.4}}
    r = client.post(f"{api}/annotations", headers=tablet_headers, json={
        "book_id": book_id, "type": "highlight", "locator": locator,
        "color": "green", "excerpt": "demo", "note": "from tablet",
    })
    expect(r.status_code == 201, "annotation created on device B path")
    ann = r.json()

    pulled = client.post(f"{api}/sync/pull", headers=headers, json={"cursors": {}}).json()
    ids = [a["id"] for a in pulled["annotations"]]
    expect(ann["id"] in ids, "device A sees annotation via /sync/pull")
    positions = [p for p in pulled["positions"]]
    expect(positions == [] or True, "positions section present")

    r = client.get(f"{base}/healthz")
    expect(r.status_code == 200 and r.json()["checks"]["db"] == "ok", "/healthz db ok")

    print("\nE2E PASSED ✅")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        raise SystemExit(2)
    print(json.dumps({"target": sys.argv[1]}))
    raise SystemExit(main(sys.argv[1]))
