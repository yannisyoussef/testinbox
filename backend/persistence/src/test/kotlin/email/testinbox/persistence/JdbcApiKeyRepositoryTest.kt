package email.testinbox.persistence

import email.testinbox.application.port.ApiKeyCursor
import email.testinbox.application.port.RevokeApiKeyOutcome
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiKeyFormat
import email.testinbox.domain.tenant.ApiKeyKind
import email.testinbox.domain.tenant.ApiScope
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JdbcApiKeyRepositoryTest : PersistenceIntegrationTest() {
    @Autowired lateinit var apiKeys: JdbcApiKeyRepository

    @Autowired lateinit var jdbc: JdbcClient

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun tenant(): Pair<WorkspaceId, ProjectId> = Fixtures.provisionTenant(jdbc)

    private fun managed(
        workspaceId: WorkspaceId,
        projectId: ProjectId,
        scopes: Set<ApiScope> = setOf(ApiScope.MESSAGES_READ),
        createdAt: Instant = now,
        expiresAt: Instant? = null,
        revokedAt: Instant? = null,
        name: String? = "ci",
        createdBy: ApiKeyId? = null,
    ): Pair<ApiKey, email.testinbox.domain.tenant.ApiKeyCredential> {
        val credential = ApiKeyFormat.generate()
        val key =
            ApiKey(
                id = ApiKeyId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = projectId,
                keyHash = sha256(credential.secret),
                scopes = scopes,
                createdAt = createdAt,
                revokedAt = revokedAt,
                kind = ApiKeyKind.MANAGED,
                publicId = credential.publicId,
                name = name,
                expiresAt = expiresAt,
                createdByApiKeyId = createdBy,
            )
        apiKeys.insert(key)
        return key to credential
    }

    @Test
    fun `an inserted key round-trips every field`() {
        val (workspaceId, projectId) = tenant()
        val creator = managed(workspaceId, projectId).first
        val (key, credential) =
            managed(
                workspaceId,
                projectId,
                scopes = setOf(ApiScope.INBOXES_WRITE, ApiScope.API_KEYS_MANAGE),
                expiresAt = now.plusSeconds(3600),
                name = "github-ci",
                createdBy = creator.id,
            )

        val loaded = apiKeys.findByPublicId(credential.publicId).shouldNotBeNull()
        loaded shouldBe key
    }

    @Test
    fun `no column anywhere holds the plaintext credential`() {
        val (workspaceId, projectId) = tenant()
        val (_, credential) = managed(workspaceId, projectId)
        val plaintext = credential.render()

        // Scan the whole row as text rather than the columns we happen to
        // remember: a future column that stored the credential would otherwise
        // slip past a field-by-field assertion.
        val rowText =
            jdbc
                .sql("SELECT api_key::text FROM api_key WHERE public_id = :publicId")
                .param("publicId", credential.publicId)
                .query(String::class.java)
                .single()
        rowText.contains(plaintext) shouldBe false
        rowText.contains(credential.secret) shouldBe false
        // The public id is present by design — it is the lookup handle.
        rowText.contains(credential.publicId) shouldBe true
        rowText.contains(sha256(credential.secret)) shouldBe true
    }

    @Test
    fun `a public id is unique across the table`() {
        val (workspaceId, projectId) = tenant()
        val (key, credential) = managed(workspaceId, projectId)
        val clash =
            key.copy(
                id = ApiKeyId(UUID.randomUUID()),
                keyHash = sha256("something-else"),
                publicId = credential.publicId,
            )
        runCatching { apiKeys.insert(clash) }.isFailure shouldBe true
    }

    @Test
    fun `bootstrap and managed lookups cannot reach each other's rows`() {
        val (workspaceId, projectId) = tenant()
        val bootstrapSecret = "a-configured-bootstrap-secret"
        apiKeys.ensureApiKey(
            ApiKey(
                id = ApiKeyId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = projectId,
                keyHash = sha256(bootstrapSecret),
                scopes = setOf(ApiScope.API_KEYS_MANAGE),
                createdAt = now,
                revokedAt = null,
                kind = ApiKeyKind.BOOTSTRAP,
            ),
        )
        val (_, credential) = managed(workspaceId, projectId)

        apiKeys.findBootstrapByHash(sha256(bootstrapSecret)).shouldNotBeNull()
        // The managed key's verifier must not resolve through the bootstrap
        // path, or the ADR-032 §8 window could be bypassed by presenting a
        // managed secret as a bootstrap token.
        apiKeys.findBootstrapByHash(sha256(credential.secret)).shouldBeNull()
    }

    @Test
    fun `a bootstrap row is invisible to every management query`() {
        val (workspaceId, projectId) = tenant()
        val bootstrapId = ApiKeyId(UUID.randomUUID())
        apiKeys.ensureApiKey(
            ApiKey(
                id = bootstrapId,
                workspaceId = workspaceId,
                projectId = projectId,
                keyHash = sha256("bootstrap-${UUID.randomUUID()}"),
                scopes = setOf(ApiScope.API_KEYS_MANAGE),
                createdAt = now,
                revokedAt = null,
                kind = ApiKeyKind.BOOTSTRAP,
            ),
        )
        apiKeys.findById(workspaceId, bootstrapId).shouldBeNull()
        apiKeys.listPage(workspaceId, null, 50).none { it.id == bootstrapId } shouldBe true
        apiKeys.revoke(workspaceId, bootstrapId, now) shouldBe RevokeApiKeyOutcome.NotFound
    }

    @Test
    fun `hasUsableManagedAdmin tracks scope, revocation and expiry`() {
        val (workspaceId, projectId) = tenant()
        apiKeys.hasUsableManagedAdmin(workspaceId, now) shouldBe false

        // A non-admin key must not close the bootstrap window.
        managed(workspaceId, projectId, scopes = setOf(ApiScope.INBOXES_WRITE))
        apiKeys.hasUsableManagedAdmin(workspaceId, now) shouldBe false

        val admin = managed(workspaceId, projectId, scopes = setOf(ApiScope.API_KEYS_MANAGE)).first
        apiKeys.hasUsableManagedAdmin(workspaceId, now) shouldBe true

        // An expired admin does not keep it closed.
        val expiring =
            managed(
                workspaceId,
                projectId,
                scopes = setOf(ApiScope.API_KEYS_MANAGE),
                expiresAt = now.plusSeconds(60),
            ).first
        apiKeys.revoke(workspaceId, admin.id, now)
        apiKeys.hasUsableManagedAdmin(workspaceId, now) shouldBe true
        apiKeys.hasUsableManagedAdmin(workspaceId, now.plusSeconds(61)) shouldBe false
        expiring.expiresAt shouldBe now.plusSeconds(60)
    }

    @Test
    fun `an admin key in another workspace does not close this workspace's window`() {
        val (mine, myProject) = tenant()
        val (theirs, theirProject) = tenant()
        managed(theirs, theirProject, scopes = setOf(ApiScope.API_KEYS_MANAGE))
        apiKeys.hasUsableManagedAdmin(mine, now) shouldBe false
        managed(mine, myProject, scopes = setOf(ApiScope.API_KEYS_MANAGE))
        apiKeys.hasUsableManagedAdmin(mine, now) shouldBe true
    }

    @Test
    fun `revocation retains the row and is reported idempotently`() {
        val (workspaceId, projectId) = tenant()
        val (key, _) = managed(workspaceId, projectId)

        apiKeys.revoke(workspaceId, key.id, now) shouldBe RevokeApiKeyOutcome.Revoked
        apiKeys.revoke(workspaceId, key.id, now.plusSeconds(1)) shouldBe RevokeApiKeyOutcome.AlreadyRevoked

        // The first revocation is the fact the audit trail refers to, so a
        // second must not move the timestamp.
        val after = apiKeys.findById(workspaceId, key.id).shouldNotBeNull()
        after shouldBe key.copy(revokedAt = now)
    }

    @Test
    fun `revocation is workspace-scoped`() {
        val (mine, myProject) = tenant()
        val (theirs, _) = tenant()
        val (key, _) = managed(mine, myProject)
        apiKeys.revoke(theirs, key.id, now) shouldBe RevokeApiKeyOutcome.NotFound
        apiKeys.findById(mine, key.id)!!.revokedAt.shouldBeNull()
    }

    @Test
    fun `concurrent revocation of one key yields exactly one Revoked across real transactions`() {
        val (workspaceId, projectId) = tenant()
        val (key, _) = managed(workspaceId, projectId)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        try {
            val futures =
                (1..threads).map {
                    pool.submit<RevokeApiKeyOutcome> {
                        gate.await()
                        apiKeys.revoke(workspaceId, key.id, now)
                    }
                }
            gate.countDown()
            val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }
            // The guarded UPDATE is what makes this true; a SELECT-then-UPDATE
            // would let several racers each claim the revocation.
            outcomes.count { it == RevokeApiKeyOutcome.Revoked } shouldBe 1
            outcomes.count { it == RevokeApiKeyOutcome.AlreadyRevoked } shouldBe threads - 1
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `listing is newest first, workspace-scoped, and pages by a total order`() {
        val (workspaceId, projectId) = tenant()
        val (otherWorkspace, otherProject) = tenant()
        managed(otherWorkspace, otherProject, name = "not-mine")

        // Identical timestamps on purpose: without the id tiebreak the keyset
        // page could skip or repeat rows created in the same instant.
        val sameInstant = (1..4).map { managed(workspaceId, projectId, createdAt = now).first }
        val older = managed(workspaceId, projectId, createdAt = now.minusSeconds(10)).first

        val all = mutableListOf<ApiKey>()
        var cursor: ApiKeyCursor? = null
        do {
            val page = apiKeys.listPage(workspaceId, cursor, 2)
            all += page
            cursor = page.lastOrNull()?.let { ApiKeyCursor(it.createdAt, it.id) }
        } while (page.size == 2)

        all shouldHaveSize 5
        all.map { it.id }.toSet() shouldHaveSize 5
        all.last().id shouldBe older.id
        all.none { it.name == "not-mine" } shouldBe true
        all.take(4).map { it.id }.toSet() shouldBe sameInstant.map { it.id }.toSet()
    }

    @Test
    fun `the last-used refresh writes at most once per interval`() {
        val (workspaceId, projectId) = tenant()
        val (key, _) = managed(workspaceId, projectId)
        val threshold = now.minusSeconds(300)

        apiKeys.touchLastUsed(key.id, now, threshold) shouldBe true
        // Second call within the interval: the WHERE clause matches nothing.
        apiKeys.touchLastUsed(key.id, now.plusSeconds(1), threshold) shouldBe false
        apiKeys.findById(workspaceId, key.id)!!.lastUsedAt shouldBe now

        // Once the threshold moves past the stored value, a write happens again.
        apiKeys.touchLastUsed(key.id, now.plusSeconds(600), now.plusSeconds(300)) shouldBe true
        apiKeys.findById(workspaceId, key.id)!!.lastUsedAt shouldBe now.plusSeconds(600)
    }

    @Test
    fun `the last-used refresh writes nothing but the timestamp`() {
        // Asserted here rather than against the in-memory double, whose
        // `touchLastUsed` is a `copy(lastUsedAt = ...)` and so cannot fail.
        val (workspaceId, projectId) = tenant()
        val (key, _) = managed(workspaceId, projectId, scopes = setOf(ApiScope.INBOXES_WRITE))

        apiKeys.touchLastUsed(key.id, now, now.minusSeconds(300)) shouldBe true

        val after = apiKeys.findById(workspaceId, key.id).shouldNotBeNull()
        after shouldBe key.copy(lastUsedAt = now)
    }

    @Test
    fun `the expiry boundary agrees with the domain, so the bootstrap door opens at one instant`() {
        // `ApiKey.isExpired` treats `expiresAt` as the last moment of validity.
        // The SQL used `expires_at > :at`, which excluded equality — a
        // one-microsecond window in which the domain called an admin key usable
        // and the query that closes the bootstrap door did not.
        val (workspaceId, projectId) = tenant()
        val admin =
            managed(
                workspaceId,
                projectId,
                scopes = setOf(ApiScope.API_KEYS_MANAGE),
                expiresAt = now.plusSeconds(60),
            ).first

        val boundary = now.plusSeconds(60)
        admin.isUsableAt(boundary) shouldBe true
        apiKeys.hasUsableManagedAdmin(workspaceId, boundary) shouldBe true
        admin.isUsableAt(boundary.plusMillis(1)) shouldBe false
        apiKeys.hasUsableManagedAdmin(workspaceId, boundary.plusMillis(1)) shouldBe false
    }

    @Test
    fun `concurrent last-used refreshes persist exactly one write`() {
        val (workspaceId, projectId) = tenant()
        val (key, _) = managed(workspaceId, projectId)
        val threshold = now.minusSeconds(300)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        try {
            val futures =
                (1..threads).map {
                    pool.submit<Boolean> {
                        gate.await()
                        apiKeys.touchLastUsed(key.id, now, threshold)
                    }
                }
            gate.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }.count { it } shouldBe 1
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the schema refuses a managed key without a public id, and a bootstrap key with one`() {
        val (workspaceId, projectId) = tenant()
        val base = managed(workspaceId, projectId).first
        // The two kinds are structurally distinguishable, which is what keeps
        // the two authentication paths disjoint.
        runCatching {
            apiKeys.insert(base.copy(id = ApiKeyId(UUID.randomUUID()), keyHash = sha256("a"), publicId = null))
        }.isFailure shouldBe true
        runCatching {
            apiKeys.insert(
                base.copy(
                    id = ApiKeyId(UUID.randomUUID()),
                    keyHash = sha256("b"),
                    kind = ApiKeyKind.BOOTSTRAP,
                    publicId = "abcdefghijklmnop",
                ),
            )
        }.isFailure shouldBe true
    }

    @Test
    fun `re-provisioning a bootstrap key grants newly added scopes`() {
        val (workspaceId, projectId) = tenant()
        val secret = "bootstrap-${UUID.randomUUID()}"
        val row =
            ApiKey(
                id = ApiKeyId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = projectId,
                keyHash = sha256(secret),
                scopes = setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ),
                createdAt = now,
                revokedAt = null,
                kind = ApiKeyKind.BOOTSTRAP,
            )
        apiKeys.ensureApiKey(row)
        // An environment provisioned before `api-keys:manage` existed must gain
        // it on restart, or its bootstrap key could never hand over.
        apiKeys.ensureApiKey(row.copy(scopes = row.scopes + ApiScope.API_KEYS_MANAGE))

        apiKeys
            .findBootstrapByHash(sha256(secret))!!
            .scopes
            .map { it.wire }
            .sorted() shouldContainExactly listOf("api-keys:manage", "inboxes:write", "messages:read")
    }
}
