package email.testinbox.ingestion.guarded

import email.testinbox.application.ObjectKeys
import email.testinbox.application.storage.FootprintPolicy
import email.testinbox.application.storage.RowFreeDebt
import email.testinbox.application.storage.VerifyAmbiguousUploads
import email.testinbox.domain.MessageId
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.FootprintModel
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.JdbcRowFreeDebtStore
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Duration
import java.util.UUID

/**
 * Rule (P) on the ambiguity verifier, against the REAL adapters and the pinned
 * MinIO (TI-STORAGE-006E PR D, contract §2.1 and Lemma 3). A late object — an
 * ambiguous upload that surfaced after its reservation was released — is deleted
 * only if its pending debt row is admitted under `ALL`. Refused, it stays where it
 * is, its ambiguity row stays unresolved (so its write slot stays held, and no
 * further late object can take its place), and the latch is set either way.
 */
class RowFreeDebtCleanupTest {
    private lateinit var h: GuardedIngestHarness
    private val model = FootprintModel.REFERENCE

    @AfterEach
    fun close() = h.close()

    private fun lateObject(bytes: Int): String {
        val ws = h.workspace()
        val (inbox, _) = h.inbox(ws)
        val key = ObjectKeys.raw(ws, inbox, MessageId(UUID.randomUUID()))
        h.s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(h.bucket)
                .key(key)
                .build(),
            RequestBody.fromBytes(ByteArray(bytes)),
        )
        h.ambiguity.record(h.nodeId, key, bytes.toLong(), Duration.ZERO)
        return key
    }

    private fun verifier(capacity: Long): VerifyAmbiguousUploads {
        val role =
            h.jdbc
                .sql("SELECT session_user::text")
                .query { rs, _ -> rs.getString(1) }
                .single()
        val rowFree =
            RowFreeDebt(
                JdbcRowFreeDebtStore(h.jdbc, TransactionTemplate(DataSourceTransactionManager(h.dataSource))),
                FootprintPolicy(model, FootprintAdmission.Limits(Long.MAX_VALUE / 4, 0, 0, 0, capacity, 0), role, 0),
                StorageEnforcement.ALL,
            )
        return VerifyAmbiguousUploads(h.ambiguity, h.ambiguity, h.reservations, h.inspection, h.metrics, rowFree = rowFree)
    }

    private fun trustAndObserve() {
        h.jdbc.sql("UPDATE storage_footprint_trust SET trusted_epoch = distrust_epoch WHERE id = 1").update()
        val walk =
            h.jdbc
                .sql("SELECT storage_begin_observation()")
                .query(Long::class.java)
                .single()
        h.jdbc
            .sql(
                "INSERT INTO storage_filesystem_observation (started_seq, source, block_size_bytes, capacity_bytes, used_bytes, " +
                    "avail_bytes, inodes_total, inodes_used, trash_bytes, minio_sys_bytes) " +
                    "VALUES (?, 'test-monitor', 4096, 9223372036854775807, 0, 0, 1000000, 0, 0, 0)",
            ).param(walk)
            .update()
    }

    private fun debt(key: String): List<String> =
        h.jdbc
            .sql(
                "SELECT CASE WHEN incurred_at = 'infinity' THEN 'pending' ELSE 'resolved' END FROM storage_deletion_debt WHERE object_key = ?",
            ).param(key)
            .query { rs, _ -> rs.getString(1) }
            .list()

    @Test
    fun `a late object rule P refuses is kept, with its ambiguity row and slot held, and the latch set`() {
        h = GuardedIngestHarness()
        trustAndObserve()
        val key = lateObject(4096)

        val report = verifier(capacity = model.bound(4096, 1) - 1).run()

        report.deferred shouldBe 1
        report.lateObjects shouldBe 0
        h.inspection.objectExists(key) shouldBe true
        h.unresolvedAmbiguity() shouldBe 1
        h.latched() shouldBe "late object found at ambiguity verification"
        debt(key) shouldBe emptyList()
        // Held: the operator cannot clear the latch while it is (V9).
        h.ambiguity.heldRefused() shouldBe 1
        val clear = runCatching { h.jdbc.sql("DELETE FROM storage_admission_latch").update() }.exceptionOrNull()
        generateSequence(clear) { it.cause }
            .filterIsInstance<java.sql.SQLException>()
            .first()
            .sqlState shouldBe "23514" // check_violation, from V9's latch-hold trigger
    }

    @Test
    fun `a late object rule P admits is deleted under its pending row, which is resolved once it is proven gone`() {
        h = GuardedIngestHarness()
        trustAndObserve()
        val key = lateObject(4096)

        val report = verifier(capacity = model.bound(4096, 1)).run()

        report.lateObjects shouldBe 1
        h.inspection.objectExists(key) shouldBe false
        h.unresolvedAmbiguity() shouldBe 0
        debt(key) shouldBe listOf("resolved")
    }

    @Test
    fun `a dead node's keyless rows stay while rule P holds a key they cover - the slot stays occupied until it is admitted`() {
        h = GuardedIngestHarness()
        trustAndObserve()
        val ws = h.workspace()
        val (inbox, _) = h.inbox(ws)
        val key = ObjectKeys.raw(ws, inbox, MessageId(UUID.randomUUID()))
        h.s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(h.bucket)
                .key(key)
                .build(),
            RequestBody.fromBytes(ByteArray(4096)),
        )
        // The dead node's coverage row for the key, then its keyless slot row.
        h.ambiguity.record(
            email.testinbox.application.port
                .coverageNode("dead-node"),
            key,
            0,
            Duration.ZERO,
        )
        h.ambiguity.record("dead-node", null, 0, Duration.ZERO)

        val refused = verifier(capacity = model.bound(4096, 1) - 1).run()
        refused.deferred shouldBe 2 // the key held, and the keyless row behind it
        h.unresolvedAmbiguity() shouldBe 2

        h.jdbc.sql("UPDATE storage_ambiguity SET verify_at = now() WHERE resolved_at IS NULL").update()
        val admit = verifier(capacity = model.bound(4096, 1))
        admit.run().lateObjects shouldBe 1 // the keyless row precedes the held one, so it waits one more pass
        h.inspection.objectExists(key) shouldBe false
        admit.run().resolved shouldBe 1
        h.unresolvedAmbiguity() shouldBe 0
    }

    private fun sweep(capacity: Long): email.testinbox.application.usecase.OrphanBlobSweep {
        val role =
            h.jdbc
                .sql("SELECT session_user::text")
                .query(String::class.java)
                .single()
        val rowFree =
            RowFreeDebt(
                JdbcRowFreeDebtStore(h.jdbc, TransactionTemplate(DataSourceTransactionManager(h.dataSource))),
                FootprintPolicy(model, FootprintAdmission.Limits(Long.MAX_VALUE / 4, 0, 0, 0, capacity, 0), role, 0),
                StorageEnforcement.ALL,
            )
        return email.testinbox.application.usecase.OrphanBlobSweep(
            h.blobs,
            h.reservations,
            h.ambiguity,
            h.ambiguity,
            h.inspection,
            java.time.Clock.offset(java.time.Clock.systemUTC(), Duration.ofMinutes(5)),
            Duration.ZERO,
            h.metrics,
            rowFree,
        )
    }

    private fun orphan(bytes: Int): String {
        val ws = h.workspace()
        val (inbox, _) = h.inbox(ws)
        val key = ObjectKeys.raw(ws, inbox, MessageId(UUID.randomUUID()))
        h.s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(h.bucket)
                .key(key)
                .build(),
            RequestBody.fromBytes(ByteArray(bytes)),
        )
        return key
    }

    @Test
    fun `the orphan sweep deletes an orphan rule P admits, under a pending row resolved once it is gone`() {
        h = GuardedIngestHarness()
        trustAndObserve()
        val key = orphan(4096)

        sweep(capacity = model.bound(4096, 1)).sweep() shouldBe 1

        h.inspection.objectExists(key) shouldBe false
        debt(key) shouldBe listOf("resolved")
    }

    @Test
    fun `a late object the orphan sweep is refused is kept and held - counted against every node's slots`() {
        h = GuardedIngestHarness()
        trustAndObserve()
        val key = orphan(4096)
        // It was ambiguous once: a late object, not an ordinary orphan.
        h.ambiguity.record(h.nodeId, key, 4096, Duration.ZERO)
        h.ambiguity.resolve(
            h.ambiguity
                .due(10)
                .single()
                .id,
        )

        sweep(capacity = model.bound(4096, 1) - 1).sweep() shouldBe 0

        h.inspection.objectExists(key) shouldBe true
        debt(key) shouldBe emptyList()
        h.ambiguity.heldRefused() shouldBe 1
        h.ambiguity.unresolvedOrphaned(Duration.ofMinutes(5)) shouldBe 1
    }

    @Test
    fun `the sweep's resolver resolves an aged pending row whose key is proven absent`() {
        h = GuardedIngestHarness()
        trustAndObserve()
        h.jdbc
            .sql("SELECT storage_record_pending_debt('ws/in/gone/raw.eml', 10, 1, 'test')")
            .query()
            .listOfRows()
        h.jdbc
            .sql(
                "UPDATE storage_deletion_debt SET recorded_at = now() - interval '2 hours' WHERE object_key = 'ws/in/gone/raw.eml'",
            ).update()

        sweep(capacity = Long.MAX_VALUE / 4).sweep()

        debt("ws/in/gone/raw.eml") shouldBe listOf("resolved")
    }
}
