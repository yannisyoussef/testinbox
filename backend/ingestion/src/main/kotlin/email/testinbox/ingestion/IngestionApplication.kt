package email.testinbox.ingestion

import email.testinbox.ingestion.config.IngestionProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * The independently deployable inbound mail gateway (ADR-001). It embeds
 * application/domain and the infrastructure adapters as libraries and
 * invokes the shared ReceiveInboundMessage use case — never writing to
 * Postgres/object storage directly (ADR-024).
 */
@SpringBootApplication(scanBasePackages = ["email.testinbox"])
@EnableConfigurationProperties(IngestionProperties::class)
class IngestionApplication

fun main(args: Array<String>) {
    runApplication<IngestionApplication>(*args)
}
