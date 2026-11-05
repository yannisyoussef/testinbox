# PROPOSED ADR-035 amendment note: one definition of MinIO metadata inodes

**Status: PROPOSED, for the owner's acceptance.** The normative text of ADR-035 and its annex (Amendment 2) is not
edited by this proposal. Once the owner accepts it, the dated note in [Text to append](#text-to-append-to-adr-035-on-acceptance)
is appended to `docs/adr/0035-physical-storage-bound-at-ingest.md`, after the 2026-10-30 note. Until then, the
pull request that carries the matching gate F, docs and generator change (#133) must not merge.

## The conflict (W3 finding, verified)

| Where | What it counts as "MinIO metadata inodes" |
|---|---|
| Annex §3.5 (normative) | Each object's inodes, including the amortized `{ws}/`, `{inbox}/`, `{message}/`, `{key}/` and `{uuid}/` directories, are charged by `i_max = 6`, and `inodes used ≤ i_max · N`. *M*'s inode budget (100 000) is left "for MinIO's own (`.minio.sys`, a few hundred)". |
| Annex §6, monitor row (normative) | "`minio_sys_bytes` + bucket directory blocks `> M`, **or their inodes** > *M*'s inode budget". Read literally, this counts every bucket directory. |
| Annex §10 E7 (normative) | "inodes ≤ budget", measured "on the E2 population". E2 has 100 000 messages in one inbox, so ≥ 200 000 `{message}/` and `{key}/` directories. Under the §6 reading E7 cannot pass with a budget of 100 000. |
| `docs/dev/filesystem-evidence.md` (gate F input) | `minio.metadataInodes` = `.minio.sys` inodes **plus every bucket directory**. |
| Ops `fs-monitor.py` `dir_blocks_and_inodes` and `collect-filesystem-evidence.py` | `n_dirs` (every directory under the bucket) + `.minio.sys` entries. The monitor latches at `2 ×` the inode budget. |
| `scripts/check-storage-activation.sh` gate F | Live `minio.metadataInodes ≤ declared.metadataBudgetInodes`, and record `metadataInodesMeasured ≤ declared.metadataBudgetInodes`. |
| Lab E7 (`metadataInodesMeasured` 157) | `.minio.sys` only. No bucket-directory count is that small for the E2 population. |

**Consequence.** A full cohort has hundreds of thousands of per-object directories. Under the current rule, gate F's
metadata-inode row blocks, and the production fs-monitor latches every mode at 200 000. That stops mail at roughly
70 000 objects. The alternative is that the record's 157 is compared with a figure counted differently, and then
means nothing.

## The reconciliation (smallest, no budget or criterion lowered)

**One definition.** MinIO metadata inodes are **every inode under `<data dir>/.minio.sys`**: the directory itself,
its subdirectories and its files (`find <data dir>/.minio.sys -xdev | wc -l`). The scope name is `minio-sys`. Bucket
directories are per-object inodes charged by `i_max` (§3.5) and are **never also** counted as metadata.

The same definition is used by:

- E7 (`metadataInodesMeasured`, with `metadataInodesScope: "minio-sys"` in its measurement);
- the record (`filesystem.metadataInodesMeasured`, plus the additive member `filesystem.metadataInodesScope: "minio-sys"`);
- the declaration (`metadataBudgetInodes`, the fs-monitor's `metadata_budget_inodes`);
- gate F (`minio.metadataInodes`, plus the required `minio.metadataInodesScope: "minio-sys"`);
- the fs-monitor's metadata check and latch.

Why nothing is relaxed:

- **The inode budget is unchanged at 100 000.** The E7 criterion "inodes ≤ budget" and gate F's two comparisons keep
  their wording. They now compare like with like.
- **Bucket directory inodes stay bounded, by the argument the contract already makes.** §3.5 proves
  `inodes used ≤ i_max · N ≤ Σφ / B`. Every bucket directory owns at least one block, and that block is counted in
  `used_bytes`, so it is constrained by `used ≤ Φ + H_F + M` (the physical headroom row).
- **Gate F already checks the physical side.** Its starting-headroom row requires
  `f_files − f_inodes_used ≥ ⌈avail / B⌉`, so blocks exhaust before inodes whatever the inodes are.
- **The inode premise gets a direct check.** The A1 census (vi), the WS-N1 note, shows zero blockless inodes on the
  E2/E7 population, `.minio.sys` included.
- **Fail closed on a mixed definition.** Gate F refuses evidence that does not state `metadataInodesScope:
  "minio-sys"` (NOT RUN when the field is absent, BLOCKED when it names another scope). It also blocks on a record
  whose E7 figure does not state the scope. The record generator refuses both, so no older collector output can
  pass silently.

## Not decided here: the same double count on the bytes side

Annex §6 and §9 ("`minio_sys_bytes` + bucket directory blocks `≤ M`") and E2's PASS clause ("the directory blocks
left after the partial drain … fit *M*") also count **every** bucket directory block. The per-object `{message}/`,
`{key}/` and `{uuid}/` blocks are already inside *O_max* (§3.1, §3.2).

- **Size of the effect.** About 2–3 directory blocks per object × 4 KiB exceeds *M* = 256 MiB at roughly 20 000–35 000
  objects.
- **What happens then.** Gate F's bytes row would block, and the fs-monitor would latch at `2·M`.
- **What is not affected.** Today's gate F evidence (a near-empty bucket, 4 096 B) passes.

This note does **not** change the bytes rule. A separate owner decision is needed. The candidate is to count *M*'s
bytes as `.minio.sys` plus bucket-directory blocks **not attributable to a live or debt object**. That is E2's
post-drain residue, measured by E2 and bounded in production by the physical headroom row. Until that decision,
the fs-monitor's bytes latch is a production risk at cohort scale. Ops should keep the monitor's metadata latch in
**alert-only** mode for bytes until it is decided. This requires no app change.

## The Ops collector change (infinity-core; written here, not applied)

`infra/testinbox/storage/fs-monitor.py`: the monitor's metadata check and the collector both use this function.

```diff
 def dir_blocks_and_inodes(bucket_dir, sys_dir):
     dirb = n_dirs = 0
     for dp, dn, fn in os.walk(bucket_dir, onerror=lambda e: None):
         try: dirb += os.lstat(dp).st_blocks * 512; n_dirs += 1
         except FileNotFoundError: pass
     sys_inodes = 0
     for dp, dn, fn in os.walk(sys_dir, onerror=lambda e: None): sys_inodes += 1 + len(fn)
-    return dirb, n_dirs + sys_inodes
+    # Metadata inodes: .minio.sys only (ADR-035 note "one definition of MinIO metadata inodes", scope minio-sys).
+    # Bucket directories are per-object inodes charged by i_max = 6 (annex §3.5), never also metadata.
+    return dirb, sys_inodes
```

`infra/testinbox/storage/activation/collect-filesystem-evidence.py`:

```diff
-        "minio": {"bucketDirectoryBytes": bdir, "metadataInodes": meta_inodes},
+        "minio": {"bucketDirectoryBytes": bdir, "metadataInodes": meta_inodes, "metadataInodesScope": "minio-sys"},
```

**The N1 E7 run** counts `find <qual data dir>/.minio.sys -xdev | wc -l` at every sample and reports the maximum as
`measurements.metadataInodesMeasured`, with `"metadataInodesScope": "minio-sys"`. If E7 is carried (W3 A5), Ops
confirm that the staging figure 157 was counted this way.

## Text to append to ADR-035 on acceptance

> ## Amendment note (<date of acceptance>, WS-N1): one definition of MinIO metadata inodes
>
> Authority: the owner's acceptance of `docs/adr/0035-amendment-note-metadata-inodes-PROPOSED.md`.
>
> Wherever this ADR or its annex (Amendment 2) speaks of MinIO metadata inodes, *M*'s inode budget or the inodes of
> MinIO's metadata, the counted set is every inode under the MinIO data directory's `.minio.sys` (scope
> `minio-sys`). This covers annex §3.5, the §6 monitor row "or their inodes > *M*'s inode budget", the §10 E7
> criterion "inodes ≤ budget", the qualification record's `filesystem.metadataInodesMeasured`, the declared
> `metadataBudgetInodes` and gate F. Directories under the bucket are per-object inodes charged by `i_max` (annex
> §3.5) and are not counted again as metadata. They remain bounded by §3.5's argument and, physically, by gate F's
> starting-headroom row (`f_files − inodes used ≥ ⌈avail / B⌉`). The inode budget (100 000) and every criterion
> are unchanged. Gate F requires the evidence's `minio.metadataInodesScope` and the record's
> `filesystem.metadataInodesScope` to be `minio-sys`. The bytes half of *M* (`minio_sys_bytes` + bucket directory
> blocks) is not changed by this note.
