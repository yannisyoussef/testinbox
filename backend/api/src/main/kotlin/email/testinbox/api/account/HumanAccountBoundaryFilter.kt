package email.testinbox.api.account

import email.testinbox.api.web.Problems
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/** Unknown paths are gated too; a disabled account flow cannot expose a forgotten handler. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 9)
class HumanAccountBoundaryFilter(
    private val properties: HumanAccountProperties,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        listOf(request.servletPath, request.requestURI.removePrefix(request.contextPath)).none { path ->
            path == "/account" || path.startsWith("/account/") || path.startsWith("/account;")
        }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (allow(request, response)) chain.doFilter(request, response)
    }

    /** Also called after handler resolution, so URL normalization cannot bypass the boundary. */
    fun allow(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Boolean {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Pragma", "no-cache")
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Referrer-Policy", "no-referrer")
        if (!properties.enabled) {
            reject(request, response, HttpStatus.SERVICE_UNAVAILABLE, "accounts-unavailable")
            return false
        }
        if (request.method !in setOf("GET", "HEAD", "OPTIONS") &&
            request.getHeaders("Origin").toList() != listOf(properties.publicOrigin)
        ) {
            reject(request, response, HttpStatus.FORBIDDEN, "invalid-origin")
            return false
        }
        return true
    }

    private fun reject(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        type: String,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        mapper.writeValue(response.writer, Problems.of(status, type, status.reasonPhrase, null, request))
    }
}
