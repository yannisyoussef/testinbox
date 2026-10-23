package email.testinbox.observability

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

/**
 * The degraded-polling signal (TI-DEPLOY-002 §11).
 *
 * This is the metric that matters most operationally, because the failure it
 * describes is invisible everywhere else: when `LISTEN` stops delivering,
 * TestInbox keeps working. HTTP stays 200, messages still arrive, readiness may
 * stay green — waits just resolve up to a second later, off the bounded
 * re-query fallback (ADR-020). That is exactly what a transaction-mode pooler
 * in front of PostgreSQL produces (ADR-030 capability 2), and without this
 * gauge nothing distinguishes it from a healthy system.
 *
 * Both states are asserted, in both directions, because a gauge that only ever
 * reports one of them is decoration.
 */
class NotifierMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = MicrometerNotifierMetrics(registry)

    private fun degraded(): Double = registry.find("testinbox_wait_listen_degraded_polling").gauge()!!.value()

    private fun reconnects(): Double = registry.find("testinbox_wait_listen_reconnect_total").counter()!!.count()

    @Test
    fun `a process that has not connected yet reports degraded, not healthy`() {
        // Starting at 0 would report "notifications fine" for a process whose
        // LISTEN connection never came up at all.
        degraded() shouldBe 1.0
    }

    @Test
    fun `establishing LISTEN clears the degraded flag`() {
        metrics.listening()
        degraded() shouldBe 0.0
    }

    @Test
    fun `losing the connection raises it again, and reconnecting clears it`() {
        metrics.listening()
        degraded() shouldBe 0.0

        metrics.degraded()
        degraded() shouldBe 1.0

        metrics.reconnected()
        metrics.listening()
        degraded() shouldBe 0.0
        reconnects() shouldBe 1.0
    }

    @Test
    fun `the reconnect counter exists at zero before the first reconnect`() {
        // A counter that only materialises once it fires cannot be alerted on
        // with increase() over a window containing its first appearance.
        reconnects() shouldBe 0.0
    }

    @Test
    fun `repeated reconnects accumulate — flapping is visible as a rate`() {
        repeat(3) {
            metrics.degraded()
            metrics.reconnected()
            metrics.listening()
        }
        reconnects() shouldBe 3.0
        degraded() shouldBe 0.0
    }
}
