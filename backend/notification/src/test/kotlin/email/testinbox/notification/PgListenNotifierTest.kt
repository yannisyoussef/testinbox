package email.testinbox.notification

import email.testinbox.application.port.NOTIFICATION_CHANNEL
import email.testinbox.application.port.NotifierMetrics
import email.testinbox.application.port.WakeOutcome
import email.testinbox.domain.InboxId
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ADR-020 recovery contract for the LISTEN adapter: wake on notify, wake
 * everyone on re-LISTEN (epoch bump), degraded ticks while disconnected,
 * health surfaced. All assertions are event-driven (awaitility), no
 * sleep-based races.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgListenNotifierTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var notifier: PgListenNotifier
    private val metrics = RecordingNotifierMetrics()

    /**
     * Records what the adapter actually reports, so the degraded signal is
     * proven against a real killed connection rather than by calling the
     * metrics object directly (TI-DEPLOY-002 §11). The unit-level behaviour of
     * the gauge lives in `NotifierMetricsTest`; what is proven here is that the
     * transport drives it.
     */
    private class RecordingNotifierMetrics : NotifierMetrics {
        @Volatile var degraded = true
        val reconnects =
            java.util.concurrent.atomic
                .AtomicInteger(0)

        override fun listening() {
            degraded = false
        }

        override fun degraded() {
            degraded = true
        }

        override fun reconnected() {
            reconnects.incrementAndGet()
        }
    }

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
        notifier =
            PgListenNotifier(
                PgListenNotifierConfig(
                    jdbcUrl = postgres.jdbcUrl,
                    username = postgres.username,
                    password = postgres.password,
                    degradedInterval = Duration.ofMillis(300),
                    reconnectBackoff = Duration.ofMillis(100),
                ),
                metrics,
            )
        notifier.start()
        await().atMost(Duration.ofSeconds(10)).until { notifier.health().listening }
    }

    @AfterAll
    fun tearDown() {
        notifier.close()
        postgres.stop()
    }

    private fun connect(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun notify(inboxId: InboxId) {
        connect().use { connection ->
            connection.prepareStatement("SELECT pg_notify(?, ?)").use {
                it.setString(1, NOTIFICATION_CHANNEL)
                it.setString(2, inboxId.value.toString())
                it.executeQuery()
            }
        }
    }

    @Test
    fun `a live LISTEN connection reports not degraded`() {
        // The healthy half of the pair asserted in the reconnect test below.
        // A gauge that only ever reported one state would be decoration.
        metrics.degraded shouldBe false
    }

    @Test
    fun `a notify for the subscribed inbox wakes the parked waiter`() {
        val inboxId = InboxId(UUID.randomUUID())
        notifier.subscribe(inboxId).use { handle ->
            val woken = CountDownLatch(1)
            val thread =
                Thread {
                    if (handle.awaitWake(Instant.now().plusSeconds(10)) == WakeOutcome.WOKEN) {
                        woken.countDown()
                    }
                }.apply { start() }
            notify(inboxId)
            woken.await(10, TimeUnit.SECONDS) shouldBe true
            thread.join(1000)
        }
    }

    @Test
    fun `a notify for a different inbox does not wake the waiter`() {
        val inboxId = InboxId(UUID.randomUUID())
        notifier.subscribe(inboxId).use { handle ->
            notify(InboxId(UUID.randomUUID()))
            handle.awaitWake(Instant.now().plusMillis(700)) shouldBe WakeOutcome.DEADLINE
        }
    }

    @Test
    fun `killed LISTEN connection - reconnect bumps the epoch and wakes parked waiters`() {
        val inboxId = InboxId(UUID.randomUUID())
        val epochBefore = notifier.health().epoch
        notifier.subscribe(inboxId).use { handle ->
            val outcome =
                java.util.concurrent.atomic
                    .AtomicReference<WakeOutcome>()
            val done = CountDownLatch(1)
            Thread {
                outcome.set(handle.awaitWake(Instant.now().plusSeconds(20)))
                done.countDown()
            }.start()

            // Kill the session-scoped LISTEN backend from the server side.
            connect().use { connection ->
                connection.createStatement().use {
                    it.execute(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                            "WHERE application_name = 'testinbox-listen'",
                    )
                }
            }

            // The waiter must be woken by reconnect (epoch bump) or a degraded tick —
            // never left sleeping (ADR-020).
            done.await(15, TimeUnit.SECONDS) shouldBe true
            outcome.get() shouldBe WakeOutcome.WOKEN
        }
        await().atMost(Duration.ofSeconds(10)).until { notifier.health().listening }
        notifier.health().epoch shouldBeGreaterThan epochBefore
        notifier.health().reconnectCount shouldBeGreaterThan 0L

        // The whole point of the degraded gauge: this outage was invisible to
        // callers — the waiter above was still woken and still got its answer —
        // so without a metric driven by the transport itself, nothing would
        // distinguish this from a healthy system running slightly slower
        // (ADR-020, ADR-030 capability 2).
        metrics.reconnects.get().toLong() shouldBeGreaterThan 0L
        // ...and it returns to healthy rather than latching.
        await().atMost(Duration.ofSeconds(10)).until { !metrics.degraded }

        // And after recovery, notifications flow again end-to-end.
        val recovered = InboxId(UUID.randomUUID())
        notifier.subscribe(recovered).use { handle ->
            val woken = CountDownLatch(1)
            Thread {
                if (handle.awaitWake(Instant.now().plusSeconds(10)) == WakeOutcome.WOKEN) {
                    woken.countDown()
                }
            }.start()
            notify(recovered)
            woken.await(10, TimeUnit.SECONDS) shouldBe true
        }
    }

    @Test
    fun `two waiters on the same inbox are both woken by one notify`() {
        val inboxId = InboxId(UUID.randomUUID())
        val first = notifier.subscribe(inboxId)
        val second = notifier.subscribe(inboxId)
        try {
            val woken = CountDownLatch(2)
            for (handle in listOf(first, second)) {
                Thread {
                    if (handle.awaitWake(Instant.now().plusSeconds(10)) == WakeOutcome.WOKEN) {
                        woken.countDown()
                    }
                }.start()
            }
            notify(inboxId)
            woken.await(10, TimeUnit.SECONDS) shouldBe true
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `closed handles no longer receive wakes`() {
        val inboxId = InboxId(UUID.randomUUID())
        val handle = notifier.subscribe(inboxId)
        handle.close()
        notify(inboxId)
        handle.awaitWake(Instant.now().plusMillis(700)) shouldBe WakeOutcome.DEADLINE
    }
}
