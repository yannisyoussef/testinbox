package email.testinbox.ingestion.guarded

import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * TI-STORAGE-003b P1-2: once an upload has begun, ANY throwable that keeps
 * its outcome from being known enters the ambiguity path before the slot can
 * be reused: the ambiguity is persisted, or else the slot is poisoned and the
 * generation is left unclean. The reservation stays charged. A fatal Error
 * then still propagates.
 */
class UploadErrorTest {
    private val harnesses = mutableListOf<GuardedIngestHarness>()

    @AfterEach
    fun close() = harnesses.reversed().forEach { it.close() }

    private fun track(h: GuardedIngestHarness) = h.also { harnesses += it }

    /** A custom fatal Error, thrown after the upload reached storage: its outcome is lost. */
    private class FatalAfterUpload : Error("fatal error after the upload began")

    private class FatalWhilePersisting : Error("fatal error while persisting the ambiguity")

    private fun nodeClean(h: GuardedIngestHarness): Boolean =
        h.count("SELECT count(*) FROM storage_node WHERE generation = '${h.node.generation}' AND clean_shutdown") == 1L

    @Test
    fun `an Error after upload start persists the ambiguity, keeps the reservation, holds the slot, and still propagates`() {
        val h = track(GuardedIngestHarness(afterUpload = { throw FatalAfterUpload() }))
        val (_, a) = h.inbox(h.workspace())

        shouldThrow<FatalAfterUpload> { h.deliver(listOf(a)) } // not swallowed

        h.inspection.objectExists(h.fencedWrites.single()) shouldBe true // it DID reach storage
        h.ambiguity.unresolvedFor(h.nodeId) shouldBe 1 // persisted before the slot came back
        h.slots.poisoned() shouldBe 0
        h.slots.occupied() shouldBe 1 // no silent slot release: the ambiguity holds it
        h.reservationStates() shouldBe mapOf("RESERVED" to 1L) // still charged
        h.messageCount() shouldBe 0
        h.breaker.isOpen shouldBe true

        h.lifecycle.stop(h.slots.poisoned())
        nodeClean(h) shouldBe true // safe: the ambiguity is in the database and survives the restart
        track(h.restart()).ambiguity.unresolvedFor(h.nodeId) shouldBe 1
    }

    @Test
    fun `an Error after upload start whose ambiguity cannot be persisted poisons the slot and leaves the generation unclean`() {
        val h =
            track(
                GuardedIngestHarness(
                    afterUpload = { throw FatalAfterUpload() },
                    beforeAmbiguityRecord = { throw FatalWhilePersisting() },
                ),
            )
        val (_, a) = h.inbox(h.workspace())

        shouldThrow<Error> { h.deliver(listOf(a)) }

        h.ambiguity.unresolvedFor(h.nodeId) shouldBe 0 // nothing could be persisted...
        h.slots.poisoned() shouldBe 1 // ...so the slot is held for the life of the process
        h.slots.occupied() shouldBe 1
        h.reservationStates() shouldBe mapOf("RESERVED" to 1L)
        h.count("SELECT count(*) FROM storage_reservation WHERE first_upload_at IS NOT NULL") shouldBe 1

        h.lifecycle.stop(h.slots.poisoned())
        nodeClean(h) shouldBe false // it must not mark itself clean

        // The restart turns the unclean generation into keyless ambiguity: the slot stays occupied.
        val restarted = track(h.restart())
        restarted.ambiguity.unresolvedFor(h.nodeId) shouldBe 1
        restarted.slots.occupied() shouldBe 1
    }

    @Test
    fun `16 Errors with failed persistence fill the node, and neither it nor its restart can start a 17th upload`() {
        val h =
            track(
                GuardedIngestHarness(
                    afterUpload = { throw FatalAfterUpload() },
                    beforeAmbiguityRecord = { throw FatalWhilePersisting() },
                    slotWait = Duration.ofMillis(200),
                ),
            )
        val ws = h.workspace()
        repeat(16) {
            h.breaker.close() // isolate the slot bound from the breaker
            shouldThrow<Error> { h.deliver(listOf(h.inbox(ws).second)) }
        }
        h.slots.poisoned() shouldBe 16
        h.breaker.close()
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(h.inbox(ws).second)) }.reason shouldBe
            StorageUnavailableReason.SLOT_WAIT
        val uploads = h.fencedWrites.size

        h.lifecycle.stop(h.slots.poisoned())
        val restarted = track(h.restart(slotWait = Duration.ofMillis(200)))
        restarted.ambiguity.unresolvedFor(h.nodeId) shouldBe 16 // capped at the slots: the H bound
        shouldThrow<StorageUnavailableException> { restarted.deliver(listOf(restarted.inbox(ws).second)) }.reason shouldBe
            StorageUnavailableReason.SLOT_WAIT
        restarted.fencedWrites.size shouldBe 0
        h.fencedWrites.size shouldBe uploads
    }

    @Test
    fun `a RuntimeException after upload start is unchanged - ambiguous, persisted, a 451`() {
        val h = track(GuardedIngestHarness(afterUpload = { error("a runtime failure after the upload began") }))
        val (_, a) = h.inbox(h.workspace())

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.UPLOAD_FAILED

        h.ambiguity.unresolvedFor(h.nodeId) shouldBe 1
        h.slots.poisoned() shouldBe 0
        h.reservationStates() shouldBe mapOf("RESERVED" to 1L)
        h.metrics.events.contains("failure:AMBIGUOUS") shouldBe true
    }
}
