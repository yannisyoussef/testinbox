package email.testinbox.api.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

object Correlation {
    const val HEADER = "X-Correlation-Id"
    const val ATTRIBUTE = "testinbox.correlationId"

    fun of(request: HttpServletRequest): String = request.getAttribute(ATTRIBUTE) as? String ?: "unknown"
}

/**
 * Assigns a correlation id to every request, echoed in the response header,
 * MDC, and RFC 7807 bodies (docs/api/principles.md #8). Also stamps the
 * pre-v1 experimental stability header (ADR-015).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationFilter : OncePerRequestFilter() {
    private companion object {
        val SAFE_CORRELATION_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // A client-supplied id is echoed into logs, MDC and problem bodies, so
        // it is accepted only in a shape that cannot forge a log line or inflate
        // a response; anything else gets a fresh one.
        val supplied = request.getHeader(Correlation.HEADER)
        val correlationId =
            if (supplied != null && SAFE_CORRELATION_ID.matches(supplied)) supplied else UUID.randomUUID().toString()
        request.setAttribute(Correlation.ATTRIBUTE, correlationId)
        response.setHeader(Correlation.HEADER, correlationId)
        response.setHeader("X-API-Stability", "experimental")
        MDC.put("correlationId", correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove("correlationId")
        }
    }
}
