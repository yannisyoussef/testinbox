package email.testinbox.api.auth

import email.testinbox.api.account.HumanSessionCookies
import email.testinbox.api.web.Problems
import email.testinbox.application.usecase.AuthenticateApiKey
import email.testinbox.application.usecase.ResolveHumanWorkspace
import email.testinbox.domain.tenant.ApiKey
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.ObjectMapper

/** Authority follows the resolved route; encoded/matrix URLs cannot choose another namespace. */
object ProductRoutes {
    private const val HUMAN = "/account/product/"

    fun isHuman(request: HttpServletRequest): Boolean = pattern(request)?.startsWith(HUMAN) == true

    fun matched(request: HttpServletRequest): String? = pattern(request)?.takeIf { it.startsWith("/v1/") || it.startsWith(HUMAN) }

    fun canonical(path: String): String = if (path.startsWith(HUMAN)) "/v1/" + path.removePrefix(HUMAN) else path

    private fun pattern(request: HttpServletRequest): String? =
        request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()
}

@Component
class WorkspaceAuthorityInterceptor(
    private val humans: ResolveHumanWorkspace,
    private val keys: AuthenticateApiKey,
    private val mapper: ObjectMapper,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        ProductRoutes.matched(request) ?: return true
        request.removeAttribute(AuthAttributes.WORKSPACE_ACTOR)
        val actor =
            if (ProductRoutes.isHuman(request)) {
                val credential = HumanSessionCookies.credential(request)
                val owner = humans.execute(credential?.value) ?: return reject(request, response, HttpStatus.UNAUTHORIZED, "unauthorized")
                if (request.method !in setOf("GET", "HEAD", "OPTIONS") &&
                    !HumanSessionCookies.csrfMatches(request, checkNotNull(credential))
                ) {
                    return reject(request, response, HttpStatus.FORBIDDEN, "invalid-csrf")
                }
                owner
            } else {
                // The early filter protects unknown /v1 paths. This second check covers
                // normalized paths that skipped that filter; never trust a human actor here.
                request.getAttribute(AuthAttributes.API_KEY) as? ApiKey
                    ?: bearerToken(request)?.let(keys::authenticate)
                    ?: return reject(request, response, HttpStatus.UNAUTHORIZED, "unauthorized")
            }
        request.setAttribute(AuthAttributes.WORKSPACE_ACTOR, actor)
        return true
    }

    private fun reject(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        type: String,
    ): Boolean {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        mapper.writeValue(response.outputStream, Problems.of(status, type, status.reasonPhrase, null, request))
        return false
    }
}

fun bearerToken(request: HttpServletRequest): String? =
    request
        .getHeaders("Authorization")
        .toList()
        .singleOrNull()
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
        ?.trim()

@Configuration
class WorkspaceAuthorityWebConfig(
    private val authority: WorkspaceAuthorityInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        // Account boundary (-200), authority (-100), workspace charge (0),
        // idempotency support (10), then handler/body conversion.
        registry.addInterceptor(authority).order(-100)
    }
}
