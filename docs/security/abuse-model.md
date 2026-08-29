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
4. **Rate limited and quota controlled per API key/workspace**: inbox
   creation rate, message ingestion rate, storage volume, and concurrent
   wait requests are all bounded, surfaced via `RateLimit-*` headers
   (`docs/api/principles.md`). Limits should be generous enough for
   legitimate CI parallelism but not unbounded.
5. **No reply/threading UI or API.** Even the debugging dashboard only
   displays received mail; it cannot be used to carry on a conversation.
6. **Unknown-recipient mail is discarded, not stored indefinitely** — no
   incentive to probe/enumerate addresses for a persistent-storage side
   effect (`docs/architecture/inbound-mail-flow.md`).

## Residual risks and how they're bounded, not eliminated

- **A malicious actor with a valid API key could still receive mail sent by
  someone they've deceived** (e.g., phishing a victim into sending sensitive
  info to a TestInbox-hosted address). Mitigation: per-key/workspace
  attribution and revocation, rate limits that make bulk abuse
  operationally costly, and terms-of-service enforcement — this is a
  business/legal control, not purely a technical one, and is called out in
  `VISION.md`'s Human Decisions (compliance/abuse-reporting posture).
- **Address guessing/enumeration**: generated tokens use enough entropy
  that guessing an active address is infeasible; SMTP-level responses are
  designed not to distinguish "unknown recipient" from "known recipient,
  message discarded for another reason" (`inbound-mail-flow.md`) to avoid
  turning TestInbox into an oracle for address validity.
- **Storage-cost abuse via large attachments**: per-message and per-attachment
  size caps, plus per-workspace storage quotas, bound worst-case cost even
  under a compromised/malicious API key, until revoked.
