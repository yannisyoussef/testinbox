# ADR-035 filesystem-fuse design review — evidence (TI-STORAGE-006C, 2026-10-08)

Supports `docs/adr/0035-amendment-proposal-filesystem-fuse.md` (PROPOSED). All runs were on **isolated** labs on the
staging host (same MinIO member `3f97c565…`, 1536 MiB, ext4 `rw,relatime` on a dedicated loop device, kernel
6.8.0-142-generic); none touched the live staging MinIO.

- `quota-lag-lab/` — why the bucket quota is not a bound: 16 × 15 MiB writers, unique bucket per run, watchdog-bounded
  (`lab-probe.py`); `H-results.json` per run; `FUSE-2026-10-08b.json` the Q calculation; scanner cycle timestamps of the
  lab under load and of live staging idle (`scanner-status-*.json`; the lab file was copied verbatim from the session
  output — its on-disk save failed, as its `_provenance` field says).
- `fs-lab/` — ENOSPC behaviour on a 2 GiB filesystem: amplification (A), fill to ENOSPC (B), operations at full (C1),
  restart at full (C2, `fill.py` + `verify.py`), batch-delete purge latency (D), late-commit sweep at +17.8 min (E).
  `RESULTS.md` summarises every number the proposal uses.

Throwaway lab credentials (`labroot`) appear in the scripts; they protect nothing.
