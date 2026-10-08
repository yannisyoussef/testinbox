package email.testinbox.e2e

import email.testinbox.client.CreateInboxOptions
import email.testinbox.client.MessageMatcher
import email.testinbox.client.StorageRefusalReason
import email.testinbox.client.TestInboxClient
import email.testinbox.client.TestInboxStorageLimitExceededException
import email.testinbox.client.TestInboxTimeoutException
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * ADR-035 §13 SDK semantics against the full stack (§17 test 45, TI-STORAGE-005):
 * `getWorkspaceStorage` agrees with the raw REST figures and with seeded
 * accounting, inbox snapshots are frozen per object while a fresh object sees
 * fresh state, a REAL `409 storage-limit-exceeded` from the deployed API
 * becomes the typed exception with the cursor already advanced, and the
 * opt-out restores the legacy wait.
 *
 * Live enforcement is OFF in both deployables, so the refusal record is
 * written exactly as the §6a upsert writes it (with its notify) on the
 * stack's own database — the server then answers the real 409.
 */
class SdkStorageAcceptanceTest {
    private val json = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()

    private fun client() = TestInboxClient(apiKey = E2eStack.API_KEY, baseUrl = E2eStack.apiBaseUrl)

    private fun rest(
        method: String,
        path: String,
    ): tools.jackson.databind.JsonNode =
        json.readTree(
            http
                .send(
                    HttpRequest
                        .newBuilder(URI.create(E2eStack.apiBaseUrl + path))
                        .header("Authorization", "Bearer ${E2eStack.API_KEY}")
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body(),
        )

    private fun recordRefusal(
        inboxId: String,
        reason: StorageRefusalReason = StorageRefusalReason.INBOX_LIMIT,
    ) = E2eStorage.recordRefusal(inboxId, reason.wire)

    private fun reserve(
        inboxId: String,
        bytes: Long,
    ) = E2eStorage.reserve(inboxId, bytes)

    @Test
    fun `getWorkspaceStorage equals the raw REST figures and moves with seeded accounting`() {
        val client = client()
        val inbox = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        try {
            val before = client.getWorkspaceStorageBlocking()
            val raw = rest("GET", "/v1/workspace/storage")
            before.limitBytes shouldBe raw["limitBytes"].asLong()
            before.storedBytes shouldBe raw["storedBytes"].asLong()
            before.reservedBytes shouldBe raw["reservedBytes"].asLong()
            before.availableBytes shouldBe raw["availableBytes"].asLong()
            before.overLimit shouldBe raw["overLimit"].asBoolean()
            raw.size() shouldBe 5

            reserve(inbox.id, 4_096)
            val after = client.getWorkspaceStorageBlocking()
            // The acceptance workspace is shared with the other suites, whose mail
            // moves storedBytes concurrently, so the proof is the reservation's own
            // delta plus the ADR-035 §13a arithmetic on one coherent read.
            (after.reservedBytes >= before.reservedBytes + 4_096) shouldBe true
            after.availableBytes shouldBe maxOf(0L, after.limitBytes - after.storedBytes - after.reservedBytes)
            after.overLimit shouldBe (after.storedBytes + after.reservedBytes > after.limitBytes)
            after.overLimit shouldBe false
            // Nothing on the public type can carry a global figure.
            after.toString().contains("global") shouldBe false
        } finally {
            inbox.deleteBlocking()
        }
    }

    @Test
    fun `inbox snapshots are frozen per object while a fresh object sees the fresh storage and refusal state`() {
        val client = client()
        val created = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        try {
            created.storageRefusalCount shouldBe 0L
            created.lastStorageRefusalReason shouldBe null
            created.storage!!.limitBytes shouldBeGreaterThan 0L
            created.storageRefusalCursor shouldBe 0L

            val reservedBefore = created.storage!!.reservedBytes
            reserve(created.id, 2_048)
            recordRefusal(created.id, StorageRefusalReason.WORKSPACE_LIMIT)

            val fresh = client.getInboxBlocking(created.id)
            fresh.storage!!.reservedBytes shouldBe reservedBefore + 2_048
            fresh.storageRefusalCount shouldBe 1L
            fresh.lastStorageRefusalReason shouldBe StorageRefusalReason.WORKSPACE_LIMIT
            fresh.lastStorageRefusalAt shouldNotBe null
            fresh.storageRefusalCursor shouldBe 1L
            // The earlier object's snapshot is untouched.
            created.storageRefusalCount shouldBe 0L
            created.storage!!.reservedBytes shouldBe reservedBefore
        } finally {
            created.deleteBlocking()
        }
    }

    @Test
    fun `a real storage-limit 409 from the API is the typed exception, with the cursor advanced before the handler runs`() {
        val client = client()
        val inbox = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        try {
            recordRefusal(inbox.id, StorageRefusalReason.INBOX_LIMIT)
            var cursorInHandler: Long? = null
            val error =
                assertThrows<TestInboxStorageLimitExceededException> {
                    try {
                        inbox.awaitMessageBlocking(Duration.ofSeconds(5), MessageMatcher.builder().subjectContains("never").build())
                    } catch (e: TestInboxStorageLimitExceededException) {
                        cursorInHandler = inbox.storageRefusalCursor
                        throw e
                    }
                }
            error.status shouldBe 409
            error.problemType shouldBe "https://testinbox.email/problems/storage-limit-exceeded"
            error.inboxId shouldBe inbox.id
            error.refusalReason shouldBe StorageRefusalReason.INBOX_LIMIT
            error.afterStorageRefusalCount shouldBe 0L
            error.storageRefusalCount shouldBe 1L
            error.quota shouldBe "STORED_BYTES"
            error.limit shouldBe inbox.storage!!.limitBytes
            cursorInHandler shouldBe 1L
            inbox.storageRefusalCount shouldBe 0L // the snapshot stays what the create response said

            // The next default wait observes only later refusals: the window expires normally.
            assertThrows<TestInboxTimeoutException> {
                inbox.awaitMessageBlocking(Duration.ofSeconds(2), MessageMatcher.builder().subjectContains("never").build())
            }
            inbox.storageRefusalCursor shouldBe 1L
        } finally {
            inbox.deleteBlocking()
        }
    }

    @Test
    fun `observeStorageRefusals = false restores the legacy wait against an inbox with refusals`() {
        val client = client()
        val inbox = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        try {
            recordRefusal(inbox.id)
            recordRefusal(inbox.id)
            val timeout =
                assertThrows<TestInboxTimeoutException> {
                    inbox.awaitMessageBlocking(
                        Duration.ofSeconds(2),
                        MessageMatcher.builder().subjectContains("never").build(),
                        null,
                        observeStorageRefusals = false,
                    )
                }
            timeout.arrivedButUnmatchedCount shouldBe 0
            inbox.storageRefusalCursor shouldBe 0L // nothing acknowledged by a TIMEOUT echo

            // And real mail still matches through the legacy path.
            E2eStack.sendRawSmtp("no-reply@example.com", inbox.address, E2eStack.verificationEmail(inbox.address, "Legacy wait"))
            val message =
                inbox.awaitMessageBlocking(
                    Duration.ofSeconds(20),
                    MessageMatcher.builder().subjectContains("Legacy").build(),
                    null,
                    observeStorageRefusals = false,
                )
            message.subject shouldBe "Legacy wait"
        } finally {
            inbox.deleteBlocking()
        }
    }
}
