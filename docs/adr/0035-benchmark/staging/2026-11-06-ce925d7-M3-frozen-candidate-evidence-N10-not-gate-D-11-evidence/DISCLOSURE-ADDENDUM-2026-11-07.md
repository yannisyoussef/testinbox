# Disclosure addendum, 2026-11-07: M3 run 1 overlapped an unrelated Ops workload

This file is **new**. No existing evidence file in this directory was edited. `SHA256SUMS` covers the original files only. This
addendum's companion is `checks/host-contamination-reanalysis-2026-11-07.md`, sha256 `5ed55bb972480c8ea72ae8fbbd9f236575504f58c32bbf5bfdd26b9a178732d5`.

## What overlapped

Run 1 ran 2026-11-06 **22:33:43Z → 22:43:06Z** (`runs.log`). On the same host (vmi2932906), an unrelated Ops workload ran
during it:

- **A selftest mutant run** of the infinity-core release-config MR, about **22:28:42.97Z → 22:42:03.83Z**.
  - About 97 mutants, around 1 CPU core, with `docker compose` renders. The processes were `selftest.sh` / `bash`.
  - Source: the Ops records of that workstream (`stage-b/fasttrack/rcfg/`). The start and end are the creation and
    last-modification times of its output file `rcfg-selftest2.out` (100 lines).
- **One suite run** of the same workstream, about **22:42:10Z → 22:42:45Z**.

Together they cover the whole run up to 22:42:45Z, including about 43 s of the last scenario, chosen-ws11-c100-r22
(22:41:55–22:43:04Z).

## Why the run's own contamination map did not flag it

The original `checks/host-contamination.md` marked 0 of 8 scenarios.
- Its rules flag a sample only when non-benchmark CPU exceeds the quiet c10dd3d reference p95 (3.69 cores) in two
  consecutive samples, or load1 exceeds that reference's per-scenario maximum.
- A sustained neighbour of about 1 core stays under those thresholds. Run 1's non-benchmark CPU was median 3.02 cores,
  p95 4.22; the reference is median 1.75.
- The rules were not designed to detect such a neighbour.
- The settle check and pre-check before the run also passed while the mutant run was active.

## Re-analysis with the disclosed window marked

`checks/host-contamination-reanalysis-2026-11-07.md` was produced on 2026-11-07, read-only on this run's own logs. It used
the same `commands/contamination.py` rules with the two windows as known external load:
- `--known 2026-11-06T22:28:42,2026-11-06T22:42:04,…`, rounded outward to whole seconds;
- `--known 2026-11-06T22:42:10,2026-11-06T22:42:45,…`.

Result, as printed there: **8 of 8 scenarios are marked NOT REPRESENTATIVE**, because every scenario overlaps the known
window. The tool marks overlap; it does not establish that the overlap caused any figure.

## Final disposition

- **Run 1 stays FAIL.** Its figures in this directory are unchanged.
- **The corrected rerun is final:** draft PR #155, `2026-11-06-ce925d7-M3-RERUN-corrected-setup-final-N10/`, 23:08:13–23:17:36Z,
  with a workload attestation.

References (private decision record, given as references only; nothing is quoted): infinity-core #12 notes 1590, 1591, 1592
and 1600.
