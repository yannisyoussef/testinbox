package email.testinbox.application.deployment

import email.testinbox.application.port.WorkspacePopulationReader
import email.testinbox.application.storage.CohortStorageRuntime
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.storage.activation.ActivationEnvelope
import email.testinbox.domain.identity.BetaCohortPolicy
import email.testinbox.domain.identity.BetaParticipantCap
import email.testinbox.domain.storage.StorageEnforcement
import java.math.BigDecimal

/**
 * One closed-beta `INGEST` workload envelope the OWNER approved (ADR-035
 * amendment D): the per-workspace refill r and burst b, the participant places
 * N and the legacy workspaces L, hence R = r × (N + L), and the ingestion
 * gateway's database pool size the passing evidence covers.
 *
 * Entries exist only in [IngestEnvelopeSafety.APPROVED_ENVELOPES]. Adding one
 * is an owner decision, a passing gate D at its R, and then that one line.
 */
data class ApprovedIngestEnvelope(
    /** N: the participant places this envelope counts (`testinbox.beta.max-participants`). */
    val participants: Int,
    /** r: per-workspace INGEST refill, incoming recipient copies per second. */
    val refill: Double,
    /** b: per-workspace INGEST burst, copies. */
    val burst: Long,
    /** L: legacy-share workspaces beside the N participant places. */
    val legacy: Int,
    /**
     * The ingestion gateway's Hikari `maximumPoolSize` the passing evidence for
     * this envelope covers, or null while no pool size is approved: then `ALL`
     * is refused on the gateway.
     */
    val ingestionPoolSize: Int?,
    /** Where the owner's decision is recorded. */
    val approval: String,
) {
    init {
        require(participants in 1..BetaCohortPolicy.MAX_PARTICIPANTS) { "N must be in 1..${BetaCohortPolicy.MAX_PARTICIPANTS}" }
        require(refill.isFinite() && refill > 0.0 && burst > 0 && legacy >= 0) { "r, b must be positive and L non-negative" }
        require(ingestionPoolSize == null || ingestionPoolSize > 0) { "an approved pool size is positive" }
    }

    /** R = r × (N + L), in exact decimal: the expected rate gate D runs at, and the only R_gated `ALL` accepts for N. */
    val gatedRate: BigDecimal get() = BigDecimal(refill.toString()).multiply(BigDecimal(participants + legacy))

    /** "1.0 × (30 + 1) = 31.0", the form every refusal names. */
    val formula: String get() = "$refill × ($participants + $legacy) = $gatedRate"
}

/**
 * The closed-beta `INGEST` workload envelope (ADR-035 §11, amendment D,
 * accepted by the owner on 2026-11-06).
 *
 * `INGEST` is charged per resolved recipient copy, per inbox and then per
 * workspace, and every admitted event charges at least one copy. So the
 * highest sustained T1 event rate the configured limits admit is
 * `r × W`, where `r` is the per-workspace refill and `W` the number of
 * workspaces that can receive mail. Gate D measures T1 at a rate R and 2R;
 * the evidence only covers a deployment whose `r × W` does not exceed that R.
 * Before a cohort plan exists the population is held to `N + L` at run time
 * by the activation watch ([email.testinbox.application.storage.activation.WorkspaceEnvelopeCheck]).
 *
 * `W` is never counted from the database at startup, which a starting process
 * cannot do reliably. It is `N + L`: N is the configured participant cap
 * (`testinbox.beta.max-participants`, at most [BetaCohortPolicy.MAX_PARTICIPANTS],
 * enforced by the admission sequence), and `L` is the declared legacy-share
 * count. While a cohort pool is required, every workspace must hold a share,
 * and a pool holding more legacy shares than `L` is never approved
 * ([CohortStorageRuntime.assess]), so the declaration cannot understate the real
 * population without failing closed.
 *
 * Under `ALL` the configured N selects an entry of [APPROVED_ENVELOPES]; there
 * is none for an N the owner has not approved, and `ALL` is refused. Within an
 * entry, r, b and L may be LOWERED (it only lowers load) but never raised, and
 * R_gated must equal the entry's R exactly. The gateway's pool size must equal
 * the entry's. None of it can grow by configuration: it is an owner decision
 * plus a new passing gate D, and a code change to [APPROVED_ENVELOPES].
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

    /** R = r × (30 + L) = 1 × 31: amendment D's expected rate, the R of its N = 30 entry. */
    const val APPROVED_GATED_RATE: Double = 31.0

    /**
     * Every owner-approved envelope, and the ONLY place one is recorded.
     *
     * Amendment D (2026-11-06) approved r = 1, b = 60, L = 1 with the thirty
     * participant places, hence R = 31. It names no database pool size, and no
     * committed gate D evidence was measured at the gateway's pool: the harness
     * sizes each scenario's pool c + 6 (`docs/dev/storage-benchmark.md`), not the
     * gateway's 8. So its pool size is UNAPPROVED (null) and `ALL` is refused on
     * the gateway until the owner approves one.
     *
     * A smaller first cohort (for example N = 10, R = 1 × (10 + 1) = 11) is NOT
     * here: it needs the owner's decision first, then one line below.
     */
    val APPROVED_ENVELOPES: List<ApprovedIngestEnvelope> =
        listOf(
            ApprovedIngestEnvelope(
                participants = BetaCohortPolicy.MAX_PARTICIPANTS,
                refill = APPROVED_WORKSPACE_REFILL,
                burst = APPROVED_WORKSPACE_BURST,
                legacy = APPROVED_LEGACY_WORKSPACES,
                ingestionPoolSize = null,
                approval = "ADR-035 amendment D, owner decision 2026-11-06 (#124 comment 6676738358)",
            ),
        )

    const val GATED_RATE = "testinbox.storage.gated-expected-rate"
    const val LEGACY = "testinbox.storage.declared-legacy-workspaces"
    const val PARTICIPANTS = BetaParticipantCap.PROPERTY
    const val POOL_SIZE = "spring.datasource.hikari.maximum-pool-size"
    const val WORKSPACE_CAPACITY = "testinbox.limits.ingest.capacity"
    const val WORKSPACE_REFILL = "testinbox.limits.ingest.refill-per-second"
    const val INBOX_CAPACITY = "testinbox.limits.ingest-per-inbox.capacity"
    const val INBOX_REFILL = "testinbox.limits.ingest-per-inbox.refill-per-second"

    fun check(
        settings: DeploymentSettings,
        approved: List<ApprovedIngestEnvelope> = APPROVED_ENVELOPES,
    ): List<DeploymentViolation> =
        perInboxWithinWorkspace(settings) + sanity(settings.storage) +
            if (settings.storage.enforcement == StorageEnforcement.ALL) envelope(settings, approved) else emptyList()

    /** The owner-approved envelope for the configured N, or null: then `ALL` is refused. */
    fun approvedFor(
        cap: BetaParticipantCap,
        approved: List<ApprovedIngestEnvelope> = APPROVED_ENVELOPES,
    ): ApprovedIngestEnvelope? = approved.singleOrNull { it.participants == cap.value }

    /** R_gated derived from the configured N: the approved entry's r × (N + L), or null while the owner has approved none. */
    fun expectedGatedRate(
        storage: StorageDeclarations,
        approved: List<ApprovedIngestEnvelope> = APPROVED_ENVELOPES,
    ): BigDecimal? = approvedFor(storage.participantCap, approved)?.gatedRate

    /** The workspace population the envelope is computed for, N + L, or null while `L` is undeclared or malformed. */
    fun mailReceivingWorkspaces(storage: StorageDeclarations): Int? =
        storage.declaredLegacyWorkspaces
            ?.takeIf { it in 0..CohortStorageRuntime.MAX_LEGACY_WORKSPACES }
            ?.let { storage.participantCap.value + it }

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
            ?.let { ActivationEnvelope(it, population, storage.participantCap.value) }

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
     * The amendment's inequality r × (N + L) ≤ R_gated holds by construction under
     * these pins: N selects an approved entry, r and L are at most the entry's and
     * R_gated equals the entry's r × (N + L) exactly (pinned by a test).
     */
    private fun envelope(
        settings: DeploymentSettings,
        approved: List<ApprovedIngestEnvelope>,
    ): List<DeploymentViolation> {
        val entry = approvedFor(settings.storage.participantCap, approved)
        return participants(settings, entry, approved) + declarations(settings.storage, entry) + limits(settings, entry) +
            pool(settings, entry)
    }

    /** N: the configured cap must be one the owner approved an envelope for. Fail closed otherwise. */
    private fun participants(
        settings: DeploymentSettings,
        entry: ApprovedIngestEnvelope?,
        approved: List<ApprovedIngestEnvelope>,
    ): List<DeploymentViolation> {
        if (entry != null) return emptyList()
        val n = settings.storage.participantCap.value
        val declaredLegacy = settings.storage.declaredLegacyWorkspaces
        val r = settings.ingest.refillPerSecond
        val derived =
            declaredLegacy
                ?.takeIf { r.isFinite() }
                ?.let { " = ${BigDecimal(r.toString()).multiply(BigDecimal(n + it))}" }
                .orEmpty()
        val known = approved.joinToString("; ") { "N = ${it.participants}: R = ${it.formula} (${it.approval})" }.ifEmpty { "none" }
        return listOf(
            DeploymentViolation(
                PARTICIPANTS,
                "is $n but no owner-approved INGEST envelope covers N = $n participant places, so R_gated = r × (N + L) = " +
                    "$r × ($n + ${declaredLegacy ?: "L"})$derived events/s has no owner approval and no passing gate D " +
                    "evidence. Approved: $known. ALL with this N needs the owner's decision first, then its entry in " +
                    "IngestEnvelopeSafety.APPROVED_ENVELOPES",
            ),
        )
    }

    /** R_gated and L: declared, and exactly what the owner approved for N (R equal; L at most the entry's). */
    private fun declarations(
        storage: StorageDeclarations,
        entry: ApprovedIngestEnvelope?,
    ): List<DeploymentViolation> =
        buildList {
            val gated = storage.gatedExpectedRate
            if (gated == null) {
                add(
                    DeploymentViolation(
                        GATED_RATE,
                        "is not declared; ALL needs R_gated, the §11 expected rate of the passing gate D evidence " +
                            "(ADR-035 amendment D, 2026-11-06). There is no default: set it from the evidence " +
                            "(${entry?.gatedRate ?: "no owner-approved R for N = ${storage.participantCap.value}"})",
                    ),
                )
            } else if (entry != null && wellFormed(gated) && !isRate(gated, entry.gatedRate)) {
                add(
                    DeploymentViolation(
                        GATED_RATE,
                        "is $gated but the owner approved R = ${entry.formula} events/s, and gate D evidence counts only at " +
                            "R_gated; $CHANGE",
                    ),
                )
            }
            val legacy = storage.declaredLegacyWorkspaces
            if (legacy == null) {
                add(
                    DeploymentViolation(
                        LEGACY,
                        "is not declared; under ALL the mail-receiving workspaces are the ${storage.participantCap.value} " +
                            "participant places plus L legacy-share workspaces, and L must be stated (ADR-035 amendment D)",
                    ),
                )
            } else if (entry != null && legacy in (entry.legacy + 1)..CohortStorageRuntime.MAX_LEGACY_WORKSPACES) {
                add(
                    DeploymentViolation(
                        LEGACY,
                        "is $legacy but the owner approved at most ${entry.legacy} legacy workspace beside the " +
                            "${entry.participants} participants and did not authorize a broader cohort; $CHANGE",
                    ),
                )
            }
        }

    /** The per-workspace budget: enforced, finite, and within the approved r and b. */
    private fun limits(
        settings: DeploymentSettings,
        entry: ApprovedIngestEnvelope?,
    ): List<DeploymentViolation> =
        buildList {
            val workspace = settings.ingest
            if (entry != null && workspace.capacity > entry.burst) {
                add(
                    DeploymentViolation(
                        WORKSPACE_CAPACITY,
                        "is ${workspace.capacity} but the owner-approved per-workspace INGEST burst b is ${entry.burst} " +
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
            } else if (entry != null && workspace.refillPerSecond > entry.refill) {
                add(
                    DeploymentViolation(
                        WORKSPACE_REFILL,
                        "is ${workspace.refillPerSecond} copies/s but the owner-approved per-workspace INGEST refill r is " +
                            "${entry.refill} copy/s; $CHANGE",
                    ),
                )
            }
        }

    /**
     * The gateway's database pool: T1's latency under `ALL` is measured at a
     * pool size, so the running pool must be the one the evidence covers.
     * Only the gateway reports one (the API runs no SMTP T1); an unreadable
     * size, or an envelope whose pool size is unapproved, fails closed.
     */
    private fun pool(
        settings: DeploymentSettings,
        entry: ApprovedIngestEnvelope?,
    ): List<DeploymentViolation> {
        val observed = settings.ingestionPoolSize
        // Not the gateway; or N itself is already refused.
        if (observed == null || entry == null) return emptyList()
        val approvedSize = entry.ingestionPoolSize
        val problem =
            when {
                approvedSize == null -> {
                    "is ${describe(observed)} but the owner has approved no ingestion database pool size for the envelope " +
                        "N = ${entry.participants}, R = ${entry.formula}: T1's latency under ALL depends on it, so ALL stays " +
                        "refused on the ingestion gateway until the owner approves one in IngestEnvelopeSafety.APPROVED_ENVELOPES"
                }

                observed is IngestionPoolSize.Unreadable -> {
                    "could not be determined (${observed.reason}); ALL needs the gateway's effective Hikari maximumPoolSize " +
                        "to equal $approvedSize, the pool the approved envelope's evidence covers"
                }

                observed is IngestionPoolSize.Known && observed.size != approvedSize -> {
                    "is ${observed.size} but the approved envelope's evidence covers an ingestion pool of exactly " +
                        "$approvedSize connections (TESTINBOX_DB_POOL_SIZE); $CHANGE"
                }

                else -> {
                    null
                }
            }
        return listOfNotNull(problem?.let { DeploymentViolation(POOL_SIZE, it) })
    }

    private fun describe(observed: IngestionPoolSize): String =
        when (observed) {
            is IngestionPoolSize.Known -> observed.size.toString()
            is IngestionPoolSize.Unreadable -> "undetermined (${observed.reason})"
        }

    /** A rate [sanity] does not already refuse. */
    private fun wellFormed(rate: Double): Boolean = rate.isFinite() && rate > 0.0

    /** Exact decimal comparison: 31 and 31.0 are the approved rate; 30.999 is not. */
    private fun isRate(
        rate: Double,
        approved: BigDecimal,
    ): Boolean = BigDecimal(rate.toString()).compareTo(approved) == 0
}
