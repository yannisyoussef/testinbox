package email.testinbox.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration

/**
 * The `STORAGE_FULL` evidence source (filesystem-containment contract §8,
 * TI-STORAGE-006E): the newest observation by `started_at`, aged by the
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

    @Test
    fun `the newest observation is the latest start, aged by the database clock`() {
        db.observe(trashBytes = 0, availBytes = 5, startedAt = db.dbNow().minusSeconds(600))
        db.observe(trashBytes = 0, availBytes = 9, startedAt = db.dbNow())
        db.observe(trashBytes = 0, availBytes = 1, startedAt = db.dbNow().minusSeconds(7_200)) // a late, old measurement

        val newest = checkNotNull(JdbcFilesystemObservations(db.jdbc).snapshot().newest)
        newest.availBytes shouldBe 9
        newest.inodesFree shouldBe 0
        (newest.age >= Duration.ZERO && newest.age < Duration.ofSeconds(30)) shouldBe true
    }

    @Test
    fun `an observation is aged from when it BEGAN, and its free inodes are total minus used`() {
        val startedAt = db.dbNow().minusSeconds(3_600)
        db.jdbc
            .sql(
                """
                INSERT INTO storage_filesystem_observation
                    (started_at, observed_at, source, block_size_bytes, capacity_bytes, used_bytes, avail_bytes,
                     inodes_total, inodes_used, trash_bytes, minio_sys_bytes)
                VALUES (?, ?, 'test-monitor', 4096, 0, 0, 7, 100, 40, 0, 0)
                """.trimIndent(),
            ).params(Timestamps.toDb(startedAt), Timestamps.toDb(startedAt.plusSeconds(3_000)))
            .update()

        // Finished 10 minutes ago, began an hour ago: the age is the hour.
        val newest = checkNotNull(JdbcFilesystemObservations(db.jdbc).snapshot().newest)
        (newest.age > Duration.ofMinutes(59)) shouldBe true
        newest.startedAt shouldBe startedAt
        newest.inodesFree shouldBe 60
    }
}
