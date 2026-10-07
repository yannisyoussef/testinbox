package email.testinbox.application.query

import email.testinbox.application.port.InboxStorageFigures
import email.testinbox.application.port.StorageAccountingOverflowException
import email.testinbox.application.port.StorageRefusalSnapshot
import email.testinbox.application.port.StorageVisibility
import email.testinbox.application.port.WorkspaceStorageFigures
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.storage.StorageUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ADR-035 §13's `StorageUsage` formulas (TI-STORAGE-004 §7, §8, §10), on
 * figures handed in directly. The SQL that produces the figures is proven in
 * the persistence suite.
 */
class StorageQueriesTest {
    /** Workspace 1000, inbox share 0.5 → inbox limit 500. Global values are irrelevant here. */
    private val policy = StorageCapacityPolicy(1_000, InboxShare.of("0.5"), 1L shl 40, 0)

    @Test
    fun `workspace - stored is base plus delta, reserved is every reservation, available is the remaining headroom`() {
        val view = StorageUsageView.workspace(policy, StorageUsage(committedBytes = 600, reservedBytes = 150))
        view shouldBe StorageUsageView(limitBytes = 1_000, storedBytes = 600, reservedBytes = 150, availableBytes = 250, overLimit = false)
    }

    @Test
    fun `at exactly the limit - overLimit is false and available is zero`() {
        StorageUsageView.workspace(policy, StorageUsage(800, 200)) shouldBe
            StorageUsageView(1_000, 800, 200, availableBytes = 0, overLimit = false)
        StorageUsageView.inbox(policy, StorageUsage(500, 0), StorageUsage(500, 0)) shouldBe
            StorageUsageView(500, 500, 0, availableBytes = 0, overLimit = false)
    }

    @Test
    fun `over the limit - overLimit is true, available is zero, and stored is reported as it is`() {
        StorageUsageView.workspace(policy, StorageUsage(900, 200)) shouldBe
            StorageUsageView(1_000, 900, 200, availableBytes = 0, overLimit = true)
    }

    @Test
    fun `inbox - overLimit is the inbox's own state, available is the minimum of inbox and workspace headroom`() {
        // Inbox has 300 of headroom; the workspace only 100.
        StorageUsageView.inbox(policy, inbox = StorageUsage(150, 50), workspace = StorageUsage(850, 50)) shouldBe
            StorageUsageView(500, 150, 50, availableBytes = 100, overLimit = false)
        // Inbox headroom is the binding one.
        StorageUsageView.inbox(policy, inbox = StorageUsage(400, 50), workspace = StorageUsage(400, 50)) shouldBe
            StorageUsageView(500, 400, 50, availableBytes = 50, overLimit = false)
    }

    @Test
    fun `a full workspace leaves an inbox with zero available but not over its own limit`() {
        StorageUsageView.inbox(policy, inbox = StorageUsage(10, 0), workspace = StorageUsage(1_000, 0)) shouldBe
            StorageUsageView(500, 10, 0, availableBytes = 0, overLimit = false)
    }

    @Test
    fun `an inbox over its own limit is overLimit even with workspace headroom`() {
        StorageUsageView.inbox(policy, inbox = StorageUsage(600, 0), workspace = StorageUsage(600, 0)) shouldBe
            StorageUsageView(500, 600, 0, availableBytes = 0, overLimit = true)
    }

    @Test
    fun `negative drift is reported as it is, never clamped into a healthier figure`() {
        // Reconciliation repairs drift; the API does not hide it. Only `availableBytes` has a floor.
        val view = StorageUsageView.workspace(policy, StorageUsage(committedBytes = -40, reservedBytes = 0))
        view.storedBytes shouldBe -40
        view.availableBytes shouldBe 1_040
        view.overLimit shouldBe false
    }

    @Test
    fun `an overflowing sum is an internal failure, not a quota figure`() {
        shouldThrow<StorageAccountingOverflowException> {
            StorageUsageView.workspace(policy, StorageUsage(Long.MAX_VALUE, 1))
        }
        shouldThrow<StorageAccountingOverflowException> {
            StorageUsageView.inbox(policy, StorageUsage(Long.MIN_VALUE, 0), StorageUsage.ZERO)
        }
    }

    @Test
    fun `the queries read one snapshot for a set of inboxes and map each inbox to its own view`() {
        val workspace = WorkspaceId(UUID.randomUUID())
        val a = InboxId(UUID.randomUUID())
        val b = InboxId(UUID.randomUUID())
        val refusedAt = Instant.parse("2026-10-07T10:00:00Z")
        val reads = mutableListOf<Set<InboxId>>()
        val visibility =
            object : StorageVisibility {
                override fun read(
                    workspaceId: WorkspaceId,
                    inboxIds: Set<InboxId>,
                ): WorkspaceStorageFigures {
                    workspaceId shouldBe workspace
                    reads += inboxIds
                    return WorkspaceStorageFigures(
                        workspace = StorageUsage(700, 100),
                        inboxes =
                            mapOf(
                                a to InboxStorageFigures(StorageUsage(100, 0), StorageRefusalSnapshot.NONE),
                                b to
                                    InboxStorageFigures(
                                        StorageUsage(450, 60),
                                        StorageRefusalSnapshot(2, refusedAt, StorageRefusalReason.INBOX_LIMIT),
                                    ),
                            ),
                    )
                }
            }
        val queries = StorageQueries(visibility, policy)

        val views = queries.inboxes(workspace, setOf(a, b))
        reads shouldBe listOf(setOf(a, b))
        views.getValue(a) shouldBe
            InboxStorageView(StorageUsageView(500, 100, 0, availableBytes = 200, overLimit = false), StorageRefusalSnapshot.NONE)
        views.getValue(b) shouldBe
            InboxStorageView(
                StorageUsageView(500, 450, 60, availableBytes = 0, overLimit = true),
                StorageRefusalSnapshot(2, refusedAt, StorageRefusalReason.INBOX_LIMIT),
            )

        queries.workspace(workspace) shouldBe StorageUsageView(1_000, 700, 100, availableBytes = 200, overLimit = false)
        // The workspace read asks for no inbox; an empty inbox set asks nothing at all.
        reads.last() shouldBe emptySet()
        queries.inboxes(workspace, emptySet()) shouldBe emptyMap()
        reads.size shouldBe 2
    }

    @Test
    fun `an inbox the read did not return is empty, never another tenant's figures`() {
        val visibility =
            object : StorageVisibility {
                override fun read(
                    workspaceId: WorkspaceId,
                    inboxIds: Set<InboxId>,
                ) = WorkspaceStorageFigures(StorageUsage(10, 0), emptyMap())
            }
        val foreign = InboxId(UUID.randomUUID())
        StorageQueries(visibility, policy).inbox(WorkspaceId(UUID.randomUUID()), foreign) shouldBe
            InboxStorageView(StorageUsageView(500, 0, 0, availableBytes = 500, overLimit = false), StorageRefusalSnapshot.NONE)
    }

    @Test
    fun `a refusal snapshot is self-consistent by construction`() {
        shouldThrow<IllegalArgumentException> { StorageRefusalSnapshot(1, null, null) }
        shouldThrow<IllegalArgumentException> { StorageRefusalSnapshot(0, Instant.EPOCH, StorageRefusalReason.INBOX_LIMIT) }
        shouldThrow<IllegalArgumentException> { StorageRefusalSnapshot(-1, null, null) }
    }
}
