package email.testinbox.api

import email.testinbox.api.web.RateCategories
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Every `/v1` route must be charged against some rate category (ADR-027 §4).
 *
 * This is the guard that makes "default-deny" real. Without it, an endpoint
 * added later and never classified would simply be unlimited — and an
 * uncharged read route in particular lets a caller refused a `WAIT` hot-poll
 * the message list instead, which would make the wait limits bound only the
 * well-behaved client.
 */
class RouteCoverageTest : ApiIntegrationTestBase() {
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var handlerMapping: RequestMappingHandlerMapping

    private fun v1Routes(): List<Pair<String, String>> =
        handlerMapping.handlerMethods.keys
            .flatMap { info ->
                val paths = info.pathPatternsCondition?.patternValues ?: emptySet()
                info.methodsCondition.methods.flatMap { method ->
                    paths.filter { it.startsWith("/v1/") }.map { method.name to it }
                }
            }

    /** Substitutes a concrete value for each `{placeholder}` so the classifier sees a real path. */
    private fun concrete(pattern: String): String = pattern.replace(Regex("\\{[^}]+}"), "11111111-1111-1111-1111-111111111111")

    @Test
    fun `every v1 route maps to a rate category`() {
        val routes = v1Routes()
        // Guard the guard: if route discovery silently returned nothing, this
        // test would pass while proving nothing.
        (routes.size >= 8) shouldBe true

        val unclassified =
            routes.filter { (method, pattern) ->
                // `of` is total, so an unmapped route falls through to DEFAULT.
                // What must never happen is a route reaching a *less* restrictive
                // class than intended, so assert each one is explicitly matched
                // by a rule rather than landing on the fallback by accident.
                RateCategories.of(method, concrete(pattern)) == RateCategories.DEFAULT &&
                    !(method == "POST" && pattern == "/v1/inboxes")
            }
        unclassified.shouldBeEmpty()
    }

    @Test
    fun `an unmapped route falls back to the most restrictive category`() {
        // Default-deny: a future endpoint that nobody classified is throttled,
        // not free.
        RateCategories.of("GET", "/v1/something-nobody-classified") shouldBe RateCategories.DEFAULT
        RateCategories.of("POST", "/v1/future/endpoint") shouldBe RateCategories.DEFAULT
    }
}
