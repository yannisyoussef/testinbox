package email.testinbox.ingestion.guarded

import email.testinbox.application.port.LimitMetrics
import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.limits.RatePolicy
import email.testinbox.storage.fenced.TcpFaultProxy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The owner's four regression tests for the INGEST refund (owner decision
 * 2026-11-06, #124 comment 6678047827; ADR-027 §4 as amended), on the real
 * guarded path: the real `JdbcRateLimiter` on PostgreSQL, real write slots,
 * real T1/T2 and fenced uploads to MinIO.
 *
 * A pre-T1 `451` is forced deterministically by holding the node's only write
 * slot, so the event waits `W_slot` and is refused before T1. Refill rates are
 * so slow (1e-6/s) that a bucket holds exactly what was taken and given back.
 */
class IngestRefundIntegrationTest {
    private val harnesses = mutableListOf<GuardedIngestHarness>()

    @AfterEach
    fun close() = harnesses.forEach { it.close() }

    /** What the use case reported about refunds: (refunded, tokens). */
    private class RecordingLimitMetrics : LimitMetrics {
        val refunds: MutableList<Pair<Boolean, Int>> = Collections.synchronizedList(mutableListOf())

        override fun rateRefund(
            category: RateCategory,
            refunded: Boolean,
            tokens: Int,
        ) {
            check(category == RateCategory.INGEST)
            refunds += refunded to tokens
        }
    }

    private fun still(capacity: Long) = RatePolicy(capacity, refillPerSecond = 1e-6)

    @Suppress("LongParameterList")
    private fun harness(
        workspace: RatePolicy,
        inbox: RatePolicy,
        metrics: RecordingLimitMetrics,
        hook: IngestSyncHook = IngestSyncHook.NONE,
        viaProxy: Boolean = false,
        slotWait: Duration = Duration.ofMillis(300),
    ) = GuardedIngestHarness(
        hook = hook,
        viaProxy = viaProxy,
        maxSlots = 1,
        perWorkspace = 1,
        slotWait = slotWait,
        ingestLimits = workspace to inbox,
        limitMetrics = metrics,
    ).also { harnesses += it }

    private fun GuardedIngestHarness.tokens(
        workspace: WorkspaceId,
        inbox: InboxId? = null,
    ): Double? =
        jdbc
            .sql(
                """
                SELECT tokens FROM rate_bucket
                 WHERE workspace_id = :ws AND category = 'INGEST' AND inbox_id IS NOT DISTINCT FROM :inbox
                """.trimIndent(),
            ).param("ws", workspace.value)
            .param("inbox", inbox?.value)
            .query { rs, _ -> rs.getDouble(1) }
            .optional()
            .orElse(null)

    private fun GuardedIngestHarness.dbNow(): Instant = jdbc.sql("SELECT now()").query { rs, _ -> rs.getTimestamp(1).toInstant() }.single()

    private fun near(value: Double) = value plusOrMinus 1e-3

    @Test
    fun `owner test 1 - a within-budget message deferred before T1 is stored exactly once on the edge's retry, its allowance intact`() {
        val metrics = RecordingLimitMetrics()
        // The inbox's budget is ONE copy: without the refund, the failed attempt would spend it
        // and the retry would be discarded behind a uniform 250 (the defect this fixes).
        val h = harness(workspace = still(2), inbox = still(1), metrics = metrics)
        val ws = h.workspace()
        val (inbox, address) = h.inbox(ws)
        val raw = GuardedIngestHarness.mime(subject = "deferred")

        val held = h.slots.acquire(setOf(ws))
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(address), raw) }.reason shouldBe StorageUnavailableReason.SLOT_WAIT

        h.messageCount() shouldBe 0
        h.reservationStates() shouldBe emptyMap()
        h.tokens(ws, inbox)!! shouldBe near(1.0) // the allowance was not lost to the failed attempt
        h.tokens(ws)!! shouldBe near(2.0)
        metrics.refunds shouldBe listOf(true to 2)

        // The edge's retry: the same bytes and envelope, once the slot is free.
        held.close()
        val retry = h.deliver(listOf(address), raw)

        retry.accepted.size shouldBe 1
        retry.rateLimitedRecipients shouldBe 0
        h.messageCount() shouldBe 1
        h.messages.listVisible(inbox).size shouldBe 1
        h.tokens(ws, inbox)!! shouldBe near(0.0) // charged once, for the delivered attempt only
        h.tokens(ws)!! shouldBe near(1.0)
        // The budget really is one copy: the retry went through on the refunded token, not on spare budget.
        h.deliver(listOf(address)).rateLimitedRecipients shouldBe 1
        h.messageCount() shouldBe 1
        metrics.refunds shouldBe listOf(true to 2)
    }

    @Test
    fun `owner test 2 - repeated deferrals and concurrent refunds never create admission capacity`() {
        val metrics = RecordingLimitMetrics()
        val inboxPolicy = still(3)
        val workspacePolicy = still(5)
        val h = harness(workspace = workspacePolicy, inbox = inboxPolicy, metrics = metrics)
        val ws = h.workspace()
        val (inbox, address) = h.inbox(ws)
        // At every instant, not only at the end: an update that would put either bucket of this
        // workspace above its capacity fails, and would surface as a failed refund or delivery.
        h.jdbc
            .sql(
                "ALTER TABLE rate_bucket ADD CONSTRAINT within_capacity CHECK (workspace_id <> '${ws.value}' OR " +
                    "tokens <= CASE WHEN inbox_id IS NULL THEN 5 ELSE 3 END) NOT VALID",
            ).update()
        val started = h.dbNow()
        val deferred = AtomicInteger()
        val discarded = AtomicInteger()
        val other = Collections.synchronizedList(mutableListOf<Throwable>())
        val pool = Executors.newFixedThreadPool(8)
        val held = h.slots.acquire(setOf(ws))
        try {
            repeat(3) {
                val gate = CountDownLatch(1)
                val attempts =
                    (1..8).map {
                        pool.submit {
                            gate.await(10, TimeUnit.SECONDS)
                            try {
                                if (h.deliver(listOf(address)).rateLimitedRecipients == 1) discarded.incrementAndGet()
                            } catch (e: StorageUnavailableException) {
                                if (e.reason == StorageUnavailableReason.SLOT_WAIT) deferred.incrementAndGet() else other += e
                            } catch (e: RuntimeException) {
                                other += e
                            }
                        }
                    }
                gate.countDown()
                attempts.forEach { it.get(60, TimeUnit.SECONDS) }
                // Every deferral's charges came back, and nothing more: both buckets are exactly full.
                h.tokens(ws, inbox)!! shouldBe near(3.0)
                h.tokens(ws)!! shouldBe near(5.0)
            }
        } finally {
            held.close()
            pool.shutdownNow()
        }
        other.shouldBeEmpty()
        (deferred.get() >= 3) shouldBe true // the inbox budget admitted up to 3 per round, each deferred
        metrics.refunds.all { it == (true to 2) } shouldBe true
        metrics.refunds.size shouldBe deferred.get()

        // Slots free again: the deferrals left no extra capacity behind. Exactly the inbox's 3 copies are admitted.
        val stored = (1..10).sumOf { h.deliver(listOf(address)).accepted.size }
        val elapsed = Duration.between(started, h.dbNow()).toMillis() / 1000.0
        h.jdbc.sql("ALTER TABLE rate_bucket DROP CONSTRAINT within_capacity").update()

        stored shouldBe 3
        h.messageCount() shouldBe 3L
        (h.messageCount() <= inboxPolicy.capacity + inboxPolicy.refillPerSecond * elapsed) shouldBe true
    }

    @Test
    fun `owner test 3 - outcomes from T1 on, and uncertain ones, keep their charges exactly as before`() {
        data class Case(
            val name: String,
            val hook: IngestSyncHook = IngestSyncHook.NONE,
            val proxyReply: String? = null,
            val expected: StorageUnavailableReason?,
            val during: (GuardedIngestHarness, () -> Unit) -> Unit = { _, deliver -> deliver() },
        )
        val cases =
            listOf(
                Case("T1 lock timeout", expected = StorageUnavailableReason.LOCK_TIMEOUT, during = { h, deliver ->
                    h.dataSource.connection.use { holder ->
                        holder.autoCommit = false
                        holder.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(35, 1)") }
                        deliver()
                        holder.rollback()
                    }
                }),
                Case(
                    "write deadline (definitive refusal)",
                    proxyReply =
                        "HTTP/1.1 403 Forbidden\r\nContent-Length: 44\r\nConnection: close\r\n\r\n<Error><Code>AccessDenied</Code></Error>    ",
                    expected = StorageUnavailableReason.UPLOAD_FAILED,
                ),
                Case(
                    "ambiguous upload (commit uncertainty)",
                    proxyReply = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                    expected = StorageUnavailableReason.UPLOAD_FAILED,
                ),
                Case(
                    "T2 failure before commit",
                    hook =
                        object : IngestSyncHook {
                            override fun beforeCommit(): Unit = error("died before T2")
                        },
                    expected = null,
                ),
                Case(
                    "T2 failure inside the commit transaction",
                    hook =
                        object : IngestSyncHook {
                            override fun inCommitAfterReservationLock(): Unit = error("T2 commit outcome unknown")
                        },
                    expected = null,
                ),
            )
        for (case in cases) {
            val metrics = RecordingLimitMetrics()
            val h = harness(workspace = still(2), inbox = still(2), metrics = metrics, hook = case.hook, viaProxy = case.proxyReply != null)
            if (case.proxyReply != null) {
                h.proxy!!.mode = TcpFaultProxy.Mode.RESPOND
                h.proxy.canned = case.proxyReply
            }
            val ws = h.workspace()
            val (inbox, address) = h.inbox(ws)

            var thrown: Throwable? = null
            case.during(h) { thrown = runCatching { h.deliver(listOf(address)) }.exceptionOrNull() }

            check(thrown != null) { "${case.name}: the event did not fail" }
            case.expected?.let { (thrown as StorageUnavailableException).reason shouldBe it }
            h.messageCount() shouldBe 0
            check(metrics.refunds.isEmpty()) { "${case.name}: refunded ${metrics.refunds}" }
            // Charged once at each scope, and kept: the behaviour before refunds existed.
            h.tokens(ws, inbox)!! shouldBe near(1.0)
            h.tokens(ws)!! shouldBe near(1.0)
        }
    }

    @Test
    fun `owner test 4 - a multi-recipient, two-workspace event refunds exactly the charges taken, per bucket`() {
        val metrics = RecordingLimitMetrics()
        val h = harness(workspace = still(2), inbox = still(2), metrics = metrics)
        val wsA = h.workspace()
        val wsB = h.workspace()
        val (a1, addressA1) = h.inbox(wsA)
        val (a2, addressA2) = h.inbox(wsA)
        val (b1, addressB1) = h.inbox(wsB)
        val (b2, addressB2) = h.inbox(wsB)
        // Earlier traffic: workspace A has one token left, inbox b2 none.
        h.rateLimiter.tryConsume(wsA, RateCategory.INGEST).allowed shouldBe true
        repeat(2) { h.rateLimiter.tryConsume(wsB, RateCategory.INGEST, b2).allowed shouldBe true }
        val recipients = listOf(addressA1, addressA2, addressB1, addressB2, "nobody-${UUID.randomUUID()}@testinbox.local")

        fun state() =
            mapOf(
                "a1" to h.tokens(wsA, a1),
                "a2" to h.tokens(wsA, a2),
                "b1" to h.tokens(wsB, b1),
                "b2" to h.tokens(wsB, b2),
                "A" to h.tokens(wsA),
                "B" to h.tokens(wsB),
            )

        val held = h.slots.acquire(setOf(wsA))
        shouldThrow<StorageUnavailableException> { h.deliver(recipients) }.reason shouldBe StorageUnavailableReason.SLOT_WAIT

        // What the attempt took: a1 (inbox + A), a2 (inbox only: A refused it), b1 (inbox + B); b2 and
        // the unknown recipient took nothing. Exactly those five tokens came back, nothing else moved.
        metrics.refunds shouldBe listOf(true to 5)
        val afterRefund = state()
        afterRefund["a1"]!! shouldBe near(2.0)
        afterRefund["a2"]!! shouldBe near(2.0)
        afterRefund["b1"]!! shouldBe near(2.0)
        afterRefund["b2"]!! shouldBe near(0.0) // refused by its own budget: nothing taken, nothing given
        afterRefund["A"]!! shouldBe near(1.0) // its one remaining token, as before the event
        afterRefund["B"]!! shouldBe near(2.0)

        // The retry: the same decisions are made again, and this time the copies are stored and kept.
        held.close()
        val retry = h.deliver(recipients)

        retry.accepted.size shouldBe 2 // a1 and b1
        retry.rateLimitedRecipients shouldBe 2 // a2 (workspace A) and b2 (its inbox)
        h.messageCount() shouldBe 2
        val afterRetry = state()
        afterRetry["a1"]!! shouldBe near(1.0)
        afterRetry["a2"]!! shouldBe near(1.0) // its inbox token stands: the event completed
        afterRetry["b1"]!! shouldBe near(1.0)
        afterRetry["b2"]!! shouldBe near(0.0)
        afterRetry["A"]!! shouldBe near(0.0)
        afterRetry["B"]!! shouldBe near(1.0)
        metrics.refunds shouldBe listOf(true to 5)
    }
}
