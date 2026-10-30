package email.testinbox.persistence

import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.domain.identity.BetaAdmission
import email.testinbox.domain.identity.BetaCohortPolicy
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanAccountState
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The cohort invariant through the real use case, transactions and PostgreSQL. */
class BetaAdmissionsTest : PersistenceIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var store: JdbcBetaAdmissions

    @Autowired lateinit var transactions: TransactionRunner

    private val clock =
        email.testinbox.application.Clocks
            .utcMicros()

    @BeforeEach
    fun resetCohort() {
        // Only these tests use the dormant cohort. Product/owner fixtures stay intact.
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("DELETE FROM human_account").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open = false, capacity_ready = false").update()
    }

    private fun useCase(repository: BetaAdmissions = store) =
        AdmitBetaParticipant(
            repository,
            transactions,
            clock,
            email.testinbox.application.storage.CohortStorageReadiness {
                true
            },
        )

    private fun applicant(
        verified: Boolean = true,
        state: HumanAccountState = HumanAccountState.ACTIVE,
    ): HumanAccountId {
        val id = HumanAccountId(UUID.randomUUID())
        jdbc
            .sql(
                """
                INSERT INTO human_account (id, contact_email, contact_verified_at, state, created_at)
                VALUES (:id, :email, CASE WHEN :verified THEN now() ELSE NULL END, :state, now())
                """.trimIndent(),
            ).param("id", id.value)
            .param("email", "${id.value}@example.invalid")
            .param("verified", verified)
            .param("state", state.name)
            .update()
        return id
    }

    private fun openWithCapacity() {
        jdbc.sql("UPDATE beta_cohort SET admissions_open = true, capacity_ready = true").update()
    }

    private fun count(table: String): Long =
        // Table names are fixed test call sites, never request data.
        jdbc.sql("SELECT count(*) FROM $table").query(Long::class.javaObjectType).single()

    private fun admit(id: HumanAccountId = applicant()): AdmitBetaParticipant.Result.Admitted =
        useCase().execute(id).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()

    @Test
    fun `new admissions are closed by default and make no workspace`() {
        transactions.required { store.lockPolicy() } shouldBe BetaCohortPolicy(false, false)
        val before = count("workspace")
        useCase().execute(applicant()) shouldBe AdmitBetaParticipant.Result.Closed
        count("workspace") shouldBe before
        count("beta_admission") shouldBe 0
    }

    @Test
    fun `opening admissions without an approved capacity policy still refuses provisioning`() {
        jdbc.sql("UPDATE beta_cohort SET admissions_open = true").update()
        useCase().execute(applicant()) shouldBe AdmitBetaParticipant.Result.CapacityUnavailable
        count("beta_admission") shouldBe 0
    }

    @Test
    fun `missing unverified suspended and deletion pending applicants get no place`() {
        openWithCapacity()
        val candidates =
            listOf(
                HumanAccountId(UUID.randomUUID()),
                applicant(verified = false),
                applicant(state = HumanAccountState.SUSPENDED),
                applicant(state = HumanAccountState.DELETION_PENDING),
            )
        val before = count("workspace")
        candidates.forEach { useCase().execute(it) shouldBe AdmitBetaParticipant.Result.Ineligible }
        count("workspace") shouldBe before
        count("beta_admission") shouldBe 0
    }

    @Test
    fun `one admission atomically creates its private workspace project and membership`() {
        openWithCapacity()
        val id = applicant()
        val admitted = admit(id)
        admitted.newlyAdmitted shouldBe true
        admitted.admission.accountId shouldBe id
        admitted.admission.place shouldBe 1
        jdbc
            .sql(
                """
                SELECT count(*) FROM beta_admission a
                JOIN project p ON p.id = a.project_id AND p.workspace_id = a.workspace_id
                JOIN workspace w ON w.id = a.workspace_id
                WHERE a.account_id = :account
                """.trimIndent(),
            ).param("account", id.value)
            .query(Long::class.javaObjectType)
            .single() shouldBe 1
    }

    @Test
    fun `existing participants keep their original workspace while admission is paused or full`() {
        openWithCapacity()
        val id = applicant()
        val original = admit(id).admission
        repeat(BetaCohortPolicy.MAX_PARTICIPANTS - 1) { admit() }
        jdbc.sql("UPDATE beta_cohort SET admissions_open = false, capacity_ready = false").update()
        val again = admit(id)
        again.newlyAdmitted shouldBe false
        again.admission shouldBe original
        count("beta_admission") shouldBe 30
    }

    @Test
    fun `a suspended participant cannot regain access through an admission retry`() {
        openWithCapacity()
        val id = applicant()
        admit(id)
        jdbc.sql("UPDATE human_account SET state = 'SUSPENDED' WHERE id = :id").param("id", id.value).update()
        useCase().execute(id) shouldBe AdmitBetaParticipant.Result.Ineligible
        count("beta_admission") shouldBe 1
    }

    @Test
    fun `concurrent callbacks for one account create only one private workspace`() {
        openWithCapacity()
        val id = applicant()
        val before = count("workspace")
        val results = race(List(8) { id }).map { it.shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>() }
        results.count { it.newlyAdmitted } shouldBe 1
        results.map { it.admission.workspaceId }.distinct().size shouldBe 1
        count("workspace") shouldBe before + 1
        count("beta_admission") shouldBe 1
    }

    @Test
    fun `independent adapters racing for the final place admit exactly one applicant`() {
        openWithCapacity()
        repeat(29) { admit() }
        val before = count("workspace")
        val results = race(List(8) { applicant() })
        results.count { it is AdmitBetaParticipant.Result.Admitted } shouldBe 1
        results.count { it == AdmitBetaParticipant.Result.Full } shouldBe 7
        count("workspace") shouldBe before + 1
        count("beta_admission") shouldBe 30
    }

    @Test
    fun `a provisioning failure rolls back every row and leaves its place available`() {
        openWithCapacity()
        val id = applicant()
        val beforeWorkspaces = count("workspace")
        val beforeProjects = count("project")
        val failing =
            object : BetaAdmissions by store {
                override fun create(
                    admission: BetaAdmission,
                    workspace: Workspace,
                    project: Project,
                ) {
                    store.create(admission, workspace, project)
                    error("provisioning failed")
                }
            }
        shouldThrow<IllegalStateException> { useCase(failing).execute(id) }.message shouldBe "provisioning failed"
        count("workspace") shouldBe beforeWorkspaces
        count("project") shouldBe beforeProjects
        count("beta_admission") shouldBe 0
        count("participant_workspace") shouldBe 0
        admit(id).admission.place shouldBe 1
        count("participant_workspace") shouldBe 1
    }

    @Test
    fun `deleting an account leaves its consumed place and never reopens the cohort`() {
        openWithCapacity()
        val deleted = applicant()
        admit(deleted)
        repeat(29) { admit() }
        jdbc.sql("DELETE FROM human_account WHERE id = :id").param("id", deleted.value).update()
        count("beta_admission") shouldBe 30
        jdbc
            .sql("SELECT count(*) FROM beta_admission WHERE account_id IS NULL")
            .query(Long::class.javaObjectType)
            .single() shouldBe 1
        useCase().execute(applicant()) shouldBe AdmitBetaParticipant.Result.Full
    }

    @Test
    fun `the database itself refuses a thirty first place and a second cohort`() {
        val id = applicant()
        val (workspace, project) = Fixtures.provisionTenant(jdbc)
        shouldThrow<DataIntegrityViolationException> {
            jdbc
                .sql(
                    """
                    INSERT INTO beta_admission (cohort_id, place, account_id, workspace_id, project_id, admitted_at)
                    VALUES ('beta-30', 31, :account, :workspace, :project, now())
                    """.trimIndent(),
                ).param("account", id.value)
                .param("workspace", workspace.value)
                .param("project", project.value)
                .update()
        }.mostSpecificCause.message shouldContain "beta_admission_place_check"
        shouldThrow<DataIntegrityViolationException> {
            jdbc.sql("INSERT INTO beta_cohort (id) VALUES ('another-30')").update()
        }.mostSpecificCause.message shouldContain "beta_cohort_id_check"
    }

    @Test
    fun `a membership cannot point at another workspace's project`() {
        val id = applicant()
        val (workspace, _) = Fixtures.provisionTenant(jdbc)
        val (_, foreignProject) = Fixtures.provisionTenant(jdbc)
        shouldThrow<DataIntegrityViolationException> {
            jdbc
                .sql(
                    """
                    INSERT INTO beta_admission (cohort_id, place, account_id, workspace_id, project_id, admitted_at)
                    VALUES ('beta-30', 1, :account, :workspace, :project, now())
                    """.trimIndent(),
                ).param("account", id.value)
                .param("workspace", workspace.value)
                .param("project", foreignProject.value)
                .update()
        }.mostSpecificCause.message shouldContain "beta_admission_project_id_workspace_id_fkey"
    }

    @Test
    fun `a locking adapter call outside a transaction refuses instead of releasing its guard early`() {
        shouldThrow<IllegalStateException> { store.lockPolicy() }
        shouldThrow<IllegalStateException> { store.lockApplicant(applicant()) }
        shouldThrow<IllegalStateException> { store.nextPlace() }
    }

    private fun race(accounts: List<HumanAccountId>): List<AdmitBetaParticipant.Result> {
        val ready = CountDownLatch(accounts.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(accounts.size)
        return try {
            val futures =
                accounts.map { account ->
                    executor.submit<AdmitBetaParticipant.Result> {
                        // Independent repository instances and transactions: the
                        // shared guard must be PostgreSQL's, not a JVM lock.
                        val participant = useCase(JdbcBetaAdmissions(jdbc))
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        participant.execute(account)
                    }
                }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `admission creates one durable fixed share and retry never allocates a second`() {
        openWithCapacity()
        val id = applicant()
        val first = admit(id)
        admit(id).admission shouldBe first.admission
        count("participant_workspace") shouldBe 1
        val marker =
            jdbc
                .sql(
                    "SELECT workspace_id, place, payload_limit_bytes, footprint_limit_bytes FROM participant_workspace",
                ).query()
                .singleRow()
        marker["workspace_id"] shouldBe first.admission.workspaceId.value
        marker["place"] shouldBe first.admission.place
        marker["payload_limit_bytes"] shouldBe email.testinbox.domain.storage.ParticipantStorageShare.PAYLOAD_BYTES
        marker["footprint_limit_bytes"] shouldBe email.testinbox.domain.storage.ParticipantStorageShare.FOOTPRINT_BYTES
    }

    @Test
    fun `participant classification survives cleared membership and removed workspace without freeing the seat`() {
        openWithCapacity()
        val id = applicant()
        val admission = admit(id).admission
        jdbc.sql("DELETE FROM human_account WHERE id=:id").param("id", id.value).update()
        jdbc.sql("UPDATE beta_admission SET workspace_id=NULL,project_id=NULL WHERE place=:place").param("place", admission.place).update()
        jdbc.sql("DELETE FROM project WHERE id=:id").param("id", admission.projectId.value).update()
        jdbc.sql("DELETE FROM workspace WHERE id=:id").param("id", admission.workspaceId.value).update()
        jdbc
            .sql("SELECT count(*) FROM participant_workspace WHERE workspace_id=:id")
            .param("id", admission.workspaceId.value)
            .query(Long::class.javaObjectType)
            .single() shouldBe 1
        // The marker's FK protects the spent place even if a future cleanup
        // accidentally tries to delete the now non-identifying tombstone.
        shouldThrow<DataIntegrityViolationException> {
            jdbc.sql("DELETE FROM beta_admission WHERE place=:place").param("place", admission.place).update()
        }
        admit().admission.place shouldBe 2
    }

    @Test
    fun `the database refuses increases to either accepted participant ceiling`() {
        openWithCapacity()
        admit()
        for (column in listOf("payload_limit_bytes", "footprint_limit_bytes")) {
            shouldThrow<DataIntegrityViolationException> {
                jdbc.sql("UPDATE participant_workspace SET $column=$column+1").update()
            }
        }
    }

    @Test
    fun `lost share is corrupt membership not a new admission or unbounded legacy fallback`() {
        openWithCapacity()
        val id = applicant()
        val original = admit(id).admission
        jdbc.sql("DELETE FROM participant_workspace WHERE workspace_id=:id").param("id", original.workspaceId.value).update()
        shouldThrow<IllegalStateException> { store.findAdmission(id) }.message shouldContain "no durable storage share"
        shouldThrow<IllegalStateException> { useCase().execute(id) }.message shouldContain "no durable storage share"
        count("beta_admission") shouldBe 1
    }
}
