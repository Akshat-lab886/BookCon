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
