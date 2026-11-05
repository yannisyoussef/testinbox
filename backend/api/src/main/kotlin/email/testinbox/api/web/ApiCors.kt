package email.testinbox.api.web

import email.testinbox.api.config.TestInboxProperties
import email.testinbox.application.deployment.CorsOrigins
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsUtils
import org.springframework.web.cors.DefaultCorsProcessor
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * What a browser on an allowed origin may do with `/v1` (docs/api/principles.md #11).
 *
 * Every list is closed and derived from the contract, not from "whatever the
 * client sends": a header added to the API later is not readable cross-origin
 * until somebody adds it here on purpose.
 */
object ApiCors {
    /** The methods `/v1` serves. HEAD and OPTIONS need no grant; anything else is refused at preflight. */
    val ALLOWED_METHODS: List<String> = listOf("GET", "POST", "DELETE")

    /**
     * Request headers a legitimate `/v1` client sends. `Last-Event-ID` is the
     * event stream's resume token; `Accept` is safelisted but named so a
     * playground that sends `Accept: text/event-stream` never needs a second
     * thought. No cookie, no CSRF token: those belong to `/account`, which
     * never gets CORS.
     */
    val ALLOWED_HEADERS: List<String> =
        listOf(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ACCEPT,
            IdempotencyHeader.NAME,
            Correlation.HEADER,
            "Last-Event-ID",
        )

    /**
     * Every non-safelisted response header the contract documents on `/v1`.
     * (`Location` is not one: only the `/account` sign-in redirects send it.)
     * Without this a browser hides them from script, and a playground could
     * show the problem body of a `429` but not the `Retry-After` that says
     * when to come back.
     */
    val EXPOSED_HEADERS: List<String> =
        listOf(
            Correlation.HEADER,
            "X-API-Stability",
            "RateLimit-Limit",
            "RateLimit-Remaining",
            "RateLimit-Reset",
            HttpHeaders.RETRY_AFTER,
            IdempotencyHeader.REPLAYED,
            HttpHeaders.CONTENT_DISPOSITION,
            HttpHeaders.ALLOW,
        )

    /** How long a browser may cache a preflight. Short enough that removing an origin takes effect within minutes. */
    val MAX_AGE: Duration = Duration.ofMinutes(10)

    /** Null when [origins] is empty: CORS is off and no header is ever written. */
    fun configuration(origins: List<String>): CorsConfiguration? {
        CorsOrigins.requireValid(origins)
        if (origins.isEmpty()) return null
        return CorsConfiguration().apply {
            allowedOrigins = origins
            allowedMethods = ALLOWED_METHODS
            allowedHeaders = ALLOWED_HEADERS
            exposedHeaders = EXPOSED_HEADERS
            // Bearer keys only. Credentialed CORS would let an allowed page ride a
            // cookie, and nothing under /v1 should ever honour one.
            allowCredentials = false
            allowPrivateNetwork = false
            maxAge = MAX_AGE.toSeconds()
        }
    }

    /**
     * `/v1` by BOTH the raw URI and the servlet path, so a normalized spelling
     * (`/v1/../account/...`, `/v1;x/...`) can never extend CORS beyond `/v1`.
     * A spelling that fails this simply gets no CORS headers: the safe side.
     */
    fun inScope(request: HttpServletRequest): Boolean =
        request.servletPath.startsWith(PREFIX) && request.requestURI.removePrefix(request.contextPath).startsWith(PREFIX)

    private const val PREFIX = "/v1/"
}

/**
 * CORS for the public `/v1` API, and nothing else — never `/account`, never
 * actuator, never a page of the web console.
 *
 * Ordered directly after correlation and ahead of the schema gate,
 * authentication, rate limiting and idempotency: a preflight is answered here
 * and never travels further. It carries no credential, so it must not be
 * authenticated, charged to a workspace budget, counted as an auth failure or
 * see an idempotency record — and it cannot be, because none of those run.
 *
 * Actual requests from an allowed origin get `Access-Control-Allow-Origin`
 * and the exposed headers **before** the rest of the chain runs, so a `401`,
 * `404`, `429` or `503` problem written by a later filter or interceptor is
 * readable by the page. Authentication itself is unchanged: the reader's key
 * still has to be on the request.
 *
 * Requests from any other origin are left exactly as they were before CORS
 * existed — no headers, no refusal — except a preflight, which is refused
 * `403 cors-request-refused` (problem+json) with no allow headers. Refusing *actual*
 * requests by origin would break server-side clients that happen to send one
 * while protecting nothing: the browser is what enforces CORS.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class ApiCorsFilter(
    properties: TestInboxProperties,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val configuration: CorsConfiguration? = ApiCors.configuration(properties.cors.allowedOrigins)
    private val allowed: Set<String> = properties.cors.allowedOrigins.toSet()
    private val processor = DefaultCorsProcessor()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = configuration == null || !ApiCors.inScope(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val config = checkNotNull(configuration)
        val origin = request.getHeader(HttpHeaders.ORIGIN)
        if (CorsUtils.isPreFlightRequest(request)) {
            // Either way the request ends here: a preflight never reaches authentication.
            if (grantable(config, origin, request)) {
                processor.processRequest(config, request, response)
            } else {
                refuse(request, response)
            }
            return
        }
        if (origin in allowed && request.method in ApiCors.ALLOWED_METHODS) {
            // Origin and method are pre-checked, so this does not refuse; if it
            // ever did, the 403 is committed and no handler may run after it.
            if (!processor.processRequest(config, request, response)) return
        } else {
            // A shared cache must not hand an allowed origin's response to another.
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN)
        }
        filterChain.doFilter(request, response)
    }

    /**
     * Decided here, not by Spring's matcher, so the preflight and the actual
     * request agree. Spring compares origins ignoring case and a trailing
     * slash; a browser never sends either form, and granting a preflight the
     * actual request would then not match only produces a confusing failure.
     * Exact membership, the API's methods, and at least one grantable header
     * when any are requested (a mixed list is answered with the granted subset).
     */
    private fun grantable(
        config: CorsConfiguration,
        origin: String?,
        request: HttpServletRequest,
    ): Boolean {
        if (origin !in allowed) return false
        if (request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) !in ApiCors.ALLOWED_METHODS) return false
        val requested =
            request
                .getHeaders(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS)
                .toList()
                .flatMap { it.split(",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return requested.isEmpty() || config.checkHeaders(requested) != null
    }

    /** A static RFC 7807 refusal: no allow headers, and nothing from the request echoed but the correlation id. */
    private fun refuse(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN)
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        mapper.writeValue(
            response.outputStream,
            Problems.of(
                HttpStatus.FORBIDDEN,
                REFUSED_TYPE,
                "CORS request refused",
                "This origin, method or header is not allowed to call the API cross-origin",
                request,
            ),
        )
    }

    companion object {
        /** Documented in the contract's cross-cutting refusals (openapi.yaml `info.description`). */
        const val REFUSED_TYPE = "cors-request-refused"
    }
}
