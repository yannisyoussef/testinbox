# Policy decisions (TI-DELIVERY-002b)

The owner decided the five policy questions TI-DELIVERY-002 left open
([LIFECYCLE.md](LIFECYCLE.md), [STATUS.md](STATUS.md) "Owner inputs"). This
document records what changed to implement them and the evidence. It
authorizes no deployment, no admission and no grant change on a live
database.

Base: `4f79b6e` (the reviewed head of PR #117). Branch:
`feature/ti-delivery-002b-policy`.

## 1. Deleted identity is erased by a reliable workflow

**Decision.** Erase the contact and login identity after content deletion
completes, as part of a reliable deletion workflow, not as an indefinite
manual follow-up.

**What changed.**

- **V23** adds `account_deletion` (account UUID, `stage`, `requested_at`,
  `stage_changed_at`, `erased_at`; no contact or login identity). It is
  written only by the operator authority: two triggers reuse V22's
  `lifecycle_require_operator()` for INSERT/UPDATE/DELETE/TRUNCATE. V23
  backfills a record for every account already `DELETION_PENDING`. It is
  classified `+` in `deploy/backup/scope.txt`, so it is restored together with
  `human_account`.
- `ChangeAccountState` opens the record (`DRAINING`) in the same transaction
  that moves the account to `DELETION_PENDING`.
- `ProcessAccountDeletion` replaces `RemoveAccountContent` and
  `EraseAccountIdentity`. It moves a deletion forward only, and every stage is
  re-entrant:
  `DRAINING` → `CONTENT_DELETING` → `ERASED`. The content gate is re-read
  under the lifecycle locks (workspace guard → `(35,1)` → account). It
  requires no reservation, no inbox row, and no EXACT reservation `ACTIVE` or
  still in cooldown. Then, in one transaction, erasure removes:
  - the account row, and by cascade the login identities, sessions, waitlist
    entry and request budget;
  - the sign-in codes sent to its contact address;
  - its workspace's key labels, idempotency records and EXACT reservations;
  - the membership's references.

  The spent place, the participant marker, every cleanup charge and the
  keys' technical rows are kept.
- The operator command gains `process-deletions`, which advances every
  pending deletion, and `status` without an account, which lists every
  pending deletion with its stage, age and overdue verdict. The new exit code
  `5` means overdue. `delete` now runs the workflow as far as it can at once.
  Re-running `delete` on an erased account reports `ERASED` with exit `0`.
  `erase-identity` is removed: it is refused with exit `2` and a pointer to
  the workflow. No override can erase while content remains.
- **Overdue threshold:** the EXACT cooldown, plus the paced-retention `T_max`,
  plus 24 h, which is 72 h with shipped values. It is read from the same
  `TESTINBOX_EXACT_COOLDOWN` and `TESTINBOX_STORAGE_FS_RETENTION_PACING_MAX_DELAY`
  the deployment uses, and `TI_LIFECYCLE_DELETION_OVERDUE_AFTER` overrides it.
- A `DELETION_PENDING` account with no record is reported as overdue by
  `status` and adopted by the timer. That covers restored, pre-V23 or
  hand-edited data. A pending record whose account row is already gone is
  closed as `ERASED`, with `anomaly=ACCOUNT_ROW_ABSENT`.
- **Backups.** Erasure does not reach a backup taken before it. Such a
  backup keeps the identity until it ages out under the backup retention Ops
  operates. Nothing promises immediate erasure from backups. A restore replay
  erases the account again.

**Not reached by erasure, by design:**

- `provider_login_state` is keyed by browser, not account. It lasts at most
  10 minutes and is purged by the existing lifecycle.
- Row-free object residue is deleted by the orphan sweep. It carries no
  contact identity.
- Application logs and the Ops lifecycle log keep their own retention.
- The account UUID stays in `account_deletion`. It is the same surrogate the
  Ops lifecycle log keeps forever, and it is what makes `ERASED` observable
  and the restore replay idempotent. The membership tombstone still drops it,
  as before.

## 2. Restored credentials are never trusted

**Decision.** `restore-reconcile` revokes all restored managed API keys,
legacy owner and monitoring keys included, before access reopens. Ops issues
replacement credentials and re-keys the synthetics. Bootstrap and configured
credentials are audited separately.

**What changed.**

- `ReconcileRestoredDatabase` still revokes the keys of every participant
  workspace. It now also revokes every other still-usable managed key, in
  every workspace (`legacyRevokedKeys=` in its output), before it records
  the clearance.
- **Access does not reopen before that.** `AuthenticateApiKey` reads the
  restore clearance per request (`RestoreQuarantine`, `JdbcRestoreQuarantine`)
  and refuses every managed key of a legacy workspace while the database is
  quarantined. Participant keys were already refused. The answer is `401`,
  counted as `WORKSPACE_INACTIVE`, with no new metric series, and the secret is
  still verified first, so the refusal is no oracle. Without this, an owner
  key revoked after the backup would have worked between the restore and
  the reconciliation.
- The bootstrap credential is not revoked or rotated by reconciliation. It
  authenticates only against the value the API is currently configured with,
  and only while no usable managed administrator exists. After
  reconciliation it is therefore the break-glass that re-keys the owner. The
  restore procedure now requires confirming it is configured before
  reconciling. The full audit of configured credentials is in
  [production.md](../../dev/production.md#credentials-after-a-restore).
- **Staging consequence:** a staging database that V22 started quarantined
  refuses its synthetic keys until reconciled, and reconciliation revokes
  them. Ops re-keys staging through its bootstrap credential.

## 3. Revoked key labels are anonymized on erasure

**Decision.** Free-text key names are potentially identifying. Erasure
removes them and keeps the technical audit records and the inventory
accounting.

**What changed.** Erasure sets `api_key.name` to null for every key of the
workspace. `created_by_human_account_id` already becomes null through the
foreign key. The workspace's `idempotency_record` rows are deleted: their
projections can carry key names and addresses, and they are transient
anyway. The key rows themselves keep their id, public id, verifier, scopes,
timestamps, `last_used_at`, revocation and creating key, as ADR-032 §5 audit
history. The 20-usable / 1000-retained inventory still counts them. No other
free text exists on these rows: workspace and project names are the fixed
`My workspace` / `Default`, and there is no key description or created-by
label column. The contract is unchanged: `ApiKey.name` was already optional.

## 4. Admission: first come, thirty places, accounts not people

**Decision.** Keep the first-come cap of 30. One person holding several
accounts is an acknowledged beta limitation. Behaviour is unchanged.

**What changed.** Docs only. `production.md` (the design summary) and
`abuse-model.md` ("Sybil limitation (accepted)") now say it explicitly, in
agreement with `ADMISSION-AND-SOURCE.md` and `STATUS.md`.

## 5. Waitlist: 30-day retention, verified

**Decision.** Keep the configured 30-day retention, and verify that expiry
actually works end to end.

**Finding.** It works; nothing needed fixing. `expires_at` is fixed at join.
The purge (`expires_at <= now`, plus admitted entries) runs from two places:
the `@Scheduled(fixedDelay = 60 s)` `BetaWaitlistMaintenance` on every API
node, and the start of every `/account/waitlist` request.

**Evidence added.**

- `BetaWaitlistTest` uses the 30-day policy on real PostgreSQL. A 31-day
  entry is removed and a 29-day entry kept. The boundary falls at exactly 30
  days. A read by another applicant also purges.
- `BetaWaitlistExpiryApiTest` runs on the full Spring context. The shipped
  configuration binds 30 days, the purge is registered with the scheduler
  with a one-minute delay, running it removes the old entry and keeps the
  young one, and an HTTP `GET /account/waitlist` purges.

## Migrations and grants

- **V23** is the only migration. It is expand-only: one table, one partial
  index, two triggers, and a backfill INSERT. `check-migration-safety.sh`
  passes. Rollback: [rollback.md](../../dev/rollback.md#deletion-workflow-and-restore-credentials-ti-delivery-002b-schema-v23).
- **Grants: no change to `roles.reference.sql`.** The runtime API does not use
  `account_deletion`. The next routine re-run of `roles.reference.sql`
  extends the API's one-shot `ON ALL TABLES` DML to it, and the V23 triggers
  refuse those writes, as for the V22 tables. Ingestion and monitor need
  nothing. Restore-quarantine authentication reads `lifecycle_restore_clearance`,
  which the API already reads, and readiness `lifecyclePrivileges` already
  probes it.
- **Optional Ops hardening,** in the same deferred MD1/md1 grant-hardening
  batch as V22: revoke `INSERT, UPDATE, DELETE ON account_deletion` from the
  API role.

## Ops actions

1. Install the **`process-deletions` timer** (every 15 minutes), append its
   output to the lifecycle log, and alert on any non-zero exit
   ([LIFECYCLE.md](LIFECYCLE.md#the-ops-timer-for-process-deletions-required)).
   Pin it to the deployed API digest.
2. Put `TESTINBOX_EXACT_COOLDOWN` and
   `TESTINBOX_STORAGE_FS_RETENTION_PACING_MAX_DELAY` in the lifecycle env
   file whenever the API overrides them.
3. Update the restore runbook:
   - step 0: confirm the bootstrap credential is configured;
   - step 2: no managed key works while quarantined;
   - step 5: re-key the owner administrator, the synthetics and monitoring;
   - the credential audit table.
4. Replace `erase-identity` in any runbook with `delete` / `process-deletions`.
5. On staging, if V22 left it quarantined: reconcile, then re-key through the
   staging bootstrap credential.
6. Backup retention: the period is still the open Ops/owner decision in
   production.md. It now also bounds how long an erased identity survives in
   backups.

## Evidence

Local, on the final tree (macOS, Docker, Java 25; Detekt on Java 21):

| Suite | Tests |
|---|---|
| domain `AccountDeletionPolicyTest` | 3 (new) |
| application `AccountLifecycleOperationsTest` | 19: the workflow stages and lock order, refusal while content or an EXACT cooldown remains, a crash inside erasure then resume, adoption of an unrecorded deletion, a row-absent record, the overdue report, legacy revocation in reconciliation |
| application `AuthenticateApiKeyTest` | +1: no managed key while quarantined, verified first, read per request |
| persistence `AccountDeletionWorkflowTest` | 6 (new, real PostgreSQL): see below |
| persistence `AccountLifecycleTest` | 13. The old deletion test moved to the new suite; reconciliation reports the legacy count |
| persistence `BetaWaitlistTest` | +1: 30-day expiry on the timer and request paths |
| api `AccountDeletionCommandTest` | 2 (new, real PostgreSQL): see below |
| api `AccountLifecycleCommandInputTest` | +3: `erase-identity` refused, the new commands parse, overdue threshold derivation and refusal |
| api `BetaWaitlistExpiryApiTest` | 3 (new) |

`AccountDeletionWorkflowTest` covers:

- an admitted account with inboxes (generated and EXACT), messages, named
  keys, sessions, a login identity, a waitlist entry, a request budget,
  sign-in codes, an idempotency record, a released reservation and a cleanup
  charge, taken through `DRAINING` → `CONTENT_DELETING` → (held by the EXACT
  cooldown) → `ERASED`, asserting every erased and retained item;
- a crash after every erasure statement, rolled back, then completed by the
  next run;
- a crash between inbox batches, then resumed, with erasure refused while
  any inbox row remains;
- adoption of an unrecorded deletion, and overdue reporting at 72 h;
- runtime-role writes to `account_deletion` refused;
- `restore-reconcile` revoking legacy keys, with no managed key
  authenticating while quarantined.

`AccountDeletionCommandTest` covers:

- `delete` → `ERASED` in one run, then an idempotent re-run and `status` of
  an erased account;
- `process-deletions` and `status` exiting `5` while a deletion is overdue,
  and `0` once the timer completes it.

Gates:

| Gate | Result |
|---|---|
| `./gradlew spotlessApply build` | BUILD SUCCESSFUL |
| `VERIFY_SCOPE=backend scripts/verify-test-results.sh` | 1821 tests, 0 skipped. Floors raised to actual: domain 148, application 499, persistence 501, api 304 |
| `./gradlew detekt` (Java 21) | clean |
| `check-migration-safety.sh` and `.test.sh` | pass |
| `check-backup-scope.sh --classification` and `check-backup-scope.test.sh` | pass, 206/206 (was 205; the fixtures now carry `account_deletion` as a `+` table, and the genuine dump seeds a row) |
| `check-roles-reference.sh` and `.test.sh` | pass, 34/34 (file unchanged) |
| `check-storage-activation.test.sh` 173/173, `verify-test-results.test.sh` 14/14, `openapi-breaking-check.test.sh` 5/5 | pass |
| `gitleaks dir .` | no leaks |

The OpenAPI contract is unchanged. The `401` for a refused key was already
documented, and the key `name` was already optional.

## Remaining

- The backup retention period is still open (production.md, "HUMAN DECISION
  REQUIRED"). It now also bounds how long an erased identity survives in
  backups.
- Ops-side implementation of the timer, the alert and the re-keying runbook
  (above) is not in this repository.
- Erasure removes nothing from application logs or the Ops lifecycle log,
  which keep account UUIDs (never contact data). Key names are no longer
  logged at all: the `api_key.created` audit line records only `named=true|false`
  (commit "never log a key's free-text name"); lines shipped by earlier releases
  keep their names until Ops' log retention expires them.
