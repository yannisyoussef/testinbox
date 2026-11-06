package email.testinbox.benchmark

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The timing probes' roles (security review S-C1): the observer and the cold
 * workspace are never the legacy workspace, and no role and not the legacy
 * workspace receives background traffic; a run that would break either fails.
 */
class ProbeRolesTest {
    /** [workspaces] workspaces of [perWorkspace] inboxes, ordered by workspace as the seeder orders them. */
    private fun population(
        workspaces: Int,
        perWorkspace: Int = 5,
    ): SeededPopulation {
        val ids = List(workspaces) { UUID(0, it.toLong() + 1) }
        return SeededPopulation(workspaces, ids.flatMap { ws -> List(perWorkspace) { SeededInbox(ws, UUID.randomUUID(), "x@bench.test") } })
    }

    @Test
    fun `background traffic covers every other workspace and never a probe role or the legacy workspace`() {
        val cohort = population(31)
        val legacy = cohort.workspaceIds.last()
        val roles = ProbeRoles(cohort, legacy, reservedHotInboxes = 3)
        val excluded = setOf(roles.hot.first().workspaceId, roles.observer.first().workspaceId, roles.cold.first().workspaceId, legacy)
        excluded.size shouldBe 4
        roles.background.map { it.workspaceId }.distinct() shouldContainExactlyInAnyOrder cohort.workspaceIds - excluded
        roles.inside.size shouldBe 3
        roles.inside.forEach {
            it.first().workspaceId shouldBe roles.hot.first().workspaceId
            it[1] shouldBe roles.observer.first()
        }
        roles.coldTarget.size shouldBe 3 // rotated like `inside`, so no single inbox meets its per-inbox INGEST refill
        roles.coldTarget.forEach { it.single().workspaceId shouldBe roles.cold.first().workspaceId }
    }

    @Test
    fun `an observer or cold workspace that is the legacy workspace fails the run`() {
        val cohort = population(31)
        shouldThrow<IllegalStateException> { ProbeRoles(cohort, cohort.workspaceIds[1], 3) }
        shouldThrow<IllegalStateException> { ProbeRoles(cohort, cohort.workspaceIds[2], 3) }
    }

    @Test
    fun `a cohort with no workspace left for the background fails the run`() {
        val cohort = population(4)
        shouldThrow<IllegalStateException> { ProbeRoles(cohort, cohort.workspaceIds[3], 3) }
    }

    @Test
    fun `the background load drives its events through the given sender and summarizes what got through`() {
        val inboxes = population(5).inboxes
        val load =
            BackgroundLoad(inboxes, 100.0, 1, 2, RecipientMix(mapOf(2 to 1)), 7) { chosen ->
                BackgroundResult(chosen.size, System.nanoTime(), unavailable = false)
            }
        load.start()
        load.join()
        val summary = load.summary()
        summary.events shouldBe 100
        summary.admittedCopies shouldBe 200
        summary.eventsReachingT1 shouldBe 100
        summary.t1?.samples shouldBe 100
    }
}
