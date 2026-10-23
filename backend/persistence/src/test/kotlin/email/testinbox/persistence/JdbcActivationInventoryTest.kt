package email.testinbox.persistence

import email.testinbox.application.storage.activation.SessionAllowlist
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.util.UUID

/**
 * ADR-035 §14 (a) under the application role's OWN privileges: the barrier
 * reads every client backend's `application_name` from `pg_stat_activity`
 * without `pg_read_all_stats`, and the node inventory from `storage_node`.
 */
class JdbcActivationInventoryTest : PersistenceIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun `sessions are read by application name, including this test's own pool`() {
        val db = LedgerTestDatabase.create(postgres, jdbc)
        val inventory = JdbcActivationInventory(db.jdbc)
        // A dedicated session with a name the allowlist accepts, and one it does not.
        val named = JdbcClient.create(namedDataSource(db, "testinbox-ingestion:inv-test:storage-v1"))
        named.sql("SELECT 1").query(Int::class.java).single()
        val legacy = JdbcClient.create(namedDataSource(db, "testinbox-listen"))
        legacy.sql("SELECT 1").query(Int::class.java).single()

        val sessions = inventory.sessions()

        sessions.shouldNotBeEmpty()
        sessions.map { it.applicationName } shouldContain "testinbox-ingestion:inv-test:storage-v1"
        sessions.map { it.applicationName } shouldContain "testinbox-listen"
        // The role is visible too, so a report can say whose session it was.
        sessions.first { it.applicationName == "testinbox-listen" }.role shouldBe postgres.username
        SessionAllowlist.violations(sessions).map { it.applicationName } shouldContain "testinbox-listen"
    }

    @Test
    fun `a plain LOGIN role with no statistics privilege sees exactly its own role's sessions - the set §14 (a) allowlists`() {
        // TI-STORAGE-006 §17: the barrier needs no pg_monitor / pg_read_all_stats. Verified
        // behaviour on PostgreSQL 16: other roles' sessions come back with their identifying
        // columns hidden and fall out of the query; the role's own sessions are complete.
        val db = LedgerTestDatabase.create(postgres, jdbc)
        val role =
            "inv_" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        db.jdbc.sql("CREATE ROLE $role LOGIN PASSWORD 'fixture-not-a-real-role-password'").update()
        db.jdbc.sql("GRANT CONNECT ON DATABASE \"${db.name}\" TO $role").update()
        db.jdbc.sql("GRANT SELECT ON storage_node TO $role").update()
        val owner = JdbcClient.create(namedDataSource(db, "testinbox-ingestion:owner-session:storage-v1"))
        owner.sql("SELECT 1").query(Int::class.java).single() // a session of ANOTHER role, kept open by its pool
        val restricted =
            com.zaxxer.hikari.HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl.substringBeforeLast('/') + "/" + db.name
                username = role
                password = "fixture-not-a-real-role-password"
                maximumPoolSize = 2
                addDataSourceProperty("ApplicationName", "testinbox-ingestion:restricted:storage-v1")
            }
        val restrictedJdbc = JdbcClient.create(restricted)
        val inventory = JdbcActivationInventory(restrictedJdbc)
        inventory.applicationRole() shouldBe role
        val sessions = inventory.sessions()
        sessions.map { it.applicationName } shouldContain "testinbox-ingestion:restricted:storage-v1"
        sessions.all { it.role == role } shouldBe true // the owner's session is not in the restricted view
        SessionAllowlist.violations(sessions, role).isEmpty() shouldBe true
        inventory.nodes() // readable with a plain SELECT grant
        restricted.close()
    }

    @Test
    fun `the node inventory and the database clock are read as the barrier needs them`() {
        val db = LedgerTestDatabase.create(postgres, jdbc)
        val inventory = JdbcActivationInventory(db.jdbc)
        val ambiguity = JdbcStorageAmbiguity(db.jdbc, db.transactions)
        val live = UUID.randomUUID()
        val gone = UUID.randomUUID()
        ambiguity.registerGeneration("api-1", live, "storage-v1")
        ambiguity.registerGeneration("ingest-1", gone, "storage-v1")
        ambiguity.markCleanShutdown("ingest-1", gone)
        db.jdbc
            .sql(
                "INSERT INTO storage_node (node_id, generation, capability, heartbeat_at, clean_shutdown) VALUES ('old-1', :g, 'storage-v0', now() - interval '10 minutes', false)",
            ).param("g", UUID.randomUUID())
            .update()

        val rows = inventory.nodes()
        val now = inventory.now()

        rows.map { it.nodeId }.toSet() shouldBe setOf("api-1", "ingest-1", "old-1")
        rows.first { it.nodeId == "ingest-1" }.cleanShutdown shouldBe true
        rows.first { it.nodeId == "old-1" }.capability shouldBe "storage-v0"
        (Duration.between(rows.first { it.nodeId == "api-1" }.heartbeatAt, now) < Duration.ofMinutes(1)) shouldBe true
        (Duration.between(rows.first { it.nodeId == "old-1" }.heartbeatAt, now) > Duration.ofMinutes(9)) shouldBe true
    }

    @Test
    fun `reserved bytes sum every reservation whatever its state (the reserved half of covered)`() {
        val db = LedgerTestDatabase.create(postgres, jdbc)
        val reservations = JdbcStorageReservations(db.jdbc, db.transactions)
        reservations.reservedBytes() shouldBe 0L
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        for ((state, bytes) in listOf("RESERVED" to 1_000L, "RELEASING" to 24L)) {
            db.jdbc
                .sql(
                    """
                    INSERT INTO storage_reservation (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at,
                                                     write_deadline_at, release_not_before, node_id, generation)
                    VALUES (:id, :ws, :inbox, ARRAY['k'], :bytes, :state, now(), now() + interval '2 minutes', now() + interval '2 minutes', 'n', :g)
                    """.trimIndent(),
                ).param("id", UUID.randomUUID())
                .param("ws", ws)
                .param("inbox", inbox)
                .param("bytes", bytes)
                .param("state", state)
                .param("g", UUID.randomUUID())
                .update()
        }
        reservations.reservedBytes() shouldBe 1_024L
    }

    private fun namedDataSource(
        db: LedgerTestDatabase,
        applicationName: String,
    ) = com.zaxxer.hikari.HikariDataSource().apply {
        jdbcUrl = postgres.jdbcUrl.substringBeforeLast('/') + "/" + db.name
        username = postgres.username
        password = postgres.password
        maximumPoolSize = 1
        minimumIdle = 1
        addDataSourceProperty("ApplicationName", applicationName)
    }
}
