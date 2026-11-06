package email.testinbox.application.usecase

import email.testinbox.application.port.EscrowKeeperState
import email.testinbox.application.port.EscrowRegrant
import email.testinbox.application.port.EscrowRegrantKind
import email.testinbox.application.port.StorageEscrowMetrics
import email.testinbox.application.port.StorageEscrowStore
import email.testinbox.application.storage.FootprintPolicy
import java.time.Duration

/**
 * The keeper's refill-only regrants (structural-admission-design.md §4.5; ADR-038
 * PROPOSED, M1 prototype). The global refill is the slow path without an event:
 * the drain fence, mode a's snapshot statement, the application's readiness
 * verdict on it (mode a's own `FootprintPolicy.unavailability`), then the one
 * regrant. The background scope refill (m1) refills each stale or low row in its
 * own transaction. Idempotent: running it twice grants from the truth twice.
 */
class RefillStorageEscrow(
    private val store: StorageEscrowStore,
    private val footprint: FootprintPolicy,
    private val metrics: StorageEscrowMetrics = StorageEscrowMetrics.NONE,
) {
    fun refillGlobal(): EscrowRegrant {
        val started = System.nanoTime()
        val regrant = store.refillGlobal { snapshot -> footprint.unavailability(snapshot.footprint)?.name?.lowercase() }
        metrics.regrant(EscrowRegrantKind.KEEPER, Duration.ofNanos(System.nanoTime() - started), regrant.granted)
        return regrant
    }

    fun state(
        nodeId: String,
        horizon: Duration,
    ): EscrowKeeperState = store.keeperState(nodeId, horizon)

    /** Refills every candidate row, each in its own transaction; returns how many were refilled (collected rows excluded). */
    fun refillScopes(
        lowPayload: Long,
        lowFootprint: Long,
        batch: Int = DEFAULT_BATCH,
    ): Int {
        var refilled = 0
        store.scopeCandidates(lowPayload, lowFootprint, batch).forEach { if (store.refillScope(it)) refilled++ }
        metrics.backgroundRefill(refilled)
        return refilled
    }

    private companion object {
        const val DEFAULT_BATCH = 500
    }
}
