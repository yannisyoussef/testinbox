package email.testinbox.application.usecase

import email.testinbox.application.InMemoryApiKeyRepository
import email.testinbox.application.MutableClock
import email.testinbox.application.RecordingApiKeyMetrics
import email.testinbox.application.RecordingAuditLog
import email.testinbox.application.Sha256
import email.testinbox.application.port.AuditEvent
import email.testinbox.application.port.AuthOutcome
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiKeyCredential
import email.testinbox.domain.tenant.ApiKeyFormat
import email.testinbox.domain.tenant.ApiKeyKind
import email.testinbox.domain.tenant.ApiScope
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

class AuthenticateApiKeyTest {
    private val workspaceId = WorkspaceId(UUID.randomUUID())
    private val projectId = ProjectId(UUID.randomUUID())
    private val clock = MutableClock(Instant.parse("2026-09-08T12:00:00Z"))
    private val apiKeys = InMemoryApiKeyRepository()
    private val metrics = RecordingApiKeyMetrics()
    private val audit = RecordingAuditLog()
    private val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) }

    /** The bootstrap credential this "process" is configured with. */
    private val bootstrapSecret = "a-configured-bootstrap-secret-value-with-entropy"

    private val auth =
        AuthenticateApiKey(
            apiKeys,
            clock,
            configuredBootstrapKeyHash = Sha256.hex(bootstrapSecret),
            metrics = metrics,
            audit = audit,
        )

    private fun managed(
        scopes: Set<ApiScope> = setOf(ApiScope.MESSAGES_READ),
        revokedAt: Instant? = null,
        expiresAt: Instant? = null,
        workspace: WorkspaceId = workspaceId,
    ): Pair<ApiKeyCredential, ApiKey> {
        val credential = ApiKeyFormat.generate(random)
        val key =
            apiKeys.put(
                ApiKey(
                    id = ApiKeyId(UUID.randomUUID()),
                    workspaceId = workspace,
                    projectId = projectId,
                    keyHash = Sha256.hex(credential.secret),
                    scopes = scopes,
                    createdAt = clock.now.minusSeconds(60),
                    revokedAt = revokedAt,
                    kind = ApiKeyKind.MANAGED,
                    publicId = credential.publicId,
                    expiresAt = expiresAt,
                ),
            )
        return credential to key
    }

    private fun bootstrap(plaintext: String): ApiKey =
        apiKeys.put(
            ApiKey(
                id = ApiKeyId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = projectId,
                keyHash = Sha256.hex(plaintext),
                scopes = setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ, ApiScope.API_KEYS_MANAGE),
                createdAt = clock.now.minusSeconds(600),
                revokedAt = null,
                kind = ApiKeyKind.BOOTSTRAP,
            ),
        )

    @Test
    fun `a valid credential authenticates and its context comes from the row`() {
        val (credential, key) = managed(scopes = setOf(ApiScope.INBOXES_WRITE))
        val authenticated = auth.authenticate(credential.render()).shouldNotBeNull()
        authenticated.id shouldBe key.id
        // Workspace, project and scopes are read from storage — never from the
        // token, which the holder could otherwise rewrite.
        authenticated.workspaceId shouldBe workspaceId
        authenticated.projectId shouldBe projectId
        authenticated.scopes shouldBe setOf(ApiScope.INBOXES_WRITE)
        metrics.auth shouldContainExactly listOf(AuthOutcome.SUCCESS)
    }

    @Test
    fun `a wrong secret against a real public id fails`() {
        val (credential, _) = managed()
        val other = ApiKeyFormat.generate(random)
        // Same (existing) public id, someone else's secret.
        val forged = ApiKeyFormat.render(credential.publicId, other.secret)
        auth.authenticate(forged).shouldBeNull()
        metrics.auth shouldContainExactly listOf(AuthOutcome.BAD_SECRET)
    }

    @Test
    fun `an unknown public id is reported as unknown, not as a bad secret`() {
        managed()
        auth.authenticate(ApiKeyFormat.generate(random).render()).shouldBeNull()
        metrics.auth shouldContainExactly listOf(AuthOutcome.UNKNOWN_KEY)
    }

    @Test
    fun `state is only reported after the secret verifies`() {
        // Otherwise anyone holding a leaked *public id* could learn whether
        // that credential is still live without ever holding the secret —
        // a revocation oracle over public information.
        val (credential, _) = managed(revokedAt = clock.now.minusSeconds(1))
        val forged = ApiKeyFormat.render(credential.publicId, ApiKeyFormat.generate(random).secret)
        auth.authenticate(forged).shouldBeNull()
        metrics.auth shouldContainExactly listOf(AuthOutcome.BAD_SECRET)
    }

    @Test
    fun `a revoked credential fails immediately, with no cache to wait out`() {
        val (credential, key) = managed()
        auth.authenticate(credential.render()).shouldNotBeNull()
        apiKeys.revoke(workspaceId, key.id, clock.now)
        // The very next call, on the same instance, with no invalidation step.
        auth.authenticate(credential.render()).shouldBeNull()
        metrics.auth shouldContainExactly listOf(AuthOutcome.SUCCESS, AuthOutcome.REVOKED)
    }

    @Test
    fun `an expired credential fails once the clock passes its expiry`() {
        val (credential, _) = managed(expiresAt = clock.now.plusSeconds(60))
        auth.authenticate(credential.render()).shouldNotBeNull()
        clock.now = clock.now.plusSeconds(61)
        auth.authenticate(credential.render()).shouldBeNull()
        metrics.auth shouldContainExactly listOf(AuthOutcome.SUCCESS, AuthOutcome.EXPIRED)
    }

    @Test
    fun `malformed, truncated and future-version tokens are each distinguishable`() {
        val (credential, _) = managed()
        auth.authenticate("").shouldBeNull()
        auth.authenticate("   ").shouldBeNull()
        auth.authenticate(credential.render().dropLast(4) + "aaaa").shouldBeNull()
        auth.authenticate("ti_k9_${credential.publicId}_${credential.secret}_aaaa").shouldBeNull()
        metrics.auth shouldContainExactly
            listOf(
                AuthOutcome.MALFORMED,
                AuthOutcome.MALFORMED,
                AuthOutcome.CHECKSUM_MISMATCH,
                AuthOutcome.UNSUPPORTED_VERSION,
            )
    }

    @Test
    fun `a credential cannot be used to reach another workspace`() {
        val otherWorkspace = WorkspaceId(UUID.randomUUID())
        val (credential, _) = managed(workspace = otherWorkspace)
        // Authentication succeeds — but into the workspace the row names, not
        // one the caller picked. Cross-workspace access is impossible because
        // there is no input that selects a workspace.
        auth.authenticate(credential.render())!!.workspaceId shouldBe otherWorkspace
    }

    @Test
    fun `the bootstrap credential works while no managed administrator exists`() {
        val plaintext = bootstrapSecret
        val key = bootstrap(plaintext)
        auth.authenticate(plaintext)!!.id shouldBe key.id
        metrics.auth shouldContainExactly listOf(AuthOutcome.BOOTSTRAP)
    }

    @Test
    fun `minting the first managed administrator closes the bootstrap window with no restart`() {
        val plaintext = bootstrapSecret
        bootstrap(plaintext)
        auth.authenticate(plaintext).shouldNotBeNull()

        managed(scopes = setOf(ApiScope.API_KEYS_MANAGE))

        // Same process, same configuration, no operator action.
        auth.authenticate(plaintext).shouldBeNull()
        metrics.auth.last() shouldBe AuthOutcome.BOOTSTRAP_SUPERSEDED
        audit.events.last().shouldBeInstanceOf<AuditEvent.BootstrapSuperseded>()
    }

    @Test
    fun `a non-administrative managed key does not close the bootstrap window`() {
        val plaintext = bootstrapSecret
        bootstrap(plaintext)
        // A CI key exists, but nobody can manage credentials with it — closing
        // the window here would lock the workspace out of ever minting one.
        managed(scopes = setOf(ApiScope.INBOXES_WRITE))
        auth.authenticate(plaintext).shouldNotBeNull()
    }

    @Test
    fun `the window reopens when every managed administrator is revoked`() {
        val plaintext = bootstrapSecret
        bootstrap(plaintext)
        val (_, admin) = managed(scopes = setOf(ApiScope.API_KEYS_MANAGE))
        auth.authenticate(plaintext).shouldBeNull()

        // The break-glass path, and the reason revoking the last admin key is
        // permitted rather than refused (ADR-032 §8).
        apiKeys.revoke(workspaceId, admin.id, clock.now)
        auth.authenticate(plaintext).shouldNotBeNull()
    }

    @Test
    fun `an expired managed administrator does not keep the window closed`() {
        val plaintext = bootstrapSecret
        bootstrap(plaintext)
        managed(scopes = setOf(ApiScope.API_KEYS_MANAGE), expiresAt = clock.now.plusSeconds(30))
        auth.authenticate(plaintext).shouldBeNull()
        clock.now = clock.now.plusSeconds(31)
        // Otherwise a workspace whose only admin key expired would have no way
        // back in at all.
        auth.authenticate(plaintext).shouldNotBeNull()
    }

    @Test
    fun `a bootstrap secret cannot be presented as a managed credential, or the reverse`() {
        val plaintext = bootstrapSecret
        val bootstrapKey = bootstrap(plaintext)
        val (credential, _) = managed()

        // The two paths hash different inputs and query different rows. If they
        // ever shared a lookup, a bootstrap row could satisfy a managed
        // credential and the §8 window would be bypassable.
        apiKeys.findByPublicId(bootstrapKey.publicId ?: "none").shouldBeNull()
        apiKeys.findBootstrapByHash(Sha256.hex(credential.secret)).shouldBeNull()
    }

    @Test
    fun `a revoked bootstrap credential does not authenticate`() {
        val plaintext = bootstrapSecret
        val key = bootstrap(plaintext)
        apiKeys.put(key.copy(revokedAt = clock.now))
        auth.authenticate(plaintext).shouldBeNull()
        metrics.auth shouldContainExactly listOf(AuthOutcome.REVOKED)
    }

    @Test
    fun `a bootstrap credential that is no longer the configured one does not authenticate`() {
        // Rotating the setting is what retires the old credential. Before this,
        // a provisioned bootstrap row authenticated forever: changing the
        // configured secret after a leak provisioned a SECOND row and left the
        // first live, and no management endpoint can revoke a bootstrap row.
        val retired = "a-previous-bootstrap-secret-value-with-entropy"
        bootstrap(retired)
        auth.authenticate(retired).shouldBeNull()
        metrics.auth shouldContainExactly listOf(AuthOutcome.UNKNOWN_KEY)
    }

    @Test
    fun `with no bootstrap credential configured there is genuinely no break-glass`() {
        bootstrap(bootstrapSecret)
        val unconfigured = AuthenticateApiKey(apiKeys, clock, configuredBootstrapKeyHash = null, metrics = metrics)
        // `docs/dev/api-keys.md` promises exactly this, and it has to be true
        // of the row that already exists, not only of one never provisioned.
        unconfigured.authenticate(bootstrapSecret).shouldBeNull()
    }

    @Test
    fun `every attempt is counted exactly once, whatever its outcome`() {
        val (credential, _) = managed()
        auth.authenticate(credential.render())
        auth.authenticate("garbage")
        auth.authenticate(ApiKeyFormat.generate(random).render())
        metrics.auth.size shouldBe 3
    }
}
