# Cohort storage readiness — verification (TI-DELIVERY-002, stream D)

Verified 2026-10-30 against `ti-delivery-002/docs-synthetics` (origin/develop
`d17044d` plus documentation). This is a reading of code, tests and procedure,
not a rebuild and not a production observation. Nothing here was run against a
production or staging host, and no laboratory declaration is used as production
evidence.

## Verdict

**Updated 2026-10-30 (TI-DELIVERY-002b, branch `feature/ti-delivery-002b-storage`).**
The application side of ADR-037 is implemented and tested, and the release
paths that were proven only by the TI-DELIVERY-001 native rehearsal now have
automated tests (below). **Production still cannot run `ALL`** — not because
of an unconditional refusal any more, but because the one remaining
condition is Ops evidence that does not exist yet:

1. **Done in TI-DELIVERY-002b: the production `ALL` refusal is conditional.**
   `DeploymentSafety` (`ProductionAllConditions`) refuses
   `TESTINBOX_STORAGE_ENFORCEMENT=ALL` in production unless every
   startup-checkable condition holds: a shipped, enablement-eligible record
   matching the declared identity and the running upload implementation, with
   `platformClass` `production-host`, the ADR-035 Amendment 2 `filesystem`
   elements (the measured *O_max* and E7 figure) and E1–E11 recorded as
   `PASS`, not contradicting itself; a declared *O_max* at least the measured
   one; a declared node identity; declarations that hold the thirty shares,
   the worst envelope and finalization before any legacy share; a computable
   cohort binding. Each unmet condition is named; nothing can skip them. The
   pool, observations, nodes and role separation stay runtime gates
   (admission answers `CAPACITY_UNAVAILABLE`). `TENANT_LIMITS` stays refused
   in production for good, `OFF` and staging are unchanged. ADR-035 carries a
   dated amendment note (2026-10-30) recording this; ADR-037 already ties the
   refusal to "every explicit mitigation and required qualification record"
   and does not mandate an unconditional refusal, so there is no
   contradiction. Tests: `ProductionAllSafetyTest` (17 cases),
   `StorageEnforcementSafetyTest` — "ALL is refused in production without a
   production-qualified record, and allowed elsewhere",
   `DeploymentSafetyCheckTest` — "production ALL with every declaration and
   the shipped staging identity fails startup, naming the reason".
2. **Remaining: no shipped record satisfies it.** The binary declares
   `adr035-presigned-put-v2`; both shipped records declare `v1`, neither is
   `production-host`, and neither carries the Amendment 2 filesystem elements
   (`QualificationRecordsTest` — "no record shipped in this artifact can back
   production ALL - the Ops-evidenced v2 record does not exist yet"). No
   record was created, relabelled or promoted. What Ops must produce, and how
   the record is added once it exists, is in
   [production.md, "Storage activation"](../../dev/production.md#storage-activation).

Everything else on the path is either proven by tests (below) or is Ops
evidence the application cannot produce.

## What code and tests prove

Test names are quoted exactly; all run in the normal backend build (persistence
and API tests against real PostgreSQL via Testcontainers).

### The shares are fixed at 128 MiB payload / 256 MiB footprint

- Constants: `ParticipantStorageShare.PAYLOAD_BYTES = 128 MiB`,
  `FOOTPRINT_BYTES = 256 MiB`
  (`backend/domain/src/main/kotlin/email/testinbox/domain/storage/CohortStorageBudget.kt`);
  the thirty-share totals use `multiplyExact`.
- Database: V19 pins `participant_workspace.payload_limit_bytes = 134217728`
  and `footprint_limit_bytes = 268435456` by `CHECK`; runtime roles hold no
  `UPDATE`/`DELETE` on the marker. At runtime `CohortStorageRuntime` fails the
  pool if any participant budget differs from the two constants.
- Tests: `BetaAdmissionsTest` — "the database refuses increases to either
  accepted participant ceiling", "admission creates one durable fixed share and
  retry never allocates a second", "the database itself refuses a thirty first
  place and a second cohort"; `CohortStorageRuntimeTest` — "participant payload
  reservations stop exactly at 128 MiB across different inboxes";
  `SchemaUpgradeTest` — "V19 backfills admitted participant shares without
  reclassifying the legacy owner", "V19 refuses unidentified prior tombstones
  rather than losing participant classification".

### All thirty shares are reserved before the first admission, without overcommit

- `CohortStorageBudget.assess` always starts from all thirty payload and
  footprint shares, adds every explicit legacy share, the worst full envelope,
  finalization, and the debt, trash, probe, metadata and operational reserves,
  and checks global payload, global footprint and filesystem containment
  independently. Missing declarations or arithmetic overflow are `Unproven`,
  never a fit.
- `ReserveCohortStorage` is the sole pool writer; through `JdbcCohortBudgets` it
  requires `admissions_open = capacity_ready = false`, locks the cohort row and
  then the global T1 lock `(35,1)`, writes the pool and re-observes the live
  snapshot, rolling back on any mismatch. Legacy shares cannot shrink or
  disappear; a participant cannot hold a legacy share.
- Tests: `CohortStorageBudgetTest` — "reserve all thirty places and full
  worst-case event before the first admission", "an explicit legacy inventory
  adds every share without borrowing participant budget", "each boundary
  accepts equality and refuses one byte less independently", "footprint is not
  payload - tiny objects and metadata keep their full price", "neither
  overflowing shares nor overflowing event arithmetic can produce a proof",
  "invalid declarations and zero legacy exceptions are refused rather than
  silently free", "sum of reserved whole-workspace bounds dominates one combined
  bound"; `EffectiveStoragePolicyTest` — "cohort proof never reuses undeclared
  OFF defaults or missing allowances"; `CohortStorageRuntimeTest` — "approval
  requires exact inventory and compatible nodes and never opens admissions",
  "pool budget and configuration cannot silently shrink or mismatch";
  `CohortBudgetInputTest` — "operator input keeps explicit byte budgets and
  duration without HTTP or environment defaults".
- Reference arithmetic (M4b, not a declaration): with the reference footprint
  model and one ingestion process, the thirty shares plus the worst
  50-recipient/501-object envelope plus finalization need about 9.876 GiB of
  global footprint allowance and about 5.449 GiB of payload allowance **before**
  legacy shares and reserves.

### Enforcement happens in the one T1 snapshot, and cleanup stays charged

- `StorageAdmission` reads payload, objects, every reservation state, the
  workspace's attributed cleanup and the pool in one statement under the T1
  lock. The effective payload limit is the smaller of the configured workspace
  limit and the persisted share; footprint is the qualified F(bytes, objects)
  model over live + reserved + cleanup. Refusal order: inbox payload,
  workspace payload, workspace footprint, service capacity.
- Pending cleanup is released only by V21's `storage_release_workspace_charges()`
  — a later, fresh, trusted observation from the monitor role showing zero
  trash, ordered after the charge — never by global debt compaction, account
  deletion or an object vanishing from a listing.
- Tests: `StorageRefusalReasonTest` — "the closed set is the payload and
  ADR-037 footprint ceilings, narrowest first"; `CohortStorageRuntimeTest` —
  "all reservation states consume physical charge and cleanup refuses even a
  one-byte payload", "concurrent events cannot overrun their smaller workspace
  physical ceiling", "payload admission and tenant visibility use the same
  smaller persisted share", "cleanup charge survives global debt compaction into
  nonempty trash", "a pending or late charge cannot be released by an earlier
  empty observation", "wrong writer and untrusted observations cannot release a
  cleanup charge", "legacy OFF deletion does not grow per-workspace cleanup
  state"; `EnvelopePrivacyTest` — "foreign tenant refusal cannot change own
  footprint outcome at global boundary"; `EnforcementModesTest` — "ALL lets all
  three ceilings refuse, narrowest first"; `FootprintEnforcementTest` — "with
  trusted counts and a valid observation, the aggregate refuses as
  SERVICE_CAPACITY - a 250-class outcome".

### Admission cannot be opened by a flag

- `AdmitBetaParticipant` answers `CAPACITY_UNAVAILABLE` unless
  `capacity_ready` **and** the live readiness collaborator agree; the API wires
  that collaborator (`HumanAccountWiring.cohortStorageReadiness`) to
  `EffectiveStoragePolicy.footprint` + `JdbcFootprintGate` + `cohort.ready`,
  failing closed on any exception.
- Tests: `HumanAccountsApiTest` — "admission requires open policy and capacity
  then returns the same private membership" (`capacity_ready = true` with
  readiness false answers `CAPACITY_UNAVAILABLE`); `BetaAdmissionsTest` —
  "opening admissions without an approved capacity policy still refuses
  provisioning"; `StorageRoleModelTest` — "under psql the API locks the cohort
  row but can never open admissions or ready capacity".

### Node compatibility is fenced

- V21's `storage_cohort_node_fence` latches `storage_admission_latch` when a
  node that is not `cohort-storage-v1` / `ALL` / the saved binding registers or
  heartbeats while a plan or participant exists.
- Tests: `CohortStorageRuntimeTest` — "incompatible node restart latches even
  when mode accidentally returns to OFF", "a restored participant marker fences
  OFF nodes even without restored approval".

### Private usage matches enforcement

- `GET /v1/workspace/storage` (and the console alias) carry the optional
  `footprint` object (`WorkspaceFootprintUsage` in the contract): limit, used
  (pending cleanup included once), pending cleanup, available, over-limit,
  enforced. No global figure.
- Tests: `CohortStorageUsageTest` — "own physical usage includes reservations
  and pending cleanup without changing payload usage"; `StorageDisclosureTest` —
  "the contract's tenant schemas declare no global figure either";
  `StorageSdkTest` (Kotlin SDK) — "workspace physical charge and cleanup survive
  mapping without unknown service fields", "partial footprint is a protocol
  error rather than an invented cleanup zero".

### The release path, now proven in CI (formerly rehearsal-only)

TI-DELIVERY-001's M4d native rehearsal (with a **lab-only** startup record,
never enablement evidence) was the only proof of these paths. Each now has an
automated test in the normal backend build, on real PostgreSQL (and the
pinned MinIO for SMTP):

- **Unmocked admission readiness against a reserved pool** —
  `CohortAdmissionReadinessTest` (api module, isolated database): the
  production factory `HumanAccountWiring.cohortStorageReadiness` — the very
  `@Bean` method the API wires, unmocked — with a pool reserved by the real
  `ReserveCohortStorage`, admits through the real `AdmitBetaParticipant` over
  `JdbcBetaAdmissions` ("a reserved, matching pool and the real readiness bean
  admit participants with fixed shares"). `CAPACITY_UNAVAILABLE`, with nothing
  provisioned, for: no pool, a revoked pool, an overcommitted legacy share, an
  unknown workspace, an incompatible or undeclared node (and the fence
  latches), a node with another binding, untrusted counts, an observation of
  a smaller filesystem, and declarations that differ from the pool (another
  monitor, another node inventory, a non-`ALL` mode); `capacity_ready` cannot
  be bypassed in either direction. The Spring container itself is not
  started: cohort state is global, so the suite owns its database.
- **`storage-budget` end to end** — `CohortBudgetCommandTest`:
  `CohortBudgetCommand.run` with a reviewed JSON file against a real migrated
  database: check → reserve → check → revoke; a file differing from the
  installed pool; refusal while admissions or capacity are open (nothing
  written); wrong declarations (`OFF`, a `G_F` that cannot hold the shares,
  declarations the nodes were not started with — rolled back, a misspelt
  member, negative or fractional allowances); a missing, duplicated, empty or
  incomplete legacy inventory; credentials only from the environment; and the
  bounded operator transaction — a held cohort row aborts `reserve` at the
  5 s lock bound, observed waiting as `ops:cohort-budget`, with nothing
  written. (The command now tags its own session `ops:cohort-budget` and
  refuses credentials in the JDBC URL, as `admission` does.)
- **Quota refusal with uniform SMTP and nothing persisted** —
  `SmtpCohortQuotaRefusalTest` (ingestion, real SMTP gateway, real adapters,
  a READY reserved pool): a participant exactly at its 128 MiB payload share
  and one at its 256 MiB footprint share get byte-for-byte the unknown
  recipient's transcript; no message or attachment row, no upload, no listed
  object, no reservation, no ambiguity; only `inbox_storage` records
  `WORKSPACE_LIMIT` / `WORKSPACE_FOOTPRINT_LIMIT`. A legacy workspace in the
  same pool still receives (positive control).
- **Private footprint and pending cleanup over HTTP** —
  `FootprintUsageApiTest`: `GET /v1/workspace/storage` carries the six-member
  `footprint` object for a workspace with an explicit share; after the inbox
  is deleted and swept, the charge remains as `pendingCleanupBytes`, counted
  once in `usedBytes`, equal to T1's own charge from `JdbcFootprintGate` /
  `CohortSql`; no global member; another tenant sees no footprint.
- **Failure when capacity, configuration or node identity does not match** —
  at startup `ProductionAllSafetyTest` (missing/ineligible/mismatched/lab-only
  record, wrong upload version, another backend, missing or contradictory
  Amendment 2 elements, *O_max* one byte under, capacity that cannot hold the
  cohort, unproven or unbindable declarations, no node identity, a node
  outside the inventory); at runtime the readiness negatives above.

The deployment gate's `storage.test.mjs` now asserts the enforcement mode the
environment declares (`TESTINBOX_STORAGE_ENFORCEMENT_EXPECTED`, default
`OFF`; unit-tested in `deploy/synthetic/unit/storage-mode.test.mjs`) instead
of a hard-coded `OFF`, and `check-storage-activation.sh` has a gate G whose
detail and evidence JSON (`cohortGate`) state that the cohort gate is
`storage-budget check`; given that command's output it records PASS or
BLOCKED (self-tested in `check-storage-activation.test.sh`). Review fix: under
`ALL` without that output gate G is DEFERRED (overall `ACTIVATION DEFERRED`,
exit 3, never READY), and a PASS must be bound to the reviewed pool file by
the `reviewed-file sha256=` line the command now prints.

## What only Ops can establish

None of the following can be inferred by the application, and none may be
taken from a laboratory declaration, the 48 GiB image size or an OFF-mode
observation:

1. **Actual capacity**: the dedicated filesystem's real `C_fs`, `I_fs`, block
   size, and the budgets `G_F`, `D_budget`, `M`, `R_ops`, probe budget `P_F`,
   `A_obs`, plus `G` and the declared maximum ingestion processes (surge
   included). They must satisfy the containment inequalities and the cohort
   proof above, with all thirty shares, every legacy share, the worst envelope
   and finalization reserved.
2. **Legacy workspace inventory**: the exact list of every non-participant
   workspace in production (the owner workspace, the synthetic monitoring
   workspace, anything else), each with an explicit finite payload and
   footprint share at least its current load. An unknown workspace closes the
   pool.
3. **Grants**: the candidate `roles.reference.sql` applied after the migrator,
   with distinct API, ingestion and monitor roles. **Before any production grant
   change, Ops must fix MD1 and md1** (infinity-core
   `infra/testinbox/PRODUCTION.md`, "Known limitations"): a `DO` block or a
   pre-BEGIN `SET ti.*` in the reference can rewrite the grant contract's
   inputs, catalog calls can be shadowed through `search_path`, and schema- and
   database-level privileges (`pg_namespace.nspacl`, `pg_database.datacl`) are
   outside the contract and its fingerprint. This repository now guards the
   reference's shape (`scripts/check-roles-reference.sh`, below); that is a
   tripwire, not the fix.
4. **Node compatibility**: every API and ingestion node runs the candidate with
   `cohort-storage-v1`, the declared node ids are exactly
   `TESTINBOX_STORAGE_EXPECTED_API_NODES` / `_INGESTION_NODES`, and the
   reconciler's replacement model (fixed id stop-then-start, or distinct ids
   counted in the surge) is declared and evidenced (production.md, row W).
5. **Filesystem observations**: the monitor role is installed and writing
   observations through `storage_begin_observation()`, fresh within `A_obs`,
   with trusted footprint counts and a valid watermark; the filesystem identity
   matches the declaration.
6. **Qualification**: the production backend combination qualified with
   `slow-W` and E1–E11 on the recreated production filesystem, recorded as an
   enablement-eligible `production-host` record for upload implementation
   `v2` with the Amendment 2 `filesystem` elements (item 2 ships it; the
   evidence list is in production.md, "Storage activation").
7. **The offline reservation**: the reviewed pool JSON (declarations, exact
   legacy list, unattributed and trash allowances) and the schema-owner
   credential through the normal secret mechanism, session tagged
   `ApplicationName=ops:cohort-budget`.

## Activation, in order

Nothing below is authorized by this document. Each step needs the
consolidated TI-DELIVERY-002 release decision.

1. **Prerequisites outside storage**: the TI-DELIVERY-002 lifecycle (stream L:
   suspension, revocation, deletion, restore quarantine) and admission controls
   (stream A) integrated, released and reviewed; Ops MD1/md1 fixed.
2. **Item 2**: Ops qualifies the production combination; a pull request
   ships the eligible `production-host` `v2` record in the artifact.
3. **Item 1** (done in TI-DELIVERY-002b, pending review): `ALL` in production
   is permitted only under the stated conditions; `TENANT_LIMITS` stays
   refused.
4. **Promote** that artifact (release pull request, `Production handoff`), with
   `TESTINBOX_STORAGE_ENFORCEMENT=OFF` still in effect and accounts disabled.
5. **Data plane, in this order** (cohort-storage.md): stage the candidate
   backup scope and checker before the migrator (gate 3b); run the migrator;
   apply the candidate grants.
6. **Ops evidence**: declarations, legacy inventory, monitor observations,
   node inventory and replacement model; run
   `scripts/check-storage-activation.sh` and record its JSON in
   `production-ops-acceptance.md`. The script does not recompute the cohort
   proof: its gate G reports DEFERRED here (overall `ACTIVATION DEFERRED`,
   exit 3: every other gate passed) and names `storage-budget check` as the
   cohort gate (the evidence JSON says so in `cohortGate`); the proof is
   `storage-budget check`/`reserve` in step 9. Pass `--environment
   production`.
7. **Record the semantic rollback floor**: from the first pool row or
   participant marker there is no artifact rollback below this release, only
   the hold-first procedure (rollback.md, "Rolling back across TI-DELIVERY-001").
8. **Switch to `ALL`** with the complete declarations, reader first: API nodes,
   proven ready, then ingestion. Verify readiness, no admission latch, and the
   deployment synthetics.
9. **Reserve the pool offline**: `storage-budget check` with the reviewed file,
   then `reserve` (admissions and `capacity_ready` must be closed; it refuses
   otherwise), then `check` again. Re-run the activation script with
   `--cohort-check-output <that check's stdout> --cohort-pool-file <the
   reviewed file>`; it must report `ACTIVATION READY` (gate G PASS, bound to
   the file's SHA-256) before step 10.
10. **Open, deliberately**: set `capacity_ready`, then — when someone is
    watching admissions (see the sybil note in abuse-model.md) — enable accounts
    on API and site and open `admissions_open`. Run `npm run test:accounts`
    with `TESTINBOX_ACCOUNTS_EXPECTED=enabled` and the declared methods. From
    step 8 on, run the deployment gate with
    `TESTINBOX_STORAGE_ENFORCEMENT_EXPECTED=ALL`: `storage.test.mjs` asserts
    the declared mode, and fails if a workspace `footprint` report disagrees.

## The exact remaining engineering gap

- **Required before any production admission:** the Ops-evidenced,
  enablement-eligible `production-host` qualification record for upload
  implementation `adr035-presigned-put-v2`, with the Amendment 2 `filesystem`
  elements and E1–E11 recorded as `PASS`, shipped in the artifact by a later
  pull request — and the Ops evidence rows above (capacity, legacy inventory,
  grants after MD1/md1, node inventory and replacement model, monitor
  observations, the reviewed pool file). Nothing else in the application
  stands between that record and a conditional production `ALL`; the
  conditional rule itself awaits the consolidated release review.
- **Closed by TI-DELIVERY-002b:** the conditional production `ALL` rule; the
  CI tests of the unmocked readiness bean, `CohortBudgetCommand.run`, the
  `250`/nothing-stored footprint refusal and the populated HTTP `footprint`;
  gate G in `check-storage-activation.sh`; the declared-mode
  `storage.test.mjs`.
- **Added by this stream:** `scripts/check-roles-reference.sh` (and its test,
  run in CI) keeps `deploy/database/roles.reference.sql` in the reviewed shape
  until Ops fixes MD1/md1: no `set_config`, no `search_path`, no `ti.*` name,
  exactly one read-only `DO $guard$` block, only the three reviewed
  `SET testinbox.*_role` input lines, no psql meta-command but
  `ON_ERROR_STOP`. The reference **does** contain a `DO` block and `SET`
  statements today — its own input guard — so a blanket "no `DO`, no `SET`"
  rule would fail on the committed file; removing them is a change to the
  reference, which waits for the MD1/md1 fix. The guard allow-lists exactly
  what Ops reviewed instead.
