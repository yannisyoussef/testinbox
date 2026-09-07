# ADR-030: Staging Deployment Target

**Status:** Proposed — requires a human decision (see `VISION.md` §7)

> Nothing in this repository selects a hosting provider. The deployment
> artifacts, topology, migration model, readiness contract, edge configuration
> and synthetic verification are all provider-neutral and are exercised end to
> end in CI against an ephemeral stack. What is *not* decided is where a
> persistent staging environment lives. This ADR states the requirement, the
> options and the trade-offs so that decision can be made once, deliberately.

## Context

`VISION.md` lists "Target initial deployment environment (self-hosted/on-prem
vs. a TestInbox-operated cloud service vs. both)" as a Human Decision Required
Before Implementation, and ADR-004 defers the production inbound-mail provider
choice (self-hosted Postfix vs. AWS SES) explicitly *until a target deployment
environment is decided*. Picking a staging host silently would pre-empt both.

TI-DEPLOY-001 therefore builds everything that does not depend on the answer,
and stops here.

### Minimum capabilities a staging target must provide

Derived from the Accepted ADRs, not from convenience:

| # | Capability | Why (source) |
|---|---|---|
| 1 | Linux container execution, ≥ 3 long-running processes + a one-shot job | ADR-001 (API and ingestion are separate deployables), ADR-029 (migration job) |
| 2 | PostgreSQL 16+, reachable over a **session-scoped** connection | ADR-006; ADR-020 requires `LISTEN` on a connection that is never routed through transaction-mode pooling |
| 3 | S3-compatible object storage | ADR-005 (raw MIME written before the DB row) |
| 4 | HTTPS ingress with a **configurable proxy read timeout > 60 s** | ADR-012/020 bounded long polling; the server wait window cap is 60 s |
| 5 | Private/restricted inbound TCP for SMTP (port 25 or an alternative), reachable by the synthetic test but not by the Internet | ADR-025 and §22 of the increment brief: no public MX yet |
| 6 | Secret injection that is not the Git repository | ADR-010 (keys hashed at rest, never logged/committed) |
| 7 | Persistent logs and a metrics scrape or push path | `docs/architecture/observability.md` |
| 8 | Ability to run a one-shot job to completion and gate on its exit code | ADR-029 |
| 9 | Ability to pull from GHCR by digest | ADR-028 |
| 10 | Outbound network (object storage, registry) | ADR-005/028 |

Capability 2 is the one that most often fails silently. A managed Postgres
fronted by a transaction-mode pooler (PgBouncer in `transaction` mode,
Supabase's pooled port, some "serverless Postgres" endpoints) will accept
`LISTEN` and then never deliver a notification, because the session is
returned to the pool between statements. TestInbox degrades to bounded
re-query in that state rather than hanging, so **the symptom is a latency
regression, not an error** — which is exactly why it must be verified before
selection, not after. Any candidate must expose a direct, session-mode
connection endpoint.

## Options

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

## Recommendation

**Option A**, unless there is an existing organisational cloud account and an
operator who wants the environment there. It is the only option that meets
capabilities 2, 4 and 5 without a per-provider investigation, it is the
cheapest, and it pre-empts nothing: the compose topology is a faithful,
smaller-scale model of any of the three, and moving from A to B or C later
changes the deployment *step* of the pipeline, not the artifacts, the
migration model, the readiness contract or the synthetic suite.

## Decision required

1. Which of A / B / C — or a specific host that already exists.
2. Who owns the account, the billing and the DNS zone for
   `staging.testinbox.email`.
3. Confirmation that the chosen PostgreSQL endpoint offers a session-mode
   connection (capability 2).
4. Confirmation of the ingress idle/read timeout ceiling (capability 4).
5. How the synthetic test reaches SMTP ingress privately (capability 5):
   host firewall, private network, VPN, or a restricted source range.

Until this ADR is Accepted, `deploy-staging.yml` builds and verifies artifacts
but performs no provider-specific provisioning, and the deployment job stays
inert behind the `STAGING_DEPLOY_ENABLED` environment variable.

## Consequences

- The pipeline is complete and proven except for its last step; enabling a
  target is expected to be configuration plus a deploy script, not a redesign.
- `deploy/staging/compose.yaml` and `deploy/staging/deploy.sh` are the
  reference implementation for Option A and are executed on every run of the
  staging workflow as an ephemeral rehearsal, so they cannot rot unnoticed.
- ADR-004 (production inbound provider) remains untouched and unprejudiced.
