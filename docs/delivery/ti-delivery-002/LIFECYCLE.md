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
3. the account row (`FOR NO KEY UPDATE`).

The account lock is `FOR NO KEY UPDATE` on purpose. Sign-in, session
authentication, logout and admission lock the row too, and they all still
serialize with it. The difference is foreign keys. A human idempotent mint
or inbox creation inserts its idempotency claim, and that claim's foreign key
holds `FOR KEY SHARE` on the account before the write reaches the workspace
guard. `FOR UPDATE` would wait on that lock while holding the guard the
writer needs, which is a deadlock. `FOR NO KEY UPDATE` does not wait on it.
The writer then reaches the guard after the suspension commits and is
refused (`AccountLifecycleTest`). Session revocation inside the transaction
uses the same lock mode for the same reason.

Every operator transaction is bounded the way T1 is: `lock_timeout` 5 s,
`statement_timeout` 10 s, `idle_in_transaction_session_timeout` 10 s. This
applies to `account-lifecycle`, `admission` and `storage-budget`. A stalled or
abandoned operator therefore cannot hold the `(35,1)` fence or the cohort
row. On expiry PostgreSQL aborts the transaction, nothing commits, and the
command exits `4`.

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
- **Row triggers.** Triggers refuse any other role. They cover
  `human_account` (`UPDATE OF state`, `DELETE`), `beta_admission` (`UPDATE`,
  `DELETE`, `TRUNCATE`) and `lifecycle_restore_clearance` (every write,
  including `TRUNCATE`). Membership decides eligibility, so re-pointing a
  suspended workspace's place at another, active account would revive it.
  Admission only inserts. The erasure step's update, and the account
  foreign key's `SET NULL`, run inside the operator's own transaction.
- **Pinned search path.** Both functions set
  `search_path = pg_catalog, public, pg_temp`, with `pg_temp` last, and
  schema-qualify every catalog reference (`pg_catalog.pg_has_role`,
  `pg_catalog.pg_class`, `'public.human_account'::pg_catalog.regclass`).
  Otherwise a runtime role could create a temporary `pg_class` holding a
  forged owner row and pass as the operator. A real-PostgreSQL test runs
  exactly that forgery and proves it refused. The runtime API role still holds plain
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
   `DeleteInbox` use case. A deleted account's EXACT addresses re-enter the
   normal ADR-021 cooldown. The command uses the deployment's own value: the
   API's `TESTINBOX_EXACT_COOLDOWN` when the operator's env file carries it,
   otherwise the shipped `exact-cooldown: 24h`. A malformed value is refused
   before anything changes. If a deployment ever overrides the cooldown
   without passing it here, the deleted addresses get 24 h instead. That
   drift is visible in `exact_address_reservation.available_at`.
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

Independent-review fixes added these tests:

| Suite | What it proves |
|---|---|
| persistence `AccountLifecycleTest`, +5 | the temporary-`pg_class` forgery refused (S1); membership re-pointing refused (S2); a human idempotent mint holding its claim while a suspension commits, with no deadlock and nothing minted (C1); the operator's bounded lock wait (C6); the readiness probe (C3) |
| `StorageRoleModelTest` | both probes pass under the reference grants; an API role that is a member of the owner is refused by the reference guard |
| `AccountLifecycleOperationsTest`, +1 | a reconciliation that loses the race is a no-op (C4) |
| `AccountLifecycleCommandInputTest`, +2 | exit code 4 (C5); the EXACT cooldown from configuration (C7) |
| `AdmissionCommandTest` | `resume` without `--open-beta` is refused |
| `check-roles-reference.test.sh` | 28 cases |
| synthetic edge-classifier unit tests | 31 cases |

Existing tests changed honestly:

- `CohortStorageRuntimeTest`'s 128 MiB fixture now has a real ACTIVE member.
  A marker with no membership is inactive by design.
- `StorageV8GrantsTest` grants the new view.
- `SchemaUpgradeTest` lists V22.
- The metric-series ceiling went from 160 to 170. There are 163 series today;
  lifecycle added five, all from closed enums.

## Operator procedure

There is no `api.jar` on the host. The API image's entrypoint is Spring
Boot's `JarLauncher` (`deploy/docker/backend.Dockerfile`), and arguments after
the image reference pass straight to the command. Run each command as a
one-off container of the reviewed API digest, on the network where
PostgreSQL is reachable. No Spring, HTTP, scheduler or migration runs.

Rules for running it:

- Put the credentials in a `0600` env file, never on the command line.
- Never put credentials in a URL with user info, or in a transcript.
- Never use `docker compose run api`: it inherits the API service's Traefik
  labels and runtime environment.

```
# /root/testinbox-ops/lifecycle.env  (mode 0600)
TI_LIFECYCLE_JDBC_URL=jdbc:postgresql://<db-host>:5432/<database>
TI_LIFECYCLE_USER=<schema owner, or a role granted membership in it>
TI_LIFECYCLE_PASSWORD=<from the secret store>
# optional: the API's own value, if the deployment overrides it
# TESTINBOX_EXACT_COOLDOWN=24h
```

Below, `lifecycle` stands for:
`docker run --rm --network <data network> --env-file /root/testinbox-ops/lifecycle.env <registry>/testinbox-api@sha256:<api digest> account-lifecycle`.

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
database.** A database restore cannot roll that log back. `restore-reconcile`
does not read it; the operator replays it by hand after reconciliation (see
the restore procedure).

Exit codes:

| Code | Meaning |
|---|---|
| `0` | done |
| `1` | refused, unknown account, or missing authority; nothing changed |
| `2` | usage |
| `3` | applied, but draining or content pending; re-run the same command later |
| `4` | failed for another reason (a lock or statement timeout, a lost connection). Whether it committed is unknown, so run `status` first. The message names only the exception type. |

```
# Suspend: sessions and keys revoked at once; new mail discarded.
lifecycle suspend <account-uuid>
lifecycle status <account-uuid>   # repeat until phase=SETTLED (exit 0)

# Resume a suspension: the user signs in again and mints new keys.
lifecycle resume <account-uuid>

# Delete: revoke, drain, then remove content via the inbox lifecycle.
lifecycle delete <account-uuid>   # repeat until phase=CONTENT_REMOVED (exit 0)

# Identity erasure: ONLY after the owner decides the retention period (open decision 1).
lifecycle erase-identity <account-uuid>

# After a database restore only (refused on a live, cleared database).
lifecycle restore-reconcile
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

1. **Leave the quarantine in place.** After the restore, and before any
   deployable starts, confirm
   `SELECT count(*) FROM lifecycle_restore_clearance;` returns `0`. If it
   returns `1`, stop the restore. The dump was not the accepted form: full
   DDL with `--exclude-table-data` for every `-` table. A `--table` dump is
   not accepted, because re-running V22 on it seeds the clearance before the
   data loads. The scope checker refuses a dump carrying the row.
2. The deployables may start, because quarantine makes it safe:
   - owner and monitoring traffic works;
   - participant keys, logins and mail are refused, and mail still gets the
     uniform `250`.
3. Run `lifecycle restore-reconcile` and record its
   `event=restore.reconcile` line. It suspends and revokes every restored
   participant, closes admissions and records the clearance. It does **not**
   read the lifecycle log. A second, concurrent run is a clean no-op.
4. Replay, by hand, from Ops' external lifecycle log, every transition
   recorded after the backup was taken. Replay every `delete`,
   `erase-identity` and `suspend` event, for participant and
   **non-participant** accounts alike, **before sign-in or registration is
   re-enabled**. This step is mandatory: a non-participant account has no
   workspace, so reconciliation does not suspend it, and without the replay
   an account deleted after the backup could sign in again.
   - Accounts deleted since the backup: `delete`, then `erase-identity` if it
     had been run.
   - Accounts suspended since the backup: leave participants suspended, and
     `suspend` non-participants again.
   - Only participants the log shows ACTIVE at the incident: `resume`.
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
change starts. The order is:

1. Stage the scope and checker.
2. Run the migrator (V22).
3. Re-run `roles.reference.sql`. The API needs this too: its `ON ALL TABLES`
   grant is one-shot, so it does not cover the new view and table until the
   file is re-run.
4. Deploy the API, then ingestion, then web, then run the synthetics.

If a role still lacks the grant, its deployable reports **NOT READY** rather
than serving: readiness `lifecyclePrivileges`, with reason
`ELIGIBILITY_VIEW_UNREADABLE` or `RESTORE_CLEARANCE_UNREADABLE`. Without this,
the API would refuse every participant key and ingestion would answer `451`
to every event.

**Staging** connects every deployable as the table owner. There the probe
always passes, and the V22 authority functions and triggers add nothing,
because the owner is the operator authority. Only production's separate roles
get the protection.

**Required Ops action once MD1/md1 are fixed: grant-level hardening.** Add
grant-level barriers on top of the V22 triggers by revoking these from the
API role:

- `INSERT, UPDATE, DELETE ON lifecycle_restore_clearance`;
- `UPDATE (state) ON human_account`;
- `UPDATE, DELETE ON beta_admission`.

These are kept out of `roles.reference.sql` for now because of the MD1
constraint. The triggers, which a real-PostgreSQL test proves, enforce the
same rule in the meantime.

**Also added to `roles.reference.sql`'s read-only guard block.** It refuses an
API, ingestion or monitor role that is a member of the schema owner, because
such a role would hold the operator authority whatever is revoked.
`scripts/check-roles-reference.sh` now also refuses the following, and its
self-test proves each refusal:

- any `ON SCHEMA` grant other than USAGE;
- any `ON DATABASE` grant;
- `ALTER DEFAULT PRIVILEGES`;
- role-membership grants;
- inside the guard block, any assignment or call other than `pg_has_role`
  and `current_setting`.

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
  The edge synthetic now also proves that the API host never serves the
  account boundary, including the `/v1/../account/…` and
  `/v1/%2e%2e/account/…` forms (`tests/edge.test.mjs`).
- **Rollback floor:** recording the lifecycle floor commit in
  `deploy/rollback-floors.txt` once it exists on `develop`. The SHA must be
  the integrated one; see [rollback](../../dev/rollback.md).
- **Ops actions:** the grant installation above, MD1/md1 first. Adding the
  lifecycle log to the Ops runbook, and `restore-reconcile` to the Ops
  restore runbook.
- **Staging:** if staging already holds admissions, V22 starts it
  quarantined. Ops runs `restore-reconcile` once after migrating, then
  resumes the rehearsal accounts.
