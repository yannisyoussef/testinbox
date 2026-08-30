package email.testinbox.api.web

import email.testinbox.api.auth.AuthAttributes
import email.testinbox.application.LimitsConfig
import email.testinbox.application.port.LimitMetrics
import email.testinbox.application.port.RateLimiter
import email.testinbox.domain.limits.RateCategory
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Maps a `/v1` request to the cost class it should be charged against
 * (ADR-027 §2).
 *
 * Classification is **default-deny**: an unmapped route is charged the most
 * restrictive category rather than passing unlimited. A route added later and
 * forgotten is therefore throttled, not free — and `RouteCoverageTest` fails
 * the build so the omission is noticed rather than shipped.
 */
object RateCategories {
    private val WAIT = Regex("^/v1/inboxes/[^/]+/messages/wait$")
    private val DOWNLOAD = Regex("^/v1/messages/[^/]+/(raw|attachments/[^/]+)$")
    private val INBOX_CREATE = Regex("^/v1/inboxes$")

    /** The category charged when nothing matches — deliberately the tightest. */
    val DEFAULT: RateCategory = RateCategory.INBOX_CREATE

    /**
     * Null means "not charged a token of its own". Cheap metadata reads are
     * deliberately uncharged: a bucket write per GET costs a row update on a
     * hot row, and these reads are already bounded by the workspace's inbox
     * and storage quotas. This is an explicit allow-list, so it can never be
     * reached by forgetting to classify a route.
     */
    fun of(
        method: String,
        path: String,
    ): RateCategory? =
        when {
            WAIT.matches(path) -> RateCategory.WAIT
            DOWNLOAD.matches(path) -> RateCategory.DOWNLOAD
            INBOX_CREATE.matches(path) && method.equals("POST", ignoreCase = true) -> RateCategory.INBOX_CREATE
            isCheapRead(method, path) -> null
            else -> DEFAULT
        }

    private fun isCheapRead(
        method: String,
        path: String,
    ): Boolean =
        (method.equals("GET", ignoreCase = true) || method.equals("DELETE", ignoreCase = true)) &&
            (
                Regex("^/v1/inboxes/[^/]+$").matches(path) ||
                    Regex("^/v1/inboxes/[^/]+/messages$").matches(path) ||
                    Regex("^/v1/messages/[^/]+$").matches(path) ||
                    Regex("^/v1/messages/[^/]+/attachments$").matches(path)
            )
}

/**
 * Charges the workspace's bucket before the handler runs and renders the
 * `RateLimit-*` headers on every governed response, so a client can pace
 * itself without provoking a rejection.
 *
 * The interceptor is a thin adapter: it maps endpoint → category and renders
 * HTTP. The decision itself belongs to the application layer, because the
 * independently deployed ingestion gateway needs the same limiter and an
 * HTTP filter could never protect it (ADR-024, ADR-027 §3).
 */
@Component
class RateLimitInterceptor(
    private val rateLimiter: RateLimiter,
    private val limits: LimitsConfig,
    private val metrics: LimitMetrics,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (!request.requestURI.startsWith("/v1/")) return true
        // Identity always comes from the authenticated key, never from a path
        // parameter, a body field, or any header (ADR-027 §3).
        val apiKey = request.getAttribute(AuthAttributes.API_KEY) ?: return true
        val workspaceId = (apiKey as email.testinbox.domain.tenant.ApiKey).workspaceId

        val category = RateCategories.of(request.method, request.requestURI) ?: return true
        val decision = rateLimiter.tryConsume(workspaceId, category)
        metrics.rateDecision(category, decision.allowed)

        response.setHeader("RateLimit-Limit", decision.limit.toString())
        response.setHeader("RateLimit-Remaining", decision.remaining.toString())
        response.setHeader(
            "RateLimit-Reset",
            (decision.retryAfter?.toSeconds() ?: limits.rateFor(category).timeToOneToken.toSeconds()).toString(),
        )
        if (decision.allowed) return true

        val seconds = maxOf(1L, decision.retryAfter?.toSeconds() ?: 1L)
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setHeader(HttpHeaders.RETRY_AFTER, seconds.toString())
        response.writer.write(
            """
            {"type":"https://testinbox.email/problems/rate-limit-exceeded",
            "title":"Rate limit exceeded","status":429,
            "detail":"Too many ${category.name} requests for this workspace",
            "category":"${category.name}","retryAfterSeconds":$seconds,
            "correlationId":"${Correlation.of(request)}"}
            """.trimIndent(),
        )
        return false
    }
}

@Component
class RateLimitWebConfig(
    private val interceptor: RateLimitInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/v1/**")
    }
}
