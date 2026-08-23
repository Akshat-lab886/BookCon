"""Metadata & cover extraction per TRD §4.

- EPUB: container.xml → OPF → title/authors/language/... + cover image
- PDF:  pypdf info + page count; page-1 render via pypdfium2
- CBZ:  ComicInfo.xml; first-image cover
- CBR:  requires an external `unrar`/`unar` binary (converted to CBZ in-memory)
Covers/thumbnails are normalized to ≤600px WebP via Pillow.
"""

from __future__ import annotations

import io
import re
import shutil
import zipfile
from dataclasses import dataclass, field
from xml.etree import ElementTree


@dataclass
class ExtractedMetadata:
    title: str | None = None
    authors: list[str] = field(default_factory=list)
    description: str | None = None
    language: str | None = None
    publisher: str | None = None
    published_date: str | None = None
    page_count: int | None = None
    word_count: int | None = None
    cover_bytes: bytes | None = None
    cover_content_type: str | None = None


FILENAME_RE = re.compile(r"^(?P<author>.+?)\s+-\s+(?P<title>.+)$")


def metadata_from_filename(filename: str) -> tuple[str | None, list[str]]:
    """`Author - Title.ext` heuristic (PRD LIB-2 fallback)."""
    stem = filename.rsplit("/", 1)[-1]
    stem = stem.rsplit(".", 1)[0].strip()
    m = FILENAME_RE.match(stem)
    if m:
        return m.group("title").strip(), [m.group("author").strip()]
    return stem or None, []


def make_thumb(image_bytes: bytes, max_px: int = 600) -> bytes:
    """Normalize any raster image to WebP, longest edge ≤ max_px."""
    from PIL import Image

    img = Image.open(io.BytesIO(image_bytes))
    if img.mode not in ("RGB", "RGBA"):
        img = img.convert("RGBA" if "A" in img.getbands() else "RGB")
    img.thumbnail((max_px, max_px), Image.LANCZOS)
    out = io.BytesIO()
    img.save(out, format="WEBP", quality=82, method=4)
    return out.getvalue()


def image_dimensions(image_bytes: bytes) -> tuple[int, int]:
    from PIL import Image

    with Image.open(io.BytesIO(image_bytes)) as img:
        return img.size


# --- EPUB ---------------------------------------------------------------------

_OPF_NS = "{http://www.idpf.org/2007/opf}"
_DC_NS = "{http://purl.org/dc/elements/1.1/}"
_CONT_NS = "{urn:oasis:names:tc:opendocument:xmlns:container}"


def _epub_opf_path(zf: zipfile.ZipFile) -> str | None:
    try:
        root = ElementTree.fromstring(zf.read("META-INF/container.xml"))
        rf = root.find(f"{_CONT_NS}rootfiles/{_CONT_NS}rootfile")
        if rf is not None:
            return rf.get("full-path")
    except (KeyError, ElementTree.ParseError):
        pass
    # Fallback: first *.opf in the archive
    for name in zf.namelist():
        if name.endswith(".opf"):
            return name
    return None


def _parse_authors(creator_text: str) -> list[str]:
    parts = re.split(r"\s*(?:,|&| and )\s*", creator_text)
    return [p.strip() for p in parts if p.strip()]


def extract_epub(data: bytes) -> ExtractedMetadata:
    meta = ExtractedMetadata()
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        opf_path = _epub_opf_path(zf)
        cover_href_by_id: dict[str, str] = {}
        cover_meta_id: str | None = None
        if opf_path:
            opf_dir = opf_path.rsplit("/", 1)[0] + "/" if "/" in opf_path else ""
            root = ElementTree.fromstring(zf.read(opf_path))
            md = root.find(f"{_OPF_NS}metadata")

            def _text(tag: str) -> str | None:
                el = md.find(f"{_DC_NS}{tag}") if md is not None else None
                if el is not None and el.text:
                    return el.text.strip()
                return None

            meta.title = _text("title")
            creator = _text("creator")
            if creator:
                meta.authors = _parse_authors(creator)
            meta.description = _text("description")
            meta.language = _text("language")
            meta.publisher = _text("publisher")
            meta.published_date = _text("date")

            if md is not None:
                for el in md.findall(f"{_OPF_NS}meta"):
                    if el.get("name") == "cover":
                        cover_meta_id = el.get("content")

            manifest = root.find(f"{_OPF_NS}manifest")
            if manifest is not None:
                for item in manifest.findall(f"{_OPF_NS}item"):
                    props = item.get("properties") or ""
                    iid = item.get("id") or ""
                    if "cover-image" in props or (cover_meta_id and iid == cover_meta_id):
                        href = item.get("href") or ""
                        cover_href_by_id[iid] = href

        # Word-count approximation from all text documents, with zip-bomb caps:
        # a hostile EPUB can declare tiny compressed entries that decompress to GBs.
        _MAX_ENTRY_UNCOMPRESSED = 32 * 1024 * 1024  # per file
        _MAX_TOTAL_UNCOMPRESSED = 128 * 1024 * 1024  # across the whole book
        total_uncompressed = 0
        words = 0
        for name in zf.namelist():
            if not name.lower().endswith((".xhtml", ".html", ".htm")):
                continue
            try:
                info = zf.getinfo(name)
            except KeyError:
                continue
            if info.file_size > _MAX_ENTRY_UNCOMPRESSED:
                continue
            total_uncompressed += info.file_size
            if total_uncompressed > _MAX_TOTAL_UNCOMPRESSED:
                break
            try:
                html = zf.read(name).decode("utf-8", errors="ignore")
            except Exception:
                continue
            text = re.sub(r"<[^>]+>", " ", html)
            words += len(text.split())
        if words:
            meta.word_count = words

        cover_bytes = None
        opf_dir = opf_path.rsplit("/", 1)[0] + "/" if (opf_path and "/" in opf_path) else ""
        for href in cover_href_by_id.values():
            href = re.sub(r"[?#].*$", "", href)
            norm = href.lstrip("/") if href.startswith("/") else opf_dir + href
            try:
                cover_bytes = zf.read(norm)
                break
            except KeyError:
                continue
        if cover_bytes is None:  # last resort: first image in the archive
            for name in sorted(zf.namelist()):
                if name.lower().endswith((".jpg", ".jpeg", ".png", ".webp")) and "cover" in name.lower():
                    cover_bytes = zf.read(name)
                    break
        if cover_bytes:
            meta.cover_bytes = cover_bytes
            meta.cover_content_type = "image/jpeg"
    return meta


# --- PDF ----------------------------------------------------------------------

def extract_pdf(data: bytes) -> ExtractedMetadata:
    from pypdf import PdfReader

    meta = ExtractedMetadata()
    reader = PdfReader(io.BytesIO(data))
    meta.page_count = len(reader.pages)
    info = reader.metadata or {}
    title = (info.get("/Title") or "").strip() if info else ""
    author = (info.get("/Author") or "").strip() if info else ""
    meta.title = title or None
    if author:
        meta.authors = _parse_authors(author)
    meta.language = (info.get("/Language") or None) if info else None

    # Render page 1 as the cover at ~300dpi equivalent width.
    try:
        import pypdfium2 as pdfium

        pdf = pdfium.PdfDocument(io.BytesIO(data))
        page = pdf[0]
        bitmap = page.render(scale=300 / 72)
        pil = bitmap.to_pil()
        buf = io.BytesIO()
        pil.save(buf, format="JPEG", quality=88)
        meta.cover_bytes = buf.getvalue()
        meta.cover_content_type = "image/jpeg"
        page.close()
        pdf.close()
    except Exception:  # rendering must never fail the import
        pass
    return meta


# --- CBZ / CBR ------------------------------------------------------------------

_IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp", ".gif")


def extract_cbz(data: bytes) -> ExtractedMetadata:
    meta = ExtractedMetadata()
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        names = sorted(zf.namelist())
        comic_info = next((n for n in names if n.lower().endswith("comicinfo.xml")), None)
        if comic_info:
            try:
                root = ElementTree.fromstring(zf.read(comic_info))

                def _txt(tag: str) -> str | None:
                    el = root.find(tag)
                    if el is not None and el.text:
                        return el.text.strip()
                    return None

                meta.title = _txt("Title")
                series = _txt("Series")
                if series and not meta.title:
                    number = _txt("Number")
                    meta.title = f"{series} #{number}" if number else series
                writer = _txt("Writer")
                if writer:
                    meta.authors = _parse_authors(writer)
                meta.publisher = _txt("Publisher")
                meta.published_date = _txt("Year")
                meta.language = _txt("LanguageISO") or _txt("Language")
                meta.description = _txt("Summary")
            except ElementTree.ParseError:
                pass
        first_image = next((n for n in names if n.lower().endswith(_IMAGE_EXTS)), None)
        if first_image:
            try:
                meta.cover_bytes = zf.read(first_image)
                meta.cover_content_type = "image/jpeg"
            except KeyError:
                pass
        if meta.page_count is None:
            meta.page_count = sum(1 for n in names if n.lower().endswith(_IMAGE_EXTS))
    return meta


def has_rar_tool() -> bool:
    return bool(shutil.which("unrar") or shutil.which("unar"))


def cbr_to_cbz_bytes(data: bytes) -> bytes:
    """Convert a RAR archive to CBZ using the system unrar/unar binary."""
    import os
    import subprocess
    import tempfile

    tool = shutil.which("unrar") or shutil.which("unar")
    if not tool:
        raise RuntimeError(
            "CBR support requires the 'unrar' or 'unar' binary on the server. "
            "Install it, or convert your comics to CBZ."
        )
    with tempfile.TemporaryDirectory() as tmpdir:
        rar_path = os.path.join(tmpdir, "book.cbr")
        outdir = os.path.join(tmpdir, "out")
        os.makedirs(outdir, exist_ok=True)
        with open(rar_path, "wb") as fh:
            fh.write(data)
        if tool.endswith("unrar"):
            subprocess.run([tool, "x", "-o+", rar_path, outdir + "/"], check=True, capture_output=True)
        else:
            subprocess.run([tool, rar_path, outdir], check=True, capture_output=True)
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as zf:
            for dirpath, _, filenames in os.walk(outdir):
                for fn in sorted(filenames):
                    fp = os.path.join(dirpath, fn)
                    zf.write(fp, os.path.relpath(fp, outdir))
        return buf.getvalue()


def extract_metadata(fmt: str, data: bytes, filename: str) -> ExtractedMetadata:
    """Top-level dispatcher; raises ValueError on unparseable content."""
    fmt = fmt.lower()
    if fmt == "epub":
        meta = extract_epub(data)
    elif fmt == "pdf":
        meta = extract_pdf(data)
    elif fmt == "cbz":
        meta = extract_cbz(data)
    elif fmt == "cbr":
        cbz_data = cbr_to_cbz_bytes(data)
        meta = extract_cbz(cbz_data)
    else:
        raise ValueError(f"Unsupported format: {fmt}")

    fb_title, fb_authors = metadata_from_filename(filename)
    if not meta.title:
        meta.title = fb_title
    if not meta.authors:
        meta.authors = fb_authors
    return meta
