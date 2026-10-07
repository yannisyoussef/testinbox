package email.testinbox.api.storage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.api.ApiIntegrationTestBase
import email.testinbox.domain.InboxId
import email.testinbox.domain.storage.StorageRefusalReason
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.swagger.v3.parser.OpenAPIV3Parser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.File
import java.util.UUID

/**
 * ADR-035 §13d, structurally (TI-STORAGE-004 §48): no authenticated tenant
 * response introduced by this slice carries a global storage figure, and a
 * `SERVICE_CAPACITY` refusal is one bit. The members must be ABSENT, so the
 * assertions are on property names, never on values being zero.
 */
class StorageDisclosureTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    @Autowired lateinit var guardedRefusals: GuardedRefusals

    /**
     * Names and name fragments that would betray a global, node or reservation-level
     * figure. `reservedBytes` (the caller's own scope) is the one legitimate
     * `reserv` member, so it is allowed by exact name.
     */
    private val forbiddenExact =
        setOf(
            "g",
            "h",
            "G",
            "H",
            "globalLimitBytes",
            "globalStoredBytes",
            "globalReservedBytes",
            "globalAvailableBytes",
            "finalizeBudgetBytes",
        )
    private val forbiddenFragments =
        listOf("global", "finalize", "admissioncap", "node", "generation", "backlog", "ambigu", "capacitybytes", "committed")
    private val allowed = setOf("reservedBytes")

    private fun propertyNames(node: JsonNode): List<String> =
        when {
            node.isObject -> {
                node
                    .fields()
                    .asSequence()
                    .flatMap { (name, child) ->
                        sequenceOf(name) + propertyNames(child).asSequence()
                    }.toList()
            }

            node.isArray -> {
                node.flatMap { propertyNames(it) }
            }

            else -> {
                emptyList()
            }
        }

    private fun offenders(names: Collection<String>): List<String> =
        names.filter { name ->
            name !in allowed &&
                (
                    name in forbiddenExact ||
                        forbiddenFragments.any { fragment -> name.lowercase().contains(fragment) } ||
                        name.lowercase().contains("reservation")
                )
        }

    @Test
    fun `no tenant response of this slice names a global, node or reservation-level figure`() {
        val tenant = provisionIsolatedWorkspace("disclosure")
        val created = json.readTree(post("/v1/inboxes", """{}""", tenant.apiKey).body)
        val inbox = InboxId(UUID.fromString(created["id"].asText()))
        appendVisibleMessage(inbox, created["address"].asText(), workspaceId = tenant.workspaceId)
        val responses = mutableMapOf<String, JsonNode>()
        responses["create"] = created
        responses["get"] = json.readTree(get("/v1/inboxes/${inbox.value}", tenant.apiKey).body)
        responses["workspace"] = json.readTree(get("/v1/workspace/storage", tenant.apiKey).body)
        responses["matched"] =
            json.readTree(
                post(
                    "/v1/inboxes/${inbox.value}/messages/wait",
                    """{"timeoutSeconds":1,"afterStorageRefusalCount":0}""",
                    tenant.apiKey,
                ).body,
            )
        responses["timeout"] =
            json.readTree(
                post(
                    "/v1/inboxes/${inbox.value}/messages/wait",
                    """{"timeoutSeconds":1,"matcher":{"subjectContains":"nope"}}""",
                    tenant.apiKey,
                ).body,
            )
        for (reason in StorageRefusalReason.entries) {
            val refused = InboxId(UUID.fromString(json.readTree(post("/v1/inboxes", """{}""", tenant.apiKey).body)["id"].asText()))
            guardedRefusals.refuse(tenant.workspaceId, refused, reason)
            val response =
                post("/v1/inboxes/${refused.value}/messages/wait", """{"timeoutSeconds":1,"afterStorageRefusalCount":0}""", tenant.apiKey)
            response.statusCode.value() shouldBe 409
            responses["409-$reason"] = json.readTree(response.body)
        }

        responses.forEach { (label, body) ->
            withClue("$label: ${propertyNames(body)}") { offenders(propertyNames(body)).shouldBeEmpty() }
        }
        // Guard the guard: the walk sees nested members (it would catch a global member inside `storage`).
        propertyNames(responses.getValue("create")) shouldBe
            propertyNames(responses.getValue("create")).also { names ->
                (names.contains("storage") && names.contains("limitBytes")) shouldBe
                    true
            }
        offenders(listOf("globalLimitBytes", "G", "finalizeBudgetBytes", "nodeId", "reservationBacklog")).size shouldBe 5
    }

    @Test
    fun `a SERVICE_CAPACITY 409 carries the reason and no quota, limit or current member at all`() {
        val tenant = provisionIsolatedWorkspace("disclosure-sc")
        val inbox = InboxId(UUID.fromString(json.readTree(post("/v1/inboxes", """{}""", tenant.apiKey).body)["id"].asText()))
        guardedRefusals.refuse(tenant.workspaceId, inbox, StorageRefusalReason.SERVICE_CAPACITY)
        val response =
            post("/v1/inboxes/${inbox.value}/messages/wait", """{"timeoutSeconds":1,"afterStorageRefusalCount":0}""", tenant.apiKey)
        response.statusCode.value() shouldBe 409
        response.headers.getFirst("Retry-After") shouldBe null
        val problem = json.readTree(response.body)
        problem["refusalReason"].asText() shouldBe "SERVICE_CAPACITY"
        problem["storageRefusalCount"].asLong() shouldBe 1
        for (member in listOf("quota", "limit", "current", "retryAfterSeconds")) {
            withClue("$member must be absent, not null or zero") { problem.has(member) shouldBe false }
        }
        propertyNames(problem).toSet() shouldBe
            setOf(
                "type",
                "title",
                "status",
                "detail",
                "instance",
                "correlationId",
                "inboxId",
                "refusalReason",
                "afterStorageRefusalCount",
                "storageRefusalCount",
                "lastStorageRefusalAt",
            ).filter { it != "instance" || problem.has("instance") }
                .toSet()
    }

    @Test
    fun `the contract's tenant schemas declare no global figure either`() {
        val openApi = OpenAPIV3Parser().readLocation(File("contract/openapi.yaml").absolutePath, null, null).openAPI
        for (schema in listOf("StorageUsage", "Inbox", "WaitResult", "Problem", "WaitRequest")) {
            val names =
                openApi.components.schemas
                    .getValue(schema)
                    .properties.keys
            withClue("$schema: $names") { offenders(names).shouldBeEmpty() }
        }
        openApi.components.schemas
            .getValue("StorageUsage")
            .properties.keys shouldBe
            setOf("limitBytes", "storedBytes", "reservedBytes", "availableBytes", "overLimit")
    }
}
