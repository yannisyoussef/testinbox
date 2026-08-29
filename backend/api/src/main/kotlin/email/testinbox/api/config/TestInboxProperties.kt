package email.testinbox.api.config

import email.testinbox.application.TestInboxConfig
import java.time.Duration
import java.util.UUID
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("testinbox")
data class TestInboxProperties(
    val mailDomain: String = "testinbox.local",
    val defaultTtl: Duration = Duration.ofMinutes(15),
    val maxTtl: Duration = Duration.ofHours(24),
    val expiryGrace: Duration = Duration.ofSeconds(30),
    val exactCooldown: Duration = Duration.ofHours(24),
    val waitWindowCap: Duration = Duration.ofSeconds(60),
    val maxRawSizeBytes: Long = 15L * 1024 * 1024,
    val sweepInterval: Duration = Duration.ofSeconds(5),
    val orphanSweepInterval: Duration = Duration.ofMinutes(30),
    val orphanMinAge: Duration = Duration.ofHours(1),
    val storage: Storage = Storage(),
    val bootstrap: Bootstrap = Bootstrap(),
) {
    data class Storage(
        val endpoint: String = "http://localhost:9000",
        val region: String = "us-east-1",
        val accessKey: String = "testinbox",
        val secretKey: String = "testinbox123",
        val bucket: String = "testinbox-mime",
    )

    data class Bootstrap(
        /** Local/dev fixture API key. Hashed before storage; the plaintext never persists (ADR-010). */
        val apiKey: String? = null,
        val workspaceId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        val projectId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001"),
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
