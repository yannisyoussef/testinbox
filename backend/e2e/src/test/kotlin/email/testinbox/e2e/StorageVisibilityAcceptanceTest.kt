package email.testinbox.e2e

import email.testinbox.client.CreateInboxOptions
import email.testinbox.client.MessageMatcher
import email.testinbox.client.TestInboxClient
import email.testinbox.client.TestInboxTimeoutException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * ADR-035 §13 against the full walking-skeleton stack (TI-STORAGE-004 §57,
 * §58): the raw REST storage surface works end to end, and the CURRENT JVM
 * SDK, which sends no `afterStorageRefusalCount`, keeps its released
 * MATCHED/TIMEOUT behaviour against an inbox whose refusal count is not zero.
 *
 * Live enforcement is OFF in both deployables, so no SMTP traffic produces a
 * refusal here; the record is written exactly as the §6a upsert writes it,
 * with its notify, on the stack's own database.
 */
class StorageVisibilityAcceptanceTest {
    private val json = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()

    private fun client() = TestInboxClient(apiKey = E2eStack.API_KEY, baseUrl = E2eStack.apiBaseUrl)

    private fun rest(
        method: String,
        path: String,
        body: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create(E2eStack.apiBaseUrl + path))
                .header("Authorization", "Bearer ${E2eStack.API_KEY}")
                .header("Content-Type", "application/json")
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun JsonNode.member(field: String): JsonNode = checkNotNull(get(field)) { "missing $field in $this" }

    /**
     * The ADR-035 §6a refusal record and its notification, as T2 or the
     * refusal-only transaction commit them. The SQL mirrors
     * `JdbcStorageReservations.recordRefusals` (the e2e module must not depend
     * on the persistence adapter); keep the two in step.
     */
    private fun recordRefusal(inboxId: String) {
        E2eStack.dbConnection().use { connection ->
            connection.autoCommit = false
            connection
                .prepareStatement(
                    """
                    INSERT INTO inbox_storage (inbox_id, workspace_id, refusal_count, last_refusal_at, last_refusal_reason)
                    SELECT i.id, i.workspace_id, 1, now(), 'INBOX_LIMIT' FROM inbox i WHERE i.id = ?
                    ON CONFLICT (inbox_id) DO UPDATE
                       SET refusal_count = inbox_storage.refusal_count + 1,
                           last_refusal_at = EXCLUDED.last_refusal_at,
                           last_refusal_reason = EXCLUDED.last_refusal_reason
                    """.trimIndent(),
                ).use {
                    it.setObject(1, UUID.fromString(inboxId))
                    it.executeUpdate() shouldBe 1
                }
            connection.prepareStatement("SELECT pg_notify('testinbox_messages', ?)").use {
                it.setString(1, inboxId)
                it.executeQuery()
            }
            connection.commit()
        }
    }

    @Test
    fun `the current JVM SDK omits the cursor, so a non-zero refusal count never changes its MATCHED or TIMEOUT behaviour`() {
        val client = client()
        val inbox = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        recordRefusal(inbox.id)
        json.readTree(rest("GET", "/v1/inboxes/${inbox.id}").body()).member("storageRefusalCount").asLong() shouldBe 1

        // A released SDK against a refused inbox: the window chains to the typed timeout, never a 409.
        val timeout =
            assertThrows<TestInboxTimeoutException> {
                inbox.awaitMessageBlocking(Duration.ofSeconds(2), MessageMatcher.builder().subjectContains("never-arrives").build())
            }
        timeout.arrivedButUnmatchedCount shouldBe 0

        // And real mail still matches, with another refusal recorded meanwhile.
        recordRefusal(inbox.id)
        E2eStack.sendRawSmtp("no-reply@example.com", inbox.address, E2eStack.verificationEmail(inbox.address, "SDK compat after refusals"))
        val message = inbox.awaitMessageBlocking(Duration.ofSeconds(20), MessageMatcher.builder().subjectContains("SDK compat").build())
        message.subject shouldBe "SDK compat after refusals"

        inbox.deleteBlocking()
    }

    @Test
    fun `raw REST exposes workspace and inbox storage, and an explicit cursor observes the refusal through the whole stack`() {
        val created = json.readTree(rest("POST", "/v1/inboxes", """{"ttlSeconds":300}""").body())
        val inboxId = created.member("id").asString()
        val storage = created.member("storage")
        storage.member("limitBytes").asLong() shouldBe 512L * 1024 * 1024 // 2 GiB default workspace limit × the 0.25 share
        storage.member("storedBytes").asLong() shouldBe 0
        storage.member("overLimit").asBoolean() shouldBe false
        created.member("storageRefusalCount").asLong() shouldBe 0

        val workspace = json.readTree(rest("GET", "/v1/workspace/storage").body())
        workspace.member("limitBytes").asLong() shouldBe 2L * 1024 * 1024 * 1024
        workspace.has("globalLimitBytes") shouldBe false
        workspace.size() shouldBe 5

        // Legacy shape of the wait: no cursor, no 409, and the informational count.
        val legacy = rest("POST", "/v1/inboxes/$inboxId/messages/wait", """{"timeoutSeconds":1}""")
        legacy.statusCode() shouldBe 200
        json.readTree(legacy.body()).member("storageRefusalCount").asLong() shouldBe 0

        recordRefusal(inboxId)
        val conflict = rest("POST", "/v1/inboxes/$inboxId/messages/wait", """{"timeoutSeconds":1,"afterStorageRefusalCount":0}""")
        conflict.statusCode() shouldBe 409
        conflict.headers().firstValue("Retry-After").isPresent shouldBe false
        val problem = json.readTree(conflict.body())
        problem.member("type").asString() shouldContain "storage-limit-exceeded"
        problem.member("refusalReason").asString() shouldBe "INBOX_LIMIT"
        problem.member("storageRefusalCount").asLong() shouldBe 1
        problem.member("limit").asLong() shouldBe 512L * 1024 * 1024

        // The caller advances its own boundary; the server kept none.
        val next = rest("POST", "/v1/inboxes/$inboxId/messages/wait", """{"timeoutSeconds":1,"afterStorageRefusalCount":1}""")
        next.statusCode() shouldBe 200
        json.readTree(next.body()).member("status").asString() shouldBe "TIMEOUT"
        json.readTree(rest("GET", "/v1/inboxes/$inboxId").body()).member("lastStorageRefusalReason").asString() shouldBe "INBOX_LIMIT"

        rest("DELETE", "/v1/inboxes/$inboxId").statusCode() shouldBe 204
    }

    /** Hands the TS live suite an inbox that already carries refusals; see `TsSdkIntegrationTest`. */
    companion object {
        fun inboxWithRefusals(): String {
            val test = StorageVisibilityAcceptanceTest()
            return with(test) {
                val inboxId = json.readTree(rest("POST", "/v1/inboxes", """{"ttlSeconds":600}""").body()).member("id").asString()
                recordRefusal(inboxId)
                inboxId
            }
        }
    }
}
