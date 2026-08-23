# BookCon Server — Deployment Runbook

## 1. Quick start (Docker Compose)

```bash
docker compose up -d
# postgres + minio (+bucket init) + alembic migrate + api
curl http://localhost:8000/healthz
open http://localhost:8000/api/v1/docs
```

Seed a demo account:

```bash
docker compose exec api python -m scripts.seed_demo
# demo@bookcon.example / demo-password-123
```

## 2. TLS behind Caddy (recommended for phone access)

```caddyfile
books.example.com {
    reverse_proxy api:8000
    header Strict-Transport-Security "max-age=31536000; includeSubDomains; preload"
}
```

Run Caddy on the same network:

```bash
docker run -d --name caddy --network bookcon_default \
  -p 80:80 -p 443:443 \
  -v caddy_data:/data \
  -v $PWD/Caddyfile:/etc/caddy/Caddyfile caddy:latest
```

Set `PUBLIC_BASE_URL=https://books.example.com` before starting the API so presigned /
signed media URLs use the public host.

## 3. Environment variables

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | `postgresql+psycopg://user:pass@postgres:5432/bookcon` |
| `S3_ENDPOINT_URL` / `S3_BUCKET` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` | Object storage (`STORAGE_BACKEND=s3`) |
| `STORAGE_BACKEND` | `s3` (compose default) or `local` |
| `LOCAL_STORAGE_DIR` | Used when `STORAGE_BACKEND=local` |
| `JWT_SECRET` | HS256 signing secret — **set a long random value** |
| `GOOGLE_CLIENT_ID` / `GOOGLE_ANDROID_CLIENT_ID` | Enables `POST /auth/google` |
| `PUBLIC_BASE_URL` | Public base for media URLs |

## 4. Backups

Nightly cron (host):

```bash
docker compose exec -T postgres pg_dump -U bookcon bookcon | gzip > backups/bookcon-$(date +%F).sql.gz
docker run --rm --network bookcon_default -v $PWD/backups:/backups \
  minio/mc sh -c "mc alias set local http://minio:9000 bookcon bookcon-secret && mc mirror local/bookcon /backups/blobs"
```

Restore: recreate containers, restore DB with `pg_restore`, then `mc mirror --overwrite`
blobs back into the bucket.

## 5. Operations

- `/healthz` — DB + storage roundtrip (used by compose healthcheck).
- `/metrics` — Prometheus text format (request counters/latency).
- Logs are structured JSON (request id, user id, route, latency).
- Rate limits: login 10/min/IP, uploads 30/h/user (in-process).

## 6. Local development without Docker

```bash
cd server
uv venv --python 3.12 .venv && source .venv/bin/activate
uv pip install -e ".[dev]"
export DATABASE_URL=sqlite:///./bookcon.db STORAGE_BACKEND=local \
       LOCAL_STORAGE_DIR=./data JWT_SECRET=dev-secret-please-change
alembic upgrade head
uvicorn app.main:app --reload
pytest          # 40 integration tests
python -m scripts.e2e_demo http://localhost:8000
```

CBR comics need the `unrar` or `unar` binary on the server.
