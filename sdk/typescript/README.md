# @testinbox/client

Official TestInbox SDK for TypeScript/JavaScript: ephemeral email inboxes,
real SMTP ingestion, and deterministic `waitForMessage` for automated tests.

- Node >= 22, zero runtime dependencies (uses the global `fetch`).
  Tested on Node 22, 24 and 26. Node 20 is end-of-life (2026-04-30) and is no
  longer a supported floor.
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

### Storage visibility and refusal-aware waits (ADR-035)

Every inbox has a storage ceiling, and so does your workspace. A message that
would exceed one is **refused at ingest**, silently over SMTP; the API records
the refusal on the inbox, and the SDK surfaces it where you are looking:

```ts
import { TestInboxClient, TestInboxStorageLimitExceededError } from "@testinbox/client";

const inbox = await client.createInbox();
try {
  const message = await inbox.waitForMessage({ subjectContains: "Verify your email" });
} catch (error) {
  if (error instanceof TestInboxStorageLimitExceededError) {
    // A storage ceiling refused a copy for this inbox after the boundary this
    // object had observed — possibly the message you were waiting for.
    console.error(error.refusalReason, error.storageRefusalCount, error.limit, error.current);
  }
  throw error;
}
```

- `inbox.storage`, `inbox.storageRefusalCount`, `inbox.lastStorageRefusalAt`
  and `inbox.lastStorageRefusalReason` are **snapshots** of the representation
  you created or fetched; they never change on that object.
- `inbox.storageRefusalCursor` is this object's **observation boundary**: the
  refusal count it has observed. It is seeded from `storageRefusalCount`, and
  it advances in exactly two cases — when a wait surfaces a
  `TestInboxStorageLimitExceededError` (to that error's count) and when you pass
  an explicit `afterStorageRefusalCount` — always to the maximum, never
  backwards. A `MATCHED` or `TIMEOUT` result never advances it, so do not copy
  counts out of those results into it: a match may have outranked a refusal
  you have not seen yet.
- `client.getWorkspaceStorage()` returns your workspace's own `StorageUsage`
  (`limitBytes`, `storedBytes`, `reservedBytes`, `availableBytes`, `overLimit`).
  Every figure is yours; the API never discloses another workspace's or the
  service's totals. A refusal for `SERVICE_CAPACITY` carries the reason only.

Opt out to get the pre-ADR-035 wait, in which a refusal is never surfaced:

```ts
await inbox.waitForMessage({ subjectContains: "Verify", observeStorageRefusals: false });
```

Resume a boundary you persisted yourself (for example across a process restart):

```ts
await inbox.waitForMessage({ subjectContains: "Verify", afterStorageRefusalCount: savedBoundary });
```

The explicit value is applied monotonically to the object's cursor, so a value
below it is overridden by the cursor. Combining it with
`observeStorageRefusals: false` is contradictory and throws a `TypeError`
before any request; a negative, fractional or unsafe value throws a
`RangeError`. The SDK never retries a storage refusal: waiting does not help.

### Error taxonomy

All errors extend `TestInboxError` and carry RFC 7807 problem details
(`problemType`, `title`, `detail`, `correlationId`):

| Class | HTTP |
| --- | --- |
| `TestInboxAuthError` | 401 |
| `TestInboxForbiddenError` | 403 |
| `TestInboxNotFoundError` | 404 |
| `TestInboxConflictError` (`retryAfterSeconds`) | 409 |
| `TestInboxQuotaExceededError` (`quota`, `limit`, `current`) | 409 `quota-exceeded` |
| `TestInboxStorageLimitExceededError` (`inboxId`, `refusalReason`, `afterStorageRefusalCount`, `storageRefusalCount`, `lastStorageRefusalAt`, tenant-scope `quota`/`limit`/`current`) | 409 `storage-limit-exceeded` |
| `TestInboxInboxGoneError` | 410 |
| `TestInboxRateLimitError` (`retryAfterSeconds`, `category`) | 429 |
| `TestInboxApiError` | other non-2xx |
| `TestInboxProtocolError` | a response that violates the contract (a required member absent or malformed) |
| `TestInboxTimeoutError` | overall wait budget expired |

## Development

```sh
npm ci
npm run build            # dual ESM/CJS + d.ts into dist/
npm test                 # unit tests (mocked fetch)
npm run test:integration # live tests; requires TESTINBOX_BASE_URL + TESTINBOX_API_KEY
```
