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
        (timings.holdNanos > 0) shouldBe true
        (timings.snapshotNanos > 0) shouldBe true
        (timings.insertNanos > 0) shouldBe true
        (timings.commitNanos > 0) shouldBe true
        (timings.snapshotNanos + timings.insertNanos + timings.commitNanos <= timings.holdNanos) shouldBe true
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
        (timings.holdNanos > 0) shouldBe true
        timings.insertNanos shouldBe 0L
    }

    @Test
    fun `the summary is null without holds, and its mean is the mean of the per-event holds`() {
        HoldSummary.of(listOf(EventTimings())).shouldBeNull()
        val events =
            listOf(2_000_000L, 4_000_000L).map { hold ->
                EventTimings().apply {
                    stepFinished(HoldTiming.Step.LOCK, 0, 1)
                    commitFinished(1, 1 + hold)
                }
            }
        val summary = HoldSummary.of(events)!!
        summary.meanHoldMs shouldBe (3.0 plusOrMinus 1e-9)
        summary.hold.samples shouldBe 2
        summary.snapshot.shouldBeNull()
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
