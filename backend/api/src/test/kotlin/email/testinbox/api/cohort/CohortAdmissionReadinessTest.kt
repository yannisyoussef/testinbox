package email.testinbox.api.cohort

import email.testinbox.api.account.HumanAccountWiring
import email.testinbox.application.storage.CohortStorageReadiness
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.application.usecase.ReserveCohortStorage
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.persistence.JdbcBetaAdmissions
import email.testinbox.persistence.JdbcCohortBudgets
import email.testinbox.persistence.JdbcFootprintGate
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * TI-DELIVERY-002b acceptance item 1: participant admission against the REAL
 * readiness collaborator, unmocked, on real PostgreSQL with a pool reserved by
 * the real `ReserveCohortStorage`.
 *
 * The collaborator is built by the production factory method
 * (`HumanAccountWiring.cohortStorageReadiness`, the very `@Bean` the API wires)
 * and handed to the real `AdmitBetaParticipant` over the real
 * `JdbcBetaAdmissions`. Only the Spring container is absent: the cohort state is
 * global, so this runs in its own database ([CohortTestDatabase]).
 *
 * Admission succeeds only with the pool reserved, approved and matching; every
 * runtime gap — no pool, a revoked pool, an overcommitted share, an incompatible
 * node, untrusted counts, an observation of another filesystem or writer, or a
 * deployment whose declarations differ from the pool's — answers
 * `CAPACITY_UNAVAILABLE` even with both cohort flags open, and provisions nothing.
 */
class CohortAdmissionReadinessTest {
    private lateinit var db: CohortTestDatabase
    private lateinit var declarations: StorageDeclarations
    private lateinit var legacy: UUID

    @BeforeEach
    fun database() {
        db = CohortTestDatabase.create()
        declarations = db.declarations()
        legacy = db.workspace()
        db.ready(declarations)
    }

    private fun readiness(d: StorageDeclarations = declarations): CohortStorageReadiness =
        HumanAccountWiring().cohortStorageReadiness(d, db.jdbc)

    private fun admit(
        account: UUID = db.account(),
        d: StorageDeclarations = declarations,
    ) = AdmitBetaParticipant(JdbcBetaAdmissions(db.jdbc), db.tx, Clock.systemUTC(), readiness(d)).execute(HumanAccountId(account))

    private fun operator() = ReserveCohortStorage(JdbcCohortBudgets(db.jdbc), db.tx, JdbcFootprintGate(db.jdbc), declarations)

    private fun reserve(
        payload: Long = CohortTestDatabase.GIB,
        footprint: Long = 2 * CohortTestDatabase.GIB,
    ) = operator().execute(
        mapOf(WorkspaceId(legacy) to LegacyStorageShare(payload, footprint)),
        CohortTestDatabase.GIB,
        CohortTestDatabase.GIB,
    )

    private fun participants() = db.count("SELECT count(*) FROM participant_workspace")

    private fun shouldBeRefused(result: AdmitBetaParticipant.Result) {
        result shouldBe AdmitBetaParticipant.Result.CapacityUnavailable
        participants() shouldBe 0
        db.count("SELECT count(*) FROM beta_admission") shouldBe 0
    }

    @Test
    fun `a reserved, matching pool and the real readiness bean admit participants with fixed shares`() {
        readiness().ready() shouldBe false
        reserve()
        readiness().ready() shouldBe true
        // Reservation never opens anything by itself.
        db.count("SELECT count(*) FROM beta_cohort WHERE admissions_open OR capacity_ready") shouldBe 0
        admit() shouldBe AdmitBetaParticipant.Result.Closed
        db.open()

        val first = admit()
        first.shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()
        first.newlyAdmitted shouldBe true
        admit().shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()
        participants() shouldBe 2
        db.count(
            "SELECT count(*) FROM participant_workspace WHERE payload_limit_bytes = 134217728 AND footprint_limit_bytes = 268435456",
        ) shouldBe 2
        // The admitted shares were reserved up front: the pool stays ready.
        readiness().ready() shouldBe true
    }

    @Test
    fun `with both flags open and no pool, admission is CAPACITY_UNAVAILABLE`() {
        db.open()
        readiness().ready() shouldBe false
        shouldBeRefused(admit())
    }

    @Test
    fun `capacity_ready cannot be bypassed - readiness alone, or the flag alone, admits nobody`() {
        reserve()
        db.open(admissions = true, capacity = false)
        shouldBeRefused(admit())
        db.open()
        admit().shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()
    }

    @Test
    fun `a revoked pool is CAPACITY_UNAVAILABLE`() {
        reserve()
        readiness().ready() shouldBe true
        operator().revoke()
        db.open()
        readiness().ready() shouldBe false
        shouldBeRefused(admit())
    }

    @Test
    fun `an overcommitted legacy share closes the pool - CAPACITY_UNAVAILABLE`() {
        // A legacy workspace already beyond its saved 1 MiB payload share.
        reserve(payload = 1L shl 20, footprint = 2L shl 20)
        readiness().ready() shouldBe true
        db.message(legacy, db.inbox(legacy), 2L shl 20)
        db.open()
        readiness().ready() shouldBe false
        shouldBeRefused(admit())
    }

    @Test
    fun `an unknown workspace outside the inventory closes the pool - CAPACITY_UNAVAILABLE`() {
        reserve()
        readiness().ready() shouldBe true
        db.workspace()
        db.open()
        readiness().ready() shouldBe false
        shouldBeRefused(admit())
    }

    @Test
    fun `an incompatible or undeclared node is CAPACITY_UNAVAILABLE - and the fence latches`() {
        reserve()
        readiness().ready() shouldBe true // the perturbation below is what closes it
        db.open()
        db.node("old-binary", null, mode = "OFF", capability = null)
        db.count("SELECT count(*) FROM storage_admission_latch") shouldBe 1
        readiness().ready() shouldBe false
        shouldBeRefused(admit())
    }

    @Test
    fun `a declared node with another binding is CAPACITY_UNAVAILABLE`() {
        reserve()
        readiness().ready() shouldBe true // the perturbation below is what closes it
        db.open()
        db.jdbc
            .sql("UPDATE storage_node SET cohort_binding = 'another-binding' WHERE node_id = ?")
            .param(CohortTestDatabase.SMTP)
            .update()
        readiness().ready() shouldBe false
        shouldBeRefused(admit())
    }

    @Test
    fun `untrusted counts after a distrust event are CAPACITY_UNAVAILABLE`() {
        reserve()
        readiness().ready() shouldBe true // the perturbation below is what closes it
        db.open()
        db.jdbc.sql("UPDATE storage_footprint_trust SET distrust_epoch = distrust_epoch + 1").update()
        readiness().ready() shouldBe false
        shouldBeRefused(admit())
    }

    @Test
    fun `an observation of a smaller filesystem than declared is CAPACITY_UNAVAILABLE`() {
        reserve()
        readiness().ready() shouldBe true // the perturbation below is what closes it
        db.open()
        db.observe(capacity = CohortTestDatabase.CAPACITY - 4096)
        readiness().ready() shouldBe false
        shouldBeRefused(admit())
    }

    @Test
    fun `a deployment whose declarations differ from the reserved pool is CAPACITY_UNAVAILABLE`() {
        reserve()
        readiness().ready() shouldBe true // the perturbation below is what closes it
        db.open()
        // Another monitor role: the observation's writer no longer matches.
        val otherMonitor = declarations.copy(filesystem = declarations.filesystem.copy(monitorRole = "another_monitor"))
        readiness(otherMonitor).ready() shouldBe false
        shouldBeRefused(admit(d = otherMonitor))
        // Another node inventory: another binding than the saved pool's.
        val otherNodes = db.declarations(ingestionNodes = setOf(CohortTestDatabase.SMTP, "smtp-2"))
        readiness(otherNodes).ready() shouldBe false
        shouldBeRefused(admit(d = otherNodes))
        // A mode other than ALL is never ready.
        val off = declarations.copy(enforcement = email.testinbox.domain.storage.StorageEnforcement.OFF)
        readiness(off).ready() shouldBe false
        // The matching deployment still admits.
        admit().shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()
    }
}
