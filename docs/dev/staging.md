# Staging

**Live at https://staging.testinbox.email**, deployed from `develop` through
the Infinity Ops platform (ADR-030, Accepted).

This document describes the **contract** the application has with that
environment — what it needs, what it exposes, what must not change. It is not
an Ops runbook: how the host is reconciled, where secrets are stored and how
the estate is administered belong in `infinity/infinity-core`, and duplicating
them here would create a second description that silently goes stale.

## As built

```
                     Cloudflare (proxied, TLS)
                              │
                       Traefik (Infinity estate)
                              │
                 ┌────────────┴────────────┐
                 │                         │
          TestInbox API              TestInbox web
                 │
   ┌─────────────┼──────────────┐
   │             │              │
PostgreSQL     MinIO      ingestion (SMTP 127.0.0.1:2525)
(direct,      (scoped
 no pooler)    creds)
```

| | |
|---|---|
| Host | Contabo US, shared Infinity dev/staging estate |
| Ingress | Cloudflare → Traefik. **The application's nginx is not deployed here** |
| Database | Stack-local PostgreSQL, direct session-scoped connection, no pooler |
| Object storage | Stack-local MinIO, bucket-scoped credentials |
| SMTP | `127.0.0.1:2525`, loopback only. No public SMTP, no MX |
| Observability | Prometheus scrapes the management ports; Loki for logs; Uptime Kuma for HTTPS/TLS |
| Delivery | GitHub → GHCR digests → GitLab `infinity-core` → host reconcile |

**Two topologies exist on purpose.** The nginx/compose stack in `deploy/staging/`
is not a stale description of the above — it is the provider-neutral reference
for self-hosted deployments, and it is what the ephemeral CI rehearsal runs on
every pull request. ADR-030 explains the split; the short version is that the
rehearsal proves the properties the *application* owns, before merge, on an
edge we control, and the deployed environment proves this particular estate
satisfies them.

## The provider-neutral reference topology

Not what runs at `staging.testinbox.email` — that is the diagram above. This is
the self-hosted stack in `deploy/staging/`, which the CI rehearsal stands up on
every pull request.

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

Both topologies are production-*like*, not production-*sized*: one API
instance, one ingestion instance, a small database and object store. The
structure — separate deployables, an edge that terminates TLS, a private
management port, a one-shot migration job — is what a larger deployment keeps,
and it is identical either side of the split.

Files: `deploy/staging/compose.yaml` (application + edge),
`deploy/staging/compose.data.yaml` (self-hosted PostgreSQL/MinIO overlay; omit
it when the environment supplies managed services),
`deploy/staging/nginx/templates/default.conf.template`,
`deploy/staging/deploy.sh`, `deploy/staging/.env.example`.

## The LISTEN constraint — true of both topologies

`waitForMessage` is woken by PostgreSQL `LISTEN/NOTIFY` on a **session-scoped**
connection (ADR-020). That connection must never be routed through
transaction-mode pooling.

The reason this needs saying out loud: a transaction-mode pooler (PgBouncer in
`transaction` mode, a managed "pooled" endpoint, several serverless Postgres
products) **accepts `LISTEN` and then never delivers a notification**, because
the session goes back to the pool between statements. TestInbox degrades to
bounded re-query rather than hanging, so the symptom is a *latency regression*,
not an error. Nothing goes red.

- The deployed staging stack connects to a stack-local PostgreSQL **directly**.
  No PgBouncer, no pooled endpoint.
- `compose.data.yaml` does the same for the self-hosted topology.
- Any future managed candidate must expose a session-mode endpoint, and
  `TESTINBOX_DB_URL` must point at it.
- The synthetic suite measures wake-up latency from the moment the gateway
  accepts the delivery, and `testinbox_wait_listen_degraded_polling` reports
  the state continuously — so the failure is caught both at deploy time and
  in perpetuity.

## Long-poll and the ingress — and the ceiling it imposes

The server answers a bounded long poll with `200 {status: TIMEOUT}` — never a
408, never a gateway error. Every hop in front of it must outlive that wait, or
a legitimate answer becomes a 504.

**On the deployed path the ceiling is Cloudflare's, and it is 100 s:**

| | value |
|---|---|
| Server wait-window cap (`testinbox.wait-window-cap`) | **60 s** |
| Required safety margin (`DeploymentSafety.PROXY_TIMEOUT_MARGIN`) | 30 s |
| Minimum acceptable ingress timeout | 90 s |
| Cloudflare effective ceiling | **100 s** |
| Traefik | no shorter timeout |
| **Effective edge timeout, deployed** | **100 s** |
| Graceful shutdown (api) | 75 s |

So there is **10 s of headroom, not 60 s**. Do not read the nginx reference's
`TESTINBOX_PROXY_READ_TIMEOUT_SECONDS=120` as describing staging: that value
governs the self-hosted topology, where we own the proxy.

> **`TESTINBOX_WAIT_WINDOW_CAP` must not exceed 70 s** while this Cloudflare
> configuration is in use. It is currently 60 s. Raising it past 70 s makes
> full-window waits fail intermittently at the edge, and the fix for that is an
> edge/DNS/plan decision — **not** an application environment variable.

`DeploymentSafety` enforces the margin from the values a process is given, and
the synthetic suite proves it end to end by parking one request for the full
window through the real ingress and requiring TestInbox's own timeout answer
back — a 502/504 fails the deployment.

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

## Secrets — who holds what

**GitHub holds exactly one secret for this path:**

| name | scope | what it can do |
|---|---|---|
| `GITLAB_TRIGGER_TOKEN` | `staging-handoff` environment | Start one pipeline in `infinity/infinity-core`. Nothing else. |

That is the whole list, and the shrinkage is the point.

Alongside it the same environment carries three **variables** — not secrets, because
none of them is one:

| name | value on the Infinity estate | why it exists |
|---|---|---|
| `GITLAB_OPS_API_URL` | `https://gitlab.yvnn.is/api/v4` | **Required.** `gitlab-handoff.sh` defaults to `gitlab.com`, which is the right default for a repository that assumes no Ops platform — but it means the host must be named explicitly, or the trigger token is POSTed to gitlab.com. The workflow fails loudly when this is unset rather than falling back. |
| `GITLAB_OPS_PROJECT` | `3` | The numeric project id, not the URL-encoded path: immune to a group or project rename. |
| `GITLAB_OPS_REF` | `develop` | The Ops branch whose pipeline is triggered. | GitHub previously held
`STAGING_SSH_HOST`, `STAGING_SSH_USER`, `STAGING_SSH_KEY`,
`STAGING_SSH_KNOWN_HOSTS` and `STAGING_SYNTHETIC_API_KEY` in order to push a
deployment and verify it afterwards. On a pull-based estate none of that is
needed, so **all five have been removed** — a compromised GitHub Actions run
can no longer reach the staging host at all.

**Ops holds everything else**, because Ops already did: the database, object
storage and bootstrap credentials, the host, and the synthetic test credential.
The application consumes them by name (`deploy/staging/.env.example` lists the
names with placeholder values) but this repository never carries the values and
never transports them.

The synthetic credential is dedicated to synthetic testing — never a personal
or administrative key — and the applications refuse to start with a bootstrap
key shorter than 32 characters or one matching a known fixture.

Since TI-002 that credential is a **managed** key (ADR-032) carrying only
`inboxes:write` and `messages:read`, so it can be revoked on its own without
touching anything else, and a leak does not hand over the ability to mint
further credentials. The credential-lifecycle product synthetic needs a second,
separate key with `api-keys:manage` (`TESTINBOX_ADMIN_API_KEY`); it is
deliberately not required by the deployment gate, so the gate's runner never
holds a key that can create keys. See
[`docs/dev/api-keys.md`](api-keys.md) for both.

**Staging's bootstrap credential is break-glass, not the working credential.**
It stops authenticating the moment the workspace holds a managed key with
`api-keys:manage`, and reopens only if every such key is revoked. Keep it
configured — an environment without one has no recovery path if its last
administrative key is lost — and rotate it by changing
`TESTINBOX_BOOTSTRAP_API_KEY` and restarting, which retires the previous value
on the next request.

Secrets appear in **no** Dockerfile, compose file, workflow literal, container
label, build arg, image, or log line. `DeploymentSafety` reports setting names
and problems, never values; the migrator logs versions, never its JDBC URL; and
the handoff script keeps the trigger token out of argv and out of every message
it prints.

## The edge fingerprints clients, and one stdlib default matches a scraper

Cloudflare's Browser Integrity Check sits in front of staging and answers a
narrow set of client signatures with **`Error 1010: Access denied`** before the
request reaches Traefik.

The measured behaviour, after Ops re-tested it properly:

| Client | Result |
|---|---|
| `Python-urllib/3.12` (stdlib default UA) | refused |
| `requests` / `httpx` | pass |
| `curl`, Node `fetch`, JVM `HttpClient` | pass |
| **no `User-Agent` at all** | pass |

So it is **not** "bot protection blocks non-browser clients" — an earlier
version of this page said that, and it was wrong. One notorious scraper
signature is refused; the libraries anyone actually writes Python API code with
are not, and neither is an empty `User-Agent`.

**Browser Integrity Check stays on.** The trade that would have justified
removing it does not exist: it would give up a working control to accommodate a
single stdlib default that almost nobody uses directly.

If you hit a `1010`:

- It is not an authentication failure. A TestInbox refusal is
  `application/problem+json` with a `correlationId`; a `1010` is a Cloudflare
  HTML page. **If the body is HTML, the request never reached us.**
- Send any explicit `User-Agent`, or use `requests`/`httpx`.

### What this did surface

No shipped SDK sent a `User-Agent`. They passed on their runtime's default,
which meant the deployment gate's ability to reach its own API rested on an
unexamined interaction between Node's default and a Cloudflare heuristic.
Harmless — an empty UA passes, so even a runtime change was unlikely to break
it — but nobody would have predicted that failure or diagnosed it quickly.

Both SDKs now send one (`testinbox-sdk-ts/…`, `testinbox-sdk-jvm/…`), which is
good practice independent of Cloudflare: it makes SDK traffic attributable in
access logs and separable from ad-hoc calls when debugging a customer's report.
A test in each SDK holds the advertised version to the one the package actually
ships, because a stale version in someone else's log is worse than none.

## Metrics

Both deployables expose `/actuator/prometheus` on the private management port
(9090 API, 9091 ingestion), which the edge never routes. The full metric list
is in [observability.md](../architecture/observability.md).

The one worth an alert is `testinbox_wait_listen_degraded_polling`. It is 1
whenever `LISTEN` notifications are not being delivered and parked waits are
falling back to bounded re-query — a state in which **everything else looks
healthy**: HTTP 200, messages arriving, readiness green, only latency worse.
It is exactly what a transaction-mode pooler in front of PostgreSQL produces,
which is why this environment connects to PostgreSQL directly.

`testinbox_build{service,git_sha,version}` answers "what is running?" from the
same place. The image digest is deliberately not a label — an image cannot know
its own digest, and Ops owns that fact.

## Host provisioning (self-hosted reference only)

> The Infinity estate provisions itself; this section applies to the
> provider-neutral topology, not to `staging.testinbox.email`.

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
