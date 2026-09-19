package email.testinbox.api.ops

import email.testinbox.api.config.TestInboxProperties
import email.testinbox.application.deployment.AppliedSchema
import email.testinbox.application.deployment.DatabaseSessionPolicy
import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.deployment.SchemaHistory
import email.testinbox.application.deployment.SchemaVersion
import email.testinbox.application.port.BlobStore
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import java.time.Instant

/**
 * Readiness is what decides whether a node receives traffic, and the whole
 * behaviour of these indicators is one mapping: healthy → UP, otherwise
 * not-UP. Nothing else in the suite asserts it — invert the condition and the
 * rehearsal still passes, because the rehearsal only ever runs them healthy.
 * The DOWN branches in particular are unreachable from any deployment test.
 */
class ReadinessIndicatorTest {
    private fun schemaAt(
        applied: List<String>,
        failed: List<String> = emptyList(),
        bundled: String = "3",
    ) = SchemaHealthIndicator(
        SchemaCompatibility(SchemaHistory { AppliedSchema(applied, failed, true) }, SchemaVersion(bundled)),
    )

    @Test
    fun `a schema at the bundled version is ready`() {
        val health = schemaAt(listOf("1", "2", "3")).health()
        health.status shouldBe Status.UP
        health.details["applied"] shouldBe "3"
        health.details["bundled"] shouldBe "3"
    }

    @Test
    fun `a schema behind this artifact takes the node out of rotation`() {
        // ADR-029 §4: the migration has not run, so this node must not serve.
        schemaAt(listOf("1", "2")).health().status shouldBe Status.OUT_OF_SERVICE
    }

    @Test
    fun `a schema ahead of this artifact stays ready - a rolled-back artifact must serve`() {
        // If this ever became OUT_OF_SERVICE, artifact rollback (ADR-028)
        // would be impossible after any migration.
        schemaAt(listOf("1", "2", "3", "4"), bundled = "3").health().status shouldBe Status.UP
    }

    @Test
    fun `a failed migration in history takes the node out of rotation`() {
        schemaAt(listOf("1", "2", "3"), failed = listOf("4")).health().status shouldBe Status.OUT_OF_SERVICE
    }

    @Test
    fun `object storage that answers is ready`() {
        ObjectStorageHealthIndicator(FakeBlobStore(failing = false)).health().status shouldBe Status.UP
    }

    @Test
    fun `object storage that cannot be reached is not ready, and the detail carries no endpoint or credential`() {
        // Raw MIME is written before the DB row (ADR-005), so a node that
        // cannot reach the store cannot honour the ordering.
        val health = ObjectStorageHealthIndicator(FakeBlobStore(failing = true)).health()
        health.status shouldBe Status.DOWN
        // An S3 exception message can carry the endpoint; only the type is surfaced.
        health.details["detail"] shouldBe "IllegalStateException"
    }

    private fun dbSession(
        raw: String,
        required: Boolean,
    ) = DatabaseSessionHealthIndicator(
        DatabaseSessionPolicy { raw },
        TestInboxProperties(deployment = TestInboxProperties.Deployment(requireDatabaseSessionTimeout = required)),
    )

    @Test
    fun `a bounded session timeout is ready wherever it is checked`() {
        for (required in listOf(true, false)) {
            val health = dbSession("30s", required).health()
            health.status shouldBe Status.UP
            health.details["bounded"] shouldBe true
            health.details["enforced"] shouldBe required
        }
    }

    @Test
    fun `production takes a node out of rotation while the database leaves a hung claim unbounded (ADR-033)`() {
        val health = dbSession("0", required = true).health()
        health.status shouldBe Status.OUT_OF_SERVICE
        health.details["idleInTransactionSessionTimeout"] shouldBe "0"
        health.details["bounded"] shouldBe false
    }

    @Test
    fun `elsewhere the same condition is reported but does not remove the node`() {
        // A staging estate that has not set the timeout must stay in service —
        // and visible — rather than be removed by the deploy that added this.
        val health = dbSession("0", required = false).health()
        health.status shouldBe Status.UP
        health.details["bounded"] shouldBe false
        health.details["enforced"] shouldBe false
    }

    @Test
    fun `a database that cannot be asked is down, and the detail carries only the exception type`() {
        val failing =
            DatabaseSessionHealthIndicator(
                DatabaseSessionPolicy { error("connection refused to db.prod.internal:5432 (user=svc)") },
                TestInboxProperties(deployment = TestInboxProperties.Deployment(requireDatabaseSessionTimeout = true)),
            )
        val health = failing.health()
        health.status shouldBe Status.DOWN
        health.details["detail"] shouldBe "IllegalStateException"
    }

    private class FakeBlobStore(
        private val failing: Boolean,
    ) : BlobStore {
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
        ): List<String> {
            check(!failing) { "object storage unreachable at https://objects.example.internal (accessKey=fixture-key)" }
            return emptyList()
        }
    }
}
