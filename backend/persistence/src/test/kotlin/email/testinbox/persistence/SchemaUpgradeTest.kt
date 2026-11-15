package email.testinbox.persistence

import email.testinbox.application.deployment.SchemaCompatibility
import email.testinbox.application.deployment.SchemaVersion
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-029 §6 / TI-DEPLOY-001 §24: a migration must be proven against the
 * *previous* schema carrying data, not only against an empty database.
 *
 * Testing only the empty case would pass for a migration that silently drops
 * every existing row, or that adds a NOT NULL column with no default — the two
 * failure modes that actually break a deployment.
 *
 * Each case runs on its own freshly created database on the shared container,
 * so the upgrade starts from a genuinely empty catalogue rather than from
 * whatever the Spring context already migrated.
 */
class SchemaUpgradeTest : PersistenceIntegrationTest() {
    @Autowired lateinit var shared: JdbcClient

    private fun freshDatabase(): DataSource {
        val name = "upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        shared.sql("CREATE DATABASE $name").update()
        return SimpleDriverDataSource(
            org.postgresql.Driver(),
            postgres.jdbcUrl.substringBeforeLast('/') + "/" + name,
            postgres.username,
            postgres.password,
        )
    }

    private fun flyway(
        dataSource: DataSource,
        target: String? = null,
    ): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .let { if (target != null) it.target(target) else it }
            .load()

    /** Rows a V1 deployment would already be holding when the upgrade lands. */
    private fun seedV1Data(jdbc: JdbcClient): Triple<UUID, UUID, UUID> {
        val workspace = UUID.randomUUID()
        val project = UUID.randomUUID()
        val inbox = UUID.randomUUID()
        val message = UUID.randomUUID()
        jdbc.sql("INSERT INTO workspace (id, name, created_at) VALUES (?, 'legacy', now())").param(workspace).update()
        jdbc
            .sql("INSERT INTO project (id, workspace_id, name, created_at) VALUES (?, ?, 'legacy', now())")
            .params(project, workspace)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at)
                VALUES (?, ?, ?, 'legacy@testinbox.local', 'GENERATED', 'ACTIVE', now(), now() + interval '1 hour')
                """.trimIndent(),
            ).params(inbox, workspace, project)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO message (
                    id, workspace_id, inbox_id, received_at, provider, provider_message_id,
                    envelope_from, envelope_to, raw_object_key, raw_size_bytes,
                    content_fingerprint, parse_status
                ) VALUES (?, ?, ?, now(), 'ses', 'evt-1', 'sut@example.com',
                          'legacy@testinbox.local', 'k/raw.eml', 10, 'fp', 'OK')
                """.trimIndent(),
            ).params(message, workspace, inbox)
            .update()
        return Triple(workspace, inbox, message)
    }

    @Test
    fun `data written under V1 survives the upgrade to the current schema`() {
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)

        flyway(dataSource, target = "1").migrate()
        val (workspace, inbox, message) = seedV1Data(jdbc)

        val result = flyway(dataSource).migrate()
        result.success shouldBe true
        // V1 was already applied; the upgrade applies only what came after it.
        result.migrationsExecuted shouldBe (BundledMigrations.versions().size - 1)

        jdbc
            .sql("SELECT count(*) FROM workspace WHERE id = ?")
            .param(workspace)
            .query(Int::class.java)
            .single() shouldBe 1
        jdbc
            .sql("SELECT count(*) FROM inbox WHERE id = ?")
            .param(inbox)
            .query(Int::class.java)
            .single() shouldBe 1
        jdbc
            .sql("SELECT provider_message_id FROM message WHERE id = ?")
            .param(message)
            .query(String::class.java)
            .single() shouldBe "evt-1"
    }

    @Test
    fun `the upgraded schema carries the ADR-026 recipient-scoped delivery index, not the V1 one`() {
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)
        flyway(dataSource, target = "1").migrate()
        seedV1Data(jdbc)
        flyway(dataSource).migrate()

        val indexes =
            jdbc
                .sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
                .query(String::class.java)
                .list()
        indexes shouldContainAll listOf("ux_message_provider_delivery")
        indexes.contains("ux_message_provider_event") shouldBe false
    }

    @Test
    fun `a node bundling the current migrations reports ready only once the upgrade has run`() {
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)
        val bundled = BundledMigrations.highest()!!
        val compatibility = SchemaCompatibility(JdbcSchemaHistory(jdbc), bundled)

        // ADR-029 §4: nothing migrated yet — the history table does not exist.
        compatibility.status().compatible shouldBe false
        compatibility.status().detail shouldContain "have not run"

        flyway(dataSource, target = "1").migrate()
        val atV1 = compatibility.status()
        atV1.compatible shouldBe false
        atV1.applied shouldBe "1"

        flyway(dataSource).migrate()
        val upgraded = compatibility.status()
        upgraded.compatible shouldBe true
        upgraded.applied shouldBe bundled.raw
    }

    @Test
    fun `the application layer reads and writes an upgraded schema, not just raw SQL`() {
        // TI-DEPLOY-001 §24 asks that the application *works* after the
        // upgrade, not merely that Flyway ran. The assertions above are raw
        // `count(*)`: they would pass against a schema whose new unique index
        // (ADR-026) or new columns the repositories cannot actually use. This
        // drives the same repository code the deployed artifact runs.
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)
        flyway(dataSource, target = "1").migrate()
        val (workspace, inboxId, messageId) = seedV1Data(jdbc)
        flyway(dataSource).migrate()

        val inboxes = JdbcInboxRepository(jdbc)
        val messages = JdbcMessageRepository(jdbc)

        // Read data written under V1 through the current mapping.
        val legacy = messages.listVisible(InboxId(inboxId))
        legacy.map { it.id.value } shouldBe listOf(messageId)
        legacy.single().providerMessageId shouldBe "evt-1"

        val inbox = inboxes.findById(WorkspaceId(workspace), InboxId(inboxId))
        inbox?.address shouldBe "legacy@testinbox.local"

        // And write through it: this is what exercises the ADR-026
        // recipient-scoped unique index that V2 put in V1's place.
        val fresh =
            Fixtures.message(
                inbox = inbox!!,
                providerMessageId = "evt-2",
            )
        messages.appendVisible(fresh)
        messages.listVisible(InboxId(inboxId)).map { it.providerMessageId }.toSet() shouldBe setOf("evt-1", "evt-2")
    }

    @Test
    fun `every bundled migration is discoverable from the artifact`() {
        // If this scan silently found nothing, the compatibility policy would
        // read "no migrations bundled" and wave every schema through.
        BundledMigrations.versions().map { it.raw } shouldBe
            listOf(
                "1",
                "2",
                "3",
                "4",
                "5",
                "6",
                "7",
                "8",
                "9",
                "10",
                "11",
                "12",
                "13",
                "14",
                "15",
                "16",
                "17",
                "18",
                "19",
                "20",
                "21",
                "22",
                "23",
                "24",
                "25",
                "26",
                "27",
                "28",
            )
    }

    @Test
    fun `V27 gives the reservations a V26 schema already holds their stored object count and the covering index, and changes no row`() {
        // Gate D A′: expand-only. The reservations written before it keep every column; object_count is
        // computed by PostgreSQL from object_keys for them and for every later insert, including an older
        // artifact's, which does not name the column.
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)
        flyway(dataSource, target = "26").migrate()
        val (workspace, inbox, _) = seedV1Data(jdbc)

        fun reserve(keys: String) =
            jdbc
                .sql(
                    """
                    INSERT INTO storage_reservation (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at,
                                                     write_deadline_at, node_id, generation)
                    VALUES (gen_random_uuid(), ?, ?, string_to_array(?, ','), 10, 'RESERVED', now(), now() + interval '2 minutes', 'n',
                            gen_random_uuid())
                    """.trimIndent(),
                ).params(workspace, inbox, keys)
                .update()
        reserve("a")
        reserve("a,b,c")
        // object_keys as text: a driver array compares by identity.
        val columns =
            "message_id, workspace_id, inbox_id, object_keys::text, bytes, state, created_at, write_deadline_at, node_id, generation"
        val before = jdbc.sql("SELECT $columns FROM storage_reservation ORDER BY message_id").query().listOfRows()

        flyway(dataSource).migrate()

        jdbc.sql("SELECT $columns FROM storage_reservation ORDER BY message_id").query().listOfRows() shouldBe before
        reserve("x,y") // the V26-era insert, unchanged
        jdbc
            .sql("SELECT count(*) FROM storage_reservation WHERE object_count IS DISTINCT FROM cardinality(object_keys)")
            .query(Long::class.java)
            .single() shouldBe 0
        jdbc
            .sql("SELECT array_agg(object_count ORDER BY object_count) FROM storage_reservation")
            .query(String::class.java)
            .single() shouldBe "{1,2,3}"
        // Generated: no writer can set it to anything else.
        runCatching {
            jdbc.sql("UPDATE storage_reservation SET object_count = 7").update()
        }.exceptionOrNull().shouldNotBeNull()
        jdbc
            .sql(
                "SELECT is_generated || ':' || generation_expression FROM information_schema.columns WHERE table_name = 'storage_reservation' AND column_name = 'object_count'",
            ).query(String::class.java)
            .single() shouldBe "ALWAYS:cardinality(object_keys)"
        jdbc
            .sql("SELECT indexdef FROM pg_indexes WHERE tablename = 'storage_reservation' AND indexname = 'ix_storage_reservation_usage'")
            .query(String::class.java)
            .single() shouldBe
            "CREATE INDEX ix_storage_reservation_usage ON public.storage_reservation USING btree (workspace_id) INCLUDE (bytes, object_count)"
    }

    @Test
    fun `a V27 attempt blocked by a reader gives up within T1's own lock timeout, leaves V26 intact, and re-runs cleanly`() {
        // While V27 waits for ACCESS EXCLUSIVE, every later lock request on storage_reservation, T1's
        // reads included, queues behind it. Its lock_timeout is therefore the longest one attempt can hold
        // T1, and it must stay under T1's own bound. A failed attempt must roll back whole, with no failed
        // history row, so the migrator can simply be re-run with the same candidate.
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)
        flyway(dataSource, target = "26").migrate()

        val failure: Throwable
        val elapsed: Duration
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            // The weakest lock T1's reads take; it conflicts only with ACCESS EXCLUSIVE.
            holder.createStatement().use { it.execute("LOCK TABLE storage_reservation IN ACCESS SHARE MODE") }
            val started = System.nanoTime()
            failure = shouldThrow<FlywayException> { flyway(dataSource, target = "27").migrate() }
            elapsed = Duration.ofNanos(System.nanoTime() - started)
            holder.rollback()
        }

        generateSequence(failure) { it.cause }
            .filterIsInstance<SQLException>()
            .map { it.sqlState }
            .toList() shouldContain "55P03"
        elapsed shouldBeLessThan JdbcStorageAdmission.DEFAULT_LOCK_TIMEOUT
        jdbc
            .sql("SELECT count(*) FROM flyway_schema_history WHERE version = '27'")
            .query(Long::class.java)
            .single() shouldBe 0
        jdbc
            .sql(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'storage_reservation' AND column_name = 'object_count'",
            ).query(Long::class.java)
            .single() shouldBe 0
        jdbc
            .sql("SELECT to_regclass('public.ix_storage_reservation_usage') IS NULL")
            .query(Boolean::class.java)
            .single() shouldBe true

        flyway(dataSource, target = "27").migrate()

        jdbc
            .sql("SELECT success FROM flyway_schema_history WHERE version = '27'")
            .query(Boolean::class.java)
            .single() shouldBe true
        jdbc
            .sql("SELECT to_regclass('public.ix_storage_reservation_usage') IS NOT NULL")
            .query(Boolean::class.java)
            .single() shouldBe true
    }

    @Test
    fun `V26 indexes the deltas a V25 schema already holds, by workspace and by inbox, and changes no row`() {
        // Gate D A2: expand-only. The deltas written before it stay exactly as they were.
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)
        flyway(dataSource, target = "25").migrate()
        val (workspace, inbox, _) = seedV1Data(jdbc)
        jdbc
            .sql("INSERT INTO storage_delta (workspace_id, inbox_id, bytes, objects) VALUES (?, ?, 7, 1), (?, NULL, -3, 0)")
            .params(workspace, inbox, workspace)
            .update()
        val before =
            jdbc
                .sql("SELECT id, workspace_id, inbox_id, bytes, objects FROM storage_delta ORDER BY id")
                .query()
                .listOfRows()
        flyway(dataSource).migrate()
        jdbc
            .sql("SELECT id, workspace_id, inbox_id, bytes, objects FROM storage_delta ORDER BY id")
            .query()
            .listOfRows() shouldBe before
        jdbc
            .sql(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'storage_delta' AND indexname LIKE 'ix_storage_delta_%' ORDER BY indexname",
            ).query(String::class.java)
            .list() shouldBe
            listOf(
                "CREATE INDEX ix_storage_delta_inbox ON public.storage_delta USING btree (inbox_id) INCLUDE (bytes, objects)",
                "CREATE INDEX ix_storage_delta_workspace ON public.storage_delta USING btree (workspace_id) INCLUDE (bytes, objects)",
            )
    }

    @Test
    fun `V9 gives a pending debt row written before it a recorded_at, so the resolver can age it`() {
        // TI-STORAGE-006E PR D: expand-only. A pre-V9 artifact's pending rows (none are
        // written before PR D, but the column must not care) get the migration's time.
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)
        flyway(dataSource, target = "8").migrate()
        jdbc.sql("SELECT storage_record_pending_debt('k', 1, 1, 'pre-v9')").query().listOfRows()
        flyway(dataSource).migrate()
        jdbc
            .sql("SELECT count(*) FROM storage_deletion_debt WHERE object_key = 'k' AND recorded_at IS NOT NULL")
            .query(Long::class.java)
            .single() shouldBe 1
        jdbc.sql("SELECT storage_record_pending_debt('k2', 1, 1, 'post-v9')").query().listOfRows()
        jdbc
            .sql("SELECT recorded_at <= now() FROM storage_deletion_debt WHERE object_key = 'k2'")
            .query(Boolean::class.java)
            .single() shouldBe true
    }

    @Test
    fun `the V4 upgrade labels an existing api key as BOOTSTRAP rather than MANAGED`() {
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)
        flyway(dataSource, target = "3").migrate()
        val (workspace, _, _) = seedV1Data(jdbc)
        val keyId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO api_key (id, workspace_id, project_id, key_hash, scopes, created_at)
                VALUES (?, ?, (SELECT id FROM project LIMIT 1), 'legacy-hash',
                        ARRAY['inboxes:write','messages:read'], now())
                """.trimIndent(),
            ).params(keyId, workspace)
            .update()

        flyway(dataSource).migrate()

        // Every key that predates the management API is a bootstrap fixture —
        // that code had no other way to create one. Labelling it MANAGED would
        // make it permanently usable and would defeat ADR-032 §8 entirely.
        jdbc
            .sql("SELECT kind FROM api_key WHERE id = ?")
            .param(keyId)
            .query(String::class.java)
            .single() shouldBe "BOOTSTRAP"
    }

    @Test
    fun `an artifact that predates V4 can still insert an api key after the migration`() {
        // The ADR-029 rollback contract: a schema ahead of the artifact must
        // stay usable. Pre-TI-002 code writes no `kind` and no `public_id`, so
        // a DEFAULT of MANAGED would leave that insert violating the check
        // constraint and a rolled-back node would fail to start.
        val dataSource = freshDatabase()
        val jdbc = JdbcClient.create(dataSource)
        flyway(dataSource).migrate()
        val (workspace, _, _) = seedV1Data(jdbc)

        jdbc
            .sql(
                """
                INSERT INTO api_key (id, workspace_id, project_id, key_hash, scopes, created_at, revoked_at)
                VALUES (?, ?, (SELECT id FROM project LIMIT 1), 'old-artifact-hash',
                        ARRAY['inboxes:write'], now(), NULL)
                """.trimIndent(),
            ).params(UUID.randomUUID(), workspace)
            .update()

        jdbc
            .sql("SELECT count(*) FROM api_key WHERE key_hash = 'old-artifact-hash' AND kind = 'BOOTSTRAP'")
            .query(Int::class.java)
            .single() shouldBe 1
    }

    @Test
    fun `V19 backfills admitted participant shares without reclassifying the legacy owner`() {
        val source = freshDatabase()
        val jdbc = JdbcClient.create(source)
        flyway(source, target = "18").migrate()
        val (participant, _, _) = seedV1Data(jdbc)
        val owner = UUID.randomUUID()
        val account = UUID.randomUUID()
        jdbc.sql("INSERT INTO workspace (id,name,created_at) VALUES (?,'owner',now())").param(owner).update()
        jdbc.sql("INSERT INTO human_account VALUES (?,'upgrade@example.invalid',now(),'ACTIVE',now())").param(account).update()
        jdbc
            .sql(
                """
                INSERT INTO beta_admission (cohort_id,place,account_id,workspace_id,project_id,admitted_at)
                VALUES ('beta-30',1,?,?,(SELECT id FROM project WHERE workspace_id=? LIMIT 1),now())
                """.trimIndent(),
            ).params(account, participant, participant)
            .update()
        flyway(source).migrate()
        jdbc.sql("SELECT workspace_id FROM participant_workspace").query(UUID::class.java).single() shouldBe participant
        jdbc.sql("SELECT payload_limit_bytes FROM participant_workspace").query(Long::class.java).single() shouldBe
            email.testinbox.domain.storage.ParticipantStorageShare.PAYLOAD_BYTES
        jdbc.sql("SELECT footprint_limit_bytes FROM participant_workspace").query(Long::class.java).single() shouldBe
            email.testinbox.domain.storage.ParticipantStorageShare.FOOTPRINT_BYTES
    }

    @Test
    fun `V19 refuses unidentified prior tombstones rather than losing participant classification`() {
        val source = freshDatabase()
        val jdbc = JdbcClient.create(source)
        flyway(source, target = "18").migrate()
        jdbc.sql("INSERT INTO beta_admission (cohort_id,place,admitted_at) VALUES ('beta-30',1,now())").update()
        val failure = runCatching { flyway(source).migrate() }.exceptionOrNull() ?: error("unidentified participant must block upgrade")
        generateSequence(failure) { it.cause }.joinToString { it.message.orEmpty() } shouldContain "requires reconciliation"
        jdbc.sql("SELECT count(*) FROM beta_admission").query(Long::class.java).single() shouldBe 1
        jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version='19' AND success").query(Long::class.java).single() shouldBe 0
    }
}
