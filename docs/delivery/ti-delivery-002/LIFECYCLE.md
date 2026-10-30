# Account lifecycle and revocation (stream L)

Authority: ADR-036 (accepted), ADR-037 (accepted), and the recorded M3j design
[ACCOUNT-LIFECYCLE.md](../ti-delivery-001/ACCOUNT-LIFECYCLE.md). This is
implementation and an operator procedure. It authorizes no deployment, no
participant admission and no grant change on a live database.

## What is implemented

### Account states and the one writer

`ChangeAccountState` is the only writer of `human_account.state`
(`ACTIVE`, `SUSPENDED`, `DELETION_PENDING`). The rules live in the domain
(`AccountLifecycle`):

| From \ command | suspend | resume | delete |
|---|---|---|---|
| ACTIVE | SUSPENDED | unchanged | DELETION_PENDING |
| SUSPENDED | unchanged | ACTIVE | DELETION_PENDING |
| DELETION_PENDING | refused | refused | unchanged |

Repeating a command changes nothing. You can re-run it to check draining.
Every non-active state does the following:

- It permanently deletes every browser session of the account. The existing
  `RevokeAccountSessions` revoker does this.
- It permanently revokes every managed key of the account's workspace. This
  uses the existing `RevokeApiKey`, extended with
  `revokeWorkspaceForLifecycle`. That method needs an `OperatorAuthorization`
  that is valid only inside the transaction that verified it.

`resume` changes the state only. It does not bring back revoked sessions or
keys. A resumed participant signs in again and issues new keys. Neither state
releases the spent place, the participant marker or any storage charge.

Lock order, in one transaction:

1. the existing workspace admission guard (`pg_advisory_xact_lock(bigint)`);
2. the storage T1 fence `(35,1)`;
3. the account row (`FOR UPDATE`).

If the account is admitted between the unlocked membership read and the
locks, the command rolls back and retries under that workspace's guard.
Authentication and read paths take none of these locks. Admission keeps its
own order (cohort row, then `(35,1)`, then account), so the two orders never
oppose.

### Operator authority

V22 adds `lifecycle_operator_authorized()`. It returns true when the session
role is the owner of `human_account`, or a member of that owner. Two places
check it:

- **The use case.** Every lifecycle transaction checks it first. Without it,
  the command throws `OperatorAuthorityRequiredException` and changes
  nothing.
- **Row triggers.** Triggers on `human_account` (`UPDATE OF state`,
  `DELETE`) and on `lifecycle_restore_clearance` (every write, including
  `TRUNCATE`) refuse any other role. The runtime API role still holds plain
  DML on those tables, so the triggers are what stop it from changing state,
  erasing an account or lifting quarantine. Sign-in never updates `state` or
  deletes an account, so the triggers refuse nothing the runtime does.

There is no HTTP endpoint for any of this. No API key or request field
counts as operator authority, and no human principal is faked.

### Ingestion eligibility, fencing and draining

`workspace_lifecycle_eligibility` (V22) is a security-barrier view with
exactly two columns, `workspace_id` and `active`. It has one row per
participant marker, and `active` is true only when all of these hold:

- the membership exists;
- the account is `ACTIVE` and verified;
- the database carries a restore clearance.

A workspace with a marker but no membership or account is inactive, never
legacy. A workspace without a marker (owner or monitoring) has no row and is
unaffected.

- **Early filter.** Recipient resolution (`ReceiveInboundDelivery`) checks
  eligibility before the rate limiter. An inactive participant's copy is
  discarded with the uniform reply and spends no ingest budget. The
  recipient still has an envelope position, so it keeps its global prefix
  price (ADR-037 A1).
- **T1 race.** T1 reads eligibility in its one snapshot statement, under the
  same `(35,1)` fence the operator takes. A copy prepared while the account
  was active but decided after the suspension commits becomes
  `StorageAdmissionDecision.Inactive`. That is a distinct outcome:
  - no reservation, no upload and no `inbox_storage` refusal record;
  - not reported as a duplicate;
  - counted as `outcome=inactive_discarded` and
    `testinbox_smtp_inactive_recipient_discard_total`.

  The SMTP transcript is byte-identical to an unknown recipient's.
- **Draining.** Copies reserved before the fence may finish their existing
  fenced operation. The command reports `phase=DRAINING` (exit 3) while any
  `storage_reservation` of the workspace remains. It reports `SETTLED` only
  when none remain. T1 refuses new inactive copies, so this count can only
  fall.

### HTTP effect

The contract is unchanged; `401` was already documented. A suspended,
deletion-pending or erased account gets these answers:

| Request | Answer |
|---|---|
| Any of the account's sessions | `401 unauthorized` |
| Any of its keys (revoked, and also refused per request by `AuthenticateApiKey` with `AuthOutcome.WORKSPACE_INACTIVE`) | `401 unauthorized` |
| A request that authenticated just before the suspension and then reaches inbox creation or key minting under the workspace guard | `401 unauthorized` |
| A new login (session issuance refuses an ineligible account) | refused |
| Admission (`Ineligible`) | refused |

An already-open wait or event-stream window is not cut off; it ends at its
existing bounded window (the wait cap is 60 s). The next request is refused.

Session policy is unchanged:

- Signing into another account revokes nothing.
- Logout revokes exactly the presented session.
- Suspension revokes all sessions of that one account.

### Participant key inventory

This is implemented, contract first. Minting a key now takes the workspace
guard and rechecks eligibility. A participant workspace holds at most:

- **20 usable managed keys.** A revocation or an expiry frees one.
- **1000 managed key rows in all**, covering keys minted by a human and by a
  machine key. These rows are kept as history and are never freed.

A key request beyond either bound gets `409 quota-exceeded` with:

- `quota` set to `API_KEYS_USABLE` or `API_KEYS_RETAINED`;
- `limit` and `current` for the caller's own workspace only;
- no `Retry-After`.

Owner and monitoring workspaces are not bounded by it. SDKs already treat
`quota` as an open string.

### Deletion

`account-lifecycle delete` runs these steps:

1. Move the account to `DELETION_PENDING` and revoke its access, as above.
2. Wait until the workspace has drained.
3. Mark every `ACTIVE`/`EXPIRING` inbox `DELETED` through the existing
   `DeleteInbox` use case. Exact addresses get their usual cooldown.
4. The existing retention sweep then removes rows and objects, as for any
   deleted inbox. It is paced under `ALL`.

Re-run the command until it reports `phase=CONTENT_REMOVED`, with no inbox
rows and no reservations left.

These are retained:

- the spent `beta_admission` place;
- the `participant_workspace` marker and its fixed share;
- every `workspace_deletion_charge`. A charge is freed only by the existing
  proven zero-trash observation;
- revoked key metadata, as audit history (ADR-032 §5);
- the workspace and project rows.

Deletion never releases a place. The next admission gets the next place.

Erasing identity is a separate, explicit step, `erase-identity`. It requires
`DELETION_PENDING`, no reservation and no inbox row. It deletes the account
row, which cascades its login identities, sessions, waitlist entry and
request budget, and sets key and idempotency provenance to null. It then
clears the membership's account and workspace references, leaving a
non-identifying tombstone of the spent place. The marker keeps the workspace
an **inactive participant**, never a legacy workspace. **When to run it is an
open owner decision (below). The delete procedure stops before it.**

### Restore quarantine

`lifecycle_restore_clearance` is a one-row table.

- It is classified `-` in `deploy/backup/scope.txt`, so it is never backed up.
- `check-backup-scope.sh` refuses any `+` classification of it, and refuses a
  dump that carries its row.

A restored database therefore has no clearance. While the clearance is
absent:

- every participant workspace reads as inactive, so its keys are refused and
  its mail is discarded with the uniform reply;
- every human account reads as `SUSPENDED` for login, session
  authentication and admission;
- `resume` is refused.

Owner and monitoring workspaces keep working. The stored states are not
changed.

`account-lifecycle restore-reconcile` is allowed only while the database is
quarantined. In one transaction it:

1. takes every participant workspace guard (sorted), then the cohort row and
   the T1 fence;
2. closes `admissions_open` and `capacity_ready`;
3. suspends every restored `ACTIVE` participant account;
4. deletes their sessions;
5. permanently revokes every managed key of every participant workspace;
6. records the clearance.

From then on the operator resumes individual accounts from Ops' external
lifecycle record. Those users sign in again and mint new keys.

Two other things are already excluded from backups, so a restore never
revives them: sessions and login state, and storage-pool approval
(`storage_cohort_plan`). The storage pool must be re-reserved
([cohort storage](../../dev/cohort-storage.md)).

V22 seeds the clearance only when the database has no admission at
migration time. Two kinds of database already have admissions when V22 runs,
and both start quarantined:

- a restored pre-V22 backup;
- a rehearsal or staging database that admitted participants before this
  release.

### Tests added

| Module | Tests |
|---|---|
| domain | `AccountLifecycleTest` (5), `ParticipantKeyInventoryTest` (4) |
| application | `AccountLifecycleOperationsTest` (14); inactive and race cases in `ReceiveInboundDeliveryTest` (+3, plus two new A1 kinds); `AuthenticateApiKeyTest` (+2); `ApiKeyLifecycleTest` (+5); `CreateInboxTest` (+1) |
| persistence | `AccountLifecycleTest` (9) |
| ingestion | `SmtpAccountLifecycleTest` (4) |
| api | `AccountLifecycleApiTest` (3), `AccountLifecycleCommandInputTest` (3), `AccountLifecycleCommandProcessTest` (3) |
| backup scope suite | 205 cases (was 197) |

The persistence `AccountLifecycleTest` runs on real PostgreSQL and covers:

- the role-based authority;
- permanent revocation across resume;
- a T1 that holds the fence, so the suspension reports `DRAINING`;
- a T1 queued behind an uncommitted suspension, which reads `Inactive`;
- a key minted concurrently with a suspension, serialized by the guard and
  then revoked;
- deletion with tombstones and an unreleased place;
- restore quarantine and reconciliation;
- V22 quarantine on upgrade.

`SmtpAccountLifecycleTest` uses real SMTP, PostgreSQL and MinIO. It drives
the prepare-to-T1 race through the existing `afterSlot` seam, without sleeps,
and proves byte-identical `250` transcripts.

`AccountLifecycleCommandProcessTest` starts the real `main` in a separate JVM
and proves three things:

- without credentials it refuses before connecting;
- a role with plain DML is not the authority and changes nothing;
- it never starts Spring or Tomcat.

`AccountLifecycleApiTest` proves over HTTP that a suspended account's
sessions and keys get `401`, that resume revives neither, and the inventory
`409`.

Existing tests changed honestly:

- `CohortStorageRuntimeTest`'s 128 MiB fixture now has a real ACTIVE member.
  A marker with no membership is inactive by design.
- `StorageV8GrantsTest` grants the new view.
- `SchemaUpgradeTest` lists V22.
- The metric-series ceiling went from 160 to 170. There are 163 series today;
  lifecycle added five, all from closed enums.

## Operator procedure

All commands use the reviewed API artifact, offline. No Spring, HTTP,
scheduler or migration runs. Credentials come from the operator's normal
secret mechanism, never arguments, a URL with user info, or a transcript:

```
export TI_LIFECYCLE_JDBC_URL='jdbc:postgresql://<db-host>:5432/<database>'
export TI_LIFECYCLE_USER='<schema owner, or a role granted membership in it>'
export TI_LIFECYCLE_PASSWORD='<from the secret store>'
```

Sessions are tagged `ApplicationName=ops:account-lifecycle`. The command
refuses a missing variable, a non-PostgreSQL URL, or a URL carrying
`user`/`password` or user info.

The CLI takes account UUIDs only, never emails. To find an account, the
operator runs a read-only query with the same credentials:
`SELECT id, state FROM human_account WHERE contact_email = '<address>';`

Every command prints metadata-only lines (ids, states, counts; never contact
data or credentials):

- `event=account.lifecycle ...`
- `event=account.content ...`
- `event=account.status ...`
- `event=restore.reconcile ...`

**Ops must append every such line to the lifecycle log kept outside the
database.** That log is what reconciles a restore. A database restore cannot
roll it back.

Exit codes:

| Code | Meaning |
|---|---|
| `0` | done |
| `1` | refused, unknown account, or missing authority; nothing changed |
| `2` | usage |
| `3` | applied, but draining or content pending; re-run the same command later |

```
# Suspend: sessions and keys revoked at once; new mail discarded.
java -jar api.jar account-lifecycle suspend <account-uuid>
java -jar api.jar account-lifecycle status <account-uuid>   # repeat until phase=SETTLED (exit 0)

# Resume a suspension: the user signs in again and mints new keys.
java -jar api.jar account-lifecycle resume <account-uuid>

# Delete: revoke, drain, then remove content via the inbox lifecycle.
java -jar api.jar account-lifecycle delete <account-uuid>   # repeat until phase=CONTENT_REMOVED (exit 0)

# Identity erasure: ONLY after the owner decides the retention period (open decision 1).
java -jar api.jar account-lifecycle erase-identity <account-uuid>

# After a database restore only (refused on a live, cleared database).
java -jar api.jar account-lifecycle restore-reconcile
```

How long each step takes:

- **Draining.** Reservations are consumed or released by T2 and cleanup.
  Normally this is within the 120 s write window. An ambiguous upload can
  hold its reservation until the existing verification proves its keys
  absent.
- **Content removal.** This waits for the retention sweep to hard-delete the
  `DELETED` inboxes.

To pause admissions, use stream A's admission control. This command does not
change `admissions_open`, except that `restore-reconcile` closes it.

## Restore procedure

Run this after Ops restores a control-plane backup by their documented
procedure (`docs/dev/production.md`, owned by Ops and stream D):

1. **Leave the quarantine in place.** Confirm
   `SELECT count(*) FROM lifecycle_restore_clearance;` returns `0`. If it
   returns `1`, the dump was not taken from the classified scope, and the
   restore must be stopped. The scope checker refuses such a dump.
2. The deployables may start, because quarantine makes it safe:
   - owner and monitoring traffic works;
   - participant keys, logins and mail are refused, and mail still gets the
     uniform `250`.
3. Run `java -jar api.jar account-lifecycle restore-reconcile`. Record its
   `event=restore.reconcile` line.
4. Re-apply, from Ops' external lifecycle log, every transition recorded after
   the backup was taken:
   - accounts deleted since the backup: `delete`, then `erase-identity` if it
     had been run;
   - accounts suspended since the backup: leave them suspended;
   - only accounts the log shows ACTIVE at the incident: `resume`.
5. The owner re-revokes any owner or monitoring key revoked after the
   backup. Legacy keys are outside the participant reconciliation (open
   decision 2).
6. Re-reserve the storage pool (`storage-budget reserve`,
   [cohort storage](../../dev/cohort-storage.md)). Approval is never
   restored.
7. Admissions stay closed until the separate release decision.

## Grant delta (for Ops, coordinated with MD1/md1)

These are exact changes to `deploy/database/roles.reference.sql`:

```sql
-- added, in the ingestion allow-list:
GRANT SELECT ON workspace_lifecycle_eligibility TO :"ingestion_role";
```

The change adds no `DO`, `SET`, `set_config` or `search_path`. The API role
needs nothing new: it already receives `SELECT/INSERT/UPDATE/DELETE ON ALL
TABLES`, which now also covers the view and `lifecycle_restore_clearance`.
The V22 triggers, not grants, stop the API role from writing state, erasing
accounts or lifting quarantine.

`StorageRoleModelTest` pins the ingestion allow-list exactly, now including
the view. It also proves:

- ingestion reads exactly `workspace_id` and `active` from the view;
- ingestion is refused on `lifecycle_restore_clearance`;
- gate F still reports no violation.

**Order.** Install the grant before any ingestion artifact containing this
change starts. Its T1 snapshot reads the view and would otherwise answer
`451` to every event. The order is:

1. Stage the scope and checker.
2. Run the migrator (V22).
3. Install the grants.
4. Deploy the API, then ingestion.

**Optional hardening, not applied.** Revoking `INSERT, UPDATE, DELETE ON
lifecycle_restore_clearance` from the API role would add a grant-level
barrier on top of the trigger. It was left out to keep the delta minimal
while MD1/md1 are pending.

## Open owner decisions

1. **Identity and contact retention after deletion.** No accepted retention
   period exists for `human_account`/`human_identity` rows after content
   removal. ADR-036 says not to retain identity merely to count admissions;
   the timing is undecided.
   - **Conservative choice implemented:** the account stays
     `DELETION_PENDING`, blocked from login, admission and resume, until an
     operator runs `erase-identity`. Nothing erases it automatically.
   - The owner must decide the period. Backups taken before an erasure keep
     that identity until those backups age out under the ADR-034 backup
     retention, which this stream does not change.
2. **Legacy key revocations across a restore.** `restore-reconcile` revokes
   every participant key, but not owner or monitoring keys. A legacy key
   revoked after the backup comes back un-revoked.
   - **Current state:** documented manual re-revocation (restore step 5).
   - **Alternative:** have reconciliation revoke every managed key, which
     would break owner synthetics until they are re-keyed.
3. **Retention of revoked key labels.** The `name` of revoked participant
   keys is kept as audit history after deletion, per the existing ADR-032
   rule. If the owner considers labels personal data, they need their own
   retention rule.

## Remaining / not in this stream

- **Admission pause control:** stream A. **Client-address trust:** streams
  A and W.
- **Synthetics for `/account`**, and the production and abuse docs: stream D.
- **Rollback floor:** recording the lifecycle floor commit in
  `deploy/rollback-floors.txt` once it exists on `develop`. The SHA must be
  the integrated one; see [rollback](../../dev/rollback.md).
- **Ops actions:** the grant installation above, MD1/md1 first. Adding the
  lifecycle log to the Ops runbook, and `restore-reconcile` to the Ops
  restore runbook.
- **Staging:** if staging already holds admissions, V22 starts it
  quarantined. Ops runs `restore-reconcile` once after migrating, then
  resumes the rehearsal accounts.
