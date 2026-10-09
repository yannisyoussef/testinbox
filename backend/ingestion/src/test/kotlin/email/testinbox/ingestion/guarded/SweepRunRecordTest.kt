package email.testinbox.ingestion.guarded

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.SweepRuns
import email.testinbox.application.usecase.OrphanBlobSweep
import email.testinbox.domain.MessageId
import email.testinbox.persistence.JdbcSweepRuns
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The orphan sweep's database record (V10; TI-STORAGE-006E owner review b, §3),
 * against the REAL adapters and the pinned MinIO.
 *
 * - A full pass opens its run before listing and completes it with the bytes
 *   it listed. The database stamps the order and instants, and computes the
 *   covered figure.
 * - The record is evidence for gate F, never a precondition of cleanup. If it
 *   cannot be written (a deployment without the V10 grants), the sweep still
 *   deletes orphans, and gate F finds no completed run.
 */
class SweepRunRecordTest {
    private lateinit var h: GuardedIngestHarness

    @AfterEach
    fun close() = h.close()

    private fun sweep(runs: SweepRuns) =
        OrphanBlobSweep(
            h.blobs,
            h.reservations,
            h.ambiguity,
            h.ambiguity,
            h.inspection,
            Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5)),
            Duration.ZERO,
            h.metrics,
            OrphanBlobSweep.Containment(runs = runs),
        )

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
    fun `a full pass is recorded with the database's order, its listed bytes and the covered figure the database computes`() {
        h = GuardedIngestHarness()
        val (_, address) = h.inbox(h.workspace())
        h.deliver(listOf(address)).accepted.size shouldBe 1

        sweep(JdbcSweepRuns(h.jdbc, "api-test")).sweep()

        val run =
            h.jdbc
                .sql(
                    "SELECT node_id, started_seq, completed_at IS NOT NULL AS done, physical_listed_bytes, covered_bytes FROM storage_sweep_run",
                ).query()
                .singleRow()
        run["node_id"] shouldBe "api-test"
        run["done"] shouldBe true
        run["physical_listed_bytes"] shouldBe h.listedBytes()
        run["covered_bytes"] shouldBe h.committedBytes() + h.reservedBytes()
    }

    @Test
    fun `a record that cannot be written never stops the sweep - the orphan is still deleted, and no run is complete`() {
        h = GuardedIngestHarness()
        val key = orphan(4096)
        val broken =
            object : SweepRuns {
                override fun begin(): Long = error("permission denied for function storage_begin_sweep")

                override fun complete(
                    run: Long,
                    listedBytes: Long,
                ) = error("unreachable")
            }

        sweep(broken).sweep() shouldBe 1

        h.inspection.objectExists(key) shouldBe false
        h.count("SELECT count(*) FROM storage_sweep_run WHERE completed_at IS NOT NULL") shouldBe 0
    }
}
