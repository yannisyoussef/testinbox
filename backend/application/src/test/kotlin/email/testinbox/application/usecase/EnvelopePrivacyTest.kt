package email.testinbox.application.usecase

import email.testinbox.application.port.InboxStorageUsage
import email.testinbox.application.port.ObservedFootprint
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.storage.FootprintPolicy
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.FootprintModel
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageUsage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Own outcomes must not reveal whether an earlier foreign copy passed its tenant limit. */
class EnvelopePrivacyTest {
    private val foreign = WorkspaceId(UUID.randomUUID())
    private val own = WorkspaceId(UUID.randomUUID())
    private val foreignInbox = InboxId(UUID.randomUUID())
    private val ownInbox = InboxId(UUID.randomUUID())
    private val placements =
        listOf(
            StorageAdmissionRules.Placement(foreign, foreignInbox),
            StorageAdmissionRules.Placement(own, ownInbox),
        )
    private val model = FootprintModel.REFERENCE

    private fun snapshot(foreignFull: Boolean) =
        StorageUsageSnapshot(
            Instant.EPOCH,
            StorageUsage.ZERO,
            mapOf(foreign to StorageUsage(if (foreignFull) 100 else 0, 0), own to StorageUsage.ZERO),
            mapOf(foreignInbox to InboxStorageUsage(foreign, StorageUsage.ZERO), ownInbox to InboxStorageUsage(own, StorageUsage.ZERO)),
            ObservedFootprint(0, 0, 0, 0, true, 0, 1, 0, 4096, Long.MAX_VALUE / 4, "monitor"),
        )

    @Test
    fun `foreign tenant refusal cannot change own payload outcome at global boundary`() {
        val policy = StorageCapacityPolicy(100, InboxShare.of("1"), 10, 0)
        val outcomes =
            listOf(false, true).map { full ->
                StorageAdmissionRules.decide(policy, StorageEnforcement.ALL, 10, placements, snapshot(full)).last()
            }
        outcomes[0].admitted shouldBe false
        outcomes[1] shouldBe outcomes[0]
    }

    @Test
    fun `foreign tenant refusal cannot change own footprint outcome at global boundary`() {
        val policy = StorageCapacityPolicy(100, InboxShare.of("1"), 10000, 0)
        val fp = FootprintPolicy(model, FootprintAdmission.Limits(model.bound(10, 1), 0, 0, 0, Long.MAX_VALUE / 4, 0), "monitor")
        val outcomes =
            listOf(false, true).map { full ->
                StorageAdmissionRules.decide(policy, StorageEnforcement.ALL, 10, 1, placements, snapshot(full), fp).last()
            }
        outcomes[0].admitted shouldBe false
        outcomes[1] shouldBe outcomes[0]
    }

    @Test
    fun `substituting any foreign position by unknown preserves own outcome for both global ceilings`() {
        val random = kotlin.random.Random(37)
        repeat(500) {
            val beforeOwn = random.nextInt(1, 50)
            val foreignPlacements =
                List(beforeOwn) { StorageAdmissionRules.Placement(WorkspaceId(UUID.randomUUID()), InboxId(UUID.randomUUID())) }
            val all = foreignPlacements + placements.last()
            val positions = all.indices.toList()
            val kept = positions.filter { it == beforeOwn || random.nextBoolean() }
            val reduced = kept.map(all::get)
            val state =
                snapshot(false).copy(
                    workspaces = all.associate { it.workspaceId to StorageUsage.ZERO },
                    inboxes = all.associate { it.inboxId to InboxStorageUsage(it.workspaceId, StorageUsage.ZERO) },
                )
            val byteLimit = random.nextLong(1, 1000)
            val footprintLimit = random.nextLong(1, model.bound(500, 50) + 1)
            val payload = StorageCapacityPolicy(10000, InboxShare.of("1"), byteLimit, 0)
            val fp = FootprintPolicy(model, FootprintAdmission.Limits(footprintLimit, 0, 0, 0, Long.MAX_VALUE / 4, 0), "monitor")
            for (footprint in listOf(null, fp)) {
                val original = StorageAdmissionRules.decide(payload, StorageEnforcement.ALL, 10, 1, all, state, footprint, positions).last()
                val substituted =
                    StorageAdmissionRules
                        .decide(
                            payload,
                            StorageEnforcement.ALL,
                            10,
                            1,
                            reduced,
                            state,
                            footprint,
                            kept,
                        ).last()
                substituted shouldBe original
            }
        }
    }

    @Test
    fun `unknown position is not compressed before T1 without a filesystem policy`() {
        val store =
            object : email.testinbox.application.port.StorageAdmissionStore {
                override fun <R : Any> admit(
                    scope: email.testinbox.application.port.StorageAdmissionScope,
                    decide: (StorageUsageSnapshot) -> email.testinbox.application.port.StorageAdmissionPlan<R>,
                ): R = decide(snapshot(false)).outcome
            }
        val message = email.testinbox.domain.MessageId(UUID.randomUUID())
        val candidate =
            StorageAdmissionCandidate(
                message,
                own,
                ownInbox,
                listOf(
                    email.testinbox.application.ObjectKeys
                        .raw(own, ownInbox, message),
                ),
            )
        val result =
            StorageAdmission(store, StorageCapacityPolicy(100, InboxShare.of("1"), 10, 0), StorageEnforcement.ALL)
                .admit(StorageAdmissionRequest(10, listOf(candidate), "node", UUID.randomUUID(), listOf(1)))
        result.admitted.size shouldBe 0
        result.refused.single().reason shouldBe email.testinbox.domain.storage.StorageRefusalReason.SERVICE_CAPACITY
    }

    @Test
    fun `invalid or reordered envelope positions are rejected before storage`() {
        val message = email.testinbox.domain.MessageId(UUID.randomUUID())
        val a =
            StorageAdmissionCandidate(
                message,
                own,
                ownInbox,
                listOf(
                    email.testinbox.application.ObjectKeys
                        .raw(own, ownInbox, message),
                ),
            )
        for (positions in listOf(emptyList(), listOf(-1), listOf(50), listOf(0, 1))) {
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                StorageAdmissionRequest(10, listOf(a), "node", UUID.randomUUID(), positions)
            }
        }
        val bMessage = email.testinbox.domain.MessageId(UUID.randomUUID())
        val b =
            StorageAdmissionCandidate(
                bMessage,
                foreign,
                foreignInbox,
                listOf(
                    email.testinbox.application.ObjectKeys
                        .raw(foreign, foreignInbox, bMessage),
                ),
            )
        for (positions in listOf(listOf(1, 0), listOf(1, 1))) {
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                StorageAdmissionRequest(10, listOf(a, b), "node", UUID.randomUUID(), positions)
            }
        }
    }
}
