package email.testinbox.application.storage.activation

import email.testinbox.application.port.ActivationInventory
import email.testinbox.application.port.DatabaseSession
import email.testinbox.application.port.StorageNodeRow
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.port.WorkspacePopulation
import email.testinbox.application.port.WorkspacePopulationReader
import email.testinbox.application.storage.StorageProtocol
import email.testinbox.domain.identity.BetaCohortPolicy
import email.testinbox.domain.storage.StorageEnforcement
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/** The gates of the ADR-035 §14 Phase 3 activation barrier, plus the §9a qualification state. */
enum class ActivationGate {
    SESSION_ALLOWLIST,
    NODE_INVENTORY,
    PHYSICAL_BASELINE,
    CLOCK_OFFSET,
    GLOBAL_BENCHMARK,
    ROLLBACK_FLOOR,
    QUALIFICATION,

    /** ADR-035 amendment D: before a cohort plan exists, at most N + L workspaces may receive mail (N ≤ 30, the participant cap). */
    WORKSPACE_ENVELOPE,
}

/** A gate answers one of these; never a bare boolean, so "not required" and "not run" cannot read as PASS. */
enum class GateVerdict { PASS, BLOCKED, NOT_RUN, NOT_REQUIRED }

data class GateResult(
    val gate: ActivationGate,
    val verdict: GateVerdict,
    /** Operator-facing: names infrastructure identities (node ids, application names), never a credential. */
    val detail: String,
)

/**
 * ADR-035 §14 (a), the capability allowlist. Every session of the
 * APPLICATION database role must be named `testinbox-<service>:<node>:storage-v1`,
 * except the named exclusions: the migrator (`testinbox-migrator:%`) and Ops
 * sessions tagged `ops:%`. Old binaries show pgJDBC's default name, and the
 * old LISTEN connection is `testinbox-listen`, so both fail.
 *
 * Sessions of OTHER roles (a backup role's `pg_dump`, a metrics exporter, a
 * DBA's `psql`) are outside the allowlist, exactly as the ADR words it: an
 * old binary can only ever connect as the application role. A human who
 * connects AS the application role must tag the session `ops:<purpose>`.
 */
object SessionAllowlist {
    private val ALLOWED = Regex("^testinbox-[^:]+:[^:]*:${Regex.escape(StorageProtocol.CAPABILITY)}$")
    private val EXCLUDED = listOf(Regex("^testinbox-migrator:"), Regex("^ops:"))

    fun allows(applicationName: String): Boolean = ALLOWED.matches(applicationName)

    fun excluded(applicationName: String): Boolean = EXCLUDED.any { it.containsMatchIn(applicationName) }

    /** The sessions of [applicationRole] (every session when null) that are neither allowed nor excluded. */
    fun violations(
        sessions: List<DatabaseSession>,
        applicationRole: String? = null,
    ): List<DatabaseSession> =
        sessions
            .filter { applicationRole == null || it.role == applicationRole }
            .filterNot { allows(it.applicationName) || excluded(it.applicationName) }

    fun evaluate(
        sessions: List<DatabaseSession>,
        applicationRole: String? = null,
    ): GateResult {
        val inScope = sessions.filter { applicationRole == null || it.role == applicationRole }
        val offending = violations(sessions, applicationRole)
        val scope = applicationRole?.let { " of role '$it'" }.orEmpty()
        return if (offending.isEmpty()) {
            GateResult(
                ActivationGate.SESSION_ALLOWLIST,
                GateVerdict.PASS,
                "${inScope.size} session(s)$scope, every one allowlisted or excluded",
            )
        } else {
            GateResult(
                ActivationGate.SESSION_ALLOWLIST,
                GateVerdict.BLOCKED,
                "${offending.size} session(s) outside the allowlist: " +
                    offending.joinToString(", ") { "'${it.applicationName.ifBlank { "<empty>" }}' (role ${it.role})" },
            )
        }
    }
}

/**
 * The nodes a deployment DECLARES (TI-STORAGE-006 §20). Never inferred from
 * what happens to be heartbeating: a missing node would shrink the expected
 * set and pass. Exact node ids, grouped by role so a failure names which
 * kind of node is missing.
 */
data class ExpectedNodes(
    val api: Set<String>,
    val ingestion: Set<String>,
) {
    init {
        require(api.none { it.isBlank() } && ingestion.none { it.isBlank() }) { "a declared node id must not be blank" }
        require(
            api.intersect(ingestion).isEmpty(),
        ) { "a node id cannot be declared as both api and ingestion: ${api.intersect(ingestion)}" }
    }

    val all: Set<String> get() = api + ingestion
    val declared: Boolean get() = all.isNotEmpty()

    fun roleOf(nodeId: String): String =
        when (nodeId) {
            in api -> "api"
            in ingestion -> "ingestion"
            else -> "undeclared"
        }

    companion object {
        val NONE = ExpectedNodes(emptySet(), emptySet())
    }
}

/**
 * ADR-035 §14 (a), the positive inventory: `expected == healthy storage-v1
 * inventory`, where healthy means capability `storage-v1`, not cleanly shut
 * down, and a heartbeat within [staleAfter]. A missing declared node, a
 * stale heartbeat, a wrong capability and an extra undeclared node are each
 * named.
 */
object NodeInventoryCheck {
    /** The same staleness the cleaner uses to declare a generation dead. */
    val DEFAULT_STALE_AFTER: Duration = Duration.ofMinutes(5)

    private const val GLOBAL_LOCKING = "GLOBAL"

    /** Not "api", so it always stops admission ([Problem.stopsAdmission]). */
    private const val LOCKING_ROLE = "admission-locking"

    /** One inventory problem, with the role of the node it concerns (`undeclared` for an extra node). */
    data class Problem(
        val role: String,
        val message: String,
    ) {
        /**
         * Whether admission must stop over it. An old or extra INGRESS instance,
         * or a node nobody declared, is the Phase 4 hazard ("no old ingress
         * instance may exist"). An absent API node is a cleanup participant
         * missing, which the API's own alerts cover: it raises the violation and
         * is observed, but does not turn away mail (TI-STORAGE-006 §22).
         */
        val stopsAdmission: Boolean get() = role != "api"
    }

    fun evaluate(
        expected: ExpectedNodes,
        rows: List<StorageNodeRow>,
        now: Instant,
        staleAfter: Duration = DEFAULT_STALE_AFTER,
        partitionedAllowed: Boolean = false,
    ): GateResult {
        if (!expected.declared) {
            return GateResult(ActivationGate.NODE_INVENTORY, GateVerdict.NOT_RUN, "no expected node inventory is declared")
        }
        val problems = problems(expected, rows, now, staleAfter, partitionedAllowed)
        return if (problems.isEmpty()) {
            GateResult(
                ActivationGate.NODE_INVENTORY,
                GateVerdict.PASS,
                "${expected.api.size} api + ${expected.ingestion.size} ingestion node(s) declared, every one healthy with ${StorageProtocol.CAPABILITY}",
            )
        } else {
            GateResult(ActivationGate.NODE_INVENTORY, GateVerdict.BLOCKED, problems.joinToString("; ") { it.message })
        }
    }

    /**
     * [partitionedAllowed]: whether a node may run the share-partitioned T1
     * (PROPOSED ADR-035 amendment), true only in the `lab` environment. Anywhere
     * else a live node declaring it is an inventory problem that stops admission,
     * whatever its role: the startup refusal in `DeploymentSafety` can be bypassed
     * by a process this one cannot see, the database row cannot.
     */
    fun problems(
        expected: ExpectedNodes,
        rows: List<StorageNodeRow>,
        now: Instant,
        staleAfter: Duration = DEFAULT_STALE_AFTER,
        partitionedAllowed: Boolean = false,
    ): List<Problem> {
        val live = rows.filter { !it.cleanShutdown }
        val healthy =
            live
                .filter { it.capability == StorageProtocol.CAPABILITY && !it.heartbeatAt.isBefore(now.minus(staleAfter)) }
                .map { it.nodeId }
                .toSet()
        val problems = mutableListOf<Problem>()
        for (node in expected.all.sorted()) {
            if (node in healthy) continue
            val seen = live.filter { it.nodeId == node }
            val role = expected.roleOf(node)
            problems +=
                Problem(
                    role,
                    when {
                        seen.isEmpty() -> {
                            "declared $role node '$node' is absent from storage_node"
                        }

                        seen.none { it.capability == StorageProtocol.CAPABILITY } -> {
                            "declared $role node '$node' has capability '${seen.first().capability}', not '${StorageProtocol.CAPABILITY}'"
                        }

                        else -> {
                            "declared $role node '$node' has a stale heartbeat (older than ${staleAfter.toSeconds()}s)"
                        }
                    },
                )
        }
        for (node in (healthy - expected.all).sorted()) {
            problems += Problem("undeclared", "undeclared node '$node' is heartbeating with ${StorageProtocol.CAPABILITY}")
        }
        if (!partitionedAllowed) {
            live
                .filter { it.admissionLocking != GLOBAL_LOCKING && !it.heartbeatAt.isBefore(now.minus(staleAfter)) }
                .map { it.nodeId to it.admissionLocking }
                .distinct()
                .sortedBy { it.first }
                .forEach { (node, locking) ->
                    problems +=
                        Problem(
                            LOCKING_ROLE,
                            "node '$node' runs T1 with admission-locking $locking, a PROPOSED ADR-035 amendment permitted only in the lab",
                        )
                }
        }
        return problems
    }
}

/**
 * ADR-035 amendment D, the window before a cohort plan exists. Gate D's
 * evidence covers r × (N + L) events/s, N being the configured participant cap
 * (`testinbox.beta.max-participants`, at most 30). Once a plan exists the pool
 * bounds the population (every workspace holds a share, at most L legacy), but
 * the pool always holds the full thirty participant shares: it bounds the
 * population to N + L only while N is thirty. Before a plan, or with a lowered
 * N, every workspace in the database counts, so the population itself must stay
 * within `N + L`. A lowered N evicts nobody, so a cohort admitted before N was
 * lowered can exceed the bound, and then this check fails closed.
 *
 * A violation is a property of the whole deployment, never of a tenant: the
 * watch answers it like a broken node inventory, `451` for every event on the
 * node before any recipient resolves (I9). It never becomes a per-workspace
 * refusal.
 */
object WorkspaceEnvelopeCheck {
    private const val LOWERED_N_AFTER_PLAN = "the cohort pool's thirty shares do not bound a lowered N, "

    /** What a node that does not run `ALL`, or declares no envelope, reports. */
    val NOT_REQUIRED = GateResult(ActivationGate.WORKSPACE_ENVELOPE, GateVerdict.NOT_REQUIRED, "not ALL, or no envelope declared")

    fun evaluate(
        maxWorkspaces: Int,
        population: WorkspacePopulation,
        participants: Int = BetaCohortPolicy.MAX_PARTICIPANTS,
    ): GateResult =
        when {
            // The pool's thirty participant shares bound the population to N + L only when N is thirty.
            population.cohortPlanExists && participants >= BetaCohortPolicy.MAX_PARTICIPANTS -> {
                GateResult(
                    ActivationGate.WORKSPACE_ENVELOPE,
                    GateVerdict.NOT_REQUIRED,
                    "a cohort plan exists; the pool bounds the population (every workspace shared, at most L legacy shares)",
                )
            }

            population.workspaces > maxWorkspaces -> {
                GateResult(
                    ActivationGate.WORKSPACE_ENVELOPE,
                    GateVerdict.BLOCKED,
                    (if (population.cohortPlanExists) LOWERED_N_AFTER_PLAN else "no cohort plan exists ") +
                        "and the database holds more than $maxWorkspaces workspaces (counted " +
                        "${population.workspaces}); gate D covers at most $participants participant places + L under ALL " +
                        "(ADR-035 amendment D)",
                )
            }

            else -> {
                GateResult(
                    ActivationGate.WORKSPACE_ENVELOPE,
                    GateVerdict.PASS,
                    "no cohort plan yet; ${population.workspaces} workspace(s), within the envelope's $maxWorkspaces",
                )
            }
        }
}

/** The population bound a node under `ALL` re-checks ([WorkspaceEnvelopeCheck]). */
data class ActivationEnvelope(
    /** N + L. */
    val maxWorkspaces: Int,
    val population: WorkspacePopulationReader,
    /** N, the configured participant cap (`testinbox.beta.max-participants`). */
    val participants: Int = BetaCohortPolicy.MAX_PARTICIPANTS,
) {
    init {
        require(maxWorkspaces > 0) { "the workspace envelope must be positive" }
        require(participants in 1..BetaCohortPolicy.MAX_PARTICIPANTS) { "N must be in 1..${BetaCohortPolicy.MAX_PARTICIPANTS}" }
    }

    /** One more than the bound: enough to tell "over", never a scan of the population. */
    fun evaluate(): GateResult = WorkspaceEnvelopeCheck.evaluate(maxWorkspaces, population.read(maxWorkspaces + 1), participants)
}

/**
 * The in-process fail-closed guard for a non-OFF node whose activation
 * invariant is broken at run time (TI-STORAGE-006 §22). Infrastructure, never
 * capacity: while violated, the guarded path answers `451` before the slot
 * and before T1, exactly like an open breaker, and clears itself the moment
 * the re-check passes. It is deliberately NOT the admission latch, which
 * records a late object and needs an operator, and NOT the breaker, whose
 * half-open trial probes storage health and would reopen ingestion on a
 * healthy bucket while the inventory is still wrong.
 */
class ActivationGuard {
    @Volatile private var violation: String? = null

    /** The current violation, or null while the invariant holds (or is only observed). */
    fun violated(): String? = violation

    fun set(detail: String) {
        violation = detail
    }

    fun clear() {
        violation = null
    }
}

/**
 * Re-runs the allowlist and inventory checks (ADR-035 §14 Phase 4: "every
 * node re-runs the allowlist and inventory checks on each cleanup pass, and
 * raises `activation_violation` on any mismatch").
 *
 * - OFF (Phase 2): the two gates are OBSERVED through the per-gate metric,
 *   the violation gauge stays 0 and nothing refuses: old and new binaries
 *   are allowed to overlap in this phase (TI-STORAGE-006 §23).
 * - TENANT_LIMITS / ALL: a mismatch raises the gauge to 1, logs the
 *   identities, and sets [ActivationGuard] so this node fails closed with
 *   `451` until a later pass finds the invariant restored.
 */
class ActivationWatch(
    private val inventory: ActivationInventory,
    private val expected: ExpectedNodes,
    private val enforcement: StorageEnforcement,
    private val guard: ActivationGuard,
    private val metrics: StorageProtocolMetrics = StorageProtocolMetrics.NOOP,
    private val staleAfter: Duration = NodeInventoryCheck.DEFAULT_STALE_AFTER,
    /** Only the `lab` environment may run the share-partitioned T1 ([NodeInventoryCheck.problems]). */
    private val partitionedAllowed: Boolean = false,
    /** ADR-035 amendment D: the pre-plan population bound, re-checked under `ALL` only ([WorkspaceEnvelopeCheck]). */
    private val envelope: ActivationEnvelope? = null,
) {
    data class Outcome(
        val sessions: GateResult,
        val nodes: GateResult,
        /** Set when the evaluation itself could not complete (the inventory read threw); the gates are then unknown. */
        val evaluationFailure: String? = null,
        val envelope: GateResult = WorkspaceEnvelopeCheck.NOT_REQUIRED,
    ) {
        val envelopeHolds: Boolean get() = envelope.verdict == GateVerdict.PASS || envelope.verdict == GateVerdict.NOT_REQUIRED

        val holds: Boolean
            get() = evaluationFailure == null && sessions.verdict == GateVerdict.PASS && nodes.verdict == GateVerdict.PASS && envelopeHolds
    }

    /** Whether this node re-checks the envelope: only under `ALL`, where gate D's evidence is what admits. */
    private val checksEnvelope: Boolean get() = envelope != null && enforcement == StorageEnforcement.ALL

    /**
     * Never throws. THE rule for an evaluation that cannot complete (a database
     * read failing, a malformed row): under OFF it is observed; under
     * TENANT_LIMITS or ALL a node that cannot PROVE the activation invariant
     * must not admit as though it held, so the guard is set and the node
     * answers 451 until a later evaluation completes and passes its
     * admission-relevant gates (TI-STORAGE-006b P1). An evaluation failure
     * is never a tenant refusal. Every [Exception] is caught (a checked one
     * leaking from an adapter included); an [Error] is not — that is a dying
     * JVM, not an inventory the node could not read.
     */
    fun run(): Outcome =
        try {
            evaluate()
        } catch (e: Exception) {
            unavailable(e)
        }

    private fun unavailable(cause: Exception): Outcome {
        val detail = "activation check could not be evaluated: ${cause.javaClass.simpleName}: ${cause.message}"
        val unknown = GateVerdict.NOT_RUN
        val outcome =
            Outcome(
                GateResult(ActivationGate.SESSION_ALLOWLIST, unknown, detail),
                GateResult(ActivationGate.NODE_INVENTORY, unknown, detail),
                evaluationFailure = detail,
                envelope =
                    if (checksEnvelope) {
                        GateResult(
                            ActivationGate.WORKSPACE_ENVELOPE,
                            unknown,
                            detail,
                        )
                    } else {
                        WorkspaceEnvelopeCheck.NOT_REQUIRED
                    },
            )
        metrics.activationGate(ActivationGate.SESSION_ALLOWLIST, false)
        metrics.activationGate(ActivationGate.NODE_INVENTORY, false)
        metrics.activationGate(ActivationGate.WORKSPACE_ENVELOPE, !checksEnvelope)
        if (enforcement == StorageEnforcement.OFF) {
            metrics.activationViolation(false)
            guard.clear()
            log.warn("storage_activation_check_failed (enforcement OFF, observing): {}", detail)
        } else {
            guard.set(detail)
            metrics.activationViolation(true)
            log.error(
                "storage_activation_violation enforcement {}: the barrier cannot be proven; this node answers 451: {}",
                enforcement,
                detail,
            )
        }
        return outcome
    }

    private fun evaluate(): Outcome {
        val sessions = SessionAllowlist.evaluate(inventory.sessions(), inventory.applicationRole())
        val rows = inventory.nodes()
        val now = inventory.now()
        val nodes = NodeInventoryCheck.evaluate(expected, rows, now, staleAfter, partitionedAllowed)
        val envelopeResult = envelope?.takeIf { checksEnvelope }?.evaluate() ?: WorkspaceEnvelopeCheck.NOT_REQUIRED
        val outcome = Outcome(sessions, nodes, envelope = envelopeResult)
        metrics.activationGate(ActivationGate.SESSION_ALLOWLIST, sessions.verdict == GateVerdict.PASS)
        metrics.activationGate(ActivationGate.NODE_INVENTORY, nodes.verdict == GateVerdict.PASS)
        metrics.activationGate(ActivationGate.WORKSPACE_ENVELOPE, outcome.envelopeHolds)
        if (enforcement == StorageEnforcement.OFF) {
            // Observed, never refused: Phase 2 permits the overlap this would see.
            metrics.activationViolation(false)
            guard.clear()
            if (!outcome.holds) log.info("storage_activation_not_ready (enforcement OFF, observing): {}", describe(outcome))
            return outcome
        }
        if (outcome.holds) {
            if (guard.violated() != null) log.warn("storage_activation_restored the activation invariant holds again")
            guard.clear()
            metrics.activationViolation(false)
            return outcome
        }
        val detail = describe(outcome)
        metrics.activationViolation(true)
        // Which mismatches turn mail away: a session outside the allowlist (an old
        // binary), an ingestion node missing/stale/wrong, or a node nobody
        // declared. An absent API node is raised and logged, never a 451.
        // An envelope breach is a deployment-wide fact (I9): every event on this node gets the same 451.
        val stopsAdmission =
            !outcome.envelopeHolds ||
                sessions.verdict != GateVerdict.PASS ||
                nodes.verdict == GateVerdict.NOT_RUN ||
                NodeInventoryCheck.problems(expected, rows, now, staleAfter, partitionedAllowed).any { it.stopsAdmission }
        if (stopsAdmission) {
            guard.set(detail)
            log.error("storage_activation_violation enforcement {} with a broken barrier; this node answers 451: {}", enforcement, detail)
        } else {
            if (guard.violated() != null) log.warn("storage_activation_restored admission-relevant gates hold again")
            guard.clear()
            log.error(
                "storage_activation_violation enforcement {}; an api node is missing from the inventory (observed, mail still admitted): {}",
                enforcement,
                detail,
            )
        }
        return outcome
    }

    private fun describe(outcome: Outcome): String =
        listOf(outcome.sessions, outcome.nodes, outcome.envelope)
            .filter { it.verdict != GateVerdict.PASS && it.verdict != GateVerdict.NOT_REQUIRED }
            .joinToString("; ") { "${it.gate.name.lowercase()}: ${it.detail}" }

    private companion object {
        val log = LoggerFactory.getLogger(ActivationWatch::class.java)
    }
}
