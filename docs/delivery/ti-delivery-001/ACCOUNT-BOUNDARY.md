# Human account boundary — implementation increment

ADR-036 is accepted for implementation. This increment provides the session
lifecycle and its authored HTTP boundary; email/provider verification is the
next increment. It is not a complete signup feature or an activation request.

## Contract and separation

`backend/api/contract/openapi.yaml` defines root-level `/account/session`,
`/account/logout` and `/account/admission`, with path-level server `/`.
Existing `/v1` operations retain their `/v1` server and Bearer authentication.
Contract coverage resolves operation/path/root server inheritance and checks
both route families for missing or undocumented handlers.

`testinbox.accounts.enabled` defaults false. All account routes then return
503 without issuing cookies or provisioning anything. Enabling the boundary
requires an explicit canonical HTTPS `testinbox.accounts.public-origin`.
No origin is inferred from Host, Forwarded or X-Forwarded-* headers.
The boundary also runs against the resolved controller so URL normalization
cannot bypass the flag or exact-origin check. This increment has no login
credential issuer reachable over HTTP.

## Sessions

- Thirty-two random bytes, base64url without padding. Only SHA-256 verifiers
  enter PostgreSQL; credential wrappers redact their string representation.
- Seven days absolute, twenty-four hours idle. Activity never extends the
  absolute lifetime. Expiry boundaries and backwards-clock observations refuse.
- Five sessions per account, serialized on its account row across API nodes.
  New issuance removes expired rows and evicts the oldest excess sessions.
- Authentication, issuance and revocation use the same account-before-session
  lock order. Each authentication rechecks verification and active account
  state; it does not cache account authority or take a workspace from a cookie.
- Rotation and session eviction are one transaction. A failed insert restores
  the preceding cookie's validity through rollback. Account deletion cascades
  sessions. Operator suspension and machine-key/ingestion lifecycle remain part
  of the later account-lifecycle increment.
- `human_session` is explicitly excluded from backups. The backup gate refuses
  reclassifying it as retained and tests a real dump containing session data.
  Restoring control-plane backups cannot restore a browser login.

The cookie name is `__Host-ti_session`: Secure, HttpOnly, SameSite=Lax,
Path=/, no Domain. Set and clear use the same attributes. Duplicate cookies
and duplicate CSRF headers fail closed.

Mutations additionally require a session-bound CSRF token and the exact public
Origin. The CSRF token uses JDK HMAC-SHA256 with the high-entropy HttpOnly
credential as its key and a fixed, separate purpose label. It is distinct from
both the bearer value and its persisted verifier, and rotation changes it.
It grants no authentication authority. JSON responses never return the session
credential. All account responses, including errors, have Cache-Control:
no-store; the boundary does not enable CORS.

Background reference checked: [OWASP CSRF prevention guidance](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html).

## Admission and pending work

The HTTP handler passes only the authenticated account ID to the existing
`AdmitBetaParticipant` transaction. A request cannot set an account, workspace,
project, cap or policy flag. Existing membership is returned unchanged on retry.
The cohort remains closed and capacity-unavailable by default. A session alone
cannot read `/v1`, and an API key cannot authenticate the human boundary.

Next: real email challenges, Google/GitHub verification with maintained Spring
OAuth libraries, bounded account/waitlist state, operator controls and resource
policy. Then connect the Next.js same-origin proxy and real console authority.
Before activation, test cross-repository cookies/callbacks and the full product
loop, complete deletion/restore behavior, resolve ADR-035 restrictions and
obtain the separate release/access decision. No infrastructure qualification
is reopened by this implementation increment.
