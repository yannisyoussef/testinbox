package email.testinbox.persistence

import email.testinbox.application.idempotency.Idempotency
import email.testinbox.application.idempotency.IdempotencyRequest
import email.testinbox.application.port.AuditEvent
import email.testinbox.application.port.AuditLog
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.application.usecase.AuthenticateHumanSession
import email.testinbox.application.usecase.CreateApiKey
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.application.usecase.ResolveHumanWorkspace
import email.testinbox.application.usecase.RevokeHumanSession
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.idempotency.IdempotencyKey
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.tenant.ApiScope
import email.testinbox.domain.tenant.HumanWorkspaceOwner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HumanWorkspaceActorTest : PersistenceIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var sessions: JdbcHumanSessions

    @Autowired lateinit var admissions: JdbcBetaAdmissions

    @Autowired lateinit var keys: JdbcApiKeyRepository

    @Autowired lateinit var records: JdbcIdempotencyRecords

    @Autowired lateinit var tx: TransactionRunner
    private val clock = Clock.systemUTC()
    private val events = mutableListOf<AuditEvent>()
    private val audit =
        object : AuditLog {
            override fun record(event: AuditEvent) {
                synchronized(events) { events += event }
            }
        }

    @BeforeEach fun reset() {
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("DELETE FROM human_account").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open=true,capacity_ready=true").update()
        events.clear()
    }

    private fun applicant(): HumanAccountId {
        val id = HumanAccountId(UUID.randomUUID())
        jdbc
            .sql(
                """
                INSERT INTO human_account(id,contact_email,contact_verified_at,created_at)
                VALUES(:id,:email,now(),now())
                """.trimIndent(),
            ).param("id", id.value)
            .param("email", "${id.value}@example.invalid")
            .update()
        return id
    }

    private fun resolver() = ResolveHumanWorkspace(AuthenticateHumanSession(sessions, tx, clock), admissions, tx)

    private fun login(id: HumanAccountId) = requireNotNull(IssueHumanSession(sessions, tx, clock).execute(id))

    private fun owner(): Pair<HumanWorkspaceOwner, IssueHumanSession.Issued> {
        val id = applicant()
        AdmitBetaParticipant(admissions, tx, clock).execute(id).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()
        val session = login(id)
        return requireNotNull(resolver().execute(session.credential.value)) to session
    }

    private fun create(repository: email.testinbox.application.port.ApiKeyRepository = keys) =
        CreateApiKey(repository, clock, audit, idempotency = Idempotency(records, tx, clock))

    private fun command(owner: HumanWorkspaceOwner) = CreateApiKey.Command(owner, "SDK", setOf(ApiScope.MESSAGES_READ), Duration.ofDays(30))

    private fun claim(
        owner: HumanWorkspaceOwner,
        key: String = "human-key-first-request",
    ) = IdempotencyRequest(requireNotNull(IdempotencyKey.of(key)), null, owner.accountId)

    @Test fun `a verified unadmitted session has no product actor and no automatic workspace`() {
        val id = applicant()
        val session = login(id)
        resolver().execute(session.credential.value) shouldBe null
        resolver().execute("forged") shouldBe null
        jdbc.sql("SELECT count(*) FROM beta_admission").query(Long::class.javaObjectType).single() shouldBe 0
    }

    @Test fun `each session resolves only its stored membership with the explicit current scope ceiling`() {
        val (first, a) = owner()
        val (second, b) = owner()
        resolver().execute(a.credential.value) shouldBe first
        resolver().execute(b.credential.value) shouldBe second
        (first.workspaceId == second.workspaceId) shouldBe false
        first.scopes shouldBe setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ, ApiScope.API_KEYS_MANAGE)
    }

    @Test fun `logout suspension deletion pending and removed membership immediately refuse new authority`() {
        val (first, session) = owner()
        RevokeHumanSession(sessions, tx).execute(session.credential.value)
        resolver().execute(session.credential.value) shouldBe null
        for (state in listOf("SUSPENDED", "DELETION_PENDING")) {
            val (actor, credential) = owner()
            jdbc
                .sql("UPDATE human_account SET state=:state WHERE id=:id")
                .param("state", state)
                .param("id", actor.accountId.value)
                .update()
            resolver().execute(credential.credential.value) shouldBe null
        }
        val fresh = login(first.accountId)
        jdbc.sql("UPDATE beta_admission SET account_id=NULL WHERE account_id=:id").param("id", first.accountId.value).update()
        resolver().execute(fresh.credential.value) shouldBe null
    }

    @Test fun `human creation persists only key verifier and distinct human provenance with replay suppression`() {
        val (actor, _) = owner()
        val result = create().execute(command(actor), claim(actor)).shouldBeInstanceOf<CreateApiKey.Result.Created>()
        val row = requireNotNull(keys.findByPublicId(result.credential.publicId))
        row.createdByHumanAccountId shouldBe actor.accountId
        row.createdByApiKeyId shouldBe null
        row.toString().contains(result.credential.secret) shouldBe false
        (row.keyHash == result.credential.secret) shouldBe false
        val event = events.single().shouldBeInstanceOf<AuditEvent.ApiKeyCreated>()
        event.actorHumanAccountId shouldBe actor.accountId
        event.actorApiKeyId shouldBe null
        val replay = create().execute(command(actor), claim(actor)).shouldBeInstanceOf<CreateApiKey.Result.AlreadyCreated>()
        replay.apiKeyId shouldBe row.id
        events.size shouldBe 1
        jdbc
            .sql("SELECT created_by_human_account_id FROM idempotency_record WHERE workspace_id=:id")
            .param("id", actor.workspaceId.value)
            .query(UUID::class.java)
            .single() shouldBe actor.accountId.value
    }

    @Test fun `repeated human login preserves the key replay actor but changing to a machine actor cannot claim it`() {
        val (actor, _) = owner()
        val original = CreateApiKey.Command(actor, "SDK", setOf(ApiScope.MESSAGES_READ, ApiScope.API_KEYS_MANAGE), Duration.ofDays(30))
        val result = create().execute(original, claim(actor)).shouldBeInstanceOf<CreateApiKey.Result.Created>()
        val anotherSession = login(actor.accountId)
        val again = requireNotNull(resolver().execute(anotherSession.credential.value))
        create().execute(original.copy(actor = again), claim(again)).shouldBeInstanceOf<CreateApiKey.Result.AlreadyCreated>()
        val machine = requireNotNull(keys.findByPublicId(result.credential.publicId))
        val request = IdempotencyRequest(requireNotNull(IdempotencyKey.of("human-key-first-request")), machine.id)
        create()
            .execute(original.copy(actor = machine), request)
            .shouldBeInstanceOf<CreateApiKey.Result.IdempotencyKeyReused>()
    }

    @Test fun `a mismatched human claim is refused before creating any key or replay metadata`() {
        val (actor, _) = owner()
        val other = applicant()
        val request = IdempotencyRequest(requireNotNull(IdempotencyKey.of("wrong-human-actor")), null, other)
        create().execute(command(actor), request) shouldBe CreateApiKey.Result.NotPermitted
        keys.listPage(actor.workspaceId, null, 20).size shouldBe 0
        events.size shouldBe 0
    }

    @Test fun `failed key insert rolls back its human idempotency claim and permits a clean retry`() {
        val (actor, _) = owner()
        val broken =
            object : email.testinbox.application.port.ApiKeyRepository by keys {
                override fun insert(apiKey: email.testinbox.domain.tenant.ApiKey) {
                    keys.insert(apiKey)
                    error("fixture post-insert failure")
                }
            }
        shouldThrow<IllegalStateException> { create(broken).execute(command(actor), claim(actor)) }
        keys.listPage(actor.workspaceId, null, 20).size shouldBe 0
        events.size shouldBe 0
        create().execute(command(actor), claim(actor)).shouldBeInstanceOf<CreateApiKey.Result.Created>()
    }

    @Test fun `concurrent duplicate human key creates share one transaction and return the plaintext once`() {
        val (actor, _) = owner()
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(6).use { pool ->
            val pending =
                List(6) {
                    pool.submit<CreateApiKey.Result> {
                        start.await(10, TimeUnit.SECONDS) shouldBe true
                        create().execute(command(actor), claim(actor))
                    }
                }
            start.countDown()
            val results = pending.map { it.get(20, TimeUnit.SECONDS) }
            results.count { it is CreateApiKey.Result.Created } shouldBe 1
            results.count { it is CreateApiKey.Result.AlreadyCreated } shouldBe 5
        }
        keys.listPage(actor.workspaceId, null, 20).size shouldBe 1
        events.size shouldBe 1
    }

    @Test fun `a request cannot represent both a human and a machine actor`() {
        val id = applicant()
        shouldThrow<IllegalArgumentException> {
            IdempotencyRequest(requireNotNull(IdempotencyKey.of("ambiguous-actor")), ApiKeyId(UUID.randomUUID()), id)
        }
    }
}
