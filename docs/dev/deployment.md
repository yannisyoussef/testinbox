# Deployment

How a commit becomes something running. The decisions behind this are
[ADR-028](../adr/0028-deployment-artifacts-and-promotion.md) (artifacts and
promotion) and [ADR-029](../adr/0029-schema-migration-execution.md)
(migrations); where staging is hosted is still open —
[ADR-030](../adr/0030-staging-deployment-target.md).

## Who does what

Four systems, and confusing them is how a green tick comes to mean nothing:

| | System | Owns |
|---|---|---|
| **Application CI** | GitHub Actions | Builds, tests, attests. Proves the artifact. |
| **Artifact registry** | GHCR | Holds immutable images, addressed by digest. |
| **CD / infrastructure** | GitLab `infinity/infinity-core` | Deploys. Reconciles the host. **Owns the deployment verdict.** |
| **Staging** | Contabo US, shared Infinity estate | Runs `develop`. Live at `staging.testinbox.email`. |
| **Production** | OVH — **not deployed** | Intended target only (ADR-030). |

GitHub does **not** deploy and holds no credential for the staging host. It
hands an immutable digest set to Ops and stops. The consequence is stated
plainly because it is easy to misread: **a green `Staging` workflow means the
release candidate was accepted, not that it was deployed.**

## Branch model

```
feature/*  ──PR──▶  develop  ──▶  handoff to Ops  ──▶  STAGING
                       │
                       └──release PR──▶  master  ──▶  PRODUCTION (not implemented)
```

- **`develop` is the staging candidate.** A merge here builds immutable
  artifacts and deploys them to staging.
- **`master` is the production-approved candidate.** It has no deployment
  automation at all. `master` does **not** auto-deploy anything, and this
  increment deliberately did not add a production pipeline.
- Feature PRs are never deployed to shared staging. They build the images and
  run an ephemeral rehearsal of the whole deployment on the CI runner, because
  a deployment path only exercised after merge is one that breaks after merge.

## Release handoff

On a merge to `develop`, after the images are built and the ephemeral rehearsal
passes:

```
GitHub ──POST trigger──▶ GitLab infinity/infinity-core (ref: develop)
                              │
                              └──▶ host pull/reconcile
                                     ├─ migration job
                                     ├─ readiness
                                     └─ post-deployment synthetic suite
```

The payload is exactly six values (`scripts/gitlab-handoff.sh`):

```
TESTINBOX_ENVIRONMENT=staging
TESTINBOX_COMMIT_SHA=<40-hex>
TESTINBOX_API_DIGEST=ghcr.io/<owner>/testinbox-api@sha256:...
TESTINBOX_INGESTION_DIGEST=...
TESTINBOX_MIGRATOR_DIGEST=...
TESTINBOX_WEB_DIGEST=...
```

No host, no SSH key, no database or object-store credential, no API key. Both
sides validate: the script refuses a malformed SHA, a tag instead of a digest,
or an image outside our registry *before* anything leaves the runner, and Ops
validates the same things on receipt. `scripts/gitlab-handoff.test.sh` asserts
each field, and that the trigger token is never printed.

**Where the authoritative verdict lives.** In `infinity-core`. The GitHub run
can tell you the candidate was accepted; it cannot tell you the host converged,
the migration applied, readiness passed, or the synthetic suite passed. That is
why the job is called *Release handoff to GitLab Ops*, why its GitHub
Environment is `staging-handoff` rather than `staging`, and why no summary in
this repository says "deployment succeeded".

## Build once, promote many

Images are built in exactly one place — `.github/workflows/build-images.yml` —
and everything downstream deploys *those bytes*:

```
commit ─▶ build 4 OCI images ─▶ push to GHCR ─▶ digest
                                                  │
                                                  ├─▶ staging deploys THIS digest
                                                  └─▶ future production promotion
                                                      deploys the SAME digest
```

| image | contents |
|---|---|
| `ghcr.io/<owner>/testinbox-api` | REST API (`backend/api`) |
| `ghcr.io/<owner>/testinbox-ingestion` | inbound SMTP gateway (`backend/ingestion`) |
| `ghcr.io/<owner>/testinbox-migrator` | one-shot Flyway executor (`backend/migrator`) |
| `ghcr.io/<owner>/testinbox-web` | inspection UI (`web`) |

**The digest is the deployment identity.** Tags (`:<sha>`, `:develop`) exist
for humans and may be moved or collected; `latest` is never published. A
deployment records digests, a rollback names digests, and
`scripts/validate-image-digest.sh` refuses anything that is not a digest under
our own registry.

Images carry **no configuration and no secrets** — that is what makes the same
bytes promotable across environments. Everything environment-specific arrives
as environment variables at run time, and a deployed process that is missing
one fails to start rather than falling back to a local-development default.

### Container properties

Both Dockerfiles are multi-stage; the runtime stage has a JRE (or Next.js
standalone output) and nothing else — no Gradle, no npm, no source tree.

- non-root (`uid 10001` for the JVM images, `node` for web), asserted in CI;
- `-XX:MaxRAMPercentage=75` so the JVM sizes itself from the *container's*
  limit rather than a hardcoded heap;
- exec-form `ENTRYPOINT`, so the JVM is PID 1 and receives `SIGTERM` directly.
  This is what makes `server.shutdown: graceful` actually drain a parked long
  poll instead of having it killed by the container runtime;
- `spring.lifecycle.timeout-per-shutdown-phase` exceeds the wait-window cap for
  the same reason;
- OCI labels record source repository, revision and version. Provenance and
  SBOM are attached as build attestations; container CVE scanning is
  **informational**, matching the OSV-Scanner posture in
  [quality/strategy.md](../quality/strategy.md).

## Deployment sequence (the provider-neutral reference)

`deploy/staging/deploy.sh` is **not** what deploys the Infinity staging
environment — Ops does that with its own reconcile mechanism. It is the
self-hosted reference implementation, and it is what the ephemeral CI rehearsal
executes on every pull request, so the deployment properties the application
owns are proven before a change lands (ADR-030, "Why two topologies").

It is deliberately not `docker compose up`, which would start every service
concurrently with the migration and report success as soon as the containers
existed.

```
1. validate image references  (ownership + digest-pinned, before any pull)
2. pull artifacts
3. run ONE migration job to completion   ── non-zero exit ⇒ DEPLOYMENT ABORTED,
                                            no service started or updated
4. start/update services, wait for READINESS (not liveness)
5. record what is running (commit, version, digest, readiness)
```

## Migrations

See [ADR-029](../adr/0029-schema-migration-execution.md). In short:

- one logical executor, the `migrator` image, built from the same commit as the
  application images so the migrations applied cannot drift from the ones the
  artifacts expect;
- deployed applications never migrate (`spring.flyway.enabled: false` in the
  `staging` profile). Local development still migrates on startup — one
  process, empty database, no deployment to gate;
- each deployable compares the highest migration version bundled in its own
  artifact against `flyway_schema_history` and refuses **readiness** when the
  schema is behind it, or when the history contains a failure. A schema
  *ahead* of the artifact is healthy — that is a rolled-back artifact, and
  forbidding it would make rollback impossible after any migration;
- migrations are **expand-only** for now: add, do not drop or narrow. A change
  that breaks that makes artifact rollback across it unsafe and must say so —
  see [rollback.md](rollback.md);
- CI proves the upgrade from the previous schema **carrying data**
  (`SchemaUpgradeTest`, `MigratorTest`), not only against an empty database.

## Configuration validation

Every deployed process runs `DeploymentSafety` at startup and refuses to start
on:

- a local-development credential, mail domain, or a loopback database/object
  store — i.e. an environment variable that was never supplied;
- a plaintext `http://` public base URL;
- a bootstrap API key shorter than 32 characters, or a known fixture key;
- `testinbox.limits.enabled=false` — a reachable deployment would be
  unprotected (ADR-027);
- **a proxy read timeout that does not exceed the wait window by at least 30s.**
  The ingress and the application read the same variable, so the pair cannot
  drift into the configuration where full-window long polls intermittently
  become 504s.

Failures name the *setting*, never the value.

## Vulnerability policy

Container scanning is **informational** on pull requests and `develop`, and
**blocking** on promotion to `master` for HIGH/CRITICAL findings *that have a
fix available*. The reasoning for both halves is in ADR-028's amendment; the
enforcement is the `enforce_vulnerability_policy` input on
`build-images.yml`, set only by `release-candidate.yml`.

## Related

- [staging.md](staging.md) — the staging environment, its secrets and its network boundaries
- [rollback.md](rollback.md) — rolling back artifacts, and when the schema makes that unsafe
- [release-process.md](release-process.md) — how a change travels from feature branch to production-approved
