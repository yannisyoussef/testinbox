# ADR-021: Inbox Addressing — Generated and Exact Modes

**Status:** Accepted (supersedes [ADR-008](0008-inbox-addressing-reservation.md))

## Context

The original product requirement included reserving an exact,
caller-chosen address (`newemail@testinbox.email`) via an authenticated
API call. ADR-008 reduced every requested alias to a prefix hint with a
mandatory random suffix, rejecting exact-match on the grounds that it
"requires a global reservation/locking scheme" with "no clean answer" for
concurrent races.

External review correctly challenged that rationale: **a PostgreSQL
unique constraint *is* the reservation scheme.** Two concurrent requests
for the same local-part race on an `INSERT`; the database picks exactly
one winner; the loser receives a constraint violation which maps to
HTTP `409 Conflict`. No distributed lock, no queueing, no additional
infrastructure. The rejection in ADR-008 solved a problem Postgres had
already solved.

The *real* costs of exact addresses are elsewhere: address reuse under
delayed mail, and namespace effects on a shared domain. Those are
addressable, and are addressed below.

## Decision

`POST /v1/inboxes` supports two addressing modes:

1. **`addressMode: GENERATED`** (default, recommended for parallel
   automation): optional `aliasHint` prefix plus a high-entropy random
   token — exactly the previous ADR-008 behavior. Generated tokens are
   never reused (unchanged from `message-lifecycle.md`).
2. **`addressMode: EXACT`** with a caller-supplied `localPart`: the
   reservation is an `INSERT` into the address-reservation table guarded
   by a partial unique index over reservations that are active or in
   cooldown (see below). Success means the caller owns exactly that
   address; a unique-constraint conflict returns `409 Conflict`
   (problem type `.../address-already-reserved`). Concurrency ownership
   is decided solely by the database constraint — **no distributed
   locks**.

### Reuse and delayed-mail hazard

Reusing a local-part is intrinsic to exact mode (that is its purpose), so
the never-reuse rule for generated tokens cannot apply. To bound the risk
of a new reservation receiving straggler mail intended for a previous
holder:

- On expiry/deletion of an `EXACT` inbox, the local-part enters a
  **cooldown window** (proposed default: 24 h, deployment-configurable)
  during which re-reservation returns `409` with a `retryAfter` hint.
  Enforced by keeping the released reservation row (with `released_at`)
  inside the scope of the partial unique index until cooldown elapses.
- **Residual risk, documented:** MTA retry horizons can exceed any
  practical cooldown (up to several days). Mail delivered to a
  re-reserved local-part after cooldown is attributed to the current
  holder. This is stated plainly in API/SDK docs; callers who cannot
  tolerate it should use `GENERATED` mode, which remains the recommended
  default.

### Namespace and abuse controls

Exact local-parts live in a **single global namespace across all
tenants** of the shared `testinbox.email` domain:

- A denylist of reserved local-parts is enforced: RFC 2142 role addresses
  (`postmaster`, `abuse`, `hostmaster`, `webmaster`, `security`, ...) and
  operationally sensitive names (`admin`, `root`, `support`, `billing`,
  `noreply`, ...). `postmaster` must additionally remain deliverable to
  operators per RFC 5321, never reservable by a tenant.
- Local-parts are validated (RFC 5321 length ≤ 64, conservative character
  set, lowercase-normalized before uniqueness checks) — no quoted-string
  local-parts.
- Exact addresses are guessable by construction, so third parties can
  spam them; this is bounded by the existing per-workspace ingestion rate
  limits and storage quotas (`docs/security/abuse-model.md`), plus a
  per-workspace cap on concurrent `EXACT` reservations to prevent
  namespace squatting.

### Scope

The v1 API contract defines both modes from the start (so SDK surfaces
and the OpenAPI contract don't churn). Whether `EXACT` mode is
implemented in the walking skeleton or immediately after remains a human
scheduling decision (`VISION.md`), now narrowed: the design question is
settled; only sequencing and the cooldown default require sign-off.

## Alternatives considered

- **Prefix-hint only (ADR-008)**: superseded — its technical rationale
  (that exact reservation needs global locking) was incorrect, and it
  silently dropped a stated product requirement.
- **Distributed lock service for reservation**: rejected — strictly
  dominated by the unique constraint.
- **Per-project subdomains to shard the exact namespace**: deferred —
  a real option once custom domains exist, but not required to make
  exact mode safe on the shared domain.

## Consequences

- `docs/api/v1-design.md` documents both modes; SDK examples may show
  exact reservation with the 409-on-conflict contract.
- `failure-modes.md` replaces "alias collision cannot occur" with the
  two-mode behavior (generated: regenerate-and-retry; exact: 409).
- The reservation table schema must model cooldown (`released_at` +
  partial unique index) from the first migration touching addressing.
- Generated mode's never-reuse guarantee is unchanged.
