package email.testinbox.ingestion.config

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.MimeParser
import email.testinbox.application.usecase.ReceiveInboundMessage
import email.testinbox.ingestion.mime.JakartaMimeParser
import email.testinbox.storage.S3BlobStore
import email.testinbox.storage.S3BlobStoreConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class IngestionWiring {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun testInboxConfig(properties: IngestionProperties): TestInboxConfig = properties.toConfig()

    @Bean(destroyMethod = "close")
    fun blobStore(properties: IngestionProperties): BlobStore =
        S3BlobStore(
            S3BlobStoreConfig(
                endpoint = properties.storage.endpoint,
                region = properties.storage.region,
                accessKey = properties.storage.accessKey,
                secretKey = properties.storage.secretKey,
                bucket = properties.storage.bucket,
            ),
        )

    @Bean
    fun mimeParser(): MimeParser = JakartaMimeParser()

    @Bean
    fun receiveInboundMessage(
        inboxes: InboxRepository,
        messages: MessageRepository,
        blobs: BlobStore,
        parser: MimeParser,
        clock: Clock,
        config: TestInboxConfig,
    ): ReceiveInboundMessage = ReceiveInboundMessage(inboxes, messages, blobs, parser, clock, config)
}
