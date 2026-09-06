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
5. **No reply/threading UI or API.** Even the debugging dashboard only
   displays received mail; it cannot be used to carry on a conversation.
6. **Unknown-recipient mail is discarded immediately and never stored**
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
