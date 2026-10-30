# Post-deployment synthetic verification

A real TestInbox workflow executed **through a deployed environment**, driven by
the public TypeScript SDK built from the same commit as the deployed images.
This is the gate that decides whether a staging deployment succeeded — not
`/health` (TI-DEPLOY-001 §17/§19).

## What it proves

| Test | What breaks if it fails |
|---|---|
| `synthetic.test.mjs` — full workflow | The deployed API, gateway, PostgreSQL, object store, parser or ingress |
| `longpoll.test.mjs` — full window through the ingress | The reverse-proxy read timeout is shorter than the wait window (§13) |
| `longpoll.test.mjs` — parked wait woken by SMTP | `LISTEN/NOTIFY` is not reaching waiters — e.g. the database is behind a transaction-mode pooler (ADR-020) |
| `events.test.mjs` — a stream event arrives before its window ends | A hop in front of the API buffers `text/event-stream` responses, so the console feed sees nothing until the window closes; or the artifact predates the console reads |
| `edge.test.mjs` — edge invariants | The ingress is serving what it must refuse, or refusing what it must serve |
| `storage.test.mjs` — ADR-035 §13 storage visibility | The deployed artifact predates TI-STORAGE-004 (`GET /v1/workspace/storage` is `404`), a tenant response carries a global figure, a legacy wait without the cursor gets a `409`, or a storage refusal was recorded although live enforcement must be OFF |

## The product suite is separate

`product/apikeys.test.mjs` exercises the credential lifecycle (ADR-032) and is
**not** part of the deployment gate. Two reasons:

- It needs a credential that can administer keys
  (`TESTINBOX_ADMIN_API_KEY`). The gate's own credential stays least-privilege,
  because a key that can mint keys is a much larger thing to leave in an
  automated runner.
- "The credential lifecycle is broken on an otherwise healthy deployment" is a
  different verdict from "do not ship this artifact". Conflating them would
  make the gate noisier without making it stronger.

```bash
TESTINBOX_ADMIN_API_KEY=… npm run test:product
```

It mints two short-lived credentials, proves one authenticates, revokes it,
proves another still works, then revokes everything it created. Revoked keys
are retained by design (ADR-032 §5), so each run leaves two inert rows behind.

## Two host-side targets for a production estate (TI-006, ADR-034)

Neither is part of the deployment gate, and neither may skip: each fails
loudly when its variables are absent.

| target | proves | where it runs |
|---|---|---|
| `npm run test:identity` | the running build on **both** deployables is the approved source commit, readiness is UP with `waitNotifier.listening`, and the database session bound is reported — in production, enforced and bounded (`dbSession`) | on the host, against the private management ports (`TESTINBOX_API_MANAGEMENT_URL`, `TESTINBOX_INGESTION_MANAGEMENT_URL`, `TESTINBOX_EXPECTED_GIT_SHA`, `TESTINBOX_EXPECTED_ENVIRONMENT`) |
| `npm run test:origin` | a direct connection to the origin's own address is **not answered** on any trust-boundary port (443, 80, 25, 2525, 9090, 9091, 5432, 9000), while the public hostname is reachable **over the same address family** (positive control) | from OUTSIDE the host; Ops supplies `TESTINBOX_ORIGIN_PROBE_ADDRESS` (an IP literal) at run time — the address is never committed. Neither suite needs the synthetic API key or the SMTP host |

The origin classifier (`src/origin.mjs`) is the deliberate mirror of the
unknown-Host one: there a timeout proves nothing, here a timeout is exactly
what a DROPping firewall produces and is the proof. Both have unit tests.

## Account boundary (`test:accounts`)

`accounts/accounts.test.mjs` asserts the human account boundary (ADR-036,
TI-DELIVERY-002) from where a browser reaches it: the **public site** origin,
whose same-origin `/account/*` routes forward to the API. It is a separate
target, like `test:identity`: it needs none of the gate's credentials, the
gate must not require its variables, and it fails loudly — never skips — when
they are absent.

| variable | meaning |
|---|---|
| `TESTINBOX_ACCOUNTS_ORIGIN` | the public site origin, exactly `https://<host>` (the API's `TESTINBOX_ACCOUNTS_PUBLIC_ORIGIN` when accounts are on) |
| `TESTINBOX_ACCOUNTS_EXPECTED` | `disabled` or `enabled` — the environment's **declared** state. Never inferred from what the environment answers: a suite that learned "disabled" from a `503` would pass on a deployment whose accounts were meant to be on and broke |
| `TESTINBOX_ACCOUNTS_EXPECTED_METHODS` | required when `enabled`: `none`, or a comma-separated subset of `email,google,github`. An incomplete method configuration does not stop the API; the method silently disappears, so the declaration is what catches it |
| `TESTINBOX_MAIL_DOMAIN` | the deployment's receive domain (`inbox.testinbox.email` in production) |
| `TESTINBOX_ACCOUNTS_TIMEOUT_MS` | optional, default 15000 |

What it proves, adapting every assertion to the declared state:

| test | `disabled` (production today) | `enabled` |
|---|---|---|
| methods | `/account/methods` answers the closed state | exactly the declared methods, `{methods}` and nothing else, `no-store`, no cookie |
| every route | all fourteen proxied routes answer the closed state: `503`, `no-store`, no cookie, no authority-bearing member — from either layer (the site's own `Sign-in is unavailable` / `Console unavailable`, or the API's `accounts-unavailable`) | every route is `no-store` and none answers a server error |
| unauthenticated read | closed | `401`, and a forged session cookie is refused exactly like none |
| CSRF | closed | logout, code request and waitlist join without/with a foreign `Origin` are `403`; the same logout from the public origin is `204` (positive control) |
| Google / GitHub start | closed | a declared provider redirects `302` to its fixed endpoint with `response_type=code`, PKCE `S256`, opaque state, a Google nonce, the fixed scopes and **exactly** `<origin>/account/oauth/callback/<provider>`, plus one private `__Host-ti_oauth` cookie; an undeclared one is `503` with no cookie |
| code request | closed | a malformed address and an address in the receive domain are refused identically (`400`, or `503` when email is not declared), with no challenge started |
| sign-in pages | `/login` and `/signup` are the TestInbox site and advertise no places | both are served |

**Safe in production, and repeatable.** It sends no email (both code requests
carry addresses the API refuses before charging a budget or writing a row),
creates no account, session, admission, waitlist entry, participant workspace
or storage pool — so it consumes no seat and never crosses the TI-DELIVERY-001
rollback floor — and never completes a provider sign-in. When accounts are
enabled it spends one provider start per declared provider (a transient,
ten-minute attempt row, counted against the 200/hour global and 15/15-minute
per-source start budgets) and one session-less logout. Until the site attests
the client address (TI-DELIVERY-002 stream A/W), that per-source budget is the
site container's, shared with every visitor: do not run it in a loop.

```bash
TESTINBOX_ACCOUNTS_ORIGIN=https://testinbox.email \
TESTINBOX_ACCOUNTS_EXPECTED=disabled \
TESTINBOX_MAIL_DOMAIN=inbox.testinbox.email \
npm run test:accounts
```

The classifiers live in `src/accounts.mjs` with their own unit tests
(`unit/accounts-classifier.test.mjs`, part of `npm run test:unit`). The local
rehearsal does not run this target: it deploys the Inspector, not the public
site, so nothing there serves `/account`.

## Running it

```bash
npm --prefix ../../sdk/typescript ci && npm --prefix ../../sdk/typescript run build
npm ci
TESTINBOX_BASE_URL=https://staging.testinbox.email \
TESTINBOX_API_KEY=…                 \
TESTINBOX_SMTP_HOST=…               \
TESTINBOX_SMTP_PORT=2525            \
npm test
```

Nothing has a localhost default: the suite refuses to run without an explicit
target, because a synthetic test that silently falls back to a local process
passes while proving nothing.

TLS verification is never disabled. Against an environment using a private CA,
point Node at it (`NODE_EXTRA_CA_CERTS=/path/to/ca.pem`) — trusting a specific
CA is not the same as skipping verification.
