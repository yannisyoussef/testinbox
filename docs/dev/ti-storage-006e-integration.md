# TI-STORAGE-006E — integration plan (prepared, NOT executed)

Owner review b, §7. Nothing here has been done. No PR is merged, nothing is
deployed, staging enforcement is unchanged (`OFF`), storage is not rebuilt,
and production is untouched. Each step below needs the owner's go-ahead.

## The chain

| PR | Branch | Base today | Content |
|---|---|---|---|
| #82 | `feature/ti-storage-006e-containment-contract` | `develop` | ADR-035 Amendment 2 and its annex; `docs/security/storage-public-boundary-decisions.md`. Docs only. |
| #83 | `feature/ti-storage-006e-footprint-ledger` | `develop` | Footprint model, V8 ledger (object counts, debt, observations, trust), exact-aggregate rules. |
| #84 | `feature/ti-storage-006e-storage-full-breaker` | #83's branch | `STORAGE_FULL` breaker and the filesystem declarations. |
| #85 | `feature/ti-storage-006e-footprint-admission` | #84's branch | PR D: T1 footprint admission, the T2 fence, rule (P), pacing, V9; and V10 (verified trust, sweep runs, containment level). |
| #86 | `feature/ti-storage-006e-gate-f` | #85's branch | PR E: gate F with the `TENANT_LIMITS` preflight; the Ops handoff; this plan. |

#82 is independent of the code chain. Every code PR is a strict descendant of
the one before it: each was merged forward, never rebased.

## Sequence

1. **Accept the ADR.** The owner formally accepts ADR-035 Amendment 2 (#82)
   and decides whether the two §5 security items are deferred until before
   public `ALL` (as proposed). #82 is merged with a merge commit. Without this
   step, nothing below starts.
2. **Final review of the new safeguards.** These are the V10 trust boundary,
   the sweep-run record, the containment level, the gate F base case and the
   `TENANT_LIMITS` preflight. They get an independent architecture, security
   and data-integrity pass on #85 and #86 at their final heads, and every P0
   and P1 must be closed.
3. **Migration and grant checks**, on the final #86 head (which contains
   everything):
   - `scripts/check-migration-safety.sh`: V8, V9 and V10 are expand-only.
   - `scripts/check-backup-scope.sh`: every new table is classified, and
     `storage_sweep_run` is excluded.
   - `StorageV8GrantsTest` and `StorageVerifiedTrustTest`: the documented
     per-role grants suffice, and a forged trust mark is refused.
   - For every environment with separated roles (production), apply the
     grants of `docs/dev/production.md` **before** any artifact from #83 or
     later starts.
     - The API role needs `SELECT` on the V8 tables, `UPDATE
       (distrust_epoch)` on the trust row, and `EXECUTE` on
       `storage_confirm_footprint_trust()`, the debt functions and the two
       sweep functions.
     - The ingestion role needs the `SELECT`s and the two probe functions.
     - The monitor role needs its observation grants.
   - A missing grant fails closed: trust stays unmarked, and a sweep run is
     not recorded. It never stops cleanup.
4. **Merge in dependency order, each with a merge commit** (never squash,
   never rebase):
   1. Merge #83 into `develop`.
   2. Retarget #84 to `develop` (`gh pr edit 84 --base develop`), let CI run
      fresh on it, then merge it.
   3. Retarget #85 to `develop`, run fresh CI, merge.
   4. Retarget #86 to `develop`, run fresh CI, merge.

   GitHub retargets automatically only if a merged head branch is deleted. Do
   not delete it, so that #81's history and the review threads keep their
   links; retarget explicitly instead.
5. **Fresh CI on the resulting `develop`**, on the merge commit of #86. That
   means every required context: the backend (now 1 300+ tests), static
   analysis (with detekt and the activation self-test of 170+ cases), the
   OpenAPI check, acceptance, the staging rehearsal with the synthetic suite,
   and the image build.
6. **Containment rollback floor.** In a small follow-up PR to `develop`, add
   to `deploy/rollback-floors.txt` the merge commit of #85, with a rationale
   naming `TI-STORAGE-006E`. Gate F's mixed-versions row reads that line, and
   gate E then proves that every running artifact contains it.
   - This must land **before any deployment that could enforce footprint
     admission**, and before the first deployment of these artifacts to any
     environment that could later be switched to `ALL`.
   - Below the floor, an artifact deletes without rule (P), and V10 counts it
     as containment level 0.
7. **`OFF`-mode release qualification.** Build the images once from the
   `develop` head that carries the floor. Hand the digest set to Ops through
   the usual staging handoff, still with `TESTINBOX_STORAGE_ENFORCEMENT=OFF`.
   Then verify:
   - the migrator applies V8, V9 and V10;
   - readiness matches the bundled schema;
   - the synthetic suite passes;
   - `testinbox_storage_footprint_counts_trusted` turns 1 after the first
     reconciliation;
   - `storage_sweep_run` gains completed rows;
   - nodes register `containment = 1`;
   - under `OFF`, no SMTP or API behaviour changes. The only intended
     difference is the T2 inbox-state fence, which owner review b keeps
     (§6).
8. **Ops handoff.** The handoff names the exact application artifact
   identity: the source SHA and the four image digests with their
   provenance attestations. That is what `testinbox_build{git_sha}` reports
   and what gate E checks against the floor.
   - Ops then recreate the storage (preallocated, `-i 4096`, dedicated),
     run E1–E11, and re-issue the qualification record with the filesystem
     elements, the measured *O_max*, the E7 metadata inodes and the
     experiment results.
   - They deploy the monitor role, and run `check-storage-activation.sh`.
   - `TENANT_LIMITS` on dark staging requires gate F's isolation preflight to
     PASS. `ALL` requires every gate to PASS, and stays refused in
     production until decisions A and B are implemented.

## What would stop the sequence

- Any gate below that is not green at its step.
- A new P0 or P1 in step 2.
- A migration-safety or backup-scope failure.
- A grant set that the tests do not prove sufficient.

The sequence never forces a gate to PASS. A `BLOCKED` gate F on staging
(owner connection, no filesystem record) is the expected state, not a
failure of this plan.
