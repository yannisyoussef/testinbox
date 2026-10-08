package email.testinbox.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration

/**
 * The `STORAGE_FULL` evidence source (filesystem-containment contract §8,
 * TI-STORAGE-006E): the newest observation by `started_seq`, aged by the
 * database clock.
 */
class FilesystemObservationsTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
    }

    @Test
    fun `there is no evidence before the monitor has written anything`() {
        val snapshot = JdbcFilesystemObservations(db.jdbc).snapshot()
        snapshot.newest shouldBe null
        (Duration.between(snapshot.databaseNow, db.dbNow()).abs() < Duration.ofSeconds(30)) shouldBe true
    }

    private fun observe(
        availBytes: Long,
        startedSeq: Long = db.nextOrder(),
        startedAt: java.time.Instant = db.dbNow(),
        observedAt: java.time.Instant = startedAt,
        inodesUsed: Long = 0,
    ) {
        db.jdbc
            .sql(
                """
                INSERT INTO storage_filesystem_observation
                    (started_seq, started_at, observed_at, source, block_size_bytes, capacity_bytes, used_bytes, avail_bytes,
                     inodes_total, inodes_used, trash_bytes, minio_sys_bytes)
                VALUES (?, ?, ?, 'test-monitor', 4096, 0, 0, ?, 100, ?, 0, 0)
                """.trimIndent(),
            ).params(startedSeq, Timestamps.toDb(startedAt), Timestamps.toDb(observedAt), availBytes, inodesUsed)
            .update()
    }

    @Test
    fun `the newest observation is the one whose order was taken last, aged by the database clock`() {
        val first = db.nextOrder()
        val second = db.nextOrder()
        observe(availBytes = 9, startedSeq = second)
        // A late-arriving measurement that took its order earlier: never the newest,
        // whatever its clock says.
        observe(availBytes = 1, startedSeq = first, startedAt = db.dbNow())

        val newest = checkNotNull(JdbcFilesystemObservations(db.jdbc).snapshot().newest)
        newest.availBytes shouldBe 9
        newest.inodesFree shouldBe 100
        (newest.age >= Duration.ZERO && newest.age < Duration.ofSeconds(30)) shouldBe true
    }

    @Test
    fun `an observation is aged from when it BEGAN, and its free inodes are total minus used`() {
        val startedAt = db.dbNow().minusSeconds(3_600)
        observe(availBytes = 7, startedAt = startedAt, observedAt = startedAt.plusSeconds(3_000), inodesUsed = 40)

        // Finished 10 minutes ago, began an hour ago: the age is the hour.
        val newest = checkNotNull(JdbcFilesystemObservations(db.jdbc).snapshot().newest)
        (newest.age > Duration.ofMinutes(59)) shouldBe true
        newest.startedAt shouldBe startedAt
        newest.inodesFree shouldBe 60
    }
}
