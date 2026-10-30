# Abuse Model

## Design invariants that prevent misuse as general communication infrastructure

1. **Private by default, always authenticated for creation.** There is no
   anonymous or public inbox-creation endpoint. This alone rules out the
   "casual anonymous throwaway inbox" use case that YOPmail-style products
   serve, and most low-effort abuse depends on that being free/anonymous.

   > **Amended by TI-DELIVERY-001/002 (ADR-036/ADR-037), 2026-10-30.** Human
   > sign-in is now self-service (Google, GitHub or an emailed code), but
   > signing in is not admission: inboxes are still created only inside a
   > workspace, by an API key or by an *admitted* participant's session, and
   > at most thirty participant workspaces can ever exist. See
   > [Self-service sign-in and the bounded beta](#self-service-sign-in-and-the-bounded-beta).
2. **Inbound-only.** TestInbox never sends mail. It cannot be used as an
   open relay or to originate spam/phishing, because there is no send
   capability in the product at all.

   > **Amended by TI-DELIVERY-001 (ADR-036), 2026-10-30.** TestInbox now
   > originates exactly one kind of message: the sign-in code, to an address a
   > visitor typed. It is still impossible to send *tenant-chosen* content to a
   > *tenant-chosen* recipient — the body is a fixed template with no link and
   > no user-supplied text — but the login mail is a bounded spam/harassment
   > vector of its own, analysed under
   > [login mail as a spam vector](#login-mail-as-a-spam-vector).
3. **Ephemeral, TTL-bounded, capped.** Inboxes have a maximum allowed TTL
   (proposed cap, e.g. 24h) and a default short TTL (e.g., 10–30 min); this
   prevents the product being used for long-lived persistent addresses,
   which would otherwise make it attractive as a real communication channel
   rather than a test fixture.
4. **Rate limited and quota controlled per workspace** (implemented,
   [ADR-027](../adr/0027-rate-limiting-and-resource-quotas.md)): inbox
   creation rate, inbound ingestion rate, download rate, active inbox count,
   stored bytes, and concurrent wait requests are all bounded, surfaced via
   `RateLimit-*` headers (`docs/api/principles.md`). Limits key on the
   workspace, never on the API key — otherwise a tenant could mint keys for
   extra allowance and key rotation would double as limit evasion — and never
   on source IP, which is caller-forgeable and wrong for CI behind shared NAT.
   Defaults are generous enough for legitimate CI parallelism.

   Storage is bounded by admission control on *tenant-initiated growth*: a
   workspace at its ceiling cannot create new inboxes, while mail to the
   inboxes it already holds is still accepted. The residual overshoot is
   bounded by the ingestion rate limit and the ADR-009 TTL cap. Quota state is
   deliberately invisible over SMTP — see below.

   Credential administration (`POST`/`DELETE /v1/api-keys`) is charged against
   its own `KEY_ADMIN` category rather than borrowing `INBOX_CREATE`. The
   category name appears in the `429` body, so borrowing one would have put a
   plain untruth in an error message; and the honest budget is much tighter,
   because a legitimate rotation is a handful of calls and a workload that
   mints credentials in a loop is a bug or an attack.

   **Not implemented: per-key rate limiting.** Budgets remain workspace-scoped,
   so minting keys still cannot increase a workspace's aggregate capacity —
   that property is the one that matters and it holds. What is missing is the
   ability to stop one runaway key exhausting its workspace's share while its
   siblings starve. Doing it properly needs a second bucket dimension keyed by
   credential, its own configuration surface, and a decision about how it
   interacts with quotas; that is a policy change rather than a lifecycle one,
   and half-building it would have produced a limit whose behaviour nobody
   could state. It is a follow-up
   ([ADR-032](../adr/0032-api-key-credential-lifecycle.md) Consequences), and
   the shape it should take is: a per-key *safety* budget strictly below the
   workspace budget, checked first, never additive — the same relationship
   `ingestPerInbox` already has with the workspace-wide `INGEST` policy.
5. **No reply/threading UI or API.** Even the debugging dashboard only
   displays received mail; it cannot be used to carry on a conversation.
6. **Idempotency records are bounded by successful mutations, not by traffic**
   ([ADR-033](../adr/0033-idempotent-mutations.md) §4). A refusal rolls its
   claim back, so the bound is the ADR-027 **creation rate multiplied by the
   retention window** — roughly 21,600 rows per workspace at the shipped
   defaults. Not the quota: a quota bounds a stock, records are a flow, and a
   workspace creating one-second inboxes never approaches its quota. Were
   rejections recorded the flow would be bounded by nothing at all.

   This is still the first table in the schema whose row count grows with
   traffic rather than with tenant count — `V3__rate_limits_and_quotas.sql`
   states that property for the limiter tables — and it is safe only because
   **workspaces are operator-created**. There is no self-service signup, so the
   multiplier is controlled. A future signup feature invalidates this analysis
   and must revisit it.

   > **Amended by TI-DELIVERY-001/002 (ADR-036/ADR-037), 2026-10-30 — the
   > revisit.** Self-service sign-in now exists, but self-service *workspace
   > creation* does not grow without bound: a workspace is created only by
   > `AdmitBetaParticipant`, once per admitted account, and
   > `beta_admission.place` is constrained to 1–30 in the database. The
   > multiplier is therefore a fixed thirty participant workspaces plus the
   > operator-created legacy ones (the owner and monitoring workspaces), not the
   > number of people who sign in. The per-workspace bound above stands; the
   > fleet bound is at most (30 + legacy) × 21,600 rows. A signed-in but
   > unadmitted account owns no workspace and can create no idempotency record.
   > Raising the cap is a separate owner decision and re-opens this analysis.

   Keys are stored hashed and salted with their scope. The salt is the
   workspace id in the adjacent column, so it is not secret: what it buys is
   resistance to *precomputation* and to *cross-workspace correlation* of the
   same key value, not secrecy against a reader of a dump. Idempotency keys are
   low-entropy and structured, so a dictionary attack against a known workspace
   still works — see ADR-033 §4a for why a pepper is cheap here and why it is
   nonetheless not built.
7. **Credentials are managed, scoped and revocable individually**
   ([ADR-032](../adr/0032-api-key-credential-lifecycle.md)). A workspace holds
   many keys, so a leaked CI credential is revoked on its own rather than by
   changing a secret every pipeline shares. Revocation takes effect on the next
   request on every node — no authorization state is cached — and the
   `api-keys:manage` scope is separate, so an ordinary CI credential cannot
   mint or revoke others. Credential lifecycle events are audited on a
   dedicated `testinbox.audit` logger, which never carries the credential, the
   `Authorization` header or the stored verifier.
8. **Unknown-recipient mail is discarded immediately and never stored**
   (metadata-only logging, [ADR-025](../adr/0025-unknown-recipient-handling.md))
   — no unauthenticated write path into storage, and no incentive to
   probe/enumerate addresses for a persistent-storage side effect
   (`docs/architecture/inbound-mail-flow.md`).

## Residual risks and how they're bounded, not eliminated

- **A malicious actor with a valid API key could still receive mail sent by
  someone they've deceived** (e.g., phishing a victim into sending sensitive
  info to a TestInbox-hosted address). Mitigation: per-key/workspace
  attribution and revocation, rate limits that make bulk abuse
  operationally costly, and terms-of-service enforcement — this is a
  business/legal control, not purely a technical one, and is called out in
  `VISION.md`'s Human Decisions (compliance/abuse-reporting posture).
- **Inference of aggregate service usage through storage refusals**
  ([ADR-035](../adr/0035-physical-storage-bound-at-ingest.md) §13d, TI-STORAGE-004).
  An authenticated tenant can learn exactly one bit about the service as a
  whole: that a copy of its own mail was refused for `SERVICE_CAPACITY`,
  through the inbox representation's refusal record and through a wait that
  opted in with `afterStorageRefusalCount`. It never sees the global limit,
  global stored, reserved or available bytes, the reservation backlog, or
  another workspace's figures: the `409` for `SERVICE_CAPACITY` carries no
  `quota`, `limit` or `current` member at all, `StorageUsage` describes only
  the caller's own inbox or workspace, and the visibility SQL selects no
  unscoped sum (`StorageDisclosureTest`, `StorageVisibilityTest`). The
  accepted residual: by sending one `DATA` to up to 50 of its own inboxes, a
  tenant can place global headroom within one copy's size, up to its own
  workspace headroom, and repeated probing yields a coarse time series of
  other tenants' aggregate usage near the cap. Workspaces are operator
  provisioned; quantising the admission cap or adding hysteresis are named
  follow-ups. While live enforcement is OFF, no refusal is recorded at all.

  > **Amended by ADR-037 (TI-DELIVERY-001), 2026-10-30.** "Workspaces are
  > operator provisioned" no longer holds for participants: up to thirty
  > strangers can each hold a workspace. ADR-037 §5–§6 accept the consequence
  > explicitly: every syntactically valid envelope position is priced against
  > both global prefix totals (so substituting an unknown recipient for a
  > foreign one cannot change another copy's outcome *within* an event), and
  > all thirty 256 MiB footprint shares are reserved up front, so one
  > participant cannot drive the service to its cliff on its own. The
  > *cross-event* channel remains: cooperating workspaces can still correlate
  > their own `SERVICE_CAPACITY` outcomes with changes in global stored data.
  > It is a coarse capacity side channel, not content access, and the owner
  > accepted it rather than hiding the diagnostic (which would not close it:
  > the sender can still see whether its own message was stored).
- **Short post-resolution `451` windows of the guarded protocol**
  (TI-STORAGE-006E PR D, filesystem-containment contract §4.5, §8). Every
  *stable* infrastructure state that refuses mail — the latch, an open breaker,
  a broken activation barrier, untrusted footprint counts, no or an invalid
  observation, corrupt totals, write slots exhausted by unresolved ambiguity —
  is answered `451` **before** recipient resolution, the same for every
  recipient. Since issue #102 the open breaker has a stable sub-state, an
  *escalated* ambiguity (the real-event trial of an ambiguous trip was
  ambiguous again): it clears only on a filesystem observation showing the
  reserve, or a restart where no monitor runs. It is reachable only through
  storage-path behaviour (a lost answer, a timeout under load), never through
  message content, and it is exported as `testinbox_storage_breaker_escalated`.
  Three races remain, each bounded to the moment a state changes and
  sharing the noise profile of an admission `LOCK_TIMEOUT`:
  (1) the footprint state changes between the pre-resolution check (reused
  for up to 1 s) and T1's own snapshot; (2) a `STORAGE_FULL` breaker's
  half-open trial fails (or its probe is refused by rule (P)) after resolution;
  (3) an inbox stops receiving — expired by the sweep or deleted by its owner —
  while an upload into it is in flight, and T2 fences the whole event. None is
  a stable state an attacker can hold open: each answers "a recipient existed
  at that instant", which address entropy already makes infeasible to aim.
- **Envelope-order capacity refusals under `ALL`** (contract §11.6): with
  footprint admission, copies of one event are decided against running totals
  in envelope order, so a tenant parking global headroom at the cliff could
  learn from its own `SERVICE_CAPACITY` record whether a foreign address
  preceding its own in an envelope exists. One of the contract's mitigations is
  mandatory before `ALL` carries real traffic; until then `DeploymentSafety`
  refuses `ALL` in production.
- **Enumeration via limit responses**: none. Rate and quota enforcement never
  changes an SMTP reply — a syntactically valid recipient always receives the
  uniform `250` of ADR-025, whether its workspace is over quota, over its
  inbound rate, or entirely unknown. An over-rate delivery is discarded
  in-process exactly as an unknown-recipient delivery is. This is why storage
  quota is enforced on inbox creation rather than on delivery: a `4xx` for a
  live-but-over-quota recipient would have made those addresses
  distinguishable from unknown ones, handing an attacker who can drive a
  workspace over quota a workspace-membership oracle
  ([ADR-027](../adr/0027-rate-limiting-and-resource-quotas.md) §1).

  > **Amended by ADR-035 and ADR-037, recorded 2026-10-30.** Storage is now
  > also enforced *at delivery*, under the same rule: a copy refused for its
  > inbox payload, its workspace payload, its workspace footprint share or
  > service capacity still gets the uniform `250` and is discarded in-process
  > — no bounce, no DSN, no `4xx`. The refusal is visible only to the owning
  > workspace (its inbox refusal record, a wait that opted in, and the
  > console's usage panel). A participant whose share is full therefore
  > *silently loses mail* from the sender's point of view; that is the price
  > of not building an oracle, and the console must say so.
- **Address guessing/enumeration**: generated tokens use enough entropy
  that guessing an active address is infeasible; SMTP-level responses are
  designed not to distinguish "unknown recipient" from "known recipient,
  message discarded for another reason" (`inbound-mail-flow.md`) to avoid
  turning TestInbox into an oracle for address validity. `EXACT`-mode
  addresses ([ADR-021](../adr/0021-exact-address-reservation.md)) are
  guessable by construction; that residual exposure is bounded by
  ingestion rate limits, storage quotas, a reserved-local-part denylist
  (RFC 2142 role addresses are never reservable), and a per-workspace cap
  on concurrent exact reservations — with `GENERATED` remaining the
  recommended default for automation.
- **Storage-cost abuse via large attachments**: per-message and per-attachment
  size caps, plus per-workspace storage quotas, bound worst-case cost even
  under a compromised/malicious API key, until revoked.

## Self-service sign-in and the bounded beta

*Added by TI-DELIVERY-002, 2026-10-30, for the design accepted in
[ADR-036](../adr/0036-human-identity-and-beta-admission.md) and
[ADR-037](../adr/0037-bounded-cohort-storage-and-disclosure.md). The sections
above keep their original text; where this section contradicts them, they carry
an explicit amendment note.*

Until TI-DELIVERY-001 every workspace was operator-created and the only human
was the owner. The accepted design lets anyone sign in with Google, GitHub or an
emailed eight-digit code, and admits at most thirty of those accounts, first
come, into one private workspace each. The code is deployed and dormant
(`TESTINBOX_ACCOUNTS_ENABLED` off, admissions closed, storage enforcement
`OFF`); this section is the threat model it will run under.

### What a stranger can and cannot do

| state | can | cannot |
|---|---|---|
| anonymous | read which sign-in methods are configured (`GET /account/methods`: method names only — no count, no capacity); start a sign-in | learn whether an address has an account (the code request answers the same `202` whether or not one exists, and whether or not the mail was delivered); read anything else |
| signed in, not admitted | read its own admission and waitlist status; join or leave the waitlist; log out | create an inbox, see a workspace, mint a key, learn its queue position, the number of admitted accounts or the remaining capacity |
| admitted participant | everything a workspace owner can do through the console and its own API keys, inside ADR-027 budgets and its ADR-037 storage share | reach another workspace (cross-tenant ids are `404`), raise its own limits, create a second workspace |

### The thirty-place cap and the waitlist

- The cap is a database constraint (`beta_admission.place BETWEEN 1 AND 30`)
  inside the one admission transaction; concurrent applicants for the last
  place are serialized on the cohort row. No configuration value raises it.
- A place is spent for good: suspension or deletion does not reopen it, and a
  replacement admission is an explicit, reviewed owner action.
- The waitlist is explicit (signing in does not enrol), holds at most 1,000
  entries (configurable lower), expires entries after its retention (30 days by
  default, at most 90), and grants nothing. Joining sends no mail.
- The verified-account inventory (admitted or not) is capped at 1,030. When it
  is full, new sign-ups answer "unavailable"; existing accounts still sign in.

### Sybil limitation (accepted)

**Thirty admitted accounts does not mean thirty distinct people.** Identity is
a provider subject or a verified mailbox, nothing more:

- contact addresses are deliberately not canonicalized (ADR-036 §2: Gmail dots
  and `+suffix` are preserved), so one mailbox owner can verify many distinct
  addresses, and one person can also hold several Google or GitHub identities;
- admission is first come, triggered when an unadmitted account opens the
  console, so one determined person can claim several places — in principle
  all of them — as fast as the sign-in budgets below allow;
- the same lever can fill the 1,000-entry waitlist and the 1,030-account
  inventory, denying the beta (or new sign-ups) to everyone else. At the global
  code budget of 200 per hour that takes hours, not minutes, and it is visible
  in `testinbox_email_login_total` and the admission count.

What bounds it: the per-source, per-recipient and global sign-in budgets; one
workspace per account and the fixed per-workspace storage share, so a
multi-account holder gains capacity linearly and can never take another
participant's share; the operator's admission pause (`beta_cohort.admissions_open`,
and the TI-DELIVERY-002 operator command); and the owner's review of who was
admitted. What would close it — invitation codes, payment, phone or document
verification — is out of scope for this beta. A controlled beta should open
admissions only while someone is watching, and pause them when the cohort is
full of people it intended to admit.

### Sign-in and email budgets

All are enforced in the application layer, in PostgreSQL, across API nodes.
Pending, failed and consumed attempts all count.

| budget | email codes | provider (Google/GitHub) starts |
|---|---|---|
| global | 200 per hour | 200 per hour |
| per verified source | 15 per 15 minutes | 15 per 15 minutes |
| per recipient address | 3 per 15 minutes, and a 60-second resend gap | — |
| retained rows | 5,000 challenges, purged after 24 hours | 5,000 state rows, purged after 24 hours |
| per challenge | 10-minute lifetime, 5 failed attempts, single use, bound to the requesting browser | 10-minute state, single use, bound to browser and provider |

The per-source hourly equivalent (60) stays below the global hour, so one source
can never consume everyone's budget on its own. **Until TI-DELIVERY-002's
trusted client address is configured, it does exactly that:** the public site
proxies `/account/*` and strips forwarding headers by design, so the API sees
the site container as the source of every visitor. Fifteen code requests in
fifteen minutes from anyone then block code requests for everyone for the rest
of the window. Stream A/W replace the shared source with a client address the
site attests to the API (`TESTINBOX_ACCOUNTS_CLIENT_ADDRESS_SECRET`,
`TESTINBOX_TRUSTED_PROXY_CIDRS`). TODO(integrator): describe the trust chain
(edge → site → API), what an unattested or forged address falls back to, and
that a raw `X-Forwarded-For` from the Internet is never believed.

Authenticated metadata requests (`/account/session`, `/account/admission`,
`/account/waitlist`) share a 60-per-minute budget per account and 3,600 per
minute globally; logout is always allowed. The human product aliases
(`/account/product/*`) are charged to the workspace's ADR-027 budgets exactly
like `/v1`. An account holds at most five sessions; a sixth evicts the oldest.

### Login mail as a spam vector

Anyone can type someone else's address and make TestInbox mail it a code. The
bound on that harassment, per victim address: 3 messages per 15 minutes and at
least 60 seconds apart (at most 288 a day, from any number of sources), within
the 200-per-hour global ceiling the attacker also spends. The content is a fixed
plain-text template — the code, its lifetime and "if you did not request it,
ignore this message" — with no link and no attacker-controlled text, so it
cannot carry a phishing payload or a URL. The residual risks are annoyance, spam
complaints against the sending domain, and the provider's own budget being
spent; the global ceiling caps all three. `testinbox_email_login_total{outcome}`
is the signal to alert on.

**TestInbox receive-domain addresses are refused as identities.** A code request
for an address in the deployment's own mail domain (`inbox.testinbox.email` or
any subdomain) answers `400 invalid-request` — the same status, problem type
and title a malformed address gets (only the per-request correlation id
differs) — before any budget is charged or any row written, and no
mail is sent. Otherwise a participant could mint unlimited throwaway identities
from its own inboxes (sybil amplification), and the service would mail itself.
The refusal is a property of the address alone, so it reveals nothing about any
account. The deployment synthetic (`npm run test:accounts`) asserts both
refusals are identical.

### Storage shares (ADR-037)

- Each participant workspace has a fixed **128 MiB payload** and **256 MiB
  conservative physical-footprint** ceiling; both apply. Thirty shares total
  7.5 GiB of footprint and are all reserved, with the legacy workspaces' explicit
  shares, before the first admission: no overcommit, so one participant cannot
  consume another's share.
- **Delayed cleanup.** Deleting messages or inboxes does not immediately free
  footprint: the physical charge stays on the workspace as *pending cleanup*
  until a later, fresh filesystem observation from the approved monitor shows
  the trash empty. A participant who fills and deletes in a loop hurts only its
  own share; it cannot launder deletion debt onto the service or another
  participant.
- **Quota refusals are silent discards.** A copy refused for inbox payload,
  workspace payload, workspace footprint or service capacity still gets the
  uniform SMTP `250` and is discarded in-process (no DSN). Only the owning
  workspace sees the refusal (refusal record, opted-in wait, console). This is
  the ADR-025/ADR-027 rule — no limit may change an SMTP reply — carried over
  unchanged; infrastructure failures still answer a uniform `451` before
  recipient resolution.
- Unknown and refused envelope positions are priced against the global totals
  (ADR-037 §5) so that, within one event, an unknown recipient is
  indistinguishable from a foreign one. The cross-event capacity side channel
  described above is accepted, not closed.

### Uniform SMTP regardless of account state

No account, admission, waitlist or quota state changes a valid recipient's SMTP
reply. A suspended or deletion-pending participant's addresses must behave like
unknown recipients — `250`, discard, nothing persisted — which is part of the
TI-DELIVERY-002 lifecycle work (stream L). TODO(integrator): confirm against the
integrated lifecycle tests.

### Disabled is closed, never a fallback

With `TESTINBOX_ACCOUNTS_ENABLED` off — production today — every `/account/*`
route answers `503` with no cookie and no side effect, the site shows no
places, and no mock login, test code, fixture account or privileged user is
substituted. An incomplete configuration closes only the affected method. The
`/v1` API, ingestion and the deployment synthetics are unaffected either way.
`npm run test:accounts` asserts the declared state from outside.
