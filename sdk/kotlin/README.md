# testinbox-client (JVM)

Official TestInbox SDK for the JVM: ephemeral email inboxes, real SMTP
ingestion, and a deterministic `awaitMessage` for automated tests.

- Kotlin, **Java 17 bytecode baseline** (ADR-023); tested on Java 17, 21 and 25.
- Coroutines for Kotlin callers; blocking facades (`...Blocking`) for plain Java
  and JUnit. Every public type is usable from Java (`JavaInteropTest` compiles
  real Java against the built classes).
- Hand-designed public API (ADR-014) over the v1 REST contract; the transport
  is internal and not part of the surface.

## Quick usage

```kotlin
val client = TestInboxClient(apiKey = System.getenv("TESTINBOX_API_KEY"))
val inbox = client.createInbox()
sut.register(inbox.address)
val message = inbox.awaitMessage(Duration.ofSeconds(30)) { subjectContains("Verify") }
val link = message.links.first().href
inbox.delete()
```

```java
TestInboxClient client = new TestInboxClient(System.getenv("TESTINBOX_API_KEY"));
Inbox inbox = client.createInboxBlocking();
Message message = inbox.awaitMessageBlocking(
    Duration.ofSeconds(30),
    MessageMatcher.builder().subjectContains("Verify").build());
```

The server caps one wait window (60 s); the SDK chains windows until your
timeout expires, then throws `TestInboxTimeoutException` with the last poll's
`arrivedButUnmatchedCount` / `parseFailedCount` diagnostics (ADR-020).

## Storage visibility and refusal-aware waits (ADR-035)

Every inbox has a storage ceiling, and so does your workspace. A message that
would exceed one is **refused at ingest**, silently over SMTP; the API records
the refusal on the inbox, and the SDK surfaces it where you are looking:

```kotlin
try {
    inbox.awaitMessage(Duration.ofSeconds(30)) { subjectContains("Verify your email") }
} catch (e: TestInboxStorageLimitExceededException) {
    // A storage ceiling refused a copy for this inbox after the boundary this
    // object had observed — possibly the message you were waiting for.
    log.error("${e.refusalReason} count=${e.storageRefusalCount} limit=${e.limit} current=${e.current}")
    throw e
}
```

- `inbox.storage`, `inbox.storageRefusalCount`, `inbox.lastStorageRefusalAt`
  and `inbox.lastStorageRefusalReason` are **snapshots** of the representation
  you created or fetched; they never change on that object. They are `null`
  against a server that predates storage visibility.
- `inbox.storageRefusalCursor` is this object's **observation boundary**: the
  refusal count it has observed. Seeded from `storageRefusalCount`, it advances
  in exactly two cases — when a wait throws
  `TestInboxStorageLimitExceededException` (to that exception's count) and when
  you pass an explicit `afterStorageRefusalCount` — always to the maximum,
  atomically (two concurrent waits on one `Inbox` are safe), never backwards.
  A `MATCHED` or `TIMEOUT` result never advances it; do not copy counts out of
  those into it, because a match may have outranked a refusal you have not seen.
- `client.getWorkspaceStorage()` / `getWorkspaceStorageBlocking()` return your
  workspace's own `StorageUsage` (`limitBytes`, `storedBytes`, `reservedBytes`,
  `availableBytes`, `overLimit`). Every figure is yours; nothing about another
  workspace or the service's totals is disclosed. A refusal for
  `SERVICE_CAPACITY` carries the reason only — `quota`, `limit` and `current`
  are `null`.
- `StorageRefusalReason` is a wrapper over the wire value with constants
  `INBOX_LIMIT`, `WORKSPACE_LIMIT` and `SERVICE_CAPACITY`; a value this SDK
  predates round-trips as its exact string.

Opt out to get the pre-ADR-035 wait, in which a refusal is never surfaced:

```kotlin
inbox.awaitMessage(Duration.ofSeconds(30), matcher, observeStorageRefusals = false)
```

```java
inbox.awaitMessageBlocking(Duration.ofSeconds(30), matcher, null, false);
```

Resume a boundary you persisted yourself (for example across a process restart):

```java
inbox.awaitMessageBlocking(Duration.ofSeconds(30), matcher, savedBoundary);
```

The explicit value is applied monotonically to the object's cursor, so a value
below it is overridden by the cursor. Combining it with
`observeStorageRefusals = false`, or passing a negative value, is refused with
an `IllegalArgumentException` before any request. The SDK never retries a
storage refusal: waiting does not help.

## Development

```sh
./gradlew build                                    # Java 17 bytecode, unit tests, Java interop
./gradlew detekt --no-daemon -Dorg.gradle.java.home=/path/to/jdk-21
```
