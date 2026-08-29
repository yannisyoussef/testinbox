package email.testinbox.ingestion.config

import email.testinbox.application.TestInboxConfig
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("testinbox")
data class IngestionProperties(
    val mailDomain: String = "testinbox.local",
    val defaultTtl: Duration = Duration.ofMinutes(15),
    val maxTtl: Duration = Duration.ofHours(24),
    val expiryGrace: Duration = Duration.ofSeconds(30),
    val exactCooldown: Duration = Duration.ofHours(24),
    val waitWindowCap: Duration = Duration.ofSeconds(60),
    val maxRawSizeBytes: Long = 15L * 1024 * 1024,
    val smtp: Smtp = Smtp(),
    val storage: Storage = Storage(),
) {
    data class Smtp(val port: Int = 2525)

    data class Storage(
        val endpoint: String = "http://localhost:9000",
        val region: String = "us-east-1",
        val accessKey: String = "testinbox",
        val secretKey: String = "testinbox123",
        val bucket: String = "testinbox-mime",
    )

    fun toConfig(): TestInboxConfig =
        TestInboxConfig(
            mailDomain = mailDomain,
            defaultTtl = defaultTtl,
            maxTtl = maxTtl,
            expiryGrace = expiryGrace,
            exactCooldown = exactCooldown,
            waitWindowCap = waitWindowCap,
            maxRawSizeBytes = maxRawSizeBytes,
        )
}
