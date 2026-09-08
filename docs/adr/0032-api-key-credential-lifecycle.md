# ADR-032: API Key Credential Format, Verification and Lifecycle

**Status:** Accepted (elaborates [ADR-010](0010-authentication-api-keys.md))

## Context

ADR-010 decided *what* authenticates a request — an opaque bearer key, hashed
at rest, scoped to a workspace/project. It deliberately left the credential's
shape, its verification construction and its lifecycle open.

The walking skeleton filled that gap with the smallest thing that works: a
single API key per environment, supplied as configuration
(`testinbox.bootstrap.api-key`), hashed with SHA-256, looked up by that hash.
That is adequate for one developer and one machine. It is not adequate for
shared use, and staging is now shared:

- every CI system that talks to TestInbox holds **the same secret**, so a leak
  in one pipeline's logs compromises all of them;
- the only way to revoke it is to change configuration and restart, which
  revokes it for **everyone at once**;
- there is no way to rotate without a window in which either the old key is
  dead or both are unknown;
- nothing records which credential performed an action, because there is only
  one;
- the bootstrap key is indistinguishable from a real one, so the temporary
  mechanism quietly becomes the permanent credential model.

TI-002 makes API keys managed credentials. This ADR records the decisions that
outlive the implementation.

## Decision

### 1. Format

```
ti_k1_<publicId>_<secret>_<check>
   │  │  │          │        └── 4 chars, CRC-32 of everything before it
   │  │  │          └─────────── 52 chars, 260 bits of CSPRNG entropy
   │  │  └────────────────────── 16 chars, 80 bits — the non-secret handle
   │  └───────────────────────── format version
   └──────────────────────────── product marker
```

All components use lowercase RFC 4648 base32 (`a–z2–7`). That alphabet is
chosen for a specific reason: it contains **no `_` and no `-`**, so `_` is an
unambiguous separator and the whole token survives being pasted into a shell,
a URL, a YAML value or a CI secret field without escaping. base64url would
have been ~20% shorter and would have made the separator ambiguous.

- **`ti_k1_` is a searchable marker.** A secret-scanning rule, a log filter or
  a human reviewing a diff can recognise a TestInbox credential without
  knowing anything else about it. The version segment means a future format
  can be introduced *additively* — `k2` keys and `k1` keys authenticate side
  by side, and no flag day is required.
- **The public id is a handle, not a secret.** It is what the database is
  indexed by, what an operator quotes in a ticket, and what appears in audit
  logs. It is random rather than sequential so it does not disclose how many
  credentials exist or in what order they were minted, and it encodes
  **nothing** — not the workspace, not the project, not the scopes.
- **The checksum is integrity, not security.** CRC-32 is used precisely
  *because* it is not cryptographic: it catches a truncated paste or a
  transposed character before any lookup, and nobody can mistake it for a
  security control.

  It does **not** change what the client sees. Every authentication failure is
  one byte-identical `401`, deliberately — a response that distinguished
  "malformed" from "no such key" would be an enumeration oracle, and that
  property is worth more than the diagnostic. What the checksum buys is a
  *server-side* signal: `testinbox_api_key_auth_total{outcome="CHECKSUM_MISMATCH"}`
  separates "someone is truncating the key in a CI variable" from "someone is
  presenting a credential we revoked", and those have completely different
  remedies. An operator can answer the question; the client still cannot ask
  it.

**Nothing authorization-bearing is encoded in the token.** Scopes, workspace
and project are read from the row the public id resolves to. A key that
carried its own scopes would be a token the holder could rewrite.

### 2. Verification: SHA-256, not a password hash

The stored verifier is `SHA-256(secret component)`, compared in constant time.

A password KDF (bcrypt/scrypt/Argon2) exists to make **guessing** expensive,
because human-chosen passwords come from a distribution small enough to
enumerate. That premise does not hold here. The secret is 260 bits from a
CSPRNG; there is no dictionary, no reuse across sites and no structure. An
attacker holding the full database and hashing at 10¹² SHA-256/s needs on the
order of 10⁵⁸ years to find one secret. Stretching that by 10⁵ changes nothing
an operator would ever notice.

What it *would* change is the cost of every authenticated request. Every
`/v1` call verifies a credential; a deliberately slow KDF on that path is a
self-inflicted denial-of-service amplifier, where an unauthenticated attacker
converts cheap requests into expensive server CPU. Recommending a password
hash here would be cargo-culting the letter of a rule against its reason.

A server-side pepper (HMAC with a configured key) was also considered and
rejected. Its value is that a database dump alone is not enough to verify
guesses — but against a 260-bit random secret, a database dump alone is
*already* not enough, so the pepper buys nothing while adding a key that must
be provisioned, rotated and kept consistent across every node. Complexity with
no corresponding threat retired.

The entropy is therefore load-bearing, and the format check enforces it:
a `k1` credential whose secret component is not exactly 52 base32 characters
is rejected as malformed, so no code path can ever mint or accept a weaker one.

### 3. Lookup by public id, verification by secret

```
parse → public id → row → constant-time verify → state checks → context
```

Authentication never queries by the full presented token. This is what makes
the credential administrable: a row can be listed, named and revoked by a
handle that is safe to display, while the part that grants access exists only
in the holder's hands. It also means the verifier column is not a lookup key,
which is the constraint that would otherwise push a design toward retrievable
plaintext.

### 4. The plaintext is unrecoverable, by construction

The complete credential exists in exactly one place at exactly one moment: the
`201` response to `POST /v1/api-keys`. It is never written to the database, a
log, a metric, a trace or an error message. There is no "show key" and there
can be no "recover key" — not as a policy that could be relaxed, but because
the information required to answer does not exist anywhere in the system.

A lost key is replaced by minting a new one and revoking the old.

### 5. Revocation is a state change, and it is immediate

Revoking sets `revoked_at`; the row is retained. Physical deletion would
destroy the audit trail of a credential precisely when it has become
interesting — a compromised key's history is the evidence.

**No authorization state is cached anywhere.** Every authenticated request
resolves the credential from the database, so the revocation bound is zero
requests and zero seconds, on every node, without invalidation messaging.
At this scale that is not a compromise; it is one indexed primary-key-shaped
lookup against a row set that is small by construction. If a cache is ever
introduced it must come with an explicit, tested staleness bound — a revoked
credential that keeps working for an unbounded interval is the failure this
paragraph exists to prevent.

The one thing that is cached is a *timestamp* (§7), which grants nothing.

### 6. Rotation is a sequence, not an endpoint

```
create replacement → deploy it → verify it works → revoke the original
```

An atomic `POST /v1/api-keys/{id}/rotate` was rejected. Rotation's hard part is
not minting the new secret — it is the interval during which the client has not
yet picked it up, and no server-side endpoint can shorten that. A `rotate` call
would have to either revoke immediately (guaranteeing downtime for anything
still holding the old key) or leave both valid (which is exactly the two calls
above, with a name that implies more atomicity than it delivers). Multiple
concurrently valid keys per workspace already make the correct procedure
possible; an endpoint would only obscure it.

### 7. `lastUsedAt` is approximate on purpose

Operators need to answer "is this credential still in use?" before revoking it.
They do not need to know to the second.

A naïve `UPDATE api_key SET last_used_at = now()` per request would put a write
— and its lock, its WAL and its replication cost — on the hot path of every
authenticated call, in service of a field nobody reads in real time. Instead
the timestamp is refreshed at most once per key per coalescing interval
(5 minutes), guarded in the `WHERE` clause so the decision is made atomically
by the database rather than by a read-then-write race between nodes:

```sql
UPDATE api_key SET last_used_at = :now
 WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :now - interval)
```

Each node additionally remembers, in a bounded in-memory map, when it last
issued that statement for a key, so the steady state is **zero** database
writes rather than one no-op write per request. The map holds timestamps only;
losing it costs at most one extra write per key.

That coalescer lives in the application layer rather than in an adapter, which
is a deliberate exception worth naming: it is framework-free policy — "how
often is often enough?" — and the half that must be correct across nodes is the
SQL `WHERE` guard, which is in the adapter where it belongs. The in-memory half
is an optimisation that may be empty, stale or discarded at any moment without
affecting the answer.

The documented semantic is therefore: *`lastUsedAt` is accurate to within the
coalescing interval, and may lag by that much after a burst.* It must never be
used as an authorization input or an audit record — the audit log is (§8).

### 8. The bootstrap credential closes its own window

The bootstrap key is retained, because a fresh installation must be able to
mint its first credential and there is nothing else to authenticate that call
with. It becomes safe by being **conditional rather than permanent**:

> A bootstrap credential authenticates only while its workspace holds no
> usable managed key with the key-administration scope.

The moment the first managed admin key exists, the bootstrap key stops
authenticating — no restart, no configuration change, no operator action, and
therefore no way to *forget*. Options where the operator must disable it
explicitly were rejected for exactly that reason: the failure mode they permit
(nobody remembers) is the one actually observed in the field.

Two consequences are deliberate:

- **It reopens if every managed admin key is revoked.** That is the break-glass
  path, and it is why revoking the last administrative key is *allowed* rather
  than refused. A "you may not revoke your last admin key" rule protects
  against lockout at the cost of forbidding the one operation you most need
  when that key is the compromised one. Recovery is a better answer than
  prevention here, and an environment with no bootstrap key configured simply
  has no break-glass — which `docs/dev/api-keys.md` states plainly.
- **The condition is evaluated per request**, not at startup, or a restart
  would silently resurrect the credential.

Bootstrap keys are marked as such in storage (`kind = 'BOOTSTRAP'`), are never
returned by the management API, and cannot be created through it.

**Configuration is what retires one.** A bootstrap credential authenticates
only when the presented token hashes to the value this process is *currently*
configured with. That sentence is doing real work, and the first implementation
of this ADR did not have it: it resolved the credential from storage alone,
which meant a bootstrap row, once provisioned, authenticated forever. Rotating
the setting after a leak provisioned a *second* row and left the first live;
removing the setting disabled nothing; and because every management query
filters `kind = 'MANAGED'`, no endpoint could revoke either one. The credential
that holds `api-keys:manage` unconditionally was the only one in the system
that could not be retired — precisely the "temporary mechanism quietly becomes
permanent" failure this section exists to prevent, one level down.

With the match required, the operational story is the obvious one: rotate the
setting and the previous credential is dead on the next request; unset it and
there is genuinely no break-glass. Stale rows may linger in the table, but they
are inert — they can never again match a configured value.

### 9. Credential administration gets its own rate category

`RateCategory.KEY_ADMIN` is added to the ADR-027 control table, with its own
`testinbox.limits.key-admin.*` budget, deliberately tighter than any other
(burst 20, ~1 per 5s sustained): a legitimate rotation is a handful of calls,
and a workload that mints credentials in a loop is a bug or an attack.

It gets its own category rather than borrowing `INBOX_CREATE` because
**ADR-027 puts the category name in the `429` body**. Telling a caller that
minting a key exceeded an "INBOX_CREATE" limit would be a plain untruth in an
error message, and the client is expected to discriminate on that field.

Everything ADR-027 requires still holds: the budget keys on the workspace
derived from the authenticated credential, the decision lives in the
application layer, the adapter only renders it, and classification stays
default-deny.

**Per-key rate limiting remains rejected**, and ADR-027 §"Alternatives" already
rejected it, for a reason this ADR does not disturb: keying budgets on the API
key would let a tenant mint N keys for N× allowance, and rotation would reset
the bucket. The follow-up recorded in `docs/security/abuse-model.md` is a
*different* shape and is compatible with that rejection — a per-key **safety**
budget strictly below the workspace budget, checked first and never additive,
exactly the relationship `ingestPerInbox` already has with the workspace-wide
`INGEST` policy. It bounds one key's share of an allowance it can never
increase. It is not built here because it needs a second bucket dimension, its
own configuration surface and a decision about how it interacts with quotas —
a policy change rather than a lifecycle one.

### 10. Scopes stay coarse

`ApiScope` gains exactly one value: `api-keys:manage`, covering the whole
lifecycle surface.

The property that matters is that **a CI key cannot mint or revoke
credentials**, and one scope delivers it. The check lives in the use case, not
only in the HTTP adapter, for the reason ADR-027 §3 gives about limits: an
authorization rule enforced in a filter protects exactly the callers that go
through that filter, and this project already has two entry points. Splitting read from write was
considered and deferred: listing credentials and revoking them are both
administrative acts, and a reader who can enumerate a workspace's keys is
already positioned for targeted disruption. Endpoint-shaped scopes were
rejected outright — they grow without bound, and the resulting permission
matrix gets copied rather than reasoned about.

Scopes are immutable for a key's lifetime. Changing a credential's permissions
is minting a new one, which keeps "what could this key do at the time?"
answerable from the audit log.

## Consequences

- The credential surface is additive: no existing key, request or SDK call
  changes meaning. The bootstrap path continues to work until — and only
  until — a managed admin key replaces it.
- `api_key.key_hash` now holds a verifier whose input depends on `kind`
  (`SHA-256` of the secret component for managed keys, of the whole configured
  token for bootstrap). The migration comment says so; a future format version
  is expected to make this explicit rather than implicit.
- Per-key rate limiting is **not** implemented. ADR-027's budgets remain
  workspace-scoped, so minting keys can never increase a workspace's capacity;
  what is missing is the ability to stop one runaway key exhausting the
  workspace's share. Doing it properly needs a second bucket dimension, its own
  configuration surface and its own quota interaction, which is a policy change
  rather than a lifecycle one. It is recorded as a follow-up in
  `docs/security/abuse-model.md` rather than half-built here.
- Human login, workspace membership and the issuing UI remain out of scope
  (ADR-010): key management is API-first.
