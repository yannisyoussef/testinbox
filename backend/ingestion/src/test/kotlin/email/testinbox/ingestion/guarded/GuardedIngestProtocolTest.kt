package email.testinbox.ingestion.guarded

import email.testinbox.application.port.AmbiguityKind
import email.testinbox.application.port.UploadOutcome
import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.storage.fenced.TcpFaultProxy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-035 guarded ingest, end to end against PostgreSQL and the pinned MinIO
 * (TI-STORAGE-003 §53 areas 5–12, 16, 20–22, and §60/§61).
 */
class GuardedIngestProtocolTest {
    private val harnesses = mutableListOf<GuardedIngestHarness>()

    private fun harness(
        enforcement: StorageEnforcement = StorageEnforcement.OFF,
        policy: StorageCapacityPolicy = GuardedIngestHarness.GENEROUS,
        hook: IngestSyncHook = IngestSyncHook.NONE,
        viaProxy: Boolean = false,
        maxSlots: Int = 16,
        perWorkspace: Int = 4,
        slotWait: Duration = Duration.ofSeconds(2),
    ) = GuardedIngestHarness(
        enforcement,
        policy,
        hook,
        viaProxy = viaProxy,
        maxSlots = maxSlots,
        perWorkspace = perWorkspace,
        slotWait = slotWait,
    ).also { harnesses += it }

    @AfterEach
    fun close() = harnesses.forEach { it.close() }

    /** Records the protocol's steps, and what the database held at each. */
    private class Evidence(
        private val h: () -> GuardedIngestHarness,
    ) : IngestSyncHook {
        val steps = Collections.synchronizedList(mutableListOf<String>())

        override fun afterSlot() {
            steps += "slot"
            steps += "reservations-at-slot=${h().count("SELECT count(*) FROM storage_reservation")}"
        }

        override fun afterAdmission() {
            steps += "T1"
            steps += "reserved-after-T1=${h().count("SELECT count(*) FROM storage_reservation WHERE state = 'RESERVED'")}"
        }

        override fun beforeUpload(key: String) {
            steps += "upload"
        }

        override fun afterUploads() {
            steps += "uploads-done"
        }

        override fun beforeCommit() {
            steps += "T2"
        }

        override fun inCommitAfterReservationLock() {
            steps += "fence-held"
        }
    }

    // --- §60 Phase-2 proof ----------------------------------------------------------------------------

    @Test
    fun `a normal message goes slot, T1, fenced uploads, T2 - and the reservation is consumed`() {
        lateinit var h: GuardedIngestHarness
        val evidence = Evidence { h }
        h = harness(hook = evidence)
        val ws = h.workspace()
        val (inbox, address) = h.inbox(ws)

        val result = h.deliver(listOf(address))

        result.accepted.size shouldBe 1
        evidence.steps shouldBe
            listOf("slot", "reservations-at-slot=0", "T1", "reserved-after-T1=1", "upload", "upload", "uploads-done", "T2", "fence-held")
        // Two exact keys (raw.eml + one attachment), both through the fenced path, and nothing else.
        h.fencedWrites.size shouldBe 2
        h.fencedWrites.forEach { key -> h.inspection.objectExists(key) shouldBe true }
        h.fencedWrites.first().endsWith("/raw.eml") shouldBe true
        h.messages
            .listVisible(inbox)
            .single()
            .id shouldBe result.accepted.single()
        h.reservationStates() shouldBe emptyMap() // consumed by T2, not left RESERVED
        h.unresolvedAmbiguity() shouldBe 0
        h.metrics.events shouldContain "admission:ADMITTED"
        // The ledger now holds exactly what was uploaded.
        h.committedBytes() shouldBe h.listedBytes()
    }

    @Test
    fun `with enforcement OFF, mail above every ceiling is stored and nothing is refused`() {
        // §61: accounting already above inbox, workspace and global limits.
        val tiny =
            StorageCapacityPolicy(
                workspaceLimitBytes = 100,
                inboxShare = InboxShare.of("0.5"),
                globalLimitBytes = 200,
                finalizeBudgetBytes = 10,
            )
        val h = harness(enforcement = StorageEnforcement.OFF, policy = tiny)
        val ws = h.workspace()
        val (_, address) = h.inbox(ws)
        repeat(2) { h.deliver(listOf(address)).accepted.size shouldBe 1 }

        val third = h.deliver(listOf(address))

        third.accepted.size shouldBe 1
        third.storageRefusedRecipients shouldBe 0
        h.refusalCount() shouldBe 0
        h.messageCount() shouldBe 3
        h.metrics.events shouldContain "unenforced:INBOX" // observed, never enforced
    }

    // --- internal enforcement modes (never live) -------------------------------------------------------

    @Test
    fun `under ALL a refused copy is not uploaded, and its refusal commits with the admitted ones in T2`() {
        val policy =
            StorageCapacityPolicy(
                workspaceLimitBytes = 1_000_000,
                inboxShare = InboxShare.of("1"),
                globalLimitBytes = 1L shl 40,
                finalizeBudgetBytes = 0,
            )
        val h = harness(enforcement = StorageEnforcement.ALL, policy = policy)
        val full = h.workspace()
        val (fullInbox, fullAddress) = h.inbox(full)
        h.jdbc
            .sql("INSERT INTO workspace_storage_account (workspace_id, base_bytes) VALUES (?, 1000000)")
            .param(full.value)
            .update()
        val open = h.workspace()
        val (_, openAddress) = h.inbox(open)

        val result = h.deliver(listOf(fullAddress, openAddress))

        result.accepted.size shouldBe 1
        result.storageRefusedRecipients shouldBe 1
        h.fencedWrites.none { it.startsWith("${full.value}/") } shouldBe true // no upload for the refused copy
        h.count("SELECT refusal_count FROM inbox_storage WHERE inbox_id = '${fullInbox.value}'") shouldBe 1
        h.count("SELECT count(*) FROM inbox_storage WHERE last_refusal_reason = 'WORKSPACE_LIMIT'") shouldBe 1
    }

    @Test
    fun `when every copy is refused, the refusal-only transaction records them and nothing is reserved`() {
        val policy =
            StorageCapacityPolicy(
                workspaceLimitBytes = 10,
                inboxShare = InboxShare.of("1"),
                globalLimitBytes = 1L shl 40,
                finalizeBudgetBytes = 0,
            )
        val h = harness(enforcement = StorageEnforcement.TENANT_LIMITS, policy = policy)
        val ws = h.workspace()
        val (_, a) = h.inbox(ws)
        val (_, b) = h.inbox(ws)

        val result = h.deliver(listOf(a, b))

        result.accepted.size shouldBe 0
        result.storageRefusedRecipients shouldBe 2
        h.refusalCount() shouldBe 2
        h.fencedWrites.size shouldBe 0
        h.reservationStates() shouldBe emptyMap()
    }

    @Test
    fun `a refusal record never survives an event that then fails physically`() {
        val policy =
            StorageCapacityPolicy(
                workspaceLimitBytes = 1_000_000,
                inboxShare = InboxShare.of("1"),
                globalLimitBytes = 1L shl 40,
                finalizeBudgetBytes = 0,
            )
        val h = harness(enforcement = StorageEnforcement.ALL, policy = policy, viaProxy = true)
        val full = h.workspace()
        val (_, fullAddress) = h.inbox(full)
        h.jdbc
            .sql("INSERT INTO workspace_storage_account (workspace_id, base_bytes) VALUES (?, 1000000)")
            .param(full.value)
            .update()
        val (_, openAddress) = h.inbox(h.workspace())
        h.proxy!!.mode = TcpFaultProxy.Mode.RESPOND
        h.proxy.canned = "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(fullAddress, openAddress)) }.reason shouldBe
            StorageUnavailableReason.UPLOAD_FAILED

        h.refusalCount() shouldBe 0 // the sender's retry re-runs admission from scratch
        h.messageCount() shouldBe 0
    }

    // --- physical failures (451, never capacity) --------------------------------------------------------

    @Test
    fun `an ambiguous upload aborts the whole event, keeps the reservation charged, persists ambiguity and opens the breaker`() {
        val h = harness(viaProxy = true)
        val (_, a) = h.inbox(h.workspace())
        val (_, b) = h.inbox(h.workspace())
        h.proxy!!.mode = TcpFaultProxy.Mode.RESPOND
        h.proxy.canned = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a, b)) }

        h.messageCount() shouldBe 0 // nothing becomes visible: the event is the unit (ADR-026)
        h.reservationStates() shouldBe mapOf("RESERVED" to 2L) // NOT released inline: ambiguous
        h.unresolvedAmbiguity() shouldBe 1 // the one ambiguous key; the upload stopped there
        h.breaker.isOpen shouldBe true
        h.metrics.events shouldContain "failure:AMBIGUOUS"
        // While open, the next event is refused before the slot and before T1: no reservation.
        h.proxy.mode = TcpFaultProxy.Mode.PASS
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.BREAKER_OPEN
        h.reservationStates() shouldBe mapOf("RESERVED" to 2L)
    }

    @Test
    fun `a definitive refusal releases the event's reservations at once and leaves no ambiguity`() {
        val h = harness(viaProxy = true)
        val (_, a) = h.inbox(h.workspace())
        h.proxy!!.mode = TcpFaultProxy.Mode.RESPOND
        h.proxy.canned =
            "HTTP/1.1 403 Forbidden\r\nContent-Length: 44\r\nConnection: close\r\n\r\n<Error><Code>AccessDenied</Code></Error>    "

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }

        h.reservationStates() shouldBe mapOf("RELEASING" to 1L) // releasable now: nothing can still appear
        h.unresolvedAmbiguity() shouldBe 0
        h.metrics.events shouldContain "failure:DEADLINE"
    }

    @Test
    fun `quota 400 opens the breaker, and the half-open trial is a real event that closes it`() {
        val h = harness(viaProxy = true)
        val (_, a) = h.inbox(h.workspace())
        h.proxy!!.mode = TcpFaultProxy.Mode.RESPOND
        val body = "<Error><Code>XMinioAdminBucketQuotaExceeded</Code></Error>"
        h.proxy.canned = "HTTP/1.1 400 Bad Request\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }
        h.metrics.events shouldContain "failure:QUOTA"
        h.breaker.isOpen shouldBe true
        h.unresolvedAmbiguity() shouldBe 0 // quota is definitive: nothing in H

        h.proxy.mode = TcpFaultProxy.Mode.PASS
        Thread.sleep(h.breaker.currentBackoff.toMillis() + 50) // the backoff itself is the thing under test
        h.deliver(listOf(a)).accepted.size shouldBe 1 // the real event WAS the trial
        h.breaker.isOpen shouldBe false
    }

    @Test
    fun `an unreachable storage opens the breaker, and a zero-byte probe closes it once storage answers`() {
        val probes =
            java.util.concurrent.atomic
                .AtomicInteger()
        val counting = { real: email.testinbox.application.port.StorageInspection ->
            object : email.testinbox.application.port.StorageInspection by real {
                override fun witness(probeKey: String): Boolean {
                    probes.incrementAndGet()
                    return real.witness(probeKey)
                }
            }
        }
        val h = GuardedIngestHarness(viaProxy = true, inspectionOverride = counting).also { harnesses += it }
        val (_, a) = h.inbox(h.workspace())
        h.proxy!!.close() // nothing listens: connections are refused, nothing is sent

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }
        h.metrics.events shouldContain "failure:UNAVAILABLE"
        h.reservationStates() shouldBe mapOf("RELEASING" to 1L) // not started = definitive
        h.breaker.isOpen shouldBe true

        probes.get() shouldBe 0
        Thread.sleep(h.breaker.currentBackoff.toMillis() + 50)
        // The probe goes to storage directly and succeeds; the event itself still
        // fails here because its uploads use the dead proxy, which reopens the breaker.
        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.UPLOAD_FAILED
        probes.get() shouldBe 1 // exactly one witness probe ran the trial, not the real event
        h.metrics.events.count { it == "failure:UNAVAILABLE" } shouldBe 2 // the event after the probe failed on its own
    }

    @Test
    fun `a set latch refuses every event before the slot and before T1`() {
        val h = harness()
        val (_, a) = h.inbox(h.workspace())
        h.ambiguity.latch("late object")

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.LATCHED

        h.fencedWrites.size shouldBe 0
        h.reservationStates() shouldBe emptyMap()
    }

    // --- ADR-026 duplicate (§40) --------------------------------------------------------------------------

    @Test
    fun `a reprocessed provider event adds no row, and its reservation's objects are cleaned, never the original's`() {
        val h = harness()
        val (inbox, a) = h.inbox(h.workspace())
        h.deliver(listOf(a), providerMessageId = "evt-1").accepted.size shouldBe 1
        val original = h.fencedWrites.toList()

        val replay = h.deliver(listOf(a), providerMessageId = "evt-1")

        replay.accepted.size shouldBe 0
        replay.duplicateRecipients shouldBe 1
        h.messages.listVisible(inbox).size shouldBe 1
        h.reservationStates() shouldBe mapOf("RELEASING" to 1L)
        val duplicateKeys = h.fencedWrites.drop(original.size)
        duplicateKeys.forEach { h.inspection.objectExists(it) shouldBe true }

        h.releaseCycle().released shouldBe 1 // witness, C_drain, then the release

        duplicateKeys.forEach { h.inspection.objectExists(it) shouldBe false }
        original.forEach { h.inspection.objectExists(it) shouldBe true }
        h.reservationStates() shouldBe emptyMap()
    }

    // --- write slots (§53 areas 5–7) ------------------------------------------------------------------------

    @Test
    fun `T1 happens only after a slot, a slot wait times out as infrastructure, and it reserves nothing`() {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var h: GuardedIngestHarness
        val hook =
            object : IngestSyncHook {
                override fun afterAdmission() {
                    if (holding.count == 1L) {
                        holding.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                }
            }
        h = harness(hook = hook, maxSlots = 1, perWorkspace = 1, slotWait = Duration.ofMillis(500))
        val (_, a) = h.inbox(h.workspace())
        val (_, b) = h.inbox(h.workspace())
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<Int> { h.deliver(listOf(a)).accepted.size }
            holding.await(30, TimeUnit.SECONDS)
            h.reservationStates() shouldBe mapOf("RESERVED" to 1L) // only the slot holder has reserved

            shouldThrow<StorageUnavailableException> { h.deliver(listOf(b)) }.reason shouldBe StorageUnavailableReason.SLOT_WAIT

            h.reservationStates() shouldBe mapOf("RESERVED" to 1L) // the waiter never reached T1
            h.metrics.events shouldContain "failure:SLOT_WAIT"
            release.countDown()
            first.get(30, TimeUnit.SECONDS) shouldBe 1
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `one workspace cannot hold more than its share of the node's slots, while another still gets one`() {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hook =
            object : IngestSyncHook {
                override fun afterSlot() {
                    if (holding.count == 1L) {
                        holding.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                }
            }
        val h = harness(hook = hook, maxSlots = 4, perWorkspace = 1, slotWait = Duration.ofMillis(500))
        val busy = h.workspace()
        val (_, busy1) = h.inbox(busy)
        val (_, busy2) = h.inbox(busy)
        val (_, other) = h.inbox(h.workspace())
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<Int> { h.deliver(listOf(busy1)).accepted.size }
            holding.await(30, TimeUnit.SECONDS)

            shouldThrow<StorageUnavailableException> { h.deliver(listOf(busy2)) }.reason shouldBe StorageUnavailableReason.SLOT_WAIT
            h.deliver(listOf(other)).accepted.size shouldBe 1 // another tenant is unaffected

            release.countDown()
            first.get(30, TimeUnit.SECONDS) shouldBe 1
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `an ambiguity that cannot be persisted keeps its slot occupied`() {
        // The database is gone at exactly the wrong moment: the slot must not be reusable.
        val h = harness(viaProxy = true, maxSlots = 1, perWorkspace = 1, slotWait = Duration.ofMillis(300))
        val (_, a) = h.inbox(h.workspace())
        h.proxy!!.mode = TcpFaultProxy.Mode.RESPOND
        h.proxy.canned = "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        h.jdbc.sql("REVOKE INSERT ON storage_ambiguity FROM PUBLIC").update()
        h.jdbc.sql("ALTER TABLE storage_ambiguity ADD CONSTRAINT refuse_all CHECK (false) NOT VALID").update()

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }
        h.breaker.close()
        h.proxy.mode = TcpFaultProxy.Mode.PASS

        shouldThrow<StorageUnavailableException> { h.deliver(listOf(a)) }.reason shouldBe StorageUnavailableReason.SLOT_WAIT
        h.slots.occupied() shouldBe 1
    }
}
