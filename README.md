# BookCon

Your books. Your server. Every device.

BookCon is an open-source, self-hosted eBook reader & cloud library manager for Android — a BookFusion-style experience you own end-to-end. Upload your own EPUB/PDF/CBZ books, organize them into shelves, series and tags, read online or offline, and sync progress, bookmarks and highlights across all your Android devices through your own server.

- **No subscriptions. No payments. Ever.**
- **No third-party clouds:** books live in your Postgres + S3-compatible storage.
- **Offline-first:** the app works fully without network; sync is invisible until it matters.

| Doc | Purpose |
|---|---|
| [docs/PRD.md](docs/PRD.md) | Product requirements, feature specs w/ acceptance criteria |
| [docs/TRD.md](docs/TRD.md) | Architecture, data model, API, sync algorithm |
| [docs/PLAN.md](docs/PLAN.md) | Phased build plan (progress tracked) |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Self-host runbook: compose, Caddy TLS, backups |

## Quick start (self-host)

```bash
docker compose up -d          # postgres + minio + migrations + api
# API ready at http://localhost:8000/api/v1/docs
docker compose exec api python -m scripts.seed_demo   # optional demo account
```

Optional Google sign-in: set `GOOGLE_CLIENT_ID` env before starting.

## Server (local dev without Docker)

```bash
cd server
uv venv --python 3.12 && source .venv/bin/activate
uv pip install -e ".[dev]"
export DATABASE_URL=sqlite:///./bookcon.db STORAGE_BACKEND=local \
       LOCAL_STORAGE_DIR=./data JWT_SECRET=dev-secret-please-change
alembic upgrade head
uvicorn app.main:app --reload

pytest                                        # 42 integration + contract tests
python -m scripts.e2e_demo http://localhost:8000   # M1/M2 milestone E2E
```

Highlights: argon2id auth with rotating refresh tokens + device management,
content-addressed dedupe, EPUB/PDF/CBZ metadata + WebP thumbnails (in-process
worker), tombstoned LWW sync (`/sync/pull`, `/sync/push`), Prometheus
`/metrics` + JSON logs.

## Web client

A single-page web client ships with the API — no build step, no npm install.
It is served from the same process:

```
http://localhost:8000/app        # the app  (also /ui, which redirects)
http://localhost:8000/static/    # app.css + app.js
```

It shares the dark theme with the Android client (`#0d1117` surfaces, `#00c853`
selected-nav accent, `#ff6b35` import accent) and talks only to `/api/v1`, so
every visible control is backed by a working endpoint:

| Surface | Backed by |
|---|---|
| Auth (sign in / sign up / sign out) | `POST /auth/register`, `/auth/login`, `/auth/logout` |
| Home — stats, continue reading, recently added | `GET /books`, `GET /positions?book_ids=…` |
| Books — search, format chips, sort, grid | `GET /books?q=&format=&sort=` |
| Import (EPUB/PDF/CBZ/CBR) | `POST /books/initiate-upload` → `PUT /books/{id}/file` → `POST /books/{id}/complete-upload` |
| Book detail — metadata, download, delete | `GET /books/{id}`, `GET /books/{id}/file-url`, `DELETE /books/{id}` |
| Bookmarks — list, open, delete | `GET/DELETE /bookmarks` |
| Profile — display name, library counts, devices, revoke | `PATCH /me`, `GET /books`, `/shelves`, `/tags`, `/devices`, `DELETE /devices/{id}` |

Tokens are kept in `localStorage` and refreshed transparently on a 401. The
active tab and sort live in the URL hash, so `#/books` and `#/book/<id>` are
shareable deep links. Uploads need a secure context (HTTPS or localhost) for
`crypto.subtle` SHA-256 — over plain HTTP on a LAN address the client says so
explicitly instead of failing silently.

## Android app

Open `android/` in Android Studio (Ladybug+), let Gradle sync (version catalog
in `gradle/libs.versions.toml`), run on emulator/device. First launch asks for
your server URL (default `http://10.0.2.2:8000` for the emulator), then sign in
and import books.

Architecture (single module, layered):

```
com.bookcon.app
├── core/          SessionStore (encrypted tokens) · SettingsRepository (DataStore)
├── data/
│   ├── local/     Room: books, annotations, bookmarks, positions,
│   │              shelves/tags/series, sync cursors, upload queue
│   ├── remote/    Retrofit + kotlinx DTOs, AuthInterceptor, TokenAuthenticator
│   ├── repo/      AuthRepository
│   └── sync/      WorkManager: PushWorker → PullWorker, UploadWorker, DownloadWorker
├── reader/        ReaderEngine seam over Readium (EPUB/PDF/CBZ navigators)
└── ui/            auth · library · details · reader · annotations · settings
```

### Verifying the UI without a device

`ScreenRenderTest` renders the main screens to PNG on the JVM using Robolectric
native graphics, so the Compose UI can be checked (and screenshotted) with no
emulator or tablet attached:

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "*ScreenRenderTest*"
ls app/build/screenshots/   # android-library.png, android-home.png, …
```

It asserts the reference palette pixel-for-pixel — `#0d1117` pages, `#161b22`
cards, `#00c853` nav accent, `#ff6b35` Import button, the `#2d1b4e` Book Detail
gradient — and checks the reference copy through the semantics tree
("My Library", "N books", "Import books", "No bookmarks yet", the bottom-nav
labels). Two notes for anyone extending it:

- Robolectric resolves its `android-all` runtime jar against the JVM's
  `user.home`, so `build.gradle.kts` redirects it into `android/.robolectric-home/`
  (gitignored, ~334 MB). Delete it to force a fresh download.
- `captureToImage()` hangs under Robolectric's paused looper (`forceRedraw`
  never fires), so the test measures/lays out the decor view and draws it into a
  `Bitmap` directly.

Sync model (TRD §3.2): local Room is the source of truth with dirty flags;
`PushWorker` drains dirty rows (server LWW-accepts newer timestamps), then
`PullWorker` applies watermarked changes incl. tombstones. Uploads and offline
downloads survive app death via WorkManager.

## Status

v1 scope complete per [PLAN.md](docs/PLAN.md): backend MVP (Phase 1) is
implemented, tested and demoed end-to-end; the Android client (Phase 2) ships
the full source tree — build it in Android Studio (an Android SDK is required;
the repo was authored without one). iOS/web/Calibre/Kindle integrations are
deferred by design (Phase 3 backlog).
