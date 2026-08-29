package email.testinbox.api.config

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.ApiKeyRepository
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.ExactAddressReservations
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.MessageNotifier
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.query.InboxQueries
import email.testinbox.application.query.MessageQueries
import email.testinbox.application.usecase.AuthenticateApiKey
import email.testinbox.application.usecase.CreateInbox
import email.testinbox.application.usecase.DeleteInbox
import email.testinbox.application.usecase.ExpireInboxes
import email.testinbox.application.usecase.OrphanBlobSweep
import email.testinbox.application.usecase.WaitForMessage
import email.testinbox.notification.PgListenNotifier
import email.testinbox.notification.PgListenNotifierConfig
import email.testinbox.storage.S3BlobStore
import email.testinbox.storage.S3BlobStoreConfig
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ApiWiring {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun testInboxConfig(properties: TestInboxProperties): TestInboxConfig = properties.toConfig()

    @Bean(destroyMethod = "close")
    fun blobStore(properties: TestInboxProperties): BlobStore =
        S3BlobStore(
            S3BlobStoreConfig(
                endpoint = properties.storage.endpoint,
                region = properties.storage.region,
                accessKey = properties.storage.accessKey,
                secretKey = properties.storage.secretKey,
                bucket = properties.storage.bucket,
            ),
        )

    @Bean(initMethod = "start", destroyMethod = "close")
    fun messageNotifier(dataSourceProperties: DataSourceProperties): PgListenNotifier =
        PgListenNotifier(
            PgListenNotifierConfig(
                jdbcUrl = dataSourceProperties.determineUrl(),
                username = dataSourceProperties.determineUsername().orEmpty(),
                password = dataSourceProperties.determinePassword().orEmpty(),
            ),
        )

    @Bean
    fun createInbox(
        inboxes: InboxRepository,
        reservations: ExactAddressReservations,
        tx: TransactionRunner,
        clock: Clock,
        config: TestInboxConfig,
    ): CreateInbox = CreateInbox(inboxes, reservations, tx, clock, config)

    @Bean
    fun deleteInbox(
        inboxes: InboxRepository,
        reservations: ExactAddressReservations,
        tx: TransactionRunner,
        clock: Clock,
        config: TestInboxConfig,
    ): DeleteInbox = DeleteInbox(inboxes, reservations, tx, clock, config)

    @Bean
    fun waitForMessage(
        inboxes: InboxRepository,
        messages: MessageRepository,
        notifier: MessageNotifier,
        clock: Clock,
        config: TestInboxConfig,
    ): WaitForMessage = WaitForMessage(inboxes, messages, notifier, clock, config)

    @Bean
    fun expireInboxes(
        inboxes: InboxRepository,
        reservations: ExactAddressReservations,
        blobs: BlobStore,
        tx: TransactionRunner,
        clock: Clock,
        config: TestInboxConfig,
    ): ExpireInboxes = ExpireInboxes(inboxes, reservations, blobs, tx, clock, config)

    @Bean
    fun orphanBlobSweep(
        blobs: BlobStore,
        messages: MessageRepository,
        clock: Clock,
        properties: TestInboxProperties,
    ): OrphanBlobSweep = OrphanBlobSweep(blobs, messages, clock, properties.orphanMinAge)

    @Bean
    fun authenticateApiKey(apiKeys: ApiKeyRepository): AuthenticateApiKey = AuthenticateApiKey(apiKeys)

    @Bean
    fun inboxQueries(inboxes: InboxRepository): InboxQueries = InboxQueries(inboxes)

    @Bean
    fun messageQueries(
        messages: MessageRepository,
        blobs: BlobStore,
    ): MessageQueries = MessageQueries(messages, blobs)
}
