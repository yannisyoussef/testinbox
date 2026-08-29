# SDK Design Principles

1. **SDKs are hand-designed products, not generated wrappers.** OpenAPI may
   generate an internal transport client; it must never be the public API
   surface (see [`architecture.md`](architecture.md) and
   [ADR-014](../adr/0014-sdk-architecture.md)).
2. **Resource-oriented ergonomics**: `client.inboxes.create(...)` /
   `testInbox.createInbox(...)` returning a rich `Inbox` object with methods
   (`awaitMessage`, `waitForMessage`), not a bag of DTOs the caller has to
   wire together manually.
3. **A small, consistent matcher vocabulary across languages** (`from`,
   `subjectContains`, `subjectEquals`, header matchers) — the same concepts
   named idiomatically per language (builder/DSL in Kotlin, fluent object in
   TypeScript), not a divergent feature set per SDK.
4. **Long-poll chaining is an SDK responsibility, not a server one.** Per
   [`docs/architecture/wait-semantics.md`](../architecture/wait-semantics.md),
   the server caps a single wait call's duration; SDKs implement a caller's
   requested `timeout` by issuing repeated calls internally, invisible to
   the caller.
5. **Forward-compatible parsing.** SDKs must not fail on unknown response
   fields or unknown enum values (deserialize permissively; treat unknown
   enum values as an explicit "unknown" variant, never throw), so the server
   can evolve additively without breaking installed SDK versions.
6. **Error mapping**: RFC 7807 problem responses map to a typed exception
   hierarchy per language (e.g., `TestInboxNotFoundException`,
   `TestInboxRateLimitedException`), not a generic "HTTP error" leaked to
   callers.
7. **No framework lock-in in the core SDK.** JUnit 5/TestNG/Playwright/etc.
   integrations are separate packages depending on the core SDK
   (`docs/sdk/architecture.md`), so using TestInbox never forces a specific
   test framework choice.
8. **Explicit, minimal configuration**: API key + base URL (for self-hosted),
   with sane defaults (hosted `api.testinbox.email`) — no hidden
   environment-variable magic beyond a documented, opt-in convention
   (e.g., `TESTINBOX_API_KEY`).
9. **Sync/async story per language's idiom**, not a forced uniform model:
   Kotlin offers coroutines with a blocking-friendly facade for plain Java
   callers; TypeScript is Promise/async-await only (no callback API).
10. **Assertion-adjacent, not an assertion framework.** SDK matcher objects
    expose plain data (`message.subject`, `message.links`) so callers use
    whatever assertion library they already have; TestInbox does not ship
    its own `assertThat`.
