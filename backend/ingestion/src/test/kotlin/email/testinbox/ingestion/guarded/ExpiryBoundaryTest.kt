package email.testinbox.ingestion.guarded

import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * The expiry boundary under `OFF`, the committed mode (TI-STORAGE-006E owner
 * review b, §6), against the REAL adapters and the pinned MinIO.
 *
 * - **In flight.** An event whose inbox stops receiving between T1 and T2 is
 *   fenced. Cleanup releases its reservation and deletes its objects, the
 *   release writes the debt, and a retry finds no receivable inbox: the copy
 *   is discarded, with nothing reserved or stored.
 * - **Deleted inboxes** fence exactly like expired ones.
 * - **Duplicates.** A provider event already recorded is a duplicate after
 *   expiry too. It is never stored a second time.
 * - **Awaiting teardown.** An expired inbox's committed payload stays accounted
 *   until teardown deletes its rows. The delete moves it into deletion debt;
 *   it never vanishes from the ledger. Reads of such an inbox are gated
 *   (`MessageReadGatingTest`, `MessageApiTest`).
 */
class ExpiryBoundaryTest {
    private lateinit var h: GuardedIngestHarness

    @AfterEach
    fun close() = h.close()

    private fun setState(
        inbox: InboxId,
        state: String,
    ) {
        val column = if (state == "DELETED") "deleted_at" else "grace_until"
        h.jdbc
            .sql("UPDATE inbox SET state = ?, $column = now() WHERE id = ?")
            .params(state, inbox.value)
            .update() shouldBe 1
    }

    private fun debtBytes(): Long = h.count("SELECT coalesce(sum(bytes), 0) FROM storage_deletion_debt")

    private fun fencedThenRetried(state: String) {
        var stop: () -> Unit = {}
        h =
            GuardedIngestHarness(
                hook =
                    object : IngestSyncHook {
                        override fun afterUploads() = stop()
                    },
            )
        val (inbox, address) = h.inbox(h.workspace())
        stop = { setState(inbox, state) }
        val raw = GuardedIngestHarness.mime()

        assertThrows<StorageUnavailableException> { h.deliver(listOf(address), raw) }.reason shouldBe
            StorageUnavailableReason.COMMIT_FENCED
        h.messageCount() shouldBe 0
        h.committedBytes() shouldBe 0

        // Cleanup releases the fenced reservation: the objects go, the release charges the debt.
        h.backdate(Duration.ofMinutes(10))
        h.releaseCycle()
        (h.reservationStates()["RESERVED"] ?: 0L) shouldBe 0L
        h.listedBytes() shouldBe 0
        (debtBytes() > 0) shouldBe true

        // The sender's retry: the inbox no longer receives, so the copy is discarded, uniformly.
        stop = {}
        val retry = h.deliver(listOf(address), raw)
        retry.accepted.size shouldBe 0
        retry.discardedRecipients shouldBe 1
        h.messageCount() shouldBe 0
        h.reservedBytes() shouldBe 0
        h.listedBytes() shouldBe 0
    }

    @Test
    fun `an event in flight across expiry is fenced, released into debt, and its retry is discarded`() = fencedThenRetried("EXPIRED")

    @Test
    fun `an event in flight across deletion is fenced the same way`() = fencedThenRetried("DELETED")

    @Test
    fun `a provider event is a duplicate while the inbox receives, and discarded once it expired - never stored twice`() {
        h = GuardedIngestHarness()
        val (inbox, address) = h.inbox(h.workspace())
        h.deliver(listOf(address), providerMessageId = "evt-1").accepted.size shouldBe 1
        val bytes = h.committedBytes()

        // Before expiry: the dedup key answers at T2 (ADR-026, the unique index). The
        // copy's reservation is released to cleanup, still charged until it is gone.
        val duplicate = h.deliver(listOf(address), providerMessageId = "evt-1")
        duplicate.duplicateRecipients shouldBe 1
        duplicate.accepted.size shouldBe 0
        (h.reservationStates()["RESERVED"] ?: 0L) shouldBe 0L
        val releasing = h.reservedBytes()

        // After expiry the address no longer resolves, so the redelivery is discarded
        // before dedup is consulted. Either way the event is stored exactly once.
        setState(inbox, "EXPIRED")
        val late = h.deliver(listOf(address), providerMessageId = "evt-1")
        late.accepted.size shouldBe 0
        late.discardedRecipients shouldBe 1
        h.messageCount() shouldBe 1
        h.committedBytes() shouldBe bytes
        h.reservedBytes() shouldBe releasing // the discarded copy reserved nothing
    }

    @Test
    fun `an expired inbox's payload stays accounted until teardown deletes it, and the delete moves it into debt`() {
        h = GuardedIngestHarness()
        val (inbox, address) = h.inbox(h.workspace())
        val ids = h.deliver(listOf(address)).accepted
        val bytes = h.committedBytes()
        (bytes > 0) shouldBe true
        setState(inbox, "EXPIRED")

        // Awaiting paced teardown: still accounted, and a new delivery is discarded.
        h.committedBytes() shouldBe bytes
        h.deliver(listOf(address)).discardedRecipients shouldBe 1
        h.committedBytes() shouldBe bytes
        debtBytes() shouldBe 0

        // Teardown's batch delete: the bytes leave the live ledger and enter deletion debt.
        email.testinbox.persistence
            .JdbcInboxRepository(h.jdbc)
            .deleteMessages(inbox, ids.map { MessageId(it.value) }) shouldBe 1
        h.committedBytes() shouldBe 0
        debtBytes() shouldBe bytes
    }
}
