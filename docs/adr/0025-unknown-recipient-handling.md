# ADR-025: Unknown-Recipient Handling — No Content Storage

**Status:** Accepted (replaces the "quarantine briefly, discard" behavior
previously described in `docs/architecture/inbound-mail-flow.md`)

## Context

The inbound flow accepts mail for unknown/expired recipients at the SMTP
level (uniform `250` after `DATA`) to avoid turning TestInbox into an
address-validity oracle — that anti-enumeration property is correct and
retained. The previous design then *quarantined the message body briefly*
before discarding it.

External review asked whether unknown-recipient content needs to be
stored at all. It does not, and storing it is a liability:

- **Privacy/legal**: the content belongs to no tenant. Persisting
  third-party mail that no customer relationship covers makes TestInbox a
  data controller for orphaned personal data (GDPR/CCPA exposure), and
  creates retention/subpoena surface for content nobody asked it to hold.
- **Abuse**: quarantined storage can hold illegal content (spam blowback,
  CSAM) attributable to no workspace, with all of the reporting
  obligations and none of the attribution.
- **Storage/DoS**: unknown-recipient traffic is exactly the traffic an
  abuser controls for free (no API key needed) — a quarantine store is an
  unauthenticated write amplifier.
- **No product benefit**: no API or dashboard surface ever exposed
  quarantined content, and none is planned. The operator question "why
  didn't my mail arrive" is answered by metadata, not by the body.

## Decision

- SMTP behavior is unchanged: syntactically valid `RCPT TO` accepted;
  recipient existence checked only after `DATA`; uniform `250` regardless
  of whether the recipient resolves (anti-enumeration, anti-backscatter).
- When recipient resolution fails (unknown, expired, deleted), the
  message content is **discarded immediately in the gateway process** —
  never written to object storage, the database, or any quarantine area.
- Only minimal operational **metadata** is recorded (log/metric:
  timestamp, hashed recipient token, sender domain, message size,
  outcome), sufficient for the `smtp_unknown_recipient_total`-style
  metrics in `docs/architecture/observability.md` and for operator
  debugging.

## Alternatives considered

- **Brief quarantine (previous design)**: rejected — all of the liability
  above in exchange for a debugging affordance that metadata already
  provides.
- **Reject at `RCPT TO` for unknown recipients**: rejected (unchanged
  from the original design) — creates an SMTP enumeration oracle for
  reserved addresses.
- **Store for the tenant "nearest" to the address (e.g., matching
  prefix)**: rejected — attribution by guess is a cross-tenant data leak
  waiting to happen.

## Consequences

- `inbound-mail-flow.md`, `failure-modes.md`, and
  `docs/security/abuse-model.md` are updated ("discarded, never stored").
- The near-expiry grace window (ADR-009) is unaffected: an inbox in
  `Expiring` still resolves and is not "unknown".
- One less data class in the legal/compliance human decision
  (`VISION.md` item 2): unknown-recipient content is out of scope by
  construction.
