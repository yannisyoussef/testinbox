# Email verification and identity core — M3c

Implemented under accepted ADR-036, with no HTTP issuer, provider connection
or deployment enabled by this increment. The next increment wires these
application use cases to the authored HTTP contract and a transactional-mail
adapter. Existing contact forwarding/inbound SMTP cannot send login codes.

## Decisions implemented

- Practical ASCII contact addresses are case-normalized; Gmail dots and plus
  suffixes are preserved. Quoted and internationalized local parts are not
  accepted in this first implementation. Reject CR/LF before normalization.
- An identity is provider + issuer + immutable subject. Google/GitHub protocol
  proof must come from the maintained OAuth adapter; these persistence models
  do not validate tokens. A contact collision refuses a new login method.
  Existing provider subjects do not silently change their contact address.
- Registration is serialized by one database lock, including across API
  instances. Its total verified-account inventory is bounded at 1,030; this
  includes admitted and unadmitted accounts. A separate explicit waitlist bound
  and lifecycle are still required. Neither registration nor code verification
  calls participant admission, creates a workspace or issues a machine key.
- Code generation is uniform over eight digits. Verifiers use HMAC-SHA256 with
  a 32-byte deployment key, a fixed purpose and the challenge ID. The key stays
  outside PostgreSQL. Browser and source hashes have distinct purposes; source
  identifiers are keyed hashes. Code/secret wrappers redact string output.
- Ten-minute code validity, five failures, atomic single-use consumption and
  browser binding. Another browser cannot spend attempts or consume a code.
  A resend replaces only that browser's active challenge after the resend
  limit passes. A late delivery result cannot revive a replaced challenge.
- Initial sending ceilings: 200/hour globally, 15/15 minutes per verified
  deployment source (its hourly equivalent stays under the global hour, so one
  source cannot block everyone), 3/15 minutes per recipient and a 60-second resend gap.
  Pending, failed and consumed attempts still count. Retain at most 5,000
  challenge rows; purge after 24 hours, on request and via a minute scheduler.
  The short resend refusal returns its actual remaining delay; other ceilings
  return a conservative window. These are login-abuse controls, not storage
  allowances or tenant message quotas.
- Delivery happens outside the registration lock. Failed delivery has the same
  public result shape and cannot authorize a code; a closed-enum observation
  records failure without an address, code or provider exception. Live wiring
  must supply real metrics. No retry stores plaintext codes in an outbox.
- Verification, account creation and session issuance commit together. Failure
  restores the code's usability and does not leave an account/session behind.
  Account inactivity still prevents new sessions.

## Persistence, restore and cleanup

V14 is expand-only. `human_identity` is retained control-plane personal data;
`email_login_challenge` and `human_auth_gate` are excluded from backups, as are
V13 browser sessions. The single-row registration lock recreates atomically
on first use after restore. The backup checker explicitly refuses retaining
these transient tables; genuine pg_dump tests include nonempty rows in each.
The minute cleanup skips incompatible schemas and records success/failure
counters. It remains active even when signup is disabled, so idle/disabled
periods do not depend on another applicant to remove old challenge data.

The existing production backup already retains control-plane tables. Added
identity data still requires accurate notices, deletion/restore semantics and
operating retention decisions before participant activation. This increment
is not a claim that those later lifecycle gates are complete.

## Evidence and limits

27 real PostgreSQL flow/budget/identity cases, including independent adapters
racing one code, one recipient send and the final verified-account inventory
place. Four cryptographic-helper and three contact/provider-shape unit cases;
writer guards with separate negative assertions for concrete identity and
challenge writes. Three cleanup scheduler cases prove schema refusal, normal
cleanup and visible failure. The full backend verifier confirms 1,552 tests;
backup suite 175/175 and Detekt pass. Exact commands are in VERIFICATION.md.

No email was sent externally. These tests do not prove SMTP credentials,
provider delivery, Google/GitHub OAuth, the browser/BFF cookie path, waitlist,
resource fairness, suspension of machine credentials/ingestion or deletion
recovery. Those remain explicit M3/M4/M5/M6 work. A source identifier must come
from the verified deployment peer; do not trust arbitrary forwarded headers.
When using a BFF, shared-peer limits aggregate its traffic unless a separately
reviewed trusted-source mechanism is configured. TI-DELIVERY-002 adds that
mechanism: an optional shared secret lets the website proxy name the client
address. IPv6 addresses are grouped by /64. Without the secret, every request
falls back to the shared peer budget. See
[ADMISSION-AND-SOURCE.md](../ti-delivery-002/ADMISSION-AND-SOURCE.md).

## M3d — HTTP and delivery adapter

The main authored contract now includes `/account/email/request` (202) and
`/account/email/verify` (204 with two separate Set-Cookie values). DTO fields
are checked explicitly, and JSON bodies are limited to 4 KiB before parsing,
including chunked requests and encoded paths. Origin checks apply before
verification. A login browser secret is never returned in JSON. Verification
sets the human session cookie and clears the challenge cookie; same-account
re-authentication invalidates the preceding credential.

Email remains off by default. Invalid human origins and incomplete email
configuration return 503 for the new flow while existing `/v1` Bearer requests
keep working. The configuration gauge describes syntactic readiness, not
provider delivery; closed-enum sending/verification counters expose failures.
No provider exception, recipient, code or credential is a metric label.

The delivery adapter uses Spring's mail abstraction and the repository's
existing pinned Jakarta Mail/Angus dependencies. It does not enable generic
`spring.mail` auto-configuration. It requires authenticated port 587 with
STARTTLS, hostname verification, TLS 1.2/1.3 and finite five-second connection,
read and write timeouts. A real local SMTP peer advertising authentication
without STARTTLS receives EHLO but no authentication, recipient or code.
There is no implicit sending identity or inbound-edge fallback.

### Environment contract for later Ops review

The real shipped YAML maps these exact names; binding tests read that YAML.
These describe setup requirements, not instructions to enable them now.

| Name | Purpose / current default |
|---|---|
| `TESTINBOX_ACCOUNTS_ENABLED` | Human boundary, false |
| `TESTINBOX_ACCOUNTS_PUBLIC_ORIGIN` | Explicit canonical HTTPS origin, empty |
| `TESTINBOX_ACCOUNTS_CLIENT_ADDRESS_SECRET` | Optional website-proxy secret for per-client source budgets (TI-DELIVERY-002), empty |
| `TESTINBOX_ACCOUNTS_EMAIL_ENABLED` | Email sign-in, false |
| `TESTINBOX_ACCOUNTS_EMAIL_VERIFICATION_KEY_BASE64` | Base64 of 32 random secret bytes, empty |
| `TESTINBOX_ACCOUNTS_EMAIL_SMTP_HOST` | Approved transactional provider hostname, empty |
| `TESTINBOX_ACCOUNTS_EMAIL_SMTP_PORT` | 587, required STARTTLS |
| `TESTINBOX_ACCOUNTS_EMAIL_SMTP_USERNAME` | Sending credential, empty |
| `TESTINBOX_ACCOUNTS_EMAIL_SMTP_PASSWORD` | Sending secret, empty |
| `TESTINBOX_ACCOUNTS_EMAIL_SMTP_FROM` | Approved authenticated sending address, empty |

New account/provider credentials must be API-only, host-secret material. Do
not place them in the frontend, ingestion environment, shared Git remote or
chat. Ops still needs to confirm the sending identity's provider authentication,
its product sending budget and network reachability. Existing forwarding aliases
and inbound SMTP do not provide this. No host/env/DNS change or external email
was made by this delivery increment.

Final verification: full backend build and 1,570-test result verifier pass,
zero failures/errors/skips. The fixture-backed HTTP journey, genuine plaintext
SMTP refusal, misconfiguration isolation and real-YAML env binding cases are
included. This is not a real Brevo/provider delivery or browser/BFF proof;
those remain acceptance work with authorized credentials and activation.
