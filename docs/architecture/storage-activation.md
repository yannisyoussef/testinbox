# Storage activation — the ADR-035 §14 barrier as an executable contract

**Status:** enforcement is **OFF** on every committed environment and in both
binaries' defaults (gated in CI by `scripts/check-storage-enforcement-off.sh`).
The barrier evaluator `scripts/check-storage-activation.sh` exists and is
self-tested; it has **not** reported `ACTIVATION READY` for any environment,
and at the current repository state it **cannot** (see "Expected current
state"). Nothing in this document turns enforcement on.

> Authority: ADR-035 §9, §9a, §11, §14, §16, §18. This document elaborates; it
> does not amend. Where they disagree, the ADR wins.

## What this is

ADR-035 ships the whole storage protocol — slots, reservations, the fence,
cleanup, compaction, reconciliation, ambiguity tracking, heartbeat — in
**Phase 2 (coexistence)**, with `testinbox.storage.enforcement=OFF`. Nothing is
refused. **Phase 4 (enforce)** is reached by Ops setting the same digests to
`TENANT_LIMITS` (inbox and workspace ceilings) and then `ALL` (the global
ceiling). The build, the image digest, the handoff and the Staging workflow
are identical in both phases: *only configuration differs*.

So the question "is it safe to turn enforcement on here?" cannot be answered
by anything that happened in GitHub. §14 answers it with a **proven state**:
five checks plus the §9a qualification, each of which can fail on its own,
each of which is evaluated against the *deployed* environment, and all of
which must hold at once. `check-storage-activation.sh` evaluates them from
inputs Ops hand it, prints a table, and writes an evidence record. The verdict
is `ACTIVATION READY` or `ACTIVATION BLOCKED`; an unevaluated gate is
`NOT RUN`, and **NOT RUN blocks** — an unevaluated gate is not a passed one.

## The gates

| Gate | ADR-035 | What must hold | Evaluated from |
|---|---|---|---|
| **A-sessions** | §14 (a) | Every client session of the APPLICATION database role (`--application-role`; without it every role is evaluated, which is stricter than the ADR) has `application_name ~ '^testinbox-[^:]+:[^:]*:storage-v1$'`, or is a named exclusion (`testinbox-migrator:%`, `ops:%`). Sessions of other roles (a backup role, an exporter, a DBA) are out of scope: at run time the application role cannot even see them (PostgreSQL hides other roles' session columns from a role without `pg_read_all_stats`), and the checker applies the same scope with `--application-role`. A human connecting AS the application role must tag `ops:<purpose>`. pgJDBC's default name, the old bare `testinbox-listen`, and an empty name are each a violation, reported by name. At least one storage-v1 session must be seen. | `pg_stat_activity` (`backend_type='client backend'`, current database) via `TESTINBOX_ACTIVATION_DB_URL`, or `--sessions-file` |
| **A-inventory** | §14 (a) | The declared node set (`--expected-api-nodes`, `--expected-ingestion-nodes`) is **exactly** the set of `storage_node` rows with `capability='storage-v1'`, `NOT clean_shutdown`, heartbeat within 5 minutes. A missing declared node, a stale/absent heartbeat, a wrong capability, a clean shutdown and an extra undeclared node are each their own message. | `storage_node`, or `--nodes-file` |
| **B-physical-baseline** | §14 (b), §9 | On every API node: `orphan_sweep_completed_at_seconds > process_start_time_seconds` (a **full** sweep completed since this candidate became active; `0` = never) **and** `physical_listed_bytes ≤ covered{committed} + covered{reserved} + finalize_budget_bytes (H)`. Blocked with the figures. | `--api-metrics` |
| **C-clock-offset** | §14 (c), §6 | On **every** declared node: `testinbox_storage_clock_offset_seconds` present and `|offset| ≤ ε_max` (30 s; `--max-clock-offset-seconds`). Absent = "no offset measured" = blocked. Also blocked if any node has `admission_latched=1` or `breaker_open=1`. A declared node with no metrics endpoint is unmeasured, and blocks. | `--api-metrics`, `--ingestion-metrics` |
| **D-benchmark** | §14 (d), §11 | `--mode TENANT_LIMITS`: **NOT REQUIRED** (never PASS; the gate does not decide). `--mode ALL`: the §11 staging-host-class evidence (`schemaVersion` 1) has `verdict: PASS`, says `adrEvidence: true` (the harness itself records every departure from §11 as `adrEvidenceReasons`), **and** the script's own recomputation from `scenarios` agrees against constants PINNED in the script and never read from the evidence: over the CHOSEN scenarios at 520/s (2 × 260) `achievedRate/offeredRate ≥ 0.995` and `percentiles.t1.p99Ms ≤ 50` **at concurrency 10/25/50/100** (concurrency 1 is the uncontended baseline: required for coverage, judged on the retention ratio and the integrity criteria, not on sustained load or T1), and at every CHOSEN 2× scenario `percentiles.retention.p99Ms ≤ 2×` the REFERENCE scenario's at equal `(workspaceCount, concurrency, round(offeredRate))` — and that reference must be `referenceMode: NO_LOCK` (ADR mode c; a same-lock CEILING_OFF reference blocks), `lockTimeoutRate < 0.001`; `deadlocks`, `deadlineMissesFromSlotQueueing`, `errors.other` and `retentionStarvedTicks` sum to 0 over all scenarios; coverage is recomputed too: concurrency 1, 10, 25, 50 and 100 each present at 2× with ≥ 10 000 workspaces and ≥ 1 000 live reservations; every `criteria.*.pass` true. A CHOSEN 2× scenario with **no matching REFERENCE** run blocks; `INCOMPLETE` blocks naming `incompleteReasons`. Warns when `gitSha` is not an ancestor of the running API's `git_sha`. | `--benchmark-evidence` (`docs/dev/storage-benchmark.md`) |
| **E-floor-staging** | §14 (e), §18 gate 5 | The running API and ingestion artifacts (`git_sha` from `testinbox_build`) each **contain** every ADR-035 floor (`git merge-base --is-ancestor floor sha`). | metrics + `--repo` |
| **E-floor-production** | §14 (e) | Every ADR-035 floor is an ancestor of `origin/master` **and** is listed in `origin/master:deploy/rollback-floors.txt` — because `production-handoff.yml` reads the floors from `master`, a floor that is only on `develop` protects nothing in production. | `--repo` (`--fetch` to refresh) |
| **Q-qualification** | §9a, §18 gate 7a | The Ops-declared backend identity (`--backend-identity`) equals a record listed in `adr035-qualification/index.txt` on every element — image index digest, platform member digest, release, commit id, mode, drive count, timeout environment, timeout CLI flags, runtime-config hash (**non-null** and equal), kernel release, filesystem type, mount options, storage-class / inline defaults, direct path, proxy, upload implementation version — **and** that record is enablement-eligible on its own content (`enablementEligible: true`, `slowWExecuted: true` with a `slow-*` scenario listed, a non-null runtime-config hash, a platform class that is not `laptop`), **and** when Ops supply `--qualification-valid-metric` from their `qualification-check`, it is `1`. Blocked with the mismatched elements or the ineligibility reasons. | `--qualification-dir`, `--backend-identity`, `--qualification-valid-metric` |
| **F-filesystem** | containment contract §9 (TI-STORAGE-006E); ADR-035 Amendment 2 | `--mode TENANT_LIMITS`: the **physical-isolation preflight** (identity, dedicated mount, capacity, inodes, preallocation, isolation, starting block and inode headroom, observation source and liveness, qualification, the record's E8–E11). Its PASS states that it proves isolation only: global footprint admission is observational there, the containment theorem does not hold, and filesystem exhaustion stays reachable. The mode is approved for dark staging qualification only, never for public traffic. `--mode ALL`: each row of the contract's §9 table holds, comparing an **observed** figure with a **declared** one, never a declaration alone. The rows are filesystem identity equal to the record the evidence names; a dedicated mount; `f_blocks × f_frsize ≥ C_fs` with `f_frsize = B`; `f_files ≥ I_fs ≥ C_fs / B`; a preallocated, non-thin backing with an fstrim exclusion; host isolation; `used ≤ Φ + H_F + M` and `avail ≥ R_ops` on the newest observation; `D_est ≤ D_budget`; trusted counts (marked only by V10's verifying function); the base case from database records (a trust mark, then a completed `storage_sweep_run` ordered after it and after any lower-containment node, listing no more than it covered); E1–E11 recorded PASS; metadata inodes within budget; a declared `TI-STORAGE-006E` containment floor; `procs` covering the live ingestion nodes; the observation written by the monitor role, which alone may write one; no application-role privilege on the V8 boundary; `storage_debt_order_seq` `CACHE 1` on the primary; MinIO metadata `≤ M`; Ops `qualification-check` valid; and an observation younger than *A_obs*. A missing, malformed, stale (older than *A_obs* on the database clock) or contradictory input (statvfs disagreeing with the monitor's own figures) is **NOT RUN**. A failing row is **BLOCKED**, and every failing row is named. | `--filesystem-evidence` (`docs/dev/filesystem-evidence.md`), plus the database through `TESTINBOX_ACTIVATION_DB_URL` or `--footprint-state` |

The ADR-035 floors are **parsed** from `deploy/rollback-floors.txt` (the lines
whose rationale names `ADR-035` or `TI-STORAGE`), never hardcoded; a floors
file that declares none blocks both E gates.

## Who proves what

The barrier is deliberately split between what the application can see about
itself and what only Ops can see. Neither half substitutes for the other.

**The application proves, about itself:**

- its **own** sessions carry `testinbox-<service>:<node>:storage-v1`
  (Hikari `ApplicationName`, and the LISTEN session's own name), and its own
  role's `pg_stat_activity` rows are visible to it;
- its **own** `storage_node` row: generation, capability `storage-v1`,
  heartbeat, clean-shutdown flag;
- its **own** metrics on the management port: clock offset, covered bytes,
  physical listing, orphan-sweep completion, latch, breaker, enforcement
  mode, `testinbox_build{git_sha}`, and the per-node
  `testinbox_storage_activation_gate_ready{gate=…}` /
  `testinbox_storage_activation_violation` it re-evaluates on every cleanup
  pass once enforcement is on (§14 phase 4);
- at startup with enforcement ≠ OFF, `DeploymentSafety` refuses to start
  unless the **declared** `backend-identity` equals a shipped qualification
  record (§9a). That compares a declaration with a record. **It cannot see the
  real MinIO.**

**Ops prove, because the application cannot:**

- the **cluster-wide** session picture: sessions of *other* roles and *other*
  binaries (an old artifact still connected, a stray tool), which the
  application's role may not even be allowed to see. Gate A-sessions is run
  by Ops against the real database, with the checker's own session tagged
  `ops:storage-activation-check`;
- the **real** MinIO combination, with `qualification-check` run under MinIO
  **admin credentials the application deliberately never holds**:
  `ServerInfo`, `mc admin config get` for `api`/`drive`/`storage_class`/
  `scanner`, the data filesystem's mount options and `uname -r`, hashed and
  compared with the record, published as
  `testinbox_storage_qualification_valid` (0/1). A `0` pages and latches;
- the **§11 benchmark on the staging host class**, before `ALL`;
- **NTP** on the database and MinIO hosts (§18 prerequisite 9) — gate C
  measures the offset the application sees, Ops make it small;
- the §9 Ops preconditions (versioning off, no object lock, no retrying
  proxy, quota margin) recorded in `production-ops-acceptance.md`.

## Inputs and outputs of the script

```
scripts/check-storage-activation.sh --mode TENANT_LIMITS|ALL
    [--api-metrics <url|file>]... [--ingestion-metrics <url|file>]...
    [--expected-api-nodes a,b] [--expected-ingestion-nodes c,d]
    [--sessions-file <tsv>] [--nodes-file <tsv>]
    [--qualification-dir <dir>] [--backend-identity <json>] [--qualification-valid-metric 0|1]
    [--benchmark-evidence <json>]
    [--repo <dir>] [--floors-file <file>] [--fetch]
    [--filesystem-evidence <json>] [--footprint-state <json>]
    [--max-clock-offset-seconds <n>] [--json <out.json>]
```

- `--api-metrics` / `--ingestion-metrics` take an `http(s)://` URL (fetched
  once with `curl -fsS`; unreachable = blocked, not skipped) or a local file in
  Prometheus text format. Metrics are matched by **name and the listed
  label**, never by exact line, so extra labels (`service`, `environment`) are
  fine.
- The database connection comes **only** from the environment
  (`TESTINBOX_ACTIVATION_DB_URL`), never from a flag that would show in `ps`.
  `--sessions-file` / `--nodes-file` (TSV) replace the two queries; the
  self-test uses them so it needs no database.
- Nothing secret is ever printed. Session names and node ids are not secrets;
  URL userinfo is redacted if present.
- Exit codes: `0` = `ACTIVATION READY` (every gate `PASS` or `NOT REQUIRED`),
  `1` = `ACTIVATION BLOCKED` (any `BLOCKED` or `NOT RUN`), `2` = usage or a
  missing input file. The output is deterministic for identical inputs.

The evidence record (`--json`):

```json
{
  "mode": "ALL",
  "evaluatedAt": "2026-10-07T00:00:00Z",
  "gates": [
    { "gate": "A-sessions",          "verdict": "PASS",         "detail": "3 storage-v1 session(s), 1 excluded (migrator/ops), 0 violations" },
    { "gate": "A-inventory",         "verdict": "PASS",         "detail": "2 declared node(s) all heartbeating storage-v1 within 300 s; no extra node" },
    { "gate": "B-physical-baseline", "verdict": "PASS",         "detail": "1 API node(s): full orphan sweep completed since start; physical_listed ≤ covered + H" },
    { "gate": "C-clock-offset",      "verdict": "PASS",         "detail": "2 node(s): |offset| ≤ 30 s, no latch, no open breaker" },
    { "gate": "D-benchmark",         "verdict": "NOT_REQUIRED", "detail": "NOT REQUIRED FOR TENANT_LIMITS: …" },
    { "gate": "E-floor-staging",     "verdict": "PASS",         "detail": "2 running artifact(s) contain every ADR-035 floor" },
    { "gate": "E-floor-production",  "verdict": "BLOCKED",      "detail": "floor c84ddd74f798 is not an ancestor of origin/master (master predates it); …" },
    { "gate": "Q-qualification",     "verdict": "NOT_RUN",      "detail": "no --backend-identity supplied" },
    { "gate": "F-filesystem",        "verdict": "NOT_RUN",      "detail": "no --filesystem-evidence supplied" }
  ],
  "verdict": "BLOCKED"
}
```

Verdicts in the JSON are `PASS`, `BLOCKED`, `NOT_RUN`, `NOT_REQUIRED`; the
table prints them with spaces. Ops attach this record to the enablement row in
`production-ops-acceptance.md`.

## Expected current state

Running the script against this repository with no runtime inputs gives
`ACTIVATION BLOCKED`, and **that is the correct result**:

- **E-floor-production is BLOCKED** because `master` predates ADR-035: the two
  ADR-035 floors (`c84ddd74…`, `d4e38b23…`) are not ancestors of
  `origin/master`, and `origin/master` has no `deploy/rollback-floors.txt` at
  all. The self-test asserts this against the real checkout — with the
  expectation *computed* from `origin/master`, so the case flips to `PASS`
  honestly once a production promotion carries the floors, instead of
  hardcoding today's answer.
- Every runtime gate is **NOT RUN** (no metrics, no database, no identity, no
  benchmark), and NOT RUN blocks.
- **Q-qualification would block** even with an identity file: the only
  shipped record (`laptop-arm64-reference-2026-09-29.json`) is
  `enablementEligible: false` — laptop platform class, `slow-W` not executed,
  runtime admin configuration never hashed (§18 gate 7a requires the
  deployed host's own combination). The production host must be
  re-qualified on its own combination before any record can be eligible.
- **F-filesystem is NOT RUN** in both modes (no filesystem evidence).
  - **With evidence, `ALL` would stay BLOCKED:**
    - No `TI-STORAGE-006E` containment floor is declared until PR D merges.
    - No shipped qualification record carries filesystem elements, the
      measured *O_max*, the E7 metadata inodes, or E1–E11 results. The staging
      record must be re-issued after the experiments run on the recreated
      filesystem.
    - On staging, the application connects as the schema owner, so the
      privileges row fails **by design**: every V8 and V10 privilege boundary
      is void there.
  - **The `TENANT_LIMITS` preflight would also block** until the re-issued
    record exists. Its PASS would prove isolation only, never containment.
- **D-benchmark is NOT RUN** for `ALL` (the harness exists —
  `docs/dev/storage-benchmark.md` — but no staging-host-class result has been
  recorded; a laptop run is `INCOMPLETE` by construction) and `NOT REQUIRED`
  for `TENANT_LIMITS`.

Companion gates that **do** run in CI today, on every pull request:

- `scripts/check-storage-enforcement-off.sh` — no committed environment
  (Spring YAML, compose, `.env.example`, rehearsal, workflows) assigns
  `TENANT_LIMITS` or `ALL`, including as a `${VAR:-ALL}` default, and both
  property classes declare `enforcement: StorageEnforcement = StorageEnforcement.OFF`.
  Absent is not OFF.
- `scripts/check-minio-mirror-pin.sh` — the single pinned MinIO digest is the
  `minio.imageIndexDigest` of a record listed in
  `adr035-qualification/index.txt`, so a re-mirror cannot land without a
  record (§9a "In CI").

## What a green workflow does and does not mean

**GitHub handoff accepted ≠ deployed-state proof.** The Staging workflow
builds, proves the artifact, and triggers GitLab `infinity/infinity-core`; a
green run means Ops *accepted the request* (the trigger returned 2xx). It
does not observe the host afterwards, and the post-deployment verdict lives in
Ops (`deploy/synthetic/` is the gate on the host, not the workflow). The same
digest serves Phase 2 and Phase 4.

Therefore **a green Staging workflow is not evidence for any gate in this
document.** Not for A (it sees no `pg_stat_activity`), not for B or C (it
reads no metrics from the host), not for D (no benchmark ran), not for E
(it does not know what is running, and production floors live on `master`),
not for Q (it never sees the real MinIO). The only admissible evidence is the
evidence record produced by `check-storage-activation.sh` against the live
environment, together with the Ops-side proofs listed above.

## Activation, operationally

1. Phase 2 is deployed and has run long enough for one full orphan sweep.
2. Ops run `check-storage-activation.sh --mode TENANT_LIMITS` with live
   metrics, the database URL in the environment, the declared node ids, the
   backend identity, and `--qualification-valid-metric` from
   `qualification-check`. `ACTIVATION READY` → set `TENANT_LIMITS` on the
   same digests. Attach the JSON to `production-ops-acceptance.md`.
3. Run the §11 benchmark on the staging host class; commit its record.
4. Ops run `--mode ALL` with `--benchmark-evidence`. `ACTIVATION READY` →
   set `ALL`.
5. From here no old ingress instance may exist (§14 phase 4). Every node keeps
   re-running the allowlist and inventory checks on each cleanup pass and
   raises `testinbox_storage_activation_violation` on any mismatch; the
   alerting of §16 covers it.

Any `BLOCKED` is answered by fixing the named cause and re-running, never by
editing the inputs. A gate that cannot be evaluated stays `NOT RUN`, and the
answer stays `BLOCKED`.

## Self-tests

- `scripts/check-storage-activation.test.sh` — fixtures under
  `scripts/testdata/storage-activation/`; proves each gate fails
  **independently** (every other gate stays `PASS`/`NOT REQUIRED` while one
  is blocked), that `NOT RUN` blocks, that `TENANT_LIMITS` is unaffected by a
  failed or absent benchmark, that the benchmark recomputation catches a
  recorded `PASS` whose numbers violate a criterion, a missing REFERENCE run
  and an `INCOMPLETE` verdict, and — on a throwaway git
  history — that floors are judged by ancestry on `origin/master`. For gate
  F it also proves that each row blocks on its own, with its figures. It
  proves that every missing, malformed, stale or contradictory input is
  `NOT RUN`. It proves that raising a declaration alone never passes a
  capacity row, and that the `TENANT_LIMITS` preflight passes only on
  isolation and says so.

  The database query behind `--footprint-state` was run by hand against
  PostgreSQL 16 with V1–V10 applied, using fixture filesystem evidence:
  - With separated roles and the documented grants, after a verified trust
    mark, a reaped containment-0 node and a later recorded sweep, gate F
    passed in both modes.
  - A forged delta was refused.
  - A sweep that began before the trust mark blocked, and so did an unclean
    lower node.
  - With the owner as the application role, the gate reported 103
    privileges and ownerships.

  None of this qualifies a real filesystem.
- `scripts/check-storage-enforcement-off.test.sh` — a YAML `TENANT_LIMITS`,
  a compose `${VAR:-ALL}`, a Spring `${VAR:ALL}`, a property default changed
  to `ALL`, a missing declaration, and the clean tree.
- `scripts/check-minio-mirror-pin.test.sh` — adds: a consistent re-pin with
  no record, a record for another digest, a record omitted from `index.txt`,
  no qualification directory, a dangling index entry.

All three run in the `static-analysis` job on every pull request. Fixtures
and tests need `bash`, `jq`, `awk`, `sed`, `grep`, `git`; no database, no
network, no Docker.

## Stated assumptions and limits

- **Qualification records are invalidated by change, never by age.** A record
  matches only while every contract element is equal; a re-mirror, a new
  digest or a changed host fact ends the match. `qualifiedAt` is recorded for
  the audit trail and is not evaluated. The daily Ops `qualification-check`
  is what catches a change made by hand (ADR-035 §9a).
- **The admin-credential separation is a privilege separation only where Ops
  provision one.** In the self-hosted reference topology,
  `deploy/staging/compose.data.yaml` starts MinIO with `MINIO_ROOT_USER` /
  `MINIO_ROOT_PASSWORD` equal to the application's S3 credentials, so there the
  application *could* run `mc admin config get`; nothing in this repository
  does, and the application never reads that configuration. On the Ops-owned
  hosts the application identity must be a scoped, non-admin user for the
  `qualification-check` to be independent in fact and not only in convention.
- **Benchmark provenance is asserted, not recomputed.** The checker re-derives
  every §11 criterion from the numbers in the evidence, but trusts `gitSha`
  (an ancestry mismatch only warns), `timestamp` and the operator-supplied
  `hostClass`. A full-matrix run on the wrong class of host with
  `--host-class "staging"` is indistinguishable to the script. The host class
  is Ops's statement, recorded in `production-ops-acceptance.md` row P with the
  run.
- **A staging record is produced the same way as a production one:** qualify
  staging's own MinIO combination with `run-slow.sh` on the staging host,
  commit the JSON under `backend/storage/src/main/resources/adr035-qualification/`,
  list it in `index.txt`, and let `check-minio-mirror-pin.sh` and
  `QualificationRecordsTest` prove it loads. Until then gate Q blocks on
  staging exactly as it does on production.
- **What the gateway proves at start.** A non-OFF gateway runs the allowlist
  and inventory check before it accepts its first `DATA`, then on every
  heartbeat; the API re-checks on every cleanup pass. The 5-minute heartbeat
  staleness is the cleaner's own.
- **An evaluation that cannot complete is a violation under a non-OFF mode.**
  `ActivationWatch.run()` never throws: if the inventory read fails, the
  gateway's guard is set, `testinbox_storage_activation_violation` is 1 and
  mail is deferred with `451` until a later check completes and passes its admission-relevant gates;
  under OFF the failure is logged and observed only (TI-STORAGE-006b P1).
  This is the one authoritative rule; the call sites add nothing.
- **Concurrency 1 in gate D** is the uncontended baseline (ADR-035 §11 as
  amended 2026-10-08): required for coverage and judged on the retention
  ratio and the integrity criteria, but not on sustained load or T1 p99.
