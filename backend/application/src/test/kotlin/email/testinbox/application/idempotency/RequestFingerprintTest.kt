package email.testinbox.application.idempotency

import email.testinbox.application.usecase.CreateApiKey
import email.testinbox.application.usecase.CreateInbox
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.idempotency.CanonicalRequest
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiScope
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class RequestFingerprintTest {
    private val workspaceId = WorkspaceId(UUID.randomUUID())
    private val projectId = ProjectId(UUID.randomUUID())

    private fun inbox(
        ttlSeconds: Long? = 600,
        aliasHint: String? = null,
        localPart: String? = null,
        mode: AddressMode = AddressMode.GENERATED,
        project: ProjectId = projectId,
    ) = CreateInbox.Command(workspaceId, project, mode, ttlSeconds, aliasHint, localPart)

    private fun actor() =
        ApiKey(
            id = ApiKeyId(UUID.randomUUID()),
            workspaceId = workspaceId,
            projectId = projectId,
            keyHash = "0".repeat(64),
            scopes = ApiScope.entries.toSet(),
            createdAt = Instant.EPOCH,
            revokedAt = null,
            publicId = "abcdefghijklmnop",
        )

    @Test
    fun `the same semantic request fingerprints identically`() {
        RequestFingerprint.of(inbox()) shouldBe RequestFingerprint.of(inbox())
    }

    @Test
    fun `every semantic field changes the fingerprint`() {
        val base = RequestFingerprint.of(inbox())
        RequestFingerprint.of(inbox(ttlSeconds = 601)) shouldNotBe base
        RequestFingerprint.of(inbox(aliasHint = "signup")) shouldNotBe base
        RequestFingerprint.of(inbox(mode = AddressMode.EXACT, localPart = "abc")) shouldNotBe base
        RequestFingerprint.of(inbox(project = ProjectId(UUID.randomUUID()))) shouldNotBe base
    }

    @Test
    fun `an absent field is distinguishable from an empty one`() {
        // Without an explicit marker these collide, and a client that stopped
        // sending aliasHint would silently replay the result of one that sent "".
        RequestFingerprint.of(inbox(aliasHint = null)) shouldNotBe RequestFingerprint.of(inbox(aliasHint = ""))
    }

    @Test
    fun `field boundaries cannot be shifted between adjacent values`() {
        // The classic canonicalisation attack: ["a","b"] against ["ab",""].
        CanonicalRequest.encode(listOf("x" to "a", "y" to "b")) shouldNotBe
            CanonicalRequest.encode(listOf("x" to "ab", "y" to ""))
    }

    @Test
    fun `local part case does not conflict with itself`() {
        // Foo and foo produce the identical inbox, so fingerprinting the raw
        // value would make a legitimate retry conflict with its own first try.
        RequestFingerprint.of(inbox(mode = AddressMode.EXACT, localPart = "Support-Team")) shouldBe
            RequestFingerprint.of(inbox(mode = AddressMode.EXACT, localPart = "support-team"))
    }

    @Test
    fun `scope order does not change an api key fingerprint, but scope content does`() {
        fun command(scopes: List<ApiScope>) = CreateApiKey.Command(actor(), "ci", scopes.toSet(), Duration.ofHours(1))

        RequestFingerprint.of(command(listOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ))) shouldBe
            RequestFingerprint.of(command(listOf(ApiScope.MESSAGES_READ, ApiScope.INBOXES_WRITE)))
        RequestFingerprint.of(command(listOf(ApiScope.INBOXES_WRITE))) shouldNotBe
            RequestFingerprint.of(command(listOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ)))
    }

    @Test
    fun `a different actor with the same request fingerprints the same`() {
        // Credential rotation inside a retry window must not defeat the
        // guarantee (ADR-033 §4a); the actor binds the record separately.
        val a = CreateApiKey.Command(actor(), "ci", setOf(ApiScope.MESSAGES_READ), null)
        val b = CreateApiKey.Command(actor(), "ci", setOf(ApiScope.MESSAGES_READ), null)
        RequestFingerprint.of(a) shouldBe RequestFingerprint.of(b)
    }

    @Test
    fun `a blank name is the same request as no name`() {
        // CreateApiKey stores a blank name as absent, so the two are the same
        // request and must not conflict with each other.
        val named = CreateApiKey.Command(actor(), "   ", setOf(ApiScope.MESSAGES_READ), null)
        val unnamed = CreateApiKey.Command(actor(), null, setOf(ApiScope.MESSAGES_READ), null)
        RequestFingerprint.of(named) shouldBe RequestFingerprint.of(unnamed)
    }
}
