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
`/proc/mounts`, the monitor's rows, the catalog) with a declaration.

That does **not** make every declaration harmless. Raising *C_fs* or *I_fs*
cannot pass, because statvfs must still cover them. Raising *M* or *D_budget*, or lowering *R_ops*,
loosens the rows that use them, so those values
must equal the deployed configuration. That is why the evidence names each
one's source below.

Two quantities are **never** read from the evidence. ε is
`1 / min(256, ⌊(B − 12)/12⌋ − 1)`, and H_F is `procs × 16 × F(15 MiB, 1)`.
The gate derives both exactly as `FootprintModel` does, with the same
rounding. *O_max* must be a value the application could load: a multiple of
*B* covering at least 6 blocks. It must also be no less than the qualified
maximum the record carries (`filesystem.objectOverheadMaxBytes`).

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
  with a file of the same shape (the self-test fixtures show one). The live
  database always wins. A state file is refused while
  `TESTINBOX_ACTIVATION_DB_URL` is set, and a PASS on a file says that the
  state was offline.
- `--mode TENANT_LIMITS` runs the **physical-isolation preflight** (ADR-035
  Amendment 2, §A2.5). It applies the rows that prove the storage is
  isolated and starts with headroom:
  - identity and dedicated mount;
  - capacity and inodes;
  - preallocation and isolation;
  - starting headroom (`avail ≥ R_ops`, and at least one free inode per free
    block);
  - observation source and liveness;
  - the Ops qualification-check;
  - the record's E8–E11 drills recorded as PASS.

  It skips every row about the global potential, because nothing enforces
  it in that mode. Its PASS says so: global admission is observational, the
  containment theorem does not hold, filesystem exhaustion stays reachable,
  and the mode is approved for dark staging qualification only, never public
  traffic. Without evidence, it is NOT RUN, which blocks.

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
| `minio.metadataInodes` | inodes under `.minio.sys` plus the bucket's directories (`find <data dir>/.minio.sys <data dir>/<bucket> -type d \| wc -l` plus `find <data dir>/.minio.sys -type f \| wc -l`) | MinIO metadata: ≤ `declared.metadataBudgetInodes` |
| `database.allConnectionsToPrimary` | the datasource URLs of every deployable and of the monitor name the primary | ordering |
| `declared.*` | the deployed values, each copied from its real source. The `TESTINBOX_STORAGE_FS_*` variables (`deploy/staging/.env.example`) give `capacityBytes` (*C_fs*), `blockSizeBytes` (*B*), `inodes` (*I_fs*), `globalFootprintLimitBytes` (*G_F*), `metadataBudgetBytes` (*M*), `operationalReserveBytes` (*R_ops*), `objectOverheadBytes` (*O_max*), `deletionDebtBudgetBytes` (*D_budget*), `metadataBudgetInodes` (the inode half of *M*; at least the record's E7 measurement), `observationMaxAgeSeconds` (*A_obs*, at most 3600) and `monitorRole`. `procs` is the declared maximum ingestion processes (`TESTINBOX_STORAGE_DECLARED_MAX_INGESTION_PROCESSES`). `applicationRoles` is the database user of every deployable's datasource; the gate also adds every role it sees connected. ε and H_F are not inputs. | every row |

## What the gate checks against the database

| Row | From the database |
|---|---|
| physical headroom | Φ = F(L + D) + W, computed from T1's own figures and the newest observation's `trash_bytes`; `used_bytes ≤ Φ + H_F + M` and `avail_bytes ≥ R_ops` |
| deletion debt | `D_est = Φ − F(L) ≤ D_budget` |
| trusted counts | `storage_footprint_trust.trusted_epoch = distrust_epoch`, marked by `storage_confirm_footprint_trust()` (V10): no application role can write the trusted columns |
| base case | all from records the database wrote (V10), never an asserted time. The trust row carries a `trusted_seq` from the verifying function. The newest **completed** `storage_sweep_run` began at a database-issued order after it, and after the last heartbeat of any `storage_node` below containment level 1. At its completion it listed no more than the covered bytes (`physical_listed ≤ covered`, as §9 states, not gate B's `+ H`). The newest observation also began after the last distrust event. |
| mixed versions | no live node below containment level 1, and a declared `TI-STORAGE-006E` rollback floor (gate E proves every running artifact contains it) |
| inode headroom | the newest observation has at least one free inode per free block (`inodes_total − inodes_used ≥ ⌈avail / B⌉`) |
| experiments | the record names E1–E11 PASS for `ALL`, and E8–E11 for the `TENANT_LIMITS` preflight |
| procs | the live `storage_node` ids (or `--expected-ingestion-nodes`) number no more than the declared `procs` |
| observation validity | the newest observation began at or above the compaction watermark, and no footprint total overflows 64 bits. Otherwise T1 would refuse while the gate passed. |
| observation source | the newest row's `written_by` is the monitor role, which is not an application role. The monitor is also the **only** login role able to insert an observation or begin a walk, other than superusers and members of the table owner. A role counts as able if it, or any role it can `SET ROLE` to (`NOINHERIT` membership included), holds `INSERT` (column grants included) on `storage_filesystem_observation` or `EXECUTE` on `storage_begin_observation()`. |
| privileges | the roles checked are the declared application roles **and** every role a `testinbox-*` session (other than the migrator) is connected as right now, so leaving a role out of the evidence hides nothing. None of them, directly or through any role it can act as, is a superuser. None holds `INSERT` on observations, `EXECUTE` on `storage_begin_observation()`, `INSERT`/`UPDATE` (column grants included) or `DELETE` on `storage_deletion_debt` or `storage_debt_watermark`, or `SET` on `session_replication_role`. None owns, directly or through membership, a `storage_*` table, sequence or function, or any relation carrying a ledger or debt trigger (`message`, `attachment`, and others), because an owner can disable those triggers. |
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
3. E1–E11 run on it. The qualification record is re-issued with a
   `filesystem` object. That object carries `uuid`, `type`, `mountOptions`,
   `mountSource`, the measured `objectOverheadMaxBytes` and
   `metadataInodesMeasured` (E7), and `experiments` (`{"E1": "PASS", …}`). A
   record without one is never reused silently.
4. Roles are separated as `docs/dev/production.md` lists. The monitor role
   is deployed and writing.
5. Gates A–E, Q and F all pass on one run, and Ops attach the `--json`
   record to the enablement row in `production-ops-acceptance.md`.

## Limits stated plainly

- **The lower-capability ordering relies on node rows surviving.** It reads
  the last heartbeat of every `storage_node` row below containment level 1.
  If such rows were pruned, an earlier lower node cannot be seen. The
  containment floor (gate E) still excludes any lower node that is running.
- **Metadata inodes are counted by Ops.** The monitor's observation carries
  no metadata-inode figure, so the count comes from the evidence. It is
  bounded by a budget that must cover the E7 measurement in the committed
  record.
- **The state query copies T1's definitions.** It does not share code with `FootprintSql.kt`. The two match today; a change to one must be made to the other.

- **Trust is only as strong as the role separation.** Since V10 the trust
  mark is made only by the verifying function, and the guard trigger refuses
  any other change to the trusted columns. A session connected **as the
  table owner** can still forge both the marker and the owner identity.
  Gate F's privileges row refuses `ALL` for any such deployment (staging
  included).
- **The observation age limit (`observationMaxAgeSeconds`, *A_obs*) comes
  from the evidence.** It is capped at 3600 s, so a large value cannot
  launder stale evidence. It is not cross-checked against the running
  configuration.
- **The declared values are copied by Ops from the deployment.** The gate
  checks them against observation, but not against the running
  configuration itself. `DeploymentSafety` checks the same values for shape
  and for I-C at startup.
- **The φ arithmetic runs in jq doubles.** It is exact below 2⁵³ bytes
  (8 PiB), far above any declared capacity.
- The privilege rows exclude superusers and the tables' owner (the migration
  role). Those roles can do anything by construction. Ops keep them out of
  every deployable's datasource.
