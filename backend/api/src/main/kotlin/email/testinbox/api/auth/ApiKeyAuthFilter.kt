package email.testinbox.api.auth

import email.testinbox.api.web.Correlation
import email.testinbox.application.usecase.AuthenticateApiKey
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiScope
import email.testinbox.domain.tenant.WorkspaceActor
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

object AuthAttributes {
    const val API_KEY = "testinbox.apiKey"

    const val WORKSPACE_ACTOR = "testinbox.workspaceActor"

    fun principal(request: HttpServletRequest): WorkspaceActor =
        request.getAttribute(WORKSPACE_ACTOR) as? WorkspaceActor
            ?: request.getAttribute(API_KEY) as? ApiKey
            ?: error("request reached a controller without authenticated workspace authority")
}

/**
 * Bearer API-key authentication for every /v1 route (ADR-010,
 * docs/api/principles.md #4). Health endpoints stay open. The key value is
 * hashed for lookup and never logged.
 */
@Component
class ApiKeyAuthFilter(
    private val authenticate: AuthenticateApiKey,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/v1/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val apiKey = bearerToken(request)?.let(authenticate::authenticate)
        if (apiKey == null) {
            writeProblem(request, response)
            return
        }
        request.setAttribute(AuthAttributes.API_KEY, apiKey)
        filterChain.doFilter(request, response)
    }

    private fun writeProblem(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setHeader(Correlation.HEADER, Correlation.of(request))
        // Static, fixed-shape RFC 7807 body — no serializer needed in the filter.
        response.writer.write(
            """
            {"type":"https://testinbox.email/problems/unauthorized","title":"Unauthorized","status":401,
            "detail":"A valid API key is required (Authorization: Bearer <key>)",
            "correlationId":"${Correlation.of(request)}"}
            """.trimIndent(),
        )
    }
}

/** Scope check helper (ADR-010: least-privilege scopes per endpoint). */
class MissingScopeException(
    val scope: ApiScope,
) : RuntimeException("missing scope ${scope.wire}")

fun WorkspaceActor.requireScope(scope: ApiScope) {
    if (!hasScope(scope)) throw MissingScopeException(scope)
}
