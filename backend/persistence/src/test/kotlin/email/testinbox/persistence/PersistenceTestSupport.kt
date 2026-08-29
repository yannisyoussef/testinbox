package email.testinbox.persistence

import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import email.testinbox.domain.message.ParsedContent
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.ComponentScan
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan("email.testinbox.persistence")
class PersistenceTestApp

@SpringBootTest(classes = [PersistenceTestApp::class])
abstract class PersistenceIntegrationTest {
    companion object {
        // Shared singleton container across all persistence test classes.
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.enabled") { "true" }
        }
    }
}

object Fixtures {
    fun provisionTenant(jdbc: JdbcClient): Pair<WorkspaceId, ProjectId> {
        val workspaceId = WorkspaceId(UUID.randomUUID())
        val projectId = ProjectId(UUID.randomUUID())
        jdbc
            .sql("INSERT INTO workspace (id, name, created_at) VALUES (:id, 'w', now())")
            .param("id", workspaceId.value)
            .update()
        jdbc
            .sql("INSERT INTO project (id, workspace_id, name, created_at) VALUES (:id, :ws, 'p', now())")
            .param("id", projectId.value)
            .param("ws", workspaceId.value)
            .update()
        return workspaceId to projectId
    }

    fun inbox(
        workspaceId: WorkspaceId,
        projectId: ProjectId,
        address: String = "${UUID.randomUUID()}@testinbox.local",
        mode: AddressMode = AddressMode.GENERATED,
        state: InboxState = InboxState.ACTIVE,
        now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
        ttlSeconds: Long = 600,
    ): Inbox =
        Inbox(
            id = InboxId(UUID.randomUUID()),
            workspaceId = workspaceId,
            projectId = projectId,
            address = address,
            addressMode = mode,
            state = state,
            createdAt = now,
            expiresAt = now.plusSeconds(ttlSeconds),
        )

    fun message(
        inbox: Inbox,
        receivedAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
        subject: String? = "hello",
        parseStatus: ParseStatus = ParseStatus.OK,
        fingerprint: String = UUID.randomUUID().toString(),
        providerMessageId: String? = null,
        possibleDuplicateOf: MessageId? = null,
    ): Message =
        Message(
            id = MessageId(UUID.randomUUID()),
            workspaceId = inbox.workspaceId,
            inboxId = inbox.id,
            receivedAt = receivedAt,
            provider = "local-smtp",
            providerMessageId = providerMessageId,
            envelopeFrom = "sut@example.com",
            envelopeTo = inbox.address,
            rawObjectKey = "${inbox.workspaceId}/${inbox.id}/raw.eml",
            rawSizeBytes = 42,
            contentFingerprint = fingerprint,
            possibleDuplicateOfMessageId = possibleDuplicateOf,
            parseStatus = parseStatus,
            parseError = if (parseStatus == ParseStatus.FAILED) "broken" else null,
            parsed =
                if (parseStatus == ParseStatus.OK) {
                    ParsedContent(
                        fromAddress = "sut@example.com",
                        fromHeader = "SUT <sut@example.com>",
                        toHeader = inbox.address,
                        subject = subject,
                        textBody = "text",
                        htmlBody = "<p>html</p>",
                        headers = emptyList(),
                        links = emptyList(),
                    )
                } else {
                    null
                },
            attachments = emptyList(),
        )
}
