package email.testinbox.domain.tenant

import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ApiKeyTest {
    private val now = Instant.parse("2026-09-08T12:00:00Z")

    private fun key(
        revokedAt: Instant? = null,
        expiresAt: Instant? = null,
    ) = ApiKey(
        id = ApiKeyId(UUID.randomUUID()),
        workspaceId = WorkspaceId(UUID.randomUUID()),
        projectId = ProjectId(UUID.randomUUID()),
        keyHash = "0".repeat(64),
        scopes = setOf(ApiScope.MESSAGES_READ),
        createdAt = now.minusSeconds(60),
        revokedAt = revokedAt,
        publicId = "abcdefghijklmnop",
        expiresAt = expiresAt,
    )

    @Test
    fun `a key with neither revocation nor expiry is usable`() {
        key().isUsableAt(now) shouldBe true
    }

    @Test
    fun `revocation makes a key unusable`() {
        key(revokedAt = now.minusSeconds(1)).isUsableAt(now) shouldBe false
    }

    @Test
    fun `expiry is evaluated against the supplied instant, and the boundary is inclusive`() {
        val expiring = key(expiresAt = now)
        // At exactly its expiry the key still works: `expiresAt` is the last
        // moment of validity, so a key created with a 60s lifetime is usable
        // for a full 60 seconds rather than 59.
        expiring.isUsableAt(now) shouldBe true
        expiring.isUsableAt(now.plusMillis(1)) shouldBe false
        expiring.isUsableAt(now.minusSeconds(1)) shouldBe true
    }

    @Test
    fun `revocation wins over a still-valid expiry`() {
        key(revokedAt = now.minusSeconds(1), expiresAt = now.plusSeconds(3600)).isUsableAt(now) shouldBe false
    }

    @Test
    fun `the verifier is never rendered by toString`() {
        val k = key()
        val rendered = k.toString()
        // A hashed credential is not a credential, but publishing every stored
        // verifier into logs would hand an offline attacker the whole target
        // set for free.
        rendered.contains(k.keyHash) shouldBe false
        rendered.contains("keyHash=<redacted>") shouldBe true
        rendered.contains(k.publicId!!) shouldBe true
    }

    @Test
    fun `scopes are checked exactly, never by prefix or superset accident`() {
        val k = key().copy(scopes = setOf(ApiScope.MESSAGES_READ))
        k.hasScope(ApiScope.MESSAGES_READ) shouldBe true
        k.hasScope(ApiScope.INBOXES_WRITE) shouldBe false
        k.hasScope(ApiScope.API_KEYS_MANAGE) shouldBe false
    }

    @Test
    fun `every scope has a stable wire name that round-trips`() {
        // The wire names are persisted in `api_key.scopes` and appear in the
        // public contract; renaming one silently would strand stored rows.
        ApiScope.entries.forEach { ApiScope.fromWire(it.wire) shouldBe it }
        ApiScope.fromWire("nope") shouldBe null
        ApiScope.API_KEYS_MANAGE.wire shouldBe "api-keys:manage"
    }
}
