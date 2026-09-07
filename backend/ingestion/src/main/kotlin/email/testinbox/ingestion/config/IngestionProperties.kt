package email.testinbox.ingestion.config

import email.testinbox.application.LimitsProperties
import email.testinbox.application.TestInboxConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("testinbox")
data class IngestionProperties(
    val mailDomain: String = "testinbox.local",
    val defaultTtl: Duration = Duration.ofMinutes(15),
    val maxTtl: Duration = Duration.ofHours(24),
    val expiryGrace: Duration = Duration.ofSeconds(30),
    val exactCooldown: Duration = Duration.ofHours(24),
    val waitWindowCap: Duration = Duration.ofSeconds(60),
    val maxRawSizeBytes: Long = 15L * 1024 * 1024,
    val limits: LimitsProperties = LimitsProperties(),
    val smtp: Smtp = Smtp(),
    val storage: Storage = Storage(),
    val deployment: Deployment = Deployment(),
) {
    data class Smtp(
        val port: Int = 2525,
    )

    data class Storage(
        val endpoint: String = "http://localhost:9000",
        val region: String = "us-east-1",
        val accessKey: String = "testinbox",
        val secretKey: String = "testinbox123",
        val bucket: String = "testinbox-mime",
        /** See TestInboxProperties.Storage.createBucket. */
        val createBucket: Boolean = true,
    )

    /**
     * Deployment identity (ADR-028/029). `environment` being set is what marks
     * this process as deployed and turns on the startup safety check.
     *
     * The gateway has no public HTTP surface of its own — it terminates SMTP —
     * so `publicBaseUrl` stays null here and the proxy-timeout coupling is
     * still declared, because the two deployables share a wait-window cap and
     * a misconfiguration on either side is worth catching on either side.
     */
    data class Deployment(
        val environment: String? = null,
        val proxyReadTimeout: Duration? = null,
        val gitSha: String = "unknown",
        val imageDigest: String = "unknown",
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
