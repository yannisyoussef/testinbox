package email.testinbox.api.ops

import email.testinbox.application.port.StorageNodeClaims
import email.testinbox.application.storage.StorageNodeLifecycle
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * This API node's ADR-035 identity in `storage_node` (§9, §14 (a)), with the
 * SAME application-layer lifecycle the ingestion gateway uses
 * ([StorageNodeLifecycle]): the node id is claimed for the life of the
 * process, a generation is registered before any cleanup pass runs, it
 * heartbeats on its own thread, and it is marked clean at shutdown.
 *
 * The API runs reservation cleanup, ambiguity verification and the orphan
 * sweep, which makes it a participant in the storage protocol; the activation
 * barrier's positive inventory must therefore see every declared API node,
 * not only the ingestion ones (TI-STORAGE-006 §18). The API holds no write
 * slots, so an unclean API generation recovers into no ambiguity rows.
 */
class ApiStorageNodeRuntime(
    private val lifecycle: StorageNodeLifecycle,
    private val claims: StorageNodeClaims,
    private val heartbeatEvery: Duration = Duration.ofSeconds(10),
) : SmartLifecycle {
    private var executor: ScheduledExecutorService? = null
    private var claim: StorageNodeClaims.Claim? = null

    override fun start() {
        claim = claims.claim(lifecycle.node.nodeId)
            ?: error("storage node id '${lifecycle.node.nodeId}' is held by another live process; every process needs its own")
        lifecycle.start()
        executor =
            Executors
                .newSingleThreadScheduledExecutor { r -> Thread(r, "storage-node-heartbeat").apply { isDaemon = true } }
                .also {
                    it.scheduleWithFixedDelay(
                        ::heartbeat,
                        heartbeatEvery.toMillis(),
                        heartbeatEvery.toMillis(),
                        TimeUnit.MILLISECONDS,
                    )
                }
    }

    @Synchronized
    private fun heartbeat() {
        runCatching {
            lifecycle.heartbeat()
            if (claim?.held() == true) return@runCatching
            // The claim's session died: take the id back, or stay without it and
            // say so loudly. The API refuses nothing on its own; the barrier's
            // inventory check is what notices a node that has lost its identity.
            claim?.let { runCatching { it.close() } }
            claim = claims.claim(lifecycle.node.nodeId)
            if (claim == null) log.error("storage node id '{}' is held by another process", lifecycle.node.nodeId)
        }.onFailure { log.warn("storage node heartbeat failed: {}", it.toString()) }
    }

    @Synchronized
    override fun stop() {
        executor?.shutdownNow()
        executor = null
        runCatching { lifecycle.stop() }.onFailure { log.warn("could not mark the storage node generation clean: {}", it.toString()) }
        claim?.close()
        claim = null
    }

    override fun isRunning(): Boolean = executor != null

    /** Before the schedulers' first cleanup pass (default phase): starts first, stops last. */
    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE - 100

    private companion object {
        val log = LoggerFactory.getLogger(ApiStorageNodeRuntime::class.java)
    }
}
