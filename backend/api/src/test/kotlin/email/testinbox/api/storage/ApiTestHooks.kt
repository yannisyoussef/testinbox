package email.testinbox.api.storage

import email.testinbox.application.usecase.WaitSyncHook
import email.testinbox.domain.InboxId
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The ADR-035 §17 wait seam, contributed to the API test context only. The
 * production wiring has no bean of this type and runs `WaitSyncHook.NOOP`
 * (`ApiWiring.waitForMessage`), so nothing deployable can reach it.
 */
@TestConfiguration
class ApiTestHooks {
    @Bean
    fun waitSyncHook(): ScriptableWaitHook = ScriptableWaitHook()
}

/**
 * Lets an API test commit a message or a refusal at an exact point of the
 * check → subscribe → recheck → park sequence, or after the decision, and
 * records which points each wait passed. Shared by every test in the
 * context, so callbacks filter on the inbox they care about and
 * [reset] is called after each test that scripted one.
 */
class ScriptableWaitHook : WaitSyncHook {
    @Volatile var onAfterInitialCheck: (InboxId) -> Unit = {}

    @Volatile var onAfterSubscribe: (InboxId) -> Unit = {}

    @Volatile var onAfterDecision: (InboxId) -> Unit = {}

    /** `(point, inbox)` for every point every wait passed. */
    val calls = CopyOnWriteArrayList<Pair<String, InboxId>>()

    fun reset() {
        onAfterInitialCheck = {}
        onAfterSubscribe = {}
        onAfterDecision = {}
        calls.clear()
    }

    fun pointsFor(inboxId: InboxId): List<String> = calls.filter { it.second == inboxId }.map { it.first }

    override fun afterInitialCheck(inboxId: InboxId) {
        calls += "afterInitialCheck" to inboxId
        onAfterInitialCheck(inboxId)
    }

    override fun afterSubscribe(inboxId: InboxId) {
        calls += "afterSubscribe" to inboxId
        onAfterSubscribe(inboxId)
    }

    override fun afterDecision(inboxId: InboxId) {
        calls += "afterDecision" to inboxId
        onAfterDecision(inboxId)
    }
}
