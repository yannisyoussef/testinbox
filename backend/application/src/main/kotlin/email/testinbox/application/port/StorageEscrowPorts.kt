package email.testinbox.application.port

import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.FootprintModel
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Slot-lane escrow (PROPOSED ADR-038; structural-admission-design.md §4, §7): the
 * fast path's port. M1 PROTOTYPE: only the storage benchmark builds an adapter.
 *
 * [tryAdmit] runs ONE transaction:
 * 1. READ COMMITTED and the bounds, in one round trip;
 * 2. `storage_escrow_lock`: the event's lane, its scope rows (refilled inline when
 *    stale or short), each inbox's owner and `t0` ([EscrowView]);
 * 3. [decide], inside the transaction;
 * 4. on [EscrowPlan.Spend], `storage_escrow_admit` and the COMMIT in one round trip.
 *
 * Everything that is not a decision comes back as [EscrowAttempt.Escalated] with a
 * closed reason, nothing written: a short or stale lane, a scope wait past `L_w`,
 * or a spend the database refused. No SQLSTATE or driver type crosses this port.
 * Only `StorageAdmission` and `RefillStorageEscrow` may call it (`EscrowRuleTest`).
 */
interface StorageEscrowStore {
    fun <R : Any> tryAdmit(
        scope: EscrowScope,
        decide: (EscrowView) -> EscrowPlan<R>,
    ): EscrowAttempt<R>

    /**
     * The keeper's refill-only slow path (§4.5): the drain fence, mode a's snapshot
     * statement with no event, [notReady] (the application's readiness verdict on
     * it, null when ready), then the one regrant.
     */
    fun refillGlobal(notReady: (StorageUsageSnapshot) -> String?): EscrowRegrant

    /** What the keeper reads to decide whether a global refill is due (no locks). */
    fun keeperState(
        nodeId: String,
        horizon: Duration,
    ): EscrowKeeperState

    /** The scope rows the background refill (m1) should refill: stale, or below the low-water marks. */
    fun scopeCandidates(
        lowPayload: Long,
        lowFootprint: Long,
        limit: Int,
    ): List<EscrowScopeRef>

    /** One scope row refilled in its own transaction, under its own row lock. False when it was collected (gone). */
    fun refillScope(ref: EscrowScopeRef): Boolean
}

/**
 * The application-supplied inputs of every scope refill: they can only inflate a
 * SCOPE grant, never a lane (security 3.4), and [binding] hashes all of them so a
 * node with another policy cannot spend this node's grants (architecture M1b).
 */
data class EscrowInputs(
    val maxStoredBytes: Long,
    val inboxShare: BigDecimal,
    val model: FootprintModel,
) {
    val binding: String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                (
                    "escrow-v1|$maxStoredBytes|${inboxShare.stripTrailingZeros().toPlainString()}|${model.blockSizeBytes}|" +
                        "${model.fragmentationDenominator}|${model.objectOverheadMaxBytes}"
                ).toByteArray(),
            ).joinToString("") { "%02x".format(it) }
            .take(BINDING_HEX)

    private companion object {
        const val BINDING_HEX = 32
    }
}

/** An application upper bound of what one event may spend from a scope row. */
data class EscrowNeed(
    val id: UUID,
    val payload: Long,
    val footprint: Long,
)

/** One fast path's lane and scope rows: the slot index names the lane (§2 point 2). */
data class EscrowScope(
    val nodeId: String,
    val lane: Int,
    val workspaces: List<EscrowNeed>,
    val inboxes: List<EscrowNeed>,
    val inputs: EscrowInputs,
) {
    init {
        require(lane in 0 until MAX_LANES) { "a lane is a write-slot index in 0..${MAX_LANES - 1}, was $lane" }
        require(workspaces.isNotEmpty() && inboxes.isNotEmpty()) { "an escrow scope names at least one inbox" }
    }

    companion object {
        const val MAX_LANES = 16
    }
}

/** Why a lane cannot be spent from, as `storage_escrow_lock` evaluated it after its locks (advisory: admit re-checks). */
enum class EscrowLaneStatus { OK, MISSING, STALE, NOT_READY, BINDING }

/** Why a scope row was refilled inline (a closed metric label). */
enum class EscrowRefillCause { NEW, STALE_EPOCH, STALE_BINDING, STALE_LIMIT, SHORT }

/** The lane as the lock statement found it. */
data class EscrowLane(
    val status: EscrowLaneStatus,
    val payloadAvail: Long?,
    val footprintAvail: Long?,
)

/**
 * One scope row after `storage_escrow_lock`. [exact] rows were refilled in THIS
 * transaction, after their lock, and carry their truth: refusals are decided only
 * on them, with mode a's formula (correctness 3).
 */
data class EscrowRow(
    val exact: Boolean,
    val refill: EscrowRefillCause?,
    val refillMicros: Long?,
    val payloadAvail: Long?,
    val footprintAvail: Long?,
    val limitPayload: Long?,
    val limitFootprint: Long?,
    val usedBytes: Long?,
    val usedObjects: Long?,
    val chargeBytes: Long?,
    val chargeObjects: Long?,
    val active: Boolean?,
)

/** What one fast path's decision reads, from the lock statement. */
data class EscrowView(
    val t0: Instant,
    val lane: EscrowLane,
    val workspaces: Map<WorkspaceId, EscrowRow>,
    val inboxes: Map<InboxId, EscrowRow>,
    /** Each inbox's CURRENT owner, from `inbox(id, workspace_id)` after the locks; null when it is gone. */
    val owners: Map<InboxId, WorkspaceId?>,
    val laneWaitMicros: Long,
    val scopeWaitMicros: Long,
)

/** The fast path's decision: spend these reservations, or escalate the whole event. */
sealed interface EscrowPlan<out R : Any> {
    data class Spend<R : Any>(
        val reservations: List<StorageReservationDraft>,
        val outcome: R,
    ) : EscrowPlan<R>

    data class Escalate(
        val reason: EscalationReason,
    ) : EscrowPlan<Nothing>
}

/** What the fast path did: decided (committed), or escalated with nothing written. */
sealed interface EscrowAttempt<out R : Any> {
    data class Decided<R : Any>(
        val outcome: R,
    ) : EscrowAttempt<R>

    data class Escalated(
        val reason: EscalationReason,
    ) : EscrowAttempt<Nothing>
}

/** Why an event left the fast path (§7; a closed metric label, `testinbox_storage_escrow_escalation_total{reason}`). */
enum class EscalationReason {
    LANE_SHORT,
    LANE_STALE,
    LANE_MISSING,
    NOT_READY,
    SCOPE_WAIT,
    SPEND_MISMATCH,
    BINDING_MISMATCH,
    ;

    val label: String get() = name.lowercase()
}

/** One regrant's result: the lanes it stamped and the quantum each got; [granted] false with a closed [reason]. */
data class EscrowRegrant(
    val lanes: Int,
    val payloadGrant: Long,
    val footprintGrant: Long,
    val granted: Boolean,
    val reason: String?,
)

data class EscrowKeeperState(
    val epoch: Long,
    val scopeEpoch: Long,
    val generation: Long,
    val globalDue: Boolean,
    val reason: String?,
)

data class EscrowScopeRef(
    val kind: Kind,
    val id: UUID,
    val cause: String,
) {
    enum class Kind { W, I }
}

/** The fast path's and the keeper's signals, closed labels only (§13.4 item 7). Benchmark-recorded in M1. */
interface StorageEscrowMetrics {
    fun path(path: EscrowPath) {}

    fun escalation(reason: EscalationReason) {}

    fun inlineRefill(
        kind: EscrowScopeRef.Kind,
        cause: EscrowRefillCause,
        duration: Duration,
    ) {}

    fun scopeLock(wait: Duration) {}

    fun regrant(
        kind: EscrowRegrantKind,
        duration: Duration,
        granted: Boolean,
    ) {}

    fun backgroundRefill(count: Int) {}

    companion object {
        val NONE: StorageEscrowMetrics = object : StorageEscrowMetrics {}
    }
}

/** How an event was decided: on the fast path (with or without an inline scope refill) or on the slow path. */
enum class EscrowPath { FAST, SCOPE_REFILL, SLOW }

/** Which regrant ran: inside the slow path's transaction, or the keeper's refill-only one. */
enum class EscrowRegrantKind { SLOW_PATH, KEEPER }

/**
 * The keeper's wake-up source (§4.5): NOTIFY on `storage_escrow` over a
 * SESSION-scoped connection, with a bounded poll while LISTEN is down.
 */
interface EscrowSignals : AutoCloseable {
    /** Waits at most [timeout] for a notification; true when one arrived. */
    fun await(timeout: Duration): Boolean

    /** True while LISTEN is down and the keeper is polling instead. */
    val degraded: Boolean
}
