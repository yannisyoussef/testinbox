package email.testinbox.domain.inbox

import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InboxStateTest {
    private val now = Instant.parse("2026-08-29T12:00:00Z")

    private fun inbox(
        state: InboxState,
        graceUntil: Instant? = null,
    ): Inbox =
        Inbox(
            id = InboxId(UUID.randomUUID()),
            workspaceId = WorkspaceId(UUID.randomUUID()),
            projectId = ProjectId(UUID.randomUUID()),
            address = "a@testinbox.local",
            addressMode = AddressMode.GENERATED,
            state = state,
            createdAt = now.minusSeconds(60),
            expiresAt = now.plusSeconds(60),
            graceUntil = graceUntil,
        )

    @Test
    fun `active inboxes receive mail and accept waiters`() {
        inbox(InboxState.ACTIVE).canReceiveAt(now) shouldBe true
        inbox(InboxState.ACTIVE).acceptsWaiters() shouldBe true
    }

    @Test
    fun `expiring inboxes honor in-flight deliveries within grace but refuse new waiters`() {
        val expiring = inbox(InboxState.EXPIRING, graceUntil = now.plusSeconds(10))
        expiring.canReceiveAt(now) shouldBe true
        expiring.canReceiveAt(now.plusSeconds(11)) shouldBe false
        expiring.acceptsWaiters() shouldBe false
    }

    @Test
    fun `expired and deleted inboxes neither receive nor accept waiters`() {
        for (state in listOf(InboxState.EXPIRED, InboxState.DELETED)) {
            inbox(state).canReceiveAt(now) shouldBe false
            inbox(state).acceptsWaiters() shouldBe false
        }
    }
}
