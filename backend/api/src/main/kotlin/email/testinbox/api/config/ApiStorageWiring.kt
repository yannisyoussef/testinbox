package email.testinbox.api.config

import email.testinbox.api.ops.ApiStorageNodeRuntime
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.ReleaseStaleReservations
import email.testinbox.application.storage.RowFreeDebt
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.storage.StorageNode
import email.testinbox.application.storage.StorageNodeLifecycle
import email.testinbox.application.storage.VerifyAmbiguousUploads
import email.testinbox.application.storage.activation.ActivationGuard
import email.testinbox.application.storage.activation.ActivationWatch
import email.testinbox.application.usecase.OrphanBlobSweep
import email.testinbox.persistence.JdbcActivationInventory
import email.testinbox.persistence.JdbcRowFreeDebtStore
import email.testinbox.persistence.JdbcStorageAmbiguity
import email.testinbox.persistence.JdbcStorageNodeClaims
import email.testinbox.persistence.JdbcStorageReservations
import email.testinbox.storage.QualificationRecords
import email.testinbox.storage.S3BlobStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * The API's side of the ADR-035 guarded ingest protocol (TI-STORAGE-003):
 * reservation cleanup, ambiguity verification and the orphan sweep. The API
 * runs them; the ingestion gateway writes.
 */
@Configuration
class ApiStorageWiring(
    private val properties: TestInboxProperties,
    private val clock: Clock,
) {
    /** The witness probe goes through rule (P) (TI-STORAGE-006E PR D). */
    @Bean
    fun storageInspection(
        blobs: BlobStore,
        rowFreeDebt: RowFreeDebt,
    ): StorageInspection = rowFreeDebt.guard((blobs as S3BlobStore).inspection())

    /** Rule (P) for every row-free deletion: the sweep, the verifier, the probes (contract §2.1). */
    @Bean
    fun rowFreeDebt(
        jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
        declarations: StorageDeclarations,
    ): RowFreeDebt =
        email.testinbox.application.storage.FootprintWiring.rowFreeDebt(
            JdbcRowFreeDebtStore(jdbc, TransactionTemplate(transactionManager)),
            declarations,
        )

    /**
     * ADR-035 §18: what this deployment declares, with the qualification
     * records shipped in this artifact. `DeploymentSafetyCheck` has already
     * refused a non-OFF value that is incomplete or unqualified by the time
     * this bean exists.
     */
    @Bean
    fun storageDeclarations(): StorageDeclarations = properties.storageDeclarations(QualificationRecords.load())

    /**
     * ADR-035 §14 Phase 4: every node re-runs the allowlist and inventory
     * checks on each cleanup pass. The API refuses no mail itself, so its
     * guard only feeds the metric and the log; the gateway's guard fails closed.
     */
    @Bean
    fun activationWatch(
        jdbc: JdbcClient,
        declarations: StorageDeclarations,
        storageMetrics: StorageProtocolMetrics,
    ): ActivationWatch =
        ActivationWatch(
            JdbcActivationInventory(jdbc),
            properties.storage.activation.toExpectedNodes(),
            declarations.enforcement,
            // The API admits no mail, so its guard is read by nothing; the gateway's
            // guard is the one GuardedStorage consults. Metrics and logs are the API's output.
            ActivationGuard(),
            storageMetrics,
        )

    @Bean
    fun storageReservations(
        jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
    ): JdbcStorageReservations = JdbcStorageReservations(jdbc, TransactionTemplate(transactionManager))

    @Bean
    fun storageAmbiguity(
        jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
    ): JdbcStorageAmbiguity = JdbcStorageAmbiguity(jdbc, TransactionTemplate(transactionManager))

    @Bean
    fun releaseStaleReservations(
        reservations: JdbcStorageReservations,
        ambiguity: JdbcStorageAmbiguity,
        inspection: StorageInspection,
        metrics: StorageProtocolMetrics,
    ): ReleaseStaleReservations =
        ReleaseStaleReservations(reservations, ambiguity, ambiguity, inspection, reservations, properties.storage.nodeId, metrics)

    @Bean
    fun verifyAmbiguousUploads(
        ambiguity: JdbcStorageAmbiguity,
        reservations: JdbcStorageReservations,
        inspection: StorageInspection,
        metrics: StorageProtocolMetrics,
        rowFreeDebt: RowFreeDebt,
    ): VerifyAmbiguousUploads = VerifyAmbiguousUploads(ambiguity, ambiguity, reservations, inspection, metrics, rowFree = rowFreeDebt)

    /** ADR-035 §14 (a): the API is a protocol participant and appears in the positive node inventory (TI-STORAGE-006 §18). */
    @Bean
    fun apiStorageNodeRuntime(
        ambiguity: JdbcStorageAmbiguity,
        dataSource: DataSource,
    ): ApiStorageNodeRuntime =
        ApiStorageNodeRuntime(
            StorageNodeLifecycle(ambiguity, StorageNode(properties.storage.nodeId, UUID.randomUUID())),
            JdbcStorageNodeClaims(dataSource),
        )

    @Bean
    fun orphanBlobSweep(
        blobs: BlobStore,
        reservations: JdbcStorageReservations,
        ambiguity: JdbcStorageAmbiguity,
        inspection: StorageInspection,
        metrics: StorageProtocolMetrics,
        rowFreeDebt: RowFreeDebt,
    ): OrphanBlobSweep =
        OrphanBlobSweep(blobs, reservations, ambiguity, ambiguity, inspection, clock, properties.orphanMinAge, metrics, rowFreeDebt)
}
