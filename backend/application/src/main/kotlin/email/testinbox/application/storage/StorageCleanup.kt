package email.testinbox.application.storage

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.AmbiguityRecord
import email.testinbox.application.port.DatabaseClock
import email.testinbox.application.port.ReleasableReservation
import email.testinbox.application.port.ReleasePath
import email.testinbox.application.port.StorageAmbiguity
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageLatch
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.port.StorageReservations
import email.testinbox.domain.MessageId
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * ADR-035 §7 `ReleaseStaleReservations`: the only path that frees reserved
 * capacity other than T2 consuming it.
 *
 * Age alone never frees anything (I5). A `RELEASING` reservation is released
 * only when ALL of these hold:
 * - `now() ≥ release_not_before`, on the database clock;
 * - the DB↔storage clock offset is within `ε_max`. Otherwise releases are
 *   suspended. The episode is RECORDED durably first, then applied: every
 *   reservation is held until at least `write_deadline_at + S + offset`, and
 *   the record is forgotten in the same transaction (see
 *   [StorageReservations.recordClockEpisode]). Every pass applies a recorded
 *   episode before it releases anything, so a crash between observing and
 *   holding cannot erase the hold;
 * - a storage witness issued at or after `release_not_before` completed at
 *   `w′`, and `now() ≥ w′ + C_drain`. This proves storage is not stalled, not
 *   that earlier commits drained;
 * - for EVERY reserved key: delete it, then `ListObjectsV2(key) = ∅` and
 *   `ListMultipartUploads(prefix = exact key) = ∅`.
 *
 * Anything found after the delete is a late object: the admission latch is
 * set and COMMITTED first, then the object is deleted and the release moves
 * `S` later. Only an operator clears the latch.
 *
 * Each row is claimed, proved and released in its own short transaction. A
 * storage error on one row is logged and counted, and never rolls back
 * another row's release, postponement or latch, nor stops the rows after it.
 */
@Suppress("LongParameterList") // the ports, then the ADR constants that tests may shorten
class ReleaseStaleReservations(
    private val reservations: StorageReservations,
    private val ambiguity: StorageAmbiguity,
    private val latch: StorageLatch,
    private val inspection: StorageInspection,
    private val clock: DatabaseClock,
    private val probeOwner: String,
    private val metrics: StorageProtocolMetrics = StorageProtocolMetrics.NOOP,
    private val hook: CleanupSyncHook = CleanupSyncHook.NONE,
    private val batch: Int = 100,
    private val staleHeartbeat: Duration = Duration.ofMinutes(5),
    /** `C_drain`. A seam for tests only; the deployables use the ADR value. */
    private val drain: Duration = StorageProtocol.C_DRAIN,
    /** `S`. A seam for tests only; the deployables use the ADR value. */
    private val settle: Duration = StorageProtocol.SETTLE,
) {
    data class Report(
        val expired: Int,
        val released: Int,
        val lateObjects: Int,
        val suspendedForClockOffset: Boolean,
        val witnessed: Boolean,
        /** Rows whose proof failed on a storage or database error: still RELEASING, still charged. */
        val failed: Int = 0,
    )

    /**
     * An observed out-of-bound offset not yet RECORDED durably. It is recorded
     * before anything else in every pass, and while it cannot be, nothing is
     * released. Once recorded, the episode lives in the database: this
     * process, or any other after it, applies it before releasing anything.
     */
    private var pendingEpisode: Duration? = null

    /** Completed witnesses (issued, completed), on the database clock, newest last. */
    private val witnesses = ArrayDeque<Pair<Instant, Instant>>()

    @Synchronized
    fun run(): Report {
        // Both throw while the database refuses them: no release this pass.
        pendingEpisode?.let { record(it) }
        applyRecordedEpisode()
        // A node whose heartbeat went stale may have died with uploads in
        // flight: keyless ambiguity keeps its slots occupied (§9).
        ambiguity.recoverDeadGenerations(null, null, staleHeartbeat, StorageProtocol.MAX_CONCURRENT_WRITES, StorageProtocol.T_VERIFY)
        val expired = reservations.expireOverdue(settle)
        metrics.reservations(reservations.countsByState())
        metrics.reservedBytes(reservations.reservedBytes())

        val offset = ClockOffset.measure(inspection, clock)
        metrics.clockOffset(offset.offset)
        if (!offset.withinBound()) {
            val observed = offset.offset.abs().plus(offset.error)
            // Recorded first, in a row no T2 touches; then applied. A crash
            // between the two leaves the record for the next process.
            pendingEpisode = maxOf(pendingEpisode ?: Duration.ZERO, observed)
            record(observed)
            val held = applyRecordedEpisode() ?: 0
            log.warn(
                "storage_release_suspended clock offset {} ms exceeds {} ms; {} reservation(s) held until deadline + S + offset",
                observed.toMillis(),
                StorageProtocol.EPSILON_MAX.toMillis(),
                held,
            )
            return Report(expired, 0, 0, suspendedForClockOffset = true, witnessed = false)
        }

        val issued = clock.now()
        val witnessed =
            runCatching { inspection.witness("${PROBE_PREFIX}$probeOwner/${UUID.randomUUID()}") }
                .onFailure { log.warn("storage witness failed: {}", it.toString()) }
                .getOrDefault(false)
        if (witnessed) {
            witnesses.addLast(issued to clock.now())
            while (witnesses.size > MAX_WITNESSES) witnesses.removeFirst()
        } else {
            metrics.witnessFailed()
        }
        val now = clock.now()
        // The newest witness that completed at w′ with now ≥ w′ + C_drain.
        // Releases are due only if that witness was ISSUED at or after their
        // release_not_before, so the horizon is its issue time.
        val horizon = witnesses.lastOrNull { (_, completed) -> !now.isBefore(completed.plus(drain)) }?.first
        if (horizon == null) return Report(expired, 0, 0, suspendedForClockOffset = false, witnessed = witnessed)

        var released = 0
        var late = 0
        var failed = 0
        for (id in reservations.releasable(horizon, batch)) {
            try {
                val outcome =
                    reservations.withReleasable(id, horizon) { row ->
                        hook.afterClaim()
                        release(row, now)
                    }
                when (outcome) {
                    Released.RELEASED -> released++
                    Released.LATE -> late++
                    null -> Unit // another cleaner holds it, or it is no longer due
                }
            } catch (e: Exception) {
                // This row stays RELEASING and charged, and is retried next pass.
                failed++
                log.warn("storage_release_failed the row stays charged: {}", e.toString())
            }
        }
        return Report(expired, released, late, suspendedForClockOffset = false, witnessed = witnessed, failed = failed)
    }

    private fun record(offset: Duration) {
        reservations.recordClockEpisode(maxOf(offset, pendingEpisode ?: Duration.ZERO))
        pendingEpisode = null
    }

    /** Holds every reservation by a recorded episode, and forgets it, in one transaction. */
    private fun applyRecordedEpisode(): Int? =
        reservations.applyClockEpisode(settle)?.also { held ->
            log.warn("storage_clock_episode applied: {} reservation(s) held until deadline + S + offset", held)
        }

    private enum class Released { RELEASED, LATE }

    private fun release(
        row: ReleasableReservation,
        now: Instant,
    ): Released {
        if (reservations.messageExists(row.messageId)) {
            // Case [D]: impossible by I4. The objects belong to a committed
            // message, so nothing is deleted.
            log.error("storage_reservation_with_committed_message reservation released without deleting any object")
            reservations.release(row.messageId)
            metrics.released(ReleasePath.RECONCILED)
            return Released.RELEASED
        }
        var deleted = false
        var late = false
        for (key in row.objectKeys) {
            if (inspection.objectExists(key)) deleted = true
            inspection.deleteObject(key)
            hook.afterDeleteBeforeList(key)
            val stillThere = inspection.objectExists(key)
            val incomplete = inspection.incompleteUploadExists(key)
            if (stillThere || incomplete) {
                if (!late) {
                    // Latch BEFORE deleting the evidence, in its own committed transaction.
                    latch.latch("late object found while releasing a reservation")
                    metrics.latched(true)
                    metrics.lateObject()
                    log.error("storage_late_object reservation keys reappeared after deletion; admission LATCHED")
                }
                late = true
                if (stillThere) inspection.deleteObject(key)
                if (incomplete) inspection.incompleteUploads().filter { it.key == key }.forEach(inspection::abortIncompleteUpload)
            }
        }
        if (late) {
            reservations.postpone(row.messageId, now.plus(settle))
            return Released.LATE
        }
        reservations.release(row.messageId)
        metrics.released(if (deleted) ReleasePath.DELETED else ReleasePath.ABSENT)
        return Released.RELEASED
    }

    companion object {
        const val PROBE_PREFIX = "_probe/"
        private const val MAX_WITNESSES = 64
        private val log = LoggerFactory.getLogger(ReleaseStaleReservations::class.java)
    }
}

/**
 * ADR-035 §9 `VerifyAmbiguousUploads`: at `verify_at` (`T_verify` after the
 * ambiguous upload), proves each ambiguous key absent or latches.
 *
 * - A keyed row whose reservation still exists is left for later, and keeps
 *   its slot. Its reservation's own release will prove its keys first.
 * - A key found present with no committed message is a late object: LATCHED
 *   first (committed on its own), then deleted and metered.
 * - A dead process's uploads are two kinds of rows. Its keyless rows only
 *   bound its slots, and resolve at `verify_at`. Every key it had started
 *   has its own keyed coverage row, proved here like any other key.
 * - One row's error is logged and the row stays unresolved for the next
 *   pass; it never stops the rows after it.
 */
class VerifyAmbiguousUploads(
    private val ambiguity: StorageAmbiguity,
    private val latch: StorageLatch,
    private val reservations: StorageReservations,
    private val inspection: StorageInspection,
    private val metrics: StorageProtocolMetrics = StorageProtocolMetrics.NOOP,
    private val batch: Int = 100,
    /** TI-STORAGE-006E PR D: rule (P) before deleting a late object; refused, the row stays unresolved. */
    private val rowFree: RowFreeDebt = RowFreeDebt.NONE,
) {
    data class Report(
        val resolved: Int,
        val deferred: Int,
        val lateObjects: Int,
    )

    fun run(): Report {
        var resolved = 0
        var deferred = 0
        var late = 0
        for (record in ambiguity.due(batch)) {
            try {
                when (verify(record)) {
                    Verified.RESOLVED -> {
                        resolved++
                    }

                    Verified.LATE -> {
                        resolved++
                        late++
                    }

                    Verified.DEFERRED -> {
                        deferred++
                    }
                }
            } catch (e: Exception) {
                log.warn("storage_ambiguity_verification_failed the row stays unresolved: {}", e.toString())
            }
        }
        metrics.ambiguousUploads(ambiguity.unresolvedTotal())
        return Report(resolved, deferred, late)
    }

    private enum class Verified { RESOLVED, LATE, DEFERRED }

    private fun verify(record: AmbiguityRecord): Verified {
        val key = record.objectKey
        if (key == null) {
            ambiguity.resolve(record.id)
            return Verified.RESOLVED
        }
        val messageId =
            ObjectKeys.messageIdOf(key)?.let { runCatching { MessageId(UUID.fromString(it)) }.getOrNull() }
        if (messageId != null && reservations.reservationExists(messageId)) return Verified.DEFERRED
        val committed = messageId != null && reservations.messageExists(messageId)
        val present = inspection.objectExists(key)
        val incomplete = inspection.incompleteUploadExists(key)
        var result = Verified.RESOLVED
        if (!committed && (present || incomplete)) {
            latch.latch("late object found at ambiguity verification")
            metrics.latched(true)
            metrics.lateObject()
            log.error("storage_late_object an ambiguous upload landed after its reservation was released; admission LATCHED")
            if (present) {
                // Rule (P): the late object's pending row is an admission. Refused, the
                // object stays, and so do its ambiguity row and write slot (contract §2.1);
                // the row is due again at the next pass.
                if (!rowFree.beforeDelete(key, inspection.objectSize(key), "ambiguity-verifier")) return Verified.DEFERRED
                inspection.deleteObject(key)
                if (!inspection.objectExists(key)) rowFree.afterProvenAbsent(key)
            }
            if (incomplete) inspection.incompleteUploads().filter { it.key == key }.forEach(inspection::abortIncompleteUpload)
            result = Verified.LATE
        }
        if (committed) ambiguity.resolveCommitted(record.id) else ambiguity.resolve(record.id)
        return result
    }

    private companion object {
        val log = LoggerFactory.getLogger(VerifyAmbiguousUploads::class.java)
    }
}

/**
 * This process's `storage_node` generation (ADR-035 §9, §14): registered at
 * start, heartbeated, marked clean at shutdown. At start, any earlier
 * generation of the same node that did not shut down cleanly is turned into
 * keyless ambiguity BEFORE this process takes its first slot.
 */
class StorageNodeLifecycle(
    private val ambiguity: StorageAmbiguity,
    val node: StorageNode,
    private val slots: Int = StorageProtocol.MAX_CONCURRENT_WRITES,
) {
    fun start(): Int {
        val recovered = ambiguity.recoverDeadGenerations(node.nodeId, node.generation, Duration.ZERO, slots, StorageProtocol.T_VERIFY)
        ambiguity.registerGeneration(node.nodeId, node.generation, StorageProtocol.CAPABILITY)
        if (recovered > 0) log.warn("storage_node_recovered keyless ambiguity rows={} for earlier generations of this node", recovered)
        return recovered
    }

    fun heartbeat() {
        if (!ambiguity.heartbeat(node.nodeId, node.generation, StorageProtocol.CAPABILITY)) {
            log.error(
                "storage_node_resurrected this generation was declared dead while alive (heartbeat stale); " +
                    "re-registered. Any uploads it had started were recorded as ambiguity (an api node starts none)",
            )
        }
    }

    /**
     * Marks the generation clean, unless [poisonedSlots] > 0: an ambiguity
     * this process could not persist must survive the restart, so the
     * generation is left unclean and the next start records it (§9).
     */
    fun stop(poisonedSlots: Int = 0) {
        if (poisonedSlots > 0) {
            log.error("storage_node_unclean_stop {} ambiguous upload(s) were never persisted; generation left unclean", poisonedSlots)
            return
        }
        ambiguity.markCleanShutdown(node.nodeId, node.generation)
    }

    private companion object {
        val log = LoggerFactory.getLogger(StorageNodeLifecycle::class.java)
    }
}
