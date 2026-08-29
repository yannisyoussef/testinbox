# ADR-012: Wait-for-Message Semantics

**Status:** Accepted (amended by [ADR-020](0020-wait-reliability-and-timeout-semantics.md):
wait-window expiry returns `200` with an explicit `TIMEOUT` result — not
`408` — and the visibility/notification atomicity and `LISTEN` recovery
requirements defined there are part of the normative contract)

## Context

`waitForMessage()` is the product's core primitive and the most
race-condition-prone piece of the system. Its contract must be explicit.

## Decision

Full contract specified in `docs/architecture/wait-semantics.md`: server-side
long-poll implementing check-then-subscribe-then-recheck (closing the
arrived-before-waiter race), matching against `Parsed` messages only,
non-consuming (a message can satisfy multiple waiters and remains queryable
afterward), capped per-call duration with SDK-side long-poll chaining for
longer caller-requested timeouts, and push-based (not polling) internal
implementation via `LISTEN/NOTIFY` (ADR-007).

## Alternatives considered

- **WebSocket/SSE push to the client**: rejected for MVP — adds client-side
  complexity across three SDK ecosystems (JVM, TS, future) for a primitive
  that a capped long-poll satisfies adequately; may be revisited if
  dashboard "live updates" become a driving requirement.
- **Server holds an unbounded-duration connection for the client's full
  requested timeout**: rejected — unbounded per-request server resource
  holding doesn't scale predictably; capped calls with SDK-side chaining
  keep server resource usage bounded and observable per call.
- **Message consumed/removed upon a successful wait match**: rejected — an
  inbox's message list is a persisted log for debugging/multiple assertions;
  a queue-consume semantic would surprise callers doing `GET` after `wait`.

## Consequences

- SDKs must implement long-poll chaining faithfully; this is a documented
  SDK responsibility (`docs/sdk/principles.md`), not optional polish.
- The check-then-subscribe-then-recheck sequence must be tested explicitly
  for the race window it closes (`docs/quality/strategy.md`, concurrency
  tests).
