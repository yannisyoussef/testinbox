# Cohort storage readiness — verification (TI-DELIVERY-002, stream D)

Verified 2026-10-30 against `ti-delivery-002/docs-synthetics` (origin/develop
`d17044d` plus documentation). This is a reading of code, tests and procedure,
not a rebuild and not a production observation. Nothing here was run against a
production or staging host, and no laboratory declaration is used as production
evidence.

## Verdict

**The application side of ADR-037 is implemented and tested; it cannot be
activated in production by configuration alone.** Two engineering items stand
between today's artifact and a first participant admission with storage
enforcement, and both are deliberate gates rather than defects:

1. **`DeploymentSafety` refuses `TESTINBOX_STORAGE_ENFORCEMENT=ALL` in
   production unconditionally**
   (`backend/application/src/main/kotlin/email/testinbox/application/deployment/DeploymentSafety.kt`,
   the `ALL`/`TENANT_LIMITS` + `ProductionPolicy.ENVIRONMENT` checks;
   `StorageEnforcementSafetyTest` — "ALL is refused in production until the
   envelope-order mitigation exists, and allowed elsewhere", "TENANT_LIMITS is
   refused in production - dark staging qualification only, it does not contain
   the filesystem"). Admission needs an `ALL`-mode pool: `ReserveCohortStorage`
   requires `enforcement == ALL`, `CohortStorageRuntime.ready` returns `false`
   for any other mode, and V21's node fence requires `cohort_mode = 'ALL'` once
   a plan or participant exists. **So no participant can be admitted in
   production while that refusal stands.** The refusal names mitigations A
   (envelope-order oracle) and B (single-sender amplification) of ADR-035
   Amendment 2 §A2.7; ADR-037 accepted the bounded-beta answer to both (§5
   full-envelope pricing, implemented as M4a; §1–§2 non-overcommitted shares,
   M4c/M4d), but ADR-037 also says "Do not remove the production ALL refusal
   until every explicit mitigation and required qualification record is
   independently reviewable and tested". Lifting it is therefore a reviewed
   code change — scoped, for example, to "ALL in production only with a
   complete cohort declaration" — made under the consolidated release decision.
   It is not written here. `TENANT_LIMITS` stays refused in production for
   good (ADR-037).
2. **No shipped qualification record matches the current artifact.** The
   binary declares upload implementation `adr035-presigned-put-v2`
   (`StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION`); both records in
   `backend/storage/src/main/resources/adr035-qualification/` declare `v1`, and
   the match compares the upload version (`DeploymentSafety`,
   `QualificationMatch.compareAll`). The staging record is
   `enablementEligible: true` but stale for v2; the laptop record is not
   eligible (`QualificationRecordsTest` — "the artifact ships the laptop record,
   and it is loaded, complete and NOT enablement-eligible";
   `DeploymentSafetyCheckTest` — "declaring the laptop backend identity still
   refuses - its record has no runtime-config hash, so no match completes"). A
   lab record is refused outside `TESTINBOX_ENVIRONMENT=lab`, and `lab` cannot
   wear the production profile (`StorageEnforcementSafetyTest` — "a lab record
   is refused in production, on top of everything production refuses", "the lab
   environment cannot wear the production profile"). So **no non-lab
   environment can run a non-OFF mode with today's artifact**. Closing this
   needs Ops to qualify the production backend combination (ADR-035 §18 gate 7a,
   including `slow-W`) and an engineering pull request that ships that record.

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

### What is proven only by the TI-DELIVERY-001 native rehearsal, not by CI

These were demonstrated once, end to end, in the M4d real native rehearsal
(isolated `ALL` deployment with a **lab-only** startup record — explicitly not
enablement evidence; see `ti-delivery-001/STATUS.md`, M4d). They are not
automated tests in this repository:

- the real, unmocked `cohortStorageReadiness` bean admitting against a reserved
  pool (API tests mock it; persistence tests drive the same predicate through a
  helper);
- an SMTP `250` with no message stored for a `WORKSPACE_FOOTPRINT_LIMIT`
  refusal (no ingestion/E2E test asserts the reply for that reason);
- `java -jar api.jar storage-budget reserve|check|revoke` end to end
  (`CohortBudgetCommand.run` has no test; its input parser and the use case it
  calls do);
- an HTTP response carrying a populated `footprint` object (API HTTP tests run
  with storage `OFF`).

These are coverage gaps, not known defects. Each is worth an automated test
before activation; none blocks reading the design as correct.

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
   `slow-W` and recorded as an enablement-eligible record for upload
   implementation `v2` (engineering item 2 ships it).
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
2. **Engineering item 2**: Ops qualifies the production combination; a pull
   request ships the eligible `v2` record in the artifact.
3. **Engineering item 1**: a reviewed change permits `ALL` in production under
   the stated conditions; `TENANT_LIMITS` stays refused.
4. **Promote** that artifact (release pull request, `Production handoff`), with
   `TESTINBOX_STORAGE_ENFORCEMENT=OFF` still in effect and accounts disabled.
5. **Data plane, in this order** (cohort-storage.md): stage the candidate
   backup scope and checker before the migrator (gate 3b); run the migrator;
   apply the candidate grants.
6. **Ops evidence**: declarations, legacy inventory, monitor observations,
   node inventory and replacement model; run
   `scripts/check-storage-activation.sh` and record its JSON in
   `production-ops-acceptance.md`. Note that the script has **no cohort gate**:
   it does not read `storage_cohort_plan`, participant shares or legacy shares
   (only gate F's privilege query mentions the cohort tables). The cohort proof
   is `storage-budget check`/`reserve` in step 9.
7. **Record the semantic rollback floor**: from the first pool row or
   participant marker there is no artifact rollback below this release, only
   the hold-first procedure (rollback.md, "Rolling back across TI-DELIVERY-001").
8. **Switch to `ALL`** with the complete declarations, reader first: API nodes,
   proven ready, then ingestion. Verify readiness, no admission latch, and the
   deployment synthetics.
9. **Reserve the pool offline**: `storage-budget check` with the reviewed file,
   then `reserve` (admissions and `capacity_ready` must be closed; it refuses
   otherwise), then `check` again.
10. **Open, deliberately**: set `capacity_ready`, then — when someone is
    watching admissions (see the sybil note in abuse-model.md) — enable accounts
    on API and site and open `admissions_open`. Run `npm run test:accounts`
    with `TESTINBOX_ACCOUNTS_EXPECTED=enabled` and the declared methods. The
    deployment gate's `storage.test.mjs` still passes under `ALL` (a fresh inbox
    records no refusal), but its name and message say enforcement is `OFF`;
    adjust them at this step so the gate does not assert something untrue.

## The exact remaining engineering gap

- **Required before any production admission:** items 1 and 2 above — the
  conditional lifting of the production `ALL` refusal, and an eligible
  qualification record for upload implementation `v2` (after Ops qualifies
  the production combination).
- **Recommended before activation** (coverage, not correctness): an automated
  test of the unmocked readiness bean against a reserved pool; an ingestion or
  E2E assertion that a `WORKSPACE_FOOTPRINT_LIMIT` refusal answers `250` and
  stores nothing; an end-to-end test of `CohortBudgetCommand.run`; an HTTP test
  with a populated `footprint`; a cohort gate in
  `check-storage-activation.sh` (or an explicit statement in its evidence
  format that `storage-budget check` is the cohort gate); and the
  `storage.test.mjs` rename in step 10.
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
