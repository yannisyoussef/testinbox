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

    /** A harness whose breaker reads its evidence from the harness's own database. */
    private fun harness(): GuardedIngestHarness {
        lateinit var h: GuardedIngestHarness
        val breaker =
            StorageBreaker(Duration.ofMillis(300), Duration.ofSeconds(1)) {
                StorageFullEvidence(JdbcFilesystemObservations(h.jdbc), Duration.ofMinutes(15), reserve)()
            }
        h = GuardedIngestHarness(viaProxy = true, breaker = breaker).also { harnesses += it }
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
                    (started_at, source, block_size_bytes, capacity_bytes, used_bytes, avail_bytes, inodes_total, inodes_used,
                     trash_bytes, minio_sys_bytes)
                VALUES (now(), 'test-monitor', 4096, 0, 0, ?, 0, 0, 0, 0)
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
    }

    @Test
    fun `500 naming ENOSPC is STORAGE_FULL, and a plain 500 is not`() {
        val enospc = harness()
        answer(enospc, "500 Internal Server Error", "InternalError", "write /data/.minio.sys/tmp/x/part.1: no space left on device")
        shouldThrow<StorageUnavailableException> { enospc.deliver(listOf(enospc.inbox(enospc.workspace()).second)) }
        enospc.metrics.events shouldContain "failure:STORAGE_FULL"

        val plain = harness()
        answer(plain, "500 Internal Server Error", "InternalError", "We encountered an internal error, please try again.")
        shouldThrow<StorageUnavailableException> { plain.deliver(listOf(plain.inbox(plain.workspace()).second)) }
        plain.metrics.events shouldContain "failure:AMBIGUOUS"
        plain.metrics.events shouldNotContain "failure:STORAGE_FULL"
    }
}
