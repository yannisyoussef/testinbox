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
