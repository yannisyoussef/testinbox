package email.testinbox.observability

import email.testinbox.application.port.BlobOperation
import email.testinbox.application.port.SmtpRejection
import email.testinbox.application.port.WaitOutcome
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.message.ParseStatus
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Each documented signal (`docs/architecture/observability.md`) is registered,
 * and the event it describes is what increments it. A metric that exists but
 * never moves is worse than a missing one: it makes a dashboard look healthy.
 */
class MicrometerMetricsTest {
    private val registry = SimpleMeterRegistry()

    @Test
    fun `inbox lifecycle counters follow their events`() {
        val metrics = MicrometerInboxMetrics(registry)
        metrics.inboxCreated(AddressMode.GENERATED)
        metrics.inboxCreated(AddressMode.GENERATED)
        metrics.inboxCreated(AddressMode.EXACT)
        metrics.inboxExpired(3)
        metrics.inboxDeleted()

        registry.counter("testinbox_inbox_creations_total", "mode", "GENERATED").count() shouldBe 2.0
        registry.counter("testinbox_inbox_creations_total", "mode", "EXACT").count() shouldBe 1.0
        registry.counter("testinbox_inbox_expired_total").count() shouldBe 3.0
        registry.counter("testinbox_inbox_deleted_total").count() shouldBe 1.0
    }

    @Test
    fun `a sweep that expired nothing does not touch the counter`() {
        // Otherwise a 5-second sweep loop makes the counter look busy while
        // nothing at all is happening.
        MicrometerInboxMetrics(registry).inboxExpired(0)
        registry.find("testinbox_inbox_expired_total").counter()?.count() ?: 0.0 shouldBe 0.0
    }

    @Test
    fun `inbound counters separate stored, discarded and deduplicated`() {
        val metrics = MicrometerInboundMetrics(registry)
        metrics.messageReceived(ParseStatus.OK)
        metrics.messageReceived(ParseStatus.FAILED)
        metrics.unknownRecipientDiscarded()
        metrics.duplicateProviderEventNoop()
        metrics.parseCompleted(Duration.ofMillis(12), ParseStatus.OK)

        registry.counter("testinbox_message_received_total", "parse_status", "OK").count() shouldBe 1.0
        registry.counter("testinbox_message_received_total", "parse_status", "FAILED").count() shouldBe 1.0
        // ADR-025 keeps the SMTP reply uniform, so this is the only signal.
        registry.counter("testinbox_smtp_unknown_recipient_discard_total").count() shouldBe 1.0
        registry.counter("testinbox_message_duplicate_event_noop_total").count() shouldBe 1.0

        val timer = registry.timer("testinbox_message_parse_duration_seconds", "parse_status", "OK")
        timer.count() shouldBe 1L
        (timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 12.0) shouldBe true
    }

    @Test
    fun `a parse failure is still timed - a hostile message is exactly the slow one`() {
        MicrometerInboundMetrics(registry).parseCompleted(Duration.ofSeconds(2), ParseStatus.FAILED)
        registry.timer("testinbox_message_parse_duration_seconds", "parse_status", "FAILED").count() shouldBe 1L
    }

    @Test
    fun `wait duration is recorded per outcome and the active gauge returns to zero`() {
        val metrics = MicrometerWaitMetrics(registry)
        metrics.waitStarted()
        metrics.waitStarted()
        registry.find("testinbox_wait_requests_active").gauge()!!.value() shouldBe 2.0

        metrics.waitCompleted(WaitOutcome.MATCHED, Duration.ofMillis(40))
        metrics.waitCompleted(WaitOutcome.TIMEOUT, Duration.ofSeconds(60))

        // A gauge that only climbed would make a handle leak look like load.
        registry.find("testinbox_wait_requests_active").gauge()!!.value() shouldBe 0.0
        registry.timer("testinbox_wait_request_duration_seconds", "outcome", "MATCHED").count() shouldBe 1L
        registry.timer("testinbox_wait_request_duration_seconds", "outcome", "TIMEOUT").count() shouldBe 1L
    }

    @Test
    fun `object storage operations are timed by operation and outcome`() {
        val metrics = MicrometerBlobStoreMetrics(registry)
        metrics.operationCompleted(BlobOperation.PUT, Duration.ofMillis(5), success = true)
        metrics.operationCompleted(BlobOperation.GET, Duration.ofMillis(3), success = false)

        registry
            .timer("testinbox_object_storage_operation_duration_seconds", "operation", "PUT", "outcome", "success")
            .count() shouldBe 1L
        registry
            .timer("testinbox_object_storage_operation_duration_seconds", "operation", "GET", "outcome", "failure")
            .count() shouldBe 1L
    }

    @Test
    fun `SMTP acceptance and refusals are counted, refusals by protocol reason`() {
        val metrics = MicrometerSmtpMetrics(registry)
        metrics.accepted()
        metrics.rejected(SmtpRejection.MESSAGE_TOO_LARGE)
        metrics.rejected(SmtpRejection.INVALID_RECIPIENT)

        registry.counter("testinbox_smtp_accept_total").count() shouldBe 1.0
        registry.counter("testinbox_smtp_reject_total", "reason", "MESSAGE_TOO_LARGE").count() shouldBe 1.0
        registry.counter("testinbox_smtp_reject_total", "reason", "INVALID_RECIPIENT").count() shouldBe 1.0
    }

    @Test
    fun `no SMTP rejection reason can reveal whether a recipient exists (ADR-025)`() {
        // The reasons are protocol-level only. If "unknown recipient" were ever
        // added here it would be a metrics-side enumeration oracle, so the
        // closed enum is asserted rather than assumed.
        SmtpRejection.entries.map { it.name }.toSet() shouldBe
            setOf("INVALID_RECIPIENT", "MESSAGE_TOO_LARGE", "PROCESSING_FAILED", "SCHEMA_UNAVAILABLE")
    }

    @Test
    fun `build info is a constant one carrying identity as labels`() {
        BuildInfoMetric(registry, service = "testinbox-api", gitSha = "abc1234", version = "0.1.0")
        val gauge = registry.find("testinbox_build").gauge()!!
        gauge.value() shouldBe 1.0
        gauge.id.getTag("service") shouldBe "testinbox-api"
        gauge.id.getTag("git_sha") shouldBe "abc1234"
        gauge.id.getTag("version") shouldBe "0.1.0"
        // The image digest is deliberately absent: an image cannot know its own
        // digest, and inventing one would be a lie in the identity metric.
        (gauge.id.getTag("image_digest") == null) shouldBe true
    }
}
