package email.testinbox.ingestion.guarded

import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The data-integrity review's interleaving (TI-STORAGE-006E PR D, contract §2.4 T2
 * row), against the REAL adapters: T1 reserves for an ACTIVE inbox, the retention
 * sweep commits it EXPIRED (and would delete its prefix) while the upload is in
 * flight, then T2 runs. Under `FOR KEY SHARE` without a state check, T2 committed
 * the copy into the expired inbox, and its object outlived the prefix delete as a
 * live object no row charges. Now T2 fences the event: `451`, no message, and the
 * reservation goes back to cleanup, whose release writes the debt row.
 */
class T2InboxFenceTest {
    private lateinit var h: GuardedIngestHarness

    @AfterEach
    fun close() = h.close()

    @Test
    fun `an inbox that stopped receiving between T1 and T2 fences the event - no copy commits into it`() {
        var expire: () -> Unit = {}
        h =
            GuardedIngestHarness(
                hook =
                    object : IngestSyncHook {
                        override fun afterUploads() = expire()
                    },
            )
        val (inbox, address) = h.inbox(h.workspace())
        expire = {
            h.jdbc
                .sql("UPDATE inbox SET state = 'EXPIRED' WHERE id = ?")
                .param(inbox.value)
                .update() shouldBe 1
        }

        assertThrows<StorageUnavailableException> { h.deliver(listOf(address)) }.reason shouldBe
            StorageUnavailableReason.COMMIT_FENCED
        h.messageCount() shouldBe 0
        (h.reservationStates()["RESERVED"] ?: 0L) shouldBe 0L
        h.reservationStates()["RELEASING"] shouldBe 1L
        h.metrics.events.contains("fenced") shouldBe true
    }

    @Test
    fun `an EXPIRING inbox still receives - only a state that stopped receiving fences`() {
        h = GuardedIngestHarness()
        val (inbox, address) = h.inbox(h.workspace())
        h.jdbc
            .sql("UPDATE inbox SET state = 'EXPIRING', grace_until = now() + interval '1 hour' WHERE id = ?")
            .param(inbox.value)
            .update()
        h.deliver(listOf(address)).accepted.size shouldBe 1
    }
}
