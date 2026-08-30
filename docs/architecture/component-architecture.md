# Component Architecture

## Style: modular monolith + independently scalable ingestion

See [ADR-001](adr/../adr/0001-architecture-style.md). Microservices are
rejected for MVP: there is no concrete scaling, isolation, or independent
deployment need that a modular monolith cannot satisfy, and splitting
services now would multiply operational cost without evidence of benefit.

The one deliberate exception is the **Inbound Mail Gateway**, which is kept
independently deployable/scalable from day one because it has a genuinely
different profile from the rest of the system:

- It terminates untrusted network input (SMTP) or is invoked by an external
  provider (SES via SNS/Lambda-style push) — different network exposure and
  attack surface than the authenticated REST API.
- It may need to scale independently under inbound mail burst load,
  decoupled from API request load.
- It is the natural boundary for hostile/malformed MIME parsing, so
  isolating it limits blast radius of a parser vulnerability or resource
  exhaustion (MIME bombs, zip bombs) away from the API process.

## Modules (single deployable "core" backend, Gradle multi-module)

Dependency model: ports-and-adapters (see
[ADR-024](../adr/0024-application-layer-and-dependency-rule.md)) — the
dependency rule is `domain` ← `application` ← adapters. Both entry points
(HTTP API and ingestion gateway) invoke the same application use cases, so
business invariants (inbox lifecycle, dedup, visibility+notify atomicity)
exist exactly once.

```mermaid
flowchart TB
    subgraph Core[Backend — modular monolith]
        APIm[api adapter\nSpring HTTP] --> App[application\nuse cases + ports]
        Auth[auth] --> App
        App --> Domain[domain]
        Persistence[persistence adapter] -. implements ports .-> App
        Storage[storage adapter] -. implements ports .-> App
        Wait[wait/notify adapter] -. implements ports .-> App
    end
    Ingestion[ingestion-gateway adapter\n(separately deployable; embeds\napplication/domain/adapters as libraries)] --> App
```

- **domain**: framework-free core model (Inbox, Message, Attachment,
  Workspace, Project, ApiKey…) and domain services. Depends on nothing —
  no Spring, no JPA, no provider-specific types. Enforced by ArchUnit rule
  (see quality strategy).
- **application**: use cases (`CreateInbox`, `ReserveExactAddress`,
  `ReceiveInboundDelivery`, `WaitForMessage`, `ExpireInboxes`) and the port
  interfaces they need (`InboxRepository`, `MessageRepository`,
  `BlobStore`, `MessageNotifier`, `Clock`). All invariant-bearing writes
  live here, once. Depends only on `domain`.
- **api**: Spring Boot HTTP adapter implementing the contract-first
  OpenAPI interfaces (ADR-022), auth filter, RFC 7807 error mapping.
- **wait/notify**: infrastructure adapter for the wait primitive
  (`docs/architecture/wait-semantics.md`) — maintains the `LISTEN`
  connection (with ADR-020 reconnect/re-query recovery) and resolves
  parked long-poll requests.
- **persistence**: Postgres adapter implementing repository ports,
  workspace-scoped queries.
- **storage**: S3-compatible object storage adapter for raw MIME/attachments.
- **auth**: API key issuance/validation, scope enforcement.
- **ingestion-gateway**: a separate deployable process implementing
  `InboundMailProvider` adapters (local SMTP, future SES/Postfix) and MIME
  parsing, then invoking the shared `ReceiveInboundDelivery` use case once per
  inbound event (ADR-026) —
  never writing through persistence directly. It embeds
  `application`/`domain` and the infrastructure adapters as libraries and
  talks to the same Postgres/object storage, so it is "independently
  deployable," not "independently owns data" (and not "independently
  reimplements invariants").

## Why not split further

`api` and `wait/notify` are not split into separate services because they
share the same request-latency budget, deployment cadence, and team, and
splitting them would only add a network hop with no isolation benefit. This
should be revisited if wait traffic ever needs to scale independently of CRUD
API traffic — no such evidence exists yet.
