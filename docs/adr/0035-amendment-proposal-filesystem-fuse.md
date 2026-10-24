# ADR-035 amendment proposal: filesystem containment instead of the bucket-quota fuse

**Status: PROPOSED — not accepted.** Prepared by Ops for owner review (TI-STORAGE-006C, 2026-10-08). Nothing here
changes ADR-035 until the owner accepts it; until then ADR-035 §9 / §18 gate 8 stand, and staging stays `OFF`.

Evidence: `docs/adr/0035-benchmark/filesystem-fuse/` (isolated labs; never the live staging MinIO).

## 1. What ADR-035 assumes today, and why it does not hold

§9 makes the MinIO bucket quota *Q* the last-resort physical fuse, sized `Q ≥ G + max(1 GiB, ⌊G/10⌋, H + churn)`, where
*churn* is "the bytes MinIO can accept during one usage-refresh lag", measured by Ops. `DeploymentSafety` enforces it for
**every non-`OFF` mode**, including `TENANT_LIMITS` (`BucketQuotaFuse`).

**Root cause (measured on the pinned release, member `3f97c565…`).** MinIO checks a hard quota against bucket usage taken
from its background **scanner**, not against what is on disk. The usage only advances when a scanner cycle completes.

| Observation | Value |
|---|---|
| Live staging, near-empty and idle: scanner cycle gap | 60.0 s, steady |
| Lab under 16 × 15 MiB writers (~210 MiB/s): cycle gaps | 7–117 s |
| Refusal lag after the quota was crossed (5 runs) | 18.8, 48.5, 70.4, 99.4, 109.3 s |
| Bytes admitted past a 256 MiB quota | up to **24 425 529 344** (22.75 GiB) |
| Required Q for G = 20 GiB on that observation | 46 152 024 064 B (42.98 GiB) > the owner's 40 GiB |

The lag follows scan duration, and scan duration grows with the number of stored objects and the write load. The
samples were taken on a near-empty deployment, so they are not a bound: at *G* = 20 GiB of real mail the lag, and the
churn, are larger and unmeasured. **Raising Q does not fix this** — any finite Q is a guess about a lag that grows with
the data it is meant to contain.

Two further defects of the quota as a *physical* fuse, independent of the lag:

1. **It counts logical bytes, not blocks.** MinIO charges the object size; the filesystem charges blocks + per-object
   metadata (§3 below: a 512 B object costs 12 308 B on disk).
2. **It does not see deleted-but-unpurged data.** A large `DeleteObjects` moves objects to `.minio.sys/tmp/.trash`; the
   bytes stay on disk ~250 s after the delete returned (lab: 1 020 MiB freed only after 251 s).

## 2. The alternative: a dedicated, preallocated filesystem as the containment boundary

ext4 refuses an allocation that does not fit **synchronously**, inside the write that needs it: no scanner, no cache, no
lag. Everything MinIO stores for TestInbox — committed objects, upload temp files, trash, `.minio.sys`, multipart state —
lives inside that filesystem, so the kernel bounds all of it at once.

**Proposed invariant (FC, filesystem containment).** *All bytes MinIO allocates for TestInbox are inside one dedicated,
fully preallocated filesystem of known block and inode capacity. Its exhaustion fails closed (no admission), cannot
affect any other filesystem, and never turns into a tenant refusal.*

FC is a **containment** property — it bounds the damage. The **operating** design must keep normal use far from it
(§4); reaching ENOSPC is an infrastructure incident, not a capacity decision.

### 2.1 Measured ENOSPC behaviour of the pinned MinIO (lab, 2 GiB ext4 `rw,relatime`, same member, 1536 MiB)

| Question | Result |
|---|---|
| Does MinIO keep a free-space reserve? | **No** — it writes until 0 B available (it runs as root, so it also uses ext4's root-reserved blocks) |
| PUT failure at exhaustion (16 × 15 MiB writers) | 13 × `500 InternalError` "cause(no space left on device)", 3 × `507 XMinioStorageFull` |
| Acknowledged objects | 113 of 113 complete and byte-identical |
| Failed objects | 16 of 16 absent at once, and **absent via S3 and on disk 17.8 min later** (no late commit; one run) |
| Small inline PUT / 0-byte PUT at "0 B available" | both **succeed** (root-reserved blocks) |
| LIST / HEAD / GET / DELETE at full | succeed |
| Restart at full | ready in 4 s; 115 objects listed and read, 0 mismatches |
| Recovery | a 15 MiB PUT succeeds immediately after deletes free space |
| `DeleteObjects` of 1 000 objects | physical release deferred ~250 s (trash); small deletes free at once |

### 2.2 What this does and does not prove

| Kind | Property |
|---|---|
| **Kernel-enforced** | MinIO's allocated blocks and inodes never exceed the filesystem's; exhausting it cannot exhaust `/` *if* the backing store is preallocated |
| **Measured, not proven** | ENOSPC failures do not commit later (16/16, one run); acknowledged objects survive ENOSPC and restart; trash purge latency ~250 s |
| **Assumed (to be qualified)** | the per-object overhead constant; purge latency under load; behaviour with millions of objects |
| **Unbounded under today's accounting** | physical footprint vs payload *G* (object count is not limited); trash backlog after mass expiry |

## 3. Why the filesystem cannot simply replace Q under today's accounting

### 3.1 Amplification and object count (lab)

| Payload | Physical per object | × | Inodes |
|---|---|---|---|
| 512 B | 12 308 B | 24.0 | 3 |
| 4 KiB | 16 404 B | 4.0 | 3 |
| 16 KiB | 28 692 B | 1.75 | 3 |
| 128 KiB | 143 401 B | 1.09 | 3 |
| 1 MiB | 1 069 193 B | 1.02 | 5 |
| 15 MiB | 15 749 530 B | 1.001 | 5 |

Physical ≈ `ceil(payload, 4 KiB) + O`, O ≈ 12–21 KiB. §2 charges *payload* ("what one copy costs"), and nothing bounds
the object count: `INGEST` allows 10 deliveries/s per workspace (burst 300) and inboxes live up to 24 h — ~864 000
messages per workspace per day, plus one object per extracted attachment. 20 GiB of payload in tiny objects would need
several hundred GiB of blocks and millions of inodes. **Under payload accounting no filesystem size is sufficient.**
(The same gap exists today: staging's 47 GiB filesystem would hit ENOSPC long before Q = 40 GiB with small messages.)

### 3.2 Deferred deletion

Retention deletes the blob prefix (`ListObjectsV2` + `DeleteObjects`) and then the rows, so the ledger releases the bytes
when MinIO answers — but large deletes reach the disk ~250 s later. After a mass expiry the filesystem can briefly hold
the deleted data **and** the newly admitted data: up to ≈ 2 × covered.

### 3.3 Inodes, thin provisioning, root reserve

- The staging filesystem has 3 145 728 inodes (one per 16 KiB). 20 GiB of 12 KiB objects at 3 inodes each needs ≈ 5.2 M,
  ≈ 10.5 M with a full trash. **The inode ratio must be set for the worst case (`mkfs.ext4 -i 4096`)**, which requires
  recreating the filesystem (migration, not resize).
- The staging image is a **sparse** file on `/` (263 MiB allocated of 48 GiB after the trim). Until it is preallocated,
  the containment boundary depends on `/` having free space — exhaustion would be *the host's*, not contained.
  Ubuntu's weekly `fstrim.timer` reads `/etc/fstab` first and our mount is a systemd unit, so it is not trimmed today —
  an invariant to keep, not an accident to rely on.
- MinIO runs as root, so ext4's 5 % root reserve is **not** protected from it. A recovery reserve must be enforced by
  policy (§5), not by ext4.

## 4. Proposed capacity model (replaces the Q formula)

Let φ(s) = `ceil(s, 4096) + O_max` be the physical footprint of one object (O_max qualified per combination; lab
≤ 21 KiB, proposed 24 KiB until qualified).

| Symbol | Meaning | Staging value (G = 20 GiB) |
|---|---|---|
| G_F | global ceiling in **footprint** bytes (Σ φ over covered objects) | 21 474 836 480 |
| H_F | procs × 16 × φ(15 MiB) | 1 × 16 × 15 753 216 ≈ 240.4 MiB |
| D_del | footprint deleted but not yet purged (budget) | ≤ G_F unless retention is paced (§6) |
| M | MinIO metadata, caches, multipart residue | budget 256 MiB (measured ~48 KB) |
| R_ops | operational headroom never planned for use | ≥ 5 % of C_fs |
| C_fs | blocks usable by MinIO (root) after ext4 metadata | 48 GiB raw: 46.94 GiB at `-i 16384`; ≈ 44 GiB at `-i 4096` |
| I_fs | inodes | must be ≥ 3 × (G_F + D_del) / φ_min (objects ≤ 128 KiB use 3 inodes; larger ones use 5 but cost ≥ 128 KiB) + prefix directories; 20 + 20 GiB at φ_min = 12 KiB → ≈ 10.5 M; `-i 4096` on 48 GiB gives ≈ 12.6 M |

**Containment condition (DeploymentSafety + barrier):** `C_fs ≥ G_F + H_F + D_del + M + R_ops` and `I_fs` as above.
At G = 20 GiB, D_del = G_F (unpaced), R_ops = 2.2 GiB: 20 + 0.23 + 20 + 0.25 + 2.2 = **42.7 GiB ≤ ~44 GiB** — a 48 GiB
filesystem recreated at `-i 4096` *just* fits, with no slack worth the name. With retention paced to D_del ≤ 4 GiB the
requirement drops to ≈ 26.7 GiB. **48 GiB is sufficient only together with footprint accounting, the inode ratio,
preallocation and a bounded D_del; with payload accounting it is not sufficient at any size.**

## 5. Failure handling

| Condition | Detection | Effect | Clears |
|---|---|---|---|
| Headroom < `H_F + D_del,obs + M + R_ops` (preventive) | Ops monitor on block **and** inode free space, every minute | Ops sets `storage_admission_latch` (reason `filesystem-headroom`): every node `451` before T1 | operator, after the runbook |
| ENOSPC on an upload (`507 XMinioStorageFull`, or `500` with "no space left on device") | the uploader | today: `Ambiguous(SERVER_ERROR)` → ambiguity row (slot held, H bounded) + breaker open → `451` | ambiguity verification; breaker trial |
| Repeated ENOSPC trips | breaker counter | latch | operator |
| Unexpected growth (physical ≫ covered + H + D_del) | Ops monitor vs `testinbox_storage_covered_bytes` | alert; latch above threshold | operator |
| Purge backlog (`.minio.sys/tmp/.trash` > budget) | Ops monitor | alert; latch if headroom rule trips | automatic purge, then operator |
| Mount missing | compose `create_host_path: false` | MinIO does not start; readiness fails | restore mount |

Every row is **infrastructure**: `451` for the whole `DATA`, the edge queues, the tenant sees nothing (§8 table). No
filesystem condition may produce `INBOX_LIMIT`/`WORKSPACE_LIMIT`/`SERVICE_CAPACITY` — the SMTP anti-oracle holds because
the outcome is identical for every recipient.

**Defect to fix with this change:** the §8 half-open trial for `SERVER_ERROR` is a zero-byte probe, and a zero-byte PUT
**succeeds on a full filesystem** (lab). A full disk would close the breaker on the probe and re-trip on every real
upload. ENOSPC needs its own kind whose trial is one real event, like `QUOTA`.

**Recovery runbook.** 1) latch stays set (no admission); 2) `mc admin info` + filesystem `df -B1`/`df -i`; 3) identify
trash, temp and orphan bytes (`.minio.sys/tmp`); 4) let the purge run (do not delete MinIO internals by hand); deletes
work at full (lab) but if they do not, free space by growing the preallocated image online (`truncate`, `losetup -c`,
`resize2fs`); 5) full orphan sweep + reconciliation must complete; 6) `qualification-check` = 1; 7) headroom above the
preventive threshold with hysteresis; 8) clear the latch; 9) watch the first admissions.

## 6. Required changes (if accepted)

**App team (ADR-035 §2/§3/§8/§9/§14/§18 amendments, tests):**
1. A **global footprint ledger**: each object also carries φ(size); the *global* ceiling and the containment check use
   footprint. Inbox and workspace limits stay in payload bytes (tenant-visible semantics unchanged).
2. `DeploymentSafety`: replace `BucketQuotaFuse` with `FilesystemContainment` (declared C_fs, I_fs, O_max, D_del budget,
   R_ops) for every non-`OFF` mode; Q becomes optional and is never load-bearing.
3. Breaker: `STORAGE_FULL` kind (507 / ENOSPC 500) with a real-event trial.
4. Optional but recommended: retention pacing, so D_del ≤ a declared budget per purge interval.
5. Activation checker: gate `F-filesystem` (C_fs, I_fs, preallocation, headroom, mount identity) from Ops evidence.
6. Acceptance tests for each of the above.

**Ops:**
1. Recreate the staging MinIO filesystem with `-i 4096`, **fully preallocated** (`fallocate`, allocated == size), as a
   systemd mount outside `/etc/fstab`; migrate MinIO data with the existing fail-closed bind.
2. Preventive monitor + latch writer (block and inode headroom, trash backlog, physical-vs-covered).
3. `qualification-check` extended: filesystem size, inode count, preallocation, mount source, trim exclusion.
4. Alerts and the runbook above; a rehearsal on an isolated lab.
5. **Production:** the production layout shares the estate MinIO; filesystem containment needs a **dedicated** MinIO on
   a dedicated filesystem for TestInbox. That is a production infrastructure change to plan before production enforcement.

## 7. Qualification plan (isolated lab; never the live MinIO)

| # | Scenario | PASS | BLOCK |
|---|---|---|---|
| 1 | amplification at 512 B…15 MiB, 3 sizes of attachments | O_max measured, ≤ declared | any object above φ |
| 2 | fill to ENOSPC, 16 writers, 5 runs | every 2xx byte-identical; every failure 5xx | a 2xx with missing/short content |
| 3 | ENOSPC late commit, sweep ≥ C_max + 2 min, S3 **and** on disk, ≥ 5 runs | 0 failed keys appear | any appearance |
| 4 | ENOSPC during freeze/slow (dm-delay) | as 2–3 | as 2–3 |
| 5 | restart at full, ×3 | ready; all objects intact | data loss / no start |
| 6 | deletes and metadata at full | succeed | delete fails at full |
| 7 | mass `DeleteObjects` (10 000 objects, 5 GiB) | purge ≤ declared L_purge; D_del within budget | backlog beyond budget |
| 8 | inode exhaustion with tiny objects | inodes never run out before blocks at the declared ratio | inode ENOSPC first |
| 9 | preventive monitor: headroom crosses threshold under load | latch set before ENOSPC; `451`; tenant sees nothing | ENOSPC reached first |
| 10 | breaker with `STORAGE_FULL` | trial is a real event; no flapping | zero-byte trial closes it |
| 11 | recovery runbook end to end | admissions resume; reconciliation + sweep clean | drift, orphan, latch stuck |
| 12 | mount disappears / image detached | MinIO refuses to start; readiness down | MinIO starts on `/` |
| 13 | qualification invalidation (fs/inode/prealloc change) | `qualification_valid = 0`, latch | stays 1 |
| 14 | SMTP at full / latched | identical `451` for every recipient | any difference |

## 8. Alternatives considered

- **Raise Q again.** Rejected: the lag grows with the data; no finite Q is a bound.
- **Lower G or the declared concurrency.** Not authorized; and neither bounds the lag.
- **Rate-limit ingestion→MinIO.** Bounds churn only if the lag is bounded — it is not.
- **A MinIO release with synchronous quota.** Unavailable in the pinned line; would invalidate the A_F qualification.
- **ext4 project quota on the bucket directory.** Same synchronous enforcement, but adds a second accounting system on
  the same filesystem for no gain over a dedicated filesystem.

## 9. Recommendation

**REQUIRES FURTHER EVIDENCE** (and application changes) before acceptance. The filesystem is the right *mechanism* —
deterministic, lag-free, measured to fail cleanly — but it can only be a sound fuse together with footprint accounting,
a bounded deletion backlog, a worst-case inode ratio, full preallocation and the `STORAGE_FULL` breaker kind. Until those
exist, staging remains `OFF`.
