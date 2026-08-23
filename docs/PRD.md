# BookCon — Product Requirements Document (PRD)

| | |
|---|---|
| **Product** | BookCon — eBook reader & cloud library manager |
| **Version** | 1.0 (Android) |
| **Status** | Approved for build |
| **Reference competitor** | BookFusion |
| **Out of scope forever (v1)** | Subscriptions / payments / paid tiers |

---

## 1. Vision

BookCon gives readers a single private home for their entire eBook collection: upload your own EPUB/PDF/CBZ books, organize them into shelves, series and tags, read them beautifully on any Android device — online or offline — and pick up exactly where you left off on any other device. All reading progress, bookmarks and highlights sync through the user's self-hosted BookCon server.

**One-line pitch:** *Your books. Your server. Every device.*

## 2. Goals

1. Replace BookFusion's core reader + cloud-library workflow for users who self-host.
2. Best-in-class reflowable EPUB reading experience on Android (Readium engine).
3. Offline-first: the app is fully usable with no network; sync is opportunistic background work.
4. Zero vendor lock-in: standard formats in, standard formats out; data exportable; server runs anywhere Docker runs.

### Non-goals (v1)
- No subscriptions, payments, or tiered limits of any kind.
- No web reader, iOS app, Calibre plugin, Send-to-Kindle, OPDS catalogs, store, social features, TTS/media-overlays (all deferred — see §9).

## 3. Target Users

| Persona | Description | Key needs |
|---|---|---|
| **Aisha — The Commuter Reader** | Reads 30+ novels/yr on phone + tablet, subway = no signal | Reliable offline library, instant resume across devices |
| **Dev — The Self-Hoster** | Runs HomeLab (Jellyfin, Immich…), wants books off Big Tech clouds | Docker deploy, own storage, no accounts with third parties |
| **Sam — The Student/Researcher** | Reads dense PDFs + EPUBs, highlights heavily | Robust highlighting/notes, export to Markdown for note apps |
| **Maya — The Comic Reader** | Large CBZ collection | Smooth paged comic reading, double-tap zoom |

## 4. Platform Scope

- **v1:** Android 8.0+ (API 26) phone & tablet. Portrait + landscape.
- **Server:** Self-hosted via Docker Compose (Postgres 16, MinIO/S3, FastAPI API, metadata worker).
- **Deferred:** iOS, Web reader, desktop.

---

## 5. Functional Requirements

Convention: `M` = must-have v1 · `S` = should-have if time permits · ID prefix per area. Acceptance criteria (AC) given per requirement.

### FR-AUTH — Accounts & Devices

- **AUTH-1 (M)** Sign up with email + password. AC: email verified format-checked client-side; password ≥ 8 chars; duplicate email rejected with clear error; auto sign-in after signup.
- **AUTH-2 (M)** Log in / log out. AC: JWT access token (15 min) + refresh token (30 d); refresh transparent to user; logout revokes tokens server-side.
- **AUTH-3 (M)** Sign in with Google OAuth. AC: native Google account picker; account linked by verified email; failure paths show readable errors.
- **AUTH-4 (M)** Multiple devices per account. AC: each install registers a device record (model, OS, app version, nickname); devices visible & removable from Settings → Devices; removing a device invalidates its refresh token.
- **AUTH-5 (S)** Password reset via email link. AC: single-use token valid 30 min; old sessions optionally kept.

### FR-LIB — Library Management

- **LIB-1 (M)** Import books. AC: multi-select EPUB/PDF/CBZ/CBR from device storage (SAF); files upload to server; import continues in background (WorkManager), survives app death; progress shown per file; failures retryable; duplicates detected by content hash and skipped (user informed).
- **LIB-2 (M)** Auto metadata extraction. AC: title, author(s), language extracted from EPUB OPF / PDF info / CBZ ComicInfo.xml where present; cover image extracted & stored as thumbnail; missing fields default from filename (`Author - Title.ext` heuristic).
- **LIB-3 (M)** Edit book metadata manually: title, authors, description, language, publisher, published date, series + series index, tags, cover replacement. AC: changes save locally immediately, sync within 5 s online.
- **LIB-4 (M)** Shelves/collections. AC: create/rename/delete shelf; add/remove books (a book may be in many shelves); reorder books inside a shelf (manual sort).
- **LIB-5 (M)** Series. AC: series name + numeric index on each book; dedicated "Series" view groups books by series sorted by index.
- **LIB-6 (M)** Tags. AC: free-form user tags; filter by tag; tag list managed centrally (rename/delete cascades).
- **LIB-7 (M)** Search & filters. AC: instant local search over title/authors/tags/description (<200 ms for 10k books); filter chips: format, read-state, tag, shelf, author; combined AND logic.
- **LIB-8 (M)** Sort. AC: recent activity, date added, title, author, progress, manual (within shelf).
- **LIB-9 (M)** Views. AC: grid (covers) & list modes; density option; "Continue Reading" carousel at top showing last-opened books with progress bars.
- **LIB-10 (M)** Book details screen. AC: cover, full metadata, progress, annotation count, actions: Read, Download/Remove-offline, Edit, Add-to-shelf, Export, Delete.
- **LIB-11 (M)** Delete book. AC: confirm dialog (also removes file from server + all annotations after second confirm); undo snackbar within 5 s for local removal.
- **LIB-12 (S)** Bulk actions: select-many → move-to-shelf, add-tag, download, delete.

### FR-RD — Reader

- **RD-1 (M)** Open EPUB 2/3 (reflowable + fixed-layout), PDF, CBZ/CBR. AC: opens from local file cache instantly; remote open downloads first with progress.
- **RD-2 (M)** Pagination modes: paginated horizontal (with page-turn animations: none/slide/curl-ish fade) and vertical scroll (EPUB). AC: mode persists per-book.
- **RD-3 (M)** Typography: font family (system serif/sans + 6 bundled open fonts), size, weight, line height, paragraph spacing, letter spacing, text alignment, publisher-defaults override toggle. AC: applies live; persists globally + optional per-book override.
- **RD-4 (M)** Margins: horizontal & vertical sliders. AC: persists.
- **RD-5 (M)** Themes: Light, Sepia, Dark, Black + custom themes (background, text color, accent). AC: theme follows system dark-mode by default when set to "Auto"; saved themes sync across devices.
- **RD-6 (M)** Brightness: reader-level brightness slider independent of system. AC: persists.
- **RD-7 (M)** Orientation: follow-system/portrait/landscape lock. AC: persists.
- **RD-8 (M)** Custom tap zones: configurable 3×3 grid mapping zones → previous-page/next-page/toggle-chrome/none. AC: editor UI with visual grid; left-handed presets included.
- **RD-9 (M)** Table of contents navigation with current-position indicator.
- **RD-10 (M)** Bookmarks: add at current position; bookmark list jump-to.
- **RD-11 (M)** In-book search. AC: results grouped by chapter; tap navigates & briefly highlights match.
- **RD-12 (M)** Progress: percentage + position saved continuously (debounced ≤ every 3 s and on exit). AC: reopen resumes exact position including scroll offset.
- **RD-13 (M)** Chrome (top/bottom bars): auto-hide; shows chapter, time or % remaining, battery (optional).
- **RD-14 (M)** Volume-key page turns (toggleable).
- **RD-15 (M)** Image handling in EPUB: tap image → full-screen gallery viewer with zoom; navigate between images of the chapter/book.
- **RD-16 (M)** Inline footnote/link preview popup (EPUB noterefs); external links open in browser with confirmation.
- **RD-17 (S)** PDF extras: fit-width/fit-page, continuous scroll, area/image highlight (see ANN-6).
- **RD-18 (S)** Keep-screen-on while reading (toggle).
- **RD-19 (Stretch)** TTS with sentence highlight (system engine).

### FR-ANN — Annotations

- **ANN-1 (M)** Text highlights: long-press select → color palette (≥5 colors) → highlight. AC: works in EPUB & PDF text layer; survives font/layout changes via locator anchoring.
- **ANN-2 (M)** Notes attached to any highlight. AC: quick-note dialog at creation; editable later.
- **ANN-3 (M)** Highlight tags. AC: assign user tags to individual annotations.
- **ANN-4 (M)** Manage: annotation list per book (sorted by location/date/color) and global "All annotations" screen with search/filter by book/tag/color/type; tap → jump to source.
- **ANN-5 (M)** Edit/delete highlights & notes. AC: deletion syncs as tombstone.
- **ANN-6 (S)** Area/image highlight in PDFs (rectangle capture with optional note). AC: renders overlay back on page at same rect.
- **ANN-7 (M)** Export annotations of a book to Markdown, CSV, plain text. AC: share sheet via Android share intent; includes book title, quoted text, note, tags, location reference.

### FR-SYNC — Cloud Sync

- **SYN-1 (M)** Account-scoped cloud library. AC: books uploaded once are downloadable to any signed-in device; covers/metadata identical everywhere.
- **SYN-2 (M)** Reading position sync. AC: opening the same book on another device resumes at latest position (Readium Locator payload); conflict resolved last-write-wins by timestamp; sync latency ≤ 5 s online.
- **SYN-3 (M)** Annotations, bookmarks, shelves, tags, series, metadata edits sync bidirectionally. AC: created/edited/deleted propagate to other devices ≤ 15 s online; tombstones prevent resurrection.
- **SYN-4 (M)** Cursor-based incremental pull + batch push. AC: device pulls only changes since its cursor; pushes only dirty local entities; idempotent retries.
- **SYN-5 (M)** Offline queue. AC: all mutations queue locally; WorkManager drains queue when connectivity returns; ordering preserved per entity.
- **SYN-6 (M)** File download-on-demand + offline pinning. AC: per-book "Available offline" toggle; downloads resumable; storage usage stats in settings; "remove offline copy" deletes local file but keeps annotations/progress.
- **SYN-7 (S)** Server-initiated change push via FCM-less polling fallback (periodic background pull every ≥ 15 min while charging/idle acceptable for v1).

### FR-SET — Settings & Misc

- **SET-1 (M)** Settings: appearance (theme), reader defaults, sync status ("Last synced…", force-sync), storage manager, devices, account (email, display name), about/licenses.
- **SET-2 (M)** OSS licenses screen.
- **SET-3 (S)** App lock with biometrics.

---

## 6. Non-Functional Requirements

| Category | Requirement |
|---|---|
| **Performance** | Cold start ≤ 2 s on mid-range device; cached book open ≤ 1 s; library scroll 60 fps @ 10k books; search < 200 ms local |
| **Reliability** | Sync engine crash-safe (WAL Room DB + queued mutations); uploads/downloads resumable; no data loss on force-stop |
| **Security** | TLS only; tokens in EncryptedSharedPreferences/Keystore; presigned URLs short-lived (≤ 15 min); password hashing bcrypt/argon2id server-side; no book content analytics ever |
| **Privacy** | Zero third-party trackers/analytics in app; server logs minimal (no reading content) |
| **Storage efficiency** | Covers stored as ≤ 600px WebP thumbnails; original files content-addressed & deduped server-side |
| **Compatibility** | Android 8–15, phones & tablets, RTL locales supported by Compose defaults |
| **Accessibility** | TalkBack-compatible navigation in library/settings; reader respects system font scale up to 200% |
| **i18n** | English v1; strings externalized for later translation |
| **Observability** | Server: structured JSON logs, /healthz, Prometheus metrics endpoint |

## 7. Success Metrics (self-hosted, privacy-preserving)

- Opt-in anonymous telemetry on server: DAU devices, books/library median, sync success rate ≥ 99.5%, crash-free session rate ≥ 99.7% (Crashlytics-free: local crash log export button).
- Qualitative: user completes first-upload→first-read flow unaided (measured via optional diagnostics).

## 8. UX Principles

1. Content first — chrome disappears while reading.
2. Never block reading: everything works offline; sync is invisible until it matters.
3. Destructive actions always confirmable + reversible where feasible.
4. Respect the OS (dark mode, font scale, back gesture, edge-to-edge).

## 9. Out-of-Scope Register (deferred roadmap)

| Feature | Target |
|---|---|
| iOS app | Phase 2 post-v1 (same API, Readium Swift Toolkit) |
| Web reader | Later (Readium Web/ts-toolkit against same API) |
| MOBI/AZW/DJVU/FB2 support | Later — server-side conversion worker (Calibre `ebook-convert`) |
| Calibre plugin sync | Later |
| Send-to-Kindle | Later |
| OPDS catalogs / free-books store | Later |
| Social (profiles/follow/sharing) | Later |
| TTS, media overlays | Stretch v1.x / later |
| Biometric app lock | S in v1 |

## 10. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Readium toolkit learning curve | Follow official TestApp patterns; isolate navigator behind `ReaderEngine` interface |
| Sync conflicts frustrate users | LWW with clear "position updated from another device" toast only when regression detected |
| Large PDFs memory pressure | Streaming render via PdfiumView paging; thumbnail cache LRU bounded |
| Self-host setup friction | Single `docker compose up`; healthchecks; seeded demo user script |
