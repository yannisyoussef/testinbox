package email.testinbox.ingestion.guarded

import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageFullEvidence
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.JdbcFilesystemObservations
import email.testinbox.storage.fenced.TcpFaultProxy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #102 / Ops lab E9 case C on the real stack: storage answers every
 * upload with something the client never reads (the proxy resets the
 * connection after the body), so every failure is `AMBIGUOUS`, exactly as a
 * withheld ENOSPC looks. The breaker must not recover through the zero-byte
 * witness and send the next real upload to a possibly full store:
 * - under an enforcing mode nothing reaches storage again until an Ops
 *   observation shows the reserve available;
 * - under OFF at most ONE real-event trial runs without evidence, and a second
 *   ambiguity escalates to the same rule.
 */
class AmbiguousRecoveryTest {
    private val harnesses = mutableListOf<GuardedIngestHarness>()

    @AfterEach
    fun close() = harnesses.forEach { it.close() }

    private val reserve = 1L * 1024 * 1024 * 1024
    private val witnesses = AtomicInteger()

    private fun harness(
        enforcement: StorageEnforcement,
        gate: Boolean,
    ): GuardedIngestHarness {
        lateinit var h: GuardedIngestHarness
        val evidence =
            object : email.testinbox.application.storage.StorageFullGate {
                val real by lazy {
                    StorageFullEvidence(JdbcFilesystemObservations(h.jdbc), Duration.ofMinutes(15), reserve, 1_000, Duration.ZERO)
                }

                override fun tripped() = real.tripped()

                override fun evidence() = real.evidence()
            }
        val breaker =
            StorageBreaker(
                Duration.ofMillis(300),
                Duration.ofSeconds(1),
                storageFullGate = if (gate) evidence else email.testinbox.application.storage.StorageFullGate.NEVER,
                ambiguityNeedsEvidence = enforcement != StorageEnforcement.OFF,
            )
        val counting = { real: email.testinbox.application.port.StorageInspection ->
            object : email.testinbox.application.port.StorageInspection by real {
                override fun witness(probeKey: String): Boolean {
                    witnesses.incrementAndGet()
                    return real.witness(probeKey)
                }
            }
        }
        h =
            GuardedIngestHarness(enforcement = enforcement, viaProxy = true, breaker = breaker, inspectionOverride = counting).also {
                harnesses +=
                    it
            }
        return h
    }

    private fun observe(
        h: GuardedIngestHarness,
        availBytes: Long,
    ) {
        h.jdbc
            .sql(
                """
                INSERT INTO storage_filesystem_observation
                    (started_seq, source, block_size_bytes, capacity_bytes, used_bytes, avail_bytes, inodes_total,
                     inodes_used, trash_bytes, minio_sys_bytes)
                VALUES (storage_begin_observation(), 'test-monitor', 4096, 0, 0, ?, 1000000, 0, 0, 0)
                """.trimIndent(),
            ).param(availBytes)
            .update()
    }

    private fun backoff(h: GuardedIngestHarness) = Thread.sleep(h.breaker.currentBackoff.toMillis() + 100)

    @Test
    fun `enforcing - an unreadable failure opens the breaker and nothing reaches storage again until Ops evidence`() {
        val h = harness(StorageEnforcement.TENANT_LIMITS, gate = true)
        val (_, a) = h.inbox(h.workspace())
        h.proxy!!.mode = TcpFaultProxy.Mode.RESET_AFTER_BODY // every answer withheld: ENOSPC or anything else

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.UPLOAD_FAILED
        h.breaker.openKinds shouldBe setOf(StorageBreaker.Kind.AMBIGUOUS)
        h.unresolvedAmbiguity() shouldBe 1
        val writes = h.fencedWrites.size

        // Backoff after backoff: no witness, no real upload, the same 451 for everyone.
        repeat(3) {
            backoff(h)
            shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.BREAKER_OPEN
        }
        h.fencedWrites.size shouldBe writes
        witnesses.get() shouldBe 0

        observe(h, availBytes = reserve - 1) // below R_ops: still not evidence
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.BREAKER_OPEN
        h.fencedWrites.size shouldBe writes

        observe(h, availBytes = 10 * reserve) // Ops looked: the reserve is there
        h.proxy.mode = TcpFaultProxy.Mode.PASS
        h.deliver(listOf(a)).accepted.size shouldBe 1 // witness, then the real event, was the trial
        witnesses.get() shouldBe 1
        h.breaker.isOpen shouldBe false
    }

    @Test
    fun `OFF - one real-event trial without evidence, then a second ambiguity escalates and only evidence or a restart reopens`() {
        val h = harness(StorageEnforcement.OFF, gate = true)
        val (_, a) = h.inbox(h.workspace())
        h.proxy!!.mode = TcpFaultProxy.Mode.RESET_AFTER_BODY

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.UPLOAD_FAILED
        backoff(h)
        // The trial: witness (passes, storage is reachable), then THIS real upload — ambiguous again.
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.UPLOAD_FAILED
        h.breaker.isEscalated shouldBe true
        h.unresolvedAmbiguity() shouldBe 2
        val writes = h.fencedWrites.size

        repeat(3) {
            backoff(h)
            shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.BREAKER_OPEN
        }
        h.fencedWrites.size shouldBe writes // E9 case C: never a third upload to a possibly full store

        observe(h, availBytes = 10 * reserve)
        h.proxy.mode = TcpFaultProxy.Mode.PASS
        h.deliver(listOf(a)).accepted.size shouldBe 1
        h.breaker.isOpen shouldBe false
        h.breaker.isEscalated shouldBe false
    }

    @Test
    fun `OFF without a monitor - after the escalation only a restart clears it`() {
        val h = harness(StorageEnforcement.OFF, gate = false)
        val (_, a) = h.inbox(h.workspace())
        h.proxy!!.mode = TcpFaultProxy.Mode.RESET_AFTER_BODY
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }
        backoff(h)
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.UPLOAD_FAILED
        h.proxy.mode = TcpFaultProxy.Mode.PASS // storage is fine again, but nobody can tell the node so
        val writes = h.fencedWrites.size
        repeat(3) {
            backoff(h)
            shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.BREAKER_OPEN
        }
        h.fencedWrites.size shouldBe writes
    }
}
