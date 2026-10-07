package email.testinbox.api.storage

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.storage.CopyPlan
import email.testinbox.application.storage.GuardedIngestReport
import email.testinbox.application.storage.GuardedStorage
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageNode
import email.testinbox.application.storage.StorageNodeLifecycle
import email.testinbox.application.storage.WriteSlots
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.application.usecase.StorageAdmissionCandidate
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.persistence.JdbcStorageAdmission
import email.testinbox.persistence.JdbcStorageAmbiguity
import email.testinbox.persistence.JdbcStorageReservations
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/**
 * TEST-ONLY production of real ADR-035 storage refusals (TI-STORAGE-004 §38).
 *
 * This is the actual guarded ingest protocol (`GuardedStorage`: slot, T1,
 * the §6a refusal upsert and its `pg_notify`) assembled by hand around the
 * API test database and bucket, with an enforcement mode that REFUSES —
 * `TENANT_LIMITS`, or `ALL` for the global ceiling — and a policy sized so
 * that one copy exceeds the wanted ceiling. The deployed wiring constructs
 * `StorageEnforcement.OFF` as a literal; no deployable configuration selects
 * this. The SMTP listener itself lives in the ingestion deployable and is
 * proven there (`SmtpAntiOracleTest`); here the API module exercises
 * everything downstream of recipient resolution, which is where the refusal
 * is decided, persisted and notified.
 */
@Component
class GuardedRefusals(
    private val jdbc: JdbcClient,
    private val transactionManager: PlatformTransactionManager,
    private val reservations: JdbcStorageReservations,
    private val ambiguity: JdbcStorageAmbiguity,
    private val blobs: BlobStore,
    private val inspection: StorageInspection,
    private val transactions: TransactionRunner,
) {
    private val template = TransactionTemplate(transactionManager)

    /** One node generation for the fixture's lifetime, registered once, not one abandoned generation per refusal. */
    private val node = StorageNode("api-test-refusals", UUID.randomUUID())
    private val lifecycle by lazy { StorageNodeLifecycle(ambiguity, node).also { it.start() } }

    /**
     * Runs one single-recipient event against [inboxId] so that the narrowest
     * ENFORCED ceiling reached is [reason]. Returns the protocol's own report,
     * which names the refusal, so a caller can assert the refusal really
     * happened rather than trusting the setup.
     */
    fun refuse(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
        reason: StorageRefusalReason,
    ): GuardedIngestReport {
        val (policy, enforcement, bytes) =
            when (reason) {
                // Workspace 1000, share 0.05 → inbox limit 50; a 60-byte copy exceeds the inbox first.
                StorageRefusalReason.INBOX_LIMIT -> {
                    Triple(StorageCapacityPolicy(1_000, InboxShare.of("0.05"), 1L shl 40, 0), StorageEnforcement.TENANT_LIMITS, 60L)
                }

                // Workspace 100 (= inbox limit) with 50 bytes already accounted: a 60-byte copy fits the inbox, not the workspace.
                StorageRefusalReason.WORKSPACE_LIMIT -> {
                    jdbc
                        .sql(
                            """
                            INSERT INTO workspace_storage_account (workspace_id, base_bytes) VALUES (:w, 50)
                            ON CONFLICT (workspace_id) DO UPDATE SET base_bytes = greatest(workspace_storage_account.base_bytes, 50)
                            """.trimIndent(),
                        ).param("w", workspaceId.value)
                        .update()
                    Triple(StorageCapacityPolicy(100, InboxShare.of("1"), 1L shl 40, 0), StorageEnforcement.TENANT_LIMITS, 60L)
                }

                // Generous tenant ceilings, a 100-byte admission cap: only the global scope refuses a 150-byte copy.
                StorageRefusalReason.SERVICE_CAPACITY -> {
                    Triple(StorageCapacityPolicy(1L shl 30, InboxShare.of("1"), 100, 0), StorageEnforcement.ALL, 150L)
                }
            }
        lifecycle
        val guarded =
            GuardedStorage(
                admission = StorageAdmission(JdbcStorageAdmission(jdbc, template), policy, enforcement),
                reservations = reservations,
                ambiguity = ambiguity,
                latch = ambiguity,
                blobs = blobs,
                inspection = inspection,
                slots = WriteSlots(16, 4, Duration.ofSeconds(2)) { ambiguity.unresolvedFor(node.nodeId) },
                breaker = StorageBreaker(),
                node = node,
                transactions = transactions,
                clock = reservations,
            )
        val messageId = MessageId(UUID.randomUUID())
        val rawKey = ObjectKeys.raw(workspaceId, inboxId, messageId)
        val copy =
            CopyPlan(
                StorageAdmissionCandidate(messageId, workspaceId, inboxId, listOf(rawKey)),
                listOf(rawKey to ByteArray(bytes.toInt())),
            )
        val report =
            guarded.ingest(bytes, listOf(copy)) {
                error("a refused copy is never persisted")
            }
        check(report.refused[inboxId] == reason) { "expected a $reason refusal, got ${report.refused}" }
        return report
    }
}
