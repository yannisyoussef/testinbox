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
            registry.add("testinbox.bootstrap.api-key") { BOOTSTRAP_KEY }
            registry.add("testinbox.mail-domain") { "testinbox.local" }
            registry.add("testinbox.wait-window-cap") { "5s" }
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

    fun headers(key: String? = BOOTSTRAP_KEY): HttpHeaders =
        HttpHeaders().apply {
            key?.let { setBearerAuth(it) }
            set("Content-Type", "application/json")
        }

    fun post(
        path: String,
        body: String,
        key: String? = BOOTSTRAP_KEY,
    ): ResponseEntity<String> = rest.exchange(url(path), HttpMethod.POST, HttpEntity(body, headers(key)), String::class.java)

    fun get(
        path: String,
        key: String? = BOOTSTRAP_KEY,
    ): ResponseEntity<String> = rest.exchange(url(path), HttpMethod.GET, HttpEntity(null, headers(key)), String::class.java)

    fun delete(
        path: String,
        key: String? = BOOTSTRAP_KEY,
    ): ResponseEntity<String> = rest.exchange(url(path), HttpMethod.DELETE, HttpEntity(null, headers(key)), String::class.java)

    /** A second tenant plus a read-only key, for isolation/scope tests. */
    val otherWorkspaceKey = "tk_other_workspace"
    val readOnlyKey = "tk_read_only"
    val bootstrapWorkspaceId: WorkspaceId =
        WorkspaceId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    @BeforeAll
    fun provisionFixtures() {
        val bootstrapProject = ProjectId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val otherWorkspace = WorkspaceId(UUID.randomUUID())
        val otherProject = ProjectId(UUID.randomUUID())
        provisioning.ensureWorkspace(Workspace(otherWorkspace, "other", now))
        provisioning.ensureProject(Project(otherProject, otherWorkspace, "other", now))
        provisioning.ensureApiKey(
            ApiKey(
                ApiKeyId(UUID.randomUUID()),
                otherWorkspace,
                otherProject,
                Sha256.hex(otherWorkspaceKey),
                setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ),
                now,
                null,
            ),
        )
        provisioning.ensureApiKey(
            ApiKey(
                ApiKeyId(UUID.randomUUID()),
                bootstrapWorkspaceId,
                bootstrapProject,
                Sha256.hex(readOnlyKey),
                setOf(ApiScope.MESSAGES_READ),
                now,
                null,
            ),
        )
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
        val plaintextKey = "tk_${label}_${UUID.randomUUID()}"
        provisioning.ensureWorkspace(Workspace(workspaceId, label, now))
        provisioning.ensureProject(Project(projectId, workspaceId, label, now))
        provisioning.ensureApiKey(
            ApiKey(
                ApiKeyId(UUID.randomUUID()),
                workspaceId,
                projectId,
                Sha256.hex(plaintextKey),
                setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ),
                now,
                null,
            ),
        )
        return IsolatedTenant(workspaceId, projectId, plaintextKey)
    }

    /** A second API key inside an existing workspace, for rotation/sharing tests. */
    fun provisionAdditionalKey(tenant: IsolatedTenant): String {
        val plaintextKey = "tk_extra_${UUID.randomUUID()}"
        provisioning.ensureApiKey(
            ApiKey(
                ApiKeyId(UUID.randomUUID()),
                tenant.workspaceId,
                tenant.projectId,
                Sha256.hex(plaintextKey),
                setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                null,
            ),
        )
        return plaintextKey
    }

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
