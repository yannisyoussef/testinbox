package email.testinbox.application.usecase

import email.testinbox.application.port.InboundMetrics
import email.testinbox.application.port.InboxMetrics
import email.testinbox.application.port.WaitMetrics
import email.testinbox.application.port.WaitOutcome
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.message.ParseStatus
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recording doubles for the observability ports.
 *
 * These exist because the metric tests in `:observability` prove only that a
 * meter moves when its port method is called — they say nothing about whether
 * production code ever calls it. That gap is not hypothetical: until this
 * increment, `IngestionWiring` constructed `ReceiveInboundDelivery` without a
 * `LimitMetrics`, so the ADR-027 inbound counters never moved in the deployed
 * gateway, and the whole metrics suite stayed green.
 *
 * The `= NOOP` defaults on every port make that failure silent by
 * construction, which is exactly why the call sites need their own assertions.
 */

class RecordingInboxMetrics : InboxMetrics {
    val created = mutableListOf<AddressMode>()
    val expired = AtomicInteger(0)
    val deleted = AtomicInteger(0)

    override fun inboxCreated(mode: AddressMode) {
        created += mode
    }

    override fun inboxExpired(count: Int) {
        expired.addAndGet(count)
    }

    override fun inboxDeleted() {
        deleted.incrementAndGet()
    }
}

class RecordingInboundMetrics : InboundMetrics {
    val received = mutableListOf<ParseStatus>()
    val parsed = mutableListOf<ParseStatus>()
    val unknownRecipients = AtomicInteger(0)
    val duplicates = AtomicInteger(0)

    override fun messageReceived(parseStatus: ParseStatus) {
        received += parseStatus
    }

    override fun parseCompleted(
        duration: Duration,
        parseStatus: ParseStatus,
    ) {
        parsed += parseStatus
    }

    override fun unknownRecipientDiscarded() {
        unknownRecipients.incrementAndGet()
    }

    override fun duplicateProviderEventNoop() {
        duplicates.incrementAndGet()
    }
}

class RecordingWaitMetrics : WaitMetrics {
    val started = AtomicInteger(0)
    val completed = mutableListOf<WaitOutcome>()
    val slotRejections = AtomicInteger(0)
    val slotDelta = AtomicInteger(0)

    override fun waitStarted() {
        started.incrementAndGet()
    }

    override fun waitCompleted(
        outcome: WaitOutcome,
        duration: Duration,
    ) {
        completed += outcome
    }

    override fun slotRejected() {
        slotRejections.incrementAndGet()
    }

    override fun slotsChanged(delta: Int) {
        slotDelta.addAndGet(delta)
    }
}
