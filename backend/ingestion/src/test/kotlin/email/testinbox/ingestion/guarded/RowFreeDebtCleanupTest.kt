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
}
