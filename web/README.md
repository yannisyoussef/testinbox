# TestInbox Inspector (web)

Minimal debugging/inspection UI for TestInbox — a walking-skeleton tool, not
a dashboard. Paste an API key and an inbox ID (kept in `sessionStorage`),
browse the inbox's messages, and inspect a message's headers, text body,
extracted links (text only, never fetched), attachment metadata, and a
sanitized HTML preview.

Security posture (ADR-011, `docs/security/threat-model.md`):

- Untrusted `htmlBody` is never injected into the page DOM. It is sanitized
  with DOMPurify and rendered only inside `<iframe sandbox="">` via
  `srcdoc`, prefixed with a `default-src 'none'` CSP meta tag so no remote
  resource (tracking pixel, font, …) ever loads. No image proxy exists.
- Extracted links are displayed as plain text and never auto-fetched.
- The API key travels in the `x-testinbox-key` header to a same-origin
  proxy (`/api/backend/[...path]`), which forwards it as
  `Authorization: Bearer` to the backend. Keys never appear in URLs.

## API key management is deliberately not here

TestInbox now has a managed credential lifecycle
([ADR-032](../docs/adr/0032-api-key-credential-lifecycle.md)), and this UI does
**not** expose it. That is a decision, not a gap.

This page has no authenticated workspace context: its "login" is a pasted API
key held in `sessionStorage`. A key-management screen built on that would have
to render a freshly minted credential in the browser — the one value that is
shown exactly once and can never be recovered — with nowhere safe to put it and
every incentive for the page to persist it "so you do not lose it". That is
precisely the insecure browser persistence the increment set out to avoid.

Key management is therefore API-first (`docs/dev/api-keys.md`). A screen here
becomes reasonable once the dashboard has real human login and a workspace
session of its own, which ADR-010 scopes as separate work.

## Run

Requires Node >= 20.

```sh
npm ci
TESTINBOX_API_URL=http://localhost:8080 npm run dev
```

Then open http://localhost:3000. `TESTINBOX_API_URL` defaults to
`http://localhost:8080` (the local backend from `./gradlew :api:bootRun`).

## Test

```sh
npm test
```

Runs the Playwright suite headless against a fixture mock backend
(`tests/mock-backend.mjs`) with hostile HTML (script payload, tracking
pixel, `javascript:` link) and asserts the preview neutralizes all of it.
First time: `npx playwright install chromium`.

## Build

```sh
npm run build
```
