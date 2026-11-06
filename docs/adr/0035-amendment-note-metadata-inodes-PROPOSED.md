# ADR-035 amendment note: one definition of MinIO metadata, in inodes and bytes

**Status: ACCEPTED 2026-11-05**, conditionally, as reviewed. That includes `minio-sys-excl-trash` only with the E10
inode-headroom drill PASS bound by `fsMonitorSha256`.

**Authority.** The owner's decisions, relayed by Ops on #124:

- comment 6665820491: the conditional acceptance of inodes and bytes, quoted below;
- comment 6671007334: acceptance of this note as reviewed at `0eeda4a`, and production *M* = 1 GiB.

**Reviews.** The independent review of #133 (all 9 findings accepted, fixed at `a93a5c8`) and its delta review
(ACCEPT WITH CONDITIONS, fixed at `0eeda4a`).

The dated note in [Text to append](#11-text-to-append-to-adr-035-on-acceptance) is appended to
`docs/adr/0035-physical-storage-bound-at-ingest.md`, after the 2026-10-30 note, with that date. The file name keeps
its historical `-PROPOSED` suffix, because the reference implementation's pinned bytes (`cc30f658…`, which Ops
vendors) cite this path.

**Owner decision (2026-11-05), verbatim:** "Inodes and bytes accepted conditionally. The reviewed implementation must
remove double counting while retaining conservative coverage of all allocations, including shared/residual
directories and pending cleanup. Application, monitor and qualification definitions must agree. Do not disable
existing safety controls."

This note is that reviewed implementation:

- §2 is the inode side, unchanged since Ops implemented it.
- §3–§8 are the bytes side.
- §9 lists what Ops changes.
- §10 is the §9a venue rule (R3).
- §11 is the text to append.

One reference implementation, [`scripts/adr035-metadata-accounting.py`](../../scripts/adr035-metadata-accounting.py)
(proved by `scripts/adr035-metadata-accounting.test.sh`), is the definition all three sides run:

- gate F's evidence;
- the Ops fs-monitor and collector;
- the N1 qualification tooling.

## 1. The conflict (W3 finding, verified)

| Where | What it counts as MinIO metadata |
|---|---|
| Annex §3.1, §3.5 (normative) | Each object's `{message}/`, `{key}/`, `{uuid}/` directories are **per-object**: their blocks are in *O_max*, their inodes in `i_max = 6`. *M* is left "for MinIO's own (`.minio.sys`, a few hundred)". |
| Annex §2.2 class (e) (normative) | *M* holds "directory blocks **not attributed to objects**", including a partially drained inbox's directory. |
| Annex §6 monitor row, §9 gate F row (normative) | "`minio_sys_bytes` + bucket directory blocks `≤ M`", read literally **every** bucket directory, and their inodes. |
| Annex §10 E2 | "the directory blocks left after the partial drain … fit *M*". E7: `minio_sys_bytes + probe residue ≤ M`, "inodes ≤ budget" on the E2 population. |
| `docs/dev/filesystem-evidence.md`, Ops `fs-monitor.py` `dir_blocks_and_inodes`, `collect-filesystem-evidence.py` | Every directory under the bucket, in bytes and (before §2) in inodes. The monitor latches at `2·M` and at twice the inode budget. |

**Consequence.**

- **Inodes:** E2 alone has ≥ 200 000 per-object directories. Gate F's inode row blocks, and the monitor latches at
  200 000, which stops mail at about 70 000 objects.
- **Bytes:** 2–3 per-object directory blocks × 4 KiB exceed *M* = 256 MiB at about 20 000–35 000 objects. Gate F's
  bytes row then blocks, and the monitor latches at `2·M`.
- **What both rows compare.** In both cases the lab figures were counted another way, so the rows compare unlike
  quantities.

## 2. Inodes

MinIO metadata inodes are **every inode under `<data>/.minio.sys`**: the directory itself, `.trash` included
(`find <data>/.minio.sys -xdev | wc -l`). The scope is `minio-sys`, as Ops implemented it. A second scope,
`minio-sys-excl-trash`, leaves out the contents of `.trash`. It is accepted only with a **proven** inode-headroom
latch: an E10 drill of that latch on the fs-monitor build the evidence names (§8.2).

Bucket directories are per-object inodes charged by `i_max` (§3.5), and are never also metadata. They stay bounded by:

- §3.5's `inodes used ≤ i_max · N ≤ Σφ / B`;
- gate F's starting-headroom row, `f_files − inodes used ≥ ⌈avail / B⌉`;
- the A1 census (vi), which found zero blockless inodes on the E2/E7 population.

The budget (100 000) and every criterion are unchanged. Gate F refuses evidence or a record whose scope is neither
`minio-sys` nor `minio-sys-excl-trash`: NOT RUN when the field is absent, BLOCKED otherwise. It also blocks
`minio-sys-excl-trash` without that proof, and evidence whose scope differs from the record's. The generator refuses
the same cases, for E7 as well.

## 3. Bytes: what is double-counted, and what must stay counted

*O_max* (annex §3.1–§3.3) already charges each object for:

- its `{key}/` and `{uuid}/` directories, its `xl.meta`, and its `part.1`;
- the `{message}/` directory of the message's first object;
- `attachments/` for the first attachment;
- one slack block for directory growth.

E1–E3 verify these per object, and E2 verifies the totals (`total delta ≤ Σφ`), including 100 000 messages in one
inbox and 10 000 inboxes in one workspace.

Counting those blocks again in *M* is the double count.

What *O_max* does **not** cover, and *M* must:

- `.minio.sys` other than trash;
- the bucket root;
- a shared directory's blocks once the objects that grew it are gone. ext4 never shrinks a directory, so a drained
  `{inbox}/` keeps about 1 200 blocks per 100 000 messages;
- directories that hold no object at all;
- anything else on the mount.

The monitor cannot see the database's rows. It cannot tell a shared directory's *live* growth (charged to *O_max*'s slack)
from *post-drain* residue (charged nowhere else). By the owner's rule, it therefore counts all of that growth in *M*.
§7 sizes the overlap this leaves.

## 4. The bytes definition (scope `minio-sys+unattributed`)

```
metadataBytes   = minioSysBytes + unattributedBytes                         (scope minio-sys+unattributed)
minioSysBytes   = allocated bytes of every inode under <data>/.minio.sys, the directory included, except the
                  CONTENTS of <data>/.minio.sys/tmp/.trash (W); the .trash directory's own blocks stay in M
unattributed    = every other allocation on the mount that no object is charged for:
  bucket root, in full;  a stray file under the bucket (not part of an object), in full;
  a directory under the bucket that IS, is INSIDE or CONTAINS an object directory: every block beyond its first;
  a directory under the bucket that holds no object: every block;  every inode outside .minio.sys and the bucket
  (mount root, lost+found, the data directory itself), in full;  every subdirectory of an object directory that its
  xl.meta does not reference (an old <dataDir>/part.N left by a crashed or retried overwrite), its whole subtree.
object directory = a directory under the bucket that directly holds a regular file named xl.meta
referenced       = a data-dir name that is a UUID whose 16 raw bytes occur in the object's xl.meta (MinIO's DDir)
```

**"Attributable to a live or debt object", mechanically.** The rule is structural: an object present in the bucket
tree is charged somewhere in `Φ + H_F`.

- **Where the objects are.** MinIO single-drive stores an object at `<data>/<bucket>/<key>/xl.meta`, plus
  `<key>/<dataDir>/part.N` above the inline threshold. `ObjectKeys.kt` gives the keys:
  - `{ws}/{inbox}/{message}/raw.eml`;
  - `{ws}/{inbox}/{message}/attachments/{id}`;
  - the probes: `_probe/{node}/{uuid}` and `_probe/readiness/…`.
- **Why a present object is charged.** By annex §2.2 an object present in the bucket is exactly one of these:
  - live: a row (a) or a reservation (b), in *F(L)*;
  - late (c), in *H_F*;
  - pending debt: a probe, or a sweep or verifier target, in *F(D)*, whose pending rows are never superseded;
  - an orphan (f), covered by (b)/(c) until swept.

  A deleted object is not in the bucket: MinIO renames it into `.trash` at once (W, and *D* after the walk began).
- **What is attributed.** The object's own files, its **referenced** data directories, and the **first** block of
  every directory that leads to it, which E1–E3 measured inside *O_max*.
- **Referenced, without a msgpack parser.** MinIO stores each version's data directory (`DDir`) as a 16-byte msgpack
  bin in `xl.meta`. A subdirectory of an object directory is referenced when its name is a UUID and those 16 bytes
  occur in `xl.meta`. This fails safe: an unreadable `xl.meta`, or one that encoded the id another way, references
  nothing, so every data directory of that object is counted. That is an over-count, never an under-count. An old
  data directory an overwrite left behind is therefore *M* (row 17a). Before this rule, a reproduced 15 MB orphan
  `part.1` added 0.
- **What is not.** Nothing else in the tree: those blocks are *M*.
- **A foreign object** would be attributed and charged nowhere. Only the headroom row sees it (§5, row 20), which is
  the model-violation latch's job.

**The algorithm** (`metadata_accounting` in the reference implementation; to be copied verbatim):

0. **Fail closed.** Refuse, with exit 1 and no figure, unless all of these hold:
   - the mountpoint, the data directory and `.minio.sys` exist and are directories;
   - all three are on the mountpoint's filesystem (`st_dev`);
   - the data directory is the mountpoint or under it.

   A missing bucket directory is reported as `bucketPresent: false`, which is legitimate only at a 0-object
   baseline. Gate F and the generator refuse such evidence, because production pre-provisions the bucket.
1. Use allocated bytes only: `st_blocks × 512` from `lstat`, which is what `du -B1` counts, never the apparent size.
   Stay on one filesystem (`st_dev`), follow no symlink, and skip an entry that vanishes mid-walk. Every walk is
   iterative, so no directory depth can exhaust the interpreter's stack (tested at depth 1 100).
2. `minioSysBytes`: sum every inode under `.minio.sys`, skipping the contents of `.trash` but keeping the `.trash`
   directory's own blocks. `trashBytes` is `du -s .trash` (the directory included). `metadataInodes` counts
   everything. `trashInodes` is the contents of `.trash`, and `metadataInodesExclTrash` = `metadataInodes −
   trashInodes`.
3. `fsRoot`: sum every inode under the mount, skipping the `.minio.sys` and bucket subtrees.
4. Bucket: add the root's own blocks. Then make one post-order walk over each directory *d*:
   - *d* is an **object directory** if it directly holds a regular `xl.meta`.
   - *d* is **occupied** if it is an object directory, lies inside one, or any child is occupied.
   - A file in *d* is attributable if *d* is inside a referenced data directory, or if the file is the `xl.meta` of
     object directory *d*. Any other file is a stray: count it.
   - A subdirectory of an object directory that holds no `xl.meta` of its own and is not referenced: count its whole
     subtree (`unreferencedDataDirs`), and do not descend further.
   - If *d* is occupied, count `max(0, alloc(d) − B)`. Otherwise count `alloc(d)`.
   - Only directories and strays are `stat`ed. An object's own files are never read.
5. `unattributedBytes` = `fsRoot` + bucket root + occupied growth + unoccupied directories + strays + unreferenced
   data directories.

The JSON output carries every term (`unattributed.{fsRoot, bucketRoot, occupiedGrowth, unoccupiedDirectories,
strayFiles, unreferencedDataDirs}`), plus `bucketPresent`, `trashInodes` and `metadataInodesExclTrash`, so Ops can
see which class grew.

Cross-checks with standard tools are **approximate**, never normative:

- `minioSysBytes` ≈ `du -B1 -sx --exclude=.trash <data>/.minio.sys` plus the `.trash` directory's own blocks. `du`
  excludes **every** entry named `.trash` (only one is expected), and it counts a hard-linked file once where the
  script counts it at each link. MinIO makes no hard links, and the script's figure is never the smaller.
- `trashBytes` ≈ `du -B1 -sx <data>/.minio.sys/tmp/.trash`.
- `metadataInodes` ≡ `find <data>/.minio.sys -xdev | wc -l`.

The bucket classification has no `find`/`du` one-liner: it needs post-order occupancy and the `xl.meta` reference
test. The reference implementation is normative.

**Performance.** The walk's cost scales with the inodes walked. The delta review measured about 16 s, warm cache, for
100 000 objects with data directories (about 400 000 inodes). Past about 400 000 large objects, a walk exceeds 60 s.

The monitor therefore uses the **5-min cadence for the metadata walk** this note allows, with `statvfs` and the
observation staying per minute. It must never run two walks at once. A cycle whose walk is still running skips its
start, and an overrun must **not** read as healthy:

- **no observation is written** for that cycle, so `testinbox_storage_filesystem_observation_age_seconds` rises,
  alerts at *A_obs*, and admission eventually refuses (T1 answers `451` on a stale observation). That is a safe
  stall, never an unchecked admit;
- the monitor raises a `metadata-walk-overrun` alert at once;
- a stale metadata figure is never reused as current.

Ops reports the walk time measured on the E2 population.

**N1 invocation.** On the shared production mount, N1 runs the script with `--mount = --data-dir = <qual data dir>`.
`fsRoot` is then the qualification data directory alone, and production's `data/` is never walked. The real mount
root and `lost+found` are recorded once, at the baseline and at the end of E7. Production use (the monitor and the
gate F collector) passes the real mountpoint.

## 5. Where every allocation is counted, class by class

The table uses four places:

- **M**: in `metadataBytes`, checked against *M* by gate F and by the monitor, and by E2/E7 at qualification.
- **O_max**: charged per object by φ/*F* in Φ, on its row, reservation, debt row or *H_F* slot.
- **W**: trash, in Φ.
- **headroom row**: seen only by `used_bytes ≤ Φ + H_F + M` (gate F's physical-headroom row and the monitor's
  `model-violation` latch).

Every allocated block is inside `used_bytes`, so every class is also inside the headroom row. The walk partitions
every reachable inode into exactly one of M, O_max and W. Only rows 20–22 are not reachable by a walk.

| # | Class | Counted in | Not double-counted / not uncounted |
|---|---|---|---|
| 1 | `.minio.sys` static: `format.json`, `config/`, `pool.bin`, `buckets/` (bucket metadata, `.usage.json`, `.usage-cache.bin`, `.bloomcycle.bin`), `buckets/<b>/.metacache/` listing caches, healing and scanner state | M (`minioSysBytes`) | No object is charged for any of it. Unchanged from today's `minio_sys_bytes`. E7 measures it at the E2 population |
| 2 | `.minio.sys/tmp/<uuid>/`: **in-flight** PUT staging of a live reservation or an unresolved late object | M (`minioSysBytes`); **also** inside *F_r* / *H_F* (annex (b), (c)) | **Deliberate conservative overlap.** The monitor cannot tell in-flight staging from residue: a stalled `rename(2)` has no server bound (`FINALIZE-SOURCE.md`). The overlap is ≤ in-flight uploads × 15 MiB ≤ `procs × 16 × 15 MiB`. §7 and §8.1 cover it |
| 3 | `.minio.sys/tmp/<uuid>/` **residue** of aborted uploads after resolution; `tmp-old/` during startup cleanup | M | Annex (e). MinIO reclaims it after 24 h (`MINIO_API_STALE_UPLOADS_EXPIRY`) or on restart |
| 4 | `.minio.sys/multipart/` (incomplete or aborted multipart) | M | TestInbox never initiates multipart (ADR-035 §5): anything here is foreign or residue. The orphan sweep's `ListMultipartUploads` reports it |
| 5 | The contents of `.minio.sys/tmp/.trash/` | W (`trash_bytes`) | Excluded from `minioSysBytes`. An object trashed after the walk began is in *D* (annex §5.3 ordering lemma) |
| 5a | The `.trash` directory's own blocks | M **and** W (`du -s .trash` includes them) | This is annex §3.6's placement, kept on purpose: the conservative choice. Moving them to W alone would supersede §3.6 to save a few blocks, while counting them twice costs only those few blocks. A `.trash` that grew to many entries and was purged keeps its blocks (ext4 does not shrink it), and *M* sees that |
| 6 | A live object's own allocations: `{key}/` and its `xl.meta`, its **referenced** `{key}/<dataDir>/` and `part.N`, any extent or xattr block on those files | O_max + payload in *F(L)* | Never walked into *M*. Their first directory block is attributed. A block beyond the first of such a single-entry directory is not expected; if one appears, it is *M* (conservative) |
| 7 | Message-level `{message}/` and `{message}/attachments/` holding an object | first block O_max (§3.1, §3.2 term 2); the rest M | `attachments/` grows past one block only beyond about 90 attachments. That growth is in *M* and in *O_max*'s slack (bounded overlap, §7) |
| 8 | Objects of a retention batch between its prefix delete and its row delete | W (in trash); the rows are still in *F_c* | Annex `R(t)`: the overlap is by design and makes Φ larger. Nothing is left in the bucket: MinIO renames the object directory away |
| 9 | Objects still in the bucket with a **pending debt row**: an orphan-sweep target before its delete, an ambiguity-verifier late object, a witness or breaker probe | O_max via *F(D)* (rule P) | Pending rows are never superseded. The structural rule attributes the present object once, and *M* does not count it |
| 10 | Late objects that surface after their reservation's release (annex (c)) | *H_F* | Present, so attributed. *H_F* holds one `F(15 MiB, 1)` per occupied slot |
| 11 | Orphans (annex (f)) | (b)/(c) until the sweep removes them | Gate F's base-case row: `physical_listed ≤ covered` |
| 12 | Shared prefix directories holding an object: `{ws}/`, `{inbox}/`, `_probe/`, `_probe/{node}/` | first block O_max; **every block beyond the first M** | The first block is in the first object's φ: E2's "10 000 inboxes in one workspace" checks total ≤ Σφ. Growth beyond it is charged to *O_max*'s slack while live, and to nothing once drained, and the monitor cannot tell the two apart. So growth is counted in *M*: a **deliberate, bounded overlap** (§7) instead of a gap |
| 13 | A partially drained `{inbox}/` (ext4 never shrinks a directory) | M (row 12: every block beyond the first) | This is exactly the residue E2 measures after its 99 % drain |
| 14 | Directories under the bucket holding **no** object: a fully drained `{inbox}/` or `{ws}/` MinIO did not remove, an empty `{message}/` or `{key}/` left by a crash, an empty `_probe/{node}/` | M, every block | Unoccupied, so nothing is attributed |
| 15 | The bucket root `<data>/<bucket>/` | M, every block | Created by MakeBucket, so it is in no object's measured delta. Its growth (one entry per workspace) is included |
| 16 | Probe and witness residue: probe objects (row 9), `_probe` prefix directories (rows 12 and 14), probe staging (rows 2 and 3) | as in those rows | E7's "probe residue" is now inside `metadataBytes`, not a separate addend |
| 17 | Stray files under the bucket: not the `xl.meta` of an object directory, not inside a referenced data directory (for example `xl.meta.bkp`, a crash leftover) | M, in full | Charged by no object |
| 17a | An object directory's subdirectory that `xl.meta` does not reference: an old `<dataDir>/part.N` from a crashed or retried overwrite, a non-UUID directory | M, the whole subtree (`unreferencedDataDirs`) | φ charges one data directory per object. Any other is charged by nothing. An unreadable `xl.meta` references nothing (fail safe) |
| 18 | Mount root directory, `lost+found`, the MinIO data directory itself | M (`fsRoot`) | Outside both trees, so the walk sees them once. They are a few blocks |
| 19 | Anything else on the mount outside `.minio.sys` and the bucket | M (`fsRoot`) | Also fails gate F's dedicated-mount row (`otherDataOnMount`) |
| 20 | Foreign objects (written by anything but TestInbox) | headroom row only | Attributed structurally and charged nowhere, so `used > Φ + H_F + M` shows them. That is an Ops invariant violation (annex §3.6) |
| 20a | *(info)* An orphan directory inside an object directory that holds an `xl.meta` of its own | treated as a nested object: its unreferenced data directory, with its `part.1`, is still counted (row 17a); its own first block and `xl.meta` (about 4 KiB each) are not | MinIO forbids nested objects (an object key cannot be a prefix of another), so such a directory is foreign, as in row 20: the headroom row sees it. The few KiB left uncounted are a bounded, accepted gap |
| 21 | Unlinked files still open: a GET streaming an object deleted meanwhile, or a MinIO handle on a renamed tmp file | headroom row only (annex (e) budget: ≤ in-flight GETs × 15 MiB) | No walk can see them: they hold room in `used ≤ Φ + H_F + M`, inside *M*'s share |
| 22 | Filesystem internals: inode tables, bitmaps, group descriptors, the journal; hidden inodes | not in `used_bytes` (bsddf: `f_blocks` is net of them, so *C_fs* already is) | The qualified features enable no `orphan_file` or quota inode. If such an inode appeared, it would be in `used_bytes` and fall to the headroom row |
| 23 | Root-reserved blocks (`mkfs -m`) | not an allocation (in neither `used_bytes` nor `avail_bytes`) | This is a capacity question (annex §1: MinIO runs as root, so *C_fs* does not exclude them; the A1 (iv) uid evidence), not a metadata one |

## 6. Effect on E2, E7, gate F and the monitor

- **E2.** Its first PASS clause (`total delta ≤ Σφ`, amortized directory cost reported) is unchanged. It is what
  puts each directory's first block inside *O_max*.

  The drain clause becomes: "after the partial drain, `metadataBytes` (scope `minio-sys+unattributed`) fits *M*; it
  is reported as `measurements.metadataBytesMeasured` with `metadataBytesScope`". The drained inbox's residue counts
  in it: every block beyond the first of a directory that still holds an object, and every block of one that holds
  none.
- **E7.** "`minio_sys_bytes + probe residue ≤ M`" becomes "`metadataBytes` (scope `minio-sys+unattributed`) ≤ *M* at
  every sample". The peak is reported as `measurements.metadataBytesMeasured`, with `metadataBytesScope`. "Inodes ≤
  budget" stays on scope `minio-sys`. "Or *M* is raised in the record" now has a field: the record's
  `filesystem.metadataBytesMeasured` is the larger of E2's and E7's figures.
- **Gate F.** The evidence must state `minio.metadataBytesScope: "minio-sys+unattributed"`:
  - absent is NOT RUN;
  - another value is BLOCKED.

  Its `minio.bucketDirectoryBytes` is `unattributedBytes`, and it must state `minio.bucketPresent: true`. Absent is
  NOT RUN, and `false` is BLOCKED.

  The existing row `observation.minio_sys_bytes + bucketDirectoryBytes ≤ M` is kept unchanged. Three rows are new,
  each BLOCKED:
  - the record does not state `filesystem.metadataBytesScope: "minio-sys+unattributed"`;
  - the record carries no `filesystem.metadataBytesMeasured`;
  - the declared *M* is below that measurement (as *O_max* and the inode budget already are).

  No row is removed or relaxed.
- **The generator** refuses each of these:
  - an E2 or E7 without both bytes members, or with another scope;
  - gate F evidence without the bytes scope;
  - a declared *M* below the measurement.
- **The monitor** keeps the same check and the same latch: alert above *M*, latch at `2·M`. The only change is the
  figure it adds to `minio_sys_bytes`, which is `unattributedBytes` instead of every bucket directory block. The
  per-object double count goes away, and no threshold moves.
- **The application** is unchanged. It reserves *M* in rule (C) as a declared constant and never measures it. The
  declaration it reserves is the one gate F now compares with the qualified measurement.

## 7. Is *M* = 256 MiB still enough?

**For what the old rule broke, yes.** Per-object directories no longer reach *M*, so the 20 000–35 000-object ceiling
is gone. The terms *M* must hold:

- **Shared-directory growth while live** (rows 7 and 12). The annex's figure is about 49 B per entry; the worst case
  is 89 B, when every htree leaf is half full.
  - Objects present are at most `⌈G_F / K⌉`, with *K* = 28 688 B. At production G_F = 16 GiB that is 598 852.
  - So this term is at most 89 B × 598 852 ≈ 51 MiB, plus the same again for workspace and attachment entries:
    **≈ 102 MiB**.
- **In-flight staging** (row 2). At most `procs × 16 × F(15 MiB, 1)`, with F(15 MiB, 1) = 15 818 768 B. At
  `procs = 2` that is 506 200 576 B **≈ 483 MiB** (= *H_F*).
- **Open GETs** of unlinked objects (row 21). At most the in-flight GETs × 15 MiB. No walk sees them; they sit only
  in the headroom row `used ≤ Φ + H_F + M`. That row relies on the declared *M* covering them, so they get a term:
  *G_open*, Ops' declared GET concurrency × 15 MiB.
- **`.minio.sys` static peak**, measured by E7.
- **Residue** (rows 13, 14) is **not bounded by `G_F / K`**. ext4 never shrinks a directory, so every non-empty
  `{inbox}/` or `{ws}/` keeps the blocks of its **historical peak**, whatever it holds now. A long-lived workspace
  that once had many inboxes, or an inbox that peaked high and drains slowly, keeps them.
  - No declared constant bounds this term. *M* therefore bounds it only through the monitor's **alert**: the alert
    is the real control for residue growth.
  - The operator acts on the runbook entry (`docs/dev/production.md`, "MinIO metadata residue"). No latch or
    threshold is ever changed for it.

**The adequacy condition for M = 1 GiB:**

```
E7 static peak + G_open  ≤  1 024 MiB − ≈102 MiB (directories, worst case) − ≈483 MiB (staging, procs = 2)  ≈ 440 MiB
```

With 256 MiB the staging term alone is about 483 MiB, so the bytes check alerts under ordinary load. It crosses
`2·M` = 512 MiB, latching the monitor, as soon as everything else exceeds about 29 MiB. **256 MiB is not adequate**
at `procs ≥ 2`.

**The cost of +768 MiB**, from Ops' capacity-proof run (infinity-core `master` 69318bb,
`infra/testinbox/storage/activation/capacity-proof.py`; #124 comment 6670191661). The run used M = 1 073 741 824 in
the environment declarations, the pool file and the fs-monitor configuration, with production R_ops =
4 294 967 296 and P_F = 67 108 864:

```
[FS]   13 004 390 207 + unattributed 268 435 456 + trash 4 294 967 296 + P_F 67 108 864 + M 1 073 741 824
       + R_ops 4 294 967 296 = 23 003 610 943 <= C_fs 47 991 902 208          slack 24 988 291 265  OK
[I-C]  G_F 17 179 869 184 + D_budget 8 589 934 592 + P_F + M + R_ops = 31 205 621 760 <= C_fs 47 991 902 208
                                                                             slack 16 786 280 448  OK
       (= the current slack 17 591 586 816 − 805 306 368)
[R_ops + M >= root reserve]  5 368 709 120 >= 2 576 977 920                    slack  2 791 731 200  OK
CONSISTENCY OK; VERDICT FITS
```

- [FS] is the `FILESYSTEM` boundary of `CohortStorageBudget.demand`; [I-C] is `G_F + D_budget + P_F + M + R_ops ≤
  C_fs` (annex §2.1, `DeploymentSafety`).
- [GLOBAL_F] is unchanged: its demand has no *M* term.
- The live `storage-budget check` runs at W-E.

**M status (2026-11-05).** The owner approved **M = 1 GiB for production**, "subject to the capacity proof passing",
and it passes (#124 comment 6671007334; infinity-core !189 @ `cc1cab9` sets M = 1 073 741 824 in the production
environment declarations, the pool file and the fs-monitor production configuration).

- `production-expected.json` fixes it, and the record generator refuses gate F evidence that declares any other M.
- It is **not** an E7 PASS: E7 is evaluated against this budget when it completes.
- The staging example and the shipped staging record stay at 256 MiB.

## 8. Open points for the owner

1. **In-flight staging under *M* (row 2).** The review recommends **(a)**: raise *M* to 1 GiB through the
   declaration, subject to §7's adequacy condition once E2 and E7 report. It is conservative and keeps every control
   armed.

   Two alternatives were rejected in review:
   - **(b) Attribute** `.minio.sys/tmp/<uuid>` entries younger than `T_verify` to *F_r* ∪ *H_F*. Rejected: it is not
     conservative, because residue of a definitively failed upload hides under the allowance.
   - **(c) Keep 256 MiB.** Rejected: the bytes latch at `2·M` is reachable under ordinary load at `procs ≥ 2`.

   The owner chose (a): production *M* = 1 GiB (2026-11-05, see §7's M status). Staging stays at 256 MiB.
2. **`.trash` inodes (inode side, §2).** Scope `minio-sys` counts every inode in `.minio.sys/tmp/.trash`: trashed
   objects' own inodes, 2–4 per object. Size of the effect:
   - a paced teardown of 25 000–50 000 objects puts about 100 000 inodes in trash until purge, over the budget;
   - 50 000–100 000 objects reach the 200 000 latch;
   - the lab's 157 had no mass deletion.

   **The proposal.** Adopt scope `minio-sys-excl-trash` **only together with** an armed, scope-independent
   inode-headroom check on the monitor:

   ```
   inodes_total − inodes_used ≥ ⌈avail_bytes / B⌉
   ```

   This mirrors gate F's starting-headroom row (`scripts/check-storage-activation.sh`, "Blocks must run out before
   inodes"). Ops is building it as the fs-monitor `inode-headroom` latch, with the same gating as the other latches
   (A1 security condition C4).
   - **Reporting.** Trash inodes are reported apart (`trashInodes`, `metadataInodesExclTrash` in the script's
     output). The inode budget then compares `metadataInodesExclTrash`.
   - **No control is disabled or loosened.** The inode-budget latch stays armed, on a figure that no longer counts
     objects' own inodes, and a new latch is added.

   **Why trash inodes are covered.** *i_max* does not cover them for long. *D* charges a trashed object only until an
   observation supersedes its debt row; after that its bytes are in *W*, and its inodes are charged by nothing
   per-object. They are still covered, for two reasons:
   - every trash inode holds at least one block (the A1 census (vi): zero blockless inodes), and that block is in
     `trash_bytes` ⊂ `used_bytes`;
   - the filesystem has `I_fs ≥ C_fs / B` (gate F's inode row).

   So trash inodes cannot exhaust inodes before blocks. The armed inode-headroom latch checks exactly that, every
   cycle, whatever any budget counts.

   **How it is enforced: proven, never self-declared.**
   - **Without the proof, only `minio-sys` is acceptable.**
   - **The drill (E10).** It records the latch firing and the fs-monitor build that ran it:
     `measurements.inodeHeadroomLatch: "PASS"` and `measurements.fsMonitorSha256` (64 hex). The generator copies
     them into the record as `filesystem.inodeHeadroomLatchDrill`.
   - **The evidence** names the running fs-monitor build, `monitor.fsMonitorSha256`.
   - **The binding.** Gate F accepts `minio-sys-excl-trash` only when the record carries a `PASS` drill whose
     `fsMonitorSha256` equals the evidence's. The generator refuses the bundle unless E10's drill is `PASS` and
     names the build the gate F evidence names. A failed drill, a missing build, or a different build blocks or
     refuses.
   - **`monitor.inodeHeadroomLatchArmed: true`** is still required, as an additional statement. On its own it proves
     nothing.
   - **One scope on both sides.** Gate F requires the evidence's `metadataInodesScope` to equal the record's (the
     bytes scope is already fixed to `minio-sys+unattributed` on both sides), and the generator requires E7's to
     equal the evidence's.

## 9. What Ops changes (infinity-core; written here, not applied)

Vendor `metadata_accounting` and its module-level helpers from `scripts/adr035-metadata-accounting.py` **verbatim**
(stdlib only), as
`infra/testinbox/storage/adr035_metadata_accounting.py`. Pin it to this repository's commit in a header comment.

`infra/testinbox/storage/fs-monitor.py`: the monitor's metadata check and the collector both use this.

```diff
-def dir_blocks_and_inodes(bucket_dir, sys_dir):
-    dirb = n_dirs = 0
-    for dp, dn, fn in os.walk(bucket_dir, onerror=lambda e: None):
-        try: dirb += os.lstat(dp).st_blocks * 512; n_dirs += 1
-        except FileNotFoundError: pass
-    sys_inodes = 0
-    for dp, dn, fn in os.walk(sys_dir, onerror=lambda e: None): sys_inodes += 1 + len(fn)
-    return dirb, sys_inodes
+from adr035_metadata_accounting import metadata_accounting  # testinbox scripts/adr035-metadata-accounting.py, verbatim
+
+def dir_blocks_and_inodes(mount, data_dir, bucket):
+    """ADR-035 note "one definition of MinIO metadata": (unattributed bytes, metadata inodes, full accounting).
+    Bytes scope minio-sys+unattributed: per-object directory blocks are in O_max, never also in M. Inodes scope minio-sys."""
+    a = metadata_accounting(mount, data_dir, bucket)          # raises on ANY failure: never a partial figure
+    return a["unattributedBytes"], a["metadataInodes"], a     # report a["metadataBytesScope"], a["metadataInodesScope"]
```

- **Callers** pass the mountpoint, the data directory and the bucket instead of the two paths.
- **On ANY exception** in the walk, the cycle writes **no** observation and raises an alert. That covers
  `AccountingRefused` (a missing or foreign `.minio.sys` or data directory), a `PermissionError`, or an I/O error.
  It never reports a figure, and never reports 0. The script behaves the same: exit 1, nothing on stdout.
- **Scopes.** The monitor reports, with its figures, the scopes it counts: `metadataBytesScope`
  (`minio-sys+unattributed`), and `metadataInodesScope` (`minio-sys`, or `minio-sys-excl-trash` only under §8.2's
  proof).
- **`bucketPresent: false` at runtime** raises an alert at once. Production pre-provisions the bucket, so a missing
  bucket directory means the wrong path or a lost bucket. The metadata figure is not trusted while it lasts
  (`docs/dev/production.md` alert table).
- **New latch:** `inode-headroom` latches when `inodes_total − inodes_used < ⌈avail / B⌉`, with the same gating as the
  other latches (§8.2). Never run two walks at once; the metadata walk runs every 5 min (§4, "Performance").
- **The M check** is unchanged in form: `minio_sys_bytes + unattributed > M` alerts, and `> 2·M` latches. The
  observation's `minio_sys_bytes` and `trash_bytes` may come from the same call (`a["minioSysBytes"]`,
  `a["trashBytes"]`). They approximate today's `du` figures; `minio_sys_bytes` now also holds the `.trash`
  directory's own blocks (row 5a).
- **Logging:** log `a["unattributed"]` with each alert, so the class that grew is named.

`infra/testinbox/storage/activation/collect-filesystem-evidence.py`:

```diff
-        "minio": {"bucketDirectoryBytes": bdir, "metadataInodes": meta_inodes, "metadataInodesScope": "minio-sys"},
+        "minio": {"bucketDirectoryBytes": acc["unattributedBytes"], "metadataBytesScope": acc["metadataBytesScope"],
+                  "bucketPresent": acc["bucketPresent"],
+                  "metadataInodes": acc["metadataInodes"], "metadataInodesScope": acc["metadataInodesScope"]},
+        # only when E10 drilled the inode-headroom latch on THIS fs-monitor build (note §8.2) may it drop .trash:
+        #   "minio": {..., "metadataInodes": acc["metadataInodesExclTrash"], "metadataInodesScope": "minio-sys-excl-trash"},
+        "monitor": {"fsMonitorSha256": <sha256 of the running fs-monitor.py>,
+                    "inodeHeadroomLatchArmed": <the fs-monitor's inode-headroom latch is armed>},
```

**N1 (E2, E7):** at each measurement point, run:

```
python3 scripts/adr035-metadata-accounting.py --mount <qual data dir> --data-dir <qual data dir> --bucket <bucket>
```

- **E2:** after the 99 % drain, report `measurements.metadataBytesMeasured` = `.metadataBytes`, with
  `metadataBytesScope: "minio-sys+unattributed"`. Report `.unattributed` as the amortized and residual directory
  cost.
- **E7:** at every sample, take the maximum `.metadataBytes` as `metadataBytesMeasured` and the maximum
  `.metadataInodes` as `metadataInodesMeasured`, each with its scope.

The **gate F evidence**, after the N1 cleanup, is the collector above.

## 10. The §9a venue (generator rule R3)

Ops runs §9a, the slow-W sweep, on its **own** 3 GiB loop image on `/` under dm-delay, by design. Its
`filesystemUuid` therefore differs from the final filesystem `c33e14fd-6b6f-4f05-881e-dbc4db22c964`.

**The generator accepts exactly two venues:**

- **The final filesystem:** `venue.json.filesystemUuid` = `c33e14fd…`, and the `9a` member absent or
  `"final-filesystem"`.
- **Ops' own image:** `venue.json` `9a: "loop-image-on-root + dm-delay(slow-W)"`, with a different uuid. Every
  identity element must equal the final filesystem's:
  - the kernel (the venue and every pass's `uname.txt`);
  - the MinIO member, binary, release, commit, mode and runtime-configuration hash (from every pass's capture);
  - the mount type and options (`mount.txt`);
  - the filesystem features, block size and inode size, read from `qualification-9a/venue-dumpe2fs-h.txt`, whose
    UUID must be the venue's.

  Only the uuid and the geometry may differ. The record's `qualification.platform` and `PROVENANCE.md` say which
  venue ran. The `filesystem` elements stay the final filesystem's.

**Anything else is refused:** another uuid without the label, another label, the final uuid with the own-image label,
or differing features. The N1 condition throughout is the running kernel 6.8.0-147-generic with the boot id
unchanged, checked at every step.

## 11. Text to append to ADR-035 on acceptance

> ## Amendment note (<date of acceptance>, WS-N1): one definition of MinIO metadata, in inodes and bytes
>
> Authority: the owner's acceptance of `docs/adr/0035-amendment-note-metadata-inodes-PROPOSED.md` (owner decision of
> 2026-11-05: double counting removed, conservative coverage of every allocation kept; application, monitor and
> qualification agree; no safety control disabled).
>
> **Inodes.** Wherever this ADR or its annex (Amendment 2) speaks of MinIO metadata inodes, *M*'s inode budget or the
> inodes of MinIO's metadata, the counted set is every inode under the MinIO data directory's `.minio.sys` (scope
> `minio-sys`). This applies to:
>
> - annex §3.5;
> - the §6 monitor row "or their inodes > *M*'s inode budget";
> - the §10 E7 criterion "inodes ≤ budget";
> - the record's `filesystem.metadataInodesMeasured`;
> - the declared `metadataBudgetInodes`;
> - gate F.
>
> Directories under the bucket are per-object inodes charged by `i_max` (annex §3.5).
>
> The same count without the contents of `.minio.sys/tmp/.trash` (scope `minio-sys-excl-trash`) may be used **only
> while** the Ops monitor's scope-independent inode-headroom latch (`inodes_total − inodes_used ≥ ⌈avail / B⌉`) is
> armed. Trashed objects' inodes are covered by that latch once their debt rows are superseded, because each holds a
> block and `I_fs ≥ C_fs / B`. Gate F refuses that scope unless the record carries a `PASS` E10 drill of that latch
> on the fs-monitor build the evidence names. Gate F also refuses evidence and a record that count inodes over
> different scopes.
>
> **Bytes.** Wherever this ADR or its annex speaks of *M*'s bytes, the counted figure is `metadataBytes` (scope
> `minio-sys+unattributed`): the allocated bytes under `.minio.sys` except the contents of `.minio.sys/tmp/.trash`
> (which are *W*; the `.trash` directory's own blocks stay in *M*, as annex §3.6 puts them), plus every allocation
> on the mount outside `.minio.sys` that no object is charged for. The second term is:
>
> - the bucket root;
> - every block beyond the first of a bucket directory that is, is inside, or contains an object directory (a
>   directory holding `xl.meta`);
> - every block of a bucket directory holding no object;
> - every file under the bucket that is not part of an object;
> - every subdirectory of an object directory that its `xl.meta` does not reference;
> - every inode outside `.minio.sys` and the bucket.
>
> This applies to annex §2.2 class (e), the §6 monitor row, the §9 gate F row, E2's drain clause and E7's criterion.
> E7's "probe residue" is inside it.
>
> The first block of each directory leading to an object, and the object's own files, are in *O_max* (annex §3.1–§3.3,
> verified in aggregate by E2's `total delta ≤ Σφ`). They are never also in *M*. The reference implementation is
> `scripts/adr035-metadata-accounting.py`.
>
> **Records and gate F.** The qualification record carries `filesystem.metadataBytesMeasured`, the larger of E2's
> post-drain and E7's peak `metadataBytes`, with `metadataBytesScope`. Gate F requires the evidence's
> `minio.metadataBytesScope` and the record's `filesystem.metadataBytesScope` to be `minio-sys+unattributed`, and the
> declared *M* to be at least the record's measurement.
>
> The walk fails closed: no figure for a missing or foreign `.minio.sys` or data directory. Evidence collected with
> no bucket directory (`bucketPresent: false`) does not pass gate F.
>
> **What does not change.** The inode budget and every other criterion are unchanged. *M* is a declaration: 1 GiB
> for production by the owner's decision of 2026-11-05 (subject to the capacity proof, which passes), and 256 MiB on
> staging. Post-drain residue has no
> declared bound (ext4 keeps each directory's historical peak). *M* bounds it through the monitor's alert, and the
> operator acts on the runbook, never by changing a latch. In-flight upload staging
> under `.minio.sys/tmp` stays in *M* and is also charged in *F_r*/*H_F*: a deliberate, conservative overlap, sized
> in the note's §7.
