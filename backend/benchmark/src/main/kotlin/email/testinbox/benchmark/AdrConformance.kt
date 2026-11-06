package email.testinbox.benchmark

/**
 * Whether a run's INPUTS are ADR-035 §11's. The flags exist so the harness
 * can be explored (a laptop smoke, a shorter matrix, a different rate), but
 * §11's constants are not loosenable through them: a run that departs from
 * the matrix is recorded as `adrEvidence: false` with the reasons, and its
 * verdict is INCOMPLETE however good its numbers are.
 */
object AdrConformance {
    const val REQUIRED_RESERVATION_BACKLOG = 1_000

    /** One compaction interval's worth of unfolded deltas, as the laptop evidence seeded it; fewer is not the ADR's backlog. */
    const val REQUIRED_DELTA_BACKLOG = 500
    const val UNSPECIFIED_HOST_CLASS = "unspecified"

    fun reasons(options: BenchmarkOptions): List<String> {
        val reasons = mutableListOf<String>()
        // ADR-035 amendment D (2026-11-06): §11's expected rate R is the one the
        // deployment's INGEST envelope admits, r × (30 + L), not a constant. 260 stays
        // the default only. Which R counts is decided where the deployment is known:
        // the activation gate refuses evidence whose expected rate is not the
        // R_gated every node declares (scripts/check-storage-activation.sh gate D).
        val requiredRates = setOf(options.expectedRate, 2 * options.expectedRate)
        if (!options.rates.toSet().containsAll(requiredRates)) {
            reasons += "offered rates ${options.rates} do not include §11's ${requiredRates.sorted()}"
        }
        if (!options.concurrency.toSet().containsAll(GateEvaluator.REQUIRED_CONCURRENCY)) {
            reasons += "concurrency ${options.concurrency} does not include §11's ${GateEvaluator.REQUIRED_CONCURRENCY.sorted()}"
        }
        if (options.reservationBacklog < REQUIRED_RESERVATION_BACKLOG) {
            reasons += "reservation backlog ${options.reservationBacklog} is below §11's $REQUIRED_RESERVATION_BACKLOG live reservations"
        }
        if (options.deltaBacklog < REQUIRED_DELTA_BACKLOG) {
            reasons += "delta backlog ${options.deltaBacklog} is below one compaction interval's worth ($REQUIRED_DELTA_BACKLOG)"
        }
        if (options.workspaces.none { it >= GateEvaluator.REQUIRED_WORKSPACES }) {
            reasons += "no population of >= ${GateEvaluator.REQUIRED_WORKSPACES} workspaces in ${options.workspaces}"
        }
        if (options.local) {
            reasons += "--local runs against a throwaway container, not the staging host class"
        }
        if (options.hostClass == UNSPECIFIED_HOST_CLASS) {
            reasons += "--host-class was not supplied; the host class is part of the evidence"
        }
        if (!options.reference) {
            reasons += "--no-reference: §11's retention criterion needs the no-ceiling reference"
        } else if (options.referenceMode != ReferenceMode.NO_LOCK) {
            reasons += "reference mode ${options.referenceMode} is not the no-ceiling mode c (NO_LOCK)"
        }
        return reasons
    }
}
