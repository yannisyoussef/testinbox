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
     * ADR-035 §13c: no match, and a storage ceiling refused a copy for the
     * inbox after the caller's observation boundary. Only a call that opted
     * in with `afterStorageRefusalCount` can end here.
     */
    STORAGE_LIMIT_EXCEEDED,

    /**
     * The call threw. Recorded rather than dropped: a wait that fails is still
     * a wait that consumed a connection and a slot, and leaving it out would
     * make the duration histogram quietly describe only the happy paths.
     */
    ERROR,
}

/**
 * Everything about a long poll: its duration and outcome, how many are in
 * flight, and the ADR-027 concurrency-slot signals.
 *
 * The slot signals live here rather than on `LimitMetrics` because
 * `WaitForMessage` is their only caller, and splitting one use case's
 * observability across two ports bought nothing but a longer parameter list.
 * The exported metric names are unchanged.
 */
interface WaitMetrics {
    fun waitStarted() {}

    fun waitCompleted(
        outcome: WaitOutcome,
        duration: Duration,
    ) {}

    /** A wait refused because the workspace already holds every slot (ADR-027 §3). */
    fun slotRejected() {}

    /** +1 on claim, -1 on release. Must be paired, or a leak looks like load. */
    fun slotsChanged(delta: Int) {}

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

/**
 * How an object-storage call ended.
 *
 * `NOT_FOUND` is separate from both because it is neither: the call worked and
 * the object was absent. Folding it into SUCCESS hides the one case worth
 * paging on — a raw MIME object missing when `/raw` asks for it (ADR-005) —
 * and folding it into FAILURE would make the orphan sweep, which expects
 * misses, look like a permanent outage.
 */
enum class BlobOutcome {
    SUCCESS,
    NOT_FOUND,
    FAILURE,
}

/** Object storage (`object_storage_operation_duration_seconds`). */
interface BlobStoreMetrics {
    fun operationCompleted(
        operation: BlobOperation,
        duration: Duration,
        outcome: BlobOutcome,
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

/**
 * Why an authentication attempt ended the way it did (ADR-032). A closed enum,
 * so the label can never carry a public id, a workspace or anything else a
 * caller chooses.
 *
 * The failure reasons are distinguished from each other on purpose. They are
 * exported only on the private management port, which is never routed by the
 * edge (`docs/architecture/observability.md`), and the difference between
 * "nobody is using that credential any more" and "someone is presenting a
 * revoked one" is exactly what an operator needs after a leak.
 */
enum class AuthOutcome {
    SUCCESS,

    /** Authenticated by the configured bootstrap credential (ADR-032 §8). */
    BOOTSTRAP,

    /** Bootstrap presented, but a managed administrator now exists — the window has closed. */
    BOOTSTRAP_SUPERSEDED,

    /** Not a parseable credential of any known format. */
    MALFORMED,

    /** A TestInbox credential whose format version this build does not implement. */
    UNSUPPORTED_VERSION,

    /** Well-shaped but self-inconsistent — almost always a truncated paste. */
    CHECKSUM_MISMATCH,

    /** No row for that public id (or that bootstrap hash). */
    UNKNOWN_KEY,

    /** The public id resolved but the secret did not verify. */
    BAD_SECRET,

    REVOKED,
    EXPIRED,
}

/** Lifecycle operations on a credential. */
enum class ApiKeyOperation {
    CREATED,
    REVOKED,

    /** A revoke that matched an already-revoked key — a retry, not a new event. */
    REVOKE_NOOP,
}

interface ApiKeyMetrics {
    fun authCompleted(outcome: AuthOutcome) {}

    fun lifecycle(operation: ApiKeyOperation) {}

    /** A `last_used_at` write actually reached the database (ADR-032 §7). */
    fun lastUsedPersisted() {}

    companion object {
        val NOOP: ApiKeyMetrics = object : ApiKeyMetrics {}
    }
}

/**
 * How an idempotent mutation resolved (ADR-033 §10). A closed enum: the
 * idempotency key is caller-chosen and must never reach a label.
 */
enum class IdempotencyOutcome {
    /** The claim was taken and the mutation ran. */
    EXECUTED,

    /** An identical request had already committed; the stored result was returned. */
    REPLAYED,

    /** The key was bound to a different logical request. */
    CONFLICT,

    /** A concurrent identical claim did not resolve within the bounded wait. */
    IN_PROGRESS,

    /**
     * The mutation was refused, so its claim was rolled back and the key is
     * free again. Worth counting separately: a run of these means clients are
     * burning keys on requests that never commit.
     */
    ROLLED_BACK,
}

interface IdempotencyMetrics {
    fun completed(
        operation: email.testinbox.domain.idempotency.IdempotentOperation,
        outcome: IdempotencyOutcome,
    ) {}

    companion object {
        val NOOP: IdempotencyMetrics = object : IdempotencyMetrics {}
    }
}

/** How one reconciliation run ended (ADR-035 §10). Closed by construction. */
enum class ReconciliationOutcome {
    /** The ledger matched the source rows. */
    CLEAN,

    /** Drift was found and repaired. Always a defect worth investigating. */
    REPAIRED,

    /** The run threw. The ledger is unchanged, and the next run retries. */
    FAILED,
}

/** How one ADR-035 compaction tick ended. */
enum class CompactionOutcome {
    /** This node held the ledger lock and folded what it could (possibly nothing). */
    OK,

    /** Another node held the ledger lock. Normal with several API replicas. */
    CONTENDED,

    /** A pass threw. It rolled back with its deltas intact, and the next tick retries. */
    FAILED,
}

/**
 * ADR-035 §16 accounting signals (`storage_ledger_unfolded_rows`,
 * `storage_covered_bytes{kind=committed}`, `storage_accounting_drift_total`).
 * Two are additions §16 does not list:
 * - `storage_reconciliation_total`, so a reconciliation that fails, leaving
 *   the ledger unproven, is visible;
 * - `storage_ledger_compaction_total`, so a compactor failing on every node
 *   is visible, and not merely a frozen backlog gauge.
 *
 * Only the accounting foundation exists (TI-STORAGE-001), so only accounting
 * signals exist. Reserved bytes, admission refusals, the breaker, ambiguity
 * and the latch arrive with the slices that implement them.
 */
interface StorageAccountingMetrics {
    fun ledgerObserved(
        unfoldedRows: Long,
        committedBytes: Long,
    ) {}

    /**
     * `testinbox_storage_footprint_bytes{kind}` (filesystem-containment
     * contract §12, TI-STORAGE-006E): the bound of what a class of objects can
     * cost on the MinIO filesystem, observed in every mode. Closed label.
     */
    fun footprintObserved(
        kind: FootprintKind,
        bytes: Long,
    ) {}

    /**
     * `testinbox_storage_filesystem_observation_age_seconds`: how old the newest
     * Ops observation is. [seconds] is negative when none has ever been
     * recorded (the gauge reads −1), so "never" cannot read as "fresh".
     */
    fun filesystemObservationAge(seconds: Long) {}

    /**
     * `testinbox_storage_footprint_counts_trusted`: 1 while the object counts are
     * trusted (contract §4.5), 0 while they are not or cannot be read. Footprint
     * admission must not enforce at 0; until PR D gates T1 on it, this gauge is
     * what an operator alerts on.
     */
    fun footprintCountsTrusted(trusted: Boolean) {}

    fun driftRepaired(direction: DriftDirection) {}

    fun reconciliationCompleted(outcome: ReconciliationOutcome) {}

    fun compactionCompleted(outcome: CompactionOutcome) {}

    companion object {
        val NOOP: StorageAccountingMetrics = object : StorageAccountingMetrics {}
    }
}

/** ADR-035 §16 `storage_admission_total{outcome}`. */
enum class StorageAdmissionOutcome { ADMITTED, REFUSED_INBOX, REFUSED_WORKSPACE, REFUSED_GLOBAL }

/** The closed `kind` vocabulary of `testinbox_storage_footprint_bytes` (contract §12). */
enum class FootprintKind { COMMITTED, RESERVED, DELETION_DEBT }

/** ADR-035 §16 `storage_physical_failure_total{kind}`: infrastructure, never capacity. */
enum class PhysicalFailureKind { QUOTA, UNAVAILABLE, TIMEOUT, AMBIGUOUS, DEADLINE, LOCK_TIMEOUT, SLOT_WAIT, CLOCK_OFFSET }

/** ADR-035 §16 `storage_reservation_released_total{path}`. */
enum class ReleasePath { COMMITTED, ABSENT, DELETED, RECONCILED }

/**
 * The ADR-035 §16 signals of the guarded ingest protocol (TI-STORAGE-003).
 * Closed enums only: no workspace, inbox, message, key or address is ever a
 * label.
 *
 * One method per §16 signal, so this port reads as that table. Splitting it
 * by an arbitrary line only to satisfy a function count would hide the
 * correspondence.
 */
@Suppress("TooManyFunctions")
interface StorageProtocolMetrics {
    fun admission(outcome: StorageAdmissionOutcome) {}

    /** A ceiling was exceeded on a scope whose enforcement is OFF: observed, not refused. */
    fun unenforcedLimit(scope: email.testinbox.domain.storage.StorageScope) {}

    fun lockWait(duration: java.time.Duration) {}

    fun slotWait(duration: java.time.Duration) {}

    fun physicalFailure(kind: PhysicalFailureKind) {}

    fun commitFenced() {}

    fun released(path: ReleasePath) {}

    fun lateObject() {}

    fun breakerOpen(open: Boolean) {}

    fun latched(latched: Boolean) {}

    fun clockOffset(offset: java.time.Duration) {}

    fun ambiguousUploads(count: Int) {}

    fun reservations(byState: Map<String, Long>) {}

    fun physicalListedBytes(bytes: Long) {}

    fun incompleteUploads(count: Int) {}

    fun witnessFailed() {}

    // --- TI-STORAGE-006: activation and enablement observability (ADR-035 §14, §16) ---

    /** `testinbox_storage_activation_violation`: 1 while a non-OFF node's barrier is broken (ADR-035 §14 Phase 4). */
    fun activationViolation(violated: Boolean) {}

    /** `testinbox_storage_activation_gate_ready{gate}`: the runtime-checkable gates, observed in every mode. */
    fun activationGate(
        gate: email.testinbox.application.storage.activation.ActivationGate,
        ready: Boolean,
    ) {}

    /** `testinbox_storage_enforcement_mode{mode}`: 1 on the effective mode, 0 on the others. Never a tenant endpoint. */
    fun enforcementMode(mode: email.testinbox.domain.storage.StorageEnforcement) {}

    /** `testinbox_storage_orphan_sweep_completed_at_seconds`: when the last FULL orphan sweep completed (barrier (b)). */
    fun orphanSweepCompleted(at: java.time.Instant) {}

    /** `testinbox_storage_orphan_sweep_total{outcome}`. */
    fun orphanSweepFinished(ok: Boolean) {}

    /** `testinbox_storage_covered_bytes{kind=reserved}`: Σ bytes of every unreleased reservation, each cleanup pass. */
    fun reservedBytes(bytes: Long) {}

    companion object {
        val NOOP: StorageProtocolMetrics = object : StorageProtocolMetrics {}
    }
}
