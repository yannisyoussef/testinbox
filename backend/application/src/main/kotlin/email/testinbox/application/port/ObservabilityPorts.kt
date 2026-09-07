package email.testinbox.application.port

import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.message.ParseStatus
import java.time.Duration

/*
 * Metric ports for the signals `docs/architecture/observability.md` specifies
 * (TI-DEPLOY-002 §10).
 *
 * Ports rather than a Micrometer dependency, for the same reason `LimitMetrics`
 * is one (ADR-024, ADR-027 §14): `application` stays framework-free, and — the
 * part that actually matters operationally — every label vocabulary is fixed
 * *here*, in code the dependency rule keeps caller-controlled values out of.
 *
 * The label rule is absolute and is enforced by a test, not by convention: a
 * workspace id, API key, inbox id, message id, address or correlation id is
 * never a label. Their cardinality is chosen by the caller, so labelling by
 * them lets a caller decide how much memory the metrics backend spends. Every
 * enum below is closed for exactly that reason; per-tenant attribution belongs
 * in the structured logs, which are access-controlled and carry a correlation
 * id already.
 *
 * Every method has a no-op default so a use case can be constructed without
 * metrics in a unit test, and so adding a signal never breaks a caller.
 */

/** Inbox lifecycle (`inbox_created_total`, `inbox_expired_total`, `inbox_deleted_total`). */
interface InboxMetrics {
    fun inboxCreated(mode: AddressMode) {}

    fun inboxExpired(count: Int) {}

    fun inboxDeleted() {}

    companion object {
        val NOOP: InboxMetrics = object : InboxMetrics {}
    }
}

/**
 * Inbound delivery (`message_received_total`, `message_parse_duration_seconds`,
 * `smtp_unknown_recipient_discard_total`, `message_duplicate_event_noop_total`).
 */
interface InboundMetrics {
    /** One accepted, persisted message. Tagged by parse outcome, never by inbox. */
    fun messageReceived(parseStatus: ParseStatus) {}

    /** Time to parse one inbound event's MIME, whatever the outcome. */
    fun parseCompleted(
        duration: Duration,
        parseStatus: ParseStatus,
    ) {}

    /**
     * A recipient that resolved to no receivable inbox. ADR-025 keeps the SMTP
     * reply uniform, so nothing else makes this visible.
     */
    fun unknownRecipientDiscarded() {}

    /** A reprocessed provider event that appended nothing (ADR-019/026). */
    fun duplicateProviderEventNoop() {}

    companion object {
        val NOOP: InboundMetrics = object : InboundMetrics {}
    }
}

/** Terminal outcome of one `waitForMessage` call. Closed by construction. */
enum class WaitOutcome {
    MATCHED,
    TIMEOUT,
    INBOX_GONE,
    INBOX_NOT_FOUND,
    WAIT_LIMIT_EXCEEDED,
    INVALID_REQUEST,

    /**
     * The call threw. Recorded rather than dropped: a wait that fails is still
     * a wait that consumed a connection and a slot, and leaving it out would
     * make the duration histogram quietly describe only the happy paths.
     */
    ERROR,
}

/** Long-poll waits (`wait_request_duration_seconds`, `wait_requests_active`). */
interface WaitMetrics {
    fun waitStarted() {}

    fun waitCompleted(
        outcome: WaitOutcome,
        duration: Duration,
    ) {}

    companion object {
        val NOOP: WaitMetrics = object : WaitMetrics {}
    }
}

/**
 * The `LISTEN` transport (`wait_listen_reconnect_total`,
 * `wait_listen_degraded_polling`).
 *
 * This is the most operationally important port here. When notifications stop
 * arriving, TestInbox keeps working — it falls back to a bounded re-query, so
 * HTTP stays green, readiness may stay green, messages still appear, and only
 * *wait latency* degrades (ADR-020). Nothing else in the system distinguishes
 * "working" from "working slowly because the notification path is broken",
 * which is precisely the state a transaction-mode pooler in front of
 * PostgreSQL produces (ADR-030 capability 2).
 */
interface NotifierMetrics {
    /** A `LISTEN` connection is established and delivering. */
    fun listening() {}

    /** The connection is gone; parked waiters are on the degraded re-query path. */
    fun degraded() {}

    /** One reconnect attempt after a lost connection. */
    fun reconnected() {}

    companion object {
        val NOOP: NotifierMetrics = object : NotifierMetrics {}
    }
}

/** Object-storage operations. A closed set — not the caller's key. */
enum class BlobOperation {
    PUT,
    GET,
    DELETE,
    DELETE_PREFIX,
    LIST,
}

/** Object storage (`object_storage_operation_duration_seconds`). */
interface BlobStoreMetrics {
    fun operationCompleted(
        operation: BlobOperation,
        duration: Duration,
        success: Boolean,
    ) {}

    companion object {
        val NOOP: BlobStoreMetrics = object : BlobStoreMetrics {}
    }
}

/**
 * Why an SMTP transaction was refused. Protocol-level reasons only: recipient
 * *existence* is never one of them (ADR-025 keeps that reply uniform), so this
 * enum cannot become an enumeration oracle even if it were exposed.
 */
enum class SmtpRejection {
    INVALID_RECIPIENT,
    MESSAGE_TOO_LARGE,
    PROCESSING_FAILED,
    SCHEMA_UNAVAILABLE,
}

/** The SMTP listener (`smtp_accept_total`, `smtp_reject_total`). */
interface SmtpMetrics {
    /** One `DATA` transaction accepted with a uniform 250 (ADR-025). */
    fun accepted() {}

    fun rejected(reason: SmtpRejection) {}

    companion object {
        val NOOP: SmtpMetrics = object : SmtpMetrics {}
    }
}
