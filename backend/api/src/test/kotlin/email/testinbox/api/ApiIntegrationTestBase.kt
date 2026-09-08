package email.testinbox.api

import email.testinbox.application.Sha256
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.ProvisioningRepository
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import email.testinbox.domain.message.ParsedContent
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiKeyFormat
import email.testinbox.domain.tenant.ApiKeyKind
import email.testinbox.domain.tenant.ApiScope
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.net.ServerSocket
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Limits are set generously here, not in @DynamicPropertySource, for two
 * reasons: every suite shares one bootstrap workspace and would otherwise
 * exhaust a realistic budget collectively, and @TestPropertySource is what a
 * subclass can override (a dynamic property source outranks it).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
    properties = [
        "testinbox.limits.max-active-inboxes=100000",
        "testinbox.limits.max-stored-bytes=1000000000",
        "testinbox.limits.max-concurrent-waits=1000",
        "testinbox.limits.inbox-create.capacity=100000",
        "testinbox.limits.inbox-create.refill-per-second=10000",
        "testinbox.limits.wait.capacity=100000",
        "testinbox.limits.wait.refill-per-second=10000",
        "testinbox.limits.download.capacity=100000",
        "testinbox.limits.download.refill-per-second=10000",
        "testinbox.limits.ingest.capacity=100000",
        "testinbox.limits.ingest.refill-per-second=10000",
        // The shipped KEY_ADMIN budget is deliberately tight (a rotation is a
        // handful of calls); a suite that mints dozens of fixture credentials
        // would exhaust it and fail on the limit rather than on the behaviour
        // under test. RateLimitApiTest asserts the real budget instead.
        "testinbox.limits.key-admin.capacity=100000",
        "testinbox.limits.key-admin.refill-per-second=10000",
        // Here rather than in @DynamicPropertySource so a subclass can override
        // it: a dynamic property source outranks @TestPropertySource.
        "testinbox.wait-window-cap=5s",
        // Likewise — BootstrapTransitionTest needs its own bootstrap workspace,
        // and a dynamic property source could not be overridden by one.
        "testinbox.bootstrap.api-key=tk_test_bootstrap_key",
    ],
)
abstract class ApiIntegrationTestBase {
    companion object {
        const val BOOTSTRAP_KEY = "tk_test_bootstrap_key"

        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        @JvmStatic
        val minio: MinIOContainer =
            MinIOContainer("minio/minio:latest")
                .withUserName("testinbox")
                .withPassword("testinbox123")
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("testinbox.storage.endpoint") { minio.s3URL }
            registry.add("testinbox.storage.access-key") { "testinbox" }
            registry.add("testinbox.storage.secret-key") { "testinbox123" }
            registry.add("testinbox.mail-domain") { "testinbox.local" }
            registry.add("testinbox.sweep-interval") { "1s" }
            registry.add("testinbox.expiry-grace") { "1s" }
        }
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var provisioning: ProvisioningRepository

    @Autowired lateinit var messages: MessageRepository

    val rest: RestTemplate =
        RestTemplate().apply {
            errorHandler =
                object : DefaultResponseErrorHandler() {
                    override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
                }
        }

    fun url(path: String) = "http://localhost:$port$path"

    fun headers(key: String? = adminKey): HttpHeaders =
        HttpHeaders().apply {
            key?.let { setBearerAuth(it) }
            set("Content-Type", "application/json")
        }

    fun post(
        path: String,
        body: String,
        key: String? = adminKey,
    ): ResponseEntity<String> = rest.exchange(url(path), HttpMethod.POST, HttpEntity(body, headers(key)), String::class.java)

    fun get(
        path: String,
        key: String? = adminKey,
    ): ResponseEntity<String> = rest.exchange(url(path), HttpMethod.GET, HttpEntity(null, headers(key)), String::class.java)

    fun delete(
        path: String,
        key: String? = adminKey,
    ): ResponseEntity<String> = rest.exchange(url(path), HttpMethod.DELETE, HttpEntity(null, headers(key)), String::class.java)

    /**
     * The default credential every suite authenticates with — a **managed**
     * key (ADR-032), not the bootstrap one. Two reasons, and both matter:
     * the suites then exercise the credential path a deployment actually uses,
     * and the bootstrap key stops authenticating the moment a managed
     * administrator exists (ADR-032 §8), so a shared bootstrap credential
     * would break every suite as soon as one of them minted a key.
     */
    lateinit var adminKey: String

    /** A second tenant plus a read-only key, for isolation/scope tests. */
    lateinit var otherWorkspaceKey: String
    lateinit var readOnlyKey: String
    open val bootstrapWorkspaceId: WorkspaceId =
        WorkspaceId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    /**
     * Mints a real `ti_k1_` credential and stores only its verifier, exactly
     * as `CreateApiKey` does. Fixtures that stored an arbitrary string would
     * be testing a code path no client can reach.
     */
    fun mintKey(
        workspaceId: WorkspaceId,
        projectId: ProjectId,
        scopes: Set<ApiScope>,
        name: String? = null,
        expiresAt: Instant? = null,
    ): String {
        val credential = ApiKeyFormat.generate()
        provisioning.ensureApiKey(
            ApiKey(
                id = ApiKeyId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = projectId,
                keyHash = Sha256.hex(credential.secret),
                scopes = scopes,
                createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
                revokedAt = null,
                kind = ApiKeyKind.MANAGED,
                publicId = credential.publicId,
                name = name,
                expiresAt = expiresAt,
            ),
        )
        return credential.render()
    }

    /**
     * Open so a suite about the bootstrap credential itself can decline it:
     * this provisions a managed administrator, which is precisely the act that
     * closes the ADR-032 §8 window.
     */
    @BeforeAll
    open fun provisionFixtures() {
        val bootstrapProject = ProjectId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val otherWorkspace = WorkspaceId(UUID.randomUUID())
        val otherProject = ProjectId(UUID.randomUUID())
        provisioning.ensureWorkspace(Workspace(otherWorkspace, "other", now))
        provisioning.ensureProject(Project(otherProject, otherWorkspace, "other", now))
        otherWorkspaceKey =
            mintKey(otherWorkspace, otherProject, setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ))
        readOnlyKey = mintKey(bootstrapWorkspaceId, bootstrapProject, setOf(ApiScope.MESSAGES_READ))
        adminKey = mintKey(bootstrapWorkspaceId, bootstrapProject, ApiScope.entries.toSet(), name = "suite-admin")
    }

    /**
     * A fresh workspace with its own API key. Any suite asserting on a
     * per-workspace limit must own its workspace: quota usage is derived from
     * real rows, so a workspace shared with other suites carries their inboxes
     * and their spent tokens.
     */
    fun provisionIsolatedWorkspace(label: String): IsolatedTenant {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val workspaceId = WorkspaceId(UUID.randomUUID())
        val projectId = ProjectId(UUID.randomUUID())
        provisioning.ensureWorkspace(Workspace(workspaceId, label, now))
        provisioning.ensureProject(Project(projectId, workspaceId, label, now))
        val plaintextKey =
            mintKey(workspaceId, projectId, setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ), name = label)
        return IsolatedTenant(workspaceId, projectId, plaintextKey)
    }

    /** A second API key inside an existing workspace, for rotation/sharing tests. */
    fun provisionAdditionalKey(tenant: IsolatedTenant): String =
        mintKey(
            tenant.workspaceId,
            tenant.projectId,
            setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ),
            name = "extra",
        )

    data class IsolatedTenant(
        val workspaceId: WorkspaceId,
        val projectId: ProjectId,
        val apiKey: String,
    )

    fun appendVisibleMessage(
        inboxId: InboxId,
        address: String,
        subject: String = "Verify your email",
        parseStatus: ParseStatus = ParseStatus.OK,
        receivedAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
        workspaceId: WorkspaceId = bootstrapWorkspaceId,
    ): Message {
        val message =
            Message(
                id = MessageId(UUID.randomUUID()),
                workspaceId = workspaceId,
                inboxId = inboxId,
                receivedAt = receivedAt,
                provider = "local-smtp",
                providerMessageId = null,
                envelopeFrom = "no-reply@example.com",
                envelopeTo = address,
                rawObjectKey = "$workspaceId/$inboxId/${UUID.randomUUID()}/raw.eml",
                rawSizeBytes = 5,
                contentFingerprint = UUID.randomUUID().toString(),
                possibleDuplicateOfMessageId = null,
                parseStatus = parseStatus,
                parseError = if (parseStatus == ParseStatus.FAILED) "broken" else null,
                parsed =
                    if (parseStatus == ParseStatus.OK) {
                        ParsedContent(
                            fromAddress = "no-reply@example.com",
                            fromHeader = "SUT <no-reply@example.com>",
                            toHeader = address,
                            subject = subject,
                            textBody = "hello body",
                            htmlBody = "<p>hello</p>",
                            headers = emptyList(),
                            links = emptyList(),
                        )
                    } else {
                        null
                    },
                attachments = emptyList(),
            )
        messages.appendVisible(message)
        return message
    }
}
