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
