# BookCon — Technical Requirements Document (TRD)

| | |
|---|---|
| **Product** | BookCon v1.0 — Android client + self-hosted sync server |
| **Related docs** | [PRD.md](PRD.md) · [PLAN.md](PLAN.md) · [../api-spec/openapi.yaml](../api-spec/openapi.yaml) |

---

## 1. System Architecture

```
                        ┌────────────────────────────────────────────┐
                        │                ANDROID APP                 │
                        │  Kotlin · Compose M3 · single-activity     │
                        │                                            │
                        │  UI Layer          Data Layer              │
                        │  ┌───────────┐   ┌──────────────────────┐  │
                        │  │ Library   │◄──│ Room DB (source of   │  │
                        │  │ Reader    │   │ truth, offline-first)│  │
                        │  │ Details   │   │ + dirty-flag queues  │  │
                        │  │ Settings  │   └──────┬───────┬───────┘  │
                        │  └───────────┘          │       │          │
                        │      Readium Kotlin     │  Retrofit/OkHttp │
                        │      Toolkit navigators │  (OpenAPI types) │
                        │      (EPUB/PDF/CBZ)     │  WorkManager:    │
                        │                         │  upload/sync/dl  │
                        └─────────────────────────┼───────┬──────────┘
                                                  │ HTTPS │ presigned S3
┌─────────────────────────────────────────────────▼───────▼─────────────────────┐
│                             SELF-HOSTED SERVER                                │
│                                                                               │
│  ┌────────────────┐   ┌──────────────────┐   ┌─────────────────────────────┐  │
│  │ API (FastAPI)  │──►│ PostgreSQL 16    │   │ Worker (arq / RQ)           │  │
│  │ JWT + OAuth2   │   │ users, books,    │   │ - metadata & cover extract  │
│  │ /v1/* JSON     │   │ annotations, ... │   │ - thumbnail generation      │
│  └───────┬────────┘   └──────────────────┘   └──────────────┬──────────────┘  │
│          │ presign / verify                                  │                │
│  ┌───────▼──────────────────────────────────────────────────▼──────────────┐  │
│  │ Object storage (S3 API): MinIO (dev/self-host) or any S3-compatible    │  │
│  │ bucket layout: files/{sha256}.{ext} · covers/{sha256}.webp             │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 1.1 Stack summary

| Tier | Choice | Rationale |
|---|---|---|
| Android | Kotlin 2.x, Jetpack Compose (M3), minSdk 26 | Modern, declarative; matches PRD perf targets |
| Reader engine | Readium Kotlin Toolkit 3.x (`readium:navigator`, `streamer`, `shared`) | Industry standard: EPUB2/3+FXL, PDF, CBZ, Locator format, search |
| Local persistence | Room + DataStore | Offline-first source of truth; typed settings |
| Background work | WorkManager | Survives app death; network constraints built-in |
| Networking | OkHttp/Retrofit + kotlinx.serialization | OpenAPI-generated DTOs keep clients honest |
| DI | Hilt | Standard for Android |
| Server framework | FastAPI (Python 3.12+) | Async, typed, auto-validating; OpenAPI first-class |
| ORM/migrations | SQLAlchemy 2.x + Alembic | Mature async support |
| DB | PostgreSQL 16 | Relational fit, JSONB for locators |
| Storage | S3-compatible (MinIO) | Self-hostable; content-addressed dedupe |
| Auth | JWT access/refresh + OAuth2 (Google) | Stateless API; device-scoped refresh tokens |
| Tests | pytest + httpx/TestClient; JUnit + Compose tests | CI-runnable |

### 1.2 Monorepo layout

```
bookcon/
├── docs/            # PRD, TRD, PLAN
├── api-spec/        # openapi.yaml — contract source of truth
├── server/
│   ├── app/
│   │   ├── core/        # config, security, errors
│   │   ├── db/          # session, base, migrations (alembic/)
│   │   ├── models/      # SQLAlchemy models
│   │   ├── schemas/     # Pydantic DTOs
│   │   ├── api/v1/      # routers: auth, users, devices, books,
│   │   │                # shelves, tags, series, sync, storage
│   │   ├── services/    # business logic + storage + metadata extraction
│   │   └── worker.py    # arq worker tasks
│   ├── alembic/
│   ├── tests/
│   ├── pyproject.toml
│   └── Dockerfile
├── android/
│   └── app/ (Gradle, Kotlin DSL, version catalog)
├── docker-compose.yml   # postgres + minio + api + worker (one command dev)
└── README.md
```

---

## 2. Data Model

### 2.1 ER overview

```
users 1─* devices
users 1─* books 1─* book_files? (single file row per book v1)
users 1─* shelves *─* books (book_shelves)
users 1─* tags    *─* books (book_tags)
users 1─* series  1─* books
books 1─* annotations
books 1─* bookmarks
books 1─* reading_positions (per device)
```

### 2.2 Tables (PostgreSQL DDL essence)

```sql
users (
  id UUID PK DEFAULT gen_random_uuid(),
  email CITEXT UNIQUE NOT NULL,
  password_hash TEXT NULL,              -- null when OAuth-only
  display_name TEXT NOT NULL DEFAULT '',
  avatar_url TEXT NULL,
  is_active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

oauth_identities (
  id UUID PK,
  user_id UUID FK users ON DELETE CASCADE,
  provider TEXT CHECK (provider IN ('google')),
  provider_account_id TEXT NOT NULL,
  UNIQUE (provider, provider_account_id)
);

devices (
  id UUID PK,
  user_id UUID FK users CASCADE,
  name TEXT NOT NULL,                   -- "Pixel 8"
  platform TEXT NOT NULL,               -- 'android'
  app_version TEXT NOT NULL,
  push_cursor BIGINT NOT NULL DEFAULT 0 -- sync cursor watermark
  last_seen_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, id)
);

refresh_tokens (
  id UUID PK,
  user_id UUID FK users CASCADE,
  device_id UUID FK devices CASCADE,
  token_hash TEXT NOT NULL UNIQUE,      -- sha256 of opaque token
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

files (                                 -- content-addressed blobs
  sha256 CHAR(64) PK,
  size_bytes BIGINT NOT NULL,
  content_type TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('book','cover','thumb')),
  width INT NULL, height INT NULL,      -- covers only
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

books (
  id UUID PK,
  user_id UUID FK users CASCADE,
  file_sha256 CHAR(64) FK files NULL,   -- null until upload completes
  format TEXT NOT NULL CHECK (format IN ('epub','pdf','cbz','cbr')),
  title TEXT NOT NULL,
  authors TEXT[] NOT NULL DEFAULT '{}',
  description TEXT NOT NULL DEFAULT '',
  language TEXT NULL,
  publisher TEXT NULL,
  published_date TEXT NULL,             -- free-form (YYYY / YYYY-MM / ISO)
  series_id UUID FK series NULL,
  series_index FLOAT NULL,
  cover_sha256 CHAR(64) FK files NULL,  -- extracted cover blob
  thumb_sha256 CHAR(64) FK files NULL,  -- ≤600px webp
  word_count INT NULL,
  page_count INT NULL,
  added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ NULL           -- tombstone
);
CREATE INDEX books_user_updated_idx ON books(user_id, updated_at DESC);

series (
  id UUID PK, user_id UUID FK users CASCADE,
  name TEXT NOT NULL, updated_at TIMESTAMPTZ,
  deleted_at TIMESTAMPTZ NULL,
  UNIQUE (user_id, name)
);

shelves (
  id UUID PK, user_id UUID FK users CASCADE,
  name TEXT NOT NULL,
  sort_position BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ, deleted_at TIMESTAMPTZ NULL,
  UNIQUE (user_id, name)
);
book_shelves (book_id FK CASCADE, shelf_id FK CASCADE, position BIGINT,
               PRIMARY KEY (book_id, shelf_id));

tags (
  id UUID PK, user_id UUID FK users CASCADE,
  name TEXT NOT NULL, updated_at TIMESTAMPTZ, deleted_at TIMESTAMPTZ NULL,
  UNIQUE (user_id, name)
);
book_tags (book_id FK CASCADE, tag_id FK CASCADE, PRIMARY KEY (book_id, tag_id));

annotations (
  id UUID PK,
  user_id UUID FK users CASCADE,
  book_id UUID FK books CASCADE,
  type TEXT NOT NULL CHECK (type IN ('highlight','underline','area')),
  locator JSONB NOT NULL,               -- Readium Locator (+rect for area)
  color TEXT NOT NULL DEFAULT 'yellow',
  note TEXT NOT NULL DEFAULT '',
  annotation_tags TEXT[] NOT NULL DEFAULT '{}',
  excerpt TEXT NOT NULL DEFAULT '',     -- quoted text for search/export
  device_id UUID NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ NULL           -- tombstone
);
CREATE INDEX annot_book_idx ON annotations(book_id, updated_at);

bookmarks (
  id UUID PK, user_id UUID FK, book_id UUID FK,
  locator JSONB NOT NULL, label TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ,
  deleted_at TIMESTAMPTZ NULL
);

reading_positions (                     -- one per (user, book); LWW
  user_id UUID, book_id UUID,
  locator JSONB NOT NULL,
  progress_percent FLOAT NULL,
  device_id UUID NULL,
  updated_at TIMESTAMPTZ,
  PRIMARY KEY (user_id, book_id)
);
```

**Sync metadata convention:** every syncable table carries `updated_at` (server-set on write), `deleted_at` tombstone. `updated_at` is **server clock**, authoritative for LWW.

### 2.3 Locator schema (cross-platform positions)

Both Readium toolkits serialize a common **Locator** object; stored verbatim in JSONB:

```json
{
  "href": "chapter_003.xhtml",
  "type": "application/xhtml+xml",
  "title": "Chapter 3",
  "locations": { "position": 412, "progression": 0.42, "totalProgression": 0.187 },
  "text": { "after": "...context..." }
}
```

Area annotations add `"locations": { ..., "rect": {"left":..,"top":..,"width":..,"height":..} }` (normalized PDF coords).

---

## 3. API Specification (v1)

Base URL `{host}/api/v1`. JSON bodies; errors follow RFC 7807-ish envelope:
`{"error": {"code": "validation_error", "message": "...", "details": [...]}}`.

Auth: `Authorization: Bearer <access-jwt>` (15 min). Refresh: `POST /auth/refresh` with http-only-safe opaque token from client secure store (30 d, device-bound, rotating).

### 3.1 Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/register` | email+password signup → tokens |
| POST | `/auth/login` | email+password → tokens (+device registration) |
| POST | `/auth/google` | Google ID token → tokens |
| POST | `/auth/refresh` | rotate refresh → new pair |
| POST | `/auth/logout` | revoke current refresh token |
| GET | `/me` | profile |
| PATCH | `/me` | display_name, avatar |
| GET/PATCH/DELETE | `/devices/{id}` | list/remove devices |
| GET | `/books` | paginated library (`?cursor&limit&sort&q&tag_id&shelf_id&format&include_deleted`) |
| POST | `/books/initiate-upload` | body `{filename,size,sha256}` → either `upload_url` (presigned PUT) or `existing_book` dedupe hit |
| POST | `/books/{id}/complete-upload` | triggers metadata worker; returns book w/ processing status |
| GET | `/books/{id}` | detail |
| PATCH | `/books/{id}` | edit metadata (incl. series/tags/shelf membership arrays) |
| DELETE | `/books/{id}` | tombstone delete |
| GET | `/books/{id}/file-url` | short-lived presigned GET |
| POST | `/books/{id}/cover` | multipart cover replace |
| GET | `/annotations?book_id&since` , `/annotations/global?q&since` | list |
| POST/PATCH/DELETE | `/annotations[/{id}]` | CRUD (DELETE = tombstone) |
| GET/POST/PATCH/DELETE | `/bookmarks[/{id}]` | same pattern |
| PUT | `/positions/{book_id}` | push reading position (LWW by server clock) |
| GET | `/positions?book_ids=a,b,c` | bulk pull for library badges |
| GET/POST/PATCH/DELETE | `/shelves[/{id}]`, `/tags[/{id}]`, `/series[/{id}]` | organize |
| POST | `/sync/pull` | `{entities:{...cursors}}` → changes since cursors + new cursors |
| POST | `/sync/push` | batch upsert/tombstone `{books?,annotations?,bookmarks?,positions?,shelves?,tags?,series?}` each item carries `client_updated_at` |
| GET | `/storage/stats` | usage bytes, counts |
| GET | `/healthz`, `/metrics` | ops |

### 3.2 Sync algorithm (normative)

**Push (client):**
1. Collect local rows where `dirty = 1` (set on every local write).
2. Group into `/sync/push` batches (≤500 rows/entity).
3. Each row includes `client_updated_at`; server compares against stored `updated_at`: if `client_updated_at > stored` apply & set `updated_at = now()` else ignore (LWW) and return authoritative row.
4. Response lists accepted/rejected ids + authoritative payloads → client clears dirty flags / overwrites locals.
5. Retries are safe: pushes idempotent (same LWW rule).

**Pull (client):**
1. Per-entity cursor = last seen monotonic `change_seq` (Postgres `BIGSERIAL change_log` trigger-maintained, or simpler: `updated_at` high-watermark + id tiebreak). v1 uses `updated_at` watermark per entity stored in `devices.push_cursor`-style local prefs.
2. `POST /sync/pull` returns changed rows (incl. tombstones) since cursors, capped at N=500/entity with `has_more`.
3. Client applies to Room inside transactions, never marks pulled rows dirty.

**Positions:** single-row-per-book LWW; pull included in `/sync/pull`.

**Files:** uploads/downloads always via presigned URLs; client re-requests URL if expired mid-transfer.

---

## 4. File & Metadata Pipeline

1. `initiate-upload` verifies sha256 against `files` table → dedupe hit returns existing mapping instantly.
2. Presigned `PUT` to `s3://bookcon-files/files/{sha256}.{ext}`.
3. `complete-upload` creates `books` row (status `processing`), enqueues worker task.
4. Worker (arq + Redis-free mode: Postgres LISTEN/NOTIFY via `procrastinate`-style loop; v1 uses simple in-process background task runner for zero extra infra):
   - EPUB: parse OPF (zip stream) → title/authors/language/description/publisher/date; extract cover image → normalize to WebP thumb ≤600px.
   - PDF: pypdf metadata + page count; render page-1 cover at 300dpi.
   - CBZ/CBR: ComicInfo.xml parse; first-image cover; CBR requires `unar` fallback → if unavailable, mark cover-less but readable (Readium handles CBR natively via streaming? — v1: convert CBR→CBZ in worker using `unrar` binary if present, else reject with clear error).
   - Sets status `ready`; failures → status `failed` + human-readable reason surfaced in app.
5. Cover/thumb blobs also content-addressed under `covers/…`.

## 5. Security Model

- Passwords: argon2id (memory 64MB, t=3, p=4).
- Access token JWT RS256 (server rotates keys), claims: `sub`, `did` (device), `exp`.
- Refresh tokens: 256-bit random, stored hashed; rotation on use; reuse-detection revokes family.
- OAuth Google: verify ID token signature against Google JWKS; link by verified email.
- Presigned URLs expire 15 min; bucket private; no public reads.
- Rate limits: login 10/min/IP; uploads 30/h/user.
- Transport: TLS enforced at reverse proxy (Caddy example in deploy docs); HSTS.
- Client secrets at rest: Android Keystore-backed EncryptedSharedPreferences.
- CORS locked to none (mobile-only); admin endpoints out of scope v1.

## 6. Performance Budgets (server)

| Operation | Budget (p95) |
|---|---|
| Login/register | 400 ms |
| `/books` page (50) | 150 ms @ 100k books/user-indexed |
| `/sync/pull` 500 rows | 250 ms |
| initiate/complete upload | 150 ms |
| Metadata worker per EPUB | ≤ 5 s |

## 7. Testing Strategy

- **Server:** pytest unit (services), integration (API against disposable Postgres via testcontainers-style fixtures), contract tests asserting responses match openapi.yaml.
- **Android:** unit (sync engine LWW logic, repository mappers), Robolectric for Room DAOs, Compose UI smoke tests; manual matrix on phone+tablet, API 26/31/35.
- **E2E:** script spins docker-compose stack, runs seeded scenario (upload→annotate→second-device pull) via API calls.

## 8. Deployment

- Dev/self-host: `docker compose up` (postgres, minio, migrate, api, worker) behind optional Caddy TLS profile.
- Env vars (12-factor): `DATABASE_URL`, `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `JWT_PRIVATE_KEY`, `GOOGLE_CLIENT_ID`, `PUBLIC_BASE_URL`.
- Backups: nightly `pg_dump` + MinIO mirror bucket sync (documented runbook).

## 9. Observability

Structured JSON logs (request id, user id, route, latency). `/metrics` Prometheus: request counters/histograms, worker queue depth, storage counts. `/healthz` checks DB + S3 roundtrip.
