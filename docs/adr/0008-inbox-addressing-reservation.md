# ADR-008: Inbox Addressing/Reservation

**Status:** Superseded by [ADR-021](0021-exact-address-reservation.md)

> Superseded 2026-08-29: the rejection of exact-match addressing below
> rested on the incorrect premise that it requires a global locking
> scheme; a Postgres unique constraint suffices. ADR-021 defines a
> two-mode design (generated + exact) and addresses reuse/cooldown and
> namespace risks. The generated-address behavior described here carries
> forward unchanged as ADR-021's `GENERATED` mode.

## Context

Can an arbitrary requested email address be guaranteed? What happens under
concurrent creation requests for the same alias?

## Decision

`createInbox()` accepts an optional `aliasHint` used only as a human-readable
prefix; the actual address always includes a generated random suffix
(`{aliasHint}-{randomToken}@testinbox.email`, or `{randomToken}@testinbox.email`
if no hint given). Uniqueness is enforced by a database unique constraint on
the full address; a collision (statistically negligible given sufficient
token entropy) triggers a transparent regenerate-and-retry within the same
request. There is **no** mode that guarantees an exact, caller-chosen
address in MVP — see `docs/architecture/message-lifecycle.md` for reuse
semantics (expired tokens are never reused).

## Alternatives considered

- **Guarantee exact caller-chosen addresses**: rejected for MVP — requires a
  global reservation/locking scheme and raises the question of what happens
  when two callers race for the same exact address (reject the second? queue
  it?), which has no clean answer without a concrete use case demanding it.
  Flagged as a Human Decision Required.
- **Distributed lock for token generation**: rejected — a DB unique
  constraint with retry-on-conflict is simpler and sufficient; no need for
  external locking (Redis/Zookeeper) for this.

## Consequences

- SDK examples showing a returned generated address (not a caller-specified
  one) accurately reflect the guarantee; documentation must not imply exact
  address control exists.
- Future project subdomains / customer-owned domains are a separate,
  not-yet-written ADR once the exact-match question is resolved.
