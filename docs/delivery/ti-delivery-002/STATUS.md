# TI-DELIVERY-002 / 002b — controlled-beta readiness checkpoint

Updated: 2026-10-30 (TI-DELIVERY-002b).

## Goal

Make the deployed native-account foundation (TI-DELIVERY-001) ready for a
controlled beta of at most 30 admitted accounts, apply the owner's
TI-DELIVERY-002b policy decisions, and make production storage eligibility
conditional on real evidence. No master promotion, production deployment,
enforcement activation or registration opening is authorized by this work.

## Where things are (resume here)

| Item | State | Head |
|---|---|---|
| App PR #117 `feature/ti-delivery-002-beta-readiness` → develop | reviewed, CI green, **draft, not merged** | `4f79b6e` |
| App PR #120 `feature/ti-delivery-002b` → stacked on #117 | reviewed, verified; see PR for CI on the final head | see PR |
| Website MR !10 `feature/ti-delivery-002-client-address` → develop | reviewed, pipeline 1520 green, **draft, not merged** | `738b775` |
| develop | unchanged | `d17044d` |
| production | d17044d, owner-only, V21, storage OFF, accounts off | — |

**Blocked step.** TI-DELIVERY-002b §1 authorizes undrafting and merging #117
into develop. The attempt was refused by the session's permission policy
("merge without review"); nothing changed. The owner (or a session with that
permission) must perform: `gh pr ready 117 && gh pr merge 117 --merge
--match-head-commit 4f79b6e` — only if the head and checks are unchanged.

**Resumption order after #117 merges:**

1. Ops S1 first: on staging `SELECT count(*) FROM beta_admission` should be 0.
   If not, V22 starts staging quarantined: Ops runs `restore-reconcile` with
   the staging owner credentials and re-keys the staging synthetic key through
   the bootstrap key.
2. Watch build-images on the develop merge commit and the GitLab staging
   handoff; verify staging: schema V22 (V23 after #120), clearance = 1,
   readiness UP on API and ingestion, deployment synthetics green.
3. Then undraft and merge !10 (website) to develop; verify the combined
   staging deployment (site + API), registration still closed
   (`test:accounts` with `TESTINBOX_ACCOUNTS_EXPECTED=disabled`).
4. Retarget #120 to develop; re-run CI on its resulting head; merge with the
   same conditions; re-verify staging (V23, `account_deletion` = 0).
5. Staged acceptance (below) with Ops, using staging-only enablement.

## Baseline (verified 2026-10-30)

- origin/develop `d17044d` = #114 + #115, released to production by #116.
- Website `infinity/testinbox-web` develop/master `9d3bcd7`.
- Ops recorded MD1/md1/md2 (infinity-core `infra/testinbox/PRODUCTION.md`).
- ADR-036 and ADR-037 accepted for implementation; not reopened.

## Completed scope

| Area | Result | Detail |
|---|---|---|
| Lifecycle (#117) | suspend/resume/delete/status/restore-reconcile; permanent session + key revocation; T1 inactive discard with uniform SMTP 250 and DRAINING; restore quarantine (V22); participant key caps 20/1000; readiness probe for lifecycle privileges | [LIFECYCLE.md](LIFECYCLE.md) |
| Admission (#117) | cap proven atomic across Google/GitHub/email; `admission status\|pause\|resume --open-beta` | [ADMISSION-AND-SOURCE.md](ADMISSION-AND-SOURCE.md) |
| Sign-in safety (#117, !10) | trusted client address: fixed Cloudflare hop count on the site, shared-secret attestation to the API, IPv6 /64, fallback never spoofable | ADMISSION-AND-SOURCE.md, website `docs/handoff.md` §6 |
| Policy decisions (#120) | resumable deletion workflow (V23) ending in automatic identity erasure; restore revokes all managed keys; managed keys refused while quarantined; labels erased and never logged; waitlist 30-day expiry proven | [POLICY-002b.md](POLICY-002b.md) |
| Storage (#120) | production ALL only under `ProductionAllConditions` (no override; TENANT_LIMITS refused); real-path acceptance automated; gate G deferred under ALL and bound to the reviewed pool file | [STORAGE-READINESS.md](STORAGE-READINESS.md) |
| Docs | production.md, abuse-model.md, dated ADR amendment notes (ADR-025/027/032/033/034/035, storage-public-boundary-decisions), rollback floors | |
| Synthetics | `test:accounts` (state-declared, no seats, no mail); API-host `/account` edge check; storage mode asserted from the declared setting | deploy/synthetic/README.md |
| Ops tooling | `scripts/check-roles-reference.sh` (MD1/md1 drift); `scripts/check-lifecycle-image.sh` (operator image preflight) | |

## Owner decisions applied (TI-DELIVERY-002b)

1. Deleted identity: erased automatically by `account-lifecycle
   process-deletions` (Ops timer) once content deletion is verifiably complete;
   seat, participant marker, cleanup charges and key technical rows retained;
   backups keep erased identities until they age out.
2. Restore credentials: `restore-reconcile` revokes every managed key incl.
   legacy owner/synthetic; bootstrap rotation is step 0 of every restore;
   bootstrap/configured credentials audited separately (production.md
   "Credentials after a restore").
3. Key labels: erased with the identity; audit lines record `named=true|false`.
4. Admission: first-come cap of 30 accounts; not 30 distinct people.
5. Waitlist: 30-day retention, expiry proven on scheduler and request paths.
6. Production ALL: permitted only when the existing qualification, capacity,
   node, observation and cohort-reservation requirements are satisfied (dated
   amendment notes cite the instruction).

## Verification

- #117 (`4f79b6e`): CI green (15 pass, 2 skipped release jobs); 1,799 backend
  tests locally.
- #120 (local final tree): full build passed, 1,872 backend tests, floors
  ratcheted; Detekt, Spotless, gitleaks clean; self-tests: storage activation
  198/198, lifecycle image preflight 10/10, enforcement-off 20/20, migration
  safety 49/49, backup scope 206/206, roles guard 34/34, result verifier 14/14,
  OpenAPI 5/5; synthetics 36/36. CI on `d6b53c9` green after one re-run.
- !10 (`738b775`): pipeline green; unit 133/133, Playwright 43 passed / 4 skipped.
- Known flakes (not in changed code; follow-up): `WaitForMessageTest` #6 once
  on CI (two waiters not parked within 5 s); `RateLimitApiTest`,
  `IdempotencyApiTest`, ingestion auth once each locally under parallel load;
  the guarded SMTP suites' probe-then-bind port race (now a 30 s failure, no
  longer a hang).

## Independent reviews

| Change | Reviews | Outcome |
|---|---|---|
| #117 | security (APPROVE WITH FIXES), correctness (no P0/P1), Ops (staging GO, production conditional), verification | all app P1/P2 fixed and verified |
| #120 | security+correctness (no P0; P1 governance citation), Ops (no P0; P1 operator image floor, restore re-key alignment), verification (no P0/P1; P2 preflight) | all app findings fixed; P2/P3 verification fixes applied |
| !10 | security and Ops reviews of the client-address chain | Ops doc corrections applied |

## Required Ops changes, in dependency order

Before the staging merges: **S1** (above). Recommended: try the operator
wrapper and the deletions timer on staging first.

Before the production deploy:

1. MD1/md1 owner decision (fix, or re-accept the exact-hash mitigation).
2. `ingestion-privileges.txt`: `workspace_lifecycle_eligibility SELECT`
   (V23 adds nothing for ingestion). Contract: pin the view's columns,
   `security_barrier`, owner; assert V22 and V23 triggers enabled; require API
   SELECT on `lifecycle_restore_clearance` and the view.
3. CI fixtures migrated through V23; the reference sha is unchanged since #117.
4. Lab rehearsal V21→V23 (grants, API first, synthetics) and rollback to
   d17044d ingestion-first on V23; add d17044d to `rollback-targets.txt`;
   decide on 408c3f3.
5. PRODUCTION.md: window parameters; T5 (max version 23, clearance = 1,
   `account_deletion` = 0, V22+V23 triggers, ingestion view grant t,
   ingestion clearance grant f, `beta_cohort` f|f); recovery row "V22 without
   V23 → roll forward only"; new backup scope includes `account_deletion`.
6. Pre-window zero checks: `beta_admission`, `human_account`,
   `participant_workspace`.
7. Window: scope + checker → migrator (V22, V23) → re-run
   `roles.reference.sql` (both roles) → API → ingestion → web → synthetics →
   verified backup.
8. RECOVERY.md V22/V23 section and a restore drill (bootstrap rotation first,
   clearance 0 after restore, legacy key refused, `legacyRevokedKeys=`,
   re-key per `mint-synthetic-key.sh`, synthetics).
9. `testinbox_restore_quarantined` gauge alert.

Before the first lifecycle or admission command:

10. Operator wrapper: operator image pinned independently of the API with a
    floor, preflight `scripts/check-lifecycle-image.sh`, allow-listed env file,
    0600 files, off-host lifecycle log.
11. `account-lifecycle process-deletions` timer every 15 min: textfile exit /
    last-run metrics, alert on any non-zero exit and >1 h staleness, stopped
    during windows and restores, installed after the V23 window's T5.
12. After MD1/md1: revoke API writes on `lifecycle_restore_clearance`,
    `human_account(state)`, `beta_admission`, ALL on `account_deletion`;
    revoke `TEMPORARY`.

Before registration:

13. Client-address secret on API and site; site 0600 env_file with the account
    variables, `TESTINBOX_TRUSTED_PROXY_CIDRS` (Cloudflare ranges only),
    `TESTINBOX_TRUSTED_PROXY_HOPS=1`; `testinbox-cf-only` on the site router;
    verify `testinbox_account_client_address_total{outcome="trusted"}` on staging.
14. T5 accounts-env check; account secrets out of the migrator and
    minio-bootstrap environments.
15. No OAuth callback query strings in access logs.
16. SPF/DKIM/DMARC for the sending identity.
17. `npm run test:accounts` wired into `synthetic.sh` (non-gating first).

Before production ALL:

18. Build `qualification-check`.
19. §9a sweep incl. slow-W on the production combination; E1–E11 on the final
    production filesystem (own DB, no reformat afterwards), evidence committed.
20. Record PR (`platformClass` `production-host`, upload v2), co-reviewed by Ops.
21. Declarations on both deployables (backend identity, `FS_*`, node id, O_max ≥
    the record's).
22. Gate Q and gate F (`--environment production`) against the new record.
23. `synthetic.sh` exports `TESTINBOX_STORAGE_ENFORCEMENT_EXPECTED`.
24. Activation: activation script (gate G DEFERRED) → record the floor → ALL on
    API then ingestion → `storage-budget reserve` → `check` → activation script
    with gate G PASS bound to the pool file → `capacity_ready`.

## Configuration, migration, backup and semantic rollback

- API env: `TESTINBOX_ACCOUNTS_CLIENT_ADDRESS_SECRET` (optional). Website env:
  `TESTINBOX_TRUSTED_PROXY_CIDRS`, `TESTINBOX_TRUSTED_PROXY_HOPS`, the same
  secret. Operator env: `TI_LIFECYCLE_*`, `TI_ADMISSION_*`, `TI_BUDGET_*`
  (schema owner or a member), `TESTINBOX_EXACT_COOLDOWN`,
  `TESTINBOX_STORAGE_FS_RETENTION_PACING_MAX_DELAY`,
  `TI_LIFECYCLE_DELETION_OVERDUE_AFTER`. Full contract: `docs/dev/production.md`.
- Migrations: V22 (lifecycle, restore quarantine) and V23 (`account_deletion`),
  both expand-only. Grant delta: ingestion SELECT on the eligibility view.
- Backup: `lifecycle_restore_clearance` `-`; `account_deletion` `+`; full-DDL
  dumps with `--exclude-table-data` for `-` tables only.
- Rollback: d17044d runs on V22/V23 (never writes account state); once any
  account/participant exists, hold-first only. Operator commands only from an
  image at or above the #120 floor. Once ALL is set, pre-002b artifacts refuse
  to start. Record the floor commit in `deploy/rollback-floors.txt` before the
  first admission.

## Remaining genuine blockers

- Merge of #117 (permission), then staging verification and !10/#120 merges.
- Ops items above, in particular MD1/md1, the grant contract, the V21→V23
  rehearsal and rollback target.
- The Ops-evidenced `production-host` upload-v2 qualification record
  (`qualification-check`, E1–E11 on the final filesystem); without it
  production ALL is refused and admission cannot open.
- Staging sign-in with real Google/GitHub/email integrations needs Ops to
  configure the staging provider credentials and sender.
- Owner: backup retention period (now also bounds how long an erased identity
  survives in backups); retention for never-admitted accounts whose waitlist
  entry expired (they keep a contact email); MD1/md1 decision.

## Staged acceptance (staging, with Ops)

1. Readiness UP; synthetics green incl. the API-host `/account` edge check;
   `test:accounts` matching the declared state.
2. Staging-only enablement (accounts on, admissions via
   `admission resume --open-beta`), then: one sign-in per method with the real
   integrations; distinct visitor budgets through Cloudflare → Traefik → site
   → API (`outcome="trusted"`), forged `X-Forwarded-For` / `X-TestInbox-*`
   ignored; concurrent admissions never exceed the cap; `admission pause` →
   CLOSED for newcomers, existing users unaffected.
3. `account-lifecycle suspend` → session and key refused, address answers like
   an unknown recipient, `status` SETTLED; `resume` → old key still refused;
   `delete` → `process-deletions` reaches ERASED; seat stays spent.
4. Restore drill on a staging copy: quarantine, all managed keys refused,
   `restore-reconcile`, re-key, synthetics.
5. Close again: accounts off, `admission pause`.

## Plan to open the 30-account beta and pause new admissions

Open (only after the final consolidated authorization):
1. Blockers above cleared; candidate promoted; Ops items 1–24 done.
2. Record the semantic rollback floor.
3. Storage: ALL (API first) → reserve pool → gate G PASS → `capacity_ready`.
4. Client-address secret on API and site; enable accounts on both; verify
   `test:accounts` (enabled); then `admission resume --open-beta`.
5. Watch admissions, client-address outcomes, budget refusals, footprint
   refusals, `testinbox_restore_quarantined`, deletions timer.

Pause new admissions without disrupting participants: `admission pause`
(sessions, keys, inboxes, mail and the waitlist continue). One account:
`account-lifecycle suspend <id>`. All sign-in: `TESTINBOX_ACCOUNTS_ENABLED=false`
on API and site (redeploy; `/v1` unaffected).

## Follow-ups (not blocking)

- The flakes listed under Verification.
- Website console "Members → Invite" placeholder (no team invitations exist).
- Edge synthetic: add `/v1/..;/account/methods`; the `%2E%2E/session` probe
  cannot distinguish the boundary's 401 from `/v1`'s.
- Pin the readiness inclusion of `lifecyclePrivileges` in a configuration test.
- md2 remains Ops-accepted.
