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
     * `first_upload_at` may have objects even if its node died (§9). False
     * when the reservation is no longer `RESERVED` (cleanup fenced it): the
     * upload must not start.
     */
    fun markUploadStarted(id: MessageId): Boolean

    /**
     * The event was abandoned and every upload it started ended DEFINITIVELY:
     * `RESERVED` → `RELEASING`, releasable now (§4). Guarded on `RESERVED`.
     */
    fun releaseAbandoned(ids: Collection<MessageId>)
}

/**
 * The ADR-035 §5 clock-offset hold: an out-of-bound DB↔storage offset is
 * recorded durably, then applied to every reservation.
 */
interface StorageClockHold {
    /**
     * A DB↔storage clock offset beyond `ε_max` was observed (§5). DURABLY,
     * every reservation, `RESERVED` or `RELEASING`, becomes releasable no
     * earlier than `write_deadline_at + settle + offset`: storage's own clock
     * may have accepted its upload up to [offset] later than the database
     * deadline says.
     *
     * Idempotent and bounded: `greatest()`, never an addition, so repeated
     * passes over one episode never compound, and the hold is at most the
     * largest offset observed. Nothing is ever moved earlier. Returns the rows
     * whose release time moved.
     */
    fun holdForClockOffset(
        offset: Duration,
        settle: Duration,
    ): Int

    /**
     * Durably records an observed out-of-bound offset episode, BEFORE any
     * hold is attempted: one tiny row, touching no reservation, whose offset
     * is only ever raised. A process that dies before its hold commits leaves
     * this record, and the next cleaner applies it before releasing anything.
     */
    fun recordClockEpisode(offset: Duration)

    /**
     * Applies a recorded episode, if there is one. In ONE transaction it
     * holds every reservation as [holdForClockOffset] does, by the recorded
     * offset, and forgets the episode, so the record disappears exactly when
     * its hold is durable. Returns the rows moved, or null when nothing was
     * recorded. Every cleaner pass calls it before any release.
     */
    fun applyClockEpisode(settle: Duration): Int?
}

/** `storage_reservation` cleanup and release (ADR-035 §7), and the orphan sweep's check. */
interface StorageReservations : StorageClockHold {
    /**
     * `RESERVED` past its write deadline → `RELEASING`, releasable at
     * `write_deadline_at + S`. One guarded statement: a concurrent T2 either
     * already holds the row (and this waits, then finds it gone) or finds it
     * `RELEASING` and is fenced.
     */
    fun expireOverdue(settle: Duration): Int

    /**
     * Up to [limit] ids of `RELEASING` rows due by [horizon], ascending. A
     * read: nothing is locked. Empty while a clock episode is recorded but
     * not yet applied.
     */
    fun releasable(
        horizon: Instant,
        limit: Int,
    ): List<MessageId>

    /**
     * In its OWN short transaction, locks the one row [id] with
     * `FOR UPDATE SKIP LOCKED`, rechecks that it is still `RELEASING` and due
     * by [horizon], and runs [work] while holding it. Returns null, without
     * running [work], when another cleaner holds the row, it is no longer
     * due, or a clock episode is recorded but not yet applied (the record is
     * the linearization point: no release after it until its hold commits). Each row commits or rolls back alone, so one row's storage error
     * can never undo another row's release, postponement or latch.
     */
    fun <T : Any> withReleasable(
        id: MessageId,
        horizon: Instant,
        work: (ReleasableReservation) -> T,
    ): T?

    /** Released: the row is deleted and its bytes stop counting. Inside [withReleasable]. */
    fun release(id: MessageId)

    /** A late object was found: the row stays charged until [until]. Inside [withReleasable]. */
    fun postpone(
        id: MessageId,
        until: Instant,
    )

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
 * process restarts. Ambiguity and node generations are one port because
 * recovering a dead generation writes ambiguity rows in the same transaction
 * that forgets it.
 */
@Suppress("TooManyFunctions")
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

    /**
     * Resolved by a COMMITTED message: the key's objects are legitimate
     * content, so no evidence is kept. A later orphan of that key (its inbox
     * deleted) must not look like a late object to [wasAmbiguous].
     */
    fun resolveCommitted(id: Long)

    /**
     * Whether [key] was ambiguous within [within]: resolved or not. The orphan
     * sweep uses it to recognise a late object it is about to delete (§9 point 2).
     */
    fun wasAmbiguous(
        key: String,
        within: Duration,
    ): Boolean

    fun oldestUnresolvedAge(): Duration?

    // --- node generations --------------------------------------------------------------------

    fun registerGeneration(
        nodeId: String,
        generation: java.util.UUID,
        capability: String,
    )

    /**
     * Refreshes this generation's heartbeat, re-creating its row if cleanup
     * recovered it while this process was alive but unable to heartbeat.
     * Returns false in that case: the generation had been declared dead.
     */
    fun heartbeat(
        nodeId: String,
        generation: java.util.UUID,
        capability: String,
    ): Boolean

    fun markCleanShutdown(
        nodeId: String,
        generation: java.util.UUID,
    )

    /**
     * For every generation of [nodeId] other than [current] that did not shut
     * down cleanly (or, when [nodeId] is null, of any node whose heartbeat is
     * older than [staleAfter]), records the uploads it might have had in
     * flight:
     * - at most [slots] keyless rows for the node, which occupy its slots
     *   (the H bound);
     * - one keyed COVERAGE row per key of its started (`first_upload_at`)
     *   but unconsumed reservations, under [coverageNode] of the node, so
     *   each key gets its own per-key proof at `verify_at` and the orphan
     *   sweep leaves it alone until then. Coverage rows occupy no slot.
     *
     * Then forgets the generation. Idempotent, and returns the keyless rows recorded.
     */
    fun recoverDeadGenerations(
        nodeId: String?,
        current: java.util.UUID?,
        staleAfter: Duration,
        slots: Int,
        verifyAfter: Duration,
    ): Int
}

/**
 * Exclusive use of a storage node id while this process lives (ADR-035 §9).
 * Two live processes sharing an id would recover each other's in-flight
 * uploads as dead and share one ambiguity budget.
 */
fun interface StorageNodeClaims {
    /** Claims [nodeId], or returns null when another live process holds it. */
    fun claim(nodeId: String): Claim?

    interface Claim : AutoCloseable {
        /** Whether the claim is still held (its database session is alive). */
        fun held(): Boolean
    }
}

/** The database admission latch (ADR-035 §9): the shared fail-closed kill switch. */
interface StorageLatch {
    fun latched(): String?

    /** Sets the latch if it is not set. Only an operator clears it, by hand (runbook). */
    fun latch(reason: String)
}

/** The `node_id` under which a dead generation's per-key coverage rows are kept: never a real node's. */
fun coverageNode(nodeId: String): String = "recovered:$nodeId"

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
