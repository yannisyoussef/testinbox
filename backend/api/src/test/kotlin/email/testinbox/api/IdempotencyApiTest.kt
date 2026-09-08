package email.testinbox.api

import email.testinbox.domain.tenant.ApiScope
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-033 through the real HTTP surface.
 *
 * The scenario the feature exists for cannot be staged directly — a response
 * that vanishes in transit is indistinguishable, from the client's side, from
 * sending the same request twice. So that is what these do.
 */
class IdempotencyApiTest : ApiIntegrationTestBase() {
    private val json = ObjectMapper()

    private fun key(): String = "test-${UUID.randomUUID()}"

    private fun postWithKey(
        path: String,
        body: String,
        idempotencyKey: String?,
        apiKey: String? = adminKey,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                apiKey?.let { setBearerAuth(it) }
                set("Content-Type", "application/json")
                idempotencyKey?.let { set("Idempotency-Key", it) }
            }
        return rest.exchange(url(path), HttpMethod.POST, HttpEntity(body, headers), String::class.java)
    }

    @Test
    fun `a retry of the same request returns the same inbox and creates nothing new`() {
        val k = key()
        val first = postWithKey("/v1/inboxes", """{"ttlSeconds":600}""", k)
        first.statusCode shouldBe HttpStatus.CREATED
        first.headers.getFirst("Idempotency-Replayed") shouldBe "false"

        val retry = postWithKey("/v1/inboxes", """{"ttlSeconds":600}""", k)
        retry.statusCode shouldBe HttpStatus.CREATED
        // Same status either way: the resource exists because of this logical
        // request, which is what 201 describes. The header is the signal.
        retry.headers.getFirst("Idempotency-Replayed") shouldBe "true"

        val firstBody = json.readTree(first.body!!)
        val retryBody = json.readTree(retry.body!!)
        retryBody["id"].asString() shouldBe firstBody["id"].asString()
        retryBody["address"].asString() shouldBe firstBody["address"].asString()
        retryBody["expiresAt"].asString() shouldBe firstBody["expiresAt"].asString()
    }

    @Test
    fun `the same key with a changed request is a terminal conflict, and executes nothing`() {
        val k = key()
        val created = postWithKey("/v1/inboxes", """{"ttlSeconds":600}""", k)
        created.statusCode shouldBe HttpStatus.CREATED

        val changed = postWithKey("/v1/inboxes", """{"ttlSeconds":900}""", k)
        changed.statusCode shouldBe HttpStatus.CONFLICT
        val problem = json.readTree(changed.body!!)
        problem["type"].asString() shouldContain "idempotency-key-reused"
        // Terminal: retrying with this key cannot succeed, so no Retry-After.
        changed.headers.getFirst("Retry-After") shouldBe null
        // And it must not leak the original request back to the caller.
        changed.body!!.contains("600") shouldBe false
    }

    @Test
    fun `the conflict never echoes the key the client sent`() {
        val k = "customer-identifier-${UUID.randomUUID()}"
        postWithKey("/v1/inboxes", """{"ttlSeconds":600}""", k)
        val conflict = postWithKey("/v1/inboxes", """{"ttlSeconds":900}""", k)
        // The key is customer-generated; echoing it would put it in the problem
        // body, our access log and the client's CI log at once.
        conflict.body!!.contains(k) shouldBe false
    }

    @Test
    fun `a malformed key is refused by constraint, never by quoting it back`() {
        val tooShort = "short"
        val refused = postWithKey("/v1/inboxes", """{"ttlSeconds":600}""", tooShort)
        refused.statusCode shouldBe HttpStatus.BAD_REQUEST
        val body = refused.body!!
        body shouldContain "Idempotency-Key"
        body.contains(tooShort) shouldBe false
    }

    @Test
    fun `a repeated header is refused rather than silently taking the first`() {
        val headers =
            HttpHeaders().apply {
                setBearerAuth(adminKey)
                set("Content-Type", "application/json")
                add("Idempotency-Key", "first-key-value-aaaaaa")
                add("Idempotency-Key", "second-key-value-bbbbb")
            }
        val response =
            rest.exchange(
                url("/v1/inboxes"),
                HttpMethod.POST,
                HttpEntity("""{"ttlSeconds":600}""", headers),
                String::class.java,
            )
        // An intermediary may fold duplicates, after which the edge and the
        // application would disagree about request identity.
        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body!! shouldContain "must not be repeated"
    }

    @Test
    fun `a refused request does not bind the key, so a corrected retry succeeds`() {
        val k = key()
        // ttlSeconds beyond the deployment maximum is a validation refusal.
        val refused = postWithKey("/v1/inboxes", """{"ttlSeconds":99999999}""", k)
        refused.statusCode shouldBe HttpStatus.BAD_REQUEST

        // Recording that failure would freeze it into the key for the whole
        // retention window, with no endpoint to clear it (ADR-033 §4).
        val corrected = postWithKey("/v1/inboxes", """{"ttlSeconds":600}""", k)
        corrected.statusCode shouldBe HttpStatus.CREATED
        corrected.headers.getFirst("Idempotency-Replayed") shouldBe "false"
    }

    @Test
    fun `two workspaces may use the same key value without seeing each other`() {
        val k = key()
        val mine = postWithKey("/v1/inboxes", """{"ttlSeconds":600}""", k)
        val theirs = postWithKey("/v1/inboxes", """{"ttlSeconds":600}""", k, apiKey = otherWorkspaceKey)
        mine.statusCode shouldBe HttpStatus.CREATED
        theirs.statusCode shouldBe HttpStatus.CREATED
        json.readTree(theirs.body!!)["id"].asString() shouldContain "-"
        json.readTree(mine.body!!)["id"].asString() shouldNotBe0 json.readTree(theirs.body!!)["id"].asString()
    }

    @Test
    fun `the same key on a different operation is an independent request`() {
        val k = key()
        postWithKey("/v1/inboxes", """{"ttlSeconds":600}""", k).statusCode shouldBe HttpStatus.CREATED
        // Not a conflict: the scope includes the operation, so one client's
        // per-job key works across every endpoint it calls in that job.
        postWithKey(
            "/v1/api-keys",
            """{"scopes":["messages:read"]}""",
            k,
        ).statusCode shouldBe HttpStatus.CREATED
    }

    @Test
    fun `concurrent identical requests create exactly one inbox`() {
        val k = key()
        val racers = 6
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(racers)
        try {
            val responses =
                (1..racers)
                    .map {
                        pool.submit<ResponseEntity<String>> {
                            gate.await()
                            postWithKey("/v1/inboxes", """{"ttlSeconds":600}""", k)
                        }
                    }.also { gate.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }

            val created = responses.filter { it.statusCode == HttpStatus.CREATED }
            created.size shouldBe racers
            // One resource, however many callers asked for it.
            created.map { json.readTree(it.body!!)["id"].asString() }.toSet().size shouldBe 1
            created.count { it.headers.getFirst("Idempotency-Replayed") == "false" } shouldBe 1
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `key creation suppresses the duplicate and names what it created`() {
        val k = key()
        val first = postWithKey("/v1/api-keys", """{"name":"ci","scopes":["messages:read"]}""", k)
        first.statusCode shouldBe HttpStatus.CREATED
        val createdId = json.readTree(first.body!!)["apiKey"]["id"].asString()
        val secret = json.readTree(first.body!!)["key"].asString()

        val retry = postWithKey("/v1/api-keys", """{"name":"ci","scopes":["messages:read"]}""", k)
        // Duplicate suppression, not replay (ADR-033 §8): a 200 with the
        // credential omitted would let a client write `undefined` into its
        // secret store and fail somewhere else, hours later.
        retry.statusCode shouldBe HttpStatus.CONFLICT
        val problem = json.readTree(retry.body!!)
        problem["type"].asString() shouldContain "idempotency-secret-not-replayable"
        problem["apiKeyId"].asString() shouldBe createdId
        // It names the credential so the caller can revoke it without a list
        // call — and it does not, under any circumstance, return the secret.
        problem["publicId"].asString() shouldBe secret.split("_")[2]
        retry.body!!.contains(secret) shouldBe false
        retry.body!!.contains(secret.split("_")[3]) shouldBe false
    }

    @Test
    fun `a retried key creation mints exactly one credential`() {
        val tenant = provisionIsolatedWorkspace("idem-keys")
        val admin = mintKey(tenant.workspaceId, tenant.projectId, ApiScope.entries.toSet())
        val k = key()
        val body = """{"name":"once","scopes":["messages:read"]}"""

        postWithKey("/v1/api-keys", body, k, apiKey = admin).statusCode shouldBe HttpStatus.CREATED
        postWithKey("/v1/api-keys", body, k, apiKey = admin).statusCode shouldBe HttpStatus.CONFLICT

        val listed = json.readTree(get("/v1/api-keys", admin).body!!)["items"].toList()
        // The orphan-credential failure TI-003 exists to prevent: without this,
        // the retry would leave a second live credential nobody holds.
        listed.count { it["name"]?.asString() == "once" } shouldBe 1
    }

    @Test
    fun `an operation that does not support the header refuses it rather than ignoring it`() {
        val inbox = json.readTree(post("/v1/inboxes", "{}").body!!)["id"].asString()
        val response =
            postWithKey(
                "/v1/inboxes/$inbox/messages/wait",
                """{"timeoutSeconds":1}""",
                key(),
            )
        // Accepting it silently would grant retry protection that does not
        // exist — and the client's SDK may then retry on that belief.
        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body!! shouldContain "idempotency-not-supported"
    }

    @Test
    fun `without the header the endpoints behave exactly as before`() {
        val a = postWithKey("/v1/inboxes", """{"ttlSeconds":600}""", null)
        val b = postWithKey("/v1/inboxes", """{"ttlSeconds":600}""", null)
        a.statusCode shouldBe HttpStatus.CREATED
        b.statusCode shouldBe HttpStatus.CREATED
        // Two calls, two inboxes: the header is opt-in and its absence changes
        // nothing.
        json.readTree(a.body!!)["id"].asString() shouldNotBe0 json.readTree(b.body!!)["id"].asString()
        a.headers.getFirst("Idempotency-Replayed") shouldBe null
    }

    private infix fun String.shouldNotBe0(other: String) {
        (this == other) shouldBe false
    }
}
