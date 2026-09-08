package email.testinbox.application.usecase

import email.testinbox.application.InMemoryApiKeyRepository
import email.testinbox.application.RecordingApiKeyMetrics
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiKeyKind
import email.testinbox.domain.tenant.ApiScope
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CoalescingLastUsedRecorderTest {
    private val apiKeys = InMemoryApiKeyRepository()
    private val metrics = RecordingApiKeyMetrics()
    private val start = Instant.parse("2026-09-08T12:00:00Z")
    private val interval = Duration.ofMinutes(5)

    private fun key(
        id: ApiKeyId = ApiKeyId(UUID.randomUUID()),
        lastUsedAt: Instant? = null,
    ) = apiKeys.put(
        ApiKey(
            id = id,
            workspaceId = WorkspaceId(UUID.randomUUID()),
            projectId = ProjectId(UUID.randomUUID()),
            keyHash = "0".repeat(64),
            scopes = setOf(ApiScope.MESSAGES_READ),
            createdAt = start.minusSeconds(3600),
            revokedAt = null,
            kind = ApiKeyKind.MANAGED,
            publicId = "aaaaaaaaaaaaaaaa",
            lastUsedAt = lastUsedAt,
        ),
    )

    private fun recorder(maxTracked: Int = 1000) = CoalescingLastUsedRecorder(apiKeys, interval, maxTracked, metrics)

    @Test
    fun `the first use is written, and a burst after it costs no database round trip at all`() {
        val recorder = recorder()
        val stored = key()

        recorder.record(stored, start)
        apiKeys.touchAttempts.get() shouldBe 1
        metrics.lastUsedWrites.get() shouldBe 1

        // The point of the in-memory half: not "a cheap UPDATE that matches
        // nothing" but *no statement at all* on the hot path. A thousand calls
        // spread across one second — well inside the coalescing interval.
        repeat(1_000) { recorder.record(stored, start.plusMillis(it.toLong())) }
        apiKeys.touchAttempts.get() shouldBe 1
    }

    @Test
    fun `a write happens again once the interval has passed`() {
        val recorder = recorder()
        val stored = key()
        recorder.record(stored, start)
        recorder.record(stored, start.plus(interval).minusSeconds(1))
        apiKeys.touchAttempts.get() shouldBe 1

        recorder.record(stored, start.plus(interval).plusSeconds(1))
        apiKeys.touchAttempts.get() shouldBe 2
        apiKeys.keys.getValue(stored.id).lastUsedAt shouldBe start.plus(interval).plusSeconds(1)
    }

    @Test
    fun `a cold process trusts the value already in the row instead of rewriting it`() {
        // A restart, or a request landing on a second node, must not produce a
        // write just because this process has never seen the key.
        val stored = key(lastUsedAt = start.minusSeconds(10))
        recorder().record(stored, start)
        apiKeys.touchAttempts.get() shouldBe 0
    }

    @Test
    fun `the database still guards the write, so two nodes cannot both persist one`() {
        val stored = key()
        // Two independent recorders: neither knows what the other has done,
        // which is exactly the multi-node situation.
        val nodeA = recorder()
        val nodeB = recorder()
        nodeA.record(stored, start)
        nodeB.record(stored, start.plusSeconds(1))

        // Both issued a statement — but the guarded UPDATE let only one through.
        apiKeys.touchAttempts.get() shouldBe 2
        metrics.lastUsedWrites.get() shouldBe 1
    }

    @Test
    fun `the tracking map is bounded, and discarding it costs only extra statements`() {
        val recorder = recorder(maxTracked = 4)
        val tracked = key()

        // First use: written, and remembered locally.
        recorder.record(tracked, start)
        apiKeys.touchAttempts.get() shouldBe 1

        // A repeat inside the interval is answered from the map — no statement.
        recorder.record(tracked, start.plusSeconds(1))
        apiKeys.touchAttempts.get() shouldBe 1

        // Now push past the ceiling with other keys, which discards the map.
        val others = (1..20).map { key() }
        others.forEach { recorder.record(it, start) }

        // The same key again, still inside the interval. The map no longer
        // remembers it, so a statement IS issued — that is the observable
        // consequence of the bound, and asserting only "each key got its first
        // write" (as this test used to) held whether or not the map was ever
        // cleared, so removing the bound left it green.
        val before = apiKeys.touchAttempts.get()
        recorder.record(tracked, start.plusSeconds(2))
        (apiKeys.touchAttempts.get() > before) shouldBe true

        // …and the database still refuses the redundant write, which is what
        // keeps correctness independent of the map.
        metrics.lastUsedWrites.get() shouldBe 1 + others.size
        others.forEach { apiKeys.keys.getValue(it.id).lastUsedAt shouldBe start }
    }

    @Test
    fun `a failing write is not retried on every subsequent request`() {
        val failing =
            object : email.testinbox.application.port.ApiKeyRepository by apiKeys {
                override fun touchLastUsed(
                    id: ApiKeyId,
                    at: Instant,
                    onlyIfOlderThan: Instant,
                ): Boolean {
                    apiKeys.touchAttempts.incrementAndGet()
                    error("database unavailable")
                }
            }
        val stored = key()
        val recorder = CoalescingLastUsedRecorder(failing, interval, 1000, metrics)

        runCatching { recorder.record(stored, start) }.isFailure shouldBe true
        // The local timestamp is recorded *before* the write, so a database
        // that is refusing writes does not also get hammered once per request.
        runCatching { recorder.record(stored, start.plusSeconds(1)) }.isFailure shouldBe false
        apiKeys.touchAttempts.get() shouldBe 1
    }

    @Test
    fun `concurrent recording does not multiply writes`() {
        val recorder = recorder()
        val stored = key()
        val threads = 16
        val gate = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                gate.await()
                recorder.record(stored, start)
                done.countDown()
            }.start()
        }
        gate.countDown()
        done.await(5, TimeUnit.SECONDS) shouldBe true

        // The map may let a few racers through; the database guard is what
        // makes the *persisted* count exactly one.
        metrics.lastUsedWrites.get() shouldBe 1
    }

    @Test
    fun `the recorder asks for a timestamp update and nothing else`() {
        // Scoped to what this level can actually establish: the fake's
        // `touchLastUsed` is `copy(lastUsedAt = ...)`, so asserting the other
        // fields here would be asserting the fake. That the real `UPDATE`
        // touches one column is proven against Postgres in
        // `JdbcApiKeyRepositoryTest.the last-used refresh writes nothing but
        // the timestamp`.
        val recorder = recorder()
        val stored = key()
        recorder.record(stored, start)
        apiKeys.keys.getValue(stored.id).lastUsedAt shouldBe start
        apiKeys.touchAttempts.get() shouldBe 1
    }
}
