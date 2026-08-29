package email.testinbox.api.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

object Correlation {
    const val HEADER = "X-Correlation-Id"
    const val ATTRIBUTE = "testinbox.correlationId"

    fun of(request: HttpServletRequest): String =
        request.getAttribute(ATTRIBUTE) as? String ?: "unknown"
}

/**
 * Assigns a correlation id to every request, echoed in the response header,
 * MDC, and RFC 7807 bodies (docs/api/principles.md #8). Also stamps the
 * pre-v1 experimental stability header (ADR-015).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = request.getHeader(Correlation.HEADER) ?: UUID.randomUUID().toString()
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
