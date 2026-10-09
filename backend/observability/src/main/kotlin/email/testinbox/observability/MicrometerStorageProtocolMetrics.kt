package email.testinbox.observability

import email.testinbox.application.port.PhysicalFailureKind
import email.testinbox.application.port.ReleasePath
import email.testinbox.application.port.StorageAdmissionOutcome
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.storage.activation.ActivationGate
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageScope
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-035 §16 signals of the guarded ingest protocol (TI-STORAGE-003), and
 * the activation and enablement signals of TI-STORAGE-006 (§14 Phase 4,
 * §16 `activation_violation`). Every label value comes from a closed enum,
 * and every counter is pre-registered at zero so dashboards and alerts see
 * the series before the first event.
 *
 * [policy] is the deployment's EFFECTIVE policy, so `global_limit_bytes` and
 * `finalize_budget_bytes` report the G and H admission really applies: the
 * activation barrier's physical-baseline check reads H from here.
 */
class MicrometerStorageProtocolMetrics(
    private val registry: MeterRegistry,
    policy: StorageCapacityPolicy,
    enforcement: StorageEnforcement = StorageEnforcement.OFF,
) : StorageProtocolMetrics {
    private val breakerOpen = AtomicLong(0)
    private val latched = AtomicLong(0)
    private val clockOffsetMillis = AtomicLong(0)
    private val ambiguous = AtomicLong(0)
    private val listedBytes = AtomicLong(0)
    private val reservedBytes = AtomicLong(0)
    private val incomplete = AtomicLong(0)
    private val activationViolation = AtomicLong(0)
    private val orphanSweepCompletedAt = AtomicLong(0)
    private val byState = mapOf("RESERVED" to AtomicLong(0), "RELEASING" to AtomicLong(0))

    /** Only the two gates a node can re-check at run time are exported per node (§14 Phase 4). */
    private val gates = mapOf(ActivationGate.SESSION_ALLOWLIST to AtomicLong(0), ActivationGate.NODE_INVENTORY to AtomicLong(0))
    private val modes = StorageEnforcement.entries.associateWith { AtomicLong(if (it == enforcement) 1 else 0) }
    private val lockWait = Timer.builder(LOCK_WAIT).register(registry)
    private val slotWait = Timer.builder(SLOT_WAIT).register(registry)

    init {
        gauge(BREAKER_OPEN, breakerOpen)
        gauge(LATCHED, latched)
        Gauge.builder(CLOCK_OFFSET, clockOffsetMillis) { it.get() / 1000.0 }.register(registry)
        gauge(AMBIGUOUS, ambiguous)
        gauge(LISTED, listedBytes)
        gauge(COVERED, reservedBytes, Tags.of("kind", "reserved"))
        gauge(INCOMPLETE, incomplete)
        gauge(ACTIVATION_VIOLATION, activationViolation)
        gauge(ORPHAN_SWEEP_COMPLETED_AT, orphanSweepCompletedAt)
        byState.forEach { (state, value) -> gauge(RESERVATIONS, value, Tags.of("state", state.lowercase())) }
        gates.forEach { (gate, value) -> gauge(ACTIVATION_GATE, value, Tags.of("gate", gate.name.lowercase())) }
        modes.forEach { (mode, value) -> gauge(ENFORCEMENT_MODE, value, Tags.of("mode", mode.name.lowercase())) }
        Gauge.builder(GLOBAL_LIMIT) { policy.globalLimitBytes.toDouble() }.register(registry)
        Gauge.builder(FINALIZE_BUDGET) { policy.finalizeBudgetBytes.toDouble() }.register(registry)
        StorageAdmissionOutcome.entries.forEach { registry.counter(ADMISSION, "outcome", it.name.lowercase()) }
        StorageScope.entries.forEach { registry.counter(UNENFORCED, "ceiling", it.name.lowercase()) }
        PhysicalFailureKind.entries.forEach { registry.counter(PHYSICAL_FAILURE, "kind", it.name.lowercase()) }
        ReleasePath.entries.forEach { registry.counter(RELEASED, "path", it.name.lowercase()) }
        SWEEP_OUTCOMES.forEach { registry.counter(ORPHAN_SWEEP, "outcome", it) }
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
        registry.counter(UNENFORCED, "ceiling", scope.name.lowercase()).increment()
    }

    override fun lockWait(duration: Duration) = lockWait.record(duration)

    override fun slotWait(duration: Duration) = slotWait.record(duration)

    override fun physicalFailure(kind: PhysicalFailureKind) {
        registry.counter(PHYSICAL_FAILURE, "kind", kind.name.lowercase()).increment()
    }

    override fun commitFenced() {
        registry.counter(COMMIT_FENCED).increment()
    }

    override fun footprintUnavailable(reason: email.testinbox.application.storage.FootprintUnavailability) {
        registry.counter(FOOTPRINT_UNAVAILABLE, "cause", reason.name.lowercase()).increment()
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

    override fun activationViolation(violated: Boolean) = activationViolation.set(if (violated) 1 else 0)

    override fun activationGate(
        gate: ActivationGate,
        ready: Boolean,
    ) {
        gates[gate]?.set(if (ready) 1 else 0)
    }

    override fun enforcementMode(mode: StorageEnforcement) {
        modes.forEach { (m, value) -> value.set(if (m == mode) 1 else 0) }
    }

    override fun orphanSweepCompleted(at: Instant) = orphanSweepCompletedAt.set(at.epochSecond)

    override fun orphanSweepFinished(ok: Boolean) {
        registry.counter(ORPHAN_SWEEP, "outcome", if (ok) SWEEP_OUTCOMES[0] else SWEEP_OUTCOMES[1]).increment()
    }

    override fun reservedBytes(bytes: Long) = reservedBytes.set(bytes)

    companion object {
        const val ADMISSION = "testinbox_storage_admission_total"
        const val UNENFORCED = "testinbox_storage_admission_unenforced_total"
        const val LOCK_WAIT = "testinbox_storage_admission_lock_wait_seconds"
        const val SLOT_WAIT = "testinbox_storage_slot_wait_seconds"
        const val PHYSICAL_FAILURE = "testinbox_storage_physical_failure_total"
        const val COMMIT_FENCED = "testinbox_storage_commit_fenced_total"
        const val FOOTPRINT_UNAVAILABLE = "testinbox_storage_footprint_unavailable_total"
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

        /** Shares its name with the accounting side's `kind=committed` series; this adapter owns `kind=reserved`. */
        const val COVERED = "testinbox_storage_covered_bytes"
        const val ACTIVATION_VIOLATION = "testinbox_storage_activation_violation"
        const val ACTIVATION_GATE = "testinbox_storage_activation_gate_ready"
        const val ENFORCEMENT_MODE = "testinbox_storage_enforcement_mode"
        const val ORPHAN_SWEEP_COMPLETED_AT = "testinbox_storage_orphan_sweep_completed_at_seconds"
        const val ORPHAN_SWEEP = "testinbox_storage_orphan_sweep_total"

        /** The closed outcome vocabulary of the sweep counter. */
        val SWEEP_OUTCOMES: List<String> = listOf("ok", "failed")
    }
}
