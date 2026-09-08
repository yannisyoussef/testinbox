package email.testinbox.application.usecase

import email.testinbox.application.InMemoryApiKeyRepository
import email.testinbox.application.MutableClock
import email.testinbox.application.RecordingApiKeyMetrics
import email.testinbox.application.RecordingAuditLog
import email.testinbox.application.Sha256
import email.testinbox.application.port.ApiKeyOperation
import email.testinbox.application.port.AuditEvent
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
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApiKeyLifecycleTest {
    private val workspaceId = WorkspaceId(UUID.randomUUID())
    private val projectId = ProjectId(UUID.randomUUID())
    private val clock = MutableClock(Instant.parse("2026-09-08T12:00:00Z"))
    private val apiKeys = InMemoryApiKeyRepository()
    private val metrics = RecordingApiKeyMetrics()
    private val audit = RecordingAuditLog()
    private val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(99L) }

    private val create = CreateApiKey(apiKeys, clock, audit, metrics, random)
    private val revoke = RevokeApiKey(apiKeys, clock, audit, metrics)
    private val queries = ApiKeyQueries(apiKeys)

    private val admin =
        apiKeys.put(
            ApiKey(
                id = ApiKeyId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = projectId,
                keyHash = "0".repeat(64),
                scopes = ApiScope.entries.toSet(),
                createdAt = clock.now.minusSeconds(3600),
                revokedAt = null,
                kind = ApiKeyKind.MANAGED,
                publicId = "aaaaaaaaaaaaaaaa",
            ),
        )

    private fun command(
        name: String? = "github-ci",
        scopes: Set<ApiScope> = setOf(ApiScope.INBOXES_WRITE),
        expiresIn: Duration? = null,
        actor: ApiKey = admin,
    ) = CreateApiKey.Command(actor, name, scopes, expiresIn)

    @Test
    fun `creation returns the plaintext exactly once and stores only a verifier`() {
        val created = create.execute(command()).shouldBeInstanceOf<CreateApiKey.Result.Created>()
        val plaintext = created.credential.render()

        // The stored row must not contain the credential in any form other
        // than the one-way verifier — this is the whole of ADR-032 §4.
        val stored = apiKeys.keys.getValue(created.apiKey.id)
        stored.keyHash shouldBe Sha256.hex(created.credential.secret)
        (stored.keyHash == plaintext) shouldBe false
        (stored.keyHash == created.credential.secret) shouldBe false
        stored.publicId shouldBe created.credential.publicId

        // And nothing that is read back afterwards can reproduce it.
        val fetched = queries.get(admin, created.apiKey.id).shouldNotBeNull()
        fetched.toString().contains(created.credential.secret) shouldBe false
    }

    @Test
    fun `a created key belongs to the creator's workspace and records its provenance`() {
        val created = create.execute(command()).shouldBeInstanceOf<CreateApiKey.Result.Created>()
        created.apiKey.workspaceId shouldBe admin.workspaceId
        created.apiKey.projectId shouldBe admin.projectId
        created.apiKey.createdByApiKeyId shouldBe admin.id
        created.apiKey.kind shouldBe ApiKeyKind.MANAGED
    }

    @Test
    fun `a key cannot grant a scope its creator does not hold`() {
        val limited = admin.copy(id = ApiKeyId(UUID.randomUUID()), scopes = setOf(ApiScope.API_KEYS_MANAGE))
        // Without this, `api-keys:manage` would silently be a superuser scope:
        // mint yourself anything, then use it.
        val result =
            create
                .execute(command(scopes = setOf(ApiScope.INBOXES_WRITE), actor = limited))
                .shouldBeInstanceOf<CreateApiKey.Result.ScopeEscalation>()
        result.missing shouldBe setOf(ApiScope.INBOXES_WRITE)
        apiKeys.keys.values.none { it.createdByApiKeyId == limited.id } shouldBe true
    }

    @Test
    fun `a key may grant a subset of its creator's scopes`() {
        val ci = admin.copy(id = ApiKeyId(UUID.randomUUID()), scopes = setOf(ApiScope.API_KEYS_MANAGE, ApiScope.MESSAGES_READ))
        create
            .execute(command(scopes = setOf(ApiScope.MESSAGES_READ), actor = ci))
            .shouldBeInstanceOf<CreateApiKey.Result.Created>()
    }

    @Test
    fun `an empty scope set is refused rather than minting a key that can do nothing`() {
        create.execute(command(scopes = emptySet())) shouldBe CreateApiKey.Result.NoScopesRequested
    }

    @Test
    fun `an implausibly short lifetime is refused at creation, not discovered as a 401`() {
        create
            .execute(command(expiresIn = Duration.ofSeconds(5)))
            .shouldBeInstanceOf<CreateApiKey.Result.ExpiryTooSoon>()
        create
            .execute(command(expiresIn = Duration.ofSeconds(60)))
            .shouldBeInstanceOf<CreateApiKey.Result.Created>()
    }

    @Test
    fun `an over-long name is refused`() {
        create
            .execute(command(name = "n".repeat(CreateApiKey.MAX_NAME_LENGTH + 1)))
            .shouldBeInstanceOf<CreateApiKey.Result.NameTooLong>()
    }

    @Test
    fun `a blank name is stored as absent rather than as an empty string`() {
        val created = create.execute(command(name = "   ")).shouldBeInstanceOf<CreateApiKey.Result.Created>()
        created.apiKey.name.shouldBeNull()
    }

    @Test
    fun `expiry is computed from the clock, not from wall time`() {
        val created =
            create
                .execute(command(expiresIn = Duration.ofHours(2)))
                .shouldBeInstanceOf<CreateApiKey.Result.Created>()
        created.apiKey.expiresAt shouldBe clock.now.plus(Duration.ofHours(2))
    }

    @Test
    fun `revocation is recorded, retains the row, and is idempotent`() {
        val created = create.execute(command()).shouldBeInstanceOf<CreateApiKey.Result.Created>()
        revoke.execute(admin, created.apiKey.id) shouldBe RevokeApiKeyOutcome.Revoked

        // Retained, not deleted: a compromised key's history is the evidence.
        val after = queries.get(admin, created.apiKey.id).shouldNotBeNull()
        after.revokedAt shouldBe clock.now
        after.createdAt shouldBe created.apiKey.createdAt

        // A retry after a network blip must not be reported as a failure.
        revoke.execute(admin, created.apiKey.id) shouldBe RevokeApiKeyOutcome.AlreadyRevoked
        metrics.lifecycle shouldContainExactly
            listOf(ApiKeyOperation.CREATED, ApiKeyOperation.REVOKED, ApiKeyOperation.REVOKE_NOOP)
    }

    @Test
    fun `revoking a key from another workspace is NotFound, never a hint that it exists`() {
        val foreign =
            apiKeys.put(
                admin.copy(
                    id = ApiKeyId(UUID.randomUUID()),
                    workspaceId = WorkspaceId(UUID.randomUUID()),
                    publicId = "bbbbbbbbbbbbbbbb",
                ),
            )
        revoke.execute(admin, foreign.id) shouldBe RevokeApiKeyOutcome.NotFound
        queries.get(admin, foreign.id).shouldBeNull()
        // Still usable in its own workspace — we refused, we did not act.
        apiKeys.keys
            .getValue(foreign.id)
            .revokedAt
            .shouldBeNull()
        // And nothing was audited, because nothing happened here.
        audit.events.none { it is AuditEvent.ApiKeyRevoked } shouldBe true
    }

    @Test
    fun `revoking the last administrative key is permitted`() {
        // Refusing would forbid revoking a *compromised* administrator, which
        // is exactly when it matters. Recovery (the bootstrap window reopening)
        // is the safer answer than prevention — ADR-032 §8.
        revoke.execute(admin, admin.id) shouldBe RevokeApiKeyOutcome.Revoked
        apiKeys.hasUsableManagedAdmin(workspaceId, clock.now) shouldBe false
    }

    @Test
    fun `concurrent revocations of the same key produce exactly one revocation`() {
        val created = create.execute(command()).shouldBeInstanceOf<CreateApiKey.Result.Created>()
        val threads = 8
        val start = CountDownLatch(1)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<RevokeApiKeyOutcome>())
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                start.await()
                outcomes += revoke.execute(admin, created.apiKey.id)
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await(5, TimeUnit.SECONDS) shouldBe true

        // A SELECT-then-UPDATE would let several racers each believe they
        // performed the revocation, and the audit log would then claim a key
        // was revoked repeatedly by different actors.
        outcomes.count { it == RevokeApiKeyOutcome.Revoked } shouldBe 1
        outcomes.count { it == RevokeApiKeyOutcome.AlreadyRevoked } shouldBe threads - 1
    }

    @Test
    fun `concurrent creation produces distinct credentials and distinct public ids`() {
        val threads = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val results = java.util.Collections.synchronizedList(mutableListOf<CreateApiKey.Result.Created>())
        repeat(threads) {
            Thread {
                start.await()
                (create.execute(command()) as? CreateApiKey.Result.Created)?.let { results += it }
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await(10, TimeUnit.SECONDS) shouldBe true

        results shouldHaveSize threads
        results.map { it.apiKey.id }.toSet() shouldHaveSize threads
        results.map { it.credential.publicId }.toSet() shouldHaveSize threads
        results.map { it.credential.secret }.toSet() shouldHaveSize threads
    }

    @Test
    fun `listing is workspace-scoped, newest first, and pages without repeating`() {
        val ids =
            (1..5).map { i ->
                clock.now = clock.now.plusSeconds(i.toLong())
                (create.execute(command(name = "key-$i")) as CreateApiKey.Result.Created).apiKey.id
            }
        // Another tenant's key must be invisible, not merely unreturned by luck.
        apiKeys.put(
            admin.copy(id = ApiKeyId(UUID.randomUUID()), workspaceId = WorkspaceId(UUID.randomUUID()), publicId = "cccccccccccccccc"),
        )

        val firstPage = queries.list(admin, null, 3)
        firstPage shouldHaveSize 3
        val secondPage = queries.list(admin, ApiKeyPaging.cursorOf(firstPage.last()), 3)
        val seen = (firstPage + secondPage).map { it.id }
        seen.toSet() shouldHaveSize seen.size
        // The admin key itself is oldest, so it lands last.
        seen.take(5) shouldContainExactly ids.reversed()
        seen.last() shouldBe admin.id
    }

    @Test
    fun `the audit trail records creation and revocation without any secret material`() {
        val created = create.execute(command()).shouldBeInstanceOf<CreateApiKey.Result.Created>()
        revoke.execute(admin, created.apiKey.id)

        val creation = audit.events.first().shouldBeInstanceOf<AuditEvent.ApiKeyCreated>()
        creation.actorApiKeyId shouldBe admin.id
        creation.apiKeyId shouldBe created.apiKey.id
        creation.publicId shouldBe created.credential.publicId

        val serialized = audit.events.joinToString(" ") { it.toString() }
        serialized.contains(created.credential.secret) shouldBe false
        serialized.contains(created.credential.render()) shouldBe false
        serialized.contains(Sha256.hex(created.credential.secret)) shouldBe false
    }

    @Test
    fun `a bootstrap row is never visible to the management API`() {
        val bootstrapKey =
            apiKeys.put(
                admin.copy(
                    id = ApiKeyId(UUID.randomUUID()),
                    kind = ApiKeyKind.BOOTSTRAP,
                    publicId = null,
                    keyHash = Sha256.hex("bootstrap"),
                ),
            )
        // Listing a credential the API cannot revoke would advertise a control
        // the caller does not actually have.
        queries.list(admin, null, 50).none { it.id == bootstrapKey.id } shouldBe true
        queries.get(admin, bootstrapKey.id).shouldBeNull()
        revoke.execute(admin, bootstrapKey.id) shouldBe RevokeApiKeyOutcome.NotFound
    }

    @Test
    fun `generated credentials use the documented format`() {
        val created = create.execute(command()).shouldBeInstanceOf<CreateApiKey.Result.Created>()
        ApiKeyFormat.parse(created.credential.render()).shouldBeInstanceOf<email.testinbox.domain.tenant.ParsedCredential.Valid>()
    }
}
