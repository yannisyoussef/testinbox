# PROPOSED ADR-035 amendment note: one definition of MinIO metadata, in inodes and bytes

**Status: PROPOSED, pending the owner's acceptance of this final text.** The normative text of ADR-035 and its annex
(Amendment 2) is not edited by this proposal. Once the owner accepts it, the dated note in
[Text to append](#11-text-to-append-to-adr-035-on-acceptance) is appended to
`docs/adr/0035-physical-storage-bound-at-ingest.md`, after the 2026-10-30 note. Until then, the pull request that
carries the matching gate F, generator and reference-implementation change (#133) must not merge.

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

## 2. Inodes (unchanged since Ops implemented it)

MinIO metadata inodes are **every inode under `<data>/.minio.sys`**: the directory itself, `.trash` included
(`find <data>/.minio.sys -xdev | wc -l`). The scope is `minio-sys`.

Bucket directories are per-object inodes charged by `i_max` (§3.5), and are never also metadata. They stay bounded by:

- §3.5's `inodes used ≤ i_max · N ≤ Σφ / B`;
- gate F's starting-headroom row, `f_files − inodes used ≥ ⌈avail / B⌉`;
- the A1 census (vi), which found zero blockless inodes on the E2/E7 population.

The budget (100 000) and every criterion are unchanged. Gate F refuses evidence or a record that does not state
`minio-sys`: NOT RUN when the field is absent, BLOCKED when it names another scope. The generator refuses an E7 that
does not state it. See §8.2 for an open point about `.trash` inodes.

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
minioSysBytes   = allocated bytes of every inode under <data>/.minio.sys, the directory included, except
                  <data>/.minio.sys/tmp/.trash and its subtree (that is W)  (= today's observation minio_sys_bytes)
unattributed    = every other allocation on the mount that no object is charged for:
  bucket root, in full;  a stray file under the bucket (not part of an object), in full;
  a directory under the bucket that IS, is INSIDE or CONTAINS an object directory: every block beyond its first;
  a directory under the bucket that holds no object: every block;  every inode outside .minio.sys and the bucket
  (mount root, lost+found, the data directory itself), in full.
object directory = a directory under the bucket that directly holds a regular file named xl.meta
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
- **What is attributed.** The object's own files, its data directories, and the **first** block of every directory
  that leads to it, which E1–E3 measured inside *O_max*.
- **What is not.** Nothing else in the tree: those blocks are *M*.
- **A foreign object** would be attributed and charged nowhere. Only the headroom row sees it (§5, row 20), which is
  the model-violation latch's job.

**The algorithm** (`metadata_accounting` in the reference implementation; to be copied verbatim):

1. Use allocated bytes only: `st_blocks × 512` from `lstat`, which is what `du -B1` counts, never the apparent size.
   Stay on one filesystem (`st_dev`), follow no symlink, and skip an entry that vanishes mid-walk.
2. `minioSysBytes`: sum every inode under `.minio.sys`, skipping the `.trash` subtree. `trashBytes` is that subtree.
   `metadataInodes` counts both.
3. `fsRoot`: sum every inode under the mount, skipping the `.minio.sys` and bucket subtrees.
4. Bucket: add the root's own blocks. Then make one post-order walk over each directory *d*:
   - *d* is an **object directory** if it directly holds a regular `xl.meta`.
   - *d* is **occupied** if it is an object directory, lies inside one, or any child is occupied.
   - A file in *d* is attributable if *d* is inside an object directory, or if the file is the `xl.meta` of object
     directory *d*. Any other file is a stray: count it.
   - If *d* is occupied, count `max(0, alloc(d) − B)`. Otherwise count `alloc(d)`.
   - Only directories and strays are `stat`ed. An object's own files are never read.
5. `unattributedBytes` = `fsRoot` + bucket root + occupied growth + unoccupied directories + strays.

The JSON output carries every term (`unattributed.{fsRoot, bucketRoot, occupiedGrowth, unoccupiedDirectories,
strayFiles}`), so Ops can see which class grew.

Cross-checks with standard tools, for the parts that have one:

- `minioSysBytes` ≡ `du -B1 -sx --exclude=.trash <data>/.minio.sys`;
- `trashBytes` ≡ `du -B1 -sx <data>/.minio.sys/tmp/.trash`;
- `metadataInodes` ≡ `find <data>/.minio.sys -xdev | wc -l`.

The bucket classification has no `find`/`du` one-liner: it needs post-order occupancy. The reference implementation
is normative.

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
| 5 | `.minio.sys/tmp/.trash/` and everything under it, `.trash` itself included | W (`trash_bytes`) | Excluded from `minioSysBytes`. An object trashed after the walk began is in *D* (annex §5.3 ordering lemma) |
| 6 | A live object's own allocations: `{key}/` and its `xl.meta`, `{key}/<dataDir>/` and `part.N`, any extent or xattr block on those files | O_max + payload in *F(L)* | Never walked into *M*. Their first directory block is attributed. A block beyond the first of such a single-entry directory is not expected; if one appears, it is *M* (conservative) |
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
| 17 | Stray files under the bucket: not the `xl.meta` of an object directory, not inside an object's data directory (for example `xl.meta.bkp`, a crash leftover) | M, in full | Charged by no object |
| 18 | Mount root directory, `lost+found`, the MinIO data directory itself | M (`fsRoot`) | Outside both trees, so the walk sees them once. They are a few blocks |
| 19 | Anything else on the mount outside `.minio.sys` and the bucket | M (`fsRoot`) | Also fails gate F's dedicated-mount row (`otherDataOnMount`) |
| 20 | Foreign objects (written by anything but TestInbox) | headroom row only | Attributed structurally and charged nowhere, so `used > Φ + H_F + M` shows them. That is an Ops invariant violation (annex §3.6) |
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

  Its `minio.bucketDirectoryBytes` is `unattributedBytes`.

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

**For what the old rule broke, yes.** Per-object directories no longer reach *M*: the 20 000–35 000-object ceiling
is gone. The directory part of *M* is now the shared-directory overlap (rows 7, 12) plus residue (rows 13, 14). The
size of each term:

- Shared-directory growth costs about 49 B per entry, by the annex's own figure (about 1 200 blocks per 100 000
  messages). The worst case is about 89 B, when every htree leaf is half full.
- An object on disk costs at least *K* = 28 688 B, so the number of objects present is at most `G_F / K`:
  - G_F = 20 GiB (the staging example) gives about 749 000;
  - G_F = 32 GiB gives about 1.2 M.
- **Live overlap:** about 35 MiB at 20 GiB (56 MiB at 32 GiB). The worst case is 127 MiB (203 MiB), when every
  object sits in a directory past 92 entries and the leaves are half full.
- **Residue** of partially drained inboxes is at most the peak growth of the inboxes being torn down. In the worst
  case that equals the live term again.

**For the runtime monitor, no, once in-flight staging counts (row 2).** `minioSysBytes` includes
`.minio.sys/tmp/<uuid>` staging:

- up to 16 × 15 MiB = 240 MiB per ingestion process;
- with `procs = 2`, up to 480 MiB plus the static `.minio.sys` and the directory term.

That crosses *M* under ordinary load, so the monitor alerts. Above about 32 MiB of everything else, it crosses `2·M`
= 512 MiB, so the monitor latches and stops mail.

E7 does not drive ingestion at full concurrency, so the qualified figure does not show this. Gate F's activation run
(no load) is not affected.

**The evidence-based figure:**

```
M ≥ E7 static peak  +  2 · c_dir(E2) · ⌈G_F / K⌉  +  procs · 16 · (15 MiB + 3 · B)
  ≈ E7 + 70 MiB + 481 MiB          (procs = 2, G_F = 20 GiB, c_dir = 49 B)
```

This recommends **M = 1 GiB** once E2 and E7 report. It costs 768 MiB of *G_F*, 1.6 % of production's *C_fs*
(47 991 902 208 B).

**This note does not change M.** That is the owner's decision (§8.1). Until it is taken, E7's measurement and gate F
compare against the declared 256 MiB.

## 8. Open points for the owner

1. **In-flight staging under *M* (row 2).** Choose one:
   - **(a) Raise *M*** through the declaration, to the figure of §7 (recommended; conservative, keeps every control
     armed).
   - **(b) Attribute** `.minio.sys/tmp/<uuid>` entries younger than `T_verify` to *F_r* ∪ *H_F*. This is not
     conservative: residue of a definitively failed upload could hide under the allowance for up to `T_verify`.
   - **(c) Keep 256 MiB**, with the monitor's bytes latch armed, and accept that full-concurrency ingestion at
     `procs ≥ 2` can latch it.

   Until one is chosen, the bytes latch at `2·M` is a production risk under load. The rest of the monitor (identity,
   preallocation, model-violation, headroom) is unaffected.
2. **`.trash` inodes (inode side, §2).** Scope `minio-sys` counts every inode in `.minio.sys/tmp/.trash`. Those are
   trashed objects' own inodes (2–4 per object), which `i_max` already charges through *D* ("trash preserves the
   ratio", §3.5): the inode analogue of the bytes double count.
   - **Size of the effect.** A paced teardown of 25 000–50 000 objects puts about 100 000 inodes in trash until purge,
     which exceeds the budget. 50 000–100 000 objects reach the 200 000 latch. The lab's 157 had no mass deletion.
   - **The candidate fix.** Use scope `minio-sys-excl-trash`, the same boundary as the bytes side. It is not made
     here, because Ops implemented `minio-sys` and the owner accepted it conditionally.
   - **Until the owner decides,** keep the inode latch alert-only during mass retention, or decide the fix.

## 9. What Ops changes (infinity-core; written here, not applied)

Vendor `metadata_accounting` from `scripts/adr035-metadata-accounting.py` **verbatim** (one function, stdlib only), as
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
+    a = metadata_accounting(mount, data_dir, bucket)
+    return a["unattributedBytes"], a["metadataInodes"], a
```

- **Callers** pass the mountpoint, the data directory and the bucket instead of the two paths.
- **The M check** is unchanged in form: `minio_sys_bytes + unattributed > M` alerts, and `> 2·M` latches. The
  observation's `minio_sys_bytes` and `trash_bytes` may come from the same call (`a["minioSysBytes"]`,
  `a["trashBytes"]`), which equal today's `du` figures.
- **Logging:** log `a["unattributed"]` with each alert, so the class that grew is named.

`infra/testinbox/storage/activation/collect-filesystem-evidence.py`:

```diff
-        "minio": {"bucketDirectoryBytes": bdir, "metadataInodes": meta_inodes, "metadataInodesScope": "minio-sys"},
+        "minio": {"bucketDirectoryBytes": acc["unattributedBytes"], "metadataBytesScope": acc["metadataBytesScope"],
+                  "metadataInodes": acc["metadataInodes"], "metadataInodesScope": acc["metadataInodesScope"]},
```

**N1 (E2, E7):** at each measurement point, run:

```
python3 scripts/adr035-metadata-accounting.py --mount <qual mount> --data-dir <qual data dir> --bucket <bucket>
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
> **Bytes.** Wherever this ADR or its annex speaks of *M*'s bytes, the counted figure is `metadataBytes` (scope
> `minio-sys+unattributed`): the allocated bytes under `.minio.sys` except `.minio.sys/tmp/.trash` (which is *W*),
> plus every allocation on the mount outside `.minio.sys` that no object is charged for. The second term is:
>
> - the bucket root;
> - every block beyond the first of a bucket directory that is, is inside, or contains an object directory (a
>   directory holding `xl.meta`);
> - every block of a bucket directory holding no object;
> - every file under the bucket that is not part of an object;
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
> **What does not change.** *M*, the inode budget and every other criterion are unchanged. In-flight upload staging
> under `.minio.sys/tmp` stays in *M* and is also charged in *F_r*/*H_F*: a deliberate, conservative overlap, sized
> in the note's §7.
