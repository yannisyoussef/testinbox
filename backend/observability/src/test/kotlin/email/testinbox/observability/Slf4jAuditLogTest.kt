package email.testinbox.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import email.testinbox.application.port.AuditEvent
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.tenant.ApiScope
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant
import java.util.UUID

class Slf4jAuditLogTest {
    private val at = Instant.parse("2026-09-08T12:00:00Z")
    private val workspaceId = WorkspaceId(UUID.randomUUID())
    private val actorId = ApiKeyId(UUID.randomUUID())
    private val keyId = ApiKeyId(UUID.randomUUID())

    private lateinit var logger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun attach() {
        logger = LoggerFactory.getLogger("testinbox.audit") as Logger
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        logger.level = Level.DEBUG
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        MDC.clear()
    }

    private fun messages(): List<String> = appender.list.map { it.formattedMessage }

    @Test
    fun `creation is recorded with the credential's public handle and its grant`() {
        MDC.put("correlationId", "corr-123")
        Slf4jAuditLog().record(
            AuditEvent.ApiKeyCreated(
                at = at,
                workspaceId = workspaceId,
                actorApiKeyId = actorId,
                apiKeyId = keyId,
                publicId = "abcdefghijklmnop",
                name = "github-ci",
                scopes = setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ),
                expiresAt = at.plusSeconds(3600),
            ),
        )
        val line = messages().single()
        line shouldContain "event=api_key.created"
        line shouldContain "publicId=abcdefghijklmnop"
        line shouldContain "actorApiKeyId=${actorId.value}"
        line shouldContain "workspaceId=${workspaceId.value}"
        // The correlation id is what joins this to the request logs, and it
        // comes from the MDC rather than from an HTTP notion in the port.
        line shouldContain "correlationId=corr-123"
        line shouldContain "scopes=inboxes:write,messages:read"
    }

    @Test
    fun `a revocation retry is distinguishable from a real revocation`() {
        val audit = Slf4jAuditLog()
        audit.record(AuditEvent.ApiKeyRevoked(at, workspaceId, actorId, keyId, alreadyRevoked = false))
        audit.record(AuditEvent.ApiKeyRevoked(at, workspaceId, actorId, keyId, alreadyRevoked = true))
        messages()[0] shouldContain "alreadyRevoked=false"
        messages()[1] shouldContain "alreadyRevoked=true"
    }

    @Test
    fun `the bootstrap transition is a warning, not an informational line`() {
        Slf4jAuditLog().record(AuditEvent.BootstrapSuperseded(at, workspaceId, actorId))
        // A run of these after a cutover means something in the estate is
        // still shipping the bootstrap secret, which is worth surfacing.
        appender.list.single().level shouldBe Level.WARN
        messages().single() shouldContain "event=bootstrap.superseded"
    }

    @Test
    fun `an absent correlation id and an absent name render as placeholders, not as null`() {
        Slf4jAuditLog().record(
            AuditEvent.ApiKeyCreated(at, workspaceId, null, keyId, "abcdefghijklmnop", null, emptySet(), null),
        )
        val line = messages().single()
        line shouldContain "correlationId=-"
        line shouldContain "actorApiKeyId=-"
        line shouldContain "name=-"
        line.contains("null") shouldBe false
    }

    @Test
    fun `no audit event has a field that could carry credential material`() {
        // The port's guarantee is structural: there is no field to put a
        // secret in. Asserted by reflection so a field added later fails here
        // rather than leaking into an operator's log aggregation.
        val eventTypes =
            listOf(
                AuditEvent.ApiKeyCreated::class,
                AuditEvent.ApiKeyRevoked::class,
                AuditEvent.BootstrapSuperseded::class,
            )
        val suspicious = listOf("secret", "hash", "verifier", "token", "credential", "password")
        eventTypes.forEach { type ->
            type.members.map { it.name.lowercase() }.forEach { name ->
                suspicious.none { name.endsWith(it) } shouldBe true
            }
        }
    }
}
