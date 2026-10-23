package email.testinbox.e2e

import email.testinbox.client.CreateInboxOptions
import email.testinbox.client.MessageMatcher
import email.testinbox.client.StorageRefusalReason
import email.testinbox.client.StorageUsage
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
 *
 * The workspace-storage figures are a live aggregate, so the proofs about
 * them run in [E2eStack.STORAGE_API_KEY]'s own quiet workspace: in the shared
 * acceptance workspace the other suites' mail, expiry and reservation release
 * move `storedBytes`/`reservedBytes` between any two requests, and two such
 * snapshots are each correct without being equal. The inbox-level proofs stay
 * on the shared workspace, since they read state only their own inbox owns.
 */
class SdkStorageAcceptanceTest {
    private val json = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()

    private fun client() = TestInboxClient(apiKey = E2eStack.API_KEY, baseUrl = E2eStack.apiBaseUrl)

    private fun storageClient() = TestInboxClient(apiKey = E2eStack.STORAGE_API_KEY, baseUrl = E2eStack.storageApiBaseUrl)

    private fun rest(
        method: String,
        path: String,
        apiKey: String = E2eStack.API_KEY,
        baseUrl: String = E2eStack.apiBaseUrl,
    ): tools.jackson.databind.JsonNode =
        json.readTree(
            http
                .send(
                    HttpRequest
                        .newBuilder(URI.create(baseUrl + path))
                        .header("Authorization", "Bearer $apiKey")
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body(),
        )

    private fun storageRest() = rest("GET", "/v1/workspace/storage", E2eStack.STORAGE_API_KEY, E2eStack.storageApiBaseUrl)

    /** ADR-035 §13a: every snapshot is internally coherent on its own, whatever instant it was read at. */
    private fun StorageUsage.shouldBeCoherent() {
        availableBytes shouldBe maxOf(0L, limitBytes - storedBytes - reservedBytes)
        overLimit shouldBe (storedBytes + reservedBytes > limitBytes)
        // Nothing on the public type can carry a global figure.
        toString().contains("global") shouldBe false
    }

    private fun recordRefusal(
        inboxId: String,
        reason: StorageRefusalReason = StorageRefusalReason.INBOX_LIMIT,
    ) = E2eStorage.recordRefusal(inboxId, reason.wire)

    private fun reserve(
        inboxId: String,
        bytes: Long,
    ) = E2eStorage.reserve(inboxId, bytes)

    @Test
    fun `getWorkspaceStorage equals the raw REST figures and moves with seeded accounting, in a workspace only this test touches`() {
        val client = storageClient()
        val inbox = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        try {
            // Two requests, two snapshots. Equality between them is an invariant HERE
            // because nothing else writes this workspace: no other suite holds its
            // key, no mail is addressed to it, and its only inbox outlives the test.
            val before = client.getWorkspaceStorageBlocking()
            val raw = storageRest()
            raw.size() shouldBe 5
            before.limitBytes shouldBe raw["limitBytes"].asLong()
            before.storedBytes shouldBe raw["storedBytes"].asLong()
            before.reservedBytes shouldBe raw["reservedBytes"].asLong()
            before.availableBytes shouldBe raw["availableBytes"].asLong()
            before.overLimit shouldBe raw["overLimit"].asBoolean()
            before.limitBytes shouldBeGreaterThan 0L
            before.shouldBeCoherent()

            reserve(inbox.id, 4_096)
            val after = client.getWorkspaceStorageBlocking()
            // The SDK observes exactly this test's reservation: the delta is exact,
            // not a lower bound, and nothing else moved.
            after.reservedBytes shouldBe before.reservedBytes + 4_096
            after.storedBytes shouldBe before.storedBytes
            after.limitBytes shouldBe before.limitBytes
            after.shouldBeCoherent()
            after.overLimit shouldBe false
        } finally {
            inbox.deleteBlocking()
        }
    }

    /**
     * The seam that made the shared-workspace version of the test above a
     * timing gamble (it compared `storedBytes` from an SDK request with the
     * same field from a LATER raw request on the shared acceptance workspace,
     * which the other suites' mail and expiry move at any instant): request A,
     * an accounting change, request B. Both responses are individually correct
     * and they are not equal. Here the change is this test's own reservation
     * instead of another suite's traffic, so the divergence is deterministic.
     */
    @Test
    fun `two storage snapshots with an accounting change between them are each correct and not equal`() {
        val client = storageClient()
        val inbox = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        try {
            val a = client.getWorkspaceStorageBlocking()
            reserve(inbox.id, 8_192) // what another suite's mail or expiry does to the shared workspace, on demand
            val b = storageRest()
            a.shouldBeCoherent()
            b["availableBytes"].asLong() shouldBe
                maxOf(0L, b["limitBytes"].asLong() - b["storedBytes"].asLong() - b["reservedBytes"].asLong())
            b["overLimit"].asBoolean() shouldBe (b["storedBytes"].asLong() + b["reservedBytes"].asLong() > b["limitBytes"].asLong())
            // The old assertion, `a.field == b.field` for the live figures, is false by construction.
            b["reservedBytes"].asLong() shouldBe a.reservedBytes + 8_192
            (a.reservedBytes == b["reservedBytes"].asLong()) shouldBe false
            (a.availableBytes == b["availableBytes"].asLong()) shouldBe false
            // Only the configured limit and the shape survive across instants.
            a.limitBytes shouldBe b["limitBytes"].asLong()
            b.size() shouldBe 5
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
