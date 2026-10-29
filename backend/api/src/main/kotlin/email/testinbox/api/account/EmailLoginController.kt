package email.testinbox.api.account

import email.testinbox.api.web.Problems
import email.testinbox.application.usecase.RequestEmailLogin
import email.testinbox.application.usecase.VerifyEmailLogin
import email.testinbox.domain.identity.ContactEmail
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/account/email")
class EmailLoginController(
    private val runtime: EmailAccountRuntime,
    private val clock: Clock,
) {
    data class ChallengeView(
        val challengeId: UUID,
        val expiresAt: Instant,
    )

    @PostMapping("/request", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun request(
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val flows = runtime.flows ?: return unavailable(request)
        val raw = body["email"] as? String ?: return invalid(request)
        val email = ContactEmail.parse(raw) ?: return invalid(request)
        if (body.keys != setOf("email") || EmailLoginCookies.ambiguous(request)) return invalid(request)
        // The deployment peer is authoritative. Arbitrary X-Forwarded-For is not.
        val result = flows.request.execute(email, request.remoteAddr, EmailLoginCookies.browser(request)?.value)
        return when (result) {
            is RequestEmailLogin.Result.Accepted -> {
                val lifetime = Duration.between(clock.instant(), result.expiresAt)
                val cookie = EmailLoginCookies.set(result.browser, lifetime)
                ResponseEntity.accepted().header(HttpHeaders.SET_COOKIE, cookie).body(ChallengeView(result.id, result.expiresAt))
            }

            is RequestEmailLogin.Result.Throttled -> {
                Problems.respond(
                    Problems.of(HttpStatus.TOO_MANY_REQUESTS, "account-rate-limit-exceeded", "Too many requests", null, request),
                    Duration.ofSeconds(maxOf(1, (result.retryAfter.toMillis() + 999) / 1000)),
                )
            }
        }
    }

    @PostMapping("/verify", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun verify(
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val flows = runtime.flows ?: return unavailable(request)
        if (body.keys != setOf("challengeId", "code") || EmailLoginCookies.ambiguous(request)) return invalid(request)
        val rawId = body["challengeId"] as? String ?: return invalid(request)
        val code = (body["code"] as? String)?.takeIf { it.length <= 64 } ?: return invalid(request)
        val id =
            runCatching { UUID.fromString(rawId) }.getOrNull()?.takeIf { it.toString().equals(rawId, ignoreCase = true) }
                ?: return invalid(request)
        val result =
            flows.verify.execute(
                id,
                EmailLoginCookies.browser(request)?.value,
                code,
                HumanSessionCookies.credential(request)?.value,
            )
        return when (result) {
            is VerifyEmailLogin.Result.Verified -> {
                val lifetime = Duration.between(clock.instant(), result.session.expiresAt)
                val sessionCookie = HumanSessionCookies.set(result.session.credential, lifetime)
                ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessionCookie, EmailLoginCookies.clear()).build<Void>()
            }

            VerifyEmailLogin.Result.Rejected -> {
                problem(request, HttpStatus.UNAUTHORIZED, "verification-failed")
            }

            VerifyEmailLogin.Result.DifferentMethod -> {
                problem(request, HttpStatus.CONFLICT, "different-signin-method")
            }

            VerifyEmailLogin.Result.CapacityUnavailable -> {
                unavailable(request)
            }
        }
    }

    private fun unavailable(request: HttpServletRequest) = problem(request, HttpStatus.SERVICE_UNAVAILABLE, "signin-unavailable")

    private fun invalid(request: HttpServletRequest) = problem(request, HttpStatus.BAD_REQUEST, "invalid-request")

    private fun problem(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
    ) = Problems.respond(Problems.of(status, type, status.reasonPhrase, null, request))
}
