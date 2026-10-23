package email.testinbox.api

import email.testinbox.api.web.IdempotentRoutes
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The contract and the code must agree on which operations honour
 * `Idempotency-Key`.
 *
 * They can drift in both directions and each is bad in its own way. A route
 * that honours it without declaring it is undocumented behaviour clients will
 * come to depend on; a route that declares it without honouring it is a
 * promise of retry safety that does not exist — and the client's SDK may retry
 * on that promise.
 */
class IdempotencyRouteTest {
    private val contract: String =
        Files.readString(Path.of("contract/openapi.yaml"))

    /** Operations whose OpenAPI block references the shared header parameter. */
    private fun declaredInContract(): Set<String> {
        val declared = mutableSetOf<String>()
        var currentPath: String? = null
        var currentMethod: String? = null
        contract.lines().forEach { line ->
            Regex("^  (/[^:]*):").find(line)?.let {
                currentPath = it.groupValues[1]
                currentMethod = null
            }
            Regex("^    (get|post|put|patch|delete):").find(line)?.let { currentMethod = it.groupValues[1] }
            if (line.contains("#/components/parameters/idempotencyKey")) {
                declared += "${currentMethod!!.uppercase()} /v1${currentPath!!}"
            }
        }
        return declared
    }

    @Test
    fun `the contract and the interceptor honour exactly the same operations`() {
        val declared = declaredInContract()
        // Guard the guard: a parsing change that found nothing would make every
        // assertion below vacuous.
        declared.isNotEmpty() shouldBe true

        val honoured =
            declared.filter { entry ->
                val (method, path) = entry.split(" ")
                IdempotentRoutes.supports(method, path)
            }
        honoured.size shouldBe declared.size

        // And nothing else honours it. Checked against the routes that exist
        // rather than a hardcoded list, so a new POST is covered automatically.
        listOf(
            "POST /v1/inboxes/11111111-1111-1111-1111-111111111111/messages/wait",
            "GET /v1/inboxes",
            "DELETE /v1/api-keys/11111111-1111-1111-1111-111111111111",
            "GET /v1/api-keys",
        ).filter { entry ->
            val (method, path) = entry.split(" ")
            IdempotentRoutes.supports(method, path)
        }.shouldBeEmpty()
    }

    @Test
    fun `the declared set is the two mutations ADR-033 names`() {
        declaredInContract() shouldBe setOf("POST /v1/inboxes", "POST /v1/api-keys")
    }
}
