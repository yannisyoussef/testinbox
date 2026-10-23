package email.testinbox.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.ObjectMapper

/**
 * A client's typo must not look like a server fault.
 *
 * Reported from staging: `GET /v1/inboxes` — a real route, wrong method —
 * answered `500` with "An unexpected error occurred" and logged a full ERROR
 * stack trace. Three separate problems in one: the contract says these are
 * `4xx`, every mistyped URL became log noise, and genuine `500`s were no
 * longer distinguishable from client mistakes in the one signal an operator
 * watches.
 *
 * The cause is a catch-all `@ExceptionHandler(Exception::class)`, which
 * outranks nothing but catches everything — including Spring's own framework
 * exceptions, which already carry the right status.
 */
class UnmatchedRouteTest : ApiIntegrationTestBase() {
    private val json = ObjectMapper()

    private fun problem(response: org.springframework.http.ResponseEntity<String>) = json.readTree(response.body!!)

    @Test
    fun `a wrong method on a real route is 405, not 500`() {
        val response = get("/v1/inboxes")
        response.statusCode shouldBe HttpStatus.METHOD_NOT_ALLOWED
        response.headers.contentType.toString() shouldContain "application/problem+json"
        problem(response)["type"].asString() shouldContain "method-not-allowed"
        // RFC 9110 requires it, and it is what tells the client what to do next.
        response.headers.getFirst("Allow")!! shouldContain "POST"
    }

    @Test
    fun `an unknown path under v1 is 404, not 500`() {
        val response = get("/v1/inboxen")
        response.statusCode shouldBe HttpStatus.NOT_FOUND
        problem(response)["type"].asString() shouldContain "not-found"
    }

    @Test
    fun `an unsupported content type is 415, not 500`() {
        val response =
            rest.exchange(
                url("/v1/inboxes"),
                org.springframework.http.HttpMethod.POST,
                org.springframework.http.HttpEntity(
                    "not json",
                    org.springframework.http.HttpHeaders().apply {
                        setBearerAuth(adminKey)
                        set("Content-Type", "text/plain")
                    },
                ),
                String::class.java,
            )
        response.statusCode shouldBe HttpStatus.UNSUPPORTED_MEDIA_TYPE
        problem(response)["type"].asString() shouldContain "unsupported-media-type"
    }

    @Test
    fun `every refusal still carries a correlation id and never leaks internals`() {
        listOf(get("/v1/inboxes"), get("/v1/inboxen")).forEach { response ->
            val body = problem(response)
            body["correlationId"].asString().isNotBlank() shouldBe true
            // No exception class names, no stack frames, no Spring internals.
            response.body!!.contains("Exception") shouldBe false
            response.body!!.contains("org.springframework") shouldBe false
        }
    }

    @Test
    fun `authentication still runs first, so an unknown path does not disclose that it is unknown`() {
        // Without a credential the answer must be 401 for a real route and an
        // imaginary one alike, or the 404/405 split becomes a map of the API
        // for an unauthenticated caller.
        get("/v1/inboxen", key = null).statusCode shouldBe HttpStatus.UNAUTHORIZED
        get("/v1/inboxes", key = null).statusCode shouldBe HttpStatus.UNAUTHORIZED
    }
}
