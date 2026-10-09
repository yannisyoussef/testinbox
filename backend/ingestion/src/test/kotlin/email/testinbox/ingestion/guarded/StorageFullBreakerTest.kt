package email.testinbox.ingestion.guarded

import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageFullEvidence
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import email.testinbox.persistence.JdbcFilesystemObservations
import email.testinbox.storage.fenced.TcpFaultProxy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * Filesystem-containment contract §8 on the real stack (TI-STORAGE-006E): a
 * full filesystem, answered as MinIO answers it, opens the `STORAGE_FULL`
 * breaker. The upload stays ambiguous (charged, slot held). The breaker
 * reopens only on Ops evidence, and its trial is a real event through the
 * normal reservation path. Until then every DATA gets the same `451`,
 * whatever its recipients.
 */
class StorageFullBreakerTest {
    private val harnesses = mutableListOf<GuardedIngestHarness>()

    @AfterEach
    fun close() = harnesses.forEach { it.close() }

    private val reserve = 1L * 1024 * 1024 * 1024

    /** Witness probes issued by the breaker: a STORAGE_FULL trial must never be one. */
    private val witnesses =
        java.util.concurrent.atomic
            .AtomicInteger()

    /**
     * A harness whose breaker reads its evidence, through the production gate,
     * from the harness's own database. No negative cache, so a test's fresh
     * observation is seen at once.
     */
    private fun harness(): GuardedIngestHarness {
        lateinit var h: GuardedIngestHarness
        val gate =
            object : email.testinbox.application.storage.StorageFullGate {
                val real by lazy {
                    StorageFullEvidence(JdbcFilesystemObservations(h.jdbc), Duration.ofMinutes(15), reserve, 1_000, Duration.ZERO)
                }

                override fun tripped() = real.tripped()

                override fun evidence() = real.evidence()
            }
        val breaker = StorageBreaker(Duration.ofMillis(300), Duration.ofSeconds(1), storageFullGate = gate)
        val counting = { real: email.testinbox.application.port.StorageInspection ->
            object : email.testinbox.application.port.StorageInspection by real {
                override fun witness(probeKey: String): Boolean {
                    witnesses.incrementAndGet()
                    return real.witness(probeKey)
                }
            }
        }
        h = GuardedIngestHarness(viaProxy = true, breaker = breaker, inspectionOverride = counting).also { harnesses += it }
        return h
    }

    private fun answer(
        h: GuardedIngestHarness,
        status: String,
        code: String,
        message: String,
    ) {
        val body = "<?xml version=\"1.0\"?><Error><Code>$code</Code><Message>$message</Message></Error>"
        h.proxy!!.mode = TcpFaultProxy.Mode.RESPOND
        h.proxy.canned = "HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
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

    @Test
    fun `507 XMinioStorageFull opens STORAGE_FULL, stays ambiguous, and only evidence reopens it, through a real event`() {
        val h = harness()
        val (_, a) = h.inbox(h.workspace())
        answer(h, "507 Insufficient Storage", "XMinioStorageFull", "Storage backend has reached its minimum free drive threshold.")

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.UPLOAD_FAILED

        h.metrics.events shouldContain "failure:STORAGE_FULL"
        h.metrics.events shouldNotContain "failure:AMBIGUOUS" // its own kind, so the zero-byte probe never reopens it
        h.breaker.openKinds shouldBe setOf(StorageBreaker.Kind.STORAGE_FULL) // and ONLY that kind
        h.unresolvedAmbiguity() shouldBe 1 // ambiguous: persisted, the slot held, H unchanged
        h.reservationStates() shouldBe mapOf("RESERVED" to 1L) // still charged
        h.breaker.isOpen shouldBe true

        // Storage answers again, the backoff has passed, but nobody has observed the filesystem:
        // every DATA, to a known inbox or to nobody, gets the same 451, and no trial is spent.
        h.proxy!!.mode = TcpFaultProxy.Mode.PASS
        Thread.sleep(h.breaker.currentBackoff.toMillis() + 100)
        val writes = h.fencedWrites.size
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.BREAKER_OPEN
        shouldThrow<StorageUnavailableException> {
            h.deliver(listOf("nobody-${UUID.randomUUID()}@testinbox.local"))
        }.reason shouldBe StorageUnavailableReason.BREAKER_OPEN
        h.fencedWrites.size shouldBe writes

        observe(h, availBytes = reserve - 1) // below R_ops: still not evidence
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.BREAKER_OPEN

        observe(h, availBytes = 10 * reserve) // Ops recovered the filesystem
        h.deliver(listOf(a)).accepted.size shouldBe 1 // the real event WAS the trial
        h.breaker.isOpen shouldBe false
        witnesses.get() shouldBe 0 // never a zero-byte probe: a full filesystem accepts one
    }

    @Test
    fun `a trial on fresh evidence that hits ENOSPC again re-trips, and the same observation cannot license another`() {
        val h = harness()
        val (_, a) = h.inbox(h.workspace())
        answer(h, "507 Insufficient Storage", "XMinioStorageFull", "Storage backend has reached its minimum free drive threshold.")
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }
        Thread.sleep(h.breaker.currentBackoff.toMillis() + 100)
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.BREAKER_OPEN

        observe(h, availBytes = 10 * reserve) // the monitor thinks it is fine; MinIO still answers 507
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.UPLOAD_FAILED

        h.breaker.openKinds shouldBe setOf(StorageBreaker.Kind.STORAGE_FULL) // re-tripped as its own kind
        h.unresolvedAmbiguity() shouldBe 2 // the failed trial's upload is ambiguous too, and holds its slot
        h.metrics.events.count { it == "failure:STORAGE_FULL" } shouldBe 2

        // The same observation cannot license a second trial, however long we wait.
        Thread.sleep(h.breaker.currentBackoff.toMillis() + 100)
        val writes = h.fencedWrites.size
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.BREAKER_OPEN
        h.fencedWrites.size shouldBe writes

        observe(h, availBytes = 10 * reserve) // a NEW observation, after the trip
        h.proxy!!.mode = TcpFaultProxy.Mode.PASS
        h.deliver(listOf(a)).accepted.size shouldBe 1
        h.breaker.isOpen shouldBe false
    }

    @Test
    fun `500 naming ENOSPC is STORAGE_FULL, and a plain 500 is not`() {
        val enospc = harness()
        // Longer than any short bound: the message is read in full before it is matched.
        answer(
            enospc,
            "500 Internal Server Error",
            "InternalError",
            "a".repeat(2_000) + " write /data/.minio.sys/tmp/x/part.1: no space left on device",
        )
        shouldThrow<StorageUnavailableException> { enospc.deliver(listOf(enospc.inbox(enospc.workspace()).second)) }
        enospc.metrics.events shouldContain "failure:STORAGE_FULL"

        val plain = harness()
        answer(plain, "500 Internal Server Error", "InternalError", "We encountered an internal error, please try again.")
        shouldThrow<StorageUnavailableException> { plain.deliver(listOf(plain.inbox(plain.workspace()).second)) }
        plain.metrics.events shouldContain "failure:AMBIGUOUS"
        plain.metrics.events shouldNotContain "failure:STORAGE_FULL"
    }
}
