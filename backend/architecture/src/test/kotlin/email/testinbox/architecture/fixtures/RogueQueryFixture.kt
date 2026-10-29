package email.testinbox.architecture.fixtures

import email.testinbox.application.port.WaitSlots
import email.testinbox.domain.WorkspaceId
import java.time.Duration

/**
 * NEVER production code. A "usage read" that claims a slot on the way — the
 * shape the limit-decision rule exists to catch in a query service.
 */
class RogueQueryFixture(
    private val waitSlots: WaitSlots,
) {
    fun usage(workspaceId: WorkspaceId): Long {
        waitSlots.acquire(workspaceId, 1, Duration.ofSeconds(1))?.close()
        return waitSlots.held(workspaceId)
    }
}
