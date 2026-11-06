package email.testinbox.application.deployment

import email.testinbox.application.storage.CohortStorageRuntime
import email.testinbox.application.storage.StorageDeclarations
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
 *
 * `N` is never counted from the database at startup, which a starting process
 * cannot do reliably. It is `30 + L`: the beta's participant places are a code
 * fact ([BetaCohortPolicy.MAX_PARTICIPANTS], enforced by the admission
 * sequence), and `L` is the declared legacy-share count. While a cohort pool is
 * required, every workspace must hold a share, and a pool holding more legacy
 * shares than `L` is never approved ([CohortStorageRuntime.assess]), so the
 * declaration cannot understate the real population without failing closed.
 *
 * These are INCOMING recipient-copy limits. TestInbox sends no mail on behalf
 * of anyone (`docs/dev/beta-limits.md`).
 */
object IngestEnvelopeSafety {
    /** The per-workspace burst `b` the owner approved for the closed beta (2026-11-06). Gate D does not measure bursts. */
    const val APPROVED_WORKSPACE_BURST: Long = 60

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

    private fun envelope(settings: DeploymentSettings): List<DeploymentViolation> =
        buildList {
            val storage = settings.storage
            val gated = storage.gatedExpectedRate
            if (gated == null) {
                add(
                    DeploymentViolation(
                        GATED_RATE,
                        "is not declared; ALL needs R_gated, the §11 expected rate of the passing gate D evidence " +
                            "(ADR-035 amendment D, 2026-11-06). There is no default: set it from the evidence",
                    ),
                )
            }
            if (storage.declaredLegacyWorkspaces == null) {
                add(
                    DeploymentViolation(
                        LEGACY,
                        "is not declared; under ALL the mail-receiving workspaces are the ${BetaCohortPolicy.MAX_PARTICIPANTS} " +
                            "participant places plus L legacy-share workspaces, and L must be stated (ADR-035 amendment D)",
                    ),
                )
            }
            val workspace = settings.ingest
            if (workspace.capacity > APPROVED_WORKSPACE_BURST) {
                add(
                    DeploymentViolation(
                        WORKSPACE_CAPACITY,
                        "is ${workspace.capacity} but the owner-approved per-workspace INGEST burst b is $APPROVED_WORKSPACE_BURST " +
                            "copies (ADR-035 amendment D); gate D does not measure bursts, so a larger b is a new owner decision",
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
                return@buildList
            }
            if (!workspace.refillPerSecond.isFinite()) {
                add(DeploymentViolation(WORKSPACE_REFILL, "is ${workspace.refillPerSecond}; an unbounded refill fits no gated rate"))
                return@buildList
            }
            val population = mailReceivingWorkspaces(storage)
            if (gated == null || population == null || !gated.isFinite() || gated <= 0.0) return@buildList
            // A non-positive refill is refused by RatePolicy when the limiter is built; it admits nothing here.
            if (workspace.refillPerSecond <= 0.0) return@buildList
            val admitted = BigDecimal(workspace.refillPerSecond.toString()).multiply(BigDecimal(population))
            if (admitted > BigDecimal(gated.toString())) {
                add(
                    DeploymentViolation(
                        WORKSPACE_REFILL,
                        "is ${workspace.refillPerSecond} copies/s, so the INGEST limits admit r × N = " +
                            "${workspace.refillPerSecond} × $population (${BetaCohortPolicy.MAX_PARTICIPANTS} participant places + " +
                            "${storage.declaredLegacyWorkspaces} legacy) = ${admitted.stripTrailingZeros().toPlainString()} events/s, " +
                            "above R_gated $gated ($GATED_RATE). Lower r, or run gate D at the new R first (ADR-035 amendment D)",
                    ),
                )
            }
        }
}
