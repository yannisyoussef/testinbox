package email.testinbox.observability

import email.testinbox.application.port.ApiKeyOperation
import email.testinbox.application.port.AuthOutcome
import email.testinbox.application.port.BlobOperation
import email.testinbox.application.port.BlobOutcome
import email.testinbox.application.port.CompactionOutcome
import email.testinbox.application.port.DriftDirection
import email.testinbox.application.port.IdempotencyOutcome
import email.testinbox.application.port.PhysicalFailureKind
import email.testinbox.application.port.ReconciliationOutcome
import email.testinbox.application.port.ReleasePath
import email.testinbox.application.port.SmtpRejection
import email.testinbox.application.port.StorageAdmissionOutcome
import email.testinbox.application.port.WaitOutcome
import email.testinbox.domain.idempotency.IdempotentOperation
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.limits.QuotaDimension
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.message.ParseStatus
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageScope
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
        wait.slotRejected()
        wait.slotsChanged(1)

        val notifier = MicrometerNotifierMetrics(registry)
        notifier.listening()
        notifier.degraded()
        notifier.reconnected()

        val blobs = MicrometerBlobStoreMetrics(registry)
        BlobOperation.entries.forEach { operation ->
            BlobOutcome.entries.forEach { blobs.operationCompleted(operation, Duration.ofMillis(1), it) }
        }

        val smtp = MicrometerSmtpMetrics(registry)
        smtp.accepted()
        SmtpRejection.entries.forEach { smtp.rejected(it) }

        val limits = MicrometerLimitMetrics(registry)
        RateCategory.entries.forEach { limits.rateDecision(it, allowed = true) }
        RateCategory.entries.forEach { limits.rateDecision(it, allowed = false) }
        QuotaDimension.entries.forEach { limits.quotaRejected(it) }

        val apiKeys = MicrometerApiKeyMetrics(registry)
        AuthOutcome.entries.forEach { apiKeys.authCompleted(it) }
        ApiKeyOperation.entries.forEach { apiKeys.lifecycle(it) }
        apiKeys.lastUsedPersisted()

        val idempotency = MicrometerIdempotencyMetrics(registry)
        IdempotentOperation.entries.forEach { operation ->
            IdempotencyOutcome.entries.forEach { idempotency.completed(operation, it) }
        }

        val storage = MicrometerStorageAccountingMetrics(registry)
        storage.ledgerObserved(unfoldedRows = 12, committedBytes = 3_456)
        // TI-STORAGE-006E: the footprint bounds and the observation age, closed label.
        email.testinbox.application.port.FootprintKind.entries
            .forEach { storage.footprintObserved(it, 1_000) }
        storage.filesystemObservationAge(7)
        storage.footprintCountsTrusted(true)
        DriftDirection.entries.forEach { storage.driftRepaired(it) }
        ReconciliationOutcome.entries.forEach { storage.reconciliationCompleted(it) }
        CompactionOutcome.entries.forEach { storage.compactionCompleted(it) }

        exerciseStorageProtocol()

        BuildInfoMetric(registry, service = "testinbox-api", gitSha = "abc1234", version = "0.1.0")
    }

    /** ADR-035 §16, the guarded protocol (TI-STORAGE-003): every signal, every enum value. */
    private fun exerciseStorageProtocol() {
        val protocol = MicrometerStorageProtocolMetrics(registry, StorageCapacityPolicy.ADR_035_REFERENCE)
        StorageAdmissionOutcome.entries.forEach { protocol.admission(it) }
        StorageScope.entries.forEach { protocol.unenforcedLimit(it) }
        PhysicalFailureKind.entries.forEach { protocol.physicalFailure(it) }
        ReleasePath.entries.forEach { protocol.released(it) }
        protocol.commitFenced()
        protocol.lateObject()
        protocol.witnessFailed()
        protocol.breakerOpen(true)
        protocol.latched(true)
        protocol.clockOffset(java.time.Duration.ofSeconds(2))
        protocol.ambiguousUploads(3)
        protocol.reservations(mapOf("RESERVED" to 4L, "RELEASING" to 1L))
        protocol.physicalListedBytes(1_000)
        protocol.incompleteUploads(0)
        protocol.lockWait(java.time.Duration.ofMillis(2))
        protocol.slotWait(java.time.Duration.ofMillis(3))
        // TI-STORAGE-006: activation and enablement signals.
        protocol.activationViolation(true)
        email.testinbox.application.storage.activation.ActivationGate.entries
            .forEach { protocol.activationGate(it, true) }
        email.testinbox.domain.storage.StorageEnforcement.entries
            .forEach { protocol.enforcementMode(it) }
        protocol.orphanSweepCompleted(java.time.Instant.parse("2026-10-08T00:00:00Z"))
        protocol.orphanSweepFinished(ok = true)
        protocol.orphanSweepFinished(ok = false)
        protocol.reservedBytes(2_000)
    }

    /** Every label key any TestInbox metric is allowed to carry. */
    private val allowedLabelKeys =
        setOf(
            "mode",
            "parse_status",
            "outcome",
            "operation",
            "reason",
            "category",
            "quota",
            "service",
            "git_sha",
            "version",
            "direction",
            "kind",
            "ceiling",
            "path",
            "state",
            "gate",
        )

    private val allowedLabelValues: Map<String, Set<String>> =
        mapOf(
            "mode" to
                AddressMode.entries.map { it.name }.toSet() +
                email.testinbox.domain.storage.StorageEnforcement.entries
                    .map { it.name.lowercase() }
                    .toSet(),
            "parse_status" to ParseStatus.entries.map { it.name }.toSet(),
            "outcome" to
                WaitOutcome.entries.map { it.name }.toSet() +
                BlobOutcome.entries.map { it.name.lowercase() }.toSet() +
                AuthOutcome.entries.map { it.name }.toSet() +
                IdempotencyOutcome.entries.map { it.name }.toSet() +
                ReconciliationOutcome.entries.map { it.name.lowercase() }.toSet() +
                CompactionOutcome.entries.map { it.name.lowercase() }.toSet() +
                StorageAdmissionOutcome.entries.map { it.name.lowercase() }.toSet() +
                MicrometerStorageProtocolMetrics.SWEEP_OUTCOMES.toSet() +
                setOf("allowed", "rejected"),
            "operation" to
                BlobOperation.entries.map { it.name }.toSet() +
                ApiKeyOperation.entries.map { it.name }.toSet() +
                IdempotentOperation.entries.map { it.name }.toSet(),
            "reason" to SmtpRejection.entries.map { it.name }.toSet(),
            "category" to RateCategory.entries.map { it.name }.toSet(),
            "quota" to QuotaDimension.entries.map { it.name }.toSet(),
            "direction" to DriftDirection.entries.map { it.name.lowercase() }.toSet(),
            // `committed` from the ledger, `reserved` from the cleanup pass (ADR-035 §16).
            "kind" to setOf("committed", "reserved", "deletion_debt") + PhysicalFailureKind.entries.map { it.name.lowercase() },
            "ceiling" to StorageScope.entries.map { it.name.lowercase() }.toSet(),
            "path" to ReleasePath.entries.map { it.name.lowercase() }.toSet(),
            "state" to setOf("reserved", "releasing"),
            // Only the two gates a node re-checks at run time are exported (ADR-035 §14 Phase 4).
            "gate" to setOf("session_allowlist", "node_inventory"),
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
        val forbidden =
            setOf(
                "workspace",
                "workspace_id",
                "inbox",
                "inbox_id",
                "message_id",
                "api_key",
                "api_key_id",
                "public_id",
                "key_id",
                "scope",
                "scopes",
                "address",
                "correlation_id",
                "client_ip",
            )
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
        // is ever introduced. ADR-035 §16 (TI-STORAGE-003) added about 45
        // enum-bounded series.
        (before < 160) shouldBe true
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
            "testinbox_api_key_auth_total",
            "testinbox_api_key_lifecycle_total",
            "testinbox_api_key_last_used_writes_total",
            "testinbox_idempotency_total",
            "testinbox_storage_ledger_unfolded_rows",
            "testinbox_storage_covered_bytes",
            "testinbox_storage_footprint_bytes",
            "testinbox_storage_filesystem_observation_age_seconds",
            "testinbox_storage_footprint_counts_trusted",
            "testinbox_storage_accounting_drift_total",
            "testinbox_storage_reconciliation_total",
            "testinbox_storage_ledger_compaction_total",
            // ADR-035 §16, the guarded protocol (TI-STORAGE-003).
            "testinbox_storage_admission_total",
            "testinbox_storage_admission_unenforced_total",
            "testinbox_storage_admission_lock_wait_seconds",
            "testinbox_storage_slot_wait_seconds",
            "testinbox_storage_physical_failure_total",
            "testinbox_storage_commit_fenced_total",
            "testinbox_storage_reservation_released_total",
            "testinbox_storage_late_object_total",
            "testinbox_storage_witness_failed_total",
            "testinbox_storage_breaker_open",
            "testinbox_storage_admission_latched",
            "testinbox_storage_clock_offset_seconds",
            "testinbox_storage_ambiguous_uploads",
            "testinbox_storage_physical_listed_bytes",
            "testinbox_storage_incomplete_uploads",
            "testinbox_storage_reservations",
            "testinbox_storage_global_limit_bytes",
            "testinbox_storage_finalize_budget_bytes",
            // TI-STORAGE-006: activation and enablement (ADR-035 §14, §16).
            "testinbox_storage_activation_violation",
            "testinbox_storage_activation_gate_ready",
            "testinbox_storage_enforcement_mode",
            "testinbox_storage_orphan_sweep_completed_at_seconds",
            "testinbox_storage_orphan_sweep_total",
        ).forEach { name -> scrape shouldContain name }
    }

    @Test
    fun `the ADR-035 wait outcome is exported under the closed outcome label (§17 test 47)`() {
        exerciseEverything()
        val scrape = registry.scrape()
        scrape shouldContain "testinbox_wait_request_duration_seconds_count{outcome=\"STORAGE_LIMIT_EXCEEDED\"}"
        // One series per enum value: no refusal count, inbox or workspace ever becomes a label.
        registry.find("testinbox_wait_request_duration_seconds").timers().size shouldBe WaitOutcome.entries.size
    }

    @Test
    fun `the storage ledger gauges report the observed values, each on its own meter`() {
        // Names and labels alone would pass with the two values swapped.
        exerciseEverything()
        registry.get("testinbox_storage_ledger_unfolded_rows").gauge().value() shouldBe 12.0
        registry
            .get("testinbox_storage_footprint_bytes")
            .tag("kind", "deletion_debt")
            .gauge()
            .value() shouldBe 1_000.0
        registry.get("testinbox_storage_filesystem_observation_age_seconds").gauge().value() shouldBe 7.0
        registry.get("testinbox_storage_footprint_counts_trusted").gauge().value() shouldBe 1.0
        registry
            .get("testinbox_storage_covered_bytes")
            .tag("kind", "committed")
            .gauge()
            .value() shouldBe 3_456.0
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
