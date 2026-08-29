package email.testinbox.notification

import email.testinbox.application.port.MessageNotifier
import email.testinbox.application.port.NOTIFICATION_CHANNEL
import email.testinbox.application.port.NotifierHealth
import email.testinbox.application.port.WaitHandle
import email.testinbox.application.port.WakeOutcome
import email.testinbox.domain.InboxId
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class PgListenNotifierConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    /** Bounded degraded-mode re-query interval while LISTEN is down (ADR-020: order of 1–2s). */
    val degradedInterval: Duration = Duration.ofSeconds(1),
    val reconnectBackoff: Duration = Duration.ofMillis(500),
)

/**
 * ADR-007/ADR-020 wait fan-out over PostgreSQL LISTEN/NOTIFY.
 *
 * The LISTEN connection is a dedicated, session-scoped connection created
 * directly via the driver — it must never be routed through a
 * transaction-mode pool. Recovery contract:
 *  - every successful (re-)LISTEN bumps the epoch and wakes ALL parked
 *    waiters so each re-runs its query once (messages committed during the
 *    gap are found);
 *  - while disconnected, a bounded-interval degraded ticker wakes all
 *    parked waiters (alarmed fallback, not the primary mechanism);
 *  - health (listening/epoch/reconnects) is surfaced for readiness.
 * Notification payloads are wake-up hints only; waiters always re-query.
 */
class PgListenNotifier(
    private val config: PgListenNotifierConfig,
) : MessageNotifier,
    AutoCloseable {
    private val waiters = ConcurrentHashMap<String, MutableSet<Waiter>>()
    private val listening = AtomicBoolean(false)
    private val epoch = AtomicLong(0)
    private val reconnects = AtomicLong(0)

    @Volatile private var running = false
    private var listenerThread: Thread? = null
    private var degradedThread: Thread? = null

    fun start() {
        check(!running) { "already started" }
        running = true
        listenerThread =
            Thread(::listenLoop, "testinbox-listen").apply {
                isDaemon = true
                start()
            }
        degradedThread =
            Thread(::degradedLoop, "testinbox-listen-degraded").apply {
                isDaemon = true
                start()
            }
    }

    override fun subscribe(inboxId: InboxId): WaitHandle {
        val waiter = Waiter(inboxId.value.toString())
        waiters.computeIfAbsent(waiter.inboxKey) { ConcurrentHashMap.newKeySet() }.add(waiter)
        return waiter
    }

    override fun health(): NotifierHealth =
        NotifierHealth(listening = listening.get(), epoch = epoch.get(), reconnectCount = reconnects.get())

    private fun listenLoop() {
        var firstAttempt = true
        while (running) {
            if (!firstAttempt) {
                reconnects.incrementAndGet()
                sleepQuietly(config.reconnectBackoff.toMillis())
                if (!running) return
            }
            firstAttempt = false
            var connection: Connection? = null
            try {
                connection = openSessionConnection()
                connection.createStatement().use { it.execute("LISTEN $NOTIFICATION_CHANNEL") }
                val pg = connection.unwrap(PGConnection::class.java)
                listening.set(true)
                epoch.incrementAndGet()
                // ADR-020: after LISTEN restoration every parked waiter re-runs its query once.
                wakeAll()
                log.info("LISTEN active on channel {} (epoch {})", NOTIFICATION_CHANNEL, epoch.get())
                while (running) {
                    pg.getNotifications(POLL_MILLIS)?.forEach { wake(it.parameter) }
                }
            } catch (e: Exception) {
                if (running) log.warn("LISTEN connection lost: {}", e.message)
            } finally {
                listening.set(false)
                runCatching { connection?.close() }
            }
        }
    }

    private fun degradedLoop() {
        while (running) {
            sleepQuietly(config.degradedInterval.toMillis())
            if (running && !listening.get()) wakeAll()
        }
    }

    private fun openSessionConnection(): Connection {
        val props =
            Properties().apply {
                setProperty("user", config.username)
                setProperty("password", config.password)
                setProperty("ApplicationName", "testinbox-listen")
            }
        return DriverManager.getConnection(config.jdbcUrl, props)
    }

    private fun wake(inboxKey: String?) {
        if (inboxKey == null) return
        waiters[inboxKey]?.forEach(Waiter::wake)
    }

    private fun wakeAll() {
        waiters.values.forEach { set -> set.forEach(Waiter::wake) }
    }

    override fun close() {
        running = false
        listenerThread?.join(2000)
        degradedThread?.join(2000)
    }

    private inner class Waiter(
        val inboxKey: String,
    ) : WaitHandle {
        private val signal = Semaphore(0)

        fun wake() {
            // Cap pending permits at 1: waiters re-query on every wake anyway.
            if (signal.availablePermits() == 0) signal.release()
        }

        override fun awaitWake(deadline: Instant): WakeOutcome {
            val waitMillis = Duration.between(Instant.now(), deadline).toMillis()
            if (waitMillis <= 0) return WakeOutcome.DEADLINE
            return if (signal.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                WakeOutcome.WOKEN
            } else {
                WakeOutcome.DEADLINE
            }
        }

        override fun close() {
            waiters[inboxKey]?.remove(this)
            waiters.compute(inboxKey) { _, set -> if (set.isNullOrEmpty()) null else set }
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val POLL_MILLIS = 500
        val log = LoggerFactory.getLogger(PgListenNotifier::class.java)
    }
}
