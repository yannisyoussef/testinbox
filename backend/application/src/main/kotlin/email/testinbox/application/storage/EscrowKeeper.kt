package email.testinbox.application.storage

import email.testinbox.application.port.EscrowSignals
import email.testinbox.application.usecase.RefillStorageEscrow
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The slot-lane escrow keeper of ONE ingestion node (structural-admission-design.md
 * §4.5; ADR-038 PROPOSED, M1 prototype). NOTIFY-driven: it wakes on every epoch
 * bump ([signals]), and at least every [poll] while nothing arrives (the bounded
 * poll is also what runs while LISTEN is down). On each wake-up:
 * - a global refill when the node's lanes are missing, of a stale epoch or
 *   generation, within [horizon] of their validity, or below half a quantum
 *   (the database decides which, `storage_escrow_keeper_state`);
 * - a background scope refill (m1) after any epoch change, and on a fixed
 *   [scopeCadence], of every row below one maximum event or stale.
 *
 * A dead keeper costs speed, not safety: a stale lane escalates, and the slow
 * path regrants. Runs on the caller's thread ([run]); [stop] ends it.
 */
class EscrowKeeper(
    private val refill: RefillStorageEscrow,
    private val signals: EscrowSignals,
    private val nodeId: String,
    /** The low-water marks of the background scope refill: one maximum event, payload and footprint. */
    private val scopeLowPayload: Long,
    private val scopeLowFootprint: Long,
    private val horizon: Duration = Duration.ofSeconds(60),
    private val poll: Duration = Duration.ofSeconds(1),
    private val scopeCadence: Duration = Duration.ofSeconds(5),
) : Runnable {
    private val stopped = AtomicBoolean(false)

    /** Global refills this keeper ran, granted or not, and the ones that failed. */
    val globalRefills = AtomicLong()
    val failures = AtomicLong()
    val scopeRefills = AtomicLong()

    @Volatile var lastSuccessNanos: Long = 0
        private set

    override fun run() {
        var lastScopes = 0L
        var lastEpochs: Triple<Long, Long, Long>? = null
        while (!stopped.get()) {
            try {
                val state = refill.state(nodeId, horizon)
                val epochs = Triple(state.epoch, state.scopeEpoch, state.generation)
                if (state.globalDue) {
                    refill.refillGlobal()
                    globalRefills.incrementAndGet()
                }
                val now = System.nanoTime()
                if (epochs != lastEpochs || now - lastScopes >= scopeCadence.toNanos()) {
                    scopeRefills.addAndGet(refill.refillScopes(scopeLowPayload, scopeLowFootprint).toLong())
                    lastScopes = now
                }
                lastEpochs = epochs
                lastSuccessNanos = System.nanoTime()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                // Liveness only: the fast path never depends on the keeper for safety.
                failures.incrementAndGet()
                log.warn("escrow keeper pass failed: {}", e.toString())
            }
            if (stopped.get()) break
            signals.await(poll)
        }
    }

    fun stop() {
        stopped.set(true)
    }

    private companion object {
        val log = LoggerFactory.getLogger(EscrowKeeper::class.java)
    }
}
