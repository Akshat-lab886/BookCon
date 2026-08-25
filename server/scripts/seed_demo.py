"""Seed a demo user with sample books for fresh self-host installs.

Usage:
    cd server
    python -m scripts.seed_demo                       # default demo account
    python -m scripts.seed_demo --email me@x.com --password secret123
"""

from __future__ import annotations

import argparse
import hashlib
import io
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sqlalchemy import select  # noqa: E402

from app.db.session import SessionLocal  # noqa: E402
from app.models import Book, User  # noqa: E402
from app.schemas.auth import DeviceInfoIn  # noqa: E402
from app.services.auth_service import register as svc_register  # noqa: E402
from app.services.book_service import (  # noqa: E402
    initiate_upload,
    process_book_metadata,
    store_upload_bytes,
)


def make_epub_bytes(title: str, author: str, chapter_text: str) -> bytes:
    """Spec-compliant EPUB 3: stored-first mimetype, dcterms:modified, nav doc.

    Readium rejects packages missing `dcterms:modified` (mandatory in EPUB 3),
    which yields an empty readingOrder and a blank reader page.
    """
    buf = io.BytesIO()
    opf = f"""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid" xml:lang="en">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:{hashlib.sha256(title.encode()).hexdigest()[:12]}</dc:identifier>
    <dc:title>{title}</dc:title>
    <dc:creator>{author}</dc:creator>
    <dc:language>en</dc:language>
    <dc:description>Seeded demo book.</dc:description>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/></spine>
</package>"""
    nav = f"""<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>{title}</title></head>
<body><nav epub:type="toc"><h1>Table of contents</h1><ol><li><a href="c1.xhtml">{title}</a></li></ol></nav></body>
</html>"""
    ch = f"""<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>{title}</title></head>
<body><h1>{title}</h1><p>{chapter_text}</p></body></html>"""
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
        zf.writestr("nav.xhtml", nav)
        zf.writestr("c1.xhtml", ch)
    return buf.getvalue()


SAMPLES = [
    ("The Time Machine", "H.G. Wells", "The Time Traveller proceeded into the far future."),
    ("Pride and Prejudice", "Jane Austen", "It is a truth universally acknowledged..."),
    ("A Study in Scarlet", "Arthur Conan Doyle", "In the year 1878 I took my degree..."),
]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--email", default="demo@bookcon.example")  # .local is rejected by email validation at login!
    parser.add_argument("--password", default="demo-password-123")
    parser.add_argument("--display-name", default="Demo Reader")
    args = parser.parse_args()

    db = SessionLocal()
    try:
        user = db.scalar(select(User).where(User.email == args.email))
        if user is None:
            svc_register(
                db, args.email, args.password, args.display_name,
                DeviceInfoIn(device_name="Seed Script", app_version="seed"),
            )
            user = db.scalar(select(User).where(User.email == args.email))
            print(f"created user {args.email}")
        else:
            print(f"user {args.email} already exists")

        existing_shas = {
            b.file_sha256 for b in db.scalars(select(Book).where(Book.user_id == user.id))
        }
        created = 0
        for title, author, text in SAMPLES:
            data = make_epub_bytes(title, author, text)
            sha = hashlib.sha256(data).hexdigest()
            if sha in existing_shas:
                print(f"  skip (already present): {title}")
                continue
            result = initiate_upload(db, user.id, f"{author} - {title}.epub", len(data), sha, "application/epub+zip")
            store_upload_bytes(db, sha, "epub", data, "application/epub+zip")
            process_book_metadata(db, result["book_id"])
            print(f"  seeded: {title} — {author}")
            created += 1
        print(f"done; {created} new books")
        return 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
