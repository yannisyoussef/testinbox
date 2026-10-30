package email.testinbox.api.account

import email.testinbox.api.web.Problems
import email.testinbox.application.usecase.RecordProviderLoginState
import email.testinbox.application.usecase.SignInWithVerifiedProvider
import email.testinbox.domain.identity.LoginProvider
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Clock
import java.time.Duration

@RestController
@RequestMapping("/account/oauth")
class ProviderLoginController(
    private val runtime: ProviderAccountRuntime,
    private val properties: HumanAccountProperties,
    mapper: ObjectMapper,
    private val clock: Clock,
) {
    private val codec = ProviderAuthorizationCodec(mapper)

    @GetMapping("/authorize/{provider}")
    fun start(
        @PathVariable provider: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val flows = runtime.flows ?: return unavailable(request)
        val identityProvider = provider(provider) ?: return invalid(request)
        if (request.method != "GET" || !request.queryString.isNullOrEmpty() ||
            ProviderLoginCookies.ambiguous(request)
        ) {
            return invalid(request)
        }
        if (flows.protocols.registrations.findByRegistrationId(provider) == null) return unavailable(request)
        return try {
            val authorization =
                flows.protocols.resolver.resolve(CanonicalRequest(request, properties.canonicalOrigin!!), provider)
                    ?: return unavailable(request)
            val result =
                flows.record.execute(
                    identityProvider,
                    authorization.state!!,
                    codec.encode(authorization),
                    request.remoteAddr,
                    ProviderLoginCookies.browser(request)?.value,
                )
            when (result) {
                is RecordProviderLoginState.Result.Recorded -> {
                    runtime.observe(ProviderAccountEvent.STARTED)
                    redirect(authorization.authorizationRequestUri)
                        .header(HttpHeaders.SET_COOKIE, ProviderLoginCookies.set(result.browser))
                        .build<Void>()
                }

                RecordProviderLoginState.Result.Limited -> {
                    runtime.observe(ProviderAccountEvent.LIMITED)
                    Problems.respond(
                        Problems.of(HttpStatus.TOO_MANY_REQUESTS, "account-rate-limit-exceeded", "Too many requests", null, request),
                    )
                }
            }
        } catch (_: RuntimeException) {
            unavailable(request)
        }
    }

    @GetMapping("/callback/{provider}")
    fun callback(
        @PathVariable provider: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val flows = runtime.flows ?: return unavailable(request)
        val identityProvider = provider(provider) ?: return invalid(request)
        val registration = flows.protocols.registrations.findByRegistrationId(provider) ?: return unavailable(request)
        if (request.method != "GET" || !validCallback(request) || ProviderLoginCookies.ambiguous(request)) return invalid(request)
        val state = request.getParameter("state") ?: return invalid(request)
        var consumed = false
        return try {
            val raw =
                flows.consume.execute(identityProvider, state, ProviderLoginCookies.browser(request)?.value)
                    ?: return failed("verification-failed", false)
            consumed = true
            val authorization = codec.decode(raw, state, registration) ?: return failed("verification-failed", true)
            if (request.getParameter("error") != null) return failed("verification-failed", true)
            val response =
                OAuth2AuthorizationResponse
                    .success(requireNotNull(request.getParameter("code")))
                    .state(state)
                    .redirectUri(requireNotNull(registration.redirectUri))
                    .build()
            val identity = flows.protocols.authenticate(registration, authorization, response)
            when (val signedIn = flows.signIn.execute(identity, HumanSessionCookies.credential(request)?.value)) {
                is SignInWithVerifiedProvider.Result.SignedIn -> {
                    runtime.observe(ProviderAccountEvent.SIGNED_IN)
                    val lifetime = Duration.between(clock.instant(), signedIn.session.expiresAt)
                    val cookie = HumanSessionCookies.set(signedIn.session.credential, lifetime)
                    redirect("${properties.canonicalOrigin}/console")
                        .header(HttpHeaders.SET_COOKIE, cookie, ProviderLoginCookies.clear())
                        .build<Void>()
                }

                SignInWithVerifiedProvider.Result.DifferentMethod -> {
                    runtime.observe(ProviderAccountEvent.DIFFERENT_METHOD)
                    failed("different-signin-method", true)
                }

                SignInWithVerifiedProvider.Result.Rejected -> {
                    failed("verification-failed", true)
                }

                SignInWithVerifiedProvider.Result.CapacityUnavailable -> {
                    failed("signin-unavailable", true)
                }
            }
        } catch (_: RuntimeException) {
            // Never expose/log provider responses, codes, claims or token-bearing exceptions.
            failed("verification-failed", consumed)
        }
    }

    private fun validCallback(request: HttpServletRequest): Boolean {
        val parameters = request.parameterMap
        if ((request.queryString?.length ?: 0) > 4096 || parameters.values.any { it.size != 1 }) return false
        val allowed = setOf("code", "state", "error", "error_description", "error_uri", "scope", "authuser", "prompt", "hd", "iss")
        if (parameters.keys.any { it !in allowed }) return false
        val state = request.getParameter("state") ?: return false
        val code = request.getParameter("code")
        val error = request.getParameter("error")
        return state.length in 16..256 && ((code != null) xor (error != null)) &&
            (code == null || code.length in 1..2048) && (error == null || error.length in 1..128)
    }

    private fun failed(
        error: String,
        consumed: Boolean,
    ): ResponseEntity<Void> {
        runtime.observe(ProviderAccountEvent.REJECTED)
        val result = redirect("${properties.canonicalOrigin}/login?error=$error")
        if (consumed) result.header(HttpHeaders.SET_COOKIE, ProviderLoginCookies.clear())
        return result.build()
    }

    private fun provider(value: String): LoginProvider? =
        when (value) {
            "google" -> LoginProvider.GOOGLE
            "github" -> LoginProvider.GITHUB
            else -> null
        }

    private fun redirect(location: String): ResponseEntity.BodyBuilder =
        ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location)

    private fun invalid(request: HttpServletRequest) =
        Problems.respond(Problems.of(HttpStatus.BAD_REQUEST, "invalid-request", "Bad request", null, request))

    private fun unavailable(request: HttpServletRequest): ResponseEntity<*> {
        runtime.observe(ProviderAccountEvent.UNAVAILABLE)
        return Problems.respond(Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "signin-unavailable", "Service unavailable", null, request))
    }
}

/** The SDK only sees the reviewed public origin; proxy-supplied Host/Forwarded cannot rewrite callbacks. */
private class CanonicalRequest(
    request: HttpServletRequest,
    origin: String,
) : HttpServletRequestWrapper(request) {
    private val canonical = URI(origin)

    override fun getScheme(): String = "https"

    override fun getServerName(): String = canonical.host

    override fun getServerPort(): Int = if (canonical.port == -1) 443 else canonical.port

    override fun getRequestURL(): StringBuffer = StringBuffer("${canonical}$requestURI")
}
