# Rollback

Two different things can be rolled back, and they have very different
properties. Conflating them is how a rollback turns an outage into data loss.

| | artifact rollback | database rollback |
|---|---|---|
| mechanism | deploy the previous digest set | **not automated, and will not be** |
| time | seconds | — |
| risk | low | high |

## Rolling back artifacts

A deployment is a set of four digests (ADR-028). Rolling back is deploying the
previous set — the same script, the same validation, no rebuild:

```
current                                     previous
api@sha256:AAA…                             api@sha256:111…
ingestion@sha256:BBB…          ────▶         ingestion@sha256:222…
web@sha256:CCC…                             web@sha256:333…
migrator@sha256:DDD…                        migrator@sha256:444…
```

**Where to find the previous set:** the "Staging deployment" summary of the
last successful `Staging` workflow run records all four digests, the commit and
the timestamp. That is why the evidence exists.

**How to roll back:** run the `Staging` workflow via *Run workflow*
(`workflow_dispatch`) and supply all four references. They are validated twice
before they leave GitHub — ownership *and* digest-pinning, by
`scripts/validate-image-digest.sh` (15 negative self-tests) and again inside
`scripts/gitlab-handoff.sh` — so an arbitrary image from an arbitrary registry
cannot be handed to Ops this way.

The handoff carries the digest set; **Ops performs the rollback**, and the
result is visible there, not in the GitHub run.

Or, for the self-hosted reference topology, on the host directly:

```bash
TESTINBOX_API_IMAGE=ghcr.io/<owner>/testinbox-api@sha256:111… \
TESTINBOX_INGESTION_IMAGE=… TESTINBOX_WEB_IMAGE=… TESTINBOX_MIGRATOR_IMAGE=… \
  ./deploy/staging/deploy.sh
```

Note that the rolled-back migrator runs too. That is intentional and harmless:
the schema is already at or beyond the version it carries, so it applies
nothing and exits 0.

## Why the database is not rolled back

Flyway `undo` is not used and down-migrations are not written. An automated
reverse migration is a script that runs, unreviewed and under time pressure,
against the only copy of production data in the middle of an incident. The
model instead is:

**forward-compatible migrations + artifact rollback**

Migrations are **expand-only**: add columns, tables and indexes; do not drop,
rename or narrow. As long as that holds, the previous artifact runs correctly
against the newer schema — it simply ignores what it does not know about — and
artifact rollback is always available.

This is enforced at run time rather than by convention: each deployable
compares the highest migration bundled in its own artifact with
`flyway_schema_history`, and a schema *ahead* of the artifact is explicitly
healthy. A stricter equality check would look tidier and would make every
rollback after a migration impossible.

## When artifact rollback is NOT safe

`scripts/check-migration-safety.sh` now enforces the expand-only rule in CI
rather than leaving it to review. It fails the build on an undeclared
rollback-breaking construct, and a migration that genuinely intends to break
compatibility must say so in the file:

```sql
-- testinbox:rollback-unsafe: legacy_flag unread since v0.4; contract release only.
ALTER TABLE inbox DROP COLUMN legacy_flag;
```

A declaration does not make it safe — it makes it *visible*, and the
promotion gate then blocks the release until it is handled deliberately
(see [release-process.md](release-process.md)).

**What the gate cannot see**, and why `.github/CODEOWNERS` covers migrations:
constraint *tightening*. Dropping a constraint and adding a narrower one reads,
statement by statement, exactly like the widening ADR-026 legitimately did in
V2. Also invisible to it: a backfill that assumes the new code is already
deployed, and a change that is technically additive but semantically
incompatible. Those need a human.

A migration breaks the property above if it does any of:

- drops or renames a column, table or index the previous artifact reads;
- narrows a type or adds a `NOT NULL` without a default;
- adds a constraint the previous artifact's writes would violate;
- changes the meaning of existing data in place.

**A pull request containing such a migration must say so in its description,
and must not be merged to `develop` without a plan.** The safe shape is to
split it across two deployments — expand, deploy, migrate data, deploy the
artifact that stops using the old column, and only then contract in a later
release. Between those, rollback stays available at every step.

Nothing currently in `db/migration` is of this kind: `V1` creates the schema,
`V2` replaces a unique index with a wider one (ADR-026), `V3` adds the limits
tables (ADR-027).

## If the migration itself fails

The deployment stops there. `deploy.sh` runs the migrator to completion and
aborts on a non-zero exit **before** any service is started or updated, so the
previously deployed artifacts keep running against the schema they already
had. There is nothing to roll back — investigate the migrator's output, fix
forward, deploy again.

If a migration failed *partway* and left a failed row in
`flyway_schema_history`, every deployable refuses readiness **and refuses
traffic** until it is resolved — the API with `503 schema-unavailable`, the
gateway with an SMTP `451` so senders retry. That is deliberate: a half-applied
schema should take the environment out of service rather than serve against it,
and readiness alone would not achieve that in this topology (see
[staging.md](staging.md#health-and-readiness)).

## Recovering staging from scratch

Staging holds no data worth preserving (see
[staging.md](staging.md#data-retention-and-reset)). If it is badly wedged, drop
the volumes and redeploy rather than repairing by hand.

## Rolling back across TI-002 (schema V4) revokes every managed credential

The V4 migration is expand-only and an older artifact starts cleanly against
it — `SchemaUpgradeTest` proves both. The *behavioural* consequence is not
covered by that, and it is severe:

**A pre-TI-002 artifact cannot authenticate any managed API key.** It looks up
`SHA-256` of the whole presented token; TI-002 stores `SHA-256` of the secret
component only, and resolves by public id. The verifiers hash different inputs,
so every `ti_k1_...` credential minted since TI-002 stops working the moment
the older artifact is running. Only the configured bootstrap credential
survives, because its verifier hashes the whole token in both versions.

So a rollback across V4 is an **outage for every API client**, not a silent
degradation, and it is not fixed by rolling forward alone — clients keep
working once the newer artifact returns, but everything in between fails with
`401`.

Before rolling back across V4:

1. Confirm `testinbox.bootstrap.api-key` is configured and known, or you will
   have no working credential at all.
2. Expect and announce client-visible `401`s for the duration.
3. Prefer rolling forward with a fix. This is one of the cases the ADR-028
   promote-by-digest model makes cheap.
