package email.testinbox.ingestion.ops

import email.testinbox.application.port.DatabaseClock
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageNodeClaims
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.port.StorageReservations
import email.testinbox.application.storage.ClockOffset
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageNodeLifecycle
import email.testinbox.application.storage.StorageProtocol
import email.testinbox.application.storage.WriteSlots
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * This gateway's ADR-035 node runtime (§9, §5):
 * - claims its node id for the life of the process, and refuses to start
 *   if another live process holds it;
 * - registers its `storage_node` generation, recovering earlier unclean
 *   generations of this node into keyless ambiguity, BEFORE the SMTP gateway
 *   accepts anything (a lower phase starts first and stops last);
 * - heartbeats on its own thread, so a slow storage call can never starve it
 *   and let cleanup mistake this live process for a dead one;
 * - measures the DB↔storage clock offset, and opens the breaker while it
 *   exceeds `ε_max`;
 * - marks the generation clean only after the gateway has stopped taking
 *   mail, and never when an ambiguity could not be persisted.
 */
@Suppress("LongParameterList") // its collaborators, then the intervals tests may shorten
class StorageNodeRuntime(
    private val lifecycle: StorageNodeLifecycle,
    private val breaker: StorageBreaker,
    private val inspection: StorageInspection,
    private val clock: DatabaseClock,
    private val metrics: StorageProtocolMetrics,
    private val heartbeatEvery: Duration = Duration.ofSeconds(10),
    private val offsetEvery: Duration = Duration.ofSeconds(30),
    private val claims: StorageNodeClaims? = null,
    private val slots: WriteSlots? = null,
    /** Where an out-of-bound offset is made durable; the same hold cleanup applies. */
    private val reservations: StorageReservations? = null,
) : SmartLifecycle {
    private var executors: List<ScheduledExecutorService> = emptyList()
    private var claim: StorageNodeClaims.Claim? = null

    override fun start() {
        claims?.let { c ->
            claim = c.claim(lifecycle.node.nodeId)
                ?: error("storage node id '${lifecycle.node.nodeId}' is held by another live process; every process needs its own")
        }
        lifecycle.start()
        executors =
            listOf(
                scheduled("storage-node-heartbeat").also {
                    it.scheduleWithFixedDelay(::heartbeat, heartbeatEvery.toMillis(), heartbeatEvery.toMillis(), TimeUnit.MILLISECONDS)
                },
                scheduled("storage-node-offset").also {
                    it.scheduleWithFixedDelay(::checkOffset, 0, offsetEvery.toMillis(), TimeUnit.MILLISECONDS)
                },
            )
    }

    private fun scheduled(name: String): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, name).apply {
                isDaemon =
                    true
            }
        }

    @Synchronized
    private fun heartbeat() {
        runCatching {
            lifecycle.heartbeat()
            val c = claims ?: return@runCatching
            if (claim?.held() == true) return@runCatching
            // The claim's session died, so the id is unguarded: take it back,
            // or stay failed closed while another process holds it. The trip
            // is renewed on EVERY heartbeat (more often than any backoff), so
            // no trial can reopen ingestion while the id is shared.
            claim?.let { runCatching { it.close() } }
            claim = c.claim(lifecycle.node.nodeId)
            if (claim == null) {
                log.error("storage node id held by another process: storage breaker kept open")
                breaker.trip(StorageBreaker.Kind.UNAVAILABLE)
                metrics.breakerOpen(true)
            }
        }.onFailure { log.warn("storage node heartbeat failed: {}", it.toString()) }
    }

    fun checkOffset() {
        runCatching {
            val offset = ClockOffset.measure(inspection, clock)
            metrics.clockOffset(offset.offset)
            if (!offset.withinBound()) {
                log.warn(
                    "storage clock offset {} ms (error {} ms) exceeds epsilon: breaker open",
                    offset.offset.toMillis(),
                    offset.error.toMillis(),
                )
                breaker.trip(StorageBreaker.Kind.CLOCK_OFFSET)
                // Every reservation this node admitted before it noticed is held
                // durably, whether or not cleanup ever observes this episode.
                reservations?.holdForClockOffset(offset.offset.abs().plus(offset.error), StorageProtocol.SETTLE)
            }
            metrics.breakerOpen(breaker.isOpen)
        }.onFailure { log.warn("storage clock offset check failed: {}", it.toString()) }
    }

    @Synchronized
    override fun stop() {
        executors.forEach { it.shutdownNow() }
        executors = emptyList()
        runCatching { lifecycle.stop(slots?.poisoned() ?: 0) }
            .onFailure { log.warn("could not mark the storage node generation clean: {}", it.toString()) }
        claim?.close()
        claim = null
    }

    override fun isRunning(): Boolean = executors.isNotEmpty()

    /** Before the SMTP gateway (default phase): starts first, stops last. */
    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE - 100

    private companion object {
        val log = LoggerFactory.getLogger(StorageNodeRuntime::class.java)
    }
}
