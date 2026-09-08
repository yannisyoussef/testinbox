package email.testinbox.api.ops

import email.testinbox.api.config.TestInboxProperties
import email.testinbox.application.Sha256
import email.testinbox.application.port.IdempotencyRecords
import email.testinbox.application.port.MessageNotifier
import email.testinbox.application.port.ProvisioningRepository
import email.testinbox.application.port.WaitSlots
import email.testinbox.application.usecase.ExpireInboxes
import email.testinbox.application.usecase.OrphanBlobSweep
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiKeyKind
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
    private val waitSlots: WaitSlots,
    private val idempotencyRecords: IdempotencyRecords,
    private val clock: Clock,
) {
    /**
     * Retention sweep for idempotency records (ADR-033 §9).
     *
     * On a slow cadence and in bounded batches, unlike the lifecycle sweep: an
     * unbounded delete of every expired row on a five-second tick is its own
     * write-ahead-log problem. It can never remove an in-flight operation,
     * because an uncommitted claim is invisible to this statement's snapshot.
     *
     * **Deliberately not `@Transactional`, and it must stay that way.** Each
     * `deleteExpired` is its own autocommit statement, so its row locks are
     * released at the end of every pass. Wrapping this loop in one transaction
     * would hold locks on up to `BATCH × MAX_PASSES` rows until the last pass
     * finished, and a claim landing on any of them would block there and be
     * reported to the client as `idempotency-request-in-progress` when nothing
     * is in progress — the ADR-033 §9 contention the `SKIP LOCKED` in
     * `deleteExpired` exists to avoid, reintroduced from the other side.
     */
    @Scheduled(fixedDelayString = "\${testinbox.idempotency.sweep-interval:5m}")
    fun idempotencySweep() {
        runCatching {
            var removed: Int
            var passes = 0
            do {
                removed = idempotencyRecords.deleteExpired(clock.instant(), IDEMPOTENCY_SWEEP_BATCH)
                passes++
            } while (removed == IDEMPOTENCY_SWEEP_BATCH && passes < IDEMPOTENCY_SWEEP_MAX_PASSES)
        }.onFailure { log.warn("idempotency sweep failed", it) }
    }

    @Scheduled(fixedDelayString = "\${testinbox.sweep-interval:5s}")
    fun lifecycleSweep() {
        runCatching { expireInboxes.sweep() }
            .onFailure { log.warn("lifecycle sweep failed", it) }
    }

    /**
     * Reclaims wait slots whose holder died. Acquisition already clears a
     * workspace's own stale rows, so this exists for the workspace that stops
     * waiting entirely — and so a rising count is a visible crash-leak signal.
     */
    @Scheduled(fixedDelayString = "\${testinbox.sweep-interval:5s}")
    fun waitLeaseSweep() {
        runCatching { waitSlots.reapExpired() }
            .onFailure { log.warn("wait lease reaper failed", it) }
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
        const val IDEMPOTENCY_SWEEP_BATCH = 500

        /** Bounds one tick's work; the next tick continues where this stopped. */
        const val IDEMPOTENCY_SWEEP_MAX_PASSES = 20

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
 * Provisions the workspace, project and **bootstrap credential** from
 * configuration. Only the SHA-256 hash of the configured token is stored; the
 * plaintext is never persisted or logged (ADR-010).
 *
 * The credential is marked `BOOTSTRAP` and carries `api-keys:manage` so it can
 * mint the first managed key — which is the act that closes its own window
 * (ADR-032 §8). It authenticates only while the workspace holds no usable
 * managed administrator, and that condition is re-evaluated on every request,
 * so provisioning it here does not re-enable it after a handover.
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
                // Random, NOT a digest of the secret. The previous
                // `nameUUIDFromBytes(sha256(plaintext))` made the row id an
                // unsalted, unstretched function of the credential — and this
                // increment publishes that id, as `createdByApiKeyId` on the
                // first managed key and as `actorApiKeyId` on every audit line.
                // Anyone holding one could grind candidate passphrases offline.
                // `ON CONFLICT (key_hash)` keeps the first id, so it is still
                // stable across restarts.
                id = ApiKeyId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = projectId,
                keyHash = Sha256.hex(plaintext),
                scopes = setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ, ApiScope.API_KEYS_MANAGE),
                createdAt = now,
                revokedAt = null,
                kind = ApiKeyKind.BOOTSTRAP,
            ),
        )
        log.info("bootstrap credential provisioned (workspace={})", workspaceId)
    }

    private companion object {
        val log = LoggerFactory.getLogger(BootstrapFixture::class.java)
    }
}
