package email.testinbox.api

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.swagger.v3.parser.OpenAPIV3Parser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.io.File

/**
 * ADR-022 conformance: the committed, hand-authored OpenAPI 3.1 contract is
 * the source of truth. This test fails when a contract operation has no
 * matching Spring handler, or when a /v1 or /account handler exists that the contract
 * does not document (implementation-only drift).
 */
class ContractCoverageTest : ApiIntegrationTestBase() {
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    lateinit var handlerMapping: RequestMappingHandlerMapping

    private val contract = File("contract/openapi.yaml")

    @Test
    fun `the contract parses as valid OpenAPI 3_1`() {
        val result = OpenAPIV3Parser().readLocation(contract.absolutePath, null, null)
        (result.messages ?: emptyList()).filter { !it.contains("unexpected") }.shouldBeEmpty()
        result.openAPI.openapi shouldBe "3.1.0"
        result.openAPI.paths.isNotEmpty() shouldBe true
    }

    @Test
    fun `every contract operation is implemented and every product handler is documented`() {
        val openApi = OpenAPIV3Parser().readLocation(contract.absolutePath, null, null).openAPI
        val contractOperations =
            openApi.paths
                .flatMap { (path, item) ->
                    item.readOperationsMap().flatMap { (method, operation) ->
                        val servers =
                            operation.servers?.takeIf { it.isNotEmpty() }
                                ?: item.servers?.takeIf { it.isNotEmpty() } ?: openApi.servers
                        check(!servers.isNullOrEmpty()) { "contract operation has no server: $method $path" }
                        servers.map { server ->
                            val prefix =
                                java.net
                                    .URI(server.url)
                                    .path
                                    .trimEnd('/')
                            "${method.name} $prefix${path.replace(Regex("\\{[^}]+}"), "{}")}"
                        }
                    }
                }.toSet()

        val implementedOperations =
            handlerMapping.handlerMethods
                .filterValues { it is HandlerMethod }
                .keys
                .flatMap { info ->
                    val paths = info.pathPatternsCondition?.patternValues ?: emptySet()
                    info.methodsCondition.methods.flatMap { method ->
                        paths
                            .filter { it.startsWith("/v1/") || it.startsWith("/account/") }
                            .map { "${method.name} ${it.replace(Regex("\\{[^}]+}"), "{}")}" }
                    }
                }.toSet()

        val unimplemented = contractOperations - implementedOperations
        val undocumented = implementedOperations - contractOperations
        check(unimplemented.isEmpty()) { "contract operations with no handler: $unimplemented" }
        check(undocumented.isEmpty()) { "handlers missing from the contract: $undocumented" }
    }
}
