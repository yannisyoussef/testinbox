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

**Production** rolls back through `Production handoff` with the *previous
approved candidate SHA*: the same verification, the same digests that
candidate had, no rebuild ([production.md](production.md#rollback)). The
previous set is recorded in the earlier handoff's run summary and manifest
artifact, and in Ops's reconcile record.

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
tables (ADR-027), `V4`–`V6` only add tables, functions and triggers (see
below for what rolling back across `V4` and `V6` means), and `V7` adds one
table, `storage_clock_episode` (see below for why that does not make every
earlier artifact a safe rollback target).

## Rolling back across TI-STORAGE-001 (schema V6)

V6 (ADR-035 accounting foundation) is expand-only: it adds seven tables, two
trigger functions, six statement-level triggers on `message` and `attachment`,
and `storage_account_recompute()`, then backfills. An older artifact starts
cleanly against it, because a schema ahead of the artifact is healthy. The
TI-STORAGE-001 pull request records the rehearsal: the persistence, ingestion,
API and migrator suites of the pre-V6 commit, run against a V6 schema.

- **The triggers stay active after a rollback.** They belong to the schema,
  not the artifact, so an older artifact's ingestion and retention writes keep
  appending to `storage_delta` without knowing it.
- **The rolled-back artifact never folds those deltas.** It has no compactor,
  so the ledger grows by about one row per writing statement until a V6-aware
  artifact returns. Nothing on a live path reads the ledger to decide anything yet (the TI-STORAGE-002 admission core is not wired), so this
  costs rows, not correctness. When the V6-aware artifact returns, its
  compactor drains the backlog in bounded passes.
- **If the backlog is ever unwanted,** run `SELECT storage_account_recompute();`
  to rebuild every base from the rows and empty the ledger in one transaction.
  It takes the same table locks as V6, and it sets no timeout of its own, so
  run it off-peak inside `BEGIN; SET LOCAL lock_timeout = '30s'; SELECT
  storage_account_recompute(); COMMIT;`.

V6 takes `SHARE ROW EXCLUSIVE` on `workspace`, `inbox`, `message` and
`attachment` with `lock_timeout = 30s`. If it cannot get them, it fails without
applying anything and the deployment stops as described below. Retry it when
the long-running writer has gone.

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

## Rolling back across TI-STORAGE-003 (the guarded ingest protocol)

From TI-STORAGE-003 on, live ingestion writes every object through the ADR-035
reservation fence. The API side runs reservation cleanup and ambiguity
verification. An artifact from before that commit would do three harmful
things:
- write objects that no reservation covers;
- leave any reservations in flight charged forever, because it runs no
  cleanup;
- ignore the admission latch.

The first live guarded-ingest commit is therefore a rollback **floor**
(`c84ddd7`). It is not the last one. The TI-STORAGE-003 commits that followed it
fixed release-safety defects in that same protocol: an upload `Error` that
returned its write slot with no ambiguity recorded, and a clock-offset hold
that a crash could erase (fixed by V7's durable clock episode, and by refusing
every release while an episode is recorded). An artifact between those two
points has a live ADR-035 release path that lacks those protections. So the
final TI-STORAGE-003 commit is a second floor (`d4e38b2`, the **safety
floor**): **artifacts below the final TI-STORAGE-003 safety floor are not
eligible for rollback once the guarded protocol is deployed.** Both floors stay
in `deploy/rollback-floors.txt`; a floor is never removed.

- **Staging.** `deploy.sh` now enforces floors itself, before the migration
  job or any service is touched (ADR-035 §18 gate 5). It reads the
  `org.opencontainers.image.revision` label of the api and ingestion images,
  and runs `scripts/check-rollback-floors.sh`, the same check the production
  gate uses. An image below a floor, or with no revision label, aborts the
  deployment. `TESTINBOX_ACKNOWLEDGE_ROLLBACK_HAZARD=true` proceeds anyway,
  with a warning, and only if the hazard is understood and announced.
- **Production.** The production handoff reads floors from `master`, so this
  floor becomes a production floor only when it is promoted through the
  normal release pull request.

This is what `deploy/rollback-floors.txt` encodes: V4's commit is a **floor**,
and `verify-production-candidate.sh` refuses a production candidate that does
not contain it unless `acknowledge_rollback_hazard` is set on the dispatch —
and then still warns in the log. A schema check alone would have called this
rollback safe. Any future break of the same shape is added there, never
removed.

**Schema V7** (`storage_clock_episode`) is expand-only: one new table, and
there is no down migration. Expand-only does not make an older artifact safe.
An artifact from before TI-STORAGE-003 has no ADR-035 release path, and is
below the first floor anyway. An intermediate guarded-ingest artifact, between
`c84ddd7` and the safety floor `d4e38b2`, DOES release reservations. It does not
know V7, so it would ignore a recorded clock episode and could release on the
plain horizon. That is exactly why it sits below the safety floor and is
refused, unless the hazard is explicitly acknowledged.

**Schema V8** (TI-STORAGE-006E, the filesystem-containment contract) is
expand-only:
- three `NOT NULL DEFAULT 0` columns (`storage_delta.objects`,
  `workspace_storage_account.base_objects`, `inbox_storage.base_objects`);
- the debt ledger, observation, walk, watermark and trust tables, and the
  ordering sequence;
- the ledger trigger bodies replaced to count objects and append deletion
  debt (`SECURITY DEFINER`, so no role needs a new grant);
- refusal triggers that stop nothing any artifact does;
- a recompute under the V6 lock set, taken after the ledger advisory lock.

No down migration. A rolled-back artifact at or above the TI-STORAGE-006
floor keeps working. Its inserts, deletes and reservation releases run the
new trigger bodies, which count for it.

**Its compactor does not know `storage_delta.objects`, and V8 compensates in
the same statement.** A statement trigger on `storage_delta` folds the object
counts that compactor drops (`testinbox.ledger_counts` is unset in its
transaction), so the counts stay exact. It also revokes trust in them
(`storage_footprint_trust`). Trust comes back only through a reconciliation
pass that finds no workspace-scope drift, under the ledger lock and by
compare-and-set (`StorageFootprintLedgerTest` replays the verbatim pre-V8
fold, and a rollback/roll-forward sequence).

While untrusted, footprint admission answers `451` under `ALL` (PR D), and
`testinbox_storage_footprint_counts_trusted` reads 0. What rollback DOES
lose: the older artifact neither observes the footprint nor compacts debt.
Both only observe while enforcement is `OFF`.

**Schema V9** (TI-STORAGE-006E PR D) is expand-only:
- three columns with constant or stable defaults (`storage_deletion_debt.recorded_at`,
  `storage_ambiguity.held_by_rule_p`, `storage_footprint_trust.distrusted_seq`;
  no table rewrite) and a partial index on pending rows;
- the trust-stamp and latch-hold triggers;
- the two probe-only debt functions.

A rolled-back artifact never names a new column. The upgrade stamps a distrust
event, so footprint admission (where it enforces) waits for an observation
taken after it. **Rolling back below PR D once footprint admission enforces is
forbidden, not merely unsafe:** an older artifact's orphan sweep, verifier
and probes delete without the rule-(P) pending rows, so containment no longer
holds while it runs (contract §4.5). `deploy/rollback-floors.txt` names the
PR D artifact as the floor before `ALL` is ever enabled; below it, the
bucket-quota fuse remains the only physical link.

**Schema V10** (TI-STORAGE-006E, owner review b) is expand-only. It adds:
- two nullable columns on the trust row (`trusted_seq`, `trusted_at`);
- a constant-default column `storage_node.containment` (no table rewrite);
- the table `storage_sweep_run`;
- the trust guard trigger, and the definer functions
  `storage_confirm_footprint_trust()`, `storage_begin_sweep(text)` and
  `storage_complete_sweep(bigint, bigint)`.

The upgrade is a distrust event, so admission under `ALL` waits for the
verifying function to mark the counts trusted again. No artifact before PR D
writes or marks trust, so a rollback to one is unaffected.

An artifact from before V10 that tried to mark trust directly would be
refused by the guard trigger. Its reconciliation would report `failed`, and
the counts would stay untrusted. That is fail-closed, and only PR D artifacts
ever marked trust.

V10 also makes compaction and repair the definer functions
`storage_compact_ledger(integer)` and `storage_repair_ledger()`, so the
ledger is written only by the database.
- An artifact from before V10 still compacts with its own statement, which
  needs the earlier ledger grants. Revoke them only once every running
  artifact is from V10 on.
- If an older artifact's compaction is refused for lack of them, its deltas
  simply stay unfolded. That is still exact, because base + Σdelta does not
  change.

An earlier artifact's node rows carry `containment = 0`. Every write or
deletion of such a row stamps `storage_containment_watermark`, which only
grows, so its activity stays visible after the row is reaped.
- Gate F refuses `ALL` while any such row is unclean (neither shut down
  cleanly nor reaped).
- Gate F also refuses until a complete sweep has started after the
  watermark.

## Adding a `StorageRefusalReason` is reader-first (TI-STORAGE-004)

The API reads `inbox_storage.last_refusal_reason` into the closed
`StorageRefusalReason` enum and **fails closed** on a value it does not know
(`JdbcStorageVisibility`): a positive refusal count with an unreadable reason is
treated as corrupt state and answers `500 internal-error` on `GET /v1/inboxes/{id}`,
on the live members of `POST /v1/inboxes`, and on every wait against that inbox.
No made-up reason is ever shown to a tenant.

So a fourth reason is not a free additive change. It must be deployed
**reader-first**: the API digest that knows the new value is deployed and
recorded as a rollback floor before any ingestion artifact may write it.
Rolling the API back below that floor while the new value exists in a row
reproduces the `500`s above. The wire contract already tells clients to
tolerate unknown values; this rule is what keeps the server able to produce them.

## Rolling back across TI-STORAGE-006 (enforcement controls)

TI-STORAGE-006 adds no migration and every new key defaults to `OFF` or
empty, so an older artifact starts cleanly against the same schema, and under
`OFF` an old API (which never claimed a node id) coexists with a new one.

What rollback must never do is run an artifact without the enforcement
controls while a ceiling is enforced:

- **Set `TESTINBOX_STORAGE_ENFORCEMENT=OFF` on BOTH deployables first, then
  roll back.** A TI-STORAGE-003/004/005 ingestion artifact has no enforcement
  setting and no activation guard; it names its sessions `storage-v1` and
  registers in `storage_node` exactly like the TI-006 one, so the barrier
  cannot tell them apart and would admit every message above every ceiling
  with every gate green. The TI-STORAGE-006 commit is therefore a rollback
  **floor** in `deploy/rollback-floors.txt`, and `check-storage-activation.sh`
  gate E refuses a running artifact below it.
- Rolling the API back under a non-OFF mode reports the API absent from the
  inventory (the old API registers nothing); mail is still admitted, the
  violation gauge stays 1 until the API returns or enforcement is set OFF.
- `OFF` is the escape hatch in every direction: it must be in place on both
  deployables before either artifact changes.
