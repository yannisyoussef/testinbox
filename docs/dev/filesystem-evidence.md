# Filesystem evidence for activation gate F — the Ops handoff

TI-STORAGE-006E PR E. Gate F of `scripts/check-storage-activation.sh`
(filesystem-containment contract §9) refuses `ALL` unless the **real**
filesystem under MinIO is the one the deployment declares and the contract's
proof assumes. This document is the handoff: what the application
guarantees, what Ops must observe and hand to the gate, and how to collect it.

Green CI proves the accounting against the pinned MinIO in Testcontainers.
**It does not qualify a filesystem.** Only this evidence, gathered on the
deployed host, and experiments E1–E11 of the contract can do that.

## What each side proves

**The application, once PR D is deployed with a declared filesystem:**

- It enforces rules (G), (C) and (P) under `ALL` on its own ledger: *L*, *D*,
  the watermark and the trust row, read in T1's one snapshot.
- It refuses to admit, with `451` before recipient resolution, whenever the
  newest observation is missing, untrusted, below the watermark, from before
  the last distrust event, from the wrong role, or for another block size or
  a smaller capacity.
- It never writes an observation, and it holds no privilege that could.

**Ops, because the application cannot see the host:**

- the filesystem's identity, its mounts, `statvfs`, and the backing image;
- the monitor that writes `storage_filesystem_observation` as its own role
  (`docs/dev/production.md`, V8 paragraph);
- the role separation that makes the V8 boundaries real;
- the qualification record that carries this filesystem's elements, re-issued
  after E1–E8 ran on it.

A **declared** value is what the deployment configures: the
`TESTINBOX_STORAGE_FS_*` variables, which are the
`testinbox.storage.filesystem.*` properties. The gate never accepts one as an
observation. Every rule compares something observed (`statvfs`,
`/proc/mounts`, the monitor's rows, the catalog) with a declaration. Raising a
declaration therefore never makes a row pass.

## Running the gate

```
TESTINBOX_ACTIVATION_DB_URL='postgresql://ops_reader@db/testinbox' \
scripts/check-storage-activation.sh --mode ALL \
    --filesystem-evidence filesystem-evidence.json \
    --expected-ingestion-nodes ing-1,ing-2 \
    … the inputs of gates A–E and Q … \
    --json activation-evidence.json
```

- The database state comes from one read-only statement over the live tables
  and the catalog: T1's own figures, the newest observation, the trust row,
  the live `storage_node` rows, privilege holders and the sequence cache. Run
  it as a role that can read the V8 tables and the catalog. It writes
  nothing. For an offline evaluation, `--footprint-state <json>` replaces it
  with a file of the same shape (the self-test fixtures show one).
- `--mode TENANT_LIMITS` reports gate F `NOT REQUIRED` and says why. Global
  admission is observational in that mode, so filesystem exhaustion stays
  reachable. `TENANT_LIMITS` is approved for dark staging qualification only,
  and never for public traffic.

## The evidence file (`testinbox.filesystem-evidence/1`)

Collect it on the MinIO host within *A_obs* of running the gate. An older
file is `NOT RUN`. Every field is required. A missing or mistyped field is
`NOT RUN`, never a default.

| Field | Collect with | Rule it feeds |
|---|---|---|
| `schema` | the literal `testinbox.filesystem-evidence/1` | shape |
| `collectedAt` | `date -u +%Y-%m-%dT%H:%M:%SZ` | freshness: within *A_obs* of the database clock, not after it |
| `qualificationRecordId` | the `recordId` of the qualification record for this host | identity; must equal what gate Q matched |
| `qualificationValid` | `testinbox_storage_qualification_valid` from `qualification-check`, extended with the filesystem elements | qualification identity |
| `filesystem.uuid`, `.type` | `blkid -o export <source>` (`UUID`, `TYPE`) | identity, against the record's `filesystem` |
| `filesystem.mountOptions` | `findmnt -no OPTIONS <mountpoint>` | identity |
| `filesystem.mountSource` | `findmnt -no SOURCE <mountpoint>`, and the image path when it is a loop device (`losetup -no BACK-FILE`) | identity |
| `mount.mountsOfSource` | `findmnt -rn -S <source> \| wc -l` | dedicated mount: exactly 1 |
| `mount.minioDataDirOnMount` | `findmnt -no TARGET -T <minio data dir>` equals the mountpoint | dedicated mount |
| `mount.otherDataOnMount` | anything at the mountpoint besides MinIO's data directory and `lost+found` | dedicated mount: must be `false` |
| `statvfs.frsize`, `.blocks`, `.files` | `stat -f -c '%S %b %c' <mountpoint>` (fragment size, total blocks, total inodes) | capacity and inodes; also checked against the monitor's newest row (a disagreement is contradictory input) |
| `preallocation.blockDevice` | `true` for a dedicated block device | preallocation |
| `preallocation.allocatedBytes`, `.apparentBytes` | for an image: `stat -c '%b*%B' <image>` and `stat -c %s <image>` | preallocation: equal (not sparse) |
| `preallocation.thinPool` | `lsblk -no TYPE` shows no `thin`/`lvm` thin pool under the device | preallocation: `false` |
| `preallocation.fstrimExcluded` | the `fstrim.timer` and `discard` exclusion for this device, as recorded in the host runbook | preallocation: `true` |
| `isolation.dedicatedDevice` | `true` when the filesystem has its own device | isolation |
| `isolation.hostFreeAtCreationBytes`, `.imageBytes` | recorded when the image was created (`df -B1 --output=avail` of its host filesystem, and its size) | isolation: free ≥ image |
| `minio.bucketDirectoryBytes` | `du -sB1 --apparent-size=false <data dir>/<bucket>` minus the objects' own blocks, as the monitor computes it | MinIO metadata: with `minio_sys_bytes` ≤ *M* |
| `baseCase.trustConfirmedAt` | the last time `testinbox_storage_footprint_counts_trusted` turned from 0 to 1 (Prometheus history) | base case |
| `baseCase.sweepStartedAt` | a lower bound on the start of the sweep that gate B relies on: the **previous** `testinbox_storage_orphan_sweep_completed_at_seconds` value on that node. Sweeps on one node do not overlap, so the current one began after its predecessor completed. In practice this needs two completed sweeps after trust was confirmed. | base case: the sweep began after trust was confirmed |
| `database.allConnectionsToPrimary` | the datasource URLs of every deployable and of the monitor name the primary | ordering |
| `declared.*` | the deployed `TESTINBOX_STORAGE_FS_*` values (`deploy/staging/.env.example` lists them): `capacityBytes` (*C_fs*), `blockSizeBytes` (*B*), `inodes` (*I_fs*), `globalFootprintLimitBytes` (*G_F*), `finalizeBudgetBytes` (*H_F*), `metadataBudgetBytes` (*M*), `operationalReserveBytes` (*R_ops*), `objectOverheadBytes` (*O_max*), `fragmentationEpsilon` (ε), `deletionDebtBudgetBytes` (*D_budget*), `observationMaxAgeSeconds` (*A_obs*), `monitorRole`, `applicationRoles` (every role a deployable connects as) and `procs` | every row |

## What the gate checks against the database

| Row | From the database |
|---|---|
| physical headroom | Φ = F(L + D) + W, computed from T1's own figures and the newest observation's `trash_bytes`; `used_bytes ≤ Φ + H_F + M` and `avail_bytes ≥ R_ops` |
| deletion debt | `D_est = Φ − F(L) ≤ D_budget` |
| trusted counts | `storage_footprint_trust.trusted_epoch = distrust_epoch` |
| base case | the newest observation's `started_seq` is after `distrusted_seq` (V9) |
| procs | the live `storage_node` ids (or `--expected-ingestion-nodes`) number no more than the declared `procs` |
| observation source | the newest row's `written_by` is the monitor role. The monitor is also the **only** login role, other than superusers and the table owner, holding `INSERT` on `storage_filesystem_observation` and `EXECUTE` on `storage_begin_observation()`. It is not an application role. |
| privileges | no application role holds `INSERT` on observations, `EXECUTE` on `storage_begin_observation()`, or `INSERT`/`UPDATE`/`DELETE` on `storage_deletion_debt` or `storage_debt_watermark`. None owns (directly or through membership) a `storage_*` table, sequence or function. |
| ordering | `pg_sequences.cache_size = 1` for `storage_debt_order_seq`, and the gate's own connection is not in recovery |
| liveness | the newest observation is no older than *A_obs*, and not in the future of the database clock |

**Staging fails the privileges row by design.** The application connects as
the schema owner there, so every V8 privilege boundary is void. That is why
staging can qualify `TENANT_LIMITS` in the dark, but never `ALL`.

## Before `ALL` can pass

1. PR D merges, and its `TI-STORAGE-006E` containment rollback floor is added
   to `deploy/rollback-floors.txt` (the mixed-versions row). Gate E then
   proves that every running artifact contains it.
2. The filesystem is recreated as the contract requires: preallocated,
   `-i 4096`, dedicated, isolated.
3. E1–E8 run on it. The qualification record is re-issued with a
   `filesystem` object (`uuid`, `type`, `mountOptions`, `mountSource`). A
   record without one is never reused silently.
4. Roles are separated as `docs/dev/production.md` lists. The monitor role
   is deployed and writing.
5. Gates A–E, Q and F all pass on one run, and Ops attach the `--json`
   record to the enablement row in `production-ops-acceptance.md`.

## Limits stated plainly

- **`baseCase` is attested by Ops, not proven by the database.** The trust
  row records the order of the last *distrust* event, but not the time trust
  was last confirmed. "The sweep began after the counts were last trusted"
  therefore rests on Prometheus history: the gauge transition, and the
  previous sweep's completion as a lower bound on the current sweep's start. Gate B independently proves that a
  full sweep completed since each API node started, and that
  `physical_listed ≤ covered + H`. A `trusted_at` column (an expand-only
  V10) would make this row machine-checked. That is proposed, not done.
- **The declared values are copied by Ops from the deployment.** The gate
  checks them against observation, but not against the running
  configuration itself. `DeploymentSafety` checks the same values for shape
  and for I-C at startup.
- **The φ arithmetic runs in jq doubles.** It is exact below 2⁵³ bytes
  (8 PiB), far above any declared capacity.
- The privilege rows exclude superusers and the tables' owner (the migration
  role). Those roles can do anything by construction. Ops keep them out of
  every deployable's datasource.
