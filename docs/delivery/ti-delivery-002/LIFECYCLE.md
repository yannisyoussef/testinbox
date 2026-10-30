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

Every operator transaction (including the per-inbox deletions, through the session `options`) is bounded the way T1 is: `lock_timeout` 5 s,
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

### Deletion (resumable workflow, TI-DELIVERY-002b)

Owner decision 1 ([POLICY-002b](POLICY-002b.md)): the contact and login
identity is erased **after** content deletion completes, by the deletion
workflow itself, never by an open-ended manual follow-up. Deletion is a
resumable, idempotent workflow with explicit stages, recorded per account in
`account_deletion` (V23: account UUID, stage, `requested_at`,
`stage_changed_at`, `erased_at`; no contact or login identity):

| Stage | Meaning | Leaves when |
|---|---|---|
| `DRAINING` | `delete` moved the account to `DELETION_PENDING` and revoked every session and key, in the same transaction that opened the record. Copies reserved before the fence may still finish. Nothing is deleted. | no `storage_reservation` of the workspace remains |
| `CONTENT_DELETING` | Every `ACTIVE`/`EXPIRING` inbox is marked `DELETED` through the existing `DeleteInbox` (EXACT addresses enter the ADR-021 cooldown with the deployment's `TESTINBOX_EXACT_COOLDOWN`, shipped 24 h). The existing retention sweep removes rows and objects, objects proven deleted first, paced under `ALL` within `T_max`. | no inbox row, no reservation, and no EXACT reservation `ACTIVE` or still cooling down |
| `ERASED` | Identity erased (below). Terminal. | — |

The final step runs in ONE operator transaction under the lifecycle lock
order (workspace guard, `(35,1)`, account row). It re-reads the content gate
under those locks and erases only if nothing remains. Otherwise it records
`CONTENT_DELETING` and changes nothing else. A crash at any point leaves a
state the next run continues from. The erasure is one transaction, so a crash
inside it rolls all of it back. After `ERASED`, re-running reports `ERASED`
and touches nothing.

**Erased** in that transaction:

- `human_account`: the contact email, and through the existing cascades every
  `human_identity` (provider issuer and subject, including the EMAIL identity),
  `human_session`, `beta_waitlist` entry and `human_request_budget`;
- `email_login_challenge` rows addressed to the account's contact email (they
  are keyed by address, not by account);
- the free-text `name` of every key of the workspace (set to null);
  `created_by_human_account_id` becomes null through the existing foreign key;
- the workspace's `idempotency_record` rows (request fingerprints and
  projections that can name keys or addresses);
- the workspace's `exact_address_reservation` rows (the chosen local parts).
  By then the gate has proved every one released or past its cooldown, so
  nothing is released early;
- the membership's account, workspace and project references.

**Retained**, never released or erased:

- the spent `beta_admission` place, as a non-identifying tombstone. Deletion
  never releases a seat; the next admission gets the next place;
- the `participant_workspace` marker and its fixed share, so the workspace
  stays an **inactive participant**, never legacy;
- every `workspace_deletion_charge`. A charge is freed only by the existing
  proven zero-trash observation;
- the keys' technical rows: id, public id, verifier, scopes, timestamps,
  `last_used_at`, revocation and creating key. ADR-032 §5 audit history and the
  20 usable / 1000 retained inventory still count them;
- the workspace and project rows (fixed names `My workspace`/`Default`);
- the `account_deletion` record itself.

Not reached by erasure, and why:

- `provider_login_state` is keyed by browser, not account. It expires within
  ten minutes and the existing lifecycle purges it.
- Row-free object residue under a deleted inbox's prefix (a late or held
  object, paced teardown) is deleted by the orphan sweep, charged by rule (P).
  It carries no contact identity.
- Application logs and the Ops lifecycle log keep account UUIDs, and key-name
  audit lines, for their own retention. Backups keep everything they held
  until they age out (below).

**Backups.** Erasure does not reach a backup taken before it. `human_account`,
`human_identity`, `api_key` (with names), `exact_address_reservation` and
`beta_admission` are `+` in `deploy/backup/scope.txt`, so each earlier backup
keeps that identity until it ages out under the ADR-034 backup retention Ops
operates (production.md, "HUMAN DECISION REQUIRED"). Nothing promises
immediate erasure from backups. A restore replays the deletion (restore
procedure, step 4), which erases it again.

**Overdue.** A deletion is overdue when it is not `ERASED` this long after
`requested_at`. The threshold is the longest the existing machinery may
legitimately take: the EXACT cooldown, plus the paced-retention `T_max`
(`TESTINBOX_STORAGE_FS_RETENTION_PACING_MAX_DELAY`, shipped 24 h), plus 24 h of
slack for draining and the timer period. With shipped values it is **72 h**.
`TI_LIFECYCLE_DELETION_OVERDUE_AFTER` overrides it. Both are Spring durations.
A malformed or non-positive value is refused before anything runs.

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
- `resume` is refused;
- **no managed key authenticates, owner and monitoring keys included**
  (TI-DELIVERY-002b decision 2). API-key authentication reads the clearance per
  request (`RestoreQuarantine`) and answers `401` (`WORKSPACE_INACTIVE` in the
  auth metric). A legacy key revoked after the backup was taken therefore
  never works again, not even in the window before reconciliation.

Mail to a legacy workspace is unaffected in principle, but no inbox exists
after a restore (`inbox` is never backed up), so there is nothing to deliver
to. The stored states are not changed.

`account-lifecycle restore-reconcile` is allowed only while the database is
quarantined. In one transaction it:

1. takes every participant workspace guard (sorted), then the cohort row and
   the T1 fence;
2. closes `admissions_open` and `capacity_ready`;
3. suspends every restored `ACTIVE` participant account;
4. deletes their sessions;
5. permanently revokes every managed key of every participant workspace;
6. permanently revokes every other still-usable managed key, in every
   workspace: the owner's administrator, the synthetic and monitoring keys
   (`legacyRevokedKeys=` in its output);
7. records the clearance.

From then on the operator resumes individual accounts from Ops' external
lifecycle record. Those users sign in again and mint new keys. Ops issues
replacement owner and synthetic credentials through the bootstrap
break-glass, which works again exactly because no usable managed
administrator is left (restore procedure, step 5).

Bootstrap credentials are **not** rows a restore brings back as authority:
the bootstrap key authenticates only when the presented token matches the
value the API process is currently configured with
(`TESTINBOX_BOOTSTRAP_API_KEY`), so reconciliation neither revokes nor
rotates it. Configured secrets (database, object store, OAuth, email,
client-address and edge secrets) live outside the database and are not
touched by a database restore at all. See the credential audit in
[production.md](../../dev/production.md#credentials-after-a-restore).

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

TI-DELIVERY-002b replaced the content and erase tests with the deletion
workflow suites (`AccountDeletionWorkflowTest`, `AccountDeletionCommandTest`,
`BetaWaitlistExpiryApiTest` and the extended unit suites). The persistence
deletion test listed below moved there. Counts are in
[POLICY-002b](POLICY-002b.md#evidence).

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

Every command prints metadata-only lines (ids, states, stages, timestamps,
counts; never contact data, key names or credentials):

- `event=account.lifecycle ...`
- `event=account.deletion ...` (one per deletion advanced or listed) and
  `event=account.deletions ...` (the summary)
- `event=account.status ...`
- `event=restore.reconcile ...`

**Ops must append every such line to the lifecycle log kept outside the
database.** A database restore cannot roll that log back. `restore-reconcile`
does not read it; the operator replays it by hand after reconciliation (see
the restore procedure).

Exit codes:

| Code | Meaning |
|---|---|
| `0` | done. For `process-deletions` and `status` without an account: nothing is overdue (deletions still within the threshold are normal) |
| `1` | refused, unknown account, or missing authority; nothing changed |
| `2` | usage (including the removed `erase-identity`) |
| `3` | applied, but draining or content pending; the timer (or a re-run) continues it |
| `4` | failed for another reason (a lock or statement timeout, a lost connection). Whether it committed is unknown, so run `status` first. The message names only the exception type. For `process-deletions`: at least one deletion's step failed (`outcome=FAILED`); the others were still advanced |
| `5` | `process-deletions`, `status` (with or without an account): at least one deletion is **overdue**, or a deletion record exists for an account that is not `DELETION_PENDING`, or a `DELETION_PENDING` account has no record. **Alert.** |

```
# Suspend: sessions and keys revoked at once; new mail discarded.
lifecycle suspend <account-uuid>
lifecycle status <account-uuid>   # repeat until phase=SETTLED (exit 0)

# Resume a suspension: the user signs in again and mints new keys.
lifecycle resume <account-uuid>

# Delete: revoke, then the workflow advances as far as it can right now.
# Exit 0 = ERASED; exit 3 = DRAINING/CONTENT_DELETING, the timer finishes it.
lifecycle delete <account-uuid>

# The Ops timer's command: advance EVERY pending deletion one step.
lifecycle process-deletions

# Read-only: every pending deletion with its stage, age and overdue verdict.
lifecycle status

# After a database restore only (refused on a live, cleared database).
lifecycle restore-reconcile
```

`erase-identity` no longer exists. It is refused with exit `2` and a message
naming its replacement. Identity is erased automatically by `delete` or
`process-deletions` once the content gate passes, and never while content
remains. No override erases earlier: erasing would release EXACT addresses
inside their cooldown and orphan the content's accounting.

Re-running `delete` for an account that is already `ERASED` prints
`state=ERASED` and exits `0`. That makes the restore replay idempotent.
`status <account-uuid>` of an erased account prints `state=ERASED` with its
`erasedAt`.

### The Ops timer for `process-deletions` (required)

Run `lifecycle process-deletions` from an Ops timer every **15 minutes**,
appending its output to the lifecycle log. Alert on any non-zero exit:

- `5`: a deletion is overdue. Inspect its line. `reservationsHeld>0` long after
  the 120 s write window means a stuck ambiguous upload. `inboxRows>0` means
  the retention sweep is not tearing the inboxes down (check
  `testinbox_storage_retention_backlog_seconds`). `exactCooldowns>0` beyond
  the cooldown means a reservation row was not moved to `COOLDOWN`.
  `stage=UNRECORDED` or a `REFUSED` line means the database was restored or
  edited outside this workflow.
- `4`: a step failed; the next run retries it. Two consecutive `4`s need a
  look.

The command is safe to run concurrently with itself and with the running
deployables. Every step is a bounded operator transaction (5 s lock wait,
10 s per statement) under the documented lock order. Example systemd units
(Ops owns the real ones):

```
# /etc/systemd/system/testinbox-deletions.service
[Service]
Type=oneshot
ExecStart=/bin/sh -c 'docker run --rm --network <data network> --env-file /root/testinbox-ops/lifecycle.env \
  <registry>/testinbox-api@sha256:<api digest> account-lifecycle process-deletions >> /var/log/testinbox/lifecycle.log 2>&1'

# /etc/systemd/system/testinbox-deletions.timer
[Timer]
OnBootSec=5min
OnUnitActiveSec=15min
[Install]
WantedBy=timers.target
```

Pin the timer to the same reviewed API digest as the deployment, and update
it with every promotion. If the API's `TESTINBOX_EXACT_COOLDOWN` or
`TESTINBOX_STORAGE_FS_RETENTION_PACING_MAX_DELAY` is overridden, put the same
values in `lifecycle.env`, so both the cooldown applied to deleted addresses
and the overdue threshold follow the deployment.

How long each stage takes:

- **Draining.** Reservations are consumed or released by T2 and cleanup.
  Normally this is within the 120 s write window. An ambiguous upload can
  hold its reservation until the existing verification proves its keys
  absent.
- **Content deleting.** Marking inboxes `DELETED` is immediate. The retention
  sweep then hard-deletes them (5 s sweep interval; paced under `ALL`, but
  never beyond `T_max`, shipped 24 h). An EXACT inbox's address cooldown (24 h
  shipped) also has to elapse.
- **Erasure.** One transaction, on the first timer run after both. So a
  deletion with an EXACT address normally erases about 24 h after the
  request, one without in minutes, and every deletion within the 72 h overdue
  threshold.

To pause admissions, use stream A's admission control. This command does not
change `admissions_open`, except that `restore-reconcile` closes it.

## Restore procedure

Run this after Ops restores a control-plane backup by their documented
procedure (`docs/dev/production.md`, owned by Ops and stream D):

0. **Before the restore,** confirm the API is configured with a bootstrap
   credential (`TESTINBOX_BOOTSTRAP_API_KEY`), and rotate it if there is any
   doubt about it. Step 3 revokes every managed key, so bootstrap is then the
   only way back into key management for the owner workspace. An API with no
   bootstrap credential has no way back.
1. **Leave the quarantine in place.** After the restore, and before any
   deployable starts, confirm
   `SELECT count(*) FROM lifecycle_restore_clearance;` returns `0`. If it
   returns `1`, stop the restore. The dump was not the accepted form: full
   DDL with `--exclude-table-data` for every `-` table. A `--table` dump is
   not accepted, because re-running V22 on it seeds the clearance before the
   data loads. The scope checker refuses a dump carrying the row.
2. The deployables may start, because quarantine makes it safe:
   - **no managed key authenticates**, participant or legacy; logins are
     refused; mail to participants is discarded with the uniform `250`;
   - the synthetics fail until step 5. That is expected.
3. Run `lifecycle restore-reconcile` and record its
   `event=restore.reconcile` line. It suspends every restored participant,
   revokes **every** restored managed key (`revokedKeys=` participant,
   `legacyRevokedKeys=` owner and monitoring), closes admissions and records
   the clearance. It does **not** read the lifecycle log. A second,
   concurrent run is a clean no-op.
4. Replay, by hand, from Ops' external lifecycle log, every transition
   recorded after the backup was taken. Replay every `delete` and `suspend`
   event (and any `erase-identity` from before TI-DELIVERY-002b as a
   `delete`), for participant and **non-participant** accounts alike,
   **before sign-in or registration is re-enabled**. This step is mandatory:
   a non-participant account has no workspace, so reconciliation does not
   suspend it, and without the replay an account deleted after the backup
   could sign in again.
   - Accounts deleted since the backup: `delete`. The workflow then erases
     them again; `process-deletions` finishes what `delete` cannot do at once.
   - Accounts suspended since the backup: leave participants suspended, and
     `suspend` non-participants again.
   - Only participants the log shows ACTIVE at the incident: `resume`.
   - Then run `lifecycle process-deletions` once and confirm that no pending
     deletion is refused (exit `5` with a `REFUSED` line means the restored
     record disagrees with the account; investigate).
5. **Re-key the deployment** (TI-DELIVERY-002b decision 2):
   - authenticate with the bootstrap credential and mint a new owner
     administrator (`api-keys:manage`). The bootstrap credential stops
     working the moment it exists;
   - with the new administrator, mint the new synthetic credential
     (`inboxes:write`, `messages:read` only) and any monitoring key;
   - put them in the Ops secret store and the synthetic runner, and run the
     synthetics. They must pass before the restore is declared complete;
   - follow the configured-credential audit in
     [production.md](../../dev/production.md#credentials-after-a-restore).
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

## Owner decisions (decided, TI-DELIVERY-002b)

The three decisions this stream left open were decided by the owner and are
implemented ([POLICY-002b](POLICY-002b.md)):

1. **Identity after deletion:** erased automatically by the deletion workflow
   once content deletion is verifiably complete (above). Backups age out
   under the existing backup retention; nothing promises immediate erasure
   from them.
2. **Legacy keys across a restore:** `restore-reconcile` revokes every
   restored managed key, legacy ones included, and no managed key
   authenticates while quarantined. Ops re-keys the owner and the synthetics
   through the bootstrap break-glass (restore procedure, step 5).
3. **Revoked key labels:** treated as potentially identifying. Erasure sets
   them to null and keeps the technical audit rows and the inventory counts.

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
  lifecycle log to the Ops runbook, `restore-reconcile` and the re-keying
  step to the Ops restore runbook, and the `process-deletions` timer with
  its alert (TI-DELIVERY-002b).
- **Staging:** if staging already holds admissions, V22 starts it
  quarantined. While it is quarantined, no managed key authenticates, so the
  staging synthetics fail. Ops runs `restore-reconcile` once after
  migrating. That now also revokes the staging owner and synthetic keys, so
  Ops then re-keys through the staging bootstrap credential (restore
  procedure, step 5) and resumes the rehearsal accounts.
