package email.testinbox.persistence

import email.testinbox.application.usecase.StorageAdmissionDecision
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.storage.StorageScope
import email.testinbox.persistence.AdmissionFixture.Companion.policy
import email.testinbox.persistence.AdmissionFixture.Companion.shape
import email.testinbox.persistence.AdmissionScenarios.F
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.stream.Stream

/**
 * ADR-035 §4 against PostgreSQL: boundaries for every scope, refusal
 * precedence, multi-recipient envelope order and enforcement OFF.
 */
class StorageAdmissionDecisionTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var fx: AdmissionFixture

    @BeforeEach
    fun database() {
        fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
    }

    // --- C. boundaries ---------------------------------------------------------------

    @ParameterizedTest(name = "{0} holding limit − f + {1}")
    @MethodSource("boundaries")
    fun `a copy fits exactly at limit − f, and never once the limit would be crossed`(
        scope: StorageScope,
        offset: Long,
    ) {
        AdmissionScenarios.boundary(fx, AdmissionUnderTest.REAL, scope, offset)
    }

    // --- D. precedence ------------------------------------------------------------------

    @Test
    fun `the narrowest exceeded ceiling is reported`() {
        AdmissionScenarios.precedence(fx, AdmissionUnderTest.REAL)
    }

    // --- E. multi-recipient ---------------------------------------------------------------

    @Test
    fun `earlier copies of the event count against later ones, per inbox and per workspace`() {
        AdmissionScenarios.runningTotals(fx, AdmissionUnderTest.REAL)
    }

    @Test
    fun `a full workspace refuses only its own copies, never another tenant's`() {
        val db = fx.db
        val a = db.workspace()
        val a1 = db.inbox(a)
        fx.workspaceBase(a, 1_000)
        val b = db.workspace()
        val b1 = db.inbox(b)
        val b2 = db.inbox(b)
        val admission = fx.admission(policy(workspace = 1_000, global = 1_000_000))

        val result = admission.admit(fx.request(F, listOf(fx.candidate(a, a1), fx.candidate(b, b1), fx.candidate(b, b2))))

        result.shape() shouldBe listOf("WORKSPACE_LIMIT", "admitted", "admitted")
        fx.reservationCount() shouldBe 2
    }

    @Test
    fun `global exhaustion mid-event admits exactly the envelope-order prefix, across tenants`() {
        val db = fx.db
        val workspaces = List(3) { db.workspace() }
        val admission = fx.admission(policy(workspace = 1_000, global = 3 * F + 5)) // three copies fit, not four
        // Six copies, two inboxes per tenant, interleaved across the three tenants.
        val candidates = List(6) { i -> workspaces[i % 3].let { ws -> fx.candidate(ws, db.inbox(ws)) } }

        val result = admission.admit(fx.request(F, candidates))

        result.shape() shouldBe List(3) { "admitted" } + List(3) { "SERVICE_CAPACITY" }
        result.admitted.map { it.candidate } shouldBe candidates.take(3)
    }

    @Test
    fun `the envelope order decides who is admitted, and the same input always decides the same`() {
        fun runOnce(reversed: Boolean): List<String> {
            val fresh = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
            val db = fresh.db
            val a = db.workspace()
            val b = db.workspace()
            val cs = listOf(fresh.candidate(a, db.inbox(a)), fresh.candidate(b, db.inbox(b)))
            val ordered = if (reversed) cs.reversed() else cs
            val result = fresh.admission(policy(workspace = 1_000, global = F)).admit(fresh.request(F, ordered))
            // Which workspace won, by position in the ORIGINAL order.
            return cs.map { c ->
                result.decisions.single { it.candidate == c }.let { if (it is StorageAdmissionDecision.Admitted) "admitted" else "refused" }
            }
        }

        runOnce(reversed = false) shouldBe listOf("admitted", "refused")
        runOnce(reversed = true) shouldBe listOf("refused", "admitted")
        runOnce(reversed = false) shouldBe listOf("admitted", "refused")
    }

    @Test
    fun `a 50-recipient event across ten workspaces reserves every copy in one T1`() {
        val db = fx.db
        val workspaces = List(10) { db.workspace() }
        val candidates = List(50) { i -> workspaces[i % 10].let { ws -> fx.candidate(ws, db.inbox(ws), attachments = 3) } }

        val result = fx.admission(StorageCapacityPolicyFixtures.GENEROUS).admit(fx.request(4_096, candidates))

        result.admitted.size shouldBe 50
        fx.reservationCount() shouldBe 50
        fx.reservedBytes() shouldBe 50 * 4_096L
        db.jdbc
            .sql("SELECT count(*) FROM storage_reservation WHERE cardinality(object_keys) = 4 AND state = 'RESERVED'")
            .query(Long::class.java)
            .single() shouldBe 50
    }

    @Test
    fun `a 50-recipient event that exhausts the global cap mid-way reserves exactly the prefix`() {
        val db = fx.db
        val workspaces = List(10) { db.workspace() }
        val candidates = List(50) { i -> workspaces[i % 10].let { ws -> fx.candidate(ws, db.inbox(ws)) } }

        val result = fx.admission(policy(workspace = 1_000_000, global = 37 * F)).admit(fx.request(F, candidates))

        result.shape() shouldBe List(37) { "admitted" } + List(13) { "SERVICE_CAPACITY" }
        fx.reservationCount() shouldBe 37
    }

    // --- K. enforcement OFF ------------------------------------------------------------------

    @Test
    fun `enforcement OFF admits and reserves beyond every ceiling, keeping the running totals`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        fx.base(ws, inbox, 5_000) // over inbox, workspace and global alike
        val policy = policy(workspace = 1_000, share = "0.5", global = 2_000)

        val siblings = List(3) { db.inbox(ws) }

        val result =
            fx
                .admission(policy, StorageEnforcement.OFF)
                .admit(fx.request(F, (listOf(inbox) + siblings).map { fx.candidate(ws, it) }))

        result.refused.shouldBeEmpty()
        // The full inbox would have been INBOX_LIMIT; its empty siblings, WORKSPACE_LIMIT.
        result.admitted.map { it.unenforcedLimit } shouldBe
            listOf(StorageRefusalReason.INBOX_LIMIT) + List(3) { StorageRefusalReason.WORKSPACE_LIMIT }
        fx.reservationCount() shouldBe 4
        // The next snapshot sees all four: OFF still counts what it admits.
        fx.used(fx.snapshot(setOf(ws), setOf(inbox)), ws, inbox) shouldBe Triple(5_040L, 5_040L, 5_010L)
    }

    @Test
    fun `enforcement OFF under every ceiling records nothing it would have refused`() {
        val db = fx.db
        val ws = db.workspace()

        val result =
            fx
                .admission(
                    StorageCapacityPolicyFixtures.GENEROUS,
                    StorageEnforcement.OFF,
                ).admit(fx.request(F, listOf(fx.candidate(ws, db.inbox(ws)))))

        result.admitted.single().unenforcedLimit shouldBe null
    }

    companion object {
        @JvmStatic
        fun boundaries(): Stream<Arguments> =
            StorageScope.entries
                .flatMap { scope -> listOf(0L, 1L, F, F + 1).map { Arguments.of(scope, it) } }
                .stream()
    }
}

object StorageCapacityPolicyFixtures {
    val GENEROUS = policy(workspace = 1L shl 40, global = 1L shl 50)
}
