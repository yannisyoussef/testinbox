package email.testinbox.api.account

import com.fasterxml.jackson.annotation.JsonInclude
import email.testinbox.api.web.Problems
import email.testinbox.application.usecase.AuthenticateHumanSession
import email.testinbox.application.usecase.ManageBetaWaitlist
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/account/waitlist")
class BetaWaitlistController(
    private val authenticate: AuthenticateHumanSession,
    private val manage: ManageBetaWaitlist,
    private val properties: BetaWaitlistProperties,
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class View(
        val status: String,
        val joinedAt: Instant? = null,
        val expiresAt: Instant? = null,
    )

    @GetMapping
    fun read(request: HttpServletRequest): ResponseEntity<*> = respond(request, ManageBetaWaitlist.Action.READ)

    @PostMapping
    fun join(request: HttpServletRequest): ResponseEntity<*> = respond(request, ManageBetaWaitlist.Action.JOIN)

    @DeleteMapping
    fun withdraw(request: HttpServletRequest): ResponseEntity<*> = respond(request, ManageBetaWaitlist.Action.WITHDRAW)

    private fun respond(
        request: HttpServletRequest,
        action: ManageBetaWaitlist.Action,
    ): ResponseEntity<*> {
        if (!request.queryString.isNullOrEmpty() || request.contentLengthLong > 0 || request.getHeader("Transfer-Encoding") != null) {
            return problem(request, HttpStatus.BAD_REQUEST, "invalid-request")
        }
        val credential = HumanSessionCookies.credential(request) ?: return problem(request, HttpStatus.UNAUTHORIZED, "unauthorized")
        val actor = authenticate.execute(credential.value) ?: return problem(request, HttpStatus.UNAUTHORIZED, "unauthorized")
        if (action != ManageBetaWaitlist.Action.READ && !HumanSessionCookies.csrfMatches(request, credential)) {
            return problem(request, HttpStatus.FORBIDDEN, "invalid-csrf")
        }
        return when (val result = manage.execute(actor.account.id, action, properties.policy)) {
            is ManageBetaWaitlist.Result.Waiting -> {
                ResponseEntity.ok(View("WAITING", result.entry.joinedAt, result.entry.expiresAt))
            }

            ManageBetaWaitlist.Result.Admitted -> {
                ResponseEntity.ok(View("ADMITTED"))
            }

            ManageBetaWaitlist.Result.NotWaiting -> {
                if (action == ManageBetaWaitlist.Action.WITHDRAW) {
                    ResponseEntity.noContent().build<Void>()
                } else {
                    ResponseEntity.ok(View("NOT_WAITING"))
                }
            }

            ManageBetaWaitlist.Result.Unavailable -> {
                ResponseEntity.ok(View("UNAVAILABLE"))
            }

            ManageBetaWaitlist.Result.Ineligible -> {
                problem(request, HttpStatus.UNAUTHORIZED, "unauthorized")
            }
        }
    }

    private fun problem(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
    ): ResponseEntity<*> = Problems.respond(Problems.of(status, type, status.reasonPhrase, null, request))
}
