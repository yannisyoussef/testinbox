package email.testinbox.domain.storage

/**
 * Slot-lane escrow arithmetic (PROPOSED ADR-038; structural-admission-design.md
 * §4.2 step 4), pure and framework-free, beside [FootprintAdmission].
 *
 * Two kinds of question, never mixed:
 * - **Grants admit only.** A copy fits a grant when its payload and its own
 *   footprint `F(c)` are within what remains of it. A grant is a lower bound of
 *   exact headroom, so a copy that fits it fits the truth; one that does not
 *   fit says nothing, and the event escalates. A grant never refuses.
 * - **Exact rows decide with mode a's own formula** (correctness 3). A row
 *   refilled in this transaction carries its truth `U`, `N`, `C`, `C_obj`, and a
 *   copy is refused exactly when mode a would refuse it:
 *   payload `U + running + f > lim`, footprint `F(U + C + running + c) > F_W`.
 */
object EscrowFit {
    /** What remains of one grant for the rest of an event: payload, and footprint where the row carries one. */
    data class Remaining(
        val payload: Long,
        val footprint: Long?,
    ) {
        /** Spends [copy]'s payload and footprint from what remains, or null when it does not fit. */
        fun spend(
            payloadBytes: Long,
            footprintBytes: Long,
        ): Remaining? {
            if (payloadBytes > payload) return null
            if (footprint != null && footprintBytes > footprint) return null
            return Remaining(payload - payloadBytes, footprint?.minus(footprintBytes))
        }
    }

    /**
     * The full-envelope priced prefix of an event (ADR-037 A1): mode a prices
     * every copy at `f · (p + 1)` for its envelope position `p`, so the largest
     * charge any copy of the event can see against the global ceilings is the
     * prefix through its LAST candidate position. A lane fits the event iff it
     * covers that prefix, payload and `F` of it; then no copy of the event can
     * reach `SERVICE_CAPACITY` or a global footprint rule in mode a.
     */
    fun lanePrefix(
        model: FootprintModel,
        bytesPerCopy: Long,
        objectsPerCopy: Long,
        lastEnvelopePosition: Int,
    ): FootprintAdmission.Load {
        require(lastEnvelopePosition >= 0) { "an envelope position is never negative" }
        val prefix = lastEnvelopePosition.toLong() + 1
        return FootprintAdmission.Load(Math.multiplyExact(bytesPerCopy, prefix), Math.multiplyExact(objectsPerCopy, prefix))
    }

    /** Does a lane's grant cover the event's priced prefix: payload, and `F(prefix)` by subadditivity (Lemma 2)? */
    fun laneFits(
        model: FootprintModel,
        payloadAvail: Long,
        footprintAvail: Long,
        prefix: FootprintAdmission.Load,
    ): Boolean = prefix.bytes <= payloadAvail && model.bound(prefix.bytes, prefix.objects) <= footprintAvail

    /** Mode a's payload rule on exact truth: refused iff `used + running + f > limit`. Checked arithmetic. */
    fun exceedsPayload(
        used: Long,
        running: Long,
        bytesPerCopy: Long,
        limit: Long,
    ): Boolean = Math.addExact(Math.addExact(used, running), bytesPerCopy) > limit

    /**
     * Mode a's workspace footprint rule on exact truth:
     * refused iff `F(U + C + running + c, N + C_obj + runningObjects + n) > F_W`.
     */
    @Suppress("LongParameterList") // the rule's own inputs, as mode a reads them
    fun exceedsFootprint(
        model: FootprintModel,
        usedBytes: Long,
        usedObjects: Long,
        cleanup: FootprintAdmission.Load,
        running: FootprintAdmission.Load,
        copy: FootprintAdmission.Load,
        footprintLimit: Long,
    ): Boolean {
        val bytes = Math.addExact(Math.addExact(Math.addExact(usedBytes, cleanup.bytes), running.bytes), copy.bytes)
        val objects = Math.addExact(Math.addExact(Math.addExact(usedObjects, cleanup.objects), running.objects), copy.objects)
        return model.bound(bytes, objects) > footprintLimit
    }
}
