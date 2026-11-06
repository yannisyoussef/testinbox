package email.testinbox.application.deployment

import email.testinbox.application.port.WorkspacePopulationReader
import email.testinbox.application.storage.CohortStorageRuntime
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.storage.activation.ActivationEnvelope
import email.testinbox.domain.identity.BetaCohortPolicy
import email.testinbox.domain.storage.StorageEnforcement
import java.math.BigDecimal

/**
 * The closed-beta `INGEST` workload envelope (ADR-035 §11, amendment D,
 * accepted by the owner on 2026-11-06).
 *
 * `INGEST` is charged per resolved recipient copy, per inbox and then per
 * workspace, and every admitted event charges at least one copy. So the
 * highest sustained T1 event rate the configured limits admit is
 * `r × N`, where `r` is the per-workspace refill and `N` the number of
 * workspaces that can receive mail. Gate D measures T1 at a rate R and 2R;
 * the evidence only covers a deployment whose `r × N` does not exceed that R.
 * Before a cohort plan exists the population is held to `30 + L` at run time
 * by the activation watch ([email.testinbox.application.storage.activation.WorkspaceEnvelopeCheck]).
 *
 * `N` is never counted from the database at startup, which a starting process
 * cannot do reliably. It is `30 + L`: the beta's participant places are a code
 * fact ([BetaCohortPolicy.MAX_PARTICIPANTS], enforced by the admission
 * sequence), and `L` is the declared legacy-share count. While a cohort pool is
 * required, every workspace must hold a share, and a pool holding more legacy
 * shares than `L` is never approved ([CohortStorageRuntime.assess]), so the
 * declaration cannot understate the real population without failing closed.
 *
 * The owner approved exactly r = 1, b = 60 and L = 1, hence R = 31, and
 * authorized no broader cohort. So under `ALL` the code pins them: r ≤ 1,
 * b ≤ 60, L ≤ 1 and R_gated = 31. Lowering r, b or L stays possible (it only
 * lowers load); raising any of them, or R_gated, cannot happen by
 * configuration. It is an owner decision plus a new passing gate D, and a
 * code change here.
 *
 * These are INCOMING recipient-copy limits. TestInbox sends no mail on behalf
 * of anyone (`docs/dev/beta-limits.md`).
 */
object IngestEnvelopeSafety {
    /** The per-workspace burst `b` the owner approved for the closed beta (2026-11-06). Gate D does not measure bursts. */
    const val APPROVED_WORKSPACE_BURST: Long = 60

    /** The per-workspace refill `r` the owner approved, in incoming recipient copies per second. */
    const val APPROVED_WORKSPACE_REFILL: Double = 1.0

    /** The legacy workspaces `L` the owner approved beside the thirty participant places ("one legacy workspace"). */
    const val APPROVED_LEGACY_WORKSPACES: Int = 1

    /** R = r × (30 + L) = 1 × 31: the expected rate gate D runs at, and the only R_gated `ALL` accepts. */
    const val APPROVED_GATED_RATE: Double = 31.0

    const val GATED_RATE = "testinbox.storage.gated-expected-rate"
    const val LEGACY = "testinbox.storage.declared-legacy-workspaces"
    const val WORKSPACE_CAPACITY = "testinbox.limits.ingest.capacity"
    const val WORKSPACE_REFILL = "testinbox.limits.ingest.refill-per-second"
    const val INBOX_CAPACITY = "testinbox.limits.ingest-per-inbox.capacity"
    const val INBOX_REFILL = "testinbox.limits.ingest-per-inbox.refill-per-second"

    fun check(settings: DeploymentSettings): List<DeploymentViolation> =
        perInboxWithinWorkspace(settings) + sanity(settings.storage) +
            if (settings.storage.enforcement == StorageEnforcement.ALL) envelope(settings) else emptyList()

    /** The workspace population the envelope is computed for, or null while `L` is undeclared or malformed. */
    fun mailReceivingWorkspaces(storage: StorageDeclarations): Int? =
        storage.declaredLegacyWorkspaces
            ?.takeIf { it in 0..CohortStorageRuntime.MAX_LEGACY_WORKSPACES }
            ?.let { BetaCohortPolicy.MAX_PARTICIPANTS + it }

    /**
     * The pre-plan population bound a node re-checks under `ALL`, or null where
     * it does not apply (another mode, or L undeclared: then startup refuses `ALL`).
     */
    fun activationEnvelope(
        storage: StorageDeclarations,
        population: WorkspacePopulationReader,
    ): ActivationEnvelope? =
        mailReceivingWorkspaces(storage)
            ?.takeIf { storage.enforcement == StorageEnforcement.ALL }
            ?.let { ActivationEnvelope(it, population) }

    /**
     * Every mode, every deployed environment: the per-inbox budget exists so one
     * flooded address cannot drain its whole workspace (ADR-027 §4). A per-inbox
     * value above the workspace's would buy nothing. `LimitsConfig` refuses it too;
     * this names it in the aggregated startup report.
     */
    private fun perInboxWithinWorkspace(settings: DeploymentSettings): List<DeploymentViolation> =
        buildList {
            val workspace = settings.ingest
            val inbox = settings.ingestPerInbox
            if (inbox.capacity > workspace.capacity) {
                add(
                    DeploymentViolation(
                        INBOX_CAPACITY,
                        "is ${inbox.capacity} but the per-workspace INGEST capacity is ${workspace.capacity}; a per-inbox " +
                            "budget above its workspace's cannot keep one flooded address from draining the workspace",
                    ),
                )
            }
            if (inbox.refillPerSecond > workspace.refillPerSecond) {
                add(
                    DeploymentViolation(
                        INBOX_REFILL,
                        "is ${inbox.refillPerSecond} but the per-workspace INGEST refill is ${workspace.refillPerSecond}; " +
                            "a per-inbox budget above its workspace's cannot keep one flooded address from draining the workspace",
                    ),
                )
            }
        }

    /** Declared values must be well-formed in any mode; absence is fine outside `ALL`. */
    private fun sanity(storage: StorageDeclarations): List<DeploymentViolation> =
        buildList {
            storage.gatedExpectedRate?.let {
                if (!it.isFinite() ||
                    it <= 0.0
                ) {
                    add(DeploymentViolation(GATED_RATE, "is $it; R_gated must be a positive number of events/s"))
                }
            }
            storage.declaredLegacyWorkspaces?.let {
                if (it !in 0..CohortStorageRuntime.MAX_LEGACY_WORKSPACES) {
                    add(
                        DeploymentViolation(
                            LEGACY,
                            "is $it; must be in 0..${CohortStorageRuntime.MAX_LEGACY_WORKSPACES}, the legacy shares a cohort pool can hold",
                        ),
                    )
                }
            }
        }

    /** Change requires an owner decision and a new passing gate D; never configuration. */
    private const val CHANGE = "changing it is an owner decision and needs a new passing gate D first (ADR-035 amendment D)"

    /**
     * The amendment's inequality r × (30 + L) ≤ R_gated holds by construction under
     * these pins: r ≤ 1, L ≤ 1 and R_gated = 1 × (30 + 1) exactly (pinned by a test).
     */
    private fun envelope(settings: DeploymentSettings): List<DeploymentViolation> = declarations(settings.storage) + limits(settings)

    /** R_gated and L: declared, and exactly what the owner approved (R = 31; L at most 1). */
    private fun declarations(storage: StorageDeclarations): List<DeploymentViolation> =
        buildList {
            val gated = storage.gatedExpectedRate
            if (gated == null) {
                add(
                    DeploymentViolation(
                        GATED_RATE,
                        "is not declared; ALL needs R_gated, the §11 expected rate of the passing gate D evidence " +
                            "(ADR-035 amendment D, 2026-11-06). There is no default: set it from the evidence ($APPROVED_GATED_RATE)",
                    ),
                )
            } else if (gated.isFinite() && gated > 0.0 && !isApprovedRate(gated)) {
                add(
                    DeploymentViolation(
                        GATED_RATE,
                        "is $gated but the owner approved R = $APPROVED_WORKSPACE_REFILL × (${BetaCohortPolicy.MAX_PARTICIPANTS} + " +
                            "$APPROVED_LEGACY_WORKSPACES) = $APPROVED_GATED_RATE events/s, and gate D evidence counts only at R_gated; $CHANGE",
                    ),
                )
            }
            val legacy = storage.declaredLegacyWorkspaces
            if (legacy == null) {
                add(
                    DeploymentViolation(
                        LEGACY,
                        "is not declared; under ALL the mail-receiving workspaces are the ${BetaCohortPolicy.MAX_PARTICIPANTS} " +
                            "participant places plus L legacy-share workspaces, and L must be stated (ADR-035 amendment D)",
                    ),
                )
            } else if (legacy in (APPROVED_LEGACY_WORKSPACES + 1)..CohortStorageRuntime.MAX_LEGACY_WORKSPACES) {
                add(
                    DeploymentViolation(
                        LEGACY,
                        "is $legacy but the owner approved at most $APPROVED_LEGACY_WORKSPACES legacy workspace beside the " +
                            "${BetaCohortPolicy.MAX_PARTICIPANTS} participants and did not authorize a broader cohort; $CHANGE",
                    ),
                )
            }
        }

    /** The per-workspace budget: enforced, finite, and within the approved r and b. */
    private fun limits(settings: DeploymentSettings): List<DeploymentViolation> =
        buildList {
            val workspace = settings.ingest
            if (workspace.capacity > APPROVED_WORKSPACE_BURST) {
                add(
                    DeploymentViolation(
                        WORKSPACE_CAPACITY,
                        "is ${workspace.capacity} but the owner-approved per-workspace INGEST burst b is $APPROVED_WORKSPACE_BURST " +
                            "copies; gate D does not measure bursts, and $CHANGE",
                    ),
                )
            }
            if (!settings.limitsEnabled) {
                add(
                    DeploymentViolation(
                        WORKSPACE_REFILL,
                        "is not enforced (testinbox.limits.enabled=false), so the INGEST rate under ALL is unbounded and " +
                            "no passing gate D evidence covers it (ADR-035 amendment D)",
                    ),
                )
            } else if (!workspace.refillPerSecond.isFinite()) {
                add(DeploymentViolation(WORKSPACE_REFILL, "is ${workspace.refillPerSecond}; an unbounded refill fits no gated rate"))
            } else if (workspace.refillPerSecond > APPROVED_WORKSPACE_REFILL) {
                add(
                    DeploymentViolation(
                        WORKSPACE_REFILL,
                        "is ${workspace.refillPerSecond} copies/s but the owner-approved per-workspace INGEST refill r is " +
                            "$APPROVED_WORKSPACE_REFILL copy/s; $CHANGE",
                    ),
                )
            }
        }

    /** Exact decimal comparison: 31 and 31.0 are the approved rate; 30.999 is not. */
    private fun isApprovedRate(rate: Double): Boolean =
        BigDecimal(rate.toString()).compareTo(BigDecimal(APPROVED_GATED_RATE.toString())) == 0
}
