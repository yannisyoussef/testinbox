package email.testinbox.application.usecase

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.InboxStorageUsage
import email.testinbox.application.port.ObservedFootprint
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.storage.FootprintGate
import email.testinbox.application.storage.FootprintPolicy
import email.testinbox.application.storage.FootprintPrecheck
import email.testinbox.application.storage.FootprintUnavailability
import email.testinbox.application.storage.StorageFootprintUnavailableException
import email.testinbox.domain.AttachmentId
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.FootprintModel
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.storage.StorageUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * T1's global footprint rules (filesystem-containment contract §2.1; TI-STORAGE-006E
 * PR D) without a database: rules (G) and (C) on the exact post-admission aggregate,
 * envelope-order running totals, the owner's counterexample, debt reducing the
 * ceiling, observational modes, and every not-evaluable state answered as an
 * infrastructure failure under `ALL` and never as a capacity verdict.
 */
class FootprintT1AdmissionTest {
    private val t0: Instant = Instant.parse("2026-10-08T12:00:00Z")
    private val model = FootprintModel.REFERENCE
    private val ws = WorkspaceId(UUID.randomUUID())
    private val inboxes = List(4) { InboxId(UUID.randomUUID()) }

    /** Payload ceilings far away: only the footprint can refuse. */
    private val payload = StorageCapacityPolicy(1L shl 40, InboxShare.of("1"), 1L shl 41, 0)

    private fun limits(
        gF: Long = Long.MAX_VALUE / 4,
        capacity: Long = Long.MAX_VALUE / 4,
        h: Long = 0,
        m: Long = 0,
        r: Long = 0,
        p: Long = 0,
    ) = FootprintAdmission.Limits(gF, h, m, r, capacity, p)

    private fun policy(limits: FootprintAdmission.Limits) = FootprintPolicy(model, limits, "testinbox_monitor", capacityBytes = 0)

    @Suppress("LongParameterList") // one knob per observed figure, each with a default
    private fun observed(
        live: Pair<Long, Long> = 0L to 0L,
        debt: Pair<Long, Long> = 0L to 0L,
        trash: Long? = 0,
        trusted: Boolean = true,
        startedSeq: Long? = 10,
        watermark: Long = 5,
        block: Long? = 4096,
        capacity: Long? = 1L shl 50,
        writer: String? = "testinbox_monitor",
    ) = ObservedFootprint(live.first, live.second, debt.first, debt.second, trusted, watermark, startedSeq, trash, block, capacity, writer)

    private inner class Store(
        private val footprint: ObservedFootprint?,
        private val global: StorageUsage = StorageUsage.ZERO,
    ) : StorageAdmissionStore {
        override fun <R : Any> admit(
            scope: StorageAdmissionScope,
            decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
        ): R =
            decide(
                StorageUsageSnapshot(
                    t0 = t0,
                    global = global,
                    workspaces = scope.workspaceIds.associateWith { StorageUsage.ZERO },
                    inboxes = scope.inboxIds.associateWith { InboxStorageUsage(ws, StorageUsage.ZERO) },
                    footprint = footprint,
                ),
            ).outcome
    }

    private fun candidates(
        n: Int,
        attachments: Int = 0,
    ) = inboxes.take(n).map { inbox ->
        val message = MessageId(UUID.randomUUID())
        StorageAdmissionCandidate(
            message,
            ws,
            inbox,
            listOf(ObjectKeys.raw(ws, inbox, message)) +
                List(attachments) { ObjectKeys.attachment(ws, inbox, message, AttachmentId(UUID.randomUUID())) },
        )
    }

    private fun admit(
        observed: ObservedFootprint?,
        limits: FootprintAdmission.Limits,
        bytes: Long,
        copies: Int = 1,
        enforcement: StorageEnforcement = StorageEnforcement.ALL,
        attachments: Int = 0,
    ) = StorageAdmission(Store(observed), payload, enforcement, footprint = policy(limits))
        .admit(StorageAdmissionRequest(bytes, candidates(copies, attachments), "node-a", UUID.randomUUID()))

    private fun StorageAdmissionResult.shape() =
        decisions.map {
            when (it) {
                is StorageAdmissionDecision.Admitted -> it.unenforcedLimit?.let { reason -> "admitted ($reason)" } ?: "admitted"
                is StorageAdmissionDecision.Refused -> it.reason.name
            }
        }

    @Test
    fun `the owner's counterexample at T1 - payload and phi fit, the aggregate does not, the copy is SERVICE_CAPACITY`() {
        // 4 096 B in one object into 30 000 B of containment headroom: φ = 28 688 B, ΔF = 32 800 B.
        model.ofObject(4096) shouldBe 28_688
        admit(observed(), limits(capacity = 30_000), bytes = 4096).shape() shouldBe listOf("SERVICE_CAPACITY")
        admit(observed(), limits(capacity = 32_800), bytes = 4096).shape() shouldBe listOf("admitted")
    }

    @Test
    fun `rule G binds the live footprint plus H_F, byte for byte`() {
        val live = 1_000_000L to 3L
        val h = 50_000L
        val after = model.bound(1_000_000 + 4096, 4) + h
        admit(observed(live = live), limits(gF = after, h = h), bytes = 4096).shape() shouldBe listOf("admitted")
        admit(observed(live = live), limits(gF = after - 1, h = h), bytes = 4096).shape() shouldBe listOf("SERVICE_CAPACITY")
    }

    @Test
    fun `rule C binds the whole potential - deletion debt and observed trash reduce the ceiling`() {
        val live = 1_000_000L to 3L
        val debt = 300_000L to 2L
        val trash = 77_000L
        val h = 10_000L
        val m = 20_000L
        val r = 30_000L
        val p = 40_000L
        val needed = model.bound(1_000_000 + 300_000 + 4096, 6) + trash + h + m + r + p
        val fits = limits(capacity = needed, h = h, m = m, r = r, p = p)
        admit(observed(live = live, debt = debt, trash = trash), fits, bytes = 4096).shape() shouldBe listOf("admitted")
        admit(observed(live = live, debt = debt, trash = trash), fits.copy(capacityBytes = needed - 1), bytes = 4096).shape() shouldBe
            listOf("SERVICE_CAPACITY")
        // Without the debt the same copy would fit with room to spare: debt is what refuses.
        admit(observed(live = live, debt = 0L to 0L, trash = trash), fits.copy(capacityBytes = needed - 1), bytes = 4096).shape() shouldBe
            listOf("admitted")
    }

    @Test
    fun `copies of one event are charged against running totals, objects included`() {
        // Each copy is one raw.eml plus two attachments: three objects of a 10 000 B copy.
        val one = model.bound(10_000, 3)
        val two = model.bound(20_000, 6)
        admit(observed(), limits(capacity = two), bytes = 10_000, copies = 3, attachments = 2).shape() shouldBe
            listOf("admitted", "admitted", "SERVICE_CAPACITY")
        (two < 2 * one + 1) shouldBe true // one rounding for the aggregate, never two
    }

    @Test
    fun `under TENANT_LIMITS and OFF the footprint is observational - admitted, and the ceiling named`() {
        listOf(StorageEnforcement.TENANT_LIMITS, StorageEnforcement.OFF).forEach { mode ->
            admit(observed(), limits(capacity = 1), bytes = 4096, enforcement = mode).shape() shouldBe
                listOf("admitted (SERVICE_CAPACITY)")
        }
    }

    @Test
    fun `under ALL every not-evaluable state is an infrastructure failure, never a capacity verdict`() {
        val cases =
            mapOf(
                FootprintUnavailability.UNREADABLE to null,
                FootprintUnavailability.UNTRUSTED to observed(trusted = false),
                FootprintUnavailability.UNOBSERVED to observed(trash = null, startedSeq = null),
                FootprintUnavailability.OBSERVATION_BELOW_WATERMARK to observed(startedSeq = 4, watermark = 5),
                FootprintUnavailability.OBSERVATION_BEFORE_DISTRUST to observed(startedSeq = 10).copy(distrustedSeq = 10),
                FootprintUnavailability.OBSERVATION_BLOCK_SIZE to observed(block = 1024),
                FootprintUnavailability.OBSERVATION_WRITER to observed(writer = "testinbox_app"),
                FootprintUnavailability.INDETERMINATE to observed(live = -1L to 0L),
            )
        cases.forEach { (reason, footprint) ->
            shouldThrow<StorageFootprintUnavailableException> {
                admit(footprint, limits(), bytes = 10)
            }.reason shouldBe reason
            // Observational modes never fail on it.
            admit(footprint, limits(), bytes = 10, enforcement = StorageEnforcement.TENANT_LIMITS).shape() shouldBe listOf("admitted")
        }
    }

    @Test
    fun `an observation of a filesystem smaller than the declared C_fs is invalid`() {
        val declared = FootprintPolicy(model, limits(), "testinbox_monitor", capacityBytes = 48L shl 30)
        declared.unavailability(observed(capacity = (48L shl 30) - 1)) shouldBe FootprintUnavailability.OBSERVATION_CAPACITY
        declared.unavailability(observed(capacity = 48L shl 30)) shouldBe null
        declared.unavailability(observed(capacity = null)) shouldBe FootprintUnavailability.OBSERVATION_CAPACITY
    }

    @Test
    fun `an overflowing aggregate refuses the whole event as INDETERMINATE under ALL`() {
        shouldThrow<StorageFootprintUnavailableException> {
            admit(observed(live = Long.MAX_VALUE - 10 to 1L), limits(), bytes = 4096, copies = 3)
        }.reason shouldBe FootprintUnavailability.INDETERMINATE
    }

    @Test
    fun `the pre-resolution check runs only under ALL, and a gate that fails reads as UNREADABLE`() {
        val fp = policy(limits())
        FootprintPrecheck(fp, StorageEnforcement.ALL) { observed(trusted = false) }.unavailable() shouldBe
            FootprintUnavailability.UNTRUSTED
        FootprintPrecheck(fp, StorageEnforcement.ALL) { observed() }.unavailable() shouldBe null
        FootprintPrecheck(fp, StorageEnforcement.ALL, FootprintGate { error("database gone") }).unavailable() shouldBe
            FootprintUnavailability.UNREADABLE
        FootprintPrecheck(fp, StorageEnforcement.TENANT_LIMITS) { observed(trusted = false) }.unavailable() shouldBe null
        FootprintPrecheck(null, StorageEnforcement.ALL) { observed(trusted = false) }.unavailable() shouldBe null
    }

    @Test
    fun `a copy refused by a narrower ceiling adds nothing to the footprint running total`() {
        // Inbox 0 is full by payload; inboxes 1 and 2 then share the footprint headroom of exactly two copies.
        val tightInbox = StorageCapacityPolicy(1L shl 40, InboxShare.of("1"), 1L shl 41, 0)
        val store =
            object : StorageAdmissionStore {
                override fun <R : Any> admit(
                    scope: StorageAdmissionScope,
                    decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
                ): R =
                    decide(
                        StorageUsageSnapshot(
                            t0 = t0,
                            global = StorageUsage.ZERO,
                            workspaces = scope.workspaceIds.associateWith { StorageUsage.ZERO },
                            inboxes =
                                scope.inboxIds.associateWith {
                                    InboxStorageUsage(ws, if (it == inboxes[0]) StorageUsage(1L shl 40, 0) else StorageUsage.ZERO)
                                },
                            footprint = observed(),
                        ),
                    ).outcome
            }
        val result =
            StorageAdmission(store, tightInbox, StorageEnforcement.ALL, footprint = policy(limits(capacity = model.bound(8192, 2))))
                .admit(StorageAdmissionRequest(4096, candidates(3), "node-a", UUID.randomUUID()))
        result.shape() shouldBe listOf(StorageRefusalReason.INBOX_LIMIT.name, "admitted", "admitted")
    }
}
