package email.testinbox.application.port

import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageRefusalReason
import java.time.Duration
import java.time.Instant

/**
 * The reservation fence of an event in flight (ADR-035 §5, §6): the upload
 * phase, and T2.
 *
 * The T2 methods ([lockForCommit] through [releaseDuplicates]) must run inside
 * one `TransactionRunner.required` block, in the order listed. That order is
 * the ADR's lock order: reservations, then inboxes, then `inbox_storage`, then
 * `message`.
 */
interface StorageCommitFence {
    // --- T2 -------------------------------------------------------------------------------

    /** `FOR UPDATE`, ascending `message_id`. The rows that exist, in any state. */
    fun lockForCommit(ids: Collection<MessageId>): List<LockedReservation>

    /** `FOR KEY SHARE`, ascending id: no concurrent delete can remove an inbox mid-commit. */
    fun lockInboxes(ids: Collection<InboxId>)

    /**
     * ADR-035 §6a: one upsert per refused inbox, ascending, `refusal_count + 1`,
     * plus one `pg_notify`. An inbox that vanished is skipped, not an error.
     */
    fun recordRefusals(refusals: Map<InboxId, StorageRefusalReason>)

    /** I4: the committed copies' reservations are consumed (deleted) by the commit itself. */
    fun consume(ids: Collection<MessageId>)

    /** ADR-026 duplicates: `RELEASING`, releasable now; cleanup deletes their objects. */
    fun releaseDuplicates(ids: Collection<MessageId>)

    // --- the upload phase --------------------------------------------------------------------

    /**
     * Records `first_upload_at`, and COMMITS it, before the first byte of a
     * reservation's upload. Crash recovery relies on it: a reservation with
     * `first_upload_at` may have objects even if its node died (§9).
     */
    fun markUploadStarted(id: MessageId)

    /**
     * The event was abandoned and every upload it started ended DEFINITIVELY:
     * `RESERVED` → `RELEASING`, releasable now (§4). Guarded on `RESERVED`.
     */
    fun releaseAbandoned(ids: Collection<MessageId>)
}

/** `storage_reservation` cleanup and release (ADR-035 §7), and the orphan sweep's check. */
interface StorageReservations {
    /**
     * `RESERVED` past its write deadline → `RELEASING`, releasable at
     * `write_deadline_at + S`. One guarded statement: a concurrent T2 either
     * already holds the row (and this waits, then finds it gone) or finds it
     * `RELEASING` and is fenced.
     */
    fun expireOverdue(settle: Duration): Int

    /**
     * In one transaction, locks up to [limit] `RELEASING` rows due by
     * [horizon] with `FOR UPDATE SKIP LOCKED`, so two cleaners never work the
     * same row, and runs [work] while holding them.
     */
    fun <T : Any> withReleasable(
        horizon: Instant,
        limit: Int,
        work: (List<ReleasableReservation>) -> T,
    ): T

    /** Released: the row is deleted and its bytes stop counting. Inside [withReleasable]. */
    fun release(id: MessageId)

    /** A late object was found: the row stays charged until [until]. Inside [withReleasable]. */
    fun postpone(
        id: MessageId,
        until: Instant,
    )

    /** After a clock-offset suspension, every pending release moves later by [by] (§5). */
    fun postponeAll(by: Duration): Int

    /** Case [D]: a committed message already has this id. */
    fun messageExists(id: MessageId): Boolean

    /** Whether any reservation (in any state) still holds this id. */
    fun reservationExists(id: MessageId): Boolean

    /**
     * ADR-035 §7 `OrphanBlobSweep`: in ONE statement, whether [key] (of
     * message [id]) has no message row, no reservation and no unresolved
     * ambiguity. Three separate checks could let a T2 commit between them and
     * delete committed content.
     */
    fun isOrphan(
        id: MessageId,
        key: String,
    ): Boolean

    fun countsByState(): Map<String, Long>
}

data class LockedReservation(
    val messageId: MessageId,
    val bytes: Long,
    val state: String,
)

data class ReleasableReservation(
    val messageId: MessageId,
    val workspaceId: WorkspaceId,
    val objectKeys: List<String>,
    val releaseNotBefore: Instant,
)

/**
 * Persisted ambiguity, node generations and the admission latch (ADR-035 §9).
 * This is what keeps the finalize budget H true across breaker cycles and
 * process restarts.
 */
interface StorageAmbiguity {
    /**
     * Persists one ambiguous upload. It must be committed BEFORE the write
     * slot it occupied can be handed out again.
     */
    fun record(
        nodeId: String,
        objectKey: String?,
        bytes: Long,
        verifyAfter: Duration,
    )

    /** Unresolved ambiguity rows of [nodeId]: each one keeps one of its write slots occupied. */
    fun unresolvedFor(nodeId: String): Int

    fun unresolvedTotal(): Int

    /** Rows whose `verify_at` has passed, oldest first. */
    fun due(limit: Int): List<AmbiguityRecord>

    fun resolve(id: Long)

    fun oldestUnresolvedAge(): Duration?

    // --- node generations --------------------------------------------------------------------

    fun registerGeneration(
        nodeId: String,
        generation: java.util.UUID,
        capability: String,
    )

    fun heartbeat(
        nodeId: String,
        generation: java.util.UUID,
    )

    fun markCleanShutdown(
        nodeId: String,
        generation: java.util.UUID,
    )

    /**
     * For every generation of [nodeId] other than [current] that did not shut
     * down cleanly (or, when [nodeId] is null, of any node whose heartbeat is
     * older than [staleAfter]), records keyless ambiguity for the uploads it
     * might have had in flight. That is at most [slots] rows per generation,
     * covering its started (`first_upload_at`) but unconsumed reservations.
     * Then forgets the generation. Idempotent, and returns the rows recorded.
     */
    fun recoverDeadGenerations(
        nodeId: String?,
        current: java.util.UUID?,
        staleAfter: Duration,
        slots: Int,
        verifyAfter: Duration,
    ): Int
}

/** The database admission latch (ADR-035 §9): the shared fail-closed kill switch. */
interface StorageLatch {
    fun latched(): String?

    /** Sets the latch if it is not set. Only an operator clears it, by hand (runbook). */
    fun latch(reason: String)
}

data class AmbiguityRecord(
    val id: Long,
    val nodeId: String,
    val objectKey: String?,
    val ambiguousAt: Instant,
)

/**
 * Read and delete operations on object storage for cleanup, verification and
 * the orphan sweep, plus the one infrastructure write ADR-035 allows outside
 * the fence: the storage witness probe under `_probe/`, which is never payload.
 */
interface StorageInspection {
    /** Strict: `ListObjectsV2(prefix = key)` contains exactly [key]. Any other failure throws. */
    fun objectExists(key: String): Boolean

    /** `ListMultipartUploads(prefix = key)` has an upload for exactly [key] (probe M3, never a parent prefix: M2). */
    fun incompleteUploadExists(key: String): Boolean

    fun deleteObject(key: String)

    /** Every incomplete multipart upload in the bucket (probe M1). */
    fun incompleteUploads(): List<IncompleteUpload>

    fun abortIncompleteUpload(upload: IncompleteUpload)

    /**
     * The storage liveness witness (§7): a 1-byte PUT to [probeKey], listed,
     * then deleted. True only if the commit completed and was listed.
     */
    fun witness(probeKey: String): Boolean

    /** MinIO's own clock: the `Date` of one response, plus how long the round trip took. */
    fun serverTime(): ServerTime

    /** The total bytes of every listed payload object (never `_probe/`). */
    fun listedPayloadBytes(): Long
}

data class IncompleteUpload(
    val key: String,
    val uploadId: String,
    val initiated: Instant,
)

data class ServerTime(
    /** Whole seconds: HTTP `Date` has 1 s resolution. */
    val date: Instant,
    val roundTrip: Duration,
)

/** The database clock: every deadline, `t0` and release time is measured on it. */
fun interface DatabaseClock {
    fun now(): Instant
}
