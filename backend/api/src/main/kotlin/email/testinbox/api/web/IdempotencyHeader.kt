package email.testinbox.api.web

import email.testinbox.application.idempotency.IdempotencyRequest
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.idempotency.IdempotencyKey
import jakarta.servlet.http.HttpServletRequest

/**
 * Reads `Idempotency-Key` off a request (ADR-033).
 *
 * Everything here is about not saying too much. The key is customer-generated
 * and may carry their identifiers, so a refusal reports the *constraint* and
 * never the value: interpolating the rejected key would put it in the problem
 * body, our access log and the client's CI log at once.
 */
object IdempotencyHeader {
    const val NAME = "Idempotency-Key"
    const val REPLAYED = "Idempotency-Replayed"

    sealed interface Outcome {
        data object Absent : Outcome

        data class Present(
            val request: IdempotencyRequest,
        ) : Outcome

        data class Invalid(
            val reason: String,
        ) : Outcome
    }

    fun read(
        request: HttpServletRequest,
        actorApiKeyId: ApiKeyId?,
    ): Outcome {
        val values = request.getHeaders(NAME)?.toList().orEmpty()
        if (values.isEmpty()) return Outcome.Absent
        if (values.size > 1) {
            // `getHeader` would silently take the first. An intermediary may
            // also fold duplicates into `a,b`, after which the edge and the
            // application disagree about request identity — cheap to close
            // here, ugly to debug later.
            return Outcome.Invalid("$NAME must not be repeated")
        }
        val key =
            IdempotencyKey.of(values.single())
                ?: return Outcome.Invalid(
                    "$NAME must be ${IdempotencyKey.MIN_LENGTH}-${IdempotencyKey.MAX_LENGTH} printable " +
                        "ASCII characters with no spaces",
                )
        return Outcome.Present(IdempotencyRequest(key, actorApiKeyId))
    }
}

/**
 * Where `Idempotency-Key` is honoured (ADR-033 §1).
 *
 * Enumerated centrally rather than checked per controller, because the failure
 * of forgetting is silent: a route that accepted and ignored the header would
 * grant a client retry protection it does not have, and the client's SDK may
 * then retry on that belief. `IdempotencyRouteTest` holds this list to the
 * operations the contract declares.
 */
object IdempotentRoutes {
    private val SUPPORTED =
        listOf(
            "POST" to Regex("^/v1/inboxes$"),
            "POST" to Regex("^/v1/api-keys$"),
        )

    fun supports(
        method: String,
        path: String,
    ): Boolean = SUPPORTED.any { (m, p) -> m.equals(method, ignoreCase = true) && p.matches(path) }
}
