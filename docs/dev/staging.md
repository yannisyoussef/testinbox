# Staging

> **Status: no staging environment is deployed.** No hosting provider has been
> selected — [ADR-030](../adr/0030-staging-deployment-target.md) is `Proposed`
> and `VISION.md` §7 lists the decision as outstanding. Everything below is
> built, committed and exercised on every CI run against an ephemeral copy of
> this exact topology; what is missing is a host to put it on.

## Topology

```
                    ┌─────────────────────────────────────────────┐
   HTTPS 443 ──────▶│ nginx (TLS, edge limits, long-poll timeout) │
                    └───────────────┬──────────────┬──────────────┘
                                /v1/│              │/
                                    ▼              ▼
                              ┌──────────┐   ┌──────────┐
                              │   api    │   │   web    │
                              └────┬─────┘   └────┬─────┘
                                   │   (same-origin proxy)
   SMTP (private) ──▶ ┌────────────┴──┐          │
                      │   ingestion   │          │
                      └───────┬───────┘          │
                              │                  │
                    ┌─────────┴──────────────────┴────────┐
                    │  PostgreSQL (direct)   object store │
                    └─────────────────────────────────────┘

   one-shot, before any of the above: migrator
```

Staging is production-*like*, not production-*sized*: one API instance, one
ingestion instance, a small database and object store. The structure — separate
deployables, an edge that terminates TLS, a private management port, a one-shot
migration job — is what a larger deployment would keep.

Files: `deploy/staging/compose.yaml` (application + edge),
`deploy/staging/compose.data.yaml` (self-hosted PostgreSQL/MinIO overlay; omit
it when the environment supplies managed services),
`deploy/staging/nginx/templates/default.conf.template`,
`deploy/staging/deploy.sh`, `deploy/staging/.env.example`.

## The LISTEN constraint — read this before choosing a database

`waitForMessage` is woken by PostgreSQL `LISTEN/NOTIFY` on a **session-scoped**
connection (ADR-020). That connection must never be routed through
transaction-mode pooling.

The reason this needs saying out loud: a transaction-mode pooler (PgBouncer in
`transaction` mode, a managed "pooled" endpoint, several serverless Postgres
products) **accepts `LISTEN` and then never delivers a notification**, because
the session goes back to the pool between statements. TestInbox degrades to
bounded re-query rather than hanging, so the symptom is a *latency regression*,
not an error. Nothing goes red.

- `compose.data.yaml` connects directly to PostgreSQL. No pooler.
- Any managed candidate must expose a session-mode endpoint, and
  `TESTINBOX_DB_URL` must point at it.
- The synthetic suite asserts a parked wait resolves within 5 s of delivery,
  which fails loudly if notifications are not arriving.

## Long-poll and the ingress

The server answers a bounded long poll with `200 {status: TIMEOUT}` — never a
408, never a gateway error. nginx's **default** `proxy_read_timeout` is 60 s,
which is exactly the wait-window cap, so a stock reverse proxy races every
full-window wait and turns a legitimate answer into a 504.

| setting | value | source |
|---|---|---|
| server wait-window cap | 60 s | `testinbox.wait-window-cap` |
| required margin | ≥ 30 s | `DeploymentSafety.PROXY_TIMEOUT_MARGIN` |
| edge `proxy_read_timeout` | 120 s | `TESTINBOX_PROXY_READ_TIMEOUT_SECONDS` |
| graceful shutdown (api) | 75 s | `spring.lifecycle.timeout-per-shutdown-phase` |

Both the edge and the applications read the same environment variable, and the
applications refuse to start if the pair is inconsistent. The synthetic suite
proves it end to end by parking a request for the full window through the real
ingress and requiring TestInbox's own timeout answer back.

## Health and readiness

Actuator is bound to a **separate management port** (9090 api / 9091
ingestion), so the traffic port serves nothing but `/v1`. The edge additionally
returns 404 for `/actuator` as a second lock.

| probe | question | contents |
|---|---|---|
| `/actuator/health/liveness` | is this process alive? | `livenessState` only — restart-worthy failures |
| `/actuator/health/readiness` (api) | can this instance serve TestInbox traffic? | `readinessState`, `db`, `schema`, `objectStorage`, `waitNotifier` |
| `/actuator/health/readiness` (ingestion) | " | `readinessState`, `db`, `schema`, `objectStorage`, `smtpListener` |

Readiness is what the container healthchecks and `deploy.sh --wait` gate on, so
a **deployment** does not complete until every process can serve traffic.

**What readiness does and does not gate here.** Be precise about this, because
the obvious reading is wrong. nginx proxies to `api:8080` unconditionally — it
does not consult Docker health — and Compose neither restarts nor removes an
unhealthy container. So readiness gates the *deployment*, not every subsequent
moment: after a host reboot, `restart: unless-stopped` brings the applications
back with no migration job in between.

That gap is why the schema refusal is enforced a second time, at request time:
the API answers `/v1` with `503 schema-unavailable` + `Retry-After`, and the
gateway soft-fails `DATA` with `451` so the sending MTA retries. ADR-029 §4
describes both halves. Under an orchestrator that honours readiness, the probe
alone would be enough; this topology is not that, and the ADR says so rather
than assuming it.

**Known cascade risk.** Readiness includes shared dependencies, so a database
or object-store outage marks every node not-ready at once rather than
degrading. For single-instance staging the practical impact is nil (nothing
routes on readiness anyway), but it is the classic fragile-health-check shape
and must be revisited before a multi-node deployment where readiness *does*
gate a load balancer. `waitNotifier` is the most debatable member: ADR-020 puts
LISTEN health in readiness, yet its bounded degraded re-query exists precisely
so a node with a dead LISTEN keeps returning *correct* results, just slower —
so on a real load balancer a brief PostgreSQL restart would remove every
replica and that degraded mode would never run. Changing it belongs in an
ADR-020 amendment, not a note here.

## Network exposure

| surface | exposure |
|---|---|
| HTTPS 443 (web + `/v1`) | public (once a host exists) |
| HTTP 80 | public, 308 redirect to HTTPS only |
| SMTP | **private** — bound to a private interface, restricted by host firewall/private network |
| PostgreSQL 5432 | compose network only, never published |
| MinIO 9000/9001 (incl. console) | compose network only, never published |
| Actuator 9090/9091 | compose network only, not routed by the edge |

**There is no public MX record and none is created here.** Staging SMTP exists
only to let the synthetic suite validate our own stack. Production inbound-mail
provider selection is a separate decision (ADR-004, still `Proposed`).

## Edge protection

Infrastructure safety controls, deliberately *not* a second copy of the ADR-027
tenant policy — that keys on the authenticated workspace, lives in the
application layer, and protects the ingestion gateway too, which an HTTP filter
could not.

| control | value |
|---|---|
| request rate per IP | 50 r/s, burst 100 (`429`) |
| concurrent connections per IP | 128 |
| request body | 256 KB (nothing in `/v1` accepts a large body; mail arrives over SMTP) |
| header buffers | 4 × 8 KB |
| slow-client timeouts | header 15 s, body 15 s, send 30 s |
| TLS | 1.2/1.3, HSTS, no session tickets |
| unmatched `Host` | `444`, no fallthrough into the TestInbox vhost |

## Required secrets — names only

Set on the GitHub `staging` **environment** (protected), never in the
repository. `deploy/staging/.env.example` carries the same names with
placeholder values.

| name | used for |
|---|---|
| `TESTINBOX_DB_URL` / `TESTINBOX_DB_USER` / `TESTINBOX_DB_PASSWORD` | database connection (session-mode) |
| `TESTINBOX_S3_ENDPOINT` / `TESTINBOX_S3_ACCESS_KEY` / `TESTINBOX_S3_SECRET_KEY` / `TESTINBOX_S3_BUCKET` | object storage |
| `TESTINBOX_BOOTSTRAP_API_KEY` | the synthetic-test credential provisioned at startup |
| `STAGING_SYNTHETIC_API_KEY` | the same value, read by the synthetic suite in CI |
| `STAGING_SSH_HOST` / `STAGING_SSH_USER` / `STAGING_SSH_KEY` / `STAGING_SSH_KNOWN_HOSTS` | deployment channel (Option A) |
| `STAGING_SMTP_HOST` | private SMTP ingress the synthetic suite delivers to |

Environment **variables** (not secret): `STAGING_DEPLOY_ENABLED`,
`STAGING_URL`, `STAGING_DEPLOY_PATH`, `STAGING_SMTP_PORT`.

The synthetic credential is dedicated to synthetic testing. It is never a
personal or administrative key, and the applications refuse to start with a
bootstrap key shorter than 32 characters or one matching a known fixture.

Secrets appear in **no** Dockerfile, compose file, workflow literal, container
label, build arg, image, or log line. `DeploymentSafety` reports setting names
and problems, never values; the migrator logs versions, never its JDBC URL.

## Host provisioning

The deployment workflow ships image digests and a commit; it does **not** ship
the environment's configuration. Before the first deployment the host needs:

1. **A checkout** at `STAGING_DEPLOY_PATH` (default `/opt/testinbox`) that the
   deploy user can `git fetch` into.
2. **`deploy/staging/.env`**, created from `.env.example` with real values.
   It is read by compose (which auto-loads `.env` from the compose file's
   directory) and is never committed. This file — not the GitHub environment
   secrets — is what the running containers read; the GitHub secrets cover the
   *deployment channel* and the synthetic credential.
3. **Registry access.** `deploy.sh` runs `docker compose pull` on the host. Either
   publish the four GHCR packages publicly (which also publishes their SBOM and
   provenance — a deliberate choice, not a default), or `docker login ghcr.io`
   on the host with a read-only token. ADR-028 avoids long-lived credentials in
   CI; a host-side pull token is a separate decision that belongs with ADR-030.
4. **TLS material** at `TESTINBOX_TLS_DIR` — see below.

## TLS: issuance and renewal

Not yet automated, and it has a bootstrap order that will bite otherwise:
nginx's `default_server` terminates 443, so **nginx will not start without a
certificate**, which means the ACME HTTP-01 location on port 80 is unreachable
on a fresh host. Two consequences:

- **First issuance must happen before the first deployment** — e.g.
  `certbot certonly --standalone` with the edge stopped, or a DNS-01 challenge.
- **Expiry takes the edge down and it will not come back**, because nginx
  refuses to start on a missing/invalid certificate. Renewal therefore needs a
  timer plus an `nginx -s reload`, and monitoring on days-to-expiry.

The `certbot-webroot` volume and the ACME location are in place for the renewal
path; the certbot container/timer itself is deliberately not configured,
because how certificates are obtained depends on the target (ADR-030) — a VM
with public DNS, a platform that terminates TLS for you, and a cloud load
balancer are three different answers.

## Data retention and reset

Staging is not a test-data archive. It uses the same TTL/lifecycle semantics as
any deployment (ADR-009): inboxes expire on their TTL, the sweep hard-deletes
expired rows and their blobs, and the orphan sweep collects unreferenced
objects. Synthetic inboxes are created with a 600–900 s TTL and deleted by the
suite even when an assertion fails, so a failed run does not accumulate data.

To reset a corrupted staging environment, throw the data away and let the
normal path rebuild it — do not hand-write SQL that bypasses the ownership
rules in `docs/architecture/data-ownership.md`:

```bash
docker compose -f compose.yaml -f compose.data.yaml down -v   # drops the volumes
./deploy/staging/deploy.sh                                    # migrator recreates the schema
```

For a managed database, drop and recreate the database (or its schema) and run
the same script; the migrator is the only thing that should ever create tables.

## Running the whole thing locally

```bash
./scripts/staging-rehearsal.sh          # builds, deploys by digest, runs the synthetic suite
./scripts/staging-rehearsal.sh --keep   # leave the stack up on https://localhost:8443
```

This is what CI runs. It stands up a local registry so images are deployed by
*real* digest, generates a private CA so TLS verification stays on rather than
being disabled, and tears everything down — printing container logs first if
anything failed.
