# TI-DELIVERY-002 — controlled-beta readiness checkpoint

Updated: 2026-10-30.

## Goal

Make the deployed native-account foundation (TI-DELIVERY-001) ready for a
controlled beta of at most 30 admitted accounts: account lifecycle and
revocation, admission controls, sign-in safety and configuration, storage
readiness, tests, synthetics and one activation package. This milestone does
not merge to master, deploy to production, open registration or admit anyone.

## Baseline (verified 2026-10-30)

- origin/develop `d17044dc28a2f9aefd1e2e3834dcc8d4930c5c81` = #114 + #115.
  #116 released it to production (owner-only; `TESTINBOX_ACCOUNTS_ENABLED`
  off; `beta_cohort` `admissions_open = capacity_ready = false`; storage
  enforcement OFF). Production rollback target `408c3f3`, schema stays V21.
- Website: GitLab `infinity/testinbox-web` master `9d3bcd7`.
- Ops: infinity-core `release/testinbox-prod-d17044dc28a2` promoted d17044d.
  Ops recorded grant-contract weaknesses MD1/md1/md2
  (`infra/testinbox/PRODUCTION.md`, "Known limitations"): MD1 and md1 must be
  fixed before ANY later change to `roles.reference.sql`, `grants-assert.sql`
  or `apply-app-grants.sh`.
- ADR-036 and ADR-037 are accepted for implementation; not reopened.

## Gaps found at baseline

1. Lifecycle (M3j, `ti-delivery-001/ACCOUNT-LIFECYCLE.md`): the
   SUSPENDED/DELETION_PENDING states exist in schema and domain but nothing
   writes them; no operator command, no API-key/ingestion effect, no deletion,
   no restore quarantine.
2. Admission pause: only `beta_cohort.admissions_open` exists; no documented
   operator control.
3. Client address: the web `/account` proxy strips all forwarding headers (by
   design), so the API sees the web container as the source of every sign-in.
   All visitors share one source budget (15 per 15 minutes).
4. Synthetics do not cover `/account`.
5. `docs/dev/production.md` and `docs/security/abuse-model.md` still describe
   "no self-service signup".

## Work streams

| Stream | Scope | Branch |
|---|---|---|
| L | lifecycle, revocation, ingestion eligibility, deletion, restore | `ti-delivery-002/lifecycle` |
| A | admission pause, cap concurrency, trusted client address (API) | `ti-delivery-002/admission-source` |
| W | website: trusted client address to the API | testinbox-web `feature/ti-delivery-002-client-address` |
| D | synthetics, production/abuse docs, storage readiness verification | `ti-delivery-002/docs-synthetics` |

Integration branch: `feature/ti-delivery-002-beta-readiness`.

## Next action

Run the streams, integrate, independent review, PR.
