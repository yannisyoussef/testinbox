package email.testinbox.ingestion.ops

import email.testinbox.application.port.DatabaseClock
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.storage.ClockOffset
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageNodeLifecycle
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * This gateway's ADR-035 node runtime (§9, §5):
 * - registers its `storage_node` generation, recovering earlier unclean
 *   generations of this node into keyless ambiguity, BEFORE the SMTP gateway
 *   accepts anything (a lower phase starts first and stops last);
 * - heartbeats, so cleanup can tell a dead process from a live one;
 * - measures the DB↔storage clock offset, and opens the breaker while it
 *   exceeds `ε_max`;
 * - marks the generation clean only after the gateway has stopped taking mail.
 */
class StorageNodeRuntime(
    private val lifecycle: StorageNodeLifecycle,
    private val breaker: StorageBreaker,
    private val inspection: StorageInspection,
    private val clock: DatabaseClock,
    private val metrics: StorageProtocolMetrics,
    private val heartbeatEvery: Duration = Duration.ofSeconds(10),
    private val offsetEvery: Duration = Duration.ofSeconds(30),
) : SmartLifecycle {
    private var executor: ScheduledExecutorService? = null

    override fun start() {
        lifecycle.start()
        executor =
            Executors
                .newSingleThreadScheduledExecutor { r -> Thread(r, "storage-node").apply { isDaemon = true } }
                .apply {
                    scheduleWithFixedDelay(::heartbeat, heartbeatEvery.toMillis(), heartbeatEvery.toMillis(), TimeUnit.MILLISECONDS)
                    scheduleWithFixedDelay(::checkOffset, 0, offsetEvery.toMillis(), TimeUnit.MILLISECONDS)
                }
    }

    private fun heartbeat() {
        runCatching { lifecycle.heartbeat() }.onFailure { log.warn("storage node heartbeat failed: {}", it.toString()) }
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
            }
            metrics.breakerOpen(breaker.isOpen)
        }.onFailure { log.warn("storage clock offset check failed: {}", it.toString()) }
    }

    override fun stop() {
        executor?.shutdownNow()
        executor = null
        runCatching { lifecycle.stop() }.onFailure { log.warn("could not mark the storage node generation clean: {}", it.toString()) }
    }

    override fun isRunning(): Boolean = executor != null

    /** Before the SMTP gateway (default phase): starts first, stops last. */
    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE - 100

    private companion object {
        val log = LoggerFactory.getLogger(StorageNodeRuntime::class.java)
    }
}
