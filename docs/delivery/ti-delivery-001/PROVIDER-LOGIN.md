# Provider login — state and verified-identity foundation (M3e)

This increment implements protocol-state storage and the verified-provider
sign-in transaction under accepted ADR-036. It does not yet wire Google or
GitHub HTTP/protocol adapters. No provider credential, OAuth application,
callback setting, host or public access was created or changed.

## State and transaction boundaries

- Use one purpose-separated hash for an opaque SDK state, another for the
  initiating browser's 256-bit cookie, and a keyed source hash. State belongs
  to exactly Google or GitHub and expires in ten minutes. Another browser,
  another provider, an expired callback or a replay cannot consume it.
- Protect the serialized authorization request (PKCE/nonce material) with JDK
  AES-256-GCM. Derive an encryption key using a fixed HMAC purpose; use a new
  random 96-bit IV for every encryption and bind provider/state hash as AAD.
  The deployment key remains outside PostgreSQL. Limit plaintext to 8 KiB
  and stored ciphertext to 16 KiB. Secret-bearing string forms are redacted.
- A PostgreSQL locked CTE returns and erases a request exactly once. Commit
  that consumption before decryption and provider I/O; do not invert the
  registration/state lock order or hold DB locks over network requests.
  Invalid ciphertext/context/key fails closed and cannot be retried as a
  usable state. A fresh browser attempt is required.
- Replacing one browser's pending attempt erases only that browser's payload,
  across providers. Bound retained rate metadata to 1,000 entries, starts to
  200/hour globally and 60/15 minutes per verified deployment source.
  Consumed/replaced attempts remain charged; switching browser/provider does
  not reset the shared source/global inventory.
- Clear expired payloads on request and with a minute scheduler. Retain only
  bounded rate metadata for up to 24 hours; cleanup remains active when the
  future login feature is disabled. It skips incompatible schemas and exposes
  closed-outcome success/failure counters.
- V15 is expand-only. `provider_login_state` is explicitly excluded from
  backups; the checker refuses marking it durable and a genuine dump with its
  rows deliberately included is refused. Restore cannot resurrect this state.

`SignInWithVerifiedProvider` requires the upstream adapter's verified identity.
It rejects the email-code channel, reuses `ResolveVerifiedIdentity` and
`IssueHumanSession` in one transaction, never auto-links by contact and never
allocates a participant workspace. Missing/inactive accounts get no session.
Failure rolls back new identity/account/session state together. Static guards
allow human-session issuance only from verified email/provider use cases;
concrete adapter and unverified-issuer fixtures independently prove refusal.

## Required next protocol work

Use Boot-managed Spring Security 7.1.1. Its official source was inspected at:
https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-oauth2-client/7.1.1/spring-security-oauth2-client-7.1.1-sources.jar

Keep maintained authorization-code, PKCE, OIDC signature/issuer/audience,
state and nonce handling. Wire a bounded authorization-request repository to
both SDK filters, discard authorized-client tokens, avoid persistent framework
security sessions and clear request security context. The public origin and
callback URLs must be fixed configuration, not untrusted forwarded headers.
Token/user-info/JWK requests need finite timeouts and approved provider HTTPS
endpoints. Google authoritative verified contact and GitHub verified-email
selection require explicit tests. Unsupported/unverified contact must not mint
an account or silently attach another sign-in method. A fallback to native
email must complete native verification and must not implicitly link accounts.

The current tests assume upstream identity proof. They do not prove actual
Google/GitHub token, signature, state/nonce or user-info behavior. Add full
protocol negative and successful callback tests before calling either provider
implemented, then browser/BFF acceptance. Do not activate from this document.

## Evidence

Four crypto-helper cases, fourteen real PostgreSQL state/sign-in cases,
two domain boundary cases, three cleanup cases and three architecture cases.
Eight independent consumers return one request exactly once. Every final
module suite passes: 1,596 tests across twelve modules, zero failures/errors/
skips; full build, Detekt and formatting pass. Backup regressions: 178/178;
all fifteen migrations expand-only. See VERIFICATION.md for failure history.
