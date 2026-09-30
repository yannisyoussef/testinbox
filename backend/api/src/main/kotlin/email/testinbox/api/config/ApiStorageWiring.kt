package email.testinbox.api.config

import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.storage.ReleaseStaleReservations
import email.testinbox.application.storage.VerifyAmbiguousUploads
import email.testinbox.application.usecase.OrphanBlobSweep
import email.testinbox.persistence.JdbcStorageAmbiguity
import email.testinbox.persistence.JdbcStorageReservations
import email.testinbox.storage.S3BlobStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

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
    @Bean
    fun storageInspection(blobs: BlobStore): StorageInspection = (blobs as S3BlobStore).inspection()

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
    ): VerifyAmbiguousUploads = VerifyAmbiguousUploads(ambiguity, ambiguity, reservations, inspection, metrics)

    @Bean
    fun orphanBlobSweep(
        blobs: BlobStore,
        reservations: JdbcStorageReservations,
        inspection: StorageInspection,
        metrics: StorageProtocolMetrics,
    ): OrphanBlobSweep = OrphanBlobSweep(blobs, reservations, inspection, clock, properties.orphanMinAge, metrics)
}
