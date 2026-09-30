package email.testinbox.observability

import email.testinbox.application.port.PhysicalFailureKind
import email.testinbox.application.port.ReleasePath
import email.testinbox.application.port.StorageAdmissionOutcome
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageScope
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-035 §16 signals of the guarded ingest protocol (TI-STORAGE-003).
 * Every label value comes from a closed enum, and every counter is
 * pre-registered at zero so dashboards and alerts see the series before the
 * first event.
 */
class MicrometerStorageProtocolMetrics(
    private val registry: MeterRegistry,
    policy: StorageCapacityPolicy,
) : StorageProtocolMetrics {
    private val breakerOpen = AtomicLong(0)
    private val latched = AtomicLong(0)
    private val clockOffsetMillis = AtomicLong(0)
    private val ambiguous = AtomicLong(0)
    private val listedBytes = AtomicLong(0)
    private val incomplete = AtomicLong(0)
    private val byState = mapOf("RESERVED" to AtomicLong(0), "RELEASING" to AtomicLong(0))
    private val lockWait = Timer.builder(LOCK_WAIT).register(registry)
    private val slotWait = Timer.builder(SLOT_WAIT).register(registry)

    init {
        gauge(BREAKER_OPEN, breakerOpen)
        gauge(LATCHED, latched)
        Gauge.builder(CLOCK_OFFSET, clockOffsetMillis) { it.get() / 1000.0 }.register(registry)
        gauge(AMBIGUOUS, ambiguous)
        gauge(LISTED, listedBytes)
        gauge(INCOMPLETE, incomplete)
        byState.forEach { (state, value) -> gauge(RESERVATIONS, value, Tags.of("state", state.lowercase())) }
        Gauge.builder(GLOBAL_LIMIT) { policy.globalLimitBytes.toDouble() }.register(registry)
        Gauge.builder(FINALIZE_BUDGET) { policy.finalizeBudgetBytes.toDouble() }.register(registry)
        StorageAdmissionOutcome.entries.forEach { registry.counter(ADMISSION, "outcome", it.name.lowercase()) }
        StorageScope.entries.forEach { registry.counter(UNENFORCED, "scope", it.name.lowercase()) }
        PhysicalFailureKind.entries.forEach { registry.counter(PHYSICAL_FAILURE, "kind", it.name.lowercase()) }
        ReleasePath.entries.forEach { registry.counter(RELEASED, "path", it.name.lowercase()) }
        registry.counter(COMMIT_FENCED)
        registry.counter(LATE_OBJECT)
        registry.counter(WITNESS_FAILED)
    }

    private fun gauge(
        name: String,
        value: AtomicLong,
        tags: Tags = Tags.empty(),
    ) {
        Gauge.builder(name, value) { it.get().toDouble() }.tags(tags).register(registry)
    }

    override fun admission(outcome: StorageAdmissionOutcome) {
        registry.counter(ADMISSION, "outcome", outcome.name.lowercase()).increment()
    }

    override fun unenforcedLimit(scope: StorageScope) {
        registry.counter(UNENFORCED, "scope", scope.name.lowercase()).increment()
    }

    override fun lockWait(duration: Duration) = lockWait.record(duration)

    override fun slotWait(duration: Duration) = slotWait.record(duration)

    override fun physicalFailure(kind: PhysicalFailureKind) {
        registry.counter(PHYSICAL_FAILURE, "kind", kind.name.lowercase()).increment()
    }

    override fun commitFenced() {
        registry.counter(COMMIT_FENCED).increment()
    }

    override fun released(path: ReleasePath) {
        registry.counter(RELEASED, "path", path.name.lowercase()).increment()
    }

    override fun lateObject() {
        registry.counter(LATE_OBJECT).increment()
    }

    override fun breakerOpen(open: Boolean) = breakerOpen.set(if (open) 1 else 0)

    override fun latched(latched: Boolean) = this.latched.set(if (latched) 1 else 0)

    override fun clockOffset(offset: Duration) = clockOffsetMillis.set(offset.toMillis())

    override fun ambiguousUploads(count: Int) = ambiguous.set(count.toLong())

    override fun reservations(byState: Map<String, Long>) {
        this.byState.forEach { (state, value) -> value.set(byState[state] ?: 0) }
    }

    override fun physicalListedBytes(bytes: Long) = listedBytes.set(bytes)

    override fun incompleteUploads(count: Int) = incomplete.set(count.toLong())

    override fun witnessFailed() {
        registry.counter(WITNESS_FAILED).increment()
    }

    companion object {
        const val ADMISSION = "testinbox_storage_admission_total"
        const val UNENFORCED = "testinbox_storage_admission_unenforced_total"
        const val LOCK_WAIT = "testinbox_storage_admission_lock_wait_seconds"
        const val SLOT_WAIT = "testinbox_storage_slot_wait_seconds"
        const val PHYSICAL_FAILURE = "testinbox_storage_physical_failure_total"
        const val COMMIT_FENCED = "testinbox_storage_commit_fenced_total"
        const val RELEASED = "testinbox_storage_reservation_released_total"
        const val LATE_OBJECT = "testinbox_storage_late_object_total"
        const val WITNESS_FAILED = "testinbox_storage_witness_failed_total"
        const val BREAKER_OPEN = "testinbox_storage_breaker_open"
        const val LATCHED = "testinbox_storage_admission_latched"
        const val CLOCK_OFFSET = "testinbox_storage_clock_offset_seconds"
        const val AMBIGUOUS = "testinbox_storage_ambiguous_uploads"
        const val LISTED = "testinbox_storage_physical_listed_bytes"
        const val INCOMPLETE = "testinbox_storage_incomplete_uploads"
        const val RESERVATIONS = "testinbox_storage_reservations"
        const val GLOBAL_LIMIT = "testinbox_storage_global_limit_bytes"
        const val FINALIZE_BUDGET = "testinbox_storage_finalize_budget_bytes"
    }
}
