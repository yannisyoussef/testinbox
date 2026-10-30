# Production

The contract between the application and the **production** estate: the OVH
dedicated host in France, operated through the Infinity Ops platform
([ADR-034](../adr/0034-production-platform-and-promotion.md)). It is written
the way [staging.md](staging.md) is: what the application needs, what it
exposes, what must not change — not an Ops runbook.

**Production is not deployed and not live.** What this document enables is a
*dark* deployment: exact staging-proven artifacts running on the production
host with production secrets, private SMTP only, no MX, no relay from the
edge, no tenant traffic. Public activation is TI-007 plus the human gates in
[ADR-004](../adr/0004-initial-inbound-provider-strategy.md).

## Topology

```
Internet HTTPS ──▶ Cloudflare ──▶ production ingress (Ops) ──▶ API / web
                                                                 │
                                                  ┌──────────────┼──────────────┐
                                                  │              │              │
                                             PostgreSQL     object store    ingestion (SMTP, private)
                                             (direct,       (pre-provisioned
                                              no pooler)     bucket, scoped creds)

future public SMTP ──▶ dedicated Contabo EU Postfix edge ──▶ private authenticated path ──▶ OVH ingestion
                       (dormant; TI-007)                     (does not exist yet)
```

The edge and the application host are separate trust boundaries and stay so.

## The four things a production process refuses to start without

`SPRING_PROFILES_ACTIVE=production` and `TESTINBOX_ENVIRONMENT=production`,
together. `production` is a profile **group** over the same `deployed` layer
staging uses; the production overrides are the second document of
`application-deployed.yaml` (a separate file would be shadowed — the layering
is proven by `DeployedProfileLayeringTest`), and `DeploymentSafety` enforces
them whatever an environment variable says
([ADR-034 §4](../adr/0034-production-platform-and-promotion.md)):

| refused | why |
|---|---|
| profile `production` without environment `production`, or the reverse; a blank environment with any deployed profile; another environment profile active alongside `production` | a staging configuration labelled production, a production process that does not know it is one, an environment variable set but empty (which used to skip every check), or a later profile document that could override the production one |
| `testinbox.mail-domain` ≠ `inbox.testinbox.email` | the public MX will name that domain and no other (ADR-004) |
| API without an `https://` `TESTINBOX_PUBLIC_BASE_URL` | a production API node has a public origin and must state it |
| a loopback authority, or `staging`/`rehearsal`/`.local` in the host, of the base URL, `TESTINBOX_DB_URL` or `TESTINBOX_S3_ENDPOINT`; a database URL or endpoint without an explicit host | the environment's own data services, never another environment's — and `jdbc:postgresql:testinbox` (implicit localhost) has no host for the check to see |
| `TESTINBOX_S3_CREATE_BUCKET=true` | production credentials hold no `CreateBucket`; the bucket is pre-provisioned |
| `TESTINBOX_EDGE_REQUEST_CEILING` unset | the ingress ceiling must be declared so the wait window is proven to fit (100 s behind Cloudflare; `TESTINBOX_WAIT_WINDOW_CAP` stays 60 s) |
| `testinbox.limits.enabled=false` | a reachable deployment would be unprotected (ADR-027) |
| `testinbox.deployment.require-database-session-timeout=false` | production must enforce the database session bound, not merely report it (below) |
| a short, low-entropy or fixture bootstrap key | it is the highest-privilege row in the table (ADR-032 §8) |

Plus everything the deployed baseline already refuses: local-development
credentials, a plaintext base URL, a proxy read timeout that does not clear
the wait window by 30 s. All violations are reported at once, by setting
name, never by value.

### The production environment, as Ops must set it

Ops does not use this repository's compose file, so its defaults do not
reach production. Every value below is set explicitly in the Ops secret
store or reconcile; the two marked *fixed* are supplied by the profile
document and an environment variable for them is ignored (and a
relaxed-binding override of the underlying property is refused).

| variable | value | note |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `production` | the group; nothing else alongside it |
| `TESTINBOX_ENVIRONMENT` | `production` | exact, lowercase, non-blank |
| `TESTINBOX_PUBLIC_BASE_URL` | `https://<production host>` | API only |
| `TESTINBOX_EDGE_REQUEST_CEILING` | `100s` | Cloudflare's per-request ceiling; required |
| `TESTINBOX_PROXY_READ_TIMEOUT` | between `90s` and `100s` | must clear the 60 s window by 30 s and must not exceed the ceiling — the reference compose default of `120s` is **refused** here |
| `TESTINBOX_WAIT_WINDOW_CAP` | `60s` | do not raise (ADR-030) |
| `TESTINBOX_MAIL_DOMAIN` | *fixed*: `inbox.testinbox.email` | supplied by the profile |
| `TESTINBOX_S3_CREATE_BUCKET` | *fixed*: `false` | supplied by the profile |
| `TESTINBOX_DB_URL`, `_USER`, `_PASSWORD` | the production PostgreSQL, `jdbc:postgresql://host:5432/db` form | direct, no pooler |
| `TESTINBOX_S3_ENDPOINT`, `_ACCESS_KEY`, `_SECRET_KEY`, `_BUCKET`, `_REGION` | the pre-provisioned bucket's scoped credentials | no `CreateBucket` |
| `TESTINBOX_BOOTSTRAP_API_KEY` | ≥ 43 chars of real entropy | break-glass only |
| `TESTINBOX_GIT_SHA`, `TESTINBOX_IMAGE_DIGEST` | the handed-off candidate and each image's own digest | identity on `/actuator/info` |
| `TESTINBOX_MANAGEMENT_PORT`, `TESTINBOX_SMTP_PORT`, `TESTINBOX_SHUTDOWN_GRACE`, `TESTINBOX_DB_POOL_SIZE` | as staging | private management port; SMTP never public |

## Readiness, and one setting the application checks live

Same probes and same components as staging
([staging.md](staging.md#health-and-readiness)), plus
**`lifecyclePrivileges`** on both deployables (V22: the role can read the
account-lifecycle view, and on the API the restore clearance; see the V22
deployment order below), plus **`dbSession`** on the
API: it reads `SHOW idle_in_transaction_session_timeout` on every probe. In
production a disabled value (`0`, PostgreSQL's default) takes the node
**OUT_OF_SERVICE** until Ops sets it; elsewhere it is reported and the node
stays up. The recommended value is `30s` — above the 30 s claim-wait ceiling,
well below anything that stops bounding a partitioned node
([ADR-033](../adr/0033-idempotent-mutations.md), [idempotency.md](idempotency.md)).

Two consequences worth knowing. The setting must be **persisted** on the
database side (`ALTER SYSTEM` or `ALTER ROLE … SET`, not a session `SET`),
because on a single production node a PostgreSQL restart that loses it takes
the API out of readiness for a liveness property of one idempotency key; row
F's proof includes surviving a restart. And this is the readiness-cascade
shape ADR-030 already flags for any multi-node production — revisit before
one exists.

Still unverifiable from inside the application, and therefore Ops
acceptance items rather than checks: that the database is reached
**directly** with no transaction-mode pooler (the synthetic long-poll suite
measures the symptom; `testinbox_wait_listen_degraded_polling` reports it
continuously), that the management ports are unreachable from the ingress,
and that `/actuator` is not routed (the edge synthetic asserts it).

## Database privileges for the ADR-035 ledger (V6)

V6's ledger triggers run as the role that writes `message` and `attachment`
rows (`SECURITY INVOKER`). **Before V6 is promoted,** every role the API,
the ingestion gateway and the retention sweep connect as must have `INSERT`
on `storage_delta` and `USAGE` on `storage_delta_id_seq`. If it does not, every
message insert and every retention delete fails with `permission denied` the
moment V6 commits, and that includes writes from artifacts built before V6.
The API role also needs `SELECT, INSERT, UPDATE, DELETE` on `storage_delta`,
`workspace_storage_account` and `inbox_storage` for compaction and
reconciliation. Staging connects every deployable as the table owner, which
satisfies all of this. A production that separates the roles must grant these
first.

**The role model as a script.** `deploy/database/roles.reference.sql` is the
reference grant set for the API, ingestion and monitor roles. Run it as the
schema owner after every migration.
- `StorageRoleModelTest` applies it to a V10 database, runs every storage
  operation of the deployables as those roles, and runs activation gate F's
  own privilege query, which reports no violation.
- A blanket "DML on all tables", or an `ALTER DEFAULT PRIVILEGES` granting
  it, is exactly what gate F refuses.

**From V10 on (TI-STORAGE-006E, owner review b), the ledger is written only
by the database.**
- The V8 ledger triggers are `SECURITY DEFINER`. Compaction and repair are
  the definer functions `storage_compact_ledger(integer)` and
  `storage_repair_ledger()`.
- No application role then needs `INSERT`, `UPDATE`, `DELETE` or `TRUNCATE`
  on `storage_delta` or `workspace_storage_account`, nor on
  `inbox_storage`'s base columns.
- The API role holds `SELECT` on the three tables and `EXECUTE` on the two
  functions.
- Both deployables keep a column grant for the refusal record:
  - `INSERT (inbox_id, workspace_id, refusal_count, last_refusal_at, last_refusal_reason)`
    and `UPDATE (refusal_count, last_refusal_at, last_refusal_reason)` on
    `inbox_storage`.
- **No definer function is executable by `PUBLIC`** (V10). A definer
  *trigger* function left executable could be attached by any login role to
  a temporary table of its own, and run as the owner. Keep `EXECUTE` on
  definer functions to the grants listed here. Gate F flags any other.
- **Revoke the earlier writes** once every running artifact is from V10 on.
  A role that keeps them can forge the counts a trust mark vouches for, and
  gate F's privileges row refuses `ALL` while one does.

**V8 (TI-STORAGE-006E, the filesystem-containment contract) needs no new
grant for the deletes the deployables already make.** The trigger bodies
that write deletion debt (on every `message`, `attachment` and
`storage_reservation` delete, including the gateway's T2 consume) are
`SECURITY DEFINER`: they run as the migration owner, so neither the
ingestion nor the API role needs — or should be given — any privilege on
`storage_deletion_debt`. A role without it cannot write a debt row itself,
in particular not a never-compactable pending one. Every V8 function sets
`search_path = pg_catalog, public, pg_temp` (`pg_temp` LAST: PostgreSQL
otherwise searches the caller's temporary schema first, and a temp table
could shadow the ledger inside the definer code).

The API role reads `storage_deletion_debt`, `storage_filesystem_observation`
and `storage_debt_watermark` (`SELECT`).

It reads `storage_footprint_trust` and may only raise its `distrust_epoch`
(`SELECT, UPDATE (distrust_epoch)`, a column grant; a trigger keeps the epoch
monotone). **It never marks trust itself (V10).** The trust mark is made by
`storage_confirm_footprint_trust()`, which the API role executes. That
function takes the ledger lock, recreates a row lost to a restore, verifies
every workspace's bytes and object counts against the `message` and
`attachment` rows, and marks the epoch it read compare-and-set, stamping its
order and time. The role holds no `INSERT` and no `UPDATE` on the trusted
columns. A guard trigger refuses any other change to them, even for a role
that still holds an older full grant.

For the orphan sweep's database record (V10), the API role holds `EXECUTE` on
`storage_begin_sweep(text)` and `storage_complete_sweep(bigint, text, bigint)`, and
no privilege on `storage_sweep_run`.

It also holds `EXECUTE` on `storage_confirm_footprint_trust()`,
`storage_compact_deletion_debt()`,
`storage_record_pending_debt(text, bigint, bigint, text)` and
`storage_resolve_pending_debt(text)`. Those are `SECURITY DEFINER` with
`EXECUTE` revoked from `PUBLIC`; the API role holds **no** `DELETE` on
`storage_deletion_debt`, so no application path can delete a pending row or
compact without raising the watermark.

`storage_filesystem_observation` is written **only by the Ops filesystem
monitor**, as its own role with `EXECUTE` on `storage_begin_observation()`,
`INSERT` on the table and `USAGE` on its id sequence, and nothing else. The
monitor calls `storage_begin_observation()` BEFORE measuring: the server
issues the order and the start time. Its insert names that order and the
measured figures; a trigger stamps `started_at`, `observed_at` and
`written_by` (`session_user`, which the activation gate requires to be the
monitor role) and refuses an order no walk of the same role began. The
application never writes an observation, and treats every row as data to be
bounded by, never as an instruction. Observations are append-only: `UPDATE`
and `TRUNCATE` are refused, and so is a `DELETE` of the newest row or of any
row at or above the compaction watermark. An Ops prune job (with `DELETE`
only) removes older rows within its retention window — one row per minute is
≈ 50 MB a year. No role is granted anything on `storage_observation_walk`.

**Footprint admission and rule (P) (TI-STORAGE-006E PR D)** are used only
where the deployment declares its filesystem (`testinbox.storage.filesystem.*`,
now including `probe-budget-bytes`, `monitor-role` and the optional
`retention-pacing-max-delay`). An undeclared `OFF` deployment's T1 reads none of
the V8 tables, writes no pending row, and needs no grant below. Where the
filesystem is declared, rule (P) and the pre-resolution check read, under T1's
admission lock, the inputs T1 reads:

- **the ingestion role** (T1, the pre-resolution check, the breaker probe):
  `SELECT` on `storage_deletion_debt`, `storage_filesystem_observation`,
  `storage_debt_watermark` and `storage_footprint_trust`, and `EXECUTE` on
  `storage_record_probe_debt(text)` and `storage_resolve_probe_debt(text)` —
  `SECURITY DEFINER`, a fixed `(0 B, 1 object)` row for `_probe/` keys only. It
  holds **no** `EXECUTE` on the general pending-debt functions: the
  internet-facing role can neither charge nor resolve an arbitrary key;
- **the API role** (the orphan sweep, the ambiguity verifier, the cleanup
  witness): the same `SELECT`s, the two probe functions, and `EXECUTE` on
  `storage_record_pending_debt(text, bigint, bigint, text)` and
  `storage_resolve_pending_debt(text)`.

`StorageV8GrantsTest` runs T1 under `OFF` and the probe path as the ingestion
role with exactly these grants.

The API's accounting jobs read three optional settings:

- `testinbox.storage-accounting.compaction-interval` (default `5s`);
- `testinbox.storage-accounting.reconciliation-interval` (default `6h`);
- `testinbox.storage-accounting.reconciliation-initial-delay` (default `15m`).

The defaults are the ADR-035 values, and nothing needs to set them.

### The guarded ingest protocol (TI-STORAGE-003)

- **Database.** The ingestion and API roles also need `SELECT, INSERT, UPDATE,
  DELETE` on `storage_reservation`, `storage_ambiguity`, `storage_node`,
  `storage_admission_latch` and `storage_clock_episode` (V7), and `USAGE` on
  `storage_ambiguity_id_seq`; with V8, only the API role's reads, trust
  marking and function grants of the V8 paragraph above (the debt triggers
  run as their owner); with PR D and a declared filesystem, the per-role
  grants of the footprint paragraph above.
  Staging's owner role already has them.
- **Object storage.** Cleanup and the orphan sweep need `ListBucket`,
  `ListBucketMultipartUploads` and `AbortMultipartUpload` on the bucket,
  besides the object read, write and delete the runtime identity already
  holds. The presigned uploads are signed with the same identity, so it needs
  no new write privilege.
- **Node identity.** Each ingestion process must run with a
  `TESTINBOX_STORAGE_NODE_ID` that is stable across restarts, and it defaults
  to `testinbox-ingestion`. Its persisted ambiguity occupies its write slots
  across restarts. A node id that changed on every restart would lose that,
  and with it the finalize budget H. Two processes during a rolling deploy
  must use DIFFERENT ids, and are counted twice in H
  (`declared-max-ingestion-processes`). This is enforced: a process claims its
  id with a session-level advisory lock for its whole life, and a second
  process started with the same id **fails to start**. If the claim's session
  dies and another process takes the id meanwhile, the first opens its
  storage breaker (`451`) rather than share it.
- **Enforcement is OFF by default, and the setting exists (TI-STORAGE-006).**
  **ADR-035 Amendment 2 (accepted):** the physical link is the
  filesystem-containment contract. A non-OFF mode requires the dedicated
  filesystem's declarations below and their static inequality, and gate F
  proves them against the real filesystem before activation. The legacy
  bucket-quota fuse *Q* is **no longer required or evaluated**. MinIO's
  scanner-based quota overshot by ~22.75 GiB, so it was never a sound bound.
  The quota may stay configured as an optional secondary defence; a declared
  `TESTINBOX_STORAGE_DECLARED_BUCKET_QUOTA_BYTES` or
  `TESTINBOX_STORAGE_MEASURED_QUOTA_LAG_CHURN_BYTES` is only checked for being
  well-formed. Gate Q (the backend qualification match) is a different thing
  and stays mandatory.
  `TESTINBOX_STORAGE_QUALIFICATION_LAB_RECORD_PATH` (issue #102) must be
  unset: a lab-only qualification record is refused, in every mode, unless
  `TESTINBOX_ENVIRONMENT` is exactly `lab`, and `lab` cannot wear the
  `production` profile.
  `TESTINBOX_STORAGE_ENFORCEMENT` is `OFF` | `TENANT_LIMITS` | `ALL`, exactly
  those three states (ADR-035 §14). Every committed environment is OFF, and
  `scripts/check-storage-enforcement-off.sh` fails CI if one is not. A non-OFF
  value is refused at startup unless ALL of these are set and consistent:
  `TESTINBOX_STORAGE_GLOBAL_LIMIT_BYTES` (*G*),
  `TESTINBOX_STORAGE_DECLARED_MAX_INGESTION_PROCESSES` (deploy surge INCLUDED:
  a rolling deploy that overlaps two gateways declares 2, so H doubles),
  `TESTINBOX_STORAGE_INBOX_SHARE`, the dedicated filesystem's declarations
  (`TESTINBOX_STORAGE_FS_*`: block size *B*, *O_max* from the qualification
  record, *G_F*, *D_budget*, *M*, *R_ops*, *C_fs*, *I_fs*, *A_obs*; they must
  satisfy `G_F + D_budget + P_F + M + R_ops ≤ C_fs` (with *P_F* the probe budget, and the monitor role and *A_obs* declared too), `R_ops ≥ max(5 % of C_fs, 2 GiB)`,
  one inode per block, and `H_F < G_F`; filesystem-containment contract,
  ADR-035 Amendment 2), and the declared backend identity
  (`testinbox.storage.backend-identity.*`: image index digest, platform member
  digest, release, commit id, mode, drive count, timeout environment and CLI
  flags, runtime admin-config hash, kernel release, filesystem type, mount
  options, storage-class defaults, direct path, proxy), which must EXACTLY
  match a qualification record shipped in the artifact that is
  enablement-eligible (ADR-035 §9a). The only shipped record today is the
  laptop one, and it is not eligible, so no artifact can currently enforce
  anywhere: that is the intended state until the production combination is
  qualified with `slow-W` (§18 gate 7a). OFF keeps starting with any of these
  absent, stale or changed — it is the requalification mode of the MinIO
  change rule.
- **Node identity, both deployables.** The API registers in `storage_node`
  too (it runs cleanup and the orphan sweep, so it is a protocol participant)
  and claims its `TESTINBOX_STORAGE_NODE_ID` with the same session lock as the
  gateway, so two API processes with one id cannot overlap either. The one
  asymmetry: a gateway that loses its claim opens its breaker (`451`); an API
  that loses its claim only logs, because the API admits no mail and two APIs
  sharing an id can only duplicate cleanup work that `SKIP LOCKED` already
  tolerates. A node id must not contain `:` (the session name cannot carry
  one); startup refuses it.
- **The declared inventory is REQUIRED under a non-OFF mode.** The barrier's
  positive inventory compares `TESTINBOX_STORAGE_EXPECTED_API_NODES` and
  `TESTINBOX_STORAGE_EXPECTED_INGESTION_NODES` (comma-separated, exact ids)
  with the healthy `storage-v1` rows; nothing is inferred from what happens to
  be heartbeating. A non-OFF node refuses to start when either list is empty,
  when its own id is not in its list, or when more ingestion nodes are declared
  than `declared-max-ingestion-processes` (each holds 16 write slots). Surge
  ABOVE the declared set is Ops's declaration; the application cannot observe
  it.
- **What a non-OFF node does with a broken barrier.** Each gateway re-checks
  the session allowlist and the inventory before its first `DATA` and then on
  every heartbeat (10 s); each API re-checks on every cleanup pass (30 s). A
  session of the application role outside the allowlist, a missing, stale or
  wrong-capability INGESTION node, or an undeclared node makes every gateway
  answer `451` until the check passes again; a missing API node raises
  `testinbox_storage_activation_violation` and is logged but does NOT turn mail
  away (an API deploy is not an old ingress instance). Alert on the gauge
  sustained for more than a minute, not on a blip.
- **Every session AS the application database role must be tagged under a
  non-OFF mode.** The allowlist is scoped to the application role, as ADR-035
  §14 (a) words it: a `pg_dump` under a backup role, a metrics exporter under
  its own role or a DBA's `psql` under theirs are not evaluated. A human or
  tool that connects AS the application role must set
  `PGAPPNAME=ops:<purpose>` (the latch runbook below included), or it reads as
  an old binary and every gateway defers mail while it is connected. Use a
  separate role for backups and exporters.
- **An API deploy under a non-OFF mode is visible, not blocking.** The API
  marks its generation clean on stop and re-registers on start; during a
  stop-then-start the inventory reports the API absent and the violation gauge
  blips (owner decision TI-STORAGE-006b: an absent API node is observed and
  alerted; mail may continue).
- **Ops prerequisite before staging `TENANT_LIMITS` (owner decision
  TI-STORAGE-006b): the reconciler's replacement model for BOTH deployables
  is declared and evidenced**, as one of exactly two shapes:
  1. **fixed node id → stop the old process before starting its replacement**
     (the id is claimed with a session lock; a second process with the same
     id refuses to start while the first lives); or
  2. **overlapping replacement → distinct declared node ids**, every one of
     them listed in `TESTINBOX_STORAGE_EXPECTED_*_NODES`, with
     `declared-max-ingestion-processes` counting the overlap.
  The evidence (the reconcile recipe and one observed replacement) goes in
  `production-ops-acceptance.md` row W. Nothing in this repository can observe
  the reconciler; the declaration is Ops's word.
- **An activation check that cannot be evaluated** (the database read fails,
  a malformed row) is itself a violation under a non-OFF mode: the gateway
  answers `451` until a later check completes and passes its admission-relevant gates; under OFF it is
  logged and observed only. A node that cannot prove the invariant never
  admits as though it held.
- **The activation barrier.** Before any `OFF → TENANT_LIMITS` change, Ops
  runs `scripts/check-storage-activation.sh` (docs/architecture/storage-activation.md)
  and records its evidence JSON in `production-ops-acceptance.md`. The
  rollback-floor gate (e) is expected to report BLOCKED until the ADR-035
  floors reach `master` through a release pull request.
- **The admission latch.** A late object sets `storage_admission_latch`, and
  every ingestion node then answers SMTP `451` before admission. The edge
  queues mail for up to 4 h. **Runbook:**
  1. Investigate the late object (`testinbox_storage_late_object_total`, and
     the `storage_late_object` error logs).
  2. Confirm the storage combination is still the qualified one.
  3. Clear the latch by hand: `DELETE FROM storage_admission_latch;` — from a
     session tagged `PGAPPNAME=ops:latch-runbook` when enforcement is not OFF,
     or the session itself trips the activation guard.
  4. **If the delete fails with `check_violation`** ("cannot be cleared while a
     late object refused by rule (P) is held"; V9, TI-STORAGE-006E PR D): a late
     object is still on disk because deleting it would breach containment
     (`testinbox_storage_held_late_objects` > 0). It is retried every 5 min and
     deleted once the potential allows — after the next observation, a debt
     compaction or a purge. Do not force it: clearing the latch while it is held
     would let slot exhaustion answer a recipient-dependent `451`.

  No endpoint clears it.

## Object storage privileges

The bucket is created by Ops before the first deployment. The runtime
identity holds object read/write/list/delete on that bucket and nothing
else. With `create-bucket: false` the adapter never attempts creation; an
absent or inaccessible bucket is a readiness failure
(`objectStorage: DOWN, NoSuchBucketException`) and every write fails loudly,
which the storage test suite proves against MinIO. Local development and the
rehearsal keep automatic creation because the store there is theirs.

## Human accounts and the controlled beta (ADR-036, ADR-037)

> **Amended by TI-DELIVERY-001/002 (ADR-036/ADR-037), 2026-10-30.** This
> document used to assume "no self-service signup": workspaces were
> operator-created and the only human was the owner. The accepted design is now
> native self-service sign-in with a bounded, first-come beta of at most thirty
> admitted participant accounts. The code ships in the artifact production runs
> (d17044d, #116) and is **dormant**: `TESTINBOX_ACCOUNTS_ENABLED` is off,
> `beta_cohort` has `admissions_open = capacity_ready = false`, and storage
> enforcement is `OFF`. Nothing in this section authorizes turning any of it on;
> that is the consolidated release decision of TI-DELIVERY-002.

### What the design is

- **Identity lives in the existing API and PostgreSQL** (ADR-036 §1). Google is
  identified by its validated issuer and immutable subject, GitHub by its
  numeric user id, email by an eight-digit code (ten minutes, five attempts,
  bound to the requesting browser). A provider's contact address is never an
  identity and never links two accounts.
- **The browser talks to the public site, not to the API host.** The GitLab
  `infinity/testinbox-web` site serves `/account/*` same-origin and forwards it
  server-side to the API (its `TESTINBOX_API_URL`). The application hostname
  routes only `/v1` to the API; there, `/account` reaches the Inspector, not the
  API. The proxy strips `Authorization`, forwarding headers and unrelated
  cookies, so until TI-DELIVERY-002's trusted client address (below) is
  configured, the API sees the site container as the source of every sign-in.
- **Authentication is not admission.** A signed-in, unadmitted account can read
  its own status and join or leave the waitlist; it cannot create inboxes,
  receive credentials or reach a workspace. Admission is one transaction
  (`AdmitBetaParticipant`, the sole workspace provisioner): lock the cohort,
  require `admissions_open`, require `capacity_ready` **and** the live
  storage-pool check, claim one of thirty numbered places, create the workspace,
  project and participant share marker. The console requests admission when an
  authenticated, unadmitted account opens it, so places go first-come.
  The cap of thirty counts **accounts, not people**: one person can hold
  several accounts and so several places. The owner accepted this as a beta
  limitation (TI-DELIVERY-002b decision 4); nothing in the code tries to
  detect it ([abuse model](../security/abuse-model.md#sybil-limitation-accepted)).
- **Sessions** are opaque 32-byte values in a `__Host-ti_session` cookie
  (Secure, HttpOnly, SameSite=Lax, Path=/, no Domain); only a SHA-256 verifier
  is stored. Seven days absolute, 24 hours idle, at most five per account.
  Mutations need the exact public `Origin` and a session-bound CSRF token.
  Every account response is `Cache-Control: no-store`; the boundary enables no
  CORS.
- **The owner/monitoring workspaces are not participants.** They hold no seat;
  under an enforcing pool they need explicit finite legacy shares
  ([cohort storage](cohort-storage.md)).

### Admission controls: three switches, from coarsest to finest

| control | where it lives | when off / false |
|---|---|---|
| `TESTINBOX_ACCOUNTS_ENABLED` (API) | API environment | every `/account/*` route, including the human product aliases, answers `503` (`accounts-unavailable`) with no cookie and no side effect. `/v1`, ingestion and the synthetics are unaffected. This is the kill switch: the console stops working for every participant until it is turned back on. It revokes nothing — stored sessions resume when it returns, and participants' SDK keys keep working throughout |
| `TESTINBOX_ACCOUNTS_ENABLED` (site) | site environment | the site's proxy answers `503` "Sign-in is unavailable" without calling the API, and the sign-in pages show the invitation-only copy. It must agree with the API's flag |
| `beta_cohort.admissions_open` | database row `beta-30` | sign-in still works; existing participants keep their workspaces; a new admission answers `CLOSED` and the console offers the waitlist. This is the **admission pause** |
| `beta_cohort.capacity_ready` | database row `beta-30` | a new admission answers `CAPACITY_UNAVAILABLE` |
| the approved storage pool | `storage_cohort_plan` (V21) + every node's effective declarations | a new admission answers `CAPACITY_UNAVAILABLE` even with both flags true: `capacity_ready` is necessary, never sufficient. The live check requires an approved, matching, non-overcommitted pool reserving all thirty shares, enforced by `ALL`-mode nodes — see [STORAGE-READINESS](../delivery/ti-delivery-002/STORAGE-READINESS.md) for why that is not yet reachable in production |

The thirty-place cap is a database constraint (`beta_admission.place BETWEEN 1
AND 30`). No configuration raises it. A suspended or deleted account does not
free its place. `beta_cohort` is retained by backups (`deploy/backup/scope.txt`),
so a restore brings back whatever the flags were at dump time; the pool approval
is not retained, so a restored database answers `CAPACITY_UNAVAILABLE` until
the pool is reserved again.

**Operator controls added by TI-DELIVERY-002.** ADR-036 §5 requires an
operator control to pause admissions and to suspend or revoke an account. These
are delivered by TI-DELIVERY-002 streams A and L as subcommands of the API
artifact (no HTTP endpoint, no public field).

**How to run an operator subcommand.** There is no `api.jar` on the host. The
API image's entrypoint is Spring Boot's launcher
(`java -cp . org.springframework.boot.loader.launch.JarLauncher`, see
`deploy/docker/backend.Dockerfile`), and arguments after the image reference
are passed straight to it. Run the subcommand as a one-off container of the
**same reviewed API digest**, on the network where PostgreSQL is reachable,
with its credentials in an env file:

```
docker run --rm --network <data network> --env-file /root/testinbox-ops/<command>.env \
  <registry>/testinbox-api@sha256:<api digest> account-lifecycle status <account-uuid>
# likewise: … <digest> admission status|pause|resume --open-beta
#           … -v /root/testinbox-ops/pool.json:/run/pool.json:ro <digest> storage-budget check /run/pool.json
```

- The env file is mode `0600`, owned by the operator, and holds only the
  `TI_ADMISSION_*`, `TI_LIFECYCLE_*` or `TI_BUDGET_*` variables (plus, for
  `account-lifecycle`, optionally the API's `TESTINBOX_EXACT_COOLDOWN`).
  Never put a password on the command line (`-e NAME=value`, a URL with
  credentials): argv is visible in `ps` and shell history.
- Never `docker compose run api …`: that inherits the API service's
  definition, including its Traefik labels and runtime environment.
- The subcommand is dispatched before Spring: no listener, scheduler, login
  provider or migration starts, and the container exits with the command.

The controls:

- admission pause / resume —
  `admission status|pause|resume --open-beta`, credentials only from
  `TI_ADMISSION_JDBC_URL`, `TI_ADMISSION_USER`, `TI_ADMISSION_PASSWORD` (no
  defaults; a URL carrying credentials is refused). It runs before Spring, so
  no listener or scheduler starts, and it rechecks the role's UPDATE privilege
  on `beta_cohort.admissions_open` inside the transaction — the runtime API
  role is refused. Pausing stops new admissions only: participants, sessions,
  keys, inboxes, ingestion and the waitlist continue, and a paused applicant
  gets the same CLOSED outcome as a never-opened cohort. It never touches
  `capacity_ready`. **Never run `resume` before the release decision:** it
  sets `admissions_open=true` even on a cohort that was never opened, which
  breaks Ops' T5 backstop (`admissions_open|capacity_ready = f|f`). The
  command therefore refuses `resume` unless it is given the explicit
  `--open-beta` flag. Details: [admission and source](../delivery/ti-delivery-002/ADMISSION-AND-SOURCE.md);
- account lifecycle —
  `account-lifecycle suspend|resume|delete|status <account-uuid>`,
  `account-lifecycle status`, `account-lifecycle process-deletions` and
  `account-lifecycle restore-reconcile`, credentials only from
  `TI_LIFECYCLE_JDBC_URL`, `TI_LIFECYCLE_USER`, `TI_LIFECYCLE_PASSWORD` (the
  owner of `human_account` or a member of it; the runtime API role is refused
  by the use case and by the V22/V23 row triggers). Exit codes: 0 done, 1 refused
  (nothing changed), 2 usage, 3 pending (the timer or a re-run continues it),
  4 failed with the outcome unknown (a timeout or lost connection: run
  `status` first), 5 a deletion is overdue (alert). Every
  operator transaction is bounded (5 s lock wait, 10 s per statement, 10 s
  idle), so a stalled operator never holds the T1 fence. Suspension and deletion permanently delete the
  account's sessions and revoke every managed key of its workspace; resume
  restores the state only, so the person signs in again and issues new keys.
  Ingestion treats the workspace as inactive from the T1 fence on (uniform
  `250`, nothing persisted). **Deletion is a resumable workflow**
  (TI-DELIVERY-002b): `DRAINING` (reservations from before the fence settle),
  `CONTENT_DELETING` (inboxes deleted through the existing deleter; the
  retention sweep removes rows and objects; EXACT cooldowns elapse), then
  `ERASED`: the contact email, login identities, sessions, waitlist entry,
  sign-in codes, key labels, idempotency records and chosen addresses are
  erased automatically in one transaction, once the content gate passes.
  The spent place, the participant marker, pending-cleanup charges and the
  revoked keys' technical rows are kept. Keep every
  `event=…` line it prints in a lifecycle log outside the database. After a
  restore, `restore-reconcile` does NOT read that log: it suspends every
  restored participant, revokes **every** restored managed key (owner and
  monitoring included), and the operator then replays the log
  by hand and re-keys the deployment (below). Procedure:
  [lifecycle](../delivery/ti-delivery-002/LIFECYCLE.md).

**Required Ops timer: `account-lifecycle process-deletions` every 15 minutes**
(TI-DELIVERY-002b). It advances every pending deletion one step and is the
only thing that completes deletions waiting on the retention sweep or an
EXACT cooldown. **Alert on any non-zero exit.** `5` means a deletion has been
unfinished for longer than the overdue threshold. The threshold is the EXACT
cooldown, plus the retention `T_max`, plus 24 h: **72 h** with shipped
values, or `TI_LIFECYCLE_DELETION_OVERDUE_AFTER`. Give the timer's env file
the API's `TESTINBOX_EXACT_COOLDOWN` and
`TESTINBOX_STORAGE_FS_RETENTION_PACING_MAX_DELAY` whenever they are
overridden. `account-lifecycle status` (no account) is the read-only view of
the same list. Unit example and triage:
[lifecycle](../delivery/ti-delivery-002/LIFECYCLE.md#the-ops-timer-for-process-deletions-required).

**After a restore everything restored is quarantined.** The clearance row of
`lifecycle_restore_clearance` is never in a backup (`-`). A restored
database therefore treats every participant workspace as inactive and every
human account as suspended, and refuses **every managed API key, owner and
monitoring ones included**, until `account-lifecycle restore-reconcile` runs.
That command closes admissions, suspends the restored participant accounts,
revokes every restored managed key in every workspace, and records the
clearance. Reconciliation only lifts the quarantine. The operator then
replays, by hand, every lifecycle event in the external log that is newer
than the backup, and re-keys:

1. **Before sign-in or registration is re-enabled — mandatory:** replay every
   `delete` and `suspend` event (a pre-002b `erase-identity` replays as
   `delete`), for participant **and non-participant** accounts alike. A
   non-participant account deleted after the backup is not suspended by
   reconciliation (it has no workspace), so without the replay it could sign
   in again once the boundary reopens. The deletion workflow erases the
   replayed deletions again.
2. Then `resume` only the participants the log shows active at the incident;
   they sign in again and mint new keys.
3. **Re-key the deployment.** Mint a new owner administrator with the
   bootstrap credential, then the new synthetic and monitoring keys with that
   administrator. Install them, and run the synthetics before declaring the
   restore complete ([credentials after a restore](#credentials-after-a-restore)).

### Deploying the account-lifecycle release (V22): required order

1. Stage the candidate `deploy/backup/scope.txt` and
   `scripts/check-backup-scope.sh` (they classify `lifecycle_restore_clearance`
   and accept the V22 view).
2. Run the migrator (V22).
3. Re-run `deploy/database/roles.reference.sql` as the schema owner. Both
   roles need it: ingestion gets `SELECT` on `workspace_lifecycle_eligibility`,
   and the API's `ON ALL TABLES` grant is one-shot, so it does not cover the
   new view and table until the file is re-run.
4. Deploy the API, then ingestion, then web, then run the synthetics.

Both deployables report **NOT READY** (`lifecyclePrivileges`
`OUT_OF_SERVICE`, reason `ELIGIBILITY_VIEW_UNREADABLE` or
`RESTORE_CLEARANCE_UNREADABLE`, and one log line on each change) when their
role cannot read what they need, instead of serving with every participant
key refused or every event answered `451`. Staging connects as the table
owner and always passes. The V22 authority checks and triggers add nothing
there either: the owner IS the operator authority.

### The API environment contract (`TESTINBOX_ACCOUNTS_*`)

Every name below is bound in `backend/api/src/main/resources/application.yaml`
(`HumanAccountEnvironmentTest` binds the shipped YAML). All are **API-only**:
never give them to ingestion or the migrator, never to the site except where
the site column says so.

| variable | default | required value when enabled | secret |
|---|---|---|---|
| `TESTINBOX_ACCOUNTS_ENABLED` | `false` | `true` only by the release decision | no |
| `TESTINBOX_ACCOUNTS_PUBLIC_ORIGIN` | empty | the site's canonical origin, exactly `https://<host>` — lowercase host, no path, no trailing slash, no query, no userinfo, no explicit `:443`. Never derived from `Host` or forwarded headers. Same value on the site | no |
| `TESTINBOX_ACCOUNTS_WAITLIST_RETENTION` | `30d` | 1–90 days; the owner's retention decision | no |
| `TESTINBOX_ACCOUNTS_WAITLIST_MAXIMUM` | `1000` | 1–1000 entries | no |
| `TESTINBOX_ACCOUNTS_EMAIL_ENABLED` | `false` | `true` to offer email sign-in | no |
| `TESTINBOX_ACCOUNTS_EMAIL_VERIFICATION_KEY_BASE64` | empty | base64 of exactly 32 random bytes; keys the code, browser and source verifiers | **yes** |
| `TESTINBOX_ACCOUNTS_EMAIL_SMTP_HOST` | empty | the approved transactional provider's hostname | no |
| `TESTINBOX_ACCOUNTS_EMAIL_SMTP_PORT` | `587` | `587` — nothing else is accepted | no |
| `TESTINBOX_ACCOUNTS_EMAIL_SMTP_USERNAME` | empty | submission credential | **yes** |
| `TESTINBOX_ACCOUNTS_EMAIL_SMTP_PASSWORD` | empty | submission credential | **yes** |
| `TESTINBOX_ACCOUNTS_EMAIL_SMTP_FROM` | empty | the approved, authenticated sending address | no |
| `TESTINBOX_ACCOUNTS_OAUTH_STATE_KEY_BASE64` | empty | base64 of exactly 32 random bytes; encrypts the PKCE/nonce state. Rotating it invalidates sign-ins in progress | **yes** |
| `TESTINBOX_ACCOUNTS_OAUTH_GOOGLE_ENABLED` | `false` | `true` to offer Google | no |
| `TESTINBOX_ACCOUNTS_OAUTH_GOOGLE_CLIENT_ID` / `_CLIENT_SECRET` | empty | the Google OAuth client | secret: **yes** |
| `TESTINBOX_ACCOUNTS_OAUTH_GITHUB_ENABLED` | `false` | `true` to offer GitHub | no |
| `TESTINBOX_ACCOUNTS_OAUTH_GITHUB_CLIENT_ID` / `_CLIENT_SECRET` | empty | the GitHub OAuth app | secret: **yes** |
| `TESTINBOX_ACCOUNTS_CLIENT_ADDRESS_SECRET` | empty | **added by TI-DELIVERY-002 (stream A).** Shared with the site; lets the API trust a client address the site attests, so visitors stop sharing the site container's sign-in budget. 32–512 bytes of printable ASCII, no whitespace, at least 12 distinct characters (`openssl rand -hex 32`). **Unset:** one shared sign-in budget for every visitor. **Set but invalid:** `/account` closes like an invalid origin (`/v1` keeps serving) and one error names the setting. **Mismatched with the site:** each request falls back to the shared budget (`testinbox_account_client_address_total{outcome="unauthenticated"}`) | **yes** |

The site's side of the contract (its own repository is authoritative):
`TESTINBOX_ACCOUNTS_ENABLED`, `TESTINBOX_ACCOUNTS_PUBLIC_ORIGIN` (same value as
the API), `TESTINBOX_API_URL` (server-side API origin, never exposed to the
browser), and — **added by TI-DELIVERY-002 (stream W)** —
`TESTINBOX_TRUSTED_PROXY_CIDRS` (the addresses of the edge hops whose
forwarded client address the site may believe) and the same
`TESTINBOX_ACCOUNTS_CLIENT_ADDRESS_SECRET`, plus `TESTINBOX_TRUSTED_PROXY_HOPS`
(1 behind Cloudflare → Traefik). `TESTINBOX_TRUSTED_PROXY_CIDRS` is a
comma-separated list of IPv4/IPv6 CIDRs, each with a prefix length:
**Cloudflare's published ranges only**, kept in sync with Ops'
`EDGE_TRUSTED_IPS`. The Traefik → site hop is the site's TCP peer and never
appears in `X-Forwarded-For`, so the Traefik/docker network must NOT be
listed. Traefik's entrypoint trusts forwarded headers only from
`EDGE_TRUSTED_IPS`; the public site router has no Cloudflare-only
middleware, so a direct, non-Cloudflare request does reach the site, but
with `X-Forwarded-For` holding only its own peer address. That entry is not
in a trusted range, so the site sends no client address and the visitor
falls back to the shared budget (safe). **Ops action before registration:**
add the `testinbox-cf-only` middleware to the site router. The site skips exactly `HOPS`
rightmost `X-Forwarded-For` entries, each of which must be trusted, and sends
the next one as `X-TestInbox-Client-Address` with `X-TestInbox-Proxy-Secret`.
If a hop is untrusted, the candidate is malformed or itself trusted, or any of
the three settings is missing or invalid, it sends neither header and the API
uses the shared budget ([abuse model](../security/abuse-model.md)).

**Misconfiguration fails closed, not loud.** `DeploymentSafety` does not refuse
to start over account settings: a production process must keep serving `/v1`
whatever the human boundary's state. An invalid public origin closes every
account route (`503`); an incomplete email configuration (bad key length,
wrong port, missing credential or sender) closes email sign-in only; an
incomplete provider closes that provider only, and it silently disappears from
`GET /account/methods`. Watch:

- `testinbox_email_login_configured` (`1` = syntactically ready, not proof of
  delivery);
- `testinbox_provider_login_configured{provider="google|github"}`;
- `GET /account/methods` through the site;
- `npm run test:accounts`, which compares the advertised methods with the ones
  the environment declares (below).

### OAuth callbacks

Register exactly these redirect URIs with each provider, built from
`TESTINBOX_ACCOUNTS_PUBLIC_ORIGIN` — never the application hostname, never a
wildcard:

```
https://<public origin>/account/oauth/callback/google
https://<public origin>/account/oauth/callback/github
```

Scopes are fixed in code: Google `openid email`, GitHub `read:user user:email`.
Both use the authorization-code flow with PKCE S256; Google also carries a
nonce. The callback query carries a one-time `code` and `state`: the site and
every edge hop in front of it must not retain callback query strings in access
logs (an Ops acceptance item).

### Sign-in email: the sending identity

TestInbox is inbound-only for tenants; the sign-in code is the only mail it
originates. It goes through a **dedicated transactional submission service**,
never the inbound mail edge and never the forwarding aliases:

- port 587 with mandatory STARTTLS, TLS 1.2/1.3, server hostname verification,
  SMTP AUTH, five-second connect/read/write timeouts (`SmtpLoginDelivery`);
- a fixed plain-text body: the code, its ten-minute lifetime, a do-not-share
  line. No link, no tracking, no user-supplied text;
- the `From` address must be one the provider is authorized to send for (SPF,
  DKIM, DMARC alignment on its domain). The application only checks that the
  address is well-formed: alignment and the provider's own sending budget are
  Ops/owner evidence;
- a failed delivery has the same public result as a sent one (no enumeration)
  and increments `testinbox_email_login_total{outcome="delivery_failed"}`. Alert
  on it; a successful HTTP response is not proof a code was sent.

The sending budgets (global 200 per hour, 15 per source per 15 minutes, 3 per
recipient per 15 minutes, a 60-second resend gap) are in
[abuse-model.md](../security/abuse-model.md).

### Secrets handling

Every secret above goes into the Ops secret store and the API's environment
only. Never into Git, an image, a build argument, a workflow output, the site's
environment (except `TESTINBOX_ACCOUNTS_CLIENT_ADDRESS_SECRET`), the ingestion
or migrator environment, a chat or a ticket. The configuration classes redact
their `toString`; `org.springframework.security.oauth2` and
`org.springframework.web.client` log at `WARN` so token exchanges never reach a
debug log; provider errors, codes, claims and tokens are never logged or put in
a redirect. Rotation: change the value and restart the API. Rotating the email
verification key invalidates outstanding codes; rotating the OAuth state key
invalidates sign-ins in progress; neither touches sessions or API keys.

### Storage prerequisites before the first admission

Admission is closed by construction until the cohort storage pool is approved
and enforced ([cohort-storage.md](cohort-storage.md), ADR-037). The full
ordered procedure, what is already proven by tests and what only Ops can show
is [STORAGE-READINESS](../delivery/ti-delivery-002/STORAGE-READINESS.md). Do
not set `capacity_ready` as a substitute for it; the use case ignores it
without the live pool.

## Promotion and handoff

The full model is [ADR-034 §3](../adr/0034-production-platform-and-promotion.md).
In operation:

1. **Release pull request `develop` → `master`.** `Release candidate` runs
   three legs — candidate identity, vulnerability policy, migration rollback
   safety — and one aggregate, **`Production promotion gate`**, the context
   `master` requires. Candidate identity resolves the four digests for the
   head commit and verifies each attests to that commit from
   `build-images.yml`; it uploads `release-manifest.json`.
2. **Merge** is a human decision and *is* production approval of that source
   commit. It deploys nothing.
3. **`Production handoff`** (`workflow_dispatch`, on `master`, environment
   `production-handoff` with required reviewers) takes the candidate SHA,
   re-verifies it — merged from `develop` into `master`, artifacts exist,
   provenance binds, rollback floors — and hands the digest set to Ops with
   `TESTINBOX_ENVIRONMENT=production`. Green means **accepted**, not deployed.
4. **Ops** confirms the digest set is the one its staging reconcile proved,
   then reconciles, migrates, waits for readiness and runs the synthetics.
   The production verdict lives there.

Nothing in 1–3 builds, pushes, or holds a host credential. What GitHub asserts
is: built, attested, merged, verified. What only Ops can assert is: staging
proved these bytes; production runs them.

Two limits of the GitHub side, stated plainly. A `pull_request` workflow
runs the pull request's **own** copy of the file, so the required status
check guards against mistakes, not against a contributor who rewrites the
gate in the same pull request; the hardened form is a repository ruleset
requiring the workflow from `master`'s copy, possible once `master` holds it
(see [release-process.md](release-process.md#master)). And a GitLab trigger
token is **project-scoped**: the staging token could request a pipeline on
the production ref with any variables, so the production boundary is
GitLab's protected ref and the Ops pipeline deriving the environment from the
ref it runs on — never from `TESTINBOX_ENVIRONMENT` — which is acceptance
row H.

### Required GitHub configuration — HUMAN ACTION

| item | value |
|---|---|
| Environment `production-handoff` | **exists** (created 2026-09-19): deployment branches restricted to `master`, required reviewer `yannisyoussef`. Verify with `gh api repos/yannisyoussef/testinbox/environments/production-handoff` — this restriction, not the workflow's own `if:`, is what stops a branch that edits the workflow from dispatching it |
| secret `GITLAB_TRIGGER_TOKEN` on it | a trigger token for the production pipeline; can start one pipeline and nothing else |
| variable `GITLAB_OPS_API_URL` | the Ops instance API root; no fallback, or the token would be posted to gitlab.com |
| variable `GITLAB_OPS_PRODUCTION_REF` | the Ops ref whose pipeline deploys production; no fallback |
| variable `GITLAB_OPS_PROJECT` | optional; defaults to `infinity%2Finfinity-core` |
| `master` branch protection | see [release-process.md](release-process.md#master) — applied only after the gate is proven |

## Rollback

Artifact rollback is a `Production handoff` with the previous approved
candidate SHA: the same verification, the same digests it had, no rebuild.
The previous set is recorded in the earlier handoff's run summary and
manifest artifact on GitHub — both subject to the repository's log/artifact
retention (90 days by default) — and durably in Ops's own reconcile record.
The genuinely durable identity is the candidate **SHA**: as long as the
`:sha` tags and their attestations remain in GHCR, the verifier
reconstructs the digest set from it. Ops follows every rollback with
readiness and the synthetic suite.

`deploy/rollback-floors.txt` names commits a candidate must contain. The
first is the V4 credential lifecycle (TI-002, `a2cb7ecc`): an artifact from
before it starts cleanly against the newer schema and then answers `401` to
every managed key — a technically startable rollback that is an outage for
every API client ([rollback.md](rollback.md)). The verifier refuses such a
candidate unless `acknowledge_rollback_hazard` is set, and then still warns
in the log. Database rollback is not automated and will not be.

## Backup and restore — the data-lifecycle contract

Ops owns the backup runtime. The application owns **what a backup may
contain**, because a backup that copies everything would keep message content
alive past the ADR-009 TTL and past explicit deletion, in a copy nobody
promised.

`deploy/backup/scope.txt` classifies every table; `scripts/check-backup-scope.sh`
checks a real dump (or a `pg_restore -l` table list, produced with
`pg_restore -l dump | awk '/ TABLE DATA / {print $7}'`) against it, and
`check-backup-scope.test.sh` proves it refuses content rows — however they
are spelled — a missing control-plane table, an unclassified table, and a
backup that examines nothing. A plain dump is read statement by statement:
the `INSERT INTO` lines inside the ADR-035 trigger-function bodies (which a
full-schema dump carries) retain nothing, while anything the reader cannot
account for — an unterminated function body, string or `COPY` block, a
truncated statement — is refused rather than ignored. The suite proves both
on a genuine `pg_dump` of a database migrated with the real `V1..V10` files,
in every documented dump form, so it needs Docker and fails without it.

What the gate is, and is not. It reads exactly the statements a plain
`pg_dump` contains and refuses everything else: a statement that would create
or fetch rows at restore (`CREATE TABLE ... AS`, `SELECT ... INTO`, `MERGE`, a
data-modifying CTE, `DO`, a materialized view, a subscription, a rule), one
that would evaluate a user function at restore (a schema-qualified call
outside a function or trigger definition, a non-empty `search_path`), table
data placed after a trigger definition, a `COPY` not from `stdin`, psql
variables and includes. That catches a misconfigured or hand-edited dump and
refuses what it cannot read. It is not a sandbox against a hostile dump
author: a restore executes SQL, and no lexer can prove a trigger-bearing
schema plus control-plane rows free of side effects. The restore drill of
row B is where that evidence lives — after the drill, `message`, `inbox` and
`attachment` are counted and must be empty.

**Only logical, table-scoped backups satisfy this contract.** Physical
backups and WAL archiving (`pg_basebackup`, continuous archiving) copy every
row of every table by construction, have no table filter, and leave nothing
for the gate to examine; they are not an option. The dump is produced with
the full schema and `pg_dump --exclude-table-data` for every `-` table — the
only accepted form — and object storage is excluded by the backup job's
configuration, which row B requires Ops to show alongside a listing of the
backup target. A `--table` dump of only the `+` tables is **not** accepted:
it omits the DDL of every other table, including
`lifecycle_restore_clearance`, so restoring it means re-running migrations,
and V22 would then seed the restore clearance before the data loads,
leaving the restored database unquarantined.

| backed up | never |
|---|---|
| `workspace`, `project`, `api_key`, `exact_address_reservation`, `flyway_schema_history` | `inbox`, `message`, `attachment`, `idempotency_record`, `rate_bucket`, `wait_lease`; the ADR-035 tables `workspace_storage_account`, `inbox_storage`, `storage_delta`, `storage_reservation`, `storage_ambiguity`, `storage_node`, `storage_admission_latch`, `storage_clock_episode` (derived or transient: after a restore `message` is empty, so empty accounting is correct); **all object storage** |

> **Amended by TI-DELIVERY-001 (ADR-036/ADR-037), recorded 2026-10-30.** The
> table above is the TI-006 set. `deploy/backup/scope.txt` is authoritative and
> now also retains the identity/admission control plane (`human_account`,
> `human_identity`, `beta_cohort`, `beta_admission`, `participant_workspace`,
> `storage_legacy_share`) and excludes the transient human-login, waitlist and
> cohort-storage evidence tables. A restore therefore brings back accounts and
> their admissions but no browser session, no pending code, no waitlist entry
> and no pool approval; reconciling deletions and revocations made after the
> dump is part of the TI-DELIVERY-002 lifecycle work (stream L).

What that buys, stated plainly:

- **Disaster recovery keeps identity.** Workspaces, projects and every
  credential verifier survive; a tenant's CI keeps authenticating after a
  restore. No plaintext credential is recoverable from a backup (ADR-032 §4).
- **Retention cannot silently widen.** Nothing a sender or tenant was
  promised would be deleted exists in a backup.
- **A restore cannot dangle.** No message row is restored, so none can point
  at a raw object that was never backed up; the orphan sweep handles the
  reverse.
- **Reservations survive**, so an `EXACT` local-part in cooldown stays in
  cooldown across a restore (ADR-021).

**The restore drill** (Ops, required before dark deployment): restore the
backup into a disposable database, run the *current* migrator (it must apply
nothing). **Before any deployable starts**, run
`SELECT count(*) FROM lifecycle_restore_clearance;` — it must be `0`; if it is
not, stop: the dump was not the accepted form and the restore is not
quarantined. Then start an API node against it and confirm that a managed
key of a **legacy** (owner/monitoring) workspace that existed at backup time
is **refused** (`401`: no managed key authenticates while quarantined,
TI-DELIVERY-002b). Run `account-lifecycle restore-reconcile`, confirm its
`legacyRevokedKeys=` count, mint a fresh administrator with the bootstrap
credential, and confirm that `message` and `inbox` are empty. A drill that
checks only "restore exited 0" is not a drill.

### Credentials after a restore

A database restore brings back credential **verifiers**, never plaintext
(ADR-032 §4). It also brings back their revocation state as of the backup.
TI-DELIVERY-002b decision 2: no restored credential is trusted.

| credential | where it lives | after a restore | Ops action |
|---|---|---|---|
| participant managed keys | `api_key` (verifier) | refused while quarantined; revoked by `restore-reconcile` | none; participants mint new keys after `resume` |
| owner administrator, production synthetic, monitoring keys (managed) | `api_key` (verifier) | refused while quarantined; **revoked** by `restore-reconcile` (`legacyRevokedKeys=`) | mint a new administrator with the bootstrap credential, then new synthetic/monitoring keys; replace `TESTINBOX_API_KEY` / `TESTINBOX_ADMIN_API_KEY` in the synthetic runner; run the synthetics |
| `TESTINBOX_BOOTSTRAP_API_KEY` | Ops secret store; the database holds only a `kind = BOOTSTRAP` verifier row | **not** rotated or revoked by reconciliation. It authenticates only when the presented token matches the configured value, and only while no usable managed administrator exists, so after reconciliation it is the break-glass that re-keys the owner | confirm it is configured **before** reconciling (without it, the owner workspace has no way back into key management). Rotate it (change the value, restart the API) if the incident could have exposed it, then retire it again by minting the administrator |
| browser sessions, sign-in codes, OAuth state | never backed up | nothing to revive | none |
| `TESTINBOX_DB_*`, `TESTINBOX_S3_*`, `TESTINBOX_ACCOUNTS_EMAIL_VERIFICATION_KEY_BASE64`, `TESTINBOX_ACCOUNTS_OAUTH_STATE_KEY_BASE64`, `TESTINBOX_ACCOUNTS_EMAIL_SMTP_*`, OAuth client secrets, `TESTINBOX_ACCOUNTS_CLIENT_ADDRESS_SECRET`, TLS, `GITLAB_TRIGGER_TOKEN` | Ops secret store / providers; never in the database | untouched by a database restore, neither revived nor rotated | rotate per the table below **if the restore follows a compromise**; otherwise nothing |
| `TI_LIFECYCLE_*` operator credentials | Ops `0600` env file; a database role | the role and its password live in the cluster, not the dump | verify the role still holds the operator authority after the restore |

Erasure and backups: an account erased after a backup was taken is still in
that backup (contact email, login identities, key labels, EXACT local parts)
until the backup ages out under the retention period chosen below. Replaying
the lifecycle log after a restore erases it again; nothing erases it from the
backup itself.

### HUMAN DECISION REQUIRED

Not decided by this increment and not decidable by software:

| decision | options | consequence |
|---|---|---|
| **Backup retention period** for the control-plane dump | 7 days · 30 days · 90 days | Longer keeps more restore points and holds credential *verifiers* and reservation history longer; nothing in the set is message content, so the privacy cost is bounded to tenant identity and hashed credentials. Any period above the 24 h `EXACT` cooldown (ADR-021) means a restore can revive a reservation whose cooldown has since elapsed — acceptable, but say so |
| **RPO** (how much control-plane change may be lost) | daily dump · hourly dump · dump on every credential change | Determines how recently created workspaces, projects and keys survive a disaster. Continuous archiving is not an option: it copies content by construction (above). Message content is out of scope of RPO by design |
| **RTO** | hours · one hour · minutes | Determines whether Ops needs a rehearsed, scripted restore or a documented manual one; the drill above is the minimum for any answer |

## Secrets — names, not values

| secret | held by | rotation |
|---|---|---|
| `TESTINBOX_DB_USER` / `TESTINBOX_DB_PASSWORD` | Ops | rotate at the database, then the stack; no API-visible effect |
| `TESTINBOX_S3_ACCESS_KEY` / `TESTINBOX_S3_SECRET_KEY` | Ops, scoped to the bucket, **no `CreateBucket`** | rotate at the store, then the stack |
| `TESTINBOX_BOOTSTRAP_API_KEY` | Ops; break-glass only ([api-keys.md](api-keys.md#bootstrap-and-the-first-key)) | change the value and restart; retires the previous one on the next request |
| production synthetic credential | Ops; a **managed** key with `inboxes:write`, `messages:read` only | revoke and mint through the API; never the bootstrap key |
| production administrative credential (`api-keys:manage`) | Ops; **distinct** from the synthetic one, never on a runner | as above |
| `GITLAB_TRIGGER_TOKEN` (production) | GitHub `production-handoff` environment; project-scoped in GitLab — the production ref's protection is what bounds it | rotate in GitLab, update the environment |
| TLS / origin certificate | Ops, at the edge | not application-owned |
| `TESTINBOX_ACCOUNTS_EMAIL_VERIFICATION_KEY_BASE64`, `TESTINBOX_ACCOUNTS_OAUTH_STATE_KEY_BASE64` | Ops; API only; 32 random bytes each, distinct | change and restart; invalidates codes / sign-ins in progress only ([accounts](#secrets-handling)) |
| `TESTINBOX_ACCOUNTS_EMAIL_SMTP_USERNAME` / `_PASSWORD` | Ops; API only; the transactional provider's submission credential | rotate at the provider, then the API |
| `TESTINBOX_ACCOUNTS_OAUTH_GOOGLE_CLIENT_SECRET`, `TESTINBOX_ACCOUNTS_OAUTH_GITHUB_CLIENT_SECRET` | Ops; API only | rotate at the provider console, then the API |
| `TESTINBOX_ACCOUNTS_CLIENT_ADDRESS_SECRET` (TI-DELIVERY-002) | Ops; API **and** site, the same value | set the new value on both and restart both; while they differ, sign-ins fall back to the shared budget (degraded, never spoofable) |

Rules the repository enforces or proves: no secret in Git (gitleaks), none
in an OCI label, build arg or layer (the Dockerfiles carry only source
repository, revision and version), none in a workflow output or step summary
(the handoff tests assert the token is never printed), and every startup
refusal names the setting, never the value.

## Observability — the alerting contract

Production has no Prometheus, Grafana or Loki yet; this repository builds
none. It states what must be observed, over metrics that already exist on the
private management ports (`docs/architecture/observability.md`), plus Spring
Boot's standard binders, whose presence the rehearsal asserts:

| signal | source | alert when |
|---|---|---|
| process liveness | `/actuator/health/liveness` | not `UP` |
| application readiness | `/actuator/health/readiness` per deployable | not `UP` for > 1 min; inspect `schema`, `objectStorage`, `waitNotifier`, `dbSession`, `smtpListener` |
| deployment identity | `testinbox_build{service,git_sha}` | `git_sha` ≠ the handed-off candidate |
| schema compatibility | readiness `schema` | `OUT_OF_SERVICE` — migration did not run or history holds a failure |
| object store / database reachability | readiness `objectStorage`, `db`; `testinbox_object_storage_operation_duration_seconds{outcome="FAILURE"}` | any `DOWN`; failures > 0 sustained |
| LISTEN degraded | `testinbox_wait_listen_degraded_polling` | `== 1` for > 1 min — **the one that is otherwise invisible** (everything else stays green) |
| storage accounting drift (ADR-035) | `testinbox_storage_accounting_drift_total{direction}`; `testinbox_storage_reconciliation_total{outcome="failed"}` | any increase: a repaired drift is always a defect, and a failed reconciliation leaves the ledger unproven |
| storage breaker open (ADR-035) | `testinbox_storage_breaker_open` | `== 1` for > 5 min on any ingestion node: mail is being deferred (`451`) |
| **storage admission latched** (ADR-035) | `testinbox_storage_admission_latched`; `testinbox_storage_late_object_total` | **page**: any late object, or the latch set. Every node refuses mail until an operator clears it (runbook above) |
| **filesystem observation stale** (TI-STORAGE-006E, observational while `OFF`) | `testinbox_storage_filesystem_observation_age_seconds`; `testinbox_storage_footprint_bytes{kind}` | **alert**: age `< 0` (never observed) or above the declared maximum (proposed 15 min), since only an observation ever lowers the deletion-debt estimate; `kind="deletion_debt"` growing without the age falling means the purge is not being verified |
| **retention backlog** (TI-STORAGE-006E PR D, paced under `ALL` only) | `testinbox_storage_retention_backlog_seconds` | **alert** above one inbox TTL: physical teardown lags logical expiry (which never waits). Escalate per runbook: raise `D_budget` within I-C, or investigate the purge. Past `testinbox.storage.filesystem.retention-pacing-max-delay` (*T_max*, default 24 h) teardown proceeds anyway and logs `storage_retention_t_max_exceeded` |
| **storage full** (TI-STORAGE-006E) | `testinbox_storage_physical_failure_total{kind="storage_full"}`; `testinbox_storage_breaker_open` | **page**: MinIO answered `507 XMinioStorageFull` or a `500` naming ENOSPC. The node answers `451` to every `DATA` and does NOT recover on a timer or a zero-byte probe (a full filesystem accepts both): it trials one real event only once a filesystem observation that BEGAN AFTER the trip is younger than *A_obs* and shows `avail ≥ R_ops` and at least `R_ops / B` free inodes; each failed trial needs a newer observation. Without a monitor writing observations, only a restart clears it. Runbook: free space (purge `.minio.sys/tmp/.trash`, retention), then let the monitor record an observation |
| old ambiguity / old RELEASING (ADR-035) | `testinbox_storage_ambiguous_uploads`; `testinbox_storage_reservations{state="releasing"}` | ambiguity older than `T_verify` + 5 min, or `RELEASING` rows older than 1 h: verification or cleanup is stuck (for example, the witness is failing: `testinbox_storage_witness_failed_total`) |
| physical over-coverage (ADR-035) | `testinbox_storage_physical_listed_bytes` against committed + reserved | `physical_listed > covered + H`: objects exist that nothing accounts for |
| incomplete multipart (ADR-035) | `testinbox_storage_incomplete_uploads` | `> 0`: TestInbox never starts one; the orphan sweep aborts it and it is a defect to explain |
| storage clock offset (ADR-035) | `testinbox_storage_clock_offset_seconds` | `abs(offset) > 15 s` (`ε_max / 2`): releases suspend at 30 s |
| storage ledger backlog (ADR-035) | `testinbox_storage_ledger_unfolded_rows` (every API replica reports the same global figure, so take `max`, never `sum`); `testinbox_storage_ledger_compaction_total{outcome="failed"}` | backlog > 1 000 sustained, or failed compactions with no `ok` in 5 min: compaction is not keeping up, or is failing. T1 reads the backlog on every event, so its cost grows with it. |

The **edge queue-age and deferred-mail alerting** of ADR-035 §12 is an Ops
prerequisite owned by the mail edge's operators. It is not in this
repository, and it is not claimed as done. Public SMTP/MX stays blocked on it.
| inbound SMTP | `testinbox_smtp_accept_total`, `testinbox_smtp_reject_total{reason}` | reject rate rising; accepts flat while the edge queue grows (TI-007) |
| unknown-recipient discards | `testinbox_smtp_unknown_recipient_discard_total` | rate change — an enumeration attempt or a misrouted sender |
| ingestion rate refusals | `testinbox_rate_decision_total{category="INGEST",outcome="REFUSED"}` | sustained refusals on one workspace |
| wait latency | `testinbox_wait_request_duration_seconds{outcome}` | p99 of `MATCHED` above 1 s while degraded polling is 0 |
| HTTP errors | `http_server_requests_seconds_count{status=~"5.."}` | 5xx ratio > 1 % over 5 min |
| saturation | `jvm_memory_used_bytes`, `hikaricp_connections_active/pending`, `process_cpu_usage` | pool pending > 0 sustained; heap > 85 % of max |

Every label is a closed enum; no metric carries an API key, address, subject,
local-part, body, or identifier. **The scrape endpoint is a trust boundary**:
an environment that exposes `/actuator/prometheus` more widely than its SMTP
listener has weakened ADR-025.

## Origin isolation

Required: the approved ingress path reaches TestInbox; a direct connection to
the origin's own address does not. The repository does not own the host
firewall and holds no OVH address. It ships the invariant as
`deploy/synthetic/origin` (`npm run test:origin`), run from **outside** the
host with the address supplied by Ops at run time as an IP literal, probing
every trust-boundary port (443, 80, 25, 2525, 9090, 9091, 5432, 9000), with a
mandatory positive control that reaches the public hostname **over the same
address family** — so an IPv6 probe from an IPv4-only runner cannot pass for
free. The result is Ops acceptance evidence; the `DOCKER-USER` chain being
non-empty is not, and no test from outside can prove the probed address *is*
the origin — that remains Ops's to show.

## Synthetics for a dark deployment

The first production deployment is verified against
[production-smoke-test.md](production-smoke-test.md): seventeen checks, each
mapped to an existing synthetic target or a named manual step, and the
deployment report that names the source commit and the four digests.

Run by Ops after the production reconcile, from the paths that can reach
each target. No public Postfix edge is required or exercised.

| suite | path | proves |
|---|---|---|
| `npm test` (deployment gate) | on the host: public HTTPS + private SMTP | create, deliver, wait, retrieve, cleanup; a full 60 s window through the real ingress; a parked wait woken by LISTEN; edge invariants incl. `/actuator` not routed and unknown Host refused |
| `npm run test:product` | on the host, with the administrative credential | credential lifecycle, idempotency |
| `npm run test:identity` | on the host, management ports | both deployables run the approved commit and are ready; LISTEN live; DB session bound reported everywhere and, in production, **enforced and bounded** |
| `npm run test:origin` | from outside | direct-origin isolation |
| `npm run test:accounts` | anywhere that reaches the public site (TI-DELIVERY-002) | the account boundary answers exactly the state the environment declares: closed everywhere while `TESTINBOX_ACCOUNTS_ENABLED` is off (and the sign-in pages advertise no places); when on, the declared methods, private caching, generic refusals, Origin enforcement, OAuth redirect shape and refused non-addresses. Consumes no seat, sends no email, creates no account or session ([README](../../deploy/synthetic/README.md#account-boundary-testaccounts)) |

## Known limits of this contract

- A second API node re-opens the readiness cascade ADR-030 flags: shared
  dependencies in readiness remove every node at once. Revisit before any
  multi-node production.
- A managed database must expose a session-mode endpoint; the pooled kind
  breaks LISTEN silently (ADR-030 capability 2).
- ~~Self-service signup invalidates the "workspaces are operator-created"
  assumption behind the idempotency-record bound (ADR-033) and changes the
  backup-volume picture.~~ **Resolved by ADR-036/ADR-037 (TI-DELIVERY-001),
  recorded 2026-10-30.** Self-service sign-in exists (dormant), but workspace
  creation stays bounded: at most thirty participant workspaces, one per
  admitted account, created only by `AdmitBetaParticipant` under a database
  constraint, plus the operator-created legacy ones. The ADR-033 flow bound
  therefore scales by a fixed 30 + legacy workspaces, not by signups, and
  ADR-027's "organization-level ceiling" is the cohort cap itself. The backup
  now also retains `human_account`, `human_identity`, `beta_cohort`,
  `beta_admission`, `participant_workspace` and `storage_legacy_share` — that
  is personal data (contact addresses), which the backup retention decision
  below must account for — and excludes every session, challenge, provider
  state, waitlist, request-budget, pool-approval and cleanup-charge table
  (`deploy/backup/scope.txt`).
- Retention, RPO and RTO are undecided (above).
