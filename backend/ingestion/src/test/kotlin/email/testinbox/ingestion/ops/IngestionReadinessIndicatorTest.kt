package email.testinbox.ingestion.ops

import email.testinbox.application.deployment.AppliedSchema
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.deployment.SchemaHistory
import email.testinbox.application.deployment.SchemaVersion
import email.testinbox.application.port.BlobStore
import email.testinbox.ingestion.config.IngestionProperties
import email.testinbox.ingestion.smtp.SmtpGateway
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.health.contributor.Status
import java.time.Instant

/**
 * The gateway's readiness mapping. `smtpListener` is the one that only exists
 * here, and it is the one that matters most: the listener is bound by a
 * `SmartLifecycle` rather than by the web server, so a context that came up
 * with a stopped listener would otherwise report a perfectly healthy actuator
 * on a process accepting no mail at all.
 */
class IngestionReadinessIndicatorTest {
    private fun schemaAt(
        applied: List<String>,
        bundled: String = "3",
    ) = IngestionSchemaHealthIndicator(
        SchemaCompatibility(SchemaHistory { AppliedSchema(applied, emptyList(), true) }, SchemaVersion(bundled)),
    )

    @Test
    fun `a schema behind this artifact takes the gateway out of rotation`() {
        schemaAt(listOf("1")).health().status shouldBe Status.OUT_OF_SERVICE
    }

    @Test
    fun `a schema at or ahead of this artifact is ready`() {
        schemaAt(listOf("1", "2", "3")).health().status shouldBe Status.UP
        schemaAt(listOf("1", "2", "3", "4")).health().status shouldBe Status.UP
    }

    @Test
    fun `object storage that cannot be reached is not ready`() {
        IngestionObjectStorageHealthIndicator(UnreachableBlobStore).health().status shouldBe Status.DOWN
    }

    @Test
    fun `a stopped SMTP listener is not ready, even though the process is perfectly alive`() {
        // The listener is bound by a SmartLifecycle, not by the web server, so
        // this is the difference between "the actuator answers" and "mail can
        // actually be received". SmtpIngestionIntegrationTest covers the real
        // bind; what is asserted here is the mapping either side of it.
        val properties = IngestionProperties(smtp = IngestionProperties.Smtp(port = 2525))
        val gateway = mock(SmtpGateway::class.java)

        `when`(gateway.isRunning).thenReturn(false)
        SmtpListenerHealthIndicator(gateway, properties).health().status shouldBe Status.DOWN

        `when`(gateway.isRunning).thenReturn(true)
        val healthy = SmtpListenerHealthIndicator(gateway, properties).health()
        healthy.status shouldBe Status.UP
        healthy.details["port"] shouldBe 2525
    }

    private object UnreachableBlobStore : BlobStore {
        override fun put(
            key: String,
            bytes: ByteArray,
            contentType: String,
        ) = Unit

        override fun get(key: String): ByteArray? = null

        override fun delete(key: String) = Unit

        override fun deletePrefix(prefix: String) = Unit

        override fun listKeysOlderThan(
            prefix: String,
            olderThan: Instant,
        ): List<String> = error("object storage unreachable")
    }
}
