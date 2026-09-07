# ADR-029: Schema Migration Execution Model

**Status:** Accepted (amends [ADR-001](0001-architecture-style.md): a third,
one-shot backend deployable)

## Context

Both deployables — the REST API and the ingestion gateway (ADR-001) — embed
the `persistence` module, and both currently run Flyway on startup
(`spring.flyway.enabled: true`). That is correct for local development, where
a single process owns an empty database. It is wrong for a deployed
environment, for three separate reasons:

1. **Racing executors.** Two processes (and later, replicas of each) starting
   concurrently race to apply the same migration. Flyway takes an advisory
   lock so the race is not usually *corrupting*, but it makes migration timing
   nondeterministic and makes "did the migration succeed?" a question with no
   single answer to record as deployment evidence.
2. **No deployment gate.** If migration is a startup side effect, there is no
   point in the pipeline at which the deployment can be aborted because the
   schema change failed. The first symptom is a crash-looping service.
3. **Serving against an incompatible schema.** A process that starts *before*
   the migration it needs has been applied will happily accept traffic and
   fail per-request. Nothing structurally prevents it.

Migration is also the one part of a deployment that artifact rollback (ADR-028)
cannot undo: rolling an image back is a second-scale operation, reversing a
migration is not.

## Decision

1. **Exactly one logical migration executor.** A dedicated deployable,
   `backend/migrator`, runs Flyway to completion and exits. It is built from
   the same commit as the API and ingestion images and carries the identical
   `db/migration` resources, so the migrations applied are by construction the
   ones the deployed artifacts expect.

2. **Deployed applications never migrate.** In any deployed profile the API
   and ingestion set `spring.flyway.enabled: false`. Local development and the
   test suites keep startup migration — there is one process, an empty
   database, and no deployment to gate.

3. **Migration failure aborts the deployment.** The migrator exits non-zero;
   the deployment step fails; application services are not started or updated.
   There is no `continue-on-error` on this path.

4. **Applications refuse traffic against a schema they do not recognise**, in
   two places, because one is not enough.

   Each deployable computes the highest migration version bundled in its own
   artifact and compares it with `flyway_schema_history`. Readiness is
   OUT_OF_SERVICE when the applied version is lower than the bundled version,
   or when the history contains a failed migration.

   Readiness alone would be sufficient under an orchestrator that stops
   routing to a node that reports not-ready. It is **not** sufficient in the
   topology this project actually ships (ADR-030 Option A): the reverse proxy
   forwards unconditionally and Docker Compose neither restarts nor removes an
   unhealthy container, so a node whose readiness dropped would keep receiving
   requests and failing them individually. The window is not hypothetical —
   `restart: unless-stopped` brings the applications back after a host reboot
   with no migration job in between.

   So the refusal is also enforced at request time: the API answers `/v1` with
   `503` + `Retry-After` (`schema-unavailable`), and the SMTP gateway soft-fails
   the `DATA` transaction with `451` so the sending MTA retries and nothing is
   lost. The verdict is cached briefly rather than queried per request.

   Note the asymmetry, which is deliberate: applied *higher* than bundled is
   **not** an error. That is the normal state during a rollback to a previous
   artifact, and forbidding it would make artifact rollback impossible after
   any migration.

5. **No automated migration rollback.** Flyway `undo` is not used and
   down-migrations are not written. The rollback model is forward-compatible
   migrations plus artifact rollback (ADR-028), documented in
   `docs/dev/rollback.md`.

6. **Migrations are expand-only for now.** A migration must leave the previous
   application version able to run: add columns/tables/indexes, do not drop or
   narrow. A change that breaks this must say so explicitly, because it makes
   artifact rollback across that migration unsafe, and that must be visible
   before promotion rather than discovered during an incident.

7. **Migration output carries no secrets.** The migrator logs the schema
   version and the applied migration count; it never logs the JDBC URL with
   credentials, the password, or connection properties.

## Alternatives considered

- **Keep startup migration and rely on Flyway's lock.** Rejected: the lock
  prevents corruption, not nondeterminism. It gives no deployment gate and no
  evidence artifact, and leaves (3) unsolved.
- **Run migrations from the API image with a `--migrate-only` flag.** Rejected
  as the primary mechanism: it couples the migration job's failure modes and
  startup surface (web server, schedulers, LISTEN connection, object-storage
  client) to a job that should do one thing. A separate deployable is smaller,
  starts faster, and fails for exactly one reason.
- **Run migrations from CI against the database directly.** Rejected: it needs
  database credentials and network reachability in the CI runner rather than
  inside the deployment boundary, and it decouples the migration binary from
  the deployed artifact — the drift ADR-028 exists to prevent.
- **A schema-version *startup* check that refuses to boot.** Rejected in
  favour of readiness: a process that boots and reports not-ready is
  diagnosable and observable; one that exits immediately produces a crash loop
  and no signal beyond restart counts.

## Consequences

- The deployment sequence gains an explicit ordered step:
  `pull artifacts → run migrator to completion → start/update services →
  wait for readiness`.
- A new module (`backend/migrator`) and a third container image exist. Both
  are small; the migrator image is the backend runtime plus Flyway.
- Local development is unchanged: `docker compose up -d` then
  `./gradlew :api:bootRun` still migrates on startup.
- The bundled-vs-applied version comparison must be tested in all three
  directions (behind, level, ahead) or the rollback path in (4) is unproven.
- The request-time refusal adds a cached database read to the request path. The
  cache TTL bounds both the cost and how long a recovered node keeps refusing.
