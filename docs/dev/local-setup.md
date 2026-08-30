# Local Development Setup

Everything runs locally — no AWS credentials, no cloud dependency (ADR-004).

## Prerequisites

- JDK 25 (Temurin recommended)
- Docker (for Postgres, MinIO, and Testcontainers-based tests)
- Node.js >= 20 (TypeScript SDK and web UI)

## 1. Start dependencies

From the repository root:

```bash
docker compose up -d
```

This starts PostgreSQL 16 (`localhost:5432`, db/user/password `testinbox`)
and MinIO (`localhost:9000`, console `localhost:9001`, credentials
`testinbox` / `testinbox123`). If host port 5432 is taken:
`TESTINBOX_DB_PORT=55432 docker compose up -d` and export
`TESTINBOX_DB_URL=jdbc:postgresql://localhost:55432/testinbox` for the apps.

## 2. Run migrations + start TestInbox

Migrations run automatically on application startup (Flyway). Start the two
deployables in separate terminals from `backend/`:

```bash
export TESTINBOX_BOOTSTRAP_API_KEY=tk_local_dev_key
./gradlew :api:bootRun
```

```bash
./gradlew :ingestion:bootRun
```

- REST API: `http://localhost:8080` (health: `/actuator/health`)
- SMTP gateway: `localhost:2525`, accepting mail for `@testinbox.local`
- The bootstrap key provisions a local workspace/project and an API key
  with `inboxes:write` + `messages:read`. Only its SHA-256 hash is stored.

## 3. Exercise the full loop

```bash
curl -s -X POST http://localhost:8080/v1/inboxes \
  -H "Authorization: Bearer $TESTINBOX_BOOTSTRAP_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"aliasHint":"demo","ttlSeconds":600}'
```

Point your system-under-test's SMTP config at `localhost:2525`, send a mail
to the returned address (e.g. with `swaks --server localhost:2525 --to <address>`),
then:

```bash
curl -s -X POST http://localhost:8080/v1/inboxes/<id>/messages/wait \
  -H "Authorization: Bearer $TESTINBOX_BOOTSTRAP_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"matcher":{},"timeoutSeconds":30}'
```

## 4. Run the test suites

```bash
cd backend && ./gradlew build          # unit + integration + architecture (Docker required)
cd backend && ./gradlew :e2e:test      # full black-box acceptance (boots both apps + Karate + SDKs)
cd sdk/kotlin && ./gradlew build       # JVM SDK (Java 17 baseline)
cd sdk/typescript && npm ci && npm test
cd web && npm ci && npm test           # Playwright (chromium via npx playwright install chromium)
```

The e2e suite runs the TypeScript SDK's live integration tests against the
booted stack, so Node must be on the PATH for `:e2e:test`.

## 5. Rate limits and quotas

Enforcement is on by default (ADR-027). Local defaults are generous — 200
active inboxes, 2 GiB stored, 50 concurrent waits, 60 inbox creations of
burst — so ordinary development never trips them. Override any of them:

```bash
TESTINBOX_LIMITS_MAX_ACTIVE_INBOXES=5 \
TESTINBOX_LIMITS_INBOX_CREATE_CAPACITY=2 \
./gradlew :api:bootRun
```

A refused request tells you which control fired: `429` with `Retry-After`
means a rate limit (waiting helps), `409` with problem type `quota-exceeded`
means an allowance is full (delete an inbox instead). Every governed response
carries `RateLimit-Limit`/`-Remaining`/`-Reset`.

Setting `testinbox.limits.enabled=false` disables enforcement and logs a
startup warning — useful for load experiments, never for a shared deployment.

Both deployables configure limits independently (`testinbox.limits.*` is read
by the API and by the ingestion gateway separately), so a shared environment
must set them consistently in both. The inbound `INGEST` budget only exists in
the gateway's configuration, and an over-rate delivery is invisible to the
sender by design — `testinbox_rate_decision_total{category="INGEST",
outcome="rejected"}` is the signal that a workspace is losing mail.

## 6. Web UI (debugging dashboard)

```bash
cd web && npm ci && TESTINBOX_API_URL=http://localhost:8080 npm run dev
```

Open `http://localhost:3000`, paste the API key and an inbox id. Untrusted
HTML renders only in a sandboxed iframe with remote loads blocked (ADR-011).
