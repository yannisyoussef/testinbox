package email.testinbox.domain.storage

/**
 * The global admission rules of the filesystem-containment contract (§2.1,
 * §2.4; TI-STORAGE-006E), as pure arithmetic over one T1 snapshot.
 *
 * The potential is ONE aggregate, rounded once:
 *
 * ```
 * Φ = F(L + D) + W
 * ```
 *
 * *L* is the live set (committed rows plus live reservations), *D* the
 * unsuperseded deletion debt, *W* the newest observation's trash bytes. A
 * candidate copy *c* is admitted only if, AFTER adding it and every copy of the
 * event admitted before it:
 *
 * ```
 * (G)  F(L + A + c) + H_F                           ≤ G_F
 * (C)  F(L + D + A + c) + W + H_F + P_F + M + R_ops ≤ C_fs
 * ```
 *
 * Never `F(L) + φ(c)`: the closed form rounds per object, so its increment for
 * one object can exceed φ (a 4 096 B object: φ = 28 688 B, ΔF = 32 800 B).
 * No observation means no admission: without *W* there is no bound on trash.
 * Every ROW-FREE debt write is itself an admission: a witness probe's
 * pending (0 B, 1 object) row, and the pending row the orphan sweep or the
 * ambiguity verifier records for a late object (p, 1) before deleting it.
 * Such a row of load *x* is admitted only if
 *
 * ```
 * (P)  F(L + D + x) + W + H_F + M + R_ops ≤ C_fs
 * ```
 *
 * ([decideRowFreeDebt]) — the invariant of (C) without P_F. Both kinds put
 * bytes in trash that, once an observation supersedes their row, no row
 * charges; no cap on ROWS bounds those BYTES under a purge stall, only
 * admitting each write against the potential does. A refused late object
 * stays where it is and keeps its §9 write slot, so no further late object
 * can take its place (fail closed). *P_F* is the reserve copies leave unused
 * so that probes keep running while copies are refused.
 */
object FootprintAdmission {
    /** Payload and object count of a set of objects. */
    data class Load(
        val bytes: Long,
        val objects: Long,
    ) {
        init {
            require(bytes >= 0 && objects >= 0) { "a load is never negative, was $bytes B in $objects objects" }
        }

        operator fun plus(other: Load): Load = Load(Math.addExact(bytes, other.bytes), Math.addExact(objects, other.objects))

        companion object {
            val ZERO = Load(0, 0)
        }
    }

    /** One T1 snapshot's global figures. [trashBytes] is null when no observation exists. */
    data class Snapshot(
        val live: Load,
        val debt: Load,
        val trashBytes: Long?,
    ) {
        init {
            require(trashBytes == null || trashBytes >= 0) { "observed trash is never negative, was $trashBytes" }
        }
    }

    /** The declared figures the rules compare against. */
    data class Limits(
        val globalFootprintLimitBytes: Long,
        val finalizeBudgetBytes: Long,
        val metadataBudgetBytes: Long,
        val operationalReserveBytes: Long,
        val capacityBytes: Long,
        /**
         * *P_F*: the reserve copies leave unused in rule (C) so that row-free debt
         * writes (rule P) stay admissible while copies are refused. Liveness, not
         * safety: every such write is itself admitted by [decideRowFreeDebt].
         */
        val probeBudgetBytes: Long,
    ) {
        init {
            require(
                listOf(
                    globalFootprintLimitBytes,
                    finalizeBudgetBytes,
                    metadataBudgetBytes,
                    operationalReserveBytes,
                    capacityBytes,
                    probeBudgetBytes,
                ).all { it >= 0 },
            ) { "limits are never negative" }
        }
    }

    enum class Verdict {
        ADMITTED,

        /** Rule (G): the live footprint would pass the policy ceiling. */
        GLOBAL_FOOTPRINT,

        /** Rule (C): the potential would breach containment (debt reduces the ceiling). */
        CONTAINMENT,

        /** No observation: trash is unbounded, nothing is admitted. An infrastructure state (451). */
        UNOBSERVED,

        /**
         * The arithmetic cannot bound the potential (an overflow): neither a
         * capacity verdict nor an admission. An infrastructure state (451),
         * never SERVICE_CAPACITY, so it is never cached as a capacity refusal.
         */
        INDETERMINATE,
    }

    /** Rule (P) for a witness probe: a pending (0 B, 1 object) row. */
    fun decideProbe(
        model: FootprintModel,
        limits: Limits,
        snapshot: Snapshot,
    ): Verdict = decideRowFreeDebt(model, limits, snapshot, PROBE)

    /**
     * Rule (P): may a row-free deletion record its pending row of [load] and
     * proceed? The row is added to D. Unobserved, or arithmetic that
     * overflows, refuses.
     */
    fun decideRowFreeDebt(
        model: FootprintModel,
        limits: Limits,
        snapshot: Snapshot,
        load: Load,
    ): Verdict {
        val trash = snapshot.trashBytes ?: return Verdict.UNOBSERVED
        return try {
            val all = snapshot.live + snapshot.debt + load
            val needed =
                Math.addExact(
                    Math.addExact(Math.addExact(model.bound(all.bytes, all.objects), trash), limits.finalizeBudgetBytes),
                    Math.addExact(limits.metadataBudgetBytes, limits.operationalReserveBytes),
                )
            if (needed > limits.capacityBytes) Verdict.CONTAINMENT else Verdict.ADMITTED
        } catch (_: ArithmeticException) {
            Verdict.INDETERMINATE
        }
    }

    /** What one witness probe adds to the debt: a zero-byte object. */
    val PROBE = Load(0, 1)

    /** Φ = F(L + D) + W, or null when unobserved. Checked arithmetic. */
    fun potential(
        model: FootprintModel,
        snapshot: Snapshot,
    ): Long? {
        val trash = snapshot.trashBytes ?: return null
        val total = snapshot.live + snapshot.debt
        return Math.addExact(model.bound(total.bytes, total.objects), trash)
    }

    /**
     * Decides each candidate copy in order against running totals: copy *i*
     * is checked with every earlier ADMITTED copy already added. A refused copy
     * adds nothing. An arithmetic overflow anywhere makes the WHOLE event
     * [Verdict.INDETERMINATE] (fail closed): totals that overflow are corrupt,
     * an infrastructure state, never a per-copy verdict.
     */
    fun decide(
        model: FootprintModel,
        limits: Limits,
        snapshot: Snapshot,
        copies: List<Load>,
    ): List<Verdict> {
        val trash = snapshot.trashBytes ?: return copies.map { Verdict.UNOBSERVED }
        return try {
            var admitted = Load.ZERO
            copies.map { copy ->
                verdict(model, limits, snapshot, trash, admitted + copy).also { if (it == Verdict.ADMITTED) admitted += copy }
            }
        } catch (_: ArithmeticException) {
            copies.map { Verdict.INDETERMINATE }
        }
    }

    private fun verdict(
        model: FootprintModel,
        limits: Limits,
        snapshot: Snapshot,
        trash: Long,
        added: Load,
    ): Verdict {
        val live = snapshot.live + added
        val policy = Math.addExact(model.bound(live.bytes, live.objects), limits.finalizeBudgetBytes)
        if (policy > limits.globalFootprintLimitBytes) return Verdict.GLOBAL_FOOTPRINT
        val all = live + snapshot.debt
        val potential = Math.addExact(model.bound(all.bytes, all.objects), trash)
        val needed =
            Math.addExact(
                Math.addExact(Math.addExact(potential, limits.finalizeBudgetBytes), limits.probeBudgetBytes),
                Math.addExact(limits.metadataBudgetBytes, limits.operationalReserveBytes),
            )
        return if (needed > limits.capacityBytes) Verdict.CONTAINMENT else Verdict.ADMITTED
    }
}
