package email.testinbox.application.storage

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.DatabaseClock
import email.testinbox.application.port.ReleasableReservation
import email.testinbox.application.port.ReleasePath
import email.testinbox.application.port.StorageAmbiguity
import email.testinbox.application.port.StorageInspection
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
 *   suspended, and on resumption every pending release moves later by the
 *   observed offset;
 * - a storage witness issued at or after `release_not_before` completed at
 *   `w′`, and `now() ≥ w′ + C_drain`. This proves storage is not stalled, not
 *   that earlier commits drained;
 * - for EVERY reserved key: delete it, then `ListObjectsV2(key) = ∅` and
 *   `ListMultipartUploads(prefix = exact key) = ∅`.
 *
 * Anything found after the delete is a late object: it is deleted, the
 * release moves `S` later, and the admission latch is set. Only an operator
 * clears the latch.
 */
class ReleaseStaleReservations(
    private val reservations: StorageReservations,
    private val ambiguity: StorageAmbiguity,
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
    )

    /** Completed witnesses (issued, completed), on the database clock, newest last. */
    private val witnesses = ArrayDeque<Pair<Instant, Instant>>()

    /** The largest offset observed while releases were suspended; applied on resumption. */
    private var suspendedOffset: Duration? = null

    @Synchronized
    fun run(): Report {
        // A node whose heartbeat went stale may have died with uploads in
        // flight: keyless ambiguity keeps its slots occupied (§9).
        ambiguity.recoverDeadGenerations(null, null, staleHeartbeat, StorageProtocol.MAX_CONCURRENT_WRITES, StorageProtocol.T_VERIFY)
        val expired = reservations.expireOverdue(settle)
        metrics.reservations(reservations.countsByState())

        val offset = ClockOffset.measure(inspection, clock)
        metrics.clockOffset(offset.offset)
        if (!offset.withinBound()) {
            val observed = offset.offset.abs().plus(offset.error)
            suspendedOffset = maxOf(suspendedOffset ?: Duration.ZERO, observed)
            log.warn(
                "storage_release_suspended clock offset {} ms exceeds {} ms",
                observed.toMillis(),
                StorageProtocol.EPSILON_MAX.toMillis(),
            )
            return Report(expired, 0, 0, suspendedForClockOffset = true, witnessed = false)
        }
        suspendedOffset?.let { by ->
            val moved = reservations.postponeAll(by)
            log.warn("storage_release_resumed pending releases moved {} ms later ({} rows)", by.toMillis(), moved)
            suspendedOffset = null
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
        reservations.withReleasable(horizon, batch) { rows ->
            hook.afterClaim()
            for (row in rows) {
                when (release(row, now)) {
                    Released.RELEASED -> released++
                    Released.LATE -> late++
                }
            }
        }
        return Report(expired, released, late, suspendedForClockOffset = false, witnessed = witnessed)
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
                late = true
                if (stillThere) inspection.deleteObject(key)
                if (incomplete) inspection.incompleteUploads().filter { it.key == key }.forEach(inspection::abortIncompleteUpload)
            }
        }
        if (late) {
            reservations.postpone(row.messageId, now.plus(settle))
            metrics.lateObject()
            ambiguity.latch("late object found while releasing a reservation")
            metrics.latched(true)
            log.error("storage_late_object reservation keys reappeared after deletion; admission LATCHED")
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
 * - A key found present with no committed message is a late object: deleted,
 *   metered, LATCHED.
 * - Keyless rows (from a dead process) only bound that process's slots. Their
 *   keys are covered by their reservations' release proofs and by the orphan
 *   sweep, so they resolve at `verify_at`.
 */
class VerifyAmbiguousUploads(
    private val ambiguity: StorageAmbiguity,
    private val reservations: StorageReservations,
    private val inspection: StorageInspection,
    private val metrics: StorageProtocolMetrics = StorageProtocolMetrics.NOOP,
    private val batch: Int = 100,
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
            val key = record.objectKey
            if (key == null) {
                ambiguity.resolve(record.id)
                resolved++
                continue
            }
            val messageId =
                ObjectKeys.messageIdOf(key)?.let { runCatching { MessageId(UUID.fromString(it)) }.getOrNull() }
            if (messageId != null && reservations.reservationExists(messageId)) {
                deferred++
                continue
            }
            val committed = messageId != null && reservations.messageExists(messageId)
            val present = inspection.objectExists(key)
            val incomplete = inspection.incompleteUploadExists(key)
            if (!committed && (present || incomplete)) {
                if (present) inspection.deleteObject(key)
                if (incomplete) inspection.incompleteUploads().filter { it.key == key }.forEach(inspection::abortIncompleteUpload)
                metrics.lateObject()
                ambiguity.latch("late object found at ambiguity verification")
                metrics.latched(true)
                log.error("storage_late_object an ambiguous upload landed after its reservation was released; admission LATCHED")
                late++
            }
            ambiguity.resolve(record.id)
            resolved++
        }
        metrics.ambiguousUploads(ambiguity.unresolvedTotal())
        return Report(resolved, deferred, late)
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

    fun heartbeat() = ambiguity.heartbeat(node.nodeId, node.generation)

    fun stop() = ambiguity.markCleanShutdown(node.nodeId, node.generation)

    private companion object {
        val log = LoggerFactory.getLogger(StorageNodeLifecycle::class.java)
    }
}
