package email.testinbox.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.domain.InboxId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The `429` contract from ADR-027 §8. Limits are tiny so boundaries are
 * exercised in milliseconds, and every test provisions its **own** workspace:
 * budgets and derived quota usage are per workspace, so a shared one would
 * carry another test's spent tokens and inboxes.
 */
@TestPropertySource(
    properties = [
        "testinbox.limits.inbox-create.capacity=2",
        // Slow refill: the boundary must not heal mid-test.
        "testinbox.limits.inbox-create.refill-per-second=0.01",
    ],
)
class RateLimitApiTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    @Test
    fun `the third create in the window is 429 with Retry-After and RFC 7807`() {
        val key = provisionIsolatedWorkspace("rl-burst").apiKey
        post("/v1/inboxes", """{}""", key = key).statusCode.value() shouldBe 201
        post("/v1/inboxes", """{}""", key = key).statusCode.value() shouldBe 201

        val limited = post("/v1/inboxes", """{}""", key = key)
        limited.statusCode.value() shouldBe 429
        limited.headers.contentType.toString() shouldContain "application/problem+json"
        // Waiting genuinely helps here, so a retry hint must be present, whole
        // seconds, and never zero. At 0.01 tokens/s one token takes 100s.
        limited.headers.getFirst("Retry-After")!!.toLong() shouldBe 100L
        val problem = json.readTree(limited.body)
        problem["type"].asText() shouldBe "https://testinbox.email/problems/rate-limit-exceeded"
        problem["category"].asText() shouldBe "INBOX_CREATE"
        problem["correlationId"].asText().isNotBlank() shouldBe true
    }

    @Test
    fun `successful responses advertise the governing budget so clients can pace themselves`() {
        val key = provisionIsolatedWorkspace("rl-headers").apiKey
        val created = post("/v1/inboxes", """{}""", key = key)
        created.statusCode.value() shouldBe 201
        created.headers.getFirst("RateLimit-Limit") shouldBe "2"
        // One of two tokens spent.
        created.headers.getFirst("RateLimit-Remaining") shouldBe "1"
        // 0.01 tokens/s means 100s to earn one back.
        created.headers.getFirst("RateLimit-Reset")!!.toLong() shouldBe 100L
    }

    @Test
    fun `cheap metadata reads are not charged a token`() {
        val key = provisionIsolatedWorkspace("rl-reads").apiKey
        val id = json.readTree(post("/v1/inboxes", """{}""", key = key).body)["id"].asText()
        // Reads are uncharged by design, so they cannot exhaust the create budget.
        repeat(5) { get("/v1/inboxes/$id", key = key).statusCode.value() shouldBe 200 }
        post("/v1/inboxes", """{}""", key = key).statusCode.value() shouldBe 201
    }

    @Test
    fun `two API keys in one workspace share one budget (rotation is not an escape hatch)`() {
        // Buckets key on the workspace, never the API key: otherwise a tenant
        // could mint N keys for N times the allowance, and rotating a key — a
        // security operation — would double as a limit-evasion primitive.
        val tenant = provisionIsolatedWorkspace("rl-keys")
        val secondKey = provisionAdditionalKey(tenant)
        post("/v1/inboxes", """{}""", key = tenant.apiKey).statusCode.value() shouldBe 201
        post("/v1/inboxes", """{}""", key = secondKey).statusCode.value() shouldBe 201
        post("/v1/inboxes", """{}""", key = secondKey).statusCode.value() shouldBe 429
    }

    @Test
    fun `no limiter state derives from a client-supplied header`() {
        val key = provisionIsolatedWorkspace("rl-forged").apiKey
        post("/v1/inboxes", """{}""", key = key).statusCode.value() shouldBe 201
        post("/v1/inboxes", """{}""", key = key).statusCode.value() shouldBe 201
        // Forging identity-ish headers must not mint a fresh bucket: workspace
        // identity comes from the authenticated key alone (ADR-027 §3).
        val forged =
            rest.exchange(
                url("/v1/inboxes"),
                HttpMethod.POST,
                HttpEntity(
                    """{}""",
                    headers(key).apply {
                        set("X-Forwarded-For", "203.0.113.7")
                        set("X-Workspace-Id", UUID.randomUUID().toString())
                    },
                ),
                String::class.java,
            )
        forged.statusCode.value() shouldBe 429
    }
}

/**
 * Quota exhaustion answers `409`, not `429`: waiting cannot fix it, so
 * advertising a retry would send SDKs into a loop that cannot succeed.
 */
@TestPropertySource(
    properties = [
        "testinbox.limits.max-active-inboxes=2",
        "testinbox.limits.inbox-create.capacity=1000",
        "testinbox.limits.inbox-create.refill-per-second=1000",
    ],
)
class QuotaApiTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    @Test
    fun `quota exhaustion is 409 with a quota problem type and no Retry-After`() {
        val key = provisionIsolatedWorkspace("quota-basic").apiKey
        val first = json.readTree(post("/v1/inboxes", """{}""", key = key).body)
        post("/v1/inboxes", """{}""", key = key).statusCode.value() shouldBe 201

        val rejected = post("/v1/inboxes", """{}""", key = key)
        rejected.statusCode.value() shouldBe 409
        val problem = json.readTree(rejected.body)
        problem["type"].asText() shouldBe "https://testinbox.email/problems/quota-exceeded"
        problem["quota"].asText() shouldBe "ACTIVE_INBOXES"
        problem["limit"].asLong() shouldBe 2L
        problem["current"].asLong() shouldBe 2L
        // Retrying does not help, so no retry hint is advertised.
        rejected.headers.getFirst("Retry-After") shouldBe null

        // The 409's advertised remedy must actually work, immediately.
        delete("/v1/inboxes/${first["id"].asText()}", key = key).statusCode.value() shouldBe 204
        post("/v1/inboxes", """{}""", key = key).statusCode.value() shouldBe 201
    }

    @Test
    fun `quota is per workspace, so another tenant is unaffected`() {
        val key = provisionIsolatedWorkspace("quota-a").apiKey
        val neighbour = provisionIsolatedWorkspace("quota-b").apiKey
        repeat(2) { post("/v1/inboxes", """{}""", key = key).statusCode.value() shouldBe 201 }
        post("/v1/inboxes", """{}""", key = key).statusCode.value() shouldBe 409
        // One workspace exhausting its allowance must not degrade another.
        post("/v1/inboxes", """{}""", key = neighbour).statusCode.value() shouldBe 201
    }

    @Test
    fun `concurrent creates at the boundary admit exactly the allowance`() {
        val key = provisionIsolatedWorkspace("quota-race").apiKey
        val threads = 8
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (1..threads).map {
                    executor.submit<Int> {
                        gate.await(5, TimeUnit.SECONDS)
                        post("/v1/inboxes", """{}""", key = key).statusCode.value()
                    }
                }
            gate.countDown()
            val codes = futures.map { it.get(30, TimeUnit.SECONDS) }
            // Derive-then-insert is check-then-act; the admission guard is what
            // stops all eight from observing capacity for two.
            codes.count { it == 201 } shouldBe 2
            codes.count { it == 409 } shouldBe threads - 2
        } finally {
            executor.shutdownNow()
        }
    }
}

/** The concurrent-wait ceiling is rate-shaped: 429 with a retry hint. */
@TestPropertySource(
    properties = [
        "testinbox.limits.max-concurrent-waits=1",
        "testinbox.wait-window-cap=3s",
    ],
)
class ConcurrentWaitLimitApiTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    @Test
    fun `a second concurrent waiter is refused with 429 while the first parks`() {
        val key = provisionIsolatedWorkspace("wait-limit").apiKey
        val id = json.readTree(post("/v1/inboxes", """{}""", key = key).body)["id"].asText()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val parked =
                executor.submit<Int> {
                    post("/v1/inboxes/$id/messages/wait", """{"timeoutSeconds":3}""", key = key)
                        .statusCode
                        .value()
                }
            // Let the first request get past check/subscribe/recheck and park.
            Thread.sleep(700)
            val refused = post("/v1/inboxes/$id/messages/wait", """{"timeoutSeconds":2}""", key = key)
            refused.statusCode.value() shouldBe 429
            val problem = json.readTree(refused.body)
            problem["type"].asText() shouldBe
                "https://testinbox.email/problems/concurrent-wait-limit-exceeded"
            problem["limit"].asLong() shouldBe 1L
            // A slot frees with time, so a retry hint is correct here.
            refused.headers.getFirst("Retry-After")!!.toLong() shouldBe 1L

            // The parked waiter still completes normally: TIMEOUT, not an error.
            parked.get(20, TimeUnit.SECONDS) shouldBe 200
            // And the released slot is immediately reusable.
            post("/v1/inboxes/$id/messages/wait", """{"timeoutSeconds":1}""", key = key)
                .statusCode
                .value() shouldBe 200
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a wait that is already satisfiable consumes no slot`() {
        val tenant = provisionIsolatedWorkspace("wait-fastpath")
        val inbox = json.readTree(post("/v1/inboxes", """{}""", key = tenant.apiKey).body)
        appendVisibleMessage(
            InboxId(UUID.fromString(inbox["id"].asText())),
            inbox["address"].asText(),
            workspaceId = tenant.workspaceId,
        )
        // The ceiling is 1, yet repeated immediately-satisfiable waits all pass:
        // a slot is claimed only just before parking (ADR-027 §7), so a wait
        // that never parks is never refused for concurrency it does not use.
        repeat(3) {
            val matched =
                post(
                    "/v1/inboxes/${inbox["id"].asText()}/messages/wait",
                    """{"timeoutSeconds":2}""",
                    key = tenant.apiKey,
                )
            matched.statusCode.value() shouldBe 200
            json.readTree(matched.body)["status"].asText() shouldBe "MATCHED"
        }
    }
}
