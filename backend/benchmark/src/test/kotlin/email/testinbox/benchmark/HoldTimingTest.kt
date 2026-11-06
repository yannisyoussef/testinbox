package email.testinbox.benchmark

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/** The `--focused` hold timer observes T1's statements on the event's thread, and changes none of them. */
class HoldTimingTest {
    /** What the fake driver was asked to do, in order. */
    private val calls = mutableListOf<String>()

    private fun <T> fake(
        type: Class<T>,
        answer: (String, Array<out Any?>?) -> Any?,
    ): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> answer(method.name, args) })

    private val resultSet: ResultSet = fake(ResultSet::class.java) { _, _ -> null }

    private fun statement(sql: String): PreparedStatement =
        fake(PreparedStatement::class.java) { name, _ ->
            calls += "$name:${sql.take(TAG)}"
            when (name) {
                "executeQuery" -> resultSet
                "executeUpdate" -> 1
                else -> null
            }
        }

    private val connection: Connection =
        fake(Connection::class.java) { name, args ->
            when (name) {
                "prepareStatement" -> statement(args!![0] as String)
                "commit" -> null.also { calls += "commit" }
                else -> null
            }
        }

    private val dataSource = HoldTiming.wrap(fake(DataSource::class.java) { name, _ -> if (name == "getConnection") connection else null })

    private fun t1(
        c: Connection,
        insert: Boolean = true,
    ) {
        c.prepareStatement(LOCK).executeQuery()
        c.prepareStatement(SNAPSHOT).executeQuery()
        if (insert) c.prepareStatement(INSERT).executeUpdate()
        c.commit()
    }

    @Test
    fun `the hold runs from the lock's return to the commit's return, with the snapshot and insert inside it`() {
        val timings = EventTimings()
        EventTimings.current.set(timings)
        try {
            val c = dataSource.connection
            t1(c)
            // T2 on the same thread afterwards: an advisory lock and a commit that are not T1's.
            c.prepareStatement(LOCK).executeQuery()
            c.prepareStatement(T2_INSERT).executeUpdate()
            c.commit()
        } finally {
            EventTimings.current.remove()
        }
        val c = timings.steps
        c.complete shouldBe true
        // The stamps are in order, and the steps tile the hold.
        listOf(c.lockStart, c.lockEnd, c.snapshotStart, c.snapshotEnd, c.writeStart, c.insertEnd, c.commitStart, c.commitEnd)
            .zipWithNext()
            .all { (a, b) -> a <= b } shouldBe true
        (c.lockEnd > 0 && c.writeStart > c.snapshotEnd) shouldBe true
        // Every call reached the driver, unchanged, in order.
        calls shouldBe
            listOf(
                "executeQuery:${LOCK.take(TAG)}",
                "executeQuery:${SNAPSHOT.take(TAG)}",
                "executeUpdate:${INSERT.take(TAG)}",
                "commit",
                "executeQuery:${LOCK.take(TAG)}",
                "executeUpdate:${T2_INSERT.take(TAG)}",
                "commit",
            )
    }

    @Test
    fun `without an event on the thread nothing is recorded, and a T1 that reserves nothing still has a hold`() {
        t1(dataSource.connection)
        val timings = EventTimings()
        EventTimings.current.set(timings)
        try {
            t1(dataSource.connection, insert = false)
        } finally {
            EventTimings.current.remove()
        }
        timings.steps.complete shouldBe true
        timings.steps.insertEnd shouldBe 0L
        // Nothing reserved: the decision is done when the commit starts.
        timings.steps.writeStart shouldBe timings.steps.commitStart
    }

    @Test
    fun `each snapshot execution is counted on its physical connection, and a T1 records its ordinal there`() {
        // The driver connections under the pool's handles (Hikari's unwrap(Connection) returns them).
        val physicalA = fake(Connection::class.java) { _, _ -> null }
        val physicalB = fake(Connection::class.java) { _, _ -> null }

        fun handleOver(physical: Connection): Connection =
            fake(Connection::class.java) { name, args ->
                when (name) {
                    "prepareStatement" -> statement(args!![0] as String)
                    "unwrap" -> physical
                    else -> null
                }
            }
        val handles = ArrayDeque(listOf(handleOver(physicalA), handleOver(physicalA), handleOver(physicalB)))
        val ledger = ConnectionLedger()
        val pool =
            HoldTiming.wrap(
                fake(DataSource::class.java) { name, _ ->
                    if (name ==
                        "getConnection"
                    ) {
                        handles.removeFirst()
                    } else {
                        null
                    }
                },
                ledger,
            )
        val a = pool.connection
        val aAgain = pool.connection
        val b = pool.connection
        // Four snapshots on A with no event on the thread (the probe, the warm-up): pgJDBC's count advances all the same.
        repeat(4) { a.prepareStatement(SNAPSHOT).executeQuery() }
        // Neither the lock nor the insert is a snapshot.
        a.prepareStatement(LOCK).executeQuery()
        ledger.startPhase()

        fun timed(c: Connection): Int {
            val timings = EventTimings()
            EventTimings.current.set(timings)
            try {
                t1(c)
            } finally {
                EventTimings.current.remove()
            }
            return timings.steps.snapshotOrdinal
        }
        // The same physical connection through another pool handle: its 5th execution, still a cold plan.
        timed(aAgain) shouldBe 5
        timed(a) shouldBe 6
        timed(b) shouldBe 1
        ledger.phase() shouldBe LedgerPhase(snapshotExecutions = 3, coldExecutions = 2, physicalConnections = 2, firstUsedInPhase = 1)
    }

    @Test
    fun `the step summary is null without a complete T1, and each step is the difference of its two stamps`() {
        T1StepsSummary.of(listOf(EventTimings())).shouldBeNull()
        val events =
            listOf(2_000_000L, 4_000_000L).map { hold ->
                EventTimings().apply {
                    steps.begin(100)
                    steps.statement(HoldTiming.Step.LOCK, 200, 1_000)
                    steps.statement(HoldTiming.Step.SNAPSHOT, 1_100, 1_500)
                    steps.statement(HoldTiming.Step.INSERT, 1_700, 1_900)
                    steps.commit(2_000, 1_000 + hold)
                    t1EndNanos = 1_000 + hold + 50
                }
            }
        val summary = T1StepsSummary.of(events)!!.steps
        summary.keys.toList() shouldBe T1StepsSummary.NAMES
        summary.getValue("hold").meanMs shouldBe (3.0 plusOrMinus 1e-9)
        summary.getValue("preLock").latency.maxMs shouldBe 0.0001
        summary.getValue("lockWait").latency.maxMs shouldBe 0.0008
        summary.getValue("decide").latency.maxMs shouldBe 0.0002
        summary.getValue("insert").latency.maxMs shouldBe 0.0002
        summary.getValue("afterCommit").latency.maxMs shouldBe 0.00005
    }

    @Test
    fun `the focused report's M-M-1 wait p99 is the analysis's figure at rho 0,62 and a 10 ms hold`() {
        // #124 run 3 analysis: t99 = ln(100ρ) / (μ(1 − ρ)) ≈ 109 ms at ρ = 0.62, μ = 100/s.
        FocusedReport.waitP99Ms(0.62, 10.0)!! shouldBe (108.6 plusOrMinus 0.1)
        FocusedReport.waitP99Ms(1.0, 10.0).shouldBeNull()
        FocusedReport.waitP99Ms(0.005, 10.0) shouldBe 0.0
    }

    @Test
    fun `a gate population's unshared part leaves its cohort shares out`() {
        val shared = UUID.randomUUID()
        val other = UUID.randomUUID()
        val population =
            SeededPopulation(
                1,
                listOf(SeededInbox(other, UUID.randomUUID(), "a"), SeededInbox(shared, UUID.randomUUID(), "b")),
                setOf(shared),
            )
        population.unshared.workspaceIds shouldBe listOf(other)
        population.unshared.workspaceCount shouldBe 1
        SeededPopulation(1, population.inboxes).let { it.unshared shouldBe it }
    }

    private companion object {
        const val TAG = 32
        const val LOCK = "SELECT pg_advisory_xact_lock(?, ?)"
        const val SNAPSHOT = "WITH ws AS (SELECT 1) SELECT 'GLOBAL' AS scope, NULL::uuid AS id"
        const val INSERT = "INSERT INTO storage_reservation (message_id) VALUES (?);\nCOMMIT"
        const val T2_INSERT = "INSERT INTO message (id) VALUES (?)"
    }
}
