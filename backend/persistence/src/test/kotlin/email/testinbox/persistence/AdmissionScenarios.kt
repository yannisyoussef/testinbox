package email.testinbox.persistence

import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageScope
import email.testinbox.persistence.AdmissionFixture.Companion.policy
import email.testinbox.persistence.AdmissionFixture.Companion.shape
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.util.UUID

/** Builds the admission under test: the real one, or a test-only mutant. */
fun interface AdmissionUnderTest {
    fun build(
        fx: AdmissionFixture,
        policy: StorageCapacityPolicy,
        enforcement: StorageEnforcement,
    ): StorageAdmission

    companion object {
        val REAL = AdmissionUnderTest { fx, policy, enforcement -> fx.admission(policy, enforcement) }
    }
}

/**
 * ADR-035 T1 decision scenarios against PostgreSQL. Each one both proves the
 * real admission and, run against a test-only mutant, proves that it can
 * fail (`StorageAdmissionMutantTest`).
 */
object AdmissionScenarios {
    const val F = 10L

    /**
     * Seeds [bytes] of usage into one scope's inbox, spread over all three
     * sums so that dropping any of them from the read changes the figure.
     */
    private fun seed(
        fx: AdmissionFixture,
        workspace: UUID,
        inbox: UUID,
        bytes: Long,
    ) {
        require(bytes >= 30)
        fx.base(workspace, inbox, bytes - 30)
        fx.delta(workspace, inbox, 10)
        fx.reservation(workspace, inbox, 20)
    }

    /**
     * A copy of [F] against [scope] already holding `limit + offset − F`:
     * offset 0 is an exact fit, and 1, [F] and [F] + 1 are one byte over,
     * exactly full and already over.
     */
    fun boundary(
        fx: AdmissionFixture,
        under: AdmissionUnderTest,
        scope: StorageScope,
        offset: Long,
    ) {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val policy =
            when (scope) {
                StorageScope.INBOX -> policy(workspace = 1_000, share = "0.1", global = 1_000_000)

                // inbox 100
                StorageScope.WORKSPACE -> policy(workspace = 1_000, global = 1_000_000)

                StorageScope.GLOBAL -> policy(workspace = 1_000_000, global = 5_000, h = 1_000) // cap 4 000
            }
        val used = policy.limitOf(scope) - F + offset
        when (scope) {
            StorageScope.INBOX -> seed(fx, ws, inbox, used)

            StorageScope.WORKSPACE -> seed(fx, ws, db.inbox(ws), used)

            // a sibling fills the workspace
            StorageScope.GLOBAL -> db.workspace().let { other -> seed(fx, other, db.inbox(other), used) }
        }

        val result = under.build(fx, policy, StorageEnforcement.ON).admit(fx.request(F, listOf(fx.candidate(ws, inbox))))

        val expected = if (offset <= 0) "admitted" else scopeReason(scope)
        withClue("$scope at limit − f + $offset") { result.shape() shouldBe listOf(expected) }
        fx.reservationCount() shouldBe 1L + (if (expected == "admitted") 1 else 0) // the seeded one, plus ours
    }

    /** Several ceilings exceeded at once: the narrowest is reported, never the first row read. */
    fun precedence(
        fx: AdmissionFixture,
        under: AdmissionUnderTest,
    ) {
        val policy = policy(workspace = 100, share = "0.5", global = 1_000) // inbox 50, workspace 100, cap 1 000
        val admission = under.build(fx, policy, StorageEnforcement.ON)
        val db = fx.db

        // All three exceeded.
        run {
            val ws = db.workspace()
            val inbox = db.inbox(ws)
            seed(fx, ws, inbox, 50)
            seed(fx, ws, db.inbox(ws), 50)
            db.workspace().let { seed(fx, it, db.inbox(it), 900) }
            withClue("inbox + workspace + global exceeded") {
                admission.admit(fx.request(F, listOf(fx.candidate(ws, inbox)))).shape() shouldBe listOf("INBOX_LIMIT")
            }
        }
        // Workspace and global exceeded (global now 1 000 + 100).
        run {
            val ws = db.workspace()
            val inbox = db.inbox(ws)
            seed(fx, ws, db.inbox(ws), 50)
            seed(fx, ws, db.inbox(ws), 50)
            withClue("workspace + global exceeded") {
                admission.admit(fx.request(F, listOf(fx.candidate(ws, inbox)))).shape() shouldBe listOf("WORKSPACE_LIMIT")
            }
        }
        // Only global exceeded.
        run {
            val ws = db.workspace()
            withClue("only global exceeded") {
                admission.admit(fx.request(F, listOf(fx.candidate(ws, db.inbox(ws))))).shape() shouldBe listOf("SERVICE_CAPACITY")
            }
        }
    }

    /**
     * Copies admitted earlier in one event count against later ones. One
     * workspace has room for exactly two copies across five inboxes, and
     * another workspace's three inboxes each have room for one.
     */
    fun runningTotals(
        fx: AdmissionFixture,
        under: AdmissionUnderTest,
    ) {
        val db = fx.db
        val policy = policy(workspace = 50, share = "0.2", global = 1_000_000) // inbox 10: exactly one copy each
        val a = db.workspace()
        val aInboxes = List(5) { db.inbox(a) }
        seed(fx, a, db.inbox(a), 30) // workspace a: 20 left, two copies
        val b = db.workspace()
        val bInbox = db.inbox(b)

        val candidates = aInboxes.map { fx.candidate(a, it) } + List(2) { fx.candidate(b, bInbox) }
        val result = under.build(fx, policy, StorageEnforcement.ON).admit(fx.request(F, candidates))

        withClue("running totals") {
            result.shape() shouldBe
                listOf("admitted", "admitted", "WORKSPACE_LIMIT", "WORKSPACE_LIMIT", "WORKSPACE_LIMIT", "admitted", "INBOX_LIMIT")
        }
        fx.reservedBytesIn(a) shouldBe 20 + 2 * F
        fx.reservedBytesIn(b) shouldBe F
    }
}

fun scopeReason(scope: StorageScope): String =
    when (scope) {
        StorageScope.INBOX -> "INBOX_LIMIT"
        StorageScope.WORKSPACE -> "WORKSPACE_LIMIT"
        StorageScope.GLOBAL -> "SERVICE_CAPACITY"
    }
