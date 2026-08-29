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

```mermaid
flowchart TB
    subgraph Core[Backend — modular monolith]
        APIm[api] --> Domain[domain]
        Wait[wait/notify] --> Domain
        Domain --> Persistence[persistence]
        Domain --> Storage[storage]
        Auth[auth] --> Domain
    end
    Ingestion[ingestion-gateway\n(separately deployable)] --> Persistence
    Ingestion --> Storage
    Ingestion -->|notify| Wait
```

- **domain**: framework-free core model (Inbox, Message, Attachment,
  Workspace, Project, ApiKey…) and domain services. No Spring, no JPA, no
  provider-specific types. Enforced by ArchUnit rule (see quality strategy).
- **api**: Spring Boot HTTP layer, OpenAPI contract, request/response DTOs,
  auth filter, RFC 7807 error mapping.
- **wait/notify**: implements the wait-for-message primitive
  (`docs/architecture/wait-semantics.md`) — subscribes to persistence-layer
  change notifications and resolves matching long-poll requests.
- **persistence**: Postgres access (repositories), workspace-scoped queries.
- **storage**: S3-compatible object storage adapter for raw MIME/attachments.
- **auth**: API key issuance/validation, scope enforcement.
- **ingestion-gateway**: a separate deployable process/module implementing
  `InboundMailProvider` adapters (local SMTP, future SES/Postfix), MIME
  parsing, and initial persistence of received messages. Talks to the same
  Postgres/object storage, so it is "independently deployable," not
  "independently owns data."

## Why not split further

`api` and `wait/notify` are not split into separate services because they
share the same request-latency budget, deployment cadence, and team, and
splitting them would only add a network hop with no isolation benefit. This
should be revisited if wait traffic ever needs to scale independently of CRUD
API traffic — no such evidence exists yet.
