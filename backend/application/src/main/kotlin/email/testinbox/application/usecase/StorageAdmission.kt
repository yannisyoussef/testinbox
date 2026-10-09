package email.testinbox.application.usecase

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.StorageAccountingOverflowException
import email.testinbox.application.port.StorageAdmissionInputException
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageReservationDraft
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.storage.FootprintPolicy
import email.testinbox.application.storage.FootprintUnavailability
import email.testinbox.application.storage.StorageFootprintUnavailableException
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.storage.StorageScope
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * One recipient copy that reached T1: it resolved to an inbox and passed the
 * rate limits (ADR-035 §4, steps 1–3). Its footprint is not its own. Every
 * copy of an event costs the event's [StorageAdmissionRequest.bytesPerCopy].
 *
 * [objectKeys] are the exact keys the later write fence authorizes: `raw.eml`
 * first, then one `attachments/{id}` per extracted attachment, all under this
 * copy's own `{workspace}/{inbox}/{message}/` prefix (ADR-005, ADR-035 I7).
 */
data class StorageAdmissionCandidate(
    val messageId: MessageId,
    val workspaceId: WorkspaceId,
    val inboxId: InboxId,
    val objectKeys: List<String>,
) {
    init {
        require(objectKeys.firstOrNull() == ObjectKeys.raw(workspaceId, inboxId, messageId)) {
            "the first key must be this copy's own raw.eml"
        }
        val attachmentPrefix = ObjectKeys.raw(workspaceId, inboxId, messageId).removeSuffix("raw.eml") + "attachments/"
        objectKeys.drop(1).forEach { key ->
            require(key.startsWith(attachmentPrefix) && isUuid(key.removePrefix(attachmentPrefix))) {
                "every further key must be one of this copy's own attachments/{id} keys"
            }
        }
        require(objectKeys.toSet().size == objectKeys.size) { "a copy names each key once" }
        require(objectKeys.size <= MAX_KEYS) { "a copy has at most $MAX_KEYS objects, was ${objectKeys.size}" }
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    companion object {
        /**
         * `raw.eml` plus one object per extracted part: the MIME parser stops
         * at 500 parts. Bounded here too, so the core does not rely on another
         * module's limit for the size of its insert.
         */
        const val MAX_KEYS = 501
    }
}

/**
 * One inbound event's admission: every recipient copy that survived
 * resolution and rate limiting, in **envelope order**, all at the same exact
 * physical footprint (ADR-035 §2).
 */
data class StorageAdmissionRequest(
    /** `raw.eml` plus every extracted attachment object, exactly. Never estimated. */
    val bytesPerCopy: Long,
    val candidates: List<StorageAdmissionCandidate>,
    val nodeId: String,
    val generation: UUID,
) {
    init {
        require(bytesPerCopy in 1..MAX_BYTES_PER_COPY) { "bytesPerCopy must be in 1..$MAX_BYTES_PER_COPY, was $bytesPerCopy" }
        require(candidates.size <= MAX_CANDIDATES) { "an event has at most $MAX_CANDIDATES recipients, was ${candidates.size}" }
        require(candidates.map { it.messageId }.toSet().size == candidates.size) { "every copy has its own message id" }
        // Recipients are normalized and deduplicated before T1, and one inbox has
        // one address: an event refuses at most one copy per inbox (ADR-035 §4).
        require(candidates.map { it.inboxId }.toSet().size == candidates.size) { "at most one copy per inbox per event" }
        require(candidates.flatMap { it.objectKeys }.toSet().size == candidates.sumOf { it.objectKeys.size }) {
            "no two copies may claim the same key"
        }
        // One parse, one set of attachments: every copy has the same key shape.
        require(candidates.map { it.objectKeys.size }.distinct().size <= 1) { "every copy of an event has the same objects" }
        require(nodeId.isNotBlank() && nodeId.length <= MAX_NODE_ID_LENGTH) { "nodeId must be 1..$MAX_NODE_ID_LENGTH characters" }
    }

    companion object {
        /** The recipient cap of the mail edge and the gateway (ADR-035 §12). */
        const val MAX_CANDIDATES = 50
        const val MAX_NODE_ID_LENGTH = 200

        /**
         * A sanity bound, far above any real copy: raw MIME is capped at
         * 15 MiB, and its attachments cannot exceed it (ADR-035 §2). A larger
         * figure is a caller bug, refused as input rather than surfacing
         * later as an accounting overflow.
         */
        const val MAX_BYTES_PER_COPY = 1L shl 30
    }
}

/** What T1 decided for one candidate. */
sealed interface StorageAdmissionDecision {
    val candidate: StorageAdmissionCandidate

    /**
     * Reserved. [unenforcedLimit] is the narrowest ceiling this copy exceeded
     * among the scopes whose enforcement is off: what *would* have refused it.
     * It is an observation, never an outcome.
     */
    data class Admitted(
        override val candidate: StorageAdmissionCandidate,
        val reservation: StorageReservationDraft,
        val unenforcedLimit: StorageRefusalReason? = null,
    ) : StorageAdmissionDecision

    /** Refused for the narrowest ceiling reached among the ENFORCED scopes. */
    data class Refused(
        override val candidate: StorageAdmissionCandidate,
        val reason: StorageRefusalReason,
    ) : StorageAdmissionDecision
}

data class StorageAdmissionResult(
    /** One per candidate, in envelope order. */
    val decisions: List<StorageAdmissionDecision>,
    /** The database clock of T1, or null when no T1 ran. */
    val t0: Instant?,
) {
    val admitted: List<StorageAdmissionDecision.Admitted> get() = decisions.filterIsInstance<StorageAdmissionDecision.Admitted>()
    val refused: List<StorageAdmissionDecision.Refused> get() = decisions.filterIsInstance<StorageAdmissionDecision.Refused>()

    companion object {
        /** No candidate survived, so there was no lock, no read, no write (ADR-035 §4, step 4). */
        val NOTHING_TO_ADMIT = StorageAdmissionResult(emptyList(), t0 = null)
    }
}

/**
 * The pure admission rule of ADR-035 §4, separated from the transaction so
 * that it can be proven exhaustively.
 *
 * Candidates are evaluated in envelope order against running totals: every
 * copy admitted earlier in the same event already counts against its inbox,
 * its workspace and the global cap. A copy fits a scope when
 * `used + bytesPerCopy ≤ limit`, so a scope already at or above its limit
 * admits nothing more (I11: no grandfathered growth).
 */
object StorageAdmissionRules {
    data class Placement(
        val workspaceId: WorkspaceId,
        val inboxId: InboxId,
    )

    data class Verdict(
        val admitted: Boolean,
        /**
         * Refused: the narrowest ENFORCED ceiling the copy did not fit.
         * Admitted: the narrowest ceiling it did not fit anyway (all of them
         * observational), or null.
         */
        val ceiling: StorageRefusalReason?,
    )

    fun decide(
        policy: StorageCapacityPolicy,
        enforcement: StorageEnforcement,
        bytesPerCopy: Long,
        placements: List<Placement>,
        snapshot: StorageUsageSnapshot,
    ): List<Verdict> = decide(policy, enforcement, bytesPerCopy, 0, placements, snapshot, null)

    /**
     * The payload ceilings, and with a [footprint] policy the global footprint
     * rules (G) and (C) of the filesystem-containment contract (§2.1), in ONE
     * envelope-order pass: a copy is checked against the exact
     * post-admission aggregate `F(L + D + A + c)`, where *A* is the copies of
     * this event admitted before it, so a copy refused by ANY ceiling adds
     * nothing. A footprint refusal is `SERVICE_CAPACITY`.
     *
     * Under `ALL`, footprint rules that cannot be evaluated — untrusted
     * counts, no or an invalid observation, negative or overflowing totals —
     * throw [StorageFootprintUnavailableException]: an infrastructure state
     * (`451`), never a capacity verdict. Under `OFF` and `TENANT_LIMITS` the
     * footprint is observational, like the payload global ceiling.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod") // the rule's inputs, and one branch per footprint verdict
    fun decide(
        policy: StorageCapacityPolicy,
        enforcement: StorageEnforcement,
        bytesPerCopy: Long,
        objectsPerCopy: Long,
        placements: List<Placement>,
        snapshot: StorageUsageSnapshot,
        footprint: FootprintPolicy?,
    ): List<Verdict> {
        val enforcesGlobal = enforcement.enforces(StorageScope.GLOBAL)
        val footprintInputs = footprint?.let { fp -> footprintInputs(fp, snapshot, enforcesGlobal) }
        return checked {
            var footprintAdded = FootprintAdmission.Load.ZERO
            val inboxUsed = snapshot.inboxes.mapValuesTo(HashMap()) { it.value.usage.usedBytes }
            val workspaceUsed = snapshot.workspaces.mapValuesTo(HashMap()) { it.value.usedBytes }
            var globalUsed = snapshot.global.usedBytes

            placements.map { placement ->
                val used =
                    mapOf(
                        StorageScope.INBOX to inboxUsed.getValue(placement.inboxId),
                        StorageScope.WORKSPACE to workspaceUsed.getValue(placement.workspaceId),
                        StorageScope.GLOBAL to globalUsed,
                    )
                // Narrowest first, by declaration: the reason never depends on
                // which figure the database happened to return first. Only an
                // enforced scope refuses, so a disabled narrower scope never
                // masks an enforced wider one.
                val copy = FootprintAdmission.Load(bytesPerCopy, objectsPerCopy)
                val footprintExceeded =
                    footprintInputs?.let { (fp, inputs) ->
                        when (FootprintAdmission.check(fp.model, fp.limits, inputs, footprintAdded + copy)) {
                            FootprintAdmission.Verdict.ADMITTED -> {
                                false
                            }

                            FootprintAdmission.Verdict.GLOBAL_FOOTPRINT, FootprintAdmission.Verdict.CONTAINMENT -> {
                                true
                            }

                            FootprintAdmission.Verdict.UNOBSERVED -> {
                                if (enforcesGlobal) {
                                    throw StorageFootprintUnavailableException(
                                        FootprintUnavailability.UNOBSERVED,
                                    )
                                } else {
                                    false
                                }
                            }

                            FootprintAdmission.Verdict.INDETERMINATE -> {
                                if (enforcesGlobal) {
                                    throw StorageFootprintUnavailableException(
                                        FootprintUnavailability.INDETERMINATE,
                                    )
                                } else {
                                    false
                                }
                            }
                        }
                    } ?: false
                val exceeded =
                    StorageScope.entries.filter { scope ->
                        Math.addExact(used.getValue(scope), bytesPerCopy) > policy.limitOf(scope) ||
                            (scope == StorageScope.GLOBAL && footprintExceeded)
                    }
                val refusing = exceeded.firstOrNull(enforcement::enforces)
                val admitted = refusing == null
                val ceiling = (refusing ?: exceeded.firstOrNull())?.let(StorageRefusalReason::of)
                if (admitted) {
                    inboxUsed[placement.inboxId] = Math.addExact(used.getValue(StorageScope.INBOX), bytesPerCopy)
                    workspaceUsed[placement.workspaceId] = Math.addExact(used.getValue(StorageScope.WORKSPACE), bytesPerCopy)
                    globalUsed = Math.addExact(globalUsed, bytesPerCopy)
                    footprintAdded += copy
                }
                Verdict(admitted, ceiling)
            }
        }
    }

    /**
     * The footprint snapshot `(L, D, W)` from T1's one statement, or null when
     * the footprint is observational and not evaluable. Under `ALL`, not
     * evaluable throws.
     */
    private fun footprintInputs(
        footprint: FootprintPolicy,
        snapshot: StorageUsageSnapshot,
        enforcesGlobal: Boolean,
    ): Pair<FootprintPolicy, FootprintAdmission.Snapshot>? {
        val unavailable =
            footprint.unavailability(snapshot.footprint)
                ?: runCatching { footprintSnapshot(snapshot) }.exceptionOrNull()?.let { FootprintUnavailability.INDETERMINATE }
        if (unavailable != null) {
            if (enforcesGlobal) throw StorageFootprintUnavailableException(unavailable)
            return null
        }
        return footprint to footprintSnapshot(snapshot)
    }

    /** Negative or overflowing totals are corrupt: they throw, and the caller treats that as INDETERMINATE. */
    private fun footprintSnapshot(snapshot: StorageUsageSnapshot): FootprintAdmission.Snapshot {
        val observed = checkNotNull(snapshot.footprint)
        return FootprintAdmission.Snapshot(
            live = FootprintAdmission.Load(observed.liveBytes, observed.liveObjects),
            debt = FootprintAdmission.Load(observed.debtBytes, observed.debtObjects),
            trashBytes = observed.trashBytes,
        )
    }

    private fun <T> checked(block: () -> T): T =
        try {
            block()
        } catch (overflow: ArithmeticException) {
            throw StorageAccountingOverflowException("storage usage overflowed a signed 64-bit figure", overflow)
        }
}

/**
 * ADR-035 T1 as a use case: decides one event's recipient copies against the
 * inbox, workspace and global ceilings, and reserves the admitted ones, all in
 * one transaction under the global admission lock.
 *
 * **TI-STORAGE-002: this is the engine, not yet connected.** No ingress path
 * calls it, so normal traffic creates no reservation and no mail is refused.
 * The live path (write slots, the fenced upload, T2) arrives in later slices.
 *
 * Time comes only from the database: `created_at` is T1's `t0`, and the write
 * deadline is `t0 + E`. No JVM clock is involved.
 */
class StorageAdmission(
    private val store: StorageAdmissionStore,
    private val policy: StorageCapacityPolicy,
    private val enforcement: StorageEnforcement,
    /** A test seam for the mutant self-tests. Production code always uses the default. */
    private val rules: (
        StorageCapacityPolicy,
        StorageEnforcement,
        Long,
        List<StorageAdmissionRules.Placement>,
        StorageUsageSnapshot,
    ) -> List<StorageAdmissionRules.Verdict> = StorageAdmissionRules::decide,
    /**
     * The global footprint rules (TI-STORAGE-006E PR D), or null where the
     * deployment declares no filesystem. Enforced under `ALL` only.
     */
    private val footprint: FootprintPolicy? = null,
) {
    fun admit(request: StorageAdmissionRequest): StorageAdmissionResult {
        if (request.candidates.isEmpty()) return StorageAdmissionResult.NOTHING_TO_ADMIT

        val scope =
            StorageAdmissionScope(
                workspaceIds = request.candidates.mapTo(LinkedHashSet()) { it.workspaceId },
                inboxIds = request.candidates.mapTo(LinkedHashSet()) { it.inboxId },
            )
        return store.admit(scope) { snapshot ->
            verifyOwnership(request, snapshot)
            val placements = request.candidates.map { StorageAdmissionRules.Placement(it.workspaceId, it.inboxId) }
            val verdicts =
                if (footprint == null) {
                    rules(policy, enforcement, request.bytesPerCopy, placements, snapshot)
                } else {
                    StorageAdmissionRules.decide(
                        policy,
                        enforcement,
                        request.bytesPerCopy,
                        request.candidates
                            .first()
                            .objectKeys.size
                            .toLong(),
                        placements,
                        snapshot,
                        footprint,
                    )
                }
            check(verdicts.size == request.candidates.size) { "one verdict per candidate" }
            val deadline = snapshot.t0.plus(WRITE_WINDOW)
            val decisions =
                request.candidates.zip(verdicts) { candidate, verdict ->
                    if (verdict.admitted) {
                        StorageAdmissionDecision.Admitted(
                            candidate = candidate,
                            reservation =
                                StorageReservationDraft(
                                    messageId = candidate.messageId,
                                    workspaceId = candidate.workspaceId,
                                    inboxId = candidate.inboxId,
                                    objectKeys = candidate.objectKeys,
                                    bytes = request.bytesPerCopy,
                                    createdAt = snapshot.t0,
                                    writeDeadlineAt = deadline,
                                    nodeId = request.nodeId,
                                    generation = request.generation,
                                ),
                            unenforcedLimit = verdict.ceiling,
                        )
                    } else {
                        StorageAdmissionDecision.Refused(candidate, checkNotNull(verdict.ceiling))
                    }
                }
            StorageAdmissionPlan(
                reservations = decisions.filterIsInstance<StorageAdmissionDecision.Admitted>().map { it.reservation },
                outcome = StorageAdmissionResult(decisions, snapshot.t0),
            )
        }
    }

    /**
     * Recipient resolution supplies trusted ids. A mismatch here is a
     * programming error, and persisting it would charge one tenant's bytes to
     * another's ceiling, so T1 fails closed. No row lock is taken: the owner
     * comes from the same snapshot as the figures.
     */
    private fun verifyOwnership(
        request: StorageAdmissionRequest,
        snapshot: StorageUsageSnapshot,
    ) {
        request.candidates.forEach { candidate ->
            val owner = snapshot.inboxes[candidate.inboxId]?.owner
            if (owner != candidate.workspaceId) {
                throw StorageAdmissionInputException(
                    if (owner == null) "inbox does not exist" else "inbox belongs to another workspace",
                )
            }
        }
    }

    companion object {
        /** `E` (ADR-035 §5): the write window, `write_deadline_at = t0 + E`. */
        val WRITE_WINDOW: Duration = Duration.ofSeconds(120)
    }
}
