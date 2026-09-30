package email.testinbox.ingestion.guarded

import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import email.testinbox.storage.fenced.TcpFaultProxy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * ADR-035 §17 tests 17, 18, 21 and 25, and TI-STORAGE-003 §42 A–E / §24: a
 * process that dies anywhere between T1 and T2 never releases capacity early,
 * never exposes objects as messages, and never loses the H bound across a
 * restart. Cleanup then reaches a safe state with no unaccounted payload.
 *
 * A "crash" is a hook throwing at the named point: nothing after it runs, just
 * as when the process dies, and the next generation of the same node starts
 * without the old one having shut down cleanly.
 */
class GuardedIngestCrashTest {
    private val harnesses = mutableListOf<GuardedIngestHarness>()

    @AfterEach
    fun close() = harnesses.reversed().forEach { it.close() }

    private class SimulatedCrash : Error("simulated process death")

    private fun crashAt(point: String) =
        object : IngestSyncHook {
            var uploads = 0

            override fun afterAdmission() {
                if (point == "afterAdmission") throw SimulatedCrash()
            }

            override fun beforeUpload(key: String) {
                if (point == "secondUpload" && ++uploads == 2) throw SimulatedCrash()
            }

            override fun beforeCommit() {
                if (point == "beforeCommit") throw SimulatedCrash()
            }
        }

    private fun h(
        hook: IngestSyncHook = IngestSyncHook.NONE,
        viaProxy: Boolean = false,
        maxSlots: Int = 16,
        tPut: Duration = Duration.ofSeconds(3),
    ) = GuardedIngestHarness(hook = hook, viaProxy = viaProxy, maxSlots = maxSlots, tPut = tPut).also { harnesses += it }

    /** Back-dates past the write deadline and `S`, then runs cleanup: witness first, release second. */
    private fun cleanUpEverything(harness: GuardedIngestHarness) {
        harness.backdate(Duration.ofMinutes(30))
        val cleanup = harness.cleanup()
        cleanup.run()
        cleanup.run()
    }

    private fun restartOf(harness: GuardedIngestHarness) = harness.restart().also { harnesses += it }

    @Test
    fun `A - a crash after T1, before any upload, holds the charge until cleanup proves nothing exists`() {
        val harness = h(hook = crashAt("afterAdmission"))
        val (_, a) = harness.inbox(harness.workspace())

        shouldThrow<SimulatedCrash> { harness.deliver(listOf(a)) }

        harness.reservationStates() shouldBe mapOf("RESERVED" to 1L)
        harness.count("SELECT count(*) FROM storage_reservation WHERE first_upload_at IS NULL") shouldBe 1
        harness.listedBytes() shouldBe 0
        val restarted = restartOf(harness)
        restarted.unresolvedAmbiguity() shouldBe 0 // nothing had started uploading

        restarted.cleanup().run().released shouldBe 0 // before its deadline: still charged
        restarted.reservationStates() shouldBe mapOf("RESERVED" to 1L)
        cleanUpEverything(restarted)
        restarted.reservationStates() shouldBe emptyMap()
        restarted.metrics.events.contains("released:ABSENT") shouldBe true
    }

    @Test
    fun `B - a crash after one upload leaves its object charged, and the restart records keyless ambiguity`() {
        val harness = h(hook = crashAt("secondUpload"))
        val (_, a) = harness.inbox(harness.workspace())

        shouldThrow<SimulatedCrash> { harness.deliver(listOf(a)) }

        harness.messageCount() shouldBe 0 // never exposed
        harness.inspection.objectExists(harness.fencedWrites.first()) shouldBe true // the raw object is there
        val keys = harness.reservationKeys()
        keys.size shouldBe 2
        harness.inspection.objectExists(keys.single { it != harness.fencedWrites.first() }) shouldBe false // never started
        (harness.reservedBytes() >= harness.listedBytes()) shouldBe true // covered: listed ≤ committed + reserved
        val restarted = restartOf(harness)
        restarted.unresolvedAmbiguity() shouldBe 1 // the dead generation's started upload holds a slot

        cleanUpEverything(restarted)

        restarted.reservationStates() shouldBe emptyMap()
        restarted.listedBytes() shouldBe 0 // no unaccounted payload remains
        restarted.metrics.events.contains("released:DELETED") shouldBe true
    }

    @Test
    fun `C - a crash after every upload, before T2, exposes nothing and cleans up every object`() {
        val harness = h(hook = crashAt("beforeCommit"))
        val ws = harness.workspace()
        val (_, a) = harness.inbox(ws)
        val (_, b) = harness.inbox(ws)

        shouldThrow<SimulatedCrash> { harness.deliver(listOf(a, b)) }

        harness.messageCount() shouldBe 0
        harness.fencedWrites.size shouldBe 4 // two copies, raw + attachment each
        harness.reservedBytes() shouldBe harness.listedBytes() // exactly covered by the reservations
        val restarted = restartOf(harness)
        restarted.unresolvedAmbiguity() shouldBe 2

        cleanUpEverything(restarted)

        restarted.listedBytes() shouldBe 0
        restarted.reservationStates() shouldBe emptyMap()
    }

    @Test
    fun `D - an ambiguous response after the full body is charged until its keys are proven absent, then verified`() {
        val harness = h(viaProxy = true)
        val (_, a) = harness.inbox(harness.workspace())
        harness.proxy!!.mode = TcpFaultProxy.Mode.SWALLOW_RESPONSE

        shouldThrow<StorageUnavailableException> { harness.deliver(listOf(a)) }

        harness.inspection.objectExists(harness.fencedWrites.single()) shouldBe true // E5: it landed anyway
        harness.reservationStates() shouldBe mapOf("RESERVED" to 1L) // never released inline
        harness.unresolvedAmbiguity() shouldBe 1
        harness.cleanup().run().released shouldBe 0 // before the deadline: nothing is freed by age

        cleanUpEverything(harness)
        harness.listedBytes() shouldBe 0
        harness.reservationStates() shouldBe emptyMap()
        // T_verify later, the ambiguity resolves: the key is proven absent, nothing latches.
        harness.backdate(Duration.ofMinutes(61))
        harness.verification().run().resolved shouldBe 1
        harness.unresolvedAmbiguity() shouldBe 0
        harness.latched() shouldBe null
    }

    @Test
    fun `E and the H bound - 16 ambiguous uploads fill every slot, and a restart cannot start 16 more`() {
        val harness = h(viaProxy = true, tPut = Duration.ofSeconds(1))
        val ws = List(4) { harness.workspace() }
        harness.proxy!!.mode = TcpFaultProxy.Mode.SWALLOW_RESPONSE
        repeat(16) { i ->
            val (_, address) = harness.inbox(ws[i % 4])
            shouldThrow<StorageUnavailableException> { harness.deliver(listOf(address)) }
            harness.breaker.close() // isolate the slot bound from the breaker
        }
        harness.unresolvedAmbiguity() shouldBe 16

        // The process dies with every slot occupied by persisted ambiguity.
        val restarted = restartOf(harness)
        val (_, fresh) = restarted.inbox(restarted.workspace())

        shouldThrow<StorageUnavailableException> { restarted.deliver(listOf(fresh)) }.reason shouldBe StorageUnavailableReason.SLOT_WAIT
        restarted.slots.occupied() shouldBe 16

        // Only verification frees them: reservations released after their proof, then T_verify.
        cleanUpEverything(restarted)
        restarted.backdate(Duration.ofMinutes(61))
        restarted.verification().run().resolved shouldBe 16
        restarted.deliver(listOf(fresh)).accepted.size shouldBe 1
    }
}
