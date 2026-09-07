package email.testinbox.observability

import email.testinbox.application.port.BlobOperation
import email.testinbox.application.port.BlobOutcome
import email.testinbox.application.port.BlobStoreMetrics
import email.testinbox.application.port.InboundMetrics
import email.testinbox.application.port.InboxMetrics
import email.testinbox.application.port.NotifierMetrics
import email.testinbox.application.port.SmtpMetrics
import email.testinbox.application.port.SmtpRejection
import email.testinbox.application.port.WaitMetrics
import email.testinbox.application.port.WaitOutcome
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.message.ParseStatus
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/*
 * Micrometer implementations of the observability ports
 * (`docs/architecture/observability.md`, TI-DEPLOY-002 §10–13).
 *
 * NAMING: every meter carries the `testinbox_` prefix, including the ones the
 * strategy document writes unprefixed (`inbox_created_total` and friends). The
 * document predates any implementation; the metrics that actually shipped with
 * ADR-027 are prefixed, and a metrics namespace that is half-prefixed is worse
 * than either convention consistently applied. The mapping is recorded in the
 * strategy document so the names there and the names on the wire agree.
 *
 * CARDINALITY: every tag value below comes from a Kotlin enum or a boolean, so
 * the total series count is a product of small constants and cannot grow with
 * traffic or with anything a caller controls. `MetricCardinalityTest` asserts
 * that against the whole registry rather than trusting this comment.
 */

/** Inbox lifecycle. */
class MicrometerInboxMetrics(
    private val registry: MeterRegistry,
) : InboxMetrics {
    override fun inboxCreated(mode: AddressMode) {
        // NOT `..._created_total`: the Prometheus exposition format reserves
        // the `_created` suffix (it is the OpenMetrics counter-birth series),
        // and Micrometer strips it — that name exports as `testinbox_inbox_total`,
        // which is not what any dashboard would be written against.
        registry.counter("testinbox_inbox_creations_total", "mode", mode.name).increment()
    }

    override fun inboxExpired(count: Int) {
        if (count > 0) registry.counter("testinbox_inbox_expired_total").increment(count.toDouble())
    }

    override fun inboxDeleted() {
        registry.counter("testinbox_inbox_deleted_total").increment()
    }
}

/** Inbound delivery. */
class MicrometerInboundMetrics(
    private val registry: MeterRegistry,
) : InboundMetrics {
    override fun messageReceived(parseStatus: ParseStatus) {
        registry.counter("testinbox_message_received_total", "parse_status", parseStatus.name).increment()
    }

    override fun parseCompleted(
        duration: Duration,
        parseStatus: ParseStatus,
    ) {
        registry
            .timer("testinbox_message_parse_duration_seconds", "parse_status", parseStatus.name)
            .record(duration)
    }

    override fun unknownRecipientDiscarded() {
        registry.counter("testinbox_smtp_unknown_recipient_discard_total").increment()
    }

    override fun duplicateProviderEventNoop() {
        registry.counter("testinbox_message_duplicate_event_noop_total").increment()
    }
}

/** Long-poll waits. */
class MicrometerWaitMetrics(
    private val registry: MeterRegistry,
) : WaitMetrics {
    private val active = AtomicInteger(0)
    private val activeSlots = AtomicLong(0)

    init {
        // Two gauges, two different questions, and conflating them is easy:
        // `requests_active` counts waits IN FLIGHT (including the fast path
        // that never parks), `slots_active` counts the ADR-027 concurrency
        // slots actually held. Either climbing without the other returning to
        // zero is a leak.
        Gauge
            .builder("testinbox_wait_requests_active", active) { it.get().toDouble() }
            .strongReference(true)
            .register(registry)
        Gauge
            .builder("testinbox_wait_slots_active", activeSlots) { it.get().toDouble() }
            .strongReference(true)
            .register(registry)
    }

    override fun waitStarted() {
        active.incrementAndGet()
    }

    override fun waitCompleted(
        outcome: WaitOutcome,
        duration: Duration,
    ) {
        active.decrementAndGet()
        registry
            .timer("testinbox_wait_request_duration_seconds", "outcome", outcome.name)
            .record(duration)
    }

    override fun slotRejected() {
        registry.counter("testinbox_wait_slot_rejected_total").increment()
    }

    override fun slotsChanged(delta: Int) {
        activeSlots.addAndGet(delta.toLong())
    }
}

/**
 * The `LISTEN` transport.
 *
 * `testinbox_wait_listen_degraded_polling` is 1 whenever notifications are NOT
 * being delivered and parked waiters are falling back to bounded re-query. It
 * is the only continuous signal that separates "healthy" from "healthy but
 * every wait is now up to a second slower" — a state where HTTP stays green,
 * messages still arrive, and readiness may not make the regression obvious
 * (ADR-020, ADR-030 capability 2).
 *
 * Deliberately a pushed gauge rather than one that polls notifier health: the
 * value must change at the instant the transport does, not at the next scrape.
 */
class MicrometerNotifierMetrics(
    private val registry: MeterRegistry,
) : NotifierMetrics {
    private val degradedFlag = AtomicLong(1)

    init {
        // Starts at 1: until a LISTEN connection is established, waiters really
        // are on the fallback path. Starting at 0 would report healthy for a
        // process that never connected at all.
        Gauge
            .builder("testinbox_wait_listen_degraded_polling", degradedFlag) { it.get().toDouble() }
            .strongReference(true)
            .register(registry)
        // Registered eagerly so the series exists at zero, before the first
        // reconnect. A counter that only appears once it has fired cannot be
        // alerted on with `increase()` over a window that contains its birth.
        registry.counter("testinbox_wait_listen_reconnect_total")
    }

    override fun listening() {
        degradedFlag.set(0)
    }

    override fun degraded() {
        degradedFlag.set(1)
    }

    override fun reconnected() {
        registry.counter("testinbox_wait_listen_reconnect_total").increment()
    }
}

/** Object storage. */
class MicrometerBlobStoreMetrics(
    private val registry: MeterRegistry,
) : BlobStoreMetrics {
    override fun operationCompleted(
        operation: BlobOperation,
        duration: Duration,
        outcome: BlobOutcome,
    ) {
        registry
            .timer(
                "testinbox_object_storage_operation_duration_seconds",
                Tags.of("operation", operation.name, "outcome", outcome.name.lowercase()),
            ).record(duration)
    }
}

/** The SMTP listener. */
class MicrometerSmtpMetrics(
    private val registry: MeterRegistry,
) : SmtpMetrics {
    override fun accepted() {
        registry.counter("testinbox_smtp_accept_total").increment()
    }

    override fun rejected(reason: SmtpRejection) {
        registry.counter("testinbox_smtp_reject_total", "reason", reason.name).increment()
    }
}

/**
 * What is running, as a metric (TI-DEPLOY-002 §13).
 *
 * Ops needs to answer "which build is this instance?" from the same place it
 * reads everything else. The classic `_info` shape — a constant 1 carrying the
 * facts as labels — is safe here precisely because the labels are fixed for a
 * process's whole lifetime: one series per deployed build, not one per request.
 *
 * The image digest is deliberately ABSENT. An image does not know its own
 * digest, and a value invented at runtime would be a plausible-looking lie in
 * the one metric whose entire job is identifying what is deployed. The digest
 * is deployment metadata and stays with Ops, which chose it; the commit SHA is
 * baked in at build time and is sufficient to identify the source.
 */
class BuildInfoMetric(
    registry: MeterRegistry,
    service: String,
    gitSha: String,
    version: String,
) {
    /**
     * Held in a field on purpose. Micrometer keeps only a WEAK reference to a
     * gauge's state object, so a boxed constant passed inline is collected at
     * the first GC and the metric starts reporting NaN — silently, and only in
     * a long-running process, which is every deployed one.
     */
    private val one = AtomicLong(1)

    init {
        // `testinbox_build`, not `testinbox_build_info`: `_info` is reserved by
        // the exposition format too, so the `_info` name exports differently
        // depending on whether the endpoint serves text or OpenMetrics. A name
        // that changes with the scrape format is worse than an unidiomatic one.
        Gauge
            .builder("testinbox_build", one) { it.get().toDouble() }
            .tags(Tags.of("service", service, "git_sha", gitSha, "version", version))
            .strongReference(true)
            .register(registry)
    }
}
