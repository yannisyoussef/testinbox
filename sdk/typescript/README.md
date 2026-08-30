# @testinbox/client

Official TestInbox SDK for TypeScript/JavaScript: ephemeral email inboxes,
real SMTP ingestion, and deterministic `waitForMessage` for automated tests.

- Node >= 20, zero runtime dependencies (uses the global `fetch`).
- Async/await only; dual ESM/CJS build with bundled type definitions.
- Hand-designed public API (ADR-014) over the v1 REST contract.

## Install

```sh
npm install --save-dev @testinbox/client
```

## Quick usage

```ts
import { TestInboxClient, TestInboxTimeoutError } from "@testinbox/client";

const client = new TestInboxClient({ apiKey: process.env.TESTINBOX_API_KEY! });
// Or rely on the documented env fallback: TESTINBOX_API_KEY / TESTINBOX_BASE_URL.

// 1. Create an ephemeral inbox.
const inbox = await client.createInbox({ ttlSeconds: 600, aliasHint: "signup" });

// 2. Point your app at it and trigger the email.
await registerUser({ email: inbox.address });

// 3. Deterministically wait for the message (long-poll; no sleeps, no polling loops).
try {
  const message = await inbox.waitForMessage({
    timeoutMs: 30_000,
    from: "noreply@myapp.example",
    subjectContains: "Verify your email",
  });

  expect(message.subject).toContain("Verify");
  const verifyLink = message.links.find((l) => l.href.includes("/verify"));
  // TestInbox never fetches links itself — your test drives the browser/HTTP call.

  const rawMime = await message.raw(); // Uint8Array of the exact wire bytes
} catch (e) {
  if (e instanceof TestInboxTimeoutError) {
    // Rich diagnostics: did mail arrive but not match? Did parsing fail?
    console.error(e.elapsedMs, e.arrivedButUnmatchedCount, e.parseFailedCount);
  }
  throw e;
} finally {
  // 4. Tear down (inboxes also expire automatically at their TTL).
  await inbox.delete();
}
```

### Matchers

`waitForMessage` matches a message iff **all** specified fields match; with no
matcher it resolves on the first parsed message:

```ts
await inbox.waitForMessage({
  from: "noreply@myapp.example",     // case-insensitive exact address match
  subjectContains: "Verify",
  subjectEquals: "Verify your email",
  headers: [{ name: "X-Campaign" }, { name: "X-Env", value: "staging" }],
});
```

### Timeout semantics (ADR-020)

The server caps a single wait call (60 s); the SDK transparently chains
long-poll calls until your overall `timeoutMs` budget (default 30 000 ms)
expires. A server-side window expiry is **not** an error — only budget
exhaustion throws `TestInboxTimeoutError`, carrying the last poll's
`arrivedButUnmatchedCount` / `parseFailedCount` diagnostics.

### Error taxonomy

All errors extend `TestInboxError` and carry RFC 7807 problem details
(`problemType`, `title`, `detail`, `correlationId`):

| Class | HTTP |
| --- | --- |
| `TestInboxAuthError` | 401 |
| `TestInboxForbiddenError` | 403 |
| `TestInboxNotFoundError` | 404 |
| `TestInboxConflictError` (`retryAfterSeconds`) | 409 |
| `TestInboxInboxGoneError` | 410 |
| `TestInboxApiError` | other non-2xx |
| `TestInboxTimeoutError` | overall wait budget expired |

## Development

```sh
npm ci
npm run build            # dual ESM/CJS + d.ts into dist/
npm test                 # unit tests (mocked fetch)
npm run test:integration # live tests; requires TESTINBOX_BASE_URL + TESTINBOX_API_KEY
```
