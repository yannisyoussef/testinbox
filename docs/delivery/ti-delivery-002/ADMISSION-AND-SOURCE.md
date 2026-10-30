# Admission controls and trusted client address (stream A)

TI-DELIVERY-002, stream A, under accepted ADR-036. This document records
implemented, locally verified behaviour. It does not open admissions,
enable sign-in or change any deployed environment.

## 1. The thirty-place cap

### What enforces it

Admission is a separate step from sign-in. Google, GitHub and email codes
only create an account and a session. None of them admits anyone. The only
participant provisioner is `AdmitBetaParticipant` (an ArchUnit rule enforces
this), and the only way to reach it is `POST /account/admission` with an
authenticated session. So the sign-in method cannot affect the cap.

One transaction does the admission:

1. `SELECT … FROM beta_cohort … FOR UPDATE` (the cohort row lock), then the
   `(35,1)` advisory lock.
2. Lock the account row and recheck that it is eligible.
3. If the account already has an admission, return that admission unchanged.
4. Require `admissions_open`, `capacity_ready` and live storage readiness.
5. Claim `max(place)+1`, and create the workspace, project, membership and
   durable share together.

PostgreSQL enforces the cap independently of the application. The
`beta_admission.place` column has a CHECK of `BETWEEN 1 AND 30`. The
`(cohort_id, place)` primary key prevents two admissions from taking the same
place. Each of `account_id`, `workspace_id` and `project_id` is UNIQUE. The
`beta_cohort.id` CHECK allows only `beta-30`, so a second cohort cannot exist.
Places held by deleted accounts stay consumed as tombstones.

### What was verified (no fix was needed)

No path could exceed thirty places or admit an account twice. New tests, all
on real PostgreSQL. They synchronize on latches and on observed lock waiters,
never on sleeps:

- `AdmissionAcrossSignInMethodsApiTest` (HTTP). Twenty places are already
  filled. Five Google, five GitHub and five email-code sessions each send
  `POST /account/admission` twice at the same moment. Exactly ten distinct
  accounts are admitted. Both answers for an admitted account carry the same
  workspace. The other five get `FULL` both times. The test ends with 30
  admissions, 30 distinct places and 30 durable shares.
- `BetaAdmissionPauseTest`. Forty applicants, each submitting twice, race
  through independent adapters for all thirty places. The result is places
  1–30 exactly once each and ten `FULL` answers.
- The existing `BetaAdmissionsTest` still passes. It covers the race for the
  final place, eight concurrent callbacks for one account, rollback after a
  provisioning failure (no place is spent), and the database refusing place 31.

### Limitation: 30 accounts are not 30 people

The cap counts **accounts**, not people. One person can control several
verified identities: a Google account, a GitHub account and several email
addresses. Each identity becomes a separate account (ADR-036 forbids linking
or merging accounts by matching email text). So thirty admitted accounts can
belong to fewer than thirty people. Nothing in this delivery detects or
prevents that. No invitation or identity-proofing policy is introduced here.
Choosing one is a separate owner decision.

## 2. Operator admission pause

### Semantics

The pause flips only `beta_cohort.admissions_open`.

**Paused (`admissions_open=false`):**
- New admissions get the existing `CLOSED` outcome. That is the same answer a
  never-opened cohort gives, so it reveals nothing about cohort internals, and
  the website offers the waitlist.
- Existing participants are not affected. Their admission retries return the
  original workspace. Their sessions, API keys and inboxes, and SMTP ingestion
  for them, keep working.
- The waitlist can still be read, joined and withdrawn from.

**Atomicity:** pausing takes the cohort row lock, the same first lock that
admission takes.
- An admission already in flight finishes before the pause commits.
- Every admission queued behind the pause answers `CLOSED` and spends no place.
- `BetaAdmissionPauseTest` proves both cases with held transactions.

**What it never touches:** `capacity_ready`, places, accounts, sessions, keys,
inboxes, waitlist entries, storage state.

**Resume** sets `admissions_open=true`. If `capacity_ready` is false or storage
is not ready, new admissions still answer `CAPACITY_UNAVAILABLE`, and the
command prints a note saying so.

The pause is distinct from these two controls:

| Control | Effect |
|---|---|
| `TESTINBOX_ACCOUNTS_ENABLED` (configuration) | Turns the whole `/account` boundary, including sign-in, on or off. Off returns 503 for every account route. It needs a redeploy. |
| Per-account suspension (stream L, separate subcommand) | Acts on one account's sessions, keys and ingestion. The admission command touches no per-account state. |
| `admission pause` (this stream) | Stops new admissions only, live, without a deploy. |

### Commands

These run from the reviewed API artifact. They are dispatched before Spring
starts, so no HTTP listener, scheduler, login provider or migration runs.

```
export TI_ADMISSION_JDBC_URL='jdbc:postgresql://<db-host>:5432/<db-name>'
export TI_ADMISSION_USER='<operator role>'
export TI_ADMISSION_PASSWORD='<operator secret>'

java -jar api.jar admission status
java -jar api.jar admission pause
java -jar api.jar admission resume
```

In the API container image, append the same arguments to the entrypoint:
`docker run --rm -e TI_ADMISSION_JDBC_URL -e TI_ADMISSION_USER -e TI_ADMISSION_PASSWORD <api-image>@sha256:<digest> admission status`.

Sample output:

```
admission pause: PASS admissions_open true->false; existing participants, sessions, keys, inboxes and waitlist untouched
admission status: admissions_open=false capacity_ready=true consumed_places=12/30 storage_budget_changes=refused
```

Repeating `pause` or `resume` reports `(unchanged)`. On an unknown verb,
missing credentials or a refused role, the command exits non-zero and leaves
the switch unchanged.

### Authority

- **Credentials:** the operator supplies them through the normal secret
  mechanism. There are no defaults. A JDBC URL that carries credentials
  (`@`, `password`, `user=`) is refused. Never put credentials in arguments or
  a transcript.
- **Session tag:** the command sets `ApplicationName=ops:admission-control`
  itself. The storage-activation session gate already treats `ops:*` as a
  named exclusion.
- **Role check:** `pause` and `resume` take the cohort row lock and then
  recheck `has_column_privilege('beta_cohort','admissions_open','UPDATE')`
  inside the transaction. The runtime API role (`roles.reference.sql`: SELECT
  and `UPDATE (id)` only) is refused, and PostgreSQL itself would also refuse
  the update. `StorageRoleModelTest` proves this under the shipped grants
  applied by psql. The schema owner, or a reviewed equivalent role, is
  authorized.
- **Status:** `status` is a plain read. Any role that can read `beta_cohort`
  and `beta_admission` can run it.
- **No other path:** there is no endpoint, API key, request field or frontend
  control that reaches this. An ArchUnit rule pins `ControlBetaAdmissions` as
  the only writer.

### Coherence with `storage-budget`

`storage-budget reserve|revoke` requires **both** `admissions_open` and
`capacity_ready` to be false, and it takes the same cohort row lock first.

- **Pause alone does not satisfy that requirement:** `status` shows
  `storage_budget_changes=refused` while `capacity_ready` is still true.
  Closing capacity stays with the existing activation procedure. This command
  does not add a way to change it.
- **The two commands serialize on the cohort row:** a `resume` and a pool
  change never interleave. Whichever commits second sees the other's result.
  A pool cannot change while admissions are open.

## 3. Trusted client address (API side)

### Problem

The browser reaches `/account/*` only through the website. The website's
`/account` proxy deliberately strips forwarded headers. As a result, the API's
`request.remoteAddr` is the website container for every visitor, and all
visitors share one source budget of 15 per 15 minutes. That budget applies to
email-code requests and, separately, to provider authorization starts.

### Contract between the website and the API

**When the API trusts a client address:** only if the API has
`TESTINBOX_ACCOUNTS_CLIENT_ADDRESS_SECRET` configured and the request carries
both of these headers:
- exactly one `X-TestInbox-Proxy-Secret` whose value equals the secret. The
  comparison is constant time over SHA-256 digests, so neither the content nor
  the length leaks through timing.
- exactly one `X-TestInbox-Client-Address` that is a single literal IPv4 or
  IPv6 address.

**What counts as a valid address:** syntax is checked before any parse, and
the parse is `InetAddress.ofLiteral`, so no name is ever resolved. The API
refuses:
- hostnames;
- lists or commas;
- ports;
- brackets;
- zone ids (`%eth0`);
- legacy IPv4 forms (`1`, `1.2.3`, leading zeros);
- anything longer than 45 characters.

**How the budget key is formed:**
- An IPv4 address is used as-is. An IPv4-mapped IPv6 address counts as its
  IPv4 address.
- An IPv6 address is reduced to its **/64**. A /64 is the smallest prefix
  normally assigned to one host or LAN, so one host cannot rotate through its
  own /64 to multiply its budget. The trade-off: clients behind one IPv6 /64,
  like clients behind one IPv4 NAT, share a budget.
- Client keys (`client:<address>`) and the peer fallback key (`peer:<remote>`)
  are in separate namespaces. The sign-in use cases hash the key with their
  own HMAC key before storing it, so no address is stored in plain text.

**Fallback (fail-safe):** in every other case the API uses `request.remoteAddr`,
which is the shared budget. The other cases are:
- no secret is configured;
- the client header is absent;
- the secret is missing, wrong or duplicated;
- the address is malformed or duplicated.

A request can never choose its own source. The API never reads
`X-Forwarded-For` or `Forwarded` for this purpose.

**Where it is applied:** all of it happens in `AccountClientSource` in the API
adapter. It is used by both consumers of sign-in budgets:
`POST /account/email/request` and `GET /account/oauth/authorize/{provider}`.
Nothing else in the API uses the peer address as a verified source; the email
verify and provider callback routes are keyed by challenge, browser and state.
The `/v1` and `/account` metadata budgets are keyed by workspace or account,
never by address.

**What the website must do (stream W):**
- Derive the client address only from its own trusted edge, using the trusted
  proxy CIDRs below.
- Send the two headers on every `/account` request it forwards.
- Keep stripping any client-supplied `X-TestInbox-*`, `X-Forwarded-*` and
  `Forwarded` headers.
- Never send the secret to the browser.

### Observability

- `testinbox_account_client_address_total{outcome}` counts every resolution.
  The outcome is one of `trusted`, `absent`, `not_configured`,
  `unauthenticated` or `malformed`. No address or secret is ever a label or a
  log line.
- `testinbox_account_client_address_configured` is 1 when a secret is
  configured, otherwise 0.

**After rollout:**
- `trusted` should account for nearly all sign-in starts.
- Sustained `unauthenticated` means the secret does not match between the two
  environments, or a non-website caller is reaching `/account`.
- `malformed` means the website sent a value the API cannot parse.

### Ops configuration

1. **Generate one random secret,** for example `openssl rand -base64 48` or
   `openssl rand -hex 32`. The API checks it at startup:
   - it must be 32 to 512 bytes of printable ASCII with no whitespace (mind a
     trailing newline);
   - it must contain at least 12 distinct characters.

   A set but weak value **closes `/account`** (sign-in, admission and the
   waitlist answer the unavailable state) exactly like an invalid public
   origin; the Bearer API `/v1` keeps running. The API logs one error naming
   the setting, never the value. An unset value is valid (one shared budget).
2. **Set the same value in both environments** as
   `TESTINBOX_ACCOUNTS_CLIENT_ADDRESS_SECRET`:
   - the API environment;
   - the website environment.

   Store it as host secret material. Keep it out of Git, the browser bundle,
   the ingestion environment and chat.
3. **On the website,** set the trusted proxy CIDRs to the Cloudflare ranges
   (https://www.cloudflare.com/ips/). The edge is Cloudflare, then Traefik
   with an origin lock to Cloudflare ranges, then the website. A forwarded
   address is believed only from those hops.
4. **Rotation:** set the new value on both sides, API first. While the two
   differ, requests fall back to the shared budget. That is degraded but safe.
5. **Leaving it unset** keeps today's behaviour: one shared budget, and no
   startup change. The current production deploy does not need it.

The `forward-headers-strategy: native` setting is unchanged. See
"Observations" in the stream report: the fallback's `remoteAddr` comes from
Tomcat's RemoteIpValve. Inside the private network, a peer could set
`X-Forwarded-For` and change its fallback budget key. The website strips that
header and `/account` is not publicly routed, so this is not exploitable from
outside in the current topology.

## 4. Verification

These were run on this branch on 2026-10-30:

- `./gradlew spotlessApply build`: PASS.
- `VERIFY_SCOPE=backend scripts/verify-test-results.sh`: 1,735 tests across
  11 modules, with zero failures, errors or skips. The module floors were
  raised to the actual counts: architecture 56, persistence 481, ingestion 146
  and api 285.
- Detekt on Java 21: PASS.

The new tests:

- `BetaAdmissionPauseTest` (6), `AdmissionAcrossSignInMethodsApiTest` (1),
  `AdmissionCommandTest` (3), a `StorageRoleModelTest` case for operator
  authority under the shipped psql grants, and an ArchUnit sole-writer rule
  for the pause.
- `AccountClientSourceTest` (5), `ClientAddressBudgetApiTest` (6), the
  `HumanAccountEnvironmentTest` startup-refusal and binding case, and the
  `EmailLoginApiTest` not-configured spoof case.
- A mutation check: removing the secret check fails four of those tests.

No migration, contract change or backup-scope change. No environment, host or
deployment was touched.
