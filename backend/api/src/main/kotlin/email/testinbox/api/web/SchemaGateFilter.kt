package email.testinbox.api.web

import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.deployment.SchemaStatus
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * ADR-029 §4 at request time: this node does not serve `/v1` traffic against a
 * schema older than the one its own artifact was built against.
 *
 * The readiness probe reports the same fact, and in an orchestrated
 * environment that would be enough — the platform stops routing to a node that
 * is not ready. It is NOT enough in the topology TestInbox actually ships
 * (ADR-030 Option A): nginx proxies to `api:8080` unconditionally and Docker
 * Compose neither restarts nor removes an unhealthy container, so a node whose
 * readiness went OUT_OF_SERVICE keeps receiving requests and failing them one
 * by one. The window is real — `restart: unless-stopped` brings the
 * applications back after a host reboot with no migration job in between.
 *
 * Refusing here is a `503` with `Retry-After`, which is the honest answer: the
 * schema is expected to arrive, and the caller should come back.
 *
 * Ordered ahead of authentication deliberately. "This node cannot serve you"
 * does not depend on who is asking, and answering `401` first would tell an
 * unauthenticated caller nothing useful while hiding an operational fault.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class SchemaGateFilter(
    private val schema: SchemaCompatibility,
    private val clock: Clock,
    private val recheckAfter: Duration = Duration.ofSeconds(5),
) : OncePerRequestFilter() {
    private data class Cached(
        val at: Instant,
        val status: SchemaStatus,
    )

    @Volatile private var cached: Cached? = null

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/v1/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val status = currentStatus()
        if (status.compatible) {
            filterChain.doFilter(request, response)
            return
        }
        log.warn("refusing /v1 traffic: {}", status.detail)
        writeUnavailable(request, response, status)
    }

    /**
     * Cached with a short TTL. The verdict is a database round trip, and one
     * in front of every request would be a real cost for a check whose answer
     * changes at most once per deployment. Five seconds bounds how long a
     * recovered node keeps refusing.
     */
    private fun currentStatus(): SchemaStatus {
        val now = clock.instant()
        cached?.let { if (Duration.between(it.at, now) < recheckAfter) return it.status }
        val fresh = schema.status()
        cached = Cached(now, fresh)
        return fresh
    }

    private fun writeUnavailable(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: SchemaStatus,
    ) {
        response.status = HttpStatus.SERVICE_UNAVAILABLE.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS.toString())
        response.setHeader(Correlation.HEADER, Correlation.of(request))
        // Static, fixed-shape RFC 7807 body — no serializer needed in a filter.
        // The schema detail is operational, not sensitive: version numbers only.
        response.writer.write(
            """
            {"type":"https://testinbox.email/problems/schema-unavailable","title":"Service unavailable",
            "status":503,"detail":"This node is not serving traffic: ${status.detail}",
            "retryAfterSeconds":$RETRY_AFTER_SECONDS,"correlationId":"${Correlation.of(request)}"}
            """.trimIndent(),
        )
    }

    private companion object {
        const val RETRY_AFTER_SECONDS = 5L
        val log = LoggerFactory.getLogger(SchemaGateFilter::class.java)
    }
}
