# ADR-030: Staging Deployment Target

**Status:** Accepted (2026-09-07). Replaces the Proposed form of this ADR; the
options analysis below is retained because it is the reasoning the decision was
made against.

> Persistent staging is live at **https://staging.testinbox.email**, deployed
> from `develop` through the Infinity Ops platform. The provider-neutral
> topology this ADR originally proposed as Option A remains in the repository
> and is exercised on every pull request — see "Why two topologies" below,
> which is the one part of this decision most likely to be misread as drift.

## Context

`VISION.md` listed "Target initial deployment environment" as a Human Decision
Required Before Implementation, and ADR-004 defers the production inbound-mail
provider *until a deployment environment is decided*. TI-DEPLOY-001 therefore
built everything that did not depend on the answer and stopped. This ADR
records the answer.

The minimum capabilities a target must provide are unchanged and are restated
below, because they are what any future environment — including production —
must still satisfy.

## Decision

Staging runs on the **existing Infinity shared dev/staging host (Contabo, US)**,
alongside the other services on that estate rather than on a host provisioned
for TestInbox alone.

```
                     Cloudflare (proxied, TLS)
                              │
                       Traefik (Infinity estate ingress)
                              │
                 ┌────────────┴────────────┐
                 │                         │
          TestInbox API              TestInbox web
                 │
   ┌─────────────┼──────────────┐
   │             │              │
PostgreSQL     MinIO      ingestion (SMTP, loopback only)
(stack-local, (stack-local,
 direct)       scoped creds)
```

| Concern | Decision |
|---|---|
| HTTP ingress | Cloudflare-proxied → the estate's existing Traefik → TestInbox. The application's own nginx is **not** deployed here. |
| Database | Stack-local PostgreSQL container, **direct session-scoped connection**. No transaction-mode pooler — capability 2 below. |
| Object storage | Stack-local MinIO with credentials scoped to TestInbox's bucket, not root credentials. |
| SMTP | `127.0.0.1:2525` on the staging host. Loopback only: no public SMTP, no MX. The synthetic suite runs **on the host**, which is why no tunnel or public port is needed. |
| Observability | Prometheus scrapes the API and ingestion management ports; Loki collects logs; Uptime Kuma watches HTTPS/TLS. |
| Continuous delivery | GitHub builds, tests and attests → GHCR immutable digests → **release handoff to GitLab `infinity/infinity-core`** → the estate's existing pull/reconcile mechanism. |
| Production | **OVH** remains the intended production target. Production is not deployed, and this ADR does not authorise it. |

### Why the CD boundary moved

The Infinity estate is pull-based, so a push-based deployment from GitHub would
have required GitHub to hold an SSH key for the staging host, plus the
synthetic-test credential to verify afterwards. Handing an immutable digest set
to Ops instead means the only cross-system credential GitHub holds is a GitLab
pipeline trigger token, which can start one pipeline and do nothing else. The
host credentials never leave the system that already owns them.

The cost is that **GitHub can no longer observe the deployment outcome**. A 2xx
from the trigger API means the release candidate was accepted, not that it was
deployed. The workflow says exactly that, and the authoritative staging verdict
lives in `infinity-core`. This is a real reduction in what GitHub can tell you,
accepted deliberately in exchange for not duplicating host credentials — see
`docs/dev/deployment.md`.

### Why two topologies is not drift

The repository still contains `deploy/staging/compose.yaml` and the nginx edge,
and CI still stands the whole thing up on every pull request. That is
deliberate, and it is not a second, stale description of the deployed system:

- **They answer different questions.** The rehearsal proves the deployment
  properties the *application* owns — that one migration job runs and is gated
  on, that readiness reflects the database/schema/object store/`LISTEN`
  connection, that a full 60s long poll survives a reverse proxy, that the
  public SDK completes a real inbound workflow. Those must hold on any edge.
  The Infinity environment proves that the *particular* estate satisfies them.
- **It is the self-hosted reference.** TestInbox is meant to be runnable by
  someone who is not on the Infinity estate; the nginx topology is the
  provider-neutral answer for them, and an unexecuted reference implementation
  rots.
- **It gates pull requests; the Ops pipeline cannot.** The Ops deployment
  happens after merge. Without the rehearsal, no deployment property would be
  checked before a change lands.

Where the two differ, the *invariant* is what is shared and the *mechanism* is
not. The unknown-Host synthetic assertion is the worked example: nginx enforces
it with `return 444` (connection dropped) and Cloudflare/Traefik with a 4xx.
The test asserts "an unrecognised Host is not served by TestInbox" and accepts
either, while still failing if a 4xx turns out to have been produced by the
application (`deploy/synthetic/src/edge.mjs`).

### Edge timeout ceiling

The deployed path imposes a ceiling the nginx reference does not:

| | value |
|---|---|
| Server wait-window cap (`testinbox.wait-window-cap`) | 60 s |
| Required safety margin (`DeploymentSafety`) | 30 s |
| Minimum acceptable ingress timeout | 90 s |
| Cloudflare effective ceiling | **100 s** |
| Traefik | no shorter timeout |
| **Effective edge timeout on this path** | **100 s** |

So `TESTINBOX_WAIT_WINDOW_CAP` **must not exceed 70 s** while this Cloudflare
configuration is in use — and 70 s is the arithmetic maximum, not a
recommendation: it consumes the entire 30 s margin, leaving nothing for TLS
handshake, transit or scheduling. **Keep it at 60 s.** Raising it past what the
edge can hold is an edge/DNS/tier decision, not an application environment
variable.

This is enforced, not merely documented: `testinbox.deployment.edge-request-ceiling`
carries the ingress's hard per-request limit, and `DeploymentSafety` refuses to
start a process whose wait window plus margin exceeds it — or whose declared
proxy read timeout claims more patience than the edge actually has. The
provider-neutral topology leaves it unset, because there the edge is ours.

## Minimum capabilities (unchanged, and how the chosen target meets them)

| # | Capability | Met by |
|---|---|---|
| 1 | Linux container execution, ≥ 3 long-running processes + a one-shot job | Existing estate host |
| 2 | PostgreSQL over a **session-scoped** connection | Stack-local PostgreSQL, direct — no PgBouncer |
| 3 | S3-compatible object storage | Stack-local MinIO, bucket-scoped credentials |
| 4 | HTTPS ingress with read timeout > 60 s | Cloudflare 100 s / Traefik — verified by the deployed synthetic suite |
| 5 | Private inbound TCP for SMTP | `127.0.0.1:2525`; synthetic runs on the host |
| 6 | Secret injection outside Git | Ops platform |
| 7 | Persistent logs and metrics | Loki, Prometheus, Uptime Kuma |
| 8 | Run a one-shot job and gate on its exit code | Ops reconcile runs the migrator |
| 9 | Pull from GHCR by digest | Ops pull/reconcile |
| 10 | Outbound network | Estate host |

Capability 2 remains the one that fails silently: a transaction-mode pooler
accepts `LISTEN` and then never delivers a notification, so the symptom is a
latency regression rather than an error. The deployed environment connects
directly, the deployed synthetic suite measures wake-up latency, and
`wait_listen_degraded_polling` now makes the fallback state continuously
visible in Prometheus (`docs/architecture/observability.md`).

## Options considered (retained for the record)

### Option A — Single small Linux VM, Docker Compose

One VM (2 vCPU / 4 GB is ample for staging), Docker Engine, the committed
`deploy/staging/compose.yaml`, Postgres and MinIO as containers on the same
host with volumes, nginx terminating TLS.

- **Satisfies:** all ten capabilities directly. Postgres is local and
  unpooled, so capability 2 is trivially met. SMTP is bound to a private
  interface and reached over the host firewall / a VPN.
- **Cost:** one VM, roughly €5–15/month at any commodity provider.
- **Effort:** provision host, install Docker, add an SSH deploy key as a
  GitHub environment secret, point DNS, run certbot. The deployment step is
  `docker compose pull && ./deploy.sh`.
- **Against:** the host is a pet. Backups, OS patching and disk growth are
  ours. A single VM is a single point of failure — acceptable for staging,
  not a template for production.
- **Pre-empts:** nothing. Postfix or SES both remain open (ADR-004).

### Option B — Managed container platform (Fly.io / Render / Railway / a cloud "app service")

Application containers run as managed services; PostgreSQL and object storage
are managed add-ons.

- **Satisfies:** 1, 6, 7, 8, 9, 10 well; TLS ingress (4) is automatic but the
  **platform proxy timeout must be checked** — several platforms cap idle
  request time at 30–60 s, which would break the 60 s wait window at the edge
  and turn a legitimate `200 {status: TIMEOUT}` into a 502/504.
- **Risk on 2:** managed Postgres add-ons frequently front the database with a
  transaction-mode pooler. Must be verified against the direct endpoint.
- **Risk on 5:** raw inbound TCP on an arbitrary port is restricted or absent
  on several of these platforms; SMTP ingress may not be expressible at all.
- **Cost:** roughly $15–40/month for app + database + storage.
- **Effort:** lowest of the three for the happy path, highest for capabilities
  4/5, which are the ones TestInbox specifically needs.
- **Against:** platform-shaped constraints get baked into the deployment
  description, and the two constraints most at risk (long-poll timeout, raw
  SMTP) are the two the product is built around.

### Option C — Cloud IaaS with managed data services (AWS/GCP/Azure)

Containers on ECS/Fargate, Cloud Run or Container Apps; RDS/Cloud SQL;
S3/GCS; ALB or equivalent.

- **Satisfies:** all ten, with the most control over 4 and 5 (an NLB can
  expose SMTP on a restricted security group; ALB idle timeout is
  configurable up to 4000 s).
- **Cost:** highest — realistically $60–150/month for a staging footprint that
  is not doing anything, and the cheapest configurations reintroduce the
  Option B risks.
- **Effort:** highest. Needs IaC to stay reproducible, an account and billing
  owner, and IAM/OIDC federation for the deployment identity.
- **Against:** disproportionate to a single-instance staging environment, and
  choosing AWS here would put a thumb on the scale for SES in ADR-004 — a
  decision `VISION.md` deliberately keeps separate.

## How the decision relates to those options

The chosen target is **Option A's shape on an estate that already existed**:
a Linux host running containers, with PostgreSQL and object storage local to
the stack. That is why capabilities 2, 4 and 5 — the three that Options B and C
put at risk — are met without any per-provider investigation.

Two things differ from Option A as written, and both are consequences of
joining an existing estate rather than provisioning a host for TestInbox alone:

1. **The ingress is the estate's Traefik behind Cloudflare, not our nginx.**
   Option A assumed we would own the edge. We do not, and adding a second
   reverse proxy inside an estate that already terminates HTTP would be pure
   duplication. The nginx configuration therefore stops being the deployed
   edge and becomes the self-hosted reference — see "Why two topologies".
2. **Delivery is pull-based via Ops, not an SSH deploy from CI.** Option A
   assumed CI would push. On a shared estate the reconcile mechanism already
   exists, and reusing it keeps host credentials out of GitHub entirely.

Options B and C are not revisited: nothing about them improved, and the
capability risks recorded above still stand if they are ever reconsidered.

## What Ops must guarantee

The handoff moved three responsibilities across the boundary. Two were already
Ops concerns; the third was previously enforced inside the GitHub run and is
now enforceable only in `infinity-core`, so it is stated here as a contract
requirement rather than left implicit:

1. **At most one reconcile per environment at a time.** ADR-029 requires
   exactly one migration executor and a deployment that is never interrupted
   mid-migration. GitHub used to serialise this with a concurrency group,
   because the migration ran inside the run. The handoff returns as soon as the
   trigger is accepted, so two rapid merges can now hand over two candidates
   while the first is still reconciling. Ops must queue them: a later candidate
   supersedes an earlier one, it does not race it.
2. **A container stop grace period of at least 90 s for the API** (60 s for
   ingestion). The applications drain a parked long poll on `SIGTERM`
   (`spring.lifecycle.timeout-per-shutdown-phase`); Docker's 10 s default would
   `SIGKILL` a wait that was still legitimately running.
3. **The post-deployment synthetic suite**, run on the host after reconcile.
   GitHub can no longer run it — it has no route to the private SMTP listener,
   by design — so it is the Ops pipeline that turns "containers started" into
   "the deployment works".

## Consequences

- GitHub no longer holds any staging host credential. It also no longer knows
  whether a deployment succeeded; that verdict is in Ops, and the workflow is
  named and worded so nobody reads acceptance as success.
- The application repository documents the **contract** with Ops (payload,
  validation, digest identity), not Ops' implementation. Reconcile mechanics
  belong in `infinity-core`.
- Two topologies coexist by design, with the split of responsibilities above.
  Neither is allowed to rot: the rehearsal runs on every pull request, and the
  deployed environment runs the same synthetic suite after every reconcile.
- The 100 s Cloudflare ceiling is an architectural constraint on the wait
  window, recorded here and enforced at startup by `DeploymentSafety` through
  `edge-request-ceiling`.
- **ADR-004 (production inbound provider) remains unresolved.** Choosing a
  staging host does not choose a mail provider, and no MX record exists.
- Production (OVH) is named as an intention only. Promoting to it needs its own
  increment and its own decision.
