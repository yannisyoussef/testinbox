# ADR-035 WS-N1 A1 (v): full-scale lab E4, kernel 6.8.0-147, 2026-10-19

The full-scale lab E4 that amendment A1 (v) requires: "A full-scale E4 has PASSED on a lab filesystem with the
identical feature list, kernel `6.8.0-147-generic` and member `3f97c565…`. It is committed in this repository and
cited by evidence reference." Cited from the production record as `labFullScaleEvidence` (E4.json `measurements`),
checked by `scripts/build-qualification-record.py` (path committed here, sha256 equal).

Run by Ops in the 006C k147 phase-A corpus (Ops workspace `ti-storage-006c/phase-006c2/evidence/k147-phase-a-2026-10-19/`),
delivered on GitLab infinity-core #12 note 1715 as `a1v-e4-k147.tgz` (sha256 `0891d99c824c8b677597a9c6f349b3c5bde0c27860e11ce9c51cf8de25bc0e31`).
The three files below are byte-identical to the corpus: their lines in `SHA256SUMS` are the corpus's own lines
(the full 46-line corpus file was cross-checked on receipt, 2026-11-10). Nothing here was typed by hand.

| File | What it shows |
|---|---|
| `runs/e4-identity.txt` | kernel `6.8.0-147-generic`; a 1 GiB ext4 image (262,144 inodes, 262,144 blocks of 4096 B), `rw,relatime`; the `Filesystem features:` line; MinIO member `sha256:3f97c565…64bb` |
| `runs/e4-e4.json` | the harness `e4` result: 77,173 objects written; at failure `bavail 0`, `bfree 4098`, `ffree 30530`; 827 × `(507, XMinioStorageFull)`; `PASS_inodes_remain_when_blocks_exhausted: true` |
| `logs/e4.log` | the run log |

**Feature list.** The identity file's `Filesystem features:` line is byte-identical to the live production
`dumpe2fs -h` of the final filesystem (UUID `c33e14fd…`), checked by Ops on 2026-11-10 (#12 note 1710):
`has_journal ext_attr resize_inode dir_index filetype needs_recovery extent 64bit flex_bg sparse_super large_file huge_file dir_nlink extra_isize metadata_csum`.

**Reading.** Blocks were exhausted at `f_bavail = 0` with `f_bfree = 4098` and `f_ffree = 30530` remaining: MinIO
answered 507 before inodes ran out, which is the property E4 qualifies.
