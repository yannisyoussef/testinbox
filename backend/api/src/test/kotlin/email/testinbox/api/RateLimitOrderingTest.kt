package email.testinbox.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.api.web.IdempotencySupportInterceptor
import email.testinbox.api.web.RateLimitInterceptor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.TestPropertySource
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.util.UUID

/**
 * Charging happens before any other `/v1` interceptor may refuse a request
 * (ADR-027 §2, ADR-033 §10).
 *
 * The ordering is load-bearing rather than cosmetic. `IdempotencySupportInterceptor`
 * short-circuits with `400 idempotency-not-supported`, so if it ran first every
 * authenticated caller could spend nothing at all by attaching an
 * `Idempotency-Key` to any `/v1` route — the ADR-027 budget defeated by a
 * request header, on every endpoint at once.
 *
 * Both halves are needed. The chain assertion says what the wiring is, and
 * fails with a readable message; the budget assertion says what it *means*,
 * and is the one that would survive somebody replacing interceptors with
 * filters.
 *
 * Removing the explicit ordering fails this class — verified by mutation, with
 * both tests going red. Removing only *one* of the two mechanisms may not,
 * because component-scan order could still happen to be right on a given
 * machine; that is precisely why the ordering is stated explicitly rather than
 * left to it.
 */
@TestPropertySource(
    properties = [
        // A WAIT budget of two, refilling far too slowly to heal mid-test, so
        // the third request is decisive rather than timing-dependent.
        "testinbox.limits.wait.capacity=2",
        "testinbox.limits.wait.refill-per-second=0.01",
    ],
)
class RateLimitOrderingTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var handlerMapping: RequestMappingHandlerMapping

    @Test
    fun `the rate limiter precedes the idempotency-support interceptor in the resolved chain`() {
        val request = MockHttpServletRequest("POST", "/v1/inboxes")
        request.pathInfo = null
        val chain = handlerMapping.getHandler(request)!!
        val types = chain.interceptorList.map { it.javaClass.name }

        // Guard the guard: if neither interceptor is in the chain the ordering
        // assertion below is vacuously true and would pass wiring that is gone.
        val limiter = types.indexOf(RateLimitInterceptor::class.java.name)
        val idempotency = types.indexOf(IdempotencySupportInterceptor::class.java.name)
        (limiter >= 0) shouldBe true
        (idempotency >= 0) shouldBe true

        (limiter < idempotency) shouldBe true
    }

    @Test
    fun `a request refused for carrying an unsupported Idempotency-Key still spends its budget`() {
        val key = provisionIsolatedWorkspace("order-idem").apiKey
        // A WAIT route, which does not honour the header. The inbox need not
        // exist: both interceptors run before the controller, and the point is
        // which of them answers.
        val path = "/v1/inboxes/${UUID.randomUUID()}/messages/wait"

        val first = waitWithIdempotencyKey(path, key)
        val second = waitWithIdempotencyKey(path, key)
        val third = waitWithIdempotencyKey(path, key)

        // The first two are refused by the idempotency interceptor — but only
        // after the limiter has charged them.
        first.statusCode.value() shouldBe 400
        second.statusCode.value() shouldBe 400
        json.readTree(first.body)["type"].asText() shouldContain "idempotency-not-supported"

        // The budget is now empty, so the third never reaches the idempotency
        // interceptor at all. If the order were reversed this would be a third
        // `400` and the WAIT budget would be unspendable by any caller willing
        // to send a header.
        third.statusCode.value() shouldBe 429
        json.readTree(third.body)["type"].asText() shouldContain "rate-limit-exceeded"
    }

    private fun waitWithIdempotencyKey(
        path: String,
        key: String,
    ) = rest.exchange(
        url(path),
        HttpMethod.POST,
        HttpEntity(
            """{"timeout":"PT1S"}""",
            HttpHeaders().apply {
                setBearerAuth(key)
                set("Content-Type", "application/json")
                set("Idempotency-Key", "ordering-probe-${UUID.randomUUID()}")
            },
        ),
        String::class.java,
    )
}
