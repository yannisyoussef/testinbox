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

    @Test
    fun `the newest observation is the walk begun last, aged by the database clock`() {
        val first = db.beginObservation()
        val second = db.beginObservation()
        db.observe(trashBytes = 0, startedSeq = second, availBytes = 9)
        // A late-arriving measurement whose walk began earlier: never the newest.
        db.observe(trashBytes = 0, startedSeq = first, availBytes = 1)

        val newest = checkNotNull(JdbcFilesystemObservations(db.jdbc).snapshot().newest)
        newest.availBytes shouldBe 9
        newest.inodesFree shouldBe 100
        (newest.age >= Duration.ZERO && newest.age < Duration.ofSeconds(30)) shouldBe true
    }

    @Test
    fun `an observation is aged from when its walk BEGAN, and its free inodes are total minus used`() {
        val walk = db.beginObservation()
        // A walk that began an hour ago (the server stamped its start; the test backdates it).
        db.jdbc
            .sql("UPDATE storage_observation_walk SET started_at = started_at - interval '1 hour' WHERE started_seq = ?")
            .param(walk)
            .update()
        val startedAt =
            db.jdbc
                .sql("SELECT started_at FROM storage_observation_walk WHERE started_seq = ?")
                .param(walk)
                .query(java.time.OffsetDateTime::class.java)
                .single()
                .toInstant()
        db.observe(trashBytes = 0, startedSeq = walk, availBytes = 7, inodesUsed = 40)

        // Written just now, began an hour ago: the age is the hour.
        val newest = checkNotNull(JdbcFilesystemObservations(db.jdbc).snapshot().newest)
        (newest.age > Duration.ofMinutes(59)) shouldBe true
        newest.startedAt shouldBe startedAt
        newest.inodesFree shouldBe 60
    }
}
