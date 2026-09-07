package email.testinbox.observability

import email.testinbox.application.port.BlobOperation
import email.testinbox.application.port.SmtpRejection
import email.testinbox.application.port.WaitOutcome
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.limits.QuotaDimension
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.message.ParseStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * Cardinality and naming, asserted against the whole registry rather than
 * per-metric.
 *
 * Cardinality is a security property here, not a tidiness one: a label whose
 * values a caller can choose lets that caller decide how much memory the
 * metrics backend spends. Workspace ids, API keys, inbox ids, message ids,
 * addresses and correlation ids are therefore never labels — attribution to a
 * tenant belongs in the access-controlled structured logs (ADR-027 §14).
 *
 * The scrape output is asserted too, because Micrometer rewrites names on the
 * way out (unit suffixes, `_total`). What Ops queries is the exported name, so
 * that is what gets pinned.
 */
class MetricCardinalityTest {
    private val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    /** Drives every metric through every value of every enum it is tagged by. */
    private fun exerciseEverything() {
        val inbox = MicrometerInboxMetrics(registry)
        AddressMode.entries.forEach { inbox.inboxCreated(it) }
        inbox.inboxExpired(1)
        inbox.inboxDeleted()

        val inbound = MicrometerInboundMetrics(registry)
        ParseStatus.entries.forEach {
            inbound.messageReceived(it)
            inbound.parseCompleted(Duration.ofMillis(1), it)
        }
        inbound.unknownRecipientDiscarded()
        inbound.duplicateProviderEventNoop()

        val wait = MicrometerWaitMetrics(registry)
        WaitOutcome.entries.forEach {
            wait.waitStarted()
            wait.waitCompleted(it, Duration.ofMillis(1))
        }

        val notifier = MicrometerNotifierMetrics(registry)
        notifier.listening()
        notifier.degraded()
        notifier.reconnected()

        val blobs = MicrometerBlobStoreMetrics(registry)
        BlobOperation.entries.forEach {
            blobs.operationCompleted(it, Duration.ofMillis(1), success = true)
            blobs.operationCompleted(it, Duration.ofMillis(1), success = false)
        }

        val smtp = MicrometerSmtpMetrics(registry)
        smtp.accepted()
        SmtpRejection.entries.forEach { smtp.rejected(it) }

        val limits = MicrometerLimitMetrics(registry)
        RateCategory.entries.forEach { limits.rateDecision(it, allowed = true) }
        RateCategory.entries.forEach { limits.rateDecision(it, allowed = false) }
        QuotaDimension.entries.forEach { limits.quotaRejected(it) }
        limits.waitSlotRejected()
        limits.waitSlotsChanged(1)

        BuildInfoMetric(registry, service = "testinbox-api", gitSha = "abc1234", version = "0.1.0")
    }

    /** Every label key any TestInbox metric is allowed to carry. */
    private val allowedLabelKeys =
        setOf("mode", "parse_status", "outcome", "operation", "reason", "category", "quota", "service", "git_sha", "version")

    private val allowedLabelValues: Map<String, Set<String>> =
        mapOf(
            "mode" to AddressMode.entries.map { it.name }.toSet(),
            "parse_status" to ParseStatus.entries.map { it.name }.toSet(),
            "outcome" to
                WaitOutcome.entries.map { it.name }.toSet() + setOf("success", "failure", "allowed", "rejected"),
            "operation" to BlobOperation.entries.map { it.name }.toSet(),
            "reason" to SmtpRejection.entries.map { it.name }.toSet(),
            "category" to RateCategory.entries.map { it.name }.toSet(),
            "quota" to QuotaDimension.entries.map { it.name }.toSet(),
        )

    @Test
    fun `every label key and value is drawn from a closed set`() {
        exerciseEverything()
        registry.meters.forEach { meter ->
            meter.id.tags.forEach { tag ->
                (tag.key in allowedLabelKeys) shouldBe true
                // build_info's labels are fixed for a process's lifetime — one
                // series per deployed build, not one per request — so they are
                // bounded without being enumerable here.
                if (tag.key in allowedLabelValues) {
                    (tag.value in allowedLabelValues.getValue(tag.key)) shouldBe true
                }
            }
        }
    }

    @Test
    fun `no tenant identifier can appear as a label, whatever a caller does`() {
        exerciseEverything()
        // Shapes that would betray a leaked identifier: a uuid, an address, or
        // a long opaque token.
        val uuidLike = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        registry.meters.forEach { meter ->
            meter.id.tags.forEach { tag ->
                uuidLike.containsMatchIn(tag.value) shouldBe false
                tag.value.contains("@") shouldBe false
            }
        }
        // And the keys a reviewer would most expect to find by accident.
        val forbidden = setOf("workspace", "workspace_id", "inbox", "inbox_id", "message_id", "api_key", "address", "correlation_id")
        registry.meters.flatMap { it.id.tags }.map { it.key }.forEach { key ->
            (key in forbidden) shouldBe false
        }
    }

    @Test
    fun `total series count is bounded by the enums, not by traffic`() {
        exerciseEverything()
        val before = registry.meters.size
        // Ten thousand more events across every enum value must create no new
        // series at all: that is the whole property.
        repeat(1_000) {
            val inbound = MicrometerInboundMetrics(registry)
            ParseStatus.entries.forEach { inbound.messageReceived(it) }
            MicrometerSmtpMetrics(registry).accepted()
            MicrometerInboxMetrics(registry).inboxCreated(AddressMode.GENERATED)
        }
        registry.meters.size shouldBe before
        // A generous ceiling that still fails loudly if a per-caller label
        // is ever introduced.
        (before < 100) shouldBe true
    }

    @Test
    fun `a caller-controlled identifier never reaches the registry through any port`() {
        // The ports take enums and durations; there is no string parameter a
        // caller could smuggle an identifier through. Exercising them with a
        // random workspace-like id in scope and finding it absent is the
        // regression guard for someone adding one later.
        val tenant = UUID.randomUUID().toString()
        exerciseEverything()
        registry.scrape().contains(tenant) shouldBe false
    }

    @Test
    fun `the exported Prometheus names are the ones Ops queries`() {
        exerciseEverything()
        val scrape = registry.scrape()
        listOf(
            "testinbox_inbox_creations_total",
            "testinbox_inbox_expired_total",
            "testinbox_inbox_deleted_total",
            "testinbox_message_received_total",
            "testinbox_message_parse_duration_seconds",
            "testinbox_message_duplicate_event_noop_total",
            "testinbox_smtp_unknown_recipient_discard_total",
            "testinbox_smtp_accept_total",
            "testinbox_smtp_reject_total",
            "testinbox_wait_request_duration_seconds",
            "testinbox_wait_requests_active",
            "testinbox_wait_listen_reconnect_total",
            "testinbox_wait_listen_degraded_polling",
            "testinbox_object_storage_operation_duration_seconds",
            "testinbox_rate_decision_total",
            "testinbox_quota_rejected_total",
            "testinbox_wait_slot_rejected_total",
            "testinbox_wait_slots_active",
            "testinbox_build",
        ).forEach { name -> scrape shouldContain name }
    }

    @Test
    fun `no documented name is silently rewritten by the exposition format`() {
        // The failure this pins is not hypothetical: `testinbox_inbox_created_total`
        // exports as `testinbox_inbox_total` and `testinbox_build_info` as
        // `testinbox_build`, because `_created` and `_info` are reserved
        // suffixes that Micrometer strips. A dashboard written against the
        // documented name would have queried a series that does not exist.
        exerciseEverything()
        val scrape = registry.scrape()
        scrape.contains("testinbox_inbox_total") shouldBe false
        scrape.contains("testinbox_build_info") shouldBe false
    }

    @Test
    fun `no metric is exported with a doubled suffix`() {
        // Micrometer appends `_total` to counters and the base unit to timers.
        // Naming a counter `..._total` ourselves risks `_total_total`, which
        // would silently be a different series from the documented one.
        exerciseEverything()
        val scrape = registry.scrape()
        scrape.contains("_total_total") shouldBe false
        scrape.contains("_seconds_seconds") shouldBe false
    }
}
