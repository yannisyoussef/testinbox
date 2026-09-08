# Abuse Model

## Design invariants that prevent misuse as general communication infrastructure

1. **Private by default, always authenticated for creation.** There is no
   anonymous or public inbox-creation endpoint. This alone rules out the
   "casual anonymous throwaway inbox" use case that YOPmail-style products
   serve, and most low-effort abuse depends on that being free/anonymous.
2. **Inbound-only.** TestInbox never sends mail. It cannot be used as an
   open relay or to originate spam/phishing, because there is no send
   capability in the product at all.
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
   claim back, so a workspace pinned at its inbox quota creates no records at
   all — quota keeps bounding the tenant's footprint, which is the one thing it
   is for. Were rejections recorded, that workspace could mint tens of
   thousands of rows a day while creating nothing.

   This is still the first table in the schema whose row count grows with
   traffic rather than with tenant count — `V3__rate_limits_and_quotas.sql`
   states that property for the limiter tables — and it is safe only because
   **workspaces are operator-created**. There is no self-service signup, so the
   multiplier is controlled. A future signup feature invalidates this analysis
   and must revisit it.

   Keys are stored hashed and salted with their scope. ADR-032 §2 rejected a
   pepper for a 260-bit random secret; that reasoning does not transfer here,
   because idempotency keys are low-entropy and structured — CI job ids, branch
   names — so an unsalted digest would be confirmable by anyone with read
   access, and the same value would be correlatable across workspaces.
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
