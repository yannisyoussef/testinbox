# ADR-007: Event/Coordination Strategy

**Status:** Accepted

## Context

`waitForMessage` must avoid busy polling and must work across horizontally
scaled API nodes without sticky routing.

## Decision

Use PostgreSQL `LISTEN/NOTIFY` for MVP as the wait-notification fan-out
mechanism: the ingestion path issues `NOTIFY` on message persistence; API
nodes hold `LISTEN` connections and resolve blocked wait requests on
notification. This works across nodes because notification is delivered by
the shared database to every listening connection, satisfying the
horizontal-scaling requirement in `docs/architecture/wait-semantics.md`
without additional infrastructure.

## Alternatives considered

- **Redis pub/sub**: rejected for MVP — adds an operational dependency not
  yet justified by scale; documented as the natural upgrade path if
  Postgres `LISTEN/NOTIFY` throughput/connection-count becomes a bottleneck.
- **Kafka/event log**: rejected — significant operational overhead
  disproportionate to MVP needs; would only be justified by a broader
  event-sourcing/audit requirement that doesn't yet exist.
- **Client-side polling**: rejected outright — explicitly what the wait
  primitive must avoid per the product brief.

## Consequences

- Notification delivery depends on Postgres connection limits; each API
  node needs at least one dedicated `LISTEN` connection, which must be
  accounted for in connection-pool sizing.
- If this stops scaling, the upgrade path (Redis pub/sub) is designed to be
  swappable behind the same internal `wait/notify` module interface.
