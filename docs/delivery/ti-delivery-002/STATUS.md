# TI-DELIVERY-002 — controlled-beta readiness checkpoint

Updated: 2026-10-30.

## Goal

Make the deployed native-account foundation (TI-DELIVERY-001) ready for a
controlled beta of at most 30 admitted accounts. This milestone does not merge
to master, deploy to production, open registration or admit anyone.

## Baseline (verified 2026-10-30)

- origin/develop `d17044dc28a2f9aefd1e2e3834dcc8d4930c5c81` = #114 + #115,
  released to production by #116: owner-only, `TESTINBOX_ACCOUNTS_ENABLED`
  off, `beta_cohort` `f|f`, storage enforcement OFF, schema V21. Production
  rollback target `408c3f3`.
- Website: GitLab `infinity/testinbox-web` develop/master `9d3bcd7`.
- Ops: infinity-core promoted d17044d. Ops recorded MD1/md1/md2 in
  `infra/testinbox/PRODUCTION.md` "Known limitations".
- ADR-036 and ADR-037 accepted for implementation; not reopened.

Gaps found: lifecycle states existed but nothing wrote them; no admission
pause; every visitor shared the web container's sign-in source budget (the
`/account` proxy strips forwarding headers); no account synthetics;
`production.md`/`abuse-model.md` still said "no self-service signup".

## Completed scope

| Area | Result | Detail |
|---|---|---|
| Lifecycle | suspend/resume/delete/status/erase-identity/restore-reconcile; permanent session + key revocation; T1 inactive discard with uniform SMTP 250 and DRAINING; deletion keeps tombstones; restore quarantine (V22); participant key caps 20/1000 | [LIFECYCLE.md](LIFECYCLE.md) |
| Admission | cap proven atomic across Google/GitHub/email under real PostgreSQL concurrency; `admission status\|pause\|resume --open-beta` | [ADMISSION-AND-SOURCE.md](ADMISSION-AND-SOURCE.md) |
| Sign-in safety | trusted client address: website derives the visitor by a fixed Cloudflare hop count and attests it with a shared secret; API trusts nothing else; IPv6 grouped by /64; invalid secret closes `/account` only | [ADMISSION-AND-SOURCE.md](ADMISSION-AND-SOURCE.md), website `docs/handoff.md` §6 |
| Readiness | each deployable is OUT_OF_SERVICE until it can read the V22 lifecycle objects (a missing grant never silently stalls mail) | LIFECYCLE.md |
| Storage | verified, not rebuilt; two engineering gaps remain (below) | [STORAGE-READINESS.md](STORAGE-READINESS.md) |
| Docs | production.md (accounts contract, operator commands, V22 deploy order, restore drill), abuse-model.md (self-service threat model), dated amendment notes on ADR-025/027/032/033/034/035, rollback floor | |
| Synthetics | `test:accounts` (state-declared, no seats, no mail); edge check that the API host never serves `/account` | deploy/synthetic/README.md |
| CI | `scripts/check-roles-reference.sh` keeps `roles.reference.sql` in the reviewed shape (MD1/md1 drift) | |
| Website copy | no invitation promised (none exists); FULL points to the waitlist; "30 accounts", not "30 places" | website !10 |

The limitation is stated everywhere it matters: 30 admitted accounts do not
guarantee 30 distinct people. No invitation policy was introduced.

## Review requests

- App: https://github.com/yannisyoussef/testinbox/pull/117 (draft),
  branch `feature/ti-delivery-002-beta-readiness`. Reviewed head: see the PR.
- Website: https://gitlab.yvnn.is/infinity/testinbox-web/-/merge_requests/10
  (draft), head `738b775`, pipeline 1520 green (verify, web:e2e).

## Verification (local, final tree)

- Backend build: 1,799 tests across 11 modules, zero failures/errors/skips,
  floors ratcheted (application 494, persistence 495, api 296, ingestion 150,
  domain 145, architecture 56). Java 21 Detekt and Spotless pass.
- Self-tests: roles guard 34/34, backup scope 205/205, storage activation
  173/173, migration safety 49/49 (22 migrations expand-only), result verifier
  14/14, rollback floors 22/22, OpenAPI breaking check 5/5, no breaking change
  vs develop. Synthetic unit tests 31/31. Staging rehearsal passed on the
  stream branch.
- Website: unit 133/133, typecheck, build, Playwright 43 passed / 4 skipped,
  accounts-closed spec 4/1, `npm audit` 0.
- gitleaks on the committed tree: no leaks.
- One local flake: `RateLimitApiTest` failed once (HTTP status -1) under
  parallel Docker load and never reproduced (alone, full suite, CI).

## Independent reviews

| Review | Verdict | Outcome |
|---|---|---|
| Security | APPROVE WITH FIXES | P1 `search_path` forgery of operator authority (reproduced) fixed + test; P2 `beta_admission` re-pointing fixed; P2 API-host `/account` edge synthetic added; P2 grant hardening listed for Ops |
| Correctness | no P0/P1 | P2 idempotent-mint/suspend deadlock fixed + deterministic test; P2 `--table` restore bypass removed + drill check; P2 grant-order readiness probe + deploy order; P3s fixed (reconcile race, exit code 4, bounded operator holds, cooldown config) |
| Ops | app delta safe and activation-neutral; staging GO; production conditional | Ops-side P1s below; doc corrections applied |
| Verification of fixes | ready for the release package | every fix verified (PoC re-run against amended V22); P3 follow-ups applied (roles-guard lexical gaps, owner-membership guard, bounded deletion transactions) |

V22 was amended in place. It exists only on this branch (never on develop,
staging or production).

## Required Ops changes, in dependency order

Before the **staging** deploy (merge to develop): nothing. Staging is bundled
(single owner role), V22 migrates and readiness passes. Staging does not
exercise the grant contract or the operator boundary.

Before the **production** deploy:

1. Owner decision on MD1/md1: fix them (inputs after the body, pinned
   `search_path` and qualified catalog calls in the contract, strip ingestion on
   a step-2 failure, `nspacl`/`datacl` in contract and fingerprint), or
   explicitly re-accept the exact-hash mitigation for this one change.
2. `infra/testinbox-data/ingestion-privileges.txt`: add
   `workspace_lifecycle_eligibility SELECT`. Contract hardening: pin the view's
   columns (`workspace_id` uuid, `active` boolean), `security_barrier`, owner;
   assert the V22 triggers exist and are enabled.
3. CI fixtures for V22 and the final reference (sha256 at the reviewed head;
   `696e1033…b277` at `db57bf0`).
4. Lab rehearsal V21→V22, grants, API first, synthetics; rollback to d17044d
   ingestion first. Add d17044d to `rollback-targets.txt`; re-rehearse or
   remove 408c3f3.
5. PRODUCTION.md window parameters, recovery table, N-1; T5 checks:
   clearance = 1, ingestion SELECT on the view = t, ingestion SELECT on the
   clearance = f, triggers present, `beta_cohort` = f|f.
6. Pre-window: `beta_admission`, `human_account`, `participant_workspace` = 0.
7. Window order: backup scope + checker → migrator (V22) → re-run
   `roles.reference.sql` (both roles; `ON ALL TABLES` is one-shot) → API →
   ingestion → web → synthetics → verified V22 backup.
8. RECOVERY.md V22 section and a restore drill ending with `restore-reconcile`
   (clearance must be 0 after restore, before any deployable starts).

Before the first lifecycle or admission command (in practice, before
registration):

9. Operator wrapper (`docker run --rm --network … --env-file <0600> <api digest>
   <subcommand>`) and the off-host lifecycle log (root 0600, off-host copy,
   retention ≥ oldest restorable backup, outside the DB backup).
10. After MD1/md1: revoke API `INSERT/UPDATE/DELETE` on
    `lifecycle_restore_clearance`, `UPDATE(state)` on `human_account`,
    `UPDATE/DELETE` on `beta_admission`; revoke `TEMPORARY` on the database
    from the runtime roles. *App side carried in `roles.reference.sql`
    (`UPDATE (id)` only on `human_account`); the `TEMPORARY` revocation is
    Ops-owned (`roles.sql.inc`).*

Before opening registration:

11. Shared client-address secret on API and site; site 0600 env_file
    (`TESTINBOX_ACCOUNTS_ENABLED`, `_PUBLIC_ORIGIN`, `TESTINBOX_API_URL`,
    `TESTINBOX_TRUSTED_PROXY_CIDRS` = Cloudflare ranges only,
    `TESTINBOX_TRUSTED_PROXY_HOPS=1`, the secret); `testinbox-cf-only` on the
    site router; verify `testinbox_account_client_address_total{outcome="trusted"}`
    on staging.
12. Update the T5 accounts-env check; keep account secrets out of the migrator
    and minio-bootstrap environments.
13. No OAuth callback query strings in any access log.
14. SPF/DKIM/DMARC alignment for the sending identity.
15. Wire `npm run test:accounts` (non-gating first) into
    `infra/testinbox/synthetic.sh`.
16. The storage prerequisites below.

## Configuration, migration, backup and semantic rollback

- New API env: `TESTINBOX_ACCOUNTS_CLIENT_ADDRESS_SECRET` (optional; unset =
  shared budget). New website env: `TESTINBOX_TRUSTED_PROXY_CIDRS`,
  `TESTINBOX_TRUSTED_PROXY_HOPS`, the same secret. Operator credentials:
  `TI_LIFECYCLE_*`, `TI_ADMISSION_*` (existing `TI_BUDGET_*`), schema owner or
  a member. Full contract: `docs/dev/production.md`.
- Migration: V22 only, expand-only. Grant delta: ingestion SELECT on
  `workspace_lifecycle_eligibility`.
- Backup: `lifecycle_restore_clearance` is `-` (never in a backup); full-DDL
  dumps with `--exclude-table-data` for `-` tables only (`--table` removed).
  A restore is quarantined until `restore-reconcile`, then the operator replays
  the off-host lifecycle log before re-enabling sign-in.
- Rollback: d17044d runs safely on V22 (it never writes account state). Once
  any account/participant exists there is no artifact rollback below this
  release, only hold-first (rollback.md, "Account lifecycle semantic floor").
  Record the floor commit in `deploy/rollback-floors.txt` before the first
  admission.

## Storage gaps that block production admissions

1. `DeploymentSafety` refuses `TESTINBOX_STORAGE_ENFORCEMENT=ALL` in production
   unconditionally; admission requires an ALL-mode pool. Lifting it is a
   reviewed change under the release decision (not made here).
2. No qualification record matches the binary's upload implementation
   `adr035-presigned-put-v2`; both shipped records are `v1`. Ops qualifies the
   production combination; a PR ships the record.

Then: approved pool (`storage-budget check|reserve`), declarations and legacy
inventory. Order: STORAGE-READINESS.md "Activation, in order".

## Acceptance steps

Staging (after the develop merge auto-deploys):
1. Readiness UP on API and ingestion (lifecycle privilege probe passes).
2. Deployment synthetics green, including the API-host `/account` edge check.
3. `npm run test:accounts` with `TESTINBOX_ACCOUNTS_EXPECTED` matching
   staging's declared state.
4. With accounts enabled on staging only: one sign-in per method; suspend that
   account (`account-lifecycle suspend`) → session and key refused, its inbox
   address answers like an unknown recipient, `status` reaches SETTLED; resume →
   old key still refused, new sign-in works; `admission pause` → CLOSED, existing
   users unaffected; `admission resume --open-beta`.
5. Client address: through Cloudflare, `outcome="trusted"` increases.

Production (after the Ops items, owner-only, accounts still off):
1. T5: V22, clearance = 1, grants as listed, `beta_cohort` f|f, readiness UP.
2. Owner mail flow and `/v1` synthetics green.
3. `test:accounts` with `TESTINBOX_ACCOUNTS_EXPECTED=disabled`.

## Bounded plan to enable the 30-account beta, and to stop it

Enable (only after the explicit production/registration authorization, with
someone watching):
1. Storage gaps closed; candidate promoted; Ops items 1–16 done.
2. Record the semantic rollback floor.
3. Switch storage enforcement to ALL (API first); reserve the pool offline;
   `capacity_ready` true.
4. Set the client-address secret on API and site, enable accounts on both,
   then `admission resume --open-beta`.
5. Watch: admissions count, `testinbox_account_client_address_total`,
   sign-in/email budget refusals, footprint refusals, waitlist.

Stop new admissions at any time, without disrupting admitted users:
`admission pause` (exit 0; existing participants, sessions, keys, inboxes, mail
and the waitlist continue). Stop one account: `account-lifecycle suspend <id>`
(wait for SETTLED). Stop all sign-in: `TESTINBOX_ACCOUNTS_ENABLED=false` on API
and site (redeploy; `/v1` unaffected).

## Owner inputs that cannot be established from existing evidence

1. MD1/md1: require the Ops fix first, or re-accept the exact-hash mitigation
   for this change.
2. Identity/contact retention after account deletion (today: blocked until an
   operator runs `erase-identity`; no automatic period).
3. Owner/monitoring keys revoked after a backup come back after a restore:
   accept manual re-revocation, or revoke all managed keys on reconcile
   (breaks synthetics until re-keyed).
4. Whether revoked key labels are personal data needing their own retention.
5. Acceptance of the sybil limitation (one person may hold several of the 30
   accounts), and the waitlist retention period (default 30 days).
6. The conditional lifting of the production ALL refusal (storage gap 1).

## Follow-ups (not blocking)

- Console "Members → Invite" placeholder suggests team invitations that do not
  exist (website).
- Edge synthetic P3s: add `/v1/..;/account/methods`; the `%2E%2E/session` probe
  cannot distinguish the boundary's 401 from `/v1`'s.
- Readiness inclusion of `lifecyclePrivileges` is not pinned by a
  configuration test; during a DB outage its reason reads as a privilege
  problem.
- md2 (ingestion `inbox UPDATE(id)`) remains Ops-accepted.

## Next action

Owner review of PR #117 and MR !10. Merging #117 to develop deploys to
staging (no Ops prerequisite). Production needs the Ops items above and the
explicit production/registration authorization.
