package email.testinbox.persistence

import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.query.StorageQueries
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.FilesystemDeclarations
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.usecase.ReserveCohortStorage
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.AdmissionFixture.Companion.shape
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real database proof of the persisted pool, T1 caps and cleanup-accounting lifecycle. */
class CohortStorageRuntimeTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase
    private lateinit var fx: AdmissionFixture
    private lateinit var declarations: StorageDeclarations
    private val gib = 1L shl 30
    private lateinit var ws: UUID
    private lateinit var inbox: UUID
    private lateinit var tx: TransactionRunner

    @BeforeEach fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
        fx = AdmissionFixture(db)
        ws = db.workspace()
        inbox = db.inbox(ws)
        declarations =
            StorageDeclarations(
                enforcement = StorageEnforcement.ALL,
                globalLimitBytes = 10 * gib,
                declaredBucketQuotaBytes = 20 * gib,
                declaredMaxIngestionProcesses = 1,
                inboxShare = "1",
                expectedApiNodes = setOf("api"),
                expectedIngestionNodes = setOf("smtp"),
                filesystem =
                    FilesystemDeclarations(
                        4096,
                        32768,
                        16 * gib,
                        2 * gib,
                        gib,
                        2 * gib,
                        32 * gib,
                        8_000_000,
                        Duration.ofMinutes(15),
                        256L shl 20,
                        postgres.username,
                    ),
            )
        tx =
            object : TransactionRunner {
                override fun <T> required(block: () -> T): T = checkNotNull(db.transactions.execute { block() })
            }
        val binding = checkNotNull(EffectiveStoragePolicy.footprint(declarations)?.cohort?.binding)
        for (node in listOf("api", "smtp")) {
            db.jdbc
                .sql(
                    "INSERT INTO storage_node(node_id,generation,capability,heartbeat_at,clean_shutdown,cohort_capability,cohort_mode,cohort_binding) VALUES (?,?,'storage-guarded-v1',now(),false,'cohort-storage-v1','ALL',?)",
                ).params(node, UUID.randomUUID(), binding)
                .update()
        }
        db.ledger.confirmTrust() shouldBe true
        observation(0)
    }

    private fun observation(trash: Long) {
        val order = db.beginObservation()
        db.jdbc
            .sql(
                """INSERT INTO storage_filesystem_observation(started_seq,source,block_size_bytes,capacity_bytes,used_bytes,avail_bytes,inodes_total,inodes_used,trash_bytes,minio_sys_bytes)
            VALUES (?,'cohort-test',4096,?,0,?,8000000,0,?,0)""",
            ).params(order, 32 * gib, 32 * gib, trash)
            .update()
    }

    private fun operator(d: StorageDeclarations = declarations) =
        ReserveCohortStorage(JdbcCohortBudgets(db.jdbc), tx, JdbcFootprintGate(db.jdbc), d)

    private fun reserve(
        payload: Long = gib,
        footprint: Long = 2 * gib,
    ) = operator().execute(
        mapOf(WorkspaceId(ws) to LegacyStorageShare(payload, footprint)),
        gib,
        gib,
    )

    private fun ready(): Boolean {
        val fp = checkNotNull(EffectiveStoragePolicy.footprint(declarations))
        val seen = JdbcFootprintGate(db.jdbc).observe()
        return fp.unavailability(seen) == null && fp.cohort?.ready(seen?.cohort) == true
    }

    private fun charges(): Long =
        db.jdbc
            .sql("SELECT coalesce(sum(bytes),0) FROM workspace_deletion_charge")
            .query(Long::class.java)
            .single()

    private fun admission() =
        StorageAdmission(
            fx.store(),
            AdmissionFixture.policy(2 * gib, "1", 10 * gib),
            StorageEnforcement.ALL,
            footprint = EffectiveStoragePolicy.footprint(declarations),
        )

    @Test fun `approval requires exact inventory and compatible nodes and never opens admissions`() {
        ready() shouldBe false
        shouldThrow<IllegalStateException> { operator().execute(emptyMap(), gib, gib) }
        db.jdbc
            .sql("SELECT count(*) FROM storage_cohort_plan")
            .query(Long::class.java)
            .single() shouldBe 0
        reserve()
        ready() shouldBe true
        db.jdbc
            .sql("SELECT admissions_open OR capacity_ready FROM beta_cohort")
            .query(Boolean::class.java)
            .single() shouldBe false
        val other = db.workspace()
        ready() shouldBe false
        operator().execute(
            mapOf(WorkspaceId(ws) to LegacyStorageShare(gib, 2 * gib), WorkspaceId(other) to LegacyStorageShare(gib, 2 * gib)),
            gib,
            gib,
        )
        ready() shouldBe true
    }

    @Test fun `pool budget and configuration cannot silently shrink or mismatch`() {
        reserve()
        shouldThrow<IllegalStateException> { reserve(payload = gib / 2) }
        shouldThrow<IllegalArgumentException> { reserve(payload = 20 * gib) }
        val changed = checkNotNull(EffectiveStoragePolicy.footprint(declarations.copy(inboxShare = "0.5")))
        (changed.unavailability(JdbcFootprintGate(db.jdbc).observe()) != null) shouldBe true
        ready() shouldBe true
        operator().revoke()
        ready() shouldBe false
        db.jdbc
            .sql("SELECT count(*) FROM storage_legacy_share")
            .query(Long::class.java)
            .single() shouldBe 1
        db.jdbc
            .sql("SELECT count(*) FROM storage_cohort_plan")
            .query(Long::class.java)
            .single() shouldBe 1
    }

    @Test fun `incompatible node restart latches even when mode accidentally returns to OFF`() {
        reserve()
        db.jdbc
            .sql(
                "INSERT INTO storage_node(node_id,generation,capability,heartbeat_at,clean_shutdown) VALUES ('old',?,'old',now(),false)",
            ).param(UUID.randomUUID())
            .update()
        db.jdbc
            .sql("SELECT count(*) FROM storage_admission_latch")
            .query(Long::class.java)
            .single() shouldBe 1
        ready() shouldBe false
    }

    @Test fun `cleanup charge survives global debt compaction into nonempty trash`() {
        reserve()
        db.message(ws, inbox, 100, listOf(0, 1, 7))
        db.hardDeleteInbox(inbox)
        charges() shouldBe 108
        val before = JdbcStorageVisibility(db.jdbc).read(WorkspaceId(ws), emptySet())
        before.budget!!.cleanup.objects shouldBe 4
        before.workspace.usedBytes shouldBe 0
        observation(12345)
        db.ledger.compactDeletionDebt()
        db.jdbc
            .sql("SELECT count(*) FROM storage_deletion_debt")
            .query(Long::class.java)
            .single() shouldBe 0
        charges() shouldBe 108
        val display =
            StorageQueries(
                JdbcStorageVisibility(db.jdbc),
                AdmissionFixture.policy(2 * gib, "1", 10 * gib),
                EffectiveStoragePolicy.footprint(declarations),
                StorageEnforcement.ALL,
            ).workspace(WorkspaceId(ws))
        display.limitBytes shouldBe gib
        display.footprint!!.pendingCleanupBytes shouldBe display.footprint!!.usedBytes
        display.footprint!!.enforced shouldBe true
        observation(0)
        db.ledger.compactDeletionDebt()
        charges() shouldBe 0
    }

    @Test fun `a pending or late charge cannot be released by an earlier empty observation`() {
        reserve()
        val key = "$ws/$inbox/${UUID.randomUUID()}/raw.eml"
        db.jdbc
            .sql("SELECT storage_record_pending_debt(?,200,1,'test')")
            .param(key)
            .query()
            .listOfRows()
        observation(0)
        db.ledger.compactDeletionDebt()
        charges() shouldBe 200
        db.jdbc
            .sql("SELECT storage_resolve_pending_debt(?)")
            .param(key)
            .query()
            .listOfRows()
        db.ledger.compactDeletionDebt()
        charges() shouldBe 200
        observation(0)
        db.ledger.compactDeletionDebt()
        charges() shouldBe 0
    }

    @Test fun `wrong writer and untrusted observations cannot release a cleanup charge`() {
        reserve()
        db.message(ws, inbox, 99)
        db.hardDeleteInbox(inbox)
        db.jdbc.sql("UPDATE storage_cohort_plan SET monitor_role='another_monitor'").update()
        observation(0)
        db.ledger.compactDeletionDebt()
        charges() shouldBe 99
        db.jdbc
            .sql("UPDATE storage_cohort_plan SET monitor_role=?::name")
            .param(postgres.username)
            .update()
        db.jdbc.sql("UPDATE storage_footprint_trust SET distrust_epoch=distrust_epoch+1").update()
        observation(0)
        db.ledger.compactDeletionDebt()
        charges() shouldBe 99
        db.ledger.confirmTrust() shouldBe true
        observation(0)
        db.ledger.compactDeletionDebt()
        charges() shouldBe 0
    }

    @Test fun `legacy OFF deletion does not grow per-workspace cleanup state`() {
        db.message(ws, inbox, 99)
        db.hardDeleteInbox(inbox)
        charges() shouldBe 0
        db.jdbc
            .sql("SELECT sum(bytes) FROM storage_deletion_debt")
            .query(Long::class.java)
            .single() shouldBe 99
    }

    @Test fun `all reservation states consume physical charge and cleanup refuses even a one-byte payload`() {
        reserve(payload = 1000, footprint = 80_000)
        fx.reservation(ws, inbox, 1, state = "RELEASING")
        admission().admit(fx.request(1, listOf(fx.candidate(ws, inbox)))).shape() shouldBe listOf("admitted")
        admission().admit(fx.request(1, listOf(fx.candidate(ws, inbox)))).shape() shouldBe listOf("WORKSPACE_FOOTPRINT_LIMIT")
        // Deleting RELEASING moves its charge to cleanup, not free space.
        db.jdbc.sql("DELETE FROM storage_reservation WHERE state='RELEASING'").update()
        admission().admit(fx.request(1, listOf(fx.candidate(ws, inbox)))).shape() shouldBe listOf("WORKSPACE_FOOTPRINT_LIMIT")
        observation(0)
        db.ledger.compactDeletionDebt()
        admission().admit(fx.request(1, listOf(fx.candidate(ws, inbox)))).shape() shouldBe listOf("admitted")
    }

    @Test fun `concurrent events cannot overrun their smaller workspace physical ceiling`() {
        reserve(payload = 1000, footprint = 80_000)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map {
                    pool.submit<List<String>> {
                        start.await(5, TimeUnit.SECONDS)
                        admission().admit(fx.request(1, listOf(fx.candidate(ws, inbox, attachments = 1)))).shape()
                    }
                }
            start.countDown()
            futures.flatMap { it.get(10, TimeUnit.SECONDS) }.sorted() shouldBe listOf("WORKSPACE_FOOTPRINT_LIMIT", "admitted")
            fx.reservationCount() shouldBe 1
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `payload admission and tenant visibility use the same smaller persisted share`() {
        reserve(payload = 100, footprint = 1_000_000)
        val result = admission().admit(fx.request(101, listOf(fx.candidate(ws, inbox))))
        result.shape() shouldBe listOf("INBOX_LIMIT")
        fx.reservationCount() shouldBe 0
        val display =
            StorageQueries(
                JdbcStorageVisibility(db.jdbc),
                AdmissionFixture.policy(2 * gib, "1", 10 * gib),
            ).workspace(WorkspaceId(ws))
        display.limitBytes shouldBe 100
        JdbcStorageVisibility(db.jdbc).read(WorkspaceId(db.workspace()), emptySet()).budget shouldBe null
    }

    @Test fun `participant payload reservations stop exactly at 128 MiB across different inboxes`() {
        db.jdbc
            .sql("INSERT INTO beta_admission(cohort_id,place,workspace_id,project_id,admitted_at) VALUES ('beta-30',1,?,?,now())")
            .params(ws, ws)
            .update()
        db.jdbc
            .sql("INSERT INTO participant_workspace(workspace_id,cohort_id,place,allocated_at) VALUES (?,'beta-30',1,now())")
            .param(ws)
            .update()
        operator().execute(emptyMap(), gib, gib)
        repeat(8) { db.message(ws, inbox, 15L shl 20) }
        db.message(ws, inbox, (8L shl 20) - 1)
        val next = db.inbox(ws)
        admission().admit(fx.request(1, listOf(fx.candidate(ws, next)))).shape() shouldBe listOf("admitted")
        admission().admit(fx.request(1, listOf(fx.candidate(ws, db.inbox(ws))))).shape() shouldBe listOf("WORKSPACE_LIMIT")
        val figures = JdbcStorageVisibility(db.jdbc).read(WorkspaceId(ws), emptySet())
        figures.workspace.usedBytes shouldBe (128L shl 20)
        figures.budget!!.payloadBytes shouldBe (128L shl 20)
    }

    @Test fun `a restored participant marker fences OFF nodes even without restored approval`() {
        db.jdbc
            .sql("INSERT INTO beta_admission(cohort_id,place,workspace_id,project_id,admitted_at) VALUES ('beta-30',1,?,?,now())")
            .params(ws, ws)
            .update()
        db.jdbc
            .sql("INSERT INTO participant_workspace(workspace_id,cohort_id,place,allocated_at) VALUES (?,'beta-30',1,now())")
            .param(ws)
            .update()
        JdbcStorageAmbiguity(db.jdbc, db.transactions).registerGeneration("off-after-restore", UUID.randomUUID(), "storage-v1")
        db.jdbc
            .sql("SELECT count(*) FROM storage_admission_latch")
            .query(Long::class.java)
            .single() shouldBe 1
    }
}
