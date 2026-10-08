package email.testinbox.ingestion.guarded

import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * TI-STORAGE-006 §44 and §45, against the REAL adapters and the pinned MinIO,
 * in an isolated database and bucket, with every activation prerequisite
 * synthetically satisfied (the guard is clear). TEST ONLY: no deployable is
 * configured this way, and the committed environments stay OFF.
 *
 * - TENANT_LIMITS: `INBOX_LIMIT` and `WORKSPACE_LIMIT` refuse; the global
 *   ceiling is observational only and can never refuse.
 * - ALL: all three ceilings refuse.
 * - A refusal is a `250`-class outcome (the use case returns; nothing is
 *   thrown), recorded on the inbox for its tenant (§6a), with no upload.
 * - §22: a set activation guard answers as infrastructure (`451`) before the
 *   slot and before T1, reserving nothing.
 */
class EnforcementModesTest {
    private val open = mutableListOf<GuardedIngestHarness>()

    private fun harness(
        enforcement: StorageEnforcement,
        policy: StorageCapacityPolicy,
    ) = GuardedIngestHarness(enforcement = enforcement, policy = policy).also { open += it }

    @AfterEach
    fun close() = open.forEach { it.close() }

    /** One copy's exact footprint, measured through the real path once. */
    private fun footprint(): Long =
        harness(StorageEnforcement.OFF, GuardedIngestHarness.GENEROUS).let { h ->
            val (_, address) = h.inbox(h.workspace())
            h.deliver(listOf(address)).accepted.size shouldBe 1
            h.committedBytes()
        }

    /** Workspace limit 10f, inbox share 0.5 (5f), G − H = 2f: the global cap is the tightest of the three. */
    private fun policy(f: Long) = StorageCapacityPolicy(10 * f, InboxShare.of("0.5"), 2 * f + 1, 1)

    @Test
    fun `TENANT_LIMITS enforces INBOX_LIMIT and WORKSPACE_LIMIT, and SERVICE_CAPACITY stays observational`() {
        val f = footprint()
        val h = harness(StorageEnforcement.TENANT_LIMITS, policy(f))
        val inboxWs = h.workspace()
        val (fullInbox, inboxFull) = h.inbox(inboxWs)
        h.jdbc
            .sql("INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes) VALUES (?, ?, ?)")
            .params(
                fullInbox.value,
                inboxWs.value,
                5 * f,
            ).update()
        val fullWs = h.workspace()
        val (_, workspaceFull) = h.inbox(fullWs)
        h.jdbc
            .sql("INSERT INTO workspace_storage_account (workspace_id, base_bytes) VALUES (?, ?)")
            .params(fullWs.value, 10 * f)
            .update()

        val refused = h.deliver(listOf(inboxFull, workspaceFull))
        refused.accepted.size shouldBe 0
        refused.storageRefusedRecipients shouldBe 2
        h.count("SELECT count(*) FROM inbox_storage WHERE last_refusal_reason = 'INBOX_LIMIT' AND refusal_count = 1") shouldBe 1
        h.count("SELECT count(*) FROM inbox_storage WHERE last_refusal_reason = 'WORKSPACE_LIMIT' AND refusal_count = 1") shouldBe 1
        h.fencedWrites.size shouldBe 0
        h.metrics.events shouldContain "admission:REFUSED_INBOX"
        h.metrics.events shouldContain "admission:REFUSED_WORKSPACE"

        // The global cap (2f, with 15f already accounted) is exceeded by every copy, yet nothing refuses on it.
        val (_, fresh) = h.inbox(h.workspace())
        val admitted = h.deliver(listOf(fresh))
        admitted.accepted.size shouldBe 1
        admitted.storageRefusedRecipients shouldBe 0
        h.metrics.events shouldContain "unenforced:GLOBAL"
        h.metrics.events shouldNotContain "admission:REFUSED_GLOBAL"
        h.count("SELECT count(*) FROM inbox_storage WHERE last_refusal_reason = 'SERVICE_CAPACITY'") shouldBe 0
        h.messageCount() shouldBe 1
    }

    @Test
    fun `ALL lets all three ceilings refuse, narrowest first`() {
        val f = footprint()
        val h = harness(StorageEnforcement.ALL, policy(f))
        val inboxWs = h.workspace()
        val (fullInbox, inboxFull) = h.inbox(inboxWs)
        h.jdbc
            .sql("INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes) VALUES (?, ?, ?)")
            .params(
                fullInbox.value,
                inboxWs.value,
                5 * f,
            ).update()
        val fullWs = h.workspace()
        val (_, workspaceFull) = h.inbox(fullWs)
        h.jdbc
            .sql("INSERT INTO workspace_storage_account (workspace_id, base_bytes) VALUES (?, ?)")
            .params(fullWs.value, 10 * f)
            .update()
        val (_, serviceFull) = h.inbox(h.workspace())

        // Global used = 15f against a cap of 2f: the open inbox is refused on SERVICE_CAPACITY, the others on their own ceilings.
        val result = h.deliver(listOf(inboxFull, workspaceFull, serviceFull))
        result.accepted.size shouldBe 0
        result.storageRefusedRecipients shouldBe 3
        setOf("INBOX_LIMIT", "WORKSPACE_LIMIT", "SERVICE_CAPACITY").forEach { reason ->
            h.count("SELECT count(*) FROM inbox_storage WHERE last_refusal_reason = '$reason' AND refusal_count = 1") shouldBe 1
        }
        h.metrics.events shouldContain "admission:REFUSED_GLOBAL"
        h.fencedWrites.size shouldBe 0
        h.messageCount() shouldBe 0
    }

    @Test
    fun `a set activation guard answers as infrastructure before the slot and before T1, reserving nothing (§22)`() {
        val h = harness(StorageEnforcement.TENANT_LIMITS, GuardedIngestHarness.GENEROUS)
        val (_, address) = h.inbox(h.workspace())
        h.activation.set("session_allowlist: 1 session(s) outside the allowlist: 'PostgreSQL JDBC Driver'")

        val failure = assertThrows<StorageUnavailableException> { h.deliver(listOf(address)) }

        failure.reason shouldBe StorageUnavailableReason.ACTIVATION_VIOLATED
        h.reservationStates() shouldBe emptyMap()
        h.fencedWrites.size shouldBe 0
        h.refusalCount() shouldBe 0 // infrastructure, never a tenant refusal record
        h.metrics.events shouldNotContain "admission:ADMITTED"

        // Cleared by the next passing check: admission resumes with no operator action.
        h.activation.clear()
        h.deliver(listOf(address)).accepted.size shouldBe 1
    }
}
