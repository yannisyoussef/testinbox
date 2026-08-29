package email.testinbox.api.ops

import email.testinbox.api.config.TestInboxProperties
import email.testinbox.application.Sha256
import email.testinbox.application.port.MessageNotifier
import email.testinbox.application.port.ProvisioningRepository
import email.testinbox.application.usecase.ExpireInboxes
import email.testinbox.application.usecase.OrphanBlobSweep
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiScope
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/** Bounded lifecycle sweep scheduler (ADR-009). */
@Component
class SweepScheduler(
    private val expireInboxes: ExpireInboxes,
    private val orphanBlobSweep: OrphanBlobSweep,
) {
    @Scheduled(fixedDelayString = "\${testinbox.sweep-interval:5s}")
    fun lifecycleSweep() {
        runCatching { expireInboxes.sweep() }
            .onFailure { log.warn("lifecycle sweep failed", it) }
    }

    @Scheduled(
        fixedDelayString = "\${testinbox.orphan-sweep-interval:30m}",
        initialDelayString = "\${testinbox.orphan-sweep-interval:30m}",
    )
    fun orphanSweep() {
        runCatching { orphanBlobSweep.sweep() }
            .onFailure { log.warn("orphan blob sweep failed", it) }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SweepScheduler::class.java)
    }
}

/** ADR-020: LISTEN connection health participates in node readiness. */
@Component("waitNotifier")
class NotifierHealthIndicator(
    private val notifier: MessageNotifier,
) : HealthIndicator {
    override fun health(): Health {
        val health = notifier.health()
        val builder = if (health.listening) Health.up() else Health.down()
        return builder
            .withDetail("listening", health.listening)
            .withDetail("epoch", health.epoch)
            .withDetail("reconnects", health.reconnectCount)
            .build()
    }
}

/**
 * Local/dev fixture provisioning: creates a workspace/project and an API
 * key from configuration. Only the SHA-256 hash of the configured key is
 * stored; the plaintext value is never persisted or logged (ADR-010).
 */
@Component
class BootstrapFixture(
    private val provisioning: ProvisioningRepository,
    private val properties: TestInboxProperties,
    private val clock: Clock,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val plaintext = properties.bootstrap.apiKey?.takeIf { it.isNotBlank() } ?: return
        val now = clock.instant()
        val workspaceId = WorkspaceId(properties.bootstrap.workspaceId)
        val projectId = ProjectId(properties.bootstrap.projectId)
        provisioning.ensureWorkspace(Workspace(workspaceId, "bootstrap", now))
        provisioning.ensureProject(Project(projectId, workspaceId, "bootstrap", now))
        provisioning.ensureApiKey(
            ApiKey(
                id = ApiKeyId(UUID.nameUUIDFromBytes(Sha256.hex(plaintext).toByteArray())),
                workspaceId = workspaceId,
                projectId = projectId,
                keyHash = Sha256.hex(plaintext),
                scopes = setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ),
                createdAt = now,
                revokedAt = null,
            ),
        )
        log.info("bootstrap fixture provisioned (workspace={})", workspaceId)
    }

    private companion object {
        val log = LoggerFactory.getLogger(BootstrapFixture::class.java)
    }
}
