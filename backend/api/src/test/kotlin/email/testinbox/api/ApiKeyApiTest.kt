package email.testinbox.api

import email.testinbox.api.web.RateCategories
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.tenant.ApiKeyFormat
import email.testinbox.domain.tenant.ApiScope
import email.testinbox.domain.tenant.ParsedCredential
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

class ApiKeyApiTest : ApiIntegrationTestBase() {
    private val json = ObjectMapper()

    private fun body(response: org.springframework.http.ResponseEntity<String>): JsonNode = json.readTree(response.body!!)

    private fun createKey(
        payload: String,
        key: String? = adminKey,
    ) = post("/v1/api-keys", payload, key)

    @Test
    fun `creation returns the credential once and no read ever returns it again`() {
        val created = createKey("""{"name":"github-ci","scopes":["inboxes:write"]}""")
        created.statusCode shouldBe HttpStatus.CREATED
        val node = body(created)
        val plaintext = node["key"].asString()
        val id = node["apiKey"]["id"].asString()

        ApiKeyFormat.parse(plaintext).shouldBeInstanceOf<ParsedCredential.Valid>()
        node["apiKey"]["publicId"].asString() shouldBe plaintext.split('_')[2]

        // Every subsequent representation of the same key.
        val fetched = get("/v1/api-keys/$id")
        fetched.statusCode shouldBe HttpStatus.OK
        fetched.body!!.contains(plaintext) shouldBe false
        fetched.body!!.contains(plaintext.split('_')[3]) shouldBe false

        val listed = get("/v1/api-keys")
        listed.body!!.contains(plaintext) shouldBe false
        listed.body!!.contains(plaintext.split('_')[3]) shouldBe false
    }

    @Test
    fun `a metadata response carries no field that could hold secret material`() {
        val created = createKey("""{"name":"probe","scopes":["messages:read"]}""")
        val id = body(created)["apiKey"]["id"].asString()
        val metadata = body(get("/v1/api-keys/$id"))

        // The whole field set is asserted, not a deny-list of names: a new
        // secret-bearing field would then show up here as an unexpected key.
        metadata.propertyNames().toSet() shouldBe
            setOf(
                "id",
                "publicId",
                "name",
                "scopes",
                "createdAt",
                "expiresAt",
                "revokedAt",
                "lastUsedAt",
                "createdByApiKeyId",
            )
    }

    @Test
    fun `a newly created key works immediately and carries only the requested scopes`() {
        val plaintext = body(createKey("""{"name":"ci","scopes":["inboxes:write"]}"""))["key"].asString()

        post("/v1/inboxes", "{}", plaintext).statusCode shouldBe HttpStatus.CREATED
        // Least privilege: no messages:read, so a read is forbidden.
        val inboxId = body(post("/v1/inboxes", "{}", plaintext))["id"].asString()
        get("/v1/inboxes/$inboxId/messages", plaintext).statusCode shouldBe HttpStatus.FORBIDDEN
    }

    @Test
    fun `revocation takes effect on the very next request`() {
        val created = body(createKey("""{"name":"doomed","scopes":["inboxes:write"]}"""))
        val plaintext = created["key"].asString()
        val id = created["apiKey"]["id"].asString()

        post("/v1/inboxes", "{}", plaintext).statusCode shouldBe HttpStatus.CREATED
        delete("/v1/api-keys/$id").statusCode shouldBe HttpStatus.NO_CONTENT
        // No cache to wait out, no invalidation step (ADR-032 §5).
        post("/v1/inboxes", "{}", plaintext).statusCode shouldBe HttpStatus.UNAUTHORIZED
    }

    @Test
    fun `revocation is idempotent and the metadata records when it happened`() {
        val created = body(createKey("""{"name":"twice","scopes":["messages:read"]}"""))
        val id = created["apiKey"]["id"].asString()

        delete("/v1/api-keys/$id").statusCode shouldBe HttpStatus.NO_CONTENT
        // A retry after a network blip must not be reported as a failure.
        delete("/v1/api-keys/$id").statusCode shouldBe HttpStatus.NO_CONTENT
        body(get("/v1/api-keys/$id"))["revokedAt"].isNull shouldBe false
    }

    @Test
    fun `rotation works with no window in which neither key is valid`() {
        val original = body(createKey("""{"name":"rotating","scopes":["inboxes:write"]}"""))
        val originalKey = original["key"].asString()
        val originalId = original["apiKey"]["id"].asString()
        post("/v1/inboxes", "{}", originalKey).statusCode shouldBe HttpStatus.CREATED

        // 1. mint the replacement — both are live.
        val replacementKey = body(createKey("""{"name":"rotating-new","scopes":["inboxes:write"]}"""))["key"].asString()
        post("/v1/inboxes", "{}", originalKey).statusCode shouldBe HttpStatus.CREATED
        post("/v1/inboxes", "{}", replacementKey).statusCode shouldBe HttpStatus.CREATED

        // 2. retire the original — only then.
        delete("/v1/api-keys/$originalId").statusCode shouldBe HttpStatus.NO_CONTENT
        post("/v1/inboxes", "{}", originalKey).statusCode shouldBe HttpStatus.UNAUTHORIZED
        post("/v1/inboxes", "{}", replacementKey).statusCode shouldBe HttpStatus.CREATED
    }

    @Test
    fun `an ordinary CI key cannot manage credentials`() {
        val ciKey = body(createKey("""{"name":"ci-only","scopes":["inboxes:write","messages:read"]}"""))["key"].asString()

        // The entire point of a separate scope (ADR-032 §10).
        createKey("""{"scopes":["inboxes:write"]}""", ciKey).statusCode shouldBe HttpStatus.FORBIDDEN
        get("/v1/api-keys", ciKey).statusCode shouldBe HttpStatus.FORBIDDEN
        delete("/v1/api-keys/${UUID.randomUUID()}", ciKey).statusCode shouldBe HttpStatus.FORBIDDEN
    }

    @Test
    fun `a manager cannot mint a key more powerful than itself`() {
        val managerOnly = body(createKey("""{"name":"manager","scopes":["api-keys:manage"]}"""))["key"].asString()
        val refused = createKey("""{"scopes":["inboxes:write"]}""", managerOnly)
        refused.statusCode shouldBe HttpStatus.FORBIDDEN
        // Distinguished from a plain missing-scope refusal so the caller can
        // tell "you may not manage keys" from "you may not grant that".
        body(refused)["type"].asString() shouldContain "scope-escalation"

        // A subset of its own scopes is fine.
        createKey("""{"scopes":["api-keys:manage"]}""", managerOnly).statusCode shouldBe HttpStatus.CREATED
    }

    @Test
    fun `an unknown scope is a 400, not a silently ignored field`() {
        val refused = createKey("""{"scopes":["inboxes:write","inboxes:delete"]}""")
        refused.statusCode shouldBe HttpStatus.BAD_REQUEST
        body(refused)["detail"].asString() shouldContain "inboxes:delete"

        createKey("""{"scopes":[]}""").statusCode shouldBe HttpStatus.BAD_REQUEST
        createKey("""{"name":"no scopes"}""").statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `another workspace's key is a 404, never a 403`() {
        val theirs = body(createKey("""{"scopes":["messages:read"]}"""))["apiKey"]["id"].asString()
        val otherTenant = provisionIsolatedWorkspace("keyprobe")
        val theirAdmin =
            mintKey(otherTenant.workspaceId, otherTenant.projectId, setOf(ApiScope.API_KEYS_MANAGE))

        // 403 would confirm the key exists; anti-enumeration requires the two
        // cases be indistinguishable.
        get("/v1/api-keys/$theirs", theirAdmin).statusCode shouldBe HttpStatus.NOT_FOUND
        get("/v1/api-keys/${UUID.randomUUID()}", theirAdmin).statusCode shouldBe HttpStatus.NOT_FOUND
        delete("/v1/api-keys/$theirs", theirAdmin).statusCode shouldBe HttpStatus.NOT_FOUND
        // And it is genuinely untouched.
        body(get("/v1/api-keys/$theirs"))["revokedAt"].isNull shouldBe true
    }

    @Test
    fun `listing is workspace-scoped and pages`() {
        val tenant = provisionIsolatedWorkspace("keylist")
        val admin = mintKey(tenant.workspaceId, tenant.projectId, ApiScope.entries.toSet())
        // Asserted, not fire-and-forget: a silently refused setup call would
        // leave this test passing against the wrong number of rows.
        repeat(3) {
            createKey("""{"name":"k$it","scopes":["messages:read"]}""", admin).statusCode shouldBe HttpStatus.CREATED
        }

        // Walk the pages the way a client does: follow nextCursor until it is
        // absent. Asserting a fixed page count instead would break the moment
        // the fixture mints one more key.
        val ids = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val query = if (cursor == null) "?limit=2" else "?limit=2&cursor=$cursor"
            val page = body(get("/v1/api-keys$query", admin))
            // toList() first: Jackson 3's JsonNode has its own `map` member,
            // which shadows Kotlin's Iterable.map and does something else
            // entirely with an array node.
            ids += page["items"].toList().map { it["id"].asString() }
            cursor = page["nextCursor"]?.takeUnless { it.isNull }?.asString()
            pages++
        } while (cursor != null && pages < 10)

        // Three minted plus the tenant's two fixture keys, each exactly once.
        ids shouldHaveSize 5
        ids.toSet() shouldHaveSize 5
        // And nothing belonging to another workspace came back.
        get("/v1/api-keys", adminKey).body!!.let { mine ->
            ids.none { mine.contains(it) } shouldBe true
        }
    }

    @Test
    fun `a malformed cursor is a 400, not a stack trace`() {
        get("/v1/api-keys?cursor=not-a-cursor").statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `an expired key stops authenticating`() {
        val tenant = provisionIsolatedWorkspace("keyexpiry")
        val expired =
            mintKey(
                tenant.workspaceId,
                tenant.projectId,
                setOf(ApiScope.INBOXES_WRITE),
                expiresAt =
                    java.time.Instant
                        .now()
                        .minusSeconds(5),
            )
        post("/v1/inboxes", "{}", expired).statusCode shouldBe HttpStatus.UNAUTHORIZED
    }

    @Test
    fun `an implausibly short lifetime is refused at creation`() {
        createKey("""{"scopes":["messages:read"],"expiresInSeconds":5}""").statusCode shouldBe HttpStatus.BAD_REQUEST
        createKey("""{"scopes":["messages:read"],"expiresInSeconds":3600}""").statusCode shouldBe HttpStatus.CREATED
    }

    @Test
    fun `a truncated or forged credential is refused without disclosing which`() {
        val plaintext = body(createKey("""{"scopes":["messages:read"]}"""))["key"].asString()
        val truncated = plaintext.dropLast(5)
        val forged = ApiKeyFormat.render(plaintext.split('_')[2], ApiKeyFormat.generate().secret)

        listOf(truncated, forged, "ti_k1_garbage", "not-a-key-at-all").forEach { candidate ->
            val response = get("/v1/api-keys", candidate)
            response.statusCode shouldBe HttpStatus.UNAUTHORIZED
            // One answer for every failure: the client learns nothing about
            // whether the public id existed or the secret was wrong.
            body(response)["type"].asString() shouldContain "unauthorized"
        }
    }

    @Test
    fun `lastUsedAt becomes visible after the key is used`() {
        val created = body(createKey("""{"name":"tracked","scopes":["inboxes:write"]}"""))
        val plaintext = created["key"].asString()
        val id = created["apiKey"]["id"].asString()
        body(get("/v1/api-keys/$id"))["lastUsedAt"].isNull shouldBe true

        post("/v1/inboxes", "{}", plaintext).statusCode shouldBe HttpStatus.CREATED
        body(get("/v1/api-keys/$id"))["lastUsedAt"].isNull shouldBe false
    }

    @Test
    fun `key management is charged against its own rate category`() {
        // The category name is reported to the client in the 429 body, so
        // borrowing INBOX_CREATE would put a plain untruth in an error message.
        // Asserting only that a RateLimit-Limit header exists was true of every
        // category — remapping the route to DOWNLOAD left that version green.
        RateCategories.of("POST", "/v1/api-keys") shouldBe RateCategory.KEY_ADMIN
        RateCategories.of("GET", "/v1/api-keys") shouldBe RateCategory.KEY_ADMIN
        RateCategories.of(
            "DELETE",
            "/v1/api-keys/11111111-1111-1111-1111-111111111111",
        ) shouldBe RateCategory.KEY_ADMIN

        createKey("""{"scopes":["messages:read"]}""").headers.getFirst("RateLimit-Limit").shouldBeInstanceOf<String>()
    }
}
