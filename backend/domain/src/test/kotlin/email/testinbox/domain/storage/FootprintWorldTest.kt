package email.testinbox.domain.storage

import email.testinbox.domain.storage.FootprintAdmission.Load
import email.testinbox.domain.storage.FootprintAdmission.Snapshot
import email.testinbox.domain.storage.FootprintAdmission.Verdict
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * The containment theorem of the filesystem-containment contract (§2.4) over
 * arbitrary interleavings of the ATOMIC steps the system really takes
 * (TI-STORAGE-006E).
 *
 * Every multi-step path is split where the implementation splits it, and any
 * other step may run in between: an upload materializes one object at a time;
 * a release deletes keys before its row goes; retention moves blobs to trash
 * before its rows go (one debt row); the orphan sweep writes a pending row,
 * deletes, and later resolves under a new order; an observation takes its
 * order, walks, and only then writes its row. The world is adversarial: every
 * object physically costs exactly φ of its payload (the qualified maximum),
 * often materializes at once, an observation sees only the trash present when
 * it began and still present when it ended, purges are rare, and late objects
 * surface whenever the ADR-035 §9 slots allow.
 *
 * Oracles, checked after EVERY step, from the physical state:
 * - **injection** (the theorem's proof, §2.4): every object on the filesystem,
 *   in trash or not, is covered by a live row or reservation, a debt row the
 *   snapshot counts, or the newest observation's measured set — except late
 *   objects whose §9 write slot is still held (at most `slots`, each at most
 *   the largest object size), which is what H_F reserves for;
 * - **theorem**: TestInbox's physical bytes ≤ C_fs − R_ops − M;
 * - **Lemma 1**: physical ≤ Φ + φ(uncovered), Φ = F(L + D) + W;
 * - **admission** (main run only): right after an admission, (G) and (C) hold
 *   on the exact post-admission aggregate.
 *
 * The worlds are tight (both rules refuse, steps run near the ceiling), the run
 * asserts it reached every interesting state, and each [Mutant] — one per
 * obligation of the proof — must be caught by a PHYSICAL oracle in the same
 * pinned worlds, with the admission oracle switched off: an oracle that only
 * restates the rule would catch a rule mutant without proving anything.
 */
class FootprintWorldTest {
    private val kib = 1024L
    private val model = FootprintModel.REFERENCE

    // Large objects keep φ within a few percent of F, so the physical oracles have little
    // slack to hide behind; the debt room is G_F / 8, like the staging D_budget / G_F.
    private val maxObject = 256 * kib
    private val slots = 2
    private val h = slots * model.bound(maxObject, 1)
    private val gF = 48 * model.bound(maxObject, 1)
    private val m = 64 * kib
    private val r = 128 * kib
    private val capacity = gF + gF / 8 + h + m + r
    private val ceiling = capacity - r - m
    private val limits = FootprintAdmission.Limits(gF, h, m, r, capacity)

    /** One obligation of the proof each, broken on purpose. */
    enum class Mutant {
        /** T1 charges `F(L + D) + W + φ(c)` — the owner's counterexample. */
        PHI_CHARGE,

        /** Rule (C) without H_F: late objects are not reserved for. */
        NO_FINALIZE_BUDGET,

        /** Rule (C) without D: deletion debt does not reduce the ceiling. */
        DEBT_OUTSIDE_C,

        /** Rule (C) without W: the measured trash is ignored. */
        NO_TRASH_TERM,

        /** Retention writes its debt row BEFORE moving blobs to trash (§4.6 violated). */
        DEBT_BEFORE_TRASH,

        /** The observation takes its order when it ENDS, not before measuring (§5.3). */
        OBSERVATION_ORDER_AT_END,

        /** Debt compaction deletes pending rows too (§5.2). */
        COMPACT_PENDING,

        /** Resolving a pending row keeps its old order instead of a new one (§5.2). */
        RESOLVE_KEEPS_ORDER,

        /** T1 reads the latest-ARRIVED observation, compaction the latest-started (§5.3). */
        NEWEST_BY_ARRIVAL,
    }

    private class Coverage {
        var admitted = 0L
        var refusedGlobal = 0L
        var refusedContainment = 0L
        var lateSurfaced = 0L
        var sweepsResolved = 0L
        var interleavedObservations = 0L
        var debtRowsCompacted = 0L
        var nearBoundary = 0L

        override fun toString() =
            "admitted=$admitted refusedG=$refusedGlobal refusedC=$refusedContainment late=$lateSurfaced " +
                "resolved=$sweepsResolved interleaved=$interleavedObservations compacted=$debtRowsCompacted nearBoundary=$nearBoundary"
    }

    @Suppress("TooManyFunctions")
    private inner class World(
        private val rnd: Random,
        private val mutant: Mutant?,
        private val coverage: Coverage,
        private val checkAdmission: Boolean,
    ) {
        private var seq = 0L
        private var nextId = 0L
        private val payload = HashMap<Long, Long>()

        private inner class Reservation(
            val ids: List<Long>,
        ) {
            val uploaded = HashSet<Long>()
        }

        private inner class Debt(
            val ids: Set<Long>,
            var seq: Long,
            var pending: Boolean,
        )

        private inner class Observation(
            val startedSeq: Long,
            val measured: Set<Long>,
            val trashBytes: Long,
            val arrival: Long,
        )

        private inner class Walk(
            val startedSeq: Long,
            val seen: Set<Long>,
            val trashMovesAtStart: Long,
        )

        private inner class Sweep(
            val id: Long,
            val row: Debt,
            var deleted: Boolean = false,
        )

        private val reservations = mutableListOf<Reservation>()
        private val releasing = mutableListOf<Reservation>()
        private val committed = mutableListOf<List<Long>>()
        private val expiring = mutableListOf<List<Long>>()

        /** Objects on the filesystem outside the trash. */
        private val materialized = HashSet<Long>()
        private val trash = HashMap<Long, Long>()
        private val pendingLate = mutableListOf<Long>()
        private val surfaced = mutableListOf<Long>()
        private val sweeps = mutableListOf<Sweep>()
        private val debts = mutableListOf<Debt>()
        private val observations = mutableListOf<Observation>()
        private val walks = mutableListOf<Walk>()
        private var trashMoves = 0L
        private val trace = ArrayDeque<Int>()

        private fun phi(id: Long) = model.ofObject(payload.getValue(id))

        private fun load(ids: Collection<Long>) = Load(ids.sumOf { payload.getValue(it) }, ids.size.toLong())

        private fun newObject(): Long {
            val bytes =
                when (rnd.nextInt(8)) {
                    0 -> 0L

                    // one byte past a block: the worst rounding
                    1 -> rnd.nextLong(maxObject / 4096) * 4096 + 1

                    2, 3 -> rnd.nextLong(maxObject + 1)

                    else -> maxObject
                }
            return nextId++.also { payload[it] = bytes }
        }

        private fun newest(): Observation? =
            if (mutant == Mutant.NEWEST_BY_ARRIVAL) {
                observations.maxByOrNull { it.arrival }
            } else {
                observations.maxByOrNull { it.startedSeq }
            }

        // A RELEASING reservation row, and an expiring message row, are live until their delete commits.
        private fun liveIds(): List<List<Long>> = committed + expiring + reservations.map { it.ids } + releasing.map { it.ids }

        private fun countedDebts(): List<Debt> {
            val from = newest()?.startedSeq ?: Long.MIN_VALUE
            return debts.filter { it.pending || it.seq >= from }
        }

        fun snapshot(): Snapshot {
            val live = liveIds().fold(Load.ZERO) { acc, ids -> acc + load(ids) }
            val debt = countedDebts().fold(Load.ZERO) { acc, d -> acc + load(d.ids) }
            return Snapshot(live, debt, newest()?.trashBytes)
        }

        /** TestInbox's bytes on the filesystem: every object at φ, plus the trash. */
        fun physical(): Long = materialized.sumOf(::phi) + trash.values.sum()

        private fun moveToTrash(id: Long) {
            if (materialized.remove(id)) {
                trash[id] = phi(id)
                trashMoves++
            }
        }

        private fun debtRow(ids: Collection<Long>) {
            if (ids.isNotEmpty()) debts += Debt(ids.toSet(), ++seq, pending = false)
        }

        private fun decide(copies: List<Load>): List<Verdict> {
            val snapshot = snapshot()
            return when (mutant) {
                Mutant.PHI_CHARGE -> {
                    phiChargeDecide(snapshot, copies)
                }

                Mutant.NO_FINALIZE_BUDGET -> {
                    FootprintAdmission.decide(model, limits.copy(finalizeBudgetBytes = 0), snapshot, copies)
                }

                Mutant.DEBT_OUTSIDE_C -> {
                    FootprintAdmission.decide(model, limits, snapshot.copy(debt = Load.ZERO), copies)
                }

                Mutant.NO_TRASH_TERM -> {
                    FootprintAdmission.decide(model, limits, snapshot.copy(trashBytes = snapshot.trashBytes?.let { 0L }), copies)
                }

                else -> {
                    FootprintAdmission.decide(model, limits, snapshot, copies)
                }
            }
        }

        /** `F(L + D) + W + Σφ(admitted copies)`: per-object charges, the rule the owner refuted. */
        private fun phiChargeDecide(
            snapshot: Snapshot,
            copies: List<Load>,
        ): List<Verdict> {
            val w = snapshot.trashBytes ?: return copies.map { Verdict.UNOBSERVED }
            val perCopy = pendingShape.sumOf { model.ofObject(it) }
            var charged = 0L
            val live = model.bound(snapshot.live.bytes, snapshot.live.objects)
            val all = snapshot.live + snapshot.debt
            val potential = model.bound(all.bytes, all.objects) + w
            return copies.map {
                if (live + charged + perCopy + h <= gF && potential + charged + perCopy + h + m + r <= capacity) {
                    charged += perCopy
                    Verdict.ADMITTED
                } else {
                    Verdict.CONTAINMENT
                }
            }
        }

        private var pendingShape: List<Long> = emptyList()

        private fun checkAdmissionInvariant(context: () -> String) {
            val s = snapshot()
            val w = checkNotNull(s.trashBytes)
            val all = s.live + s.debt
            withClue("admission (G): ${context()}") { (model.bound(s.live.bytes, s.live.objects) + h <= gF) shouldBe true }
            withClue("admission (C): ${context()}") {
                (model.bound(all.bytes, all.objects) + w + h + m + r <= capacity) shouldBe true
            }
        }

        private fun admit(context: () -> String) {
            if (observations.isEmpty()) return
            val shape = List(1 + rnd.nextInt(2)) { newObject() }
            pendingShape = shape.map { payload.getValue(it) }
            val verdicts = decide(List(1 + rnd.nextInt(3)) { load(shape) })
            var first = true
            verdicts.forEach { verdict ->
                when (verdict) {
                    Verdict.ADMITTED -> {
                        val ids = if (first) shape else shape.map { id -> nextId++.also { payload[it] = payload.getValue(id) } }
                        first = false
                        val reservation = Reservation(ids)
                        reservations += reservation
                        // Worst case, often: the whole copy is written at once.
                        if (rnd.nextInt(4) != 0) ids.forEach { reservation.uploaded += it.also(materialized::add) }
                        coverage.admitted++
                    }

                    Verdict.GLOBAL_FOOTPRINT -> {
                        coverage.refusedGlobal++
                    }

                    else -> {
                        coverage.refusedContainment++
                    }
                }
            }
            if (checkAdmission && Verdict.ADMITTED in verdicts) checkAdmissionInvariant(context)
        }

        @Suppress("CyclomaticComplexMethod", "LongMethod") // one branch per atomic step, by design
        fun step(context: () -> String) {
            val op = OPS[rnd.nextInt(OPS.size)]
            trace.addLast(op)
            if (trace.size > TRACE) trace.removeFirst()
            when (op) {
                0 -> {
                    admit(context)
                }

                // An upload materializes one reserved object.
                4 -> {
                    reservations.randomOrNull()?.let { res ->
                        res.ids.firstOrNull { it !in res.uploaded }?.let { res.uploaded += it.also(materialized::add) }
                    }
                }

                // T2: a fully uploaded reservation becomes rows (a transfer within L).
                6 -> {
                    reservations.firstOrNull { it.uploaded.size == it.ids.size }?.let {
                        reservations.remove(it)
                        committed += it.ids
                    }
                }

                // Definitive failure or duplicate, step 1: keys deleted and proven absent.
                7 -> {
                    reservations.randomOrNull()?.let { res ->
                        reservations.remove(res)
                        res.uploaded.forEach(::moveToTrash)
                        releasing += res
                    }
                }

                // Release step 2: the row goes, and its trigger writes the debt row.
                8 -> {
                    releasing.randomOrNull()?.let {
                        releasing.remove(it)
                        debtRow(it.ids)
                    }
                }

                // Ambiguous release: proven absent at release, but an un-uploaded object may
                // still surface while its write slot is held (ADR-035 §9: at most `slots`).
                9 -> {
                    if (pendingLate.size + surfaced.size >= slots) return
                    reservations.firstOrNull { it.uploaded.size < it.ids.size }?.let { res ->
                        reservations.remove(res)
                        res.uploaded.forEach(::moveToTrash)
                        debtRow(res.ids)
                        pendingLate += res.ids.first { it !in res.uploaded }
                    }
                }

                10 -> {
                    if (pendingLate.isNotEmpty()) {
                        val id = pendingLate.removeAt(0)
                        if (rnd.nextBoolean()) {
                            surfaced += id
                            materialized += id
                            coverage.lateSurfaced++
                        } // else verified absent: the slot frees
                    }
                }

                // Retention step 1 (blobs first, §4.6). DEBT_BEFORE_TRASH writes its debt row here instead.
                11 -> {
                    committed.randomOrNull()?.let { ids ->
                        committed.remove(ids)
                        expiring += ids
                        if (mutant == Mutant.DEBT_BEFORE_TRASH) debtRow(ids) else ids.forEach(::moveToTrash)
                    }
                }

                // Retention step 2: the rows go, one debt row.
                12 -> {
                    expiring.randomOrNull()?.let { ids ->
                        expiring.remove(ids)
                        if (mutant == Mutant.DEBT_BEFORE_TRASH) ids.forEach(::moveToTrash) else debtRow(ids)
                    }
                }

                // Orphan sweep / verifier on a surfaced object: pending row (committed first),
                // then the S3 delete, then the proof and the resolution under a new order.
                13 -> {
                    val sweep = sweeps.randomOrNull()
                    when {
                        sweep == null -> {
                            surfaced.firstOrNull()?.let { id ->
                                val row = Debt(setOf(id), ++seq, pending = true)
                                debts += row
                                sweeps += Sweep(id, row)
                            }
                        }

                        !sweep.deleted -> {
                            surfaced.remove(sweep.id)
                            moveToTrash(sweep.id)
                            sweep.deleted = true
                        }

                        else -> {
                            sweep.row.pending = false
                            if (mutant != Mutant.RESOLVE_KEEPS_ORDER) sweep.row.seq = ++seq
                            sweeps.remove(sweep)
                            coverage.sweepsResolved++
                        }
                    }
                }

                // MinIO purges one trash object.
                14 -> {
                    trash.keys.randomOrNull()?.let { trash.remove(it) }
                }

                // Observation, step 1: the order is taken BEFORE the walk begins.
                15 -> {
                    if (walks.size < 2) {
                        val started = if (mutant == Mutant.OBSERVATION_ORDER_AT_END) Long.MIN_VALUE else ++seq
                        walks += Walk(started, trash.keys.toSet(), trashMoves)
                    }
                }

                // Observation, step 2: the walk sees only what was in trash at its start and is
                // still there; then the row is written.
                16 -> {
                    walks.randomOrNull()?.let { walk ->
                        walks.remove(walk)
                        val measured = walk.seen.filterTo(HashSet()) { it in trash }
                        val started = if (walk.startedSeq == Long.MIN_VALUE) ++seq else walk.startedSeq
                        observations += Observation(started, measured, measured.sumOf { trash.getValue(it) }, observations.size.toLong())
                        if (trashMoves > walk.trashMovesAtStart) coverage.interleavedObservations++
                    }
                }

                // Debt compaction keyed on the newest-STARTED observation (the database function).
                17 -> {
                    val newest = observations.maxOfOrNull { it.startedSeq } ?: return
                    val before = debts.size
                    debts.removeAll { (mutant == Mutant.COMPACT_PENDING || !it.pending) && it.seq < newest }
                    coverage.debtRowsCompacted += before - debts.size
                }
            }
        }

        fun checkOracles(context: () -> String) {
            val physical = physical()
            if (physical > ceiling * 9 / 10) coverage.nearBoundary++
            // Injection: whatever is on the filesystem is covered by what T1 reads.
            val covered = HashSet<Long>()
            liveIds().forEach(covered::addAll)
            countedDebts().forEach { covered.addAll(it.ids) }
            newest()?.let { covered.addAll(it.measured) }
            val uncovered = (materialized + trash.keys).filter { it !in covered }
            // The only objects allowed outside what T1 reads are late objects whose §9 write
            // slot is still held (at most `slots`, each at most the largest object): that is
            // what H_F reserves for. One in trash, or one whose slot freed, is lost debt.
            withClue("injection: uncovered=${uncovered.map { payload[it] }} ${context()}") {
                (surfaced.size <= slots && uncovered.all { it in surfaced && payload.getValue(it) <= maxObject }) shouldBe true
            }
            withClue("theorem: ${context()}") { (physical <= ceiling) shouldBe true }
            val potential = FootprintAdmission.potential(model, snapshot()) ?: return
            withClue("Lemma 1: ${context()}") { (physical <= potential + uncovered.sumOf(::phi)) shouldBe true }
        }

        fun describe(): String =
            "physical=${physical()} ceiling=$ceiling snapshot=${snapshot()} surfaced=${surfaced.size} pendingLate=${pendingLate.size} " +
                "trash=${trash.size} debts=${debts.size} walks=${walks.size} observations=${observations.size} last steps=$trace"

        private fun <T> Collection<T>.randomOrNull(): T? = if (isEmpty()) null else elementAt(rnd.nextInt(size))
    }

    /** Runs one pinned world; returns the first oracle violation, or null. */
    private fun run(
        seed: Long,
        mutant: Mutant?,
        coverage: Coverage,
        checkAdmission: Boolean,
    ): AssertionError? {
        val world = World(Random(seed), mutant, coverage, checkAdmission)
        repeat(STEPS) { i ->
            val context = { "seed=$seed step=$i mutant=$mutant ${world.describe()}" }
            try {
                world.step(context)
                world.checkOracles(context)
            } catch (e: AssertionError) {
                return e
            }
        }
        return null
    }

    @Test
    fun `containment holds after every atomic step of every pinned interleaving, and the worlds are tight`() {
        val coverage = Coverage()
        (1L..WORLDS).forEach { seed -> run(seed, mutant = null, coverage, checkAdmission = true)?.let { throw it } }
        println("FootprintWorldTest coverage: $coverage")
        // Vacuity guard: the run must reach every state the proof is about. The floors are
        // about half of what the pinned worlds reach, so a change that starves one fails here.
        withClue("coverage: $coverage") {
            coverage.admitted shouldBeGreaterThan 10_000
            coverage.refusedGlobal shouldBeGreaterThan 200 // (C) binds first in these worlds: debt room is G_F / 8
            coverage.refusedContainment shouldBeGreaterThan 1_000
            coverage.lateSurfaced shouldBeGreaterThan 1_000
            coverage.sweepsResolved shouldBeGreaterThan 1_000
            coverage.interleavedObservations shouldBeGreaterThan 1_000
            coverage.debtRowsCompacted shouldBeGreaterThan 1_000
            coverage.nearBoundary shouldBeGreaterThan 1_000
        }
    }

    /** Which oracles caught a mutant in the pinned worlds: the first violation of each world. */
    private fun caughtBy(
        mutant: Mutant,
        checkAdmission: Boolean,
    ): Set<String> =
        (1L..WORLDS)
            .mapNotNull { seed -> run(seed, mutant, Coverage(), checkAdmission)?.message }
            .map { it.substringBefore(':').substringBefore(" (") }
            .toSet()

    @Test
    fun `the owner's counterexample is caught by the admission invariant it breaks`() {
        // Under the per-object premise (each object costs at most φ) the φ rule is not
        // physically unsafe on its own — Σφ(L) + φ(c) ≤ F(L) + φ(c) — but it breaks the
        // invariant every other argument of the proof is built on.
        ("admission" in caughtBy(Mutant.PHI_CHARGE, checkAdmission = true)) shouldBe true
    }

    @Test
    fun `every other mutant of the proof puts real bytes past the ceiling or outside what T1 reads`() {
        val caught = (Mutant.entries - Mutant.PHI_CHARGE).associateWith { caughtBy(it, checkAdmission = false) }
        println("FootprintWorldTest mutants: $caught")
        caught.forEach { (mutant, oracles) ->
            withClue("$mutant, caught by $caught") { (oracles - "admission").isNotEmpty() shouldBe true }
        }
    }

    private companion object {
        const val WORLDS = 400L
        const val STEPS = 600
        const val TRACE = 24

        /**
         * The step weights, as a table of step numbers. Purges are rare so trash
         * accumulates; the sweep, walk and compaction steps are frequent enough
         * that their interleavings occur in the pinned worlds.
         */
        val OPS: IntArray =
            intArrayOf(
                0,
                0,
                0,
                0, // T1
                4,
                4, // upload
                6,
                6, // T2
                7,
                8, // definitive release
                9,
                9,
                10,
                10, // ambiguous release; late surface or verified
                11,
                11,
                12,
                12, // retention
                13,
                13,
                13,
                13, // sweep steps
                14, // purge
                15,
                15,
                16,
                16, // observation walks
                17,
                17, // debt compaction
            )
    }
}
