package email.testinbox.api.web

import email.testinbox.application.deployment.AppliedSchema
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.deployment.SchemaHistory
import email.testinbox.application.deployment.SchemaVersion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gate exists because readiness alone does not stop traffic in the shipped
 * topology (ADR-029 §4, ADR-030 Option A): nginx proxies unconditionally and
 * Compose does not remove an unhealthy container. These tests are what make
 * that guarantee true rather than aspirational.
 */
class SchemaGateFilterTest {
    private class MutableClock(
        var now: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    ) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    private fun gate(
        applied: List<String>,
        bundled: String = "3",
        clock: Clock = MutableClock(),
        reads: AtomicInteger = AtomicInteger(),
    ) = SchemaGateFilter(
        SchemaCompatibility(
            SchemaHistory {
                reads.incrementAndGet()
                AppliedSchema(applied, emptyList(), true)
            },
            SchemaVersion(bundled),
        ),
        clock,
    )

    private fun request(uri: String = "/v1/inboxes") = MockHttpServletRequest("GET", uri).apply { requestURI = uri }

    @Test
    fun `a node on the expected schema serves the request`() {
        var reached = false
        val response = MockHttpServletResponse()
        gate(listOf("1", "2", "3")).doFilter(request(), response, FilterChain { _, _ -> reached = true })
        reached shouldBe true
        response.status shouldBe 200
    }

    @Test
    fun `a node whose schema is behind refuses with 503 and Retry-After, and never reaches the controller`() {
        val response = MockHttpServletResponse()
        var reached = false

        gate(listOf("1")).doFilter(request(), response, FilterChain { _, _ -> reached = true })

        reached shouldBe false
        response.status shouldBe 503
        response.contentType shouldBe "application/problem+json"
        response.getHeader("Retry-After") shouldBe "5"
        response.contentAsString shouldContain "schema-unavailable"
        response.contentAsString shouldContain "requires 3"
    }

    @Test
    fun `a node ahead of its artifact serves normally - a rolled-back artifact must work`() {
        var reached = false
        gate(listOf("1", "2", "3", "4")).doFilter(request(), MockHttpServletResponse(), FilterChain { _, _ -> reached = true })
        reached shouldBe true
    }

    @Test
    fun `non-v1 paths are never gated - the readiness probe must stay reachable`() {
        var reached = false
        val response = MockHttpServletResponse()
        gate(listOf("1")).doFilter(request("/actuator/health"), response, FilterChain { _, _ -> reached = true })
        reached shouldBe true
        response.status shouldBe 200
    }

    @Test
    fun `the verdict is cached, so the gate does not add a query to every request`() {
        val reads = AtomicInteger()
        val clock = MutableClock()
        val filter = gate(listOf("1", "2", "3"), clock = clock, reads = reads)

        repeat(20) { filter.doFilter(request(), MockHttpServletResponse(), MockFilterChain()) }
        reads.get() shouldBe 1

        // ...and re-read once the window passes, so a node that recovers starts
        // serving again on its own rather than needing a restart.
        clock.now = clock.now.plus(Duration.ofSeconds(6))
        filter.doFilter(request(), MockHttpServletResponse(), MockFilterChain())
        reads.get() shouldBe 2
    }
}
