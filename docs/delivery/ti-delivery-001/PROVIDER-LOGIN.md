# Provider login — state and verified-identity foundation (M3e)

This increment implements protocol-state storage and the verified-provider
sign-in transaction under accepted ADR-036. M3e did not wire Google or GitHub HTTP/protocol adapters; the M3f section
below records their later local implementation. No provider credential, OAuth application,
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
  across providers. Bound retained rate metadata to 5,000 entries, starts to
  200/hour globally and 15/15 minutes per verified deployment source (so one
  source can never use up the global hour). The 5,000 lies above what the hourly
  limit can retain over the 24-hour window (4,800), so sustained legitimate use
  never reaches it; it only fails closed on an anomaly.
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

## Protocol requirements recorded before M3f

Use Boot-managed Spring Security 7.1.1. Its official source was inspected at:
https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-oauth2-client/7.1.1/spring-security-oauth2-client-7.1.1-sources.jar

Keep maintained authorization-code, PKCE, OIDC signature/issuer/audience,
state and nonce handling. The initial candidate considered both SDK filters;
M3f instead invokes the SDK resolver/authentication providers directly,
without creating a framework security session/context or retaining tokens. The public origin and
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

## M3f protocol adapter (verified locally)

The authored account contract now includes Google/GitHub start and callback
GETs. MVC controllers compose the SDK resolver and authentication providers
directly; they do not install the SDK's session-oriented servlet filters.
This keeps standard code/PKCE/OIDC validation without a SecurityContext,
HttpSession, authorized-client repository or provider-token persistence.
The protocol result is converted to `VerifiedLoginIdentity` and passed through
the sole verified-provider transaction from M3e. All flags remain off.

The resolver creates S256 PKCE for both providers and nonce for Google.
Explicit JSON serializes only authorization-request fields; M3e encrypts it.
The callback consumes browser/provider-bound state before any token request,
rebuilds the SDK exchange from the fixed registration and validates the result.
No user-supplied scopes, return URL, client ID or endpoint is accepted. The
configured HTTPS origin is supplied to the SDK; arbitrary forwarded headers
cannot select a callback. HEAD cannot start or consume login state.

Google uses signed ID-token claims, validated by the maintained RS256 decoder,
OIDC validator and nonce checker. Verified Gmail/Googlemail contact or a matching
Google Workspace `hd` is required. Other Google contacts can use TestInbox's
separate email flow; no unverified identity is retained or linked. GitHub uses
its authenticated `/user` numeric ID and verified primary `/user/emails`
contact, including private email; the mutable login/public-profile email is
never identity proof. If no suitable primary email is returned in the bounded
100-entry response, fail closed and offer the independent email method.

The outbound HTTP transport has five-second connection/read deadlines and
refuses redirects, including token endpoint redirects. OAuth error responses
and exceptions never reach logs or redirect text. Public error codes are a
closed set. Success sets only the TestInbox HttpOnly session and expires the
attempt cookie; it allocates no workspace, admission or machine API key.
A failed exchange consumes the attempt; a fresh login is required.

API-only configuration, separate from email verifier and SMTP credentials:

```
TESTINBOX_ACCOUNTS_OAUTH_STATE_KEY_BASE64=
TESTINBOX_ACCOUNTS_OAUTH_GOOGLE_ENABLED=false
TESTINBOX_ACCOUNTS_OAUTH_GOOGLE_CLIENT_ID=
TESTINBOX_ACCOUNTS_OAUTH_GOOGLE_CLIENT_SECRET=
TESTINBOX_ACCOUNTS_OAUTH_GITHUB_ENABLED=false
TESTINBOX_ACCOUNTS_OAUTH_GITHUB_CLIENT_ID=
TESTINBOX_ACCOUNTS_OAUTH_GITHUB_CLIENT_SECRET=
```

The state key must decode to exactly 32 random bytes and stay outside the DB
and backups. Changing it invalidates pending provider requests. Each provider
also requires the top-level enabled flag and canonical origin. Incomplete
configuration closes only that flow; no fixture or auto-provisioned user is
substituted. Configured gauges label only the two fixed providers; outcome
counters use only a closed enum.

Release prerequisites: register each provider's exact
`<public-origin>/account/oauth/callback/google|github` URI; forward the root
`/account` paths from the Next.js origin to this API; verify proxy/access-log
handling does not retain callback code/state query strings; supply secrets
only to the API; verify real provider consent/credentials in the authorized
staging window. Local signed-provider fixtures are protocol proofs, not a live
Google/GitHub acceptance or an access-widening approval.

Official protocol sources inspected 2026-10-29:
- https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps
- https://developers.google.com/identity/openid-connect/openid-connect
- Spring Security managed 7.1.1 source JAR linked above.
