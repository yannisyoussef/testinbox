# CLAUDE.md — TestInbox repository guidance

TestInbox is an automation-first email testing platform (ephemeral inboxes,
real SMTP ingestion, deterministic wait-for-message). Read this before
changing anything.

## Where authority lives

- **Accepted ADRs in `docs/adr/` are authoritative.** `docs/architecture/`,
  `docs/api/`, `docs/sdk/`, `docs/security/`, `docs/quality/` are the
  normative elaboration. Code serves the ADRs, not the other way around.
- `Proposed` ADRs (currently 0004, 0016, 0017, 0018) require a human
  decision before implementation may rely on them. **Never silently promote
  a Proposed ADR to Accepted.**
- If an Accepted ADR appears impossible/contradictory during implementation:
  stop that piece of work, explain the contradiction, identify the affected
  document, propose the smallest correction — do not silently bypass or
  reinterpret it. Supersede ADRs with new ADRs; never rewrite history.
- The REST contract is **contract-first**: `backend/api/contract/openapi.yaml`
  is hand-authored and committed (ADR-022). Never generate the public
  contract from Spring controllers. API changes start as a spec diff in the
  same PR as the implementation.

## Dependency rule (ADR-024, enforced by ArchUnit in `backend/architecture`)

```
domain  ←  application  ←  adapters (api, ingestion, persistence, storage, notification)
```

- `domain`: framework-free. No Spring, no JPA, no SQL, no provider or
  storage types. Java/Kotlin stdlib only.
- `application`: use cases + outbound ports. Depends only on `domain`.
  **Every invariant-bearing write goes through exactly one use case** —
  the ingestion gateway and the API both call the same use cases.
- Adapters depend inward and implement application ports. `ingestion` never
  writes to Postgres/MinIO directly — it invokes `ReceiveInboundDelivery`
  **once per inbound event** (ADR-026), never once per recipient.

## Non-negotiable invariants (each is tested; keep the tests honest)

1. **No content-based dedup (ADR-019).** Two SMTP `DATA` transactions with
   identical bytes ⇒ two `Message` rows. Dedup only on
   `(provider, providerMessageId, envelope recipient)` for reprocessed
   provider events (ADR-026: one provider event may fan out to several
   recipients, and all of its rows commit in ONE transaction).
   Fingerprint is annotation metadata, never a suppression key.
2. **Visibility+notify atomicity (ADR-020).** Message insert and
   `pg_notify()` happen in the same Postgres transaction. Never
   commit-then-notify from application code.
3. **LISTEN recovery (ADR-020).** Session-scoped LISTEN connection (never
   through transaction-mode pooling); on reconnect every parked waiter
   re-queries once; bounded degraded re-query while LISTEN is down; health
   surfaced. Waiters must never sleep past a persisted match.
4. **Wait semantics (ADR-012/020).** check → subscribe → recheck → park.
   Server wait-window expiry is `200 {status: TIMEOUT}` with diagnostics,
   never `408`. Non-active inbox ⇒ `410`. Waits are non-consuming reads.
5. **Unknown recipients (ADR-025).** Uniform SMTP `250` after `DATA`;
   content discarded in-process, never persisted anywhere; metadata-only
   logging. SMTP must not leak recipient existence.
6. **Raw-first storage (ADR-005).** Raw MIME written to object storage
   before the DB row; per-message object keys, never content-addressed;
   parse failure never loses raw bytes (`parseStatus=FAILED`, `/raw` works).
7. **Exact reservations (ADR-021).** Concurrency decided solely by the
   Postgres unique index; loser gets `409`. Cooldown is modeled as explicit
   state (`ACTIVE`/`COOLDOWN`/`RELEASED` + `available_at` with guarded
   transactional reclaim) — **never a `now()`-dependent index predicate**.
   Generated tokens are never reused. Cooldown is config-driven.
8. **Limits (ADR-027).** Rate limits and quotas key on the **workspace**,
   derived from the authenticated key — never on an API key (rotation would
   reset them), a header, or a source IP. Enforcement lives in the
   application layer, never in an HTTP filter, or the ingestion gateway is
   unprotected. **No limit may change an SMTP reply**: a syntactically valid
   recipient always gets the uniform `250` of ADR-025, or the differing
   replies become a workspace-membership oracle. Rate refusals are `429` +
   `Retry-After`; quota exhaustion is `409` (waiting does not help). Quota
   usage is derived from real rows, never a counter — a cascade delete runs
   no application code to decrement one.
9. **Security.** API keys hashed at rest, never logged. Untrusted HTML is
   never rendered on the primary origin (sandboxed iframe, CSP, no remote
   loads — ADR-011). Never auto-fetch URLs from message content. Every
   query workspace-scoped; cross-tenant read is a security bug. Cross-tenant
   lookups return `404`, not `403`.

## Coding conventions

- Kotlin, 4-space indent, formatting enforced by Spotless/ktlint
  (`./gradlew spotlessCheck` / `spotlessApply`).
- Backend runtime Java 25, Spring Boot 4.x. JVM SDK (`sdk/kotlin`) is an
  independent build with **Java 17 bytecode baseline** (ADR-023) — do not
  raise it or leak backend deps into it. TS SDK targets Node ≥ 20.
- Persistence is plain SQL via Spring `JdbcClient` + Flyway migrations in
  `backend/persistence/src/main/resources/db/migration`. Never edit an
  applied migration; add a new one.
- RFC 7807 for all API errors; problem types under
  `https://testinbox.email/problems/...`.
- Conventional Commits (`feat:`, `fix:`, `docs:`, ...).
- SDK public surfaces are hand-designed (ADR-014); transport is an internal
  implementation detail (`internal` packages) — never export it.

## Testing expectations (docs/quality/strategy.md)

- Domain/application logic: JUnit 5 unit tests (+ Kotest property tests
  where useful). Persistence/storage/notification/ingestion: Testcontainers
  (Postgres, MinIO) — no mocked-out database "integration" tests.
- Concurrency-sensitive code (wait, exact reservation, expiry race) gets
  deterministic concurrency tests using synchronization primitives, not
  sleeps.
- Hostile/malformed MIME corpus lives in
  `backend/ingestion/src/test/resources/mime-corpus/` and is a first-class,
  growing asset — add a corpus entry with every parser bug fix.
- CI must verify suites actually produced results:
  `VERIFY_SCOPE=backend|e2e|all scripts/verify-test-results.sh`. The scope is
  mandatory context — CI jobs run disjoint suites, so verifying "everything"
  from a job that ran a subset is a false failure. The verifier and the
  OpenAPI compatibility gate have their own tests (`scripts/*.test.sh`); keep
  them passing, a gate that cannot fail is not a gate.
- Which gates fail CI and which are informational is documented in
  `docs/quality/strategy.md` — do not silently promote or demote one.

## Commands

All backend commands run from `backend/` (Gradle wrapper committed there):

```
./gradlew build                  # compile + unit/integration tests + arch tests
./gradlew spotlessCheck          # formatting
./gradlew :architecture:test     # ArchUnit boundary tests only
./gradlew :e2e:test              # black-box acceptance (needs Docker)
./gradlew :api:bootRun           # run REST API      (needs compose deps up)
./gradlew :ingestion:bootRun     # run SMTP gateway  (needs compose deps up)
./gradlew detekt                 # static analysis — run Gradle on Java 21
```

Detekt is detached from `check` on purpose: its current stable line cannot run
on a Java 25 runtime, so `./gradlew detekt` must be invoked with `JAVA_HOME`
pointing at a Java 21 JDK (the CI static-analysis job does exactly this).
Everything else builds and runs on Java 25.

Local dependencies: `docker compose up -d` from the repo root (Postgres 16 +
MinIO). SDKs: `sdk/kotlin` has its own `./gradlew build`; `sdk/typescript`
and `web` use `npm ci && npm test` / `npm run build`. Full local setup:
`docs/dev/local-setup.md`.

## Specialist subagents

`.claude/agents/` defines reviewers (architecture-reviewer,
backend-engineer, quality-engineer, security-reviewer, email-mime-expert,
api-sdk-reviewer). They are reviewers/specialists — the ADRs remain the only
source of truth; an agent opinion never overrides an Accepted ADR.
