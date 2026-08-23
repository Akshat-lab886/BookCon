# BookCon — Build Plan

Execution order, dependencies, and task breakdown. Estimates assume one full-time developer; "done" = acceptance criteria in PRD met.

## Phase 0 — Foundations ✅
- [x] PRD.md, TRD.md, PLAN.md
- [x] `api-spec/openapi.yaml` v1 contract
- [x] Monorepo scaffold: `server/`, `android/`, `docker-compose.yml`, README
- **Exit:** docs reviewed; compose stack boots. (Compose config validated; server boots natively — see Phase 1 exit.)

## Phase 1 — Backend MVP ✅
1. [x] Project skeleton: FastAPI app factory, settings via pydantic-settings, structured logging, error envelope.
2. [x] Alembic init + migration for full schema (§2.2 TRD).
3. [x] Auth: register/login/refresh/logout, argon2id, device registration, Google OAuth verify.
4. [x] Storage service: S3 client + local backend, presign put/get / signed media URLs, content-address paths.
5. [x] Books: initiate/complete upload, dedupe, list/detail/patch/delete, file-url.
6. [x] Metadata worker: EPUB OPF + cover extraction, PDF pypdf(+pdfium cover render), CBZ ComicInfo; thumb generation (Pillow WebP ≤600px).
7. [x] Organize endpoints: shelves/tags/series CRUD + membership on book patch.
8. [x] Annotations, bookmarks, positions CRUD.
9. [x] `/sync/pull` + `/sync/push` LWW engine + tests for conflict matrix.
10. [x] Integration test suite green (40 tests); seed script (`scripts/seed_demo.py`).
**Exit:** M1 E2E script passes (`scripts/e2e_demo.py` against live uvicorn; docker-compose stack provided).

## Phase 2 — Android App ✅ (source tree complete; build in Android Studio)
1. [x] Gradle scaffold: version catalog, Hilt, Room schema export. (CI lint+unit: deferred — no CI in repo scope.)
2. [x] Core data layer: Room entities/DAOs mirroring server model, dirty flags, OpenAPI DTOs + Retrofit services, OkHttp auth interceptor w/ refresh rotation.
3. [x] Sync engine: WorkManager workers (upload, push, pull, download-file), LWW merge per TRD §3.2.
4. [x] Auth UI: login/register, device naming, session gate (Google Credential Manager = disabled stub pending GOOGLE_CLIENT_ID config).
5. [x] Library UI: grid/list, Continue Reading carousel, search/filter/sort, shelves & series & tags tabs, bulk select, book details + edit sheet.
6. [x] Import flow: SAF picker → staged copy → sha256 → upload queue → WorkManager → optimistic local insert.
7. [x] Reader: Readium EPUB navigator behind `ReaderEngine` seam (PDF/CBZ adapters stubbed pending navigator artifacts); chrome, TOC, bookmarks, search UI; RD-2…RD-16 settings persisted in DataStore.
8. [x] Annotations: selection popup, color palette, note editor, side rail w/ jump-back, Markdown/CSV/TXT export via share sheet.
9. [x] Offline: download pins via WorkManager, storage manager screen, remove-offline.
10. [x] Settings: devices management, sync status/force-sync, appearance, licenses.
11. [ ] Polish pass: perf (baseline profiles), TalkBack sweep, RTL check, crash-log export — **deferred to post-source-review** (requires a real device build).
**Exit:** source tree complete and internally consistent (static review); on-device M2 demo requires an Android SDK build — runbook in README.

## Phase 3 — Post-v1 backlog (priority order)
1. iOS app (Swift toolkit; API unchanged).
2. Server-side format conversion worker (MOBI/AZW/FB2/DJVU→EPUB).
3. Web reader (Readium Web) reusing API.
4. Calibre plugin; Send-to-Kindle; OPDS; social.

## Milestone demos
| Milestone | Demo |
|---|---|
| M1 end of Ph1 | Upload EPUB via curl → metadata appears → second GET shows it |
| M2 mid Ph2 | Android offline: airplane-mode annotate → online → second device sees it |
| GA | Fresh install → login → import 100 books → read synced position on tablet |
