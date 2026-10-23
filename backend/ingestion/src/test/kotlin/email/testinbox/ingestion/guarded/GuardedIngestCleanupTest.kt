package email.testinbox.ingestion.guarded

import email.testinbox.application.port.StorageInspection
import email.testinbox.application.storage.CleanupSyncHook
import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import email.testinbox.application.usecase.OrphanBlobSweep
import email.testinbox.ingestion.ops.StorageNodeRuntime
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-035 §7/§9 cleanup on the real stack (§17 tests 19, 20, 24, 26, 28, 29):
 * late objects latch, the per-exact-key multipart proof, clock offset,
 * T2 against cleanup in both orders, and the bucket-wide multipart guard.
 */
class GuardedIngestCleanupTest {
    private val harnesses = mutableListOf<GuardedIngestHarness>()

    @AfterEach
    fun close() = harnesses.forEach { it.close() }

    private fun track(h: GuardedIngestHarness) = h.also { harnesses += it }

    /** An event that reserved and uploaded, then died before T2: its reservation is ready for cleanup. */
    private fun abandonedEvent(h: GuardedIngestHarness): List<String> {
        val (_, a) = h.inbox(h.workspace())
        runCatching { h.deliver(listOf(a)) }
        return h.reservationKeys()
    }

    private val crashBeforeCommit =
        object : IngestSyncHook {
            override fun beforeCommit(): Unit = error("died before T2")
        }

    @Test
    fun `an object that reappears after the delete is a late object - deleted, charged S longer, and LATCHED`() {
        lateinit var h: GuardedIngestHarness
        val lateCommit =
            object : CleanupSyncHook {
                override fun afterDeleteBeforeList(key: String) {
                    // A commit that lands between the delete and the proof listing.
                    h.s3.putObject(
                        PutObjectRequest
                            .builder()
                            .bucket(h.bucket)
                            .key(key)
                            .build(),
                        RequestBody.fromBytes(byteArrayOf(1)),
                    )
                }
            }
        h = track(GuardedIngestHarness(hook = crashBeforeCommit, cleanupHook = lateCommit))
        val keys = abandonedEvent(h)
        keys.size shouldBe 2

        h.backdate(Duration.ofMinutes(30))
        h.releaseCycle().lateObjects shouldBe 1

        h.latched() shouldNotBe null
        h.metrics.events.contains("late") shouldBe true
        h.reservationStates() shouldBe mapOf("RELEASING" to 1L) // still charged
        keys.forEach { h.inspection.objectExists(it) shouldBe false } // and the late object deleted
        // Every node now refuses before T1 until an operator clears the latch.
        val (_, b) = h.inbox(h.workspace())
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(b)) }.reason shouldBe StorageUnavailableReason.LATCHED
    }

    @Test
    fun `an open multipart upload on an exact reserved key fails the absence proof, which a parent prefix would miss`() {
        val h = track(GuardedIngestHarness(hook = crashBeforeCommit))
        val keys = abandonedEvent(h)
        val raw = keys.first { it.endsWith("/raw.eml") }
        h.s3.createMultipartUpload(
            CreateMultipartUploadRequest
                .builder()
                .bucket(h.bucket)
                .key(raw)
                .build(),
        )
        val parent = raw.removeSuffix("raw.eml")
        // Probe M2: MinIO lists nothing for the parent prefix; M3: the exact key finds it.
        h.s3
            .listMultipartUploads(
                ListMultipartUploadsRequest
                    .builder()
                    .bucket(h.bucket)
                    .prefix(parent)
                    .build(),
            ).uploads()
            .shouldBeEmpty()
        h.inspection.incompleteUploadExists(raw) shouldBe true

        h.backdate(Duration.ofMinutes(30))
        h.releaseCycle().lateObjects shouldBe 1

        h.latched() shouldNotBe null
        h.inspection.incompleteUploadExists(raw) shouldBe false // aborted
        h.reservationStates() shouldBe mapOf("RELEASING" to 1L)
    }

    @Test
    fun `the orphan sweep aborts stray multipart uploads bucket-wide and alarms`() {
        val h = track(GuardedIngestHarness())
        h.s3.createMultipartUpload(
            CreateMultipartUploadRequest
                .builder()
                .bucket(h.bucket)
                .key("ws/inbox/stray/raw.eml")
                .build(),
        )
        h.inspection.incompleteUploads().size shouldBe 1

        OrphanBlobSweep(
            h.blobs,
            h.reservations,
            h.ambiguity,
            h.ambiguity,
            h.inspection,
            Clock.offset(Clock.systemUTC(), Duration.ofHours(2)),
            Duration.ofHours(1),
        ).sweep()

        h.inspection.incompleteUploads().shouldBeEmpty()
    }

    @Test
    fun `cleanup claiming first fences T2 out - 451, nothing visible, and the objects go with the reservation`() {
        lateinit var h: GuardedIngestHarness
        val cleanupFirst =
            object : IngestSyncHook {
                override fun afterUploads() {
                    h.backdate(Duration.ofMinutes(5)) // past the write deadline
                    h.reservations.expireOverdue(Duration.ofMinutes(17)) shouldBe 1
                }
            }
        h = track(GuardedIngestHarness(hook = cleanupFirst))
        val (_, a) = h.inbox(h.workspace())

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.COMMIT_FENCED

        h.messageCount() shouldBe 0
        h.metrics.events.contains("fenced") shouldBe true
        h.backdate(Duration.ofMinutes(30))
        h.releaseCycle().released shouldBe 1
        h.listedBytes() shouldBe 0
    }

    @Test
    fun `T2 holding the fence makes cleanup skip the row, and the committed objects are never deleted`() {
        lateinit var h: GuardedIngestHarness
        val pool = Executors.newSingleThreadExecutor()
        val t2First =
            object : IngestSyncHook {
                override fun afterUploads() = h.backdate(Duration.ofMinutes(5))

                override fun inCommitAfterReservationLock() {
                    // A concurrent cleaner on its own connection: SKIP LOCKED, no wait, no claim.
                    pool.submit<Int> { h.reservations.expireOverdue(Duration.ofMinutes(17)) }.get(30, TimeUnit.SECONDS) shouldBe 0
                }
            }
        h = track(GuardedIngestHarness(hook = t2First))
        val (inbox, a) = h.inbox(h.workspace())
        try {
            h.deliver(listOf(a)).accepted.size shouldBe 1
        } finally {
            pool.shutdownNow()
        }

        h.messages.listVisible(inbox).size shouldBe 1
        h.reservationStates() shouldBe emptyMap()
        h.backdate(Duration.ofMinutes(30))
        h.releaseCycle().released shouldBe 0
        h.fencedWrites.forEach { h.inspection.objectExists(it) shouldBe true } // committed content is safe
    }

    @Test
    fun `the orphan sweep latches when an orphan it deletes was ambiguous within 24 hours`() {
        val h = track(GuardedIngestHarness())
        val key = "${UUID.randomUUID()}/${UUID.randomUUID()}/${UUID.randomUUID()}/raw.eml"
        h.ambiguity.record("some-node", key, 1, Duration.ZERO)
        h.jdbc.sql("UPDATE storage_ambiguity SET resolved_at = now()").update() // verified absent, then landed
        h.s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(h.bucket)
                .key(key)
                .build(),
            RequestBody.fromBytes(byteArrayOf(1)),
        )

        OrphanBlobSweep(
            h.blobs,
            h.reservations,
            h.ambiguity,
            h.ambiguity,
            h.inspection,
            Clock.offset(Clock.systemUTC(), Duration.ofHours(2)),
            Duration.ofHours(1),
        ).sweep() shouldBe 1

        h.latched() shouldBe "late object found by the orphan sweep"
        h.inspection.objectExists(key) shouldBe false
    }

    @Test
    fun `a key proven committed leaves no evidence, so its later orphan never latches`() {
        // A live node wrongly declared dead gets coverage rows for events that then
        // commit normally. When such a message is later deleted, its orphaned
        // objects are ordinary content, not late objects.
        val h = track(GuardedIngestHarness())
        val (_, a) = h.inbox(h.workspace())
        h.deliver(listOf(a)).accepted.size shouldBe 1
        val key = h.fencedWrites.first()
        h.ambiguity.record("recovered:some-node", key, 0, Duration.ZERO)
        h.verification().run().resolved shouldBe 1
        h.count("SELECT count(*) FROM storage_ambiguity") shouldBe 0

        h.jdbc.sql("DELETE FROM message").update() // e.g. its inbox hard-deleted, the prefix delete failed
        OrphanBlobSweep(
            h.blobs,
            h.reservations,
            h.ambiguity,
            h.ambiguity,
            h.inspection,
            Clock.offset(Clock.systemUTC(), Duration.ofHours(2)),
            Duration.ofHours(1),
        ).sweep()

        h.latched() shouldBe null
        h.inspection.objectExists(key) shouldBe false
    }
}
