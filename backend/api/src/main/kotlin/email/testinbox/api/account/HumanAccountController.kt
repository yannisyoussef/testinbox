package email.testinbox.api.account

import com.fasterxml.jackson.annotation.JsonInclude
import email.testinbox.api.web.Problems
import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.application.usecase.AuthenticateHumanSession
import email.testinbox.application.usecase.RevokeHumanSession
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/account")
class HumanAccountController(
    private val authenticate: AuthenticateHumanSession,
    private val revoke: RevokeHumanSession,
    private val admit: AdmitBetaParticipant,
    private val admissions: BetaAdmissions,
) {
    data class SessionView(
        val contactEmail: String,
        val expiresAt: Instant,
        val csrfToken: String,
        val admission: String,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val workspaceId: UUID? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val projectId: UUID? = null,
    )

    data class AdmissionView(
        val status: String,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val workspaceId: UUID? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val projectId: UUID? = null,
    )

    @GetMapping("/session")
    fun session(request: HttpServletRequest): ResponseEntity<*> {
        val credential = HumanSessionCookies.credential(request) ?: return unauthorized(request)
        val authenticated = authenticate.execute(credential.value) ?: return unauthorized(request)
        val membership = admissions.findAdmission(authenticated.account.id)
        return ResponseEntity.ok(
            SessionView(
                authenticated.account.contactEmail,
                authenticated.expiresAt,
                HumanSessionCookies.csrf(credential),
                if (membership == null) "NOT_ADMITTED" else "ADMITTED",
                membership?.workspaceId?.value,
                membership?.projectId?.value,
            ),
        )
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<*> {
        if (!emptyRequest(request)) return invalidRequest(request)
        val credential = HumanSessionCookies.credential(request)
        if (credential != null && authenticate.execute(credential.value) != null) {
            if (!HumanSessionCookies.csrfMatches(request, credential)) return forbidden(request)
            revoke.execute(credential.value)
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, HumanSessionCookies.clear()).build<Void>()
    }

    @PostMapping("/admission")
    fun admission(request: HttpServletRequest): ResponseEntity<*> {
        if (!emptyRequest(request)) return invalidRequest(request)
        val credential = HumanSessionCookies.credential(request) ?: return unauthorized(request)
        val authenticated = authenticate.execute(credential.value) ?: return unauthorized(request)
        if (!HumanSessionCookies.csrfMatches(request, credential)) return forbidden(request)
        val result =
            when (val admission = admit.execute(authenticated.account.id)) {
                is AdmitBetaParticipant.Result.Admitted -> {
                    AdmissionView(
                        "ADMITTED",
                        admission.admission.workspaceId.value,
                        admission.admission.projectId.value,
                    )
                }

                AdmitBetaParticipant.Result.Closed -> {
                    AdmissionView("CLOSED")
                }

                AdmitBetaParticipant.Result.Full -> {
                    AdmissionView("FULL")
                }

                AdmitBetaParticipant.Result.CapacityUnavailable -> {
                    AdmissionView("CAPACITY_UNAVAILABLE")
                }

                AdmitBetaParticipant.Result.Ineligible -> {
                    return unauthorized(request)
                }
            }
        return ResponseEntity.ok(result)
    }

    private fun emptyRequest(request: HttpServletRequest): Boolean =
        request.queryString.isNullOrEmpty() && request.contentLengthLong <= 0 && request.getHeader("Transfer-Encoding") == null

    private fun unauthorized(request: HttpServletRequest) =
        Problems.respond(Problems.of(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", null, request))

    private fun forbidden(request: HttpServletRequest) =
        Problems.respond(Problems.of(HttpStatus.FORBIDDEN, "invalid-csrf", "Forbidden", null, request))

    private fun invalidRequest(request: HttpServletRequest) =
        Problems.respond(Problems.of(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", null, request))
}
