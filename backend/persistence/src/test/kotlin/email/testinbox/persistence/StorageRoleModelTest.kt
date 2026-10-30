package email.testinbox.persistence

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.testcontainers.utility.MountableFile
import java.io.File
import java.util.UUID

/**
 * The reference production role model (`deploy/database/roles.reference.sql`),
 * proven on PostgreSQL with V1–V10 (Ops finding 3, TI-STORAGE-006E).
 *
 * The test applies the reference grants as the schema owner, then:
 * - runs the deployables' storage operations as the roles it creates;
 * - shows that the writes the containment proof forbids are refused;
 * - runs activation gate F's own privilege query from
 *   `scripts/check-storage-activation.sh`, which must report **no**
 *   violation, and the monitor as the only observation writer.
 *
 * A blanket "DML on all tables", which an earlier Ops role script granted,
 * fails that last step.
 */
class StorageRoleModelTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private val suffix = UUID.randomUUID().toString().take(8)
    private val api = "ti_api_$suffix"
    private val ingestion = "ti_ingest_$suffix"
    private val monitor = "ti_monitor_$suffix"

    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .first { it.isFile }

    private fun LedgerTestDatabase.login(role: String): JdbcClient {
        jdbc.sql("CREATE ROLE $role LOGIN PASSWORD 'pw-$role'").update()
        jdbc.sql("GRANT CONNECT ON DATABASE $name TO $role").update()
        return JdbcClient.create(
            SimpleDriverDataSource(org.postgresql.Driver(), postgres.jdbcUrl.substringBeforeLast('/') + "/" + name, role, "pw-$role"),
        )
    }

    private fun LedgerTestDatabase.applyReferenceGrants() {
        val sql =
            repoFile("deploy/database/roles.reference.sql")
                .readLines()
                .filterNot { it.startsWith("\\") }
                .joinToString("\n")
                .replace(":\"api_role\"", "\"$api\"")
                .replace(":\"ingestion_role\"", "\"$ingestion\"")
                .replace(":\"monitor_role\"", "\"$monitor\"")
                .replace(":'api_role'", "'$api'")
                .replace(":'ingestion_role'", "'$ingestion'")
                .replace(":'monitor_role'", "'$monitor'")
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }

    /**
     * Runs the reference file exactly as Ops does: psql, `-v` role variables,
     * ON_ERROR_STOP, as the schema owner. The JDBC path above strips psql meta
     * commands; this one proves the shipped file, guard included.
     */
    private fun LedgerTestDatabase.psqlReference(
        apiRole: String,
        ingestionRole: String,
        monitorRole: String,
    ): org.testcontainers.containers.Container.ExecResult {
        val inContainer = "/tmp/roles.reference.$suffix.sql"
        postgres.copyFileToContainer(MountableFile.forHostPath(repoFile("deploy/database/roles.reference.sql").path), inContainer)
        return postgres.execInContainer(
            "psql",
            "-X",
            "-q",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            postgres.username,
            "-d",
            name,
            "-v",
            "api_role=$apiRole",
            "-v",
            "ingestion_role=$ingestionRole",
            "-v",
            "monitor_role=$monitorRole",
            "-f",
            inContainer,
        )
    }

    private fun LedgerTestDatabase.can(
        role: String,
        privilege: String,
        table: String,
    ): Boolean =
        jdbc
            .sql("SELECT has_table_privilege(?, ?, ?)")
            .params(role, "public.$table", privilege)
            .query(Boolean::class.javaObjectType)
            .single()

    /** Gate F's state statement, read from the script itself, for the given application roles. */
    private fun LedgerTestDatabase.gateFState(appRoles: String): String {
        val script = repoFile("scripts/check-storage-activation.sh").readText()
        val sql =
            script
                .substringAfter("FOOTPRINT_STATE_SQL=\"\$(cat <<'SQL'\n")
                .substringBefore("\nSQL\n)\"")
                .replace(":'app_roles'", "'$appRoles'")
        return jdbc.sql(sql).query(String::class.java).single()
    }

    private fun failure(block: () -> Unit): String {
        val e = runCatching(block).exceptionOrNull() ?: error("expected a refusal")
        return generateSequence(e) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }

    @Test
    fun `the reference grants run every storage operation of the deployables, refuse the forbidden writes, and pass gate F`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val asApi = db.login(api)
        val asIngestion = db.login(ingestion)
        val asMonitor = db.login(monitor)
        // An earlier role script's leftovers - a blanket DML grant and a TRUNCATE - are cleared by the clean slate.
        db.jdbc.sql("GRANT USAGE ON SCHEMA public TO $api").update()
        db.jdbc.sql("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA public TO $api").update()
        db.applyReferenceGrants()
        db.applyReferenceGrants() // re-run after every migration

        for (runtime in listOf(asApi, asIngestion)) {
            for (table in listOf("storage_cohort_plan", "storage_legacy_share", "workspace_deletion_charge")) {
                failure { runtime.sql("DELETE FROM $table WHERE false").update() } shouldContain "permission denied"
                failure { runtime.sql("INSERT INTO $table DEFAULT VALUES").update() } shouldContain "permission denied"
            }
            failure { runtime.sql("SELECT storage_release_workspace_charges()").query().listOfRows() } shouldContain "permission denied"
        }
        // Ingestion: commits mail (the ledger and debt triggers run as their owner), registers
        // and heartbeats its node, records a refusal, and runs the probe pair.
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 1_000, attachments = listOf(10), connection = asIngestion)
        val nodes = JdbcStorageAmbiguity(asIngestion, db.transactions)
        val generation = UUID.randomUUID()
        nodes.registerGeneration("ing-1", generation, "storage-v1")
        nodes.heartbeat("ing-1", generation, "storage-v1") shouldBe true // an existing generation is updated
        asIngestion
            .sql(
                "INSERT INTO inbox_storage (inbox_id, workspace_id, refusal_count, last_refusal_at, last_refusal_reason) " +
                    "VALUES (?, ?, 1, now(), 'INBOX_LIMIT') ON CONFLICT (inbox_id) DO UPDATE SET refusal_count = inbox_storage.refusal_count + 1",
            ).params(inbox, ws)
            .update() shouldBe 1
        asIngestion.sql("SELECT storage_record_probe_debt('_probe/ing-1/1')").query().listOfRows()
        asIngestion.sql("SELECT storage_resolve_probe_debt('_probe/ing-1/1')").query().listOfRows()

        // API: compaction, repair, the trust verifier, the debt functions and the sweep record.
        val ledger = JdbcStorageLedger(asApi, db.transactions)
        ledger.compact(100).foldedRows shouldBe 2
        ledger.repairDrift() shouldBe emptyList()
        ledger.confirmTrust() shouldBe true
        ledger.compactDeletionDebt()
        asApi.sql("SELECT storage_record_pending_debt('ws/in/m/raw.eml', 10, 1, 'test')").query().listOfRows()
        asApi.sql("SELECT storage_resolve_pending_debt('ws/in/m/raw.eml')").query().listOfRows()
        val runs = JdbcSweepRuns(asApi, "api-1")
        runs.complete(runs.begin(), 1_000)

        // Monitor: one observation, server-ordered.
        val walk = asMonitor.sql("SELECT storage_begin_observation()").query(Long::class.java).single()
        asMonitor
            .sql(
                "INSERT INTO storage_filesystem_observation (started_seq, source, block_size_bytes, capacity_bytes, used_bytes, " +
                    "avail_bytes, inodes_total, inodes_used, trash_bytes, minio_sys_bytes) VALUES (?, 'monitor', 4096, 1, 0, 1, 1, 0, 0, 0)",
            ).param(walk)
            .update() shouldBe 1

        // The writes the containment proof forbids.
        for (role in listOf(asApi, asIngestion)) {
            failure {
                role
                    .sql(
                        "INSERT INTO storage_delta (workspace_id, bytes, objects) VALUES (?, -1, -1)",
                    ).param(ws)
                    .update()
            } shouldContain
                "permission denied"
            failure { role.sql("UPDATE workspace_storage_account SET base_objects = 0").update() } shouldContain "permission denied"
            failure { role.sql("UPDATE inbox_storage SET base_bytes = 0").update() } shouldContain "permission denied"
            failure { role.sql("UPDATE storage_footprint_trust SET trusted_epoch = 0").update() } shouldContain "permission denied"
            failure { role.sql("DELETE FROM storage_deletion_debt").update() } shouldContain "permission denied"
            failure { role.sql("TRUNCATE storage_reservation").update() } shouldContain "permission denied"
            failure { role.sql("SELECT setval('storage_debt_order_seq', 1)").query().listOfRows() } shouldContain "permission denied"
            failure { role.sql("SELECT storage_begin_observation()").query().listOfRows() } shouldContain "permission denied"
        }
        // The fail-closed latch: a deployable sets it, only an operator clears it.
        JdbcStorageAmbiguity(asIngestion, db.transactions).latch("role-model test")
        for (role in listOf(asApi, asIngestion)) {
            failure { role.sql("DELETE FROM storage_admission_latch").update() } shouldContain "permission denied"
            failure { role.sql("UPDATE storage_admission_latch SET reason = 'x'").update() } shouldContain "permission denied"
        }
        // The internet-facing role holds no general debt function and no ledger maintenance.
        failure { asIngestion.sql("SELECT storage_record_pending_debt('k', 1, 1, 'x')").query().listOfRows() } shouldContain
            "permission denied"
        failure { asIngestion.sql("SELECT storage_compact_ledger(1)").query().listOfRows() } shouldContain "permission denied"

        // Activation gate F's own privilege query: nothing to report.
        val state = db.gateFState("$api,$ingestion").replace(" ", "")
        state shouldContain "\"roleViolations\":[]"
        state shouldContain "\"observationInserters\":[\"$monitor\"]"
        state shouldContain "\"beginObservationExecutors\":[\"$monitor\"]"
    }

    @Test
    fun `ingestion cannot read or mutate human identity metadata while the API can authenticate and budget sessions`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        val smtp = db.login(ingestion)
        db.login(monitor)
        db.applyReferenceGrants()
        val account =
            email.testinbox.domain.identity
                .HumanAccountId(UUID.randomUUID())
        db.jdbc
            .sql("INSERT INTO human_account VALUES (:id,'role-fixture@example.invalid',now(),'ACTIVE',now())")
            .param("id", account.value)
            .update()
        // Use the SAME role-bound DataSource for JDBC and transactions; an admin
        // transaction around another role's autocommit calls would be a false proof.
        val source =
            SimpleDriverDataSource(org.postgresql.Driver(), postgres.jdbcUrl.substringBeforeLast('/') + "/" + db.name, api, "pw-$api")
        val client = JdbcClient.create(source)
        val tx =
            SpringTransactionRunner(
                org.springframework.jdbc.datasource
                    .DataSourceTransactionManager(source),
            )
        val sessions = JdbcHumanSessions(client)
        val issued =
            checkNotNull(
                email.testinbox.application.usecase
                    .IssueHumanSession(sessions, tx, java.time.Clock.systemUTC())
                    .execute(account),
            )
        val authenticate =
            email.testinbox.application.usecase
                .AuthenticateHumanSession(sessions, tx, java.time.Clock.systemUTC())
        authenticate.execute(issued.credential.value)!!.account.id shouldBe account
        val charge =
            email.testinbox.application.usecase
                .ChargeAccountRequest(authenticate, JdbcAccountRequestBudgets(client), tx)
        charge.execute(issued.credential.value)!!.allowed shouldBe true
        db.jdbc.sql("UPDATE beta_cohort SET admissions_open=true,capacity_ready=true").update()
        val admission =
            email.testinbox.application.usecase
                .AdmitBetaParticipant(
                    JdbcBetaAdmissions(client),
                    tx,
                    java.time.Clock.systemUTC(),
                    email.testinbox.application.storage
                        .CohortStorageReadiness { true },
                ).execute(account) as email.testinbox.application.usecase.AdmitBetaParticipant.Result.Admitted
        client
            .sql("SELECT count(*) FROM participant_workspace WHERE workspace_id=?")
            .param(admission.admission.workspaceId.value)
            .query(Long::class.java)
            .single() shouldBe 1
        for (role in listOf(client, smtp)) {
            failure { role.sql("DELETE FROM participant_workspace WHERE false").update() } shouldContain "permission denied"
            failure { role.sql("UPDATE participant_workspace SET place=place WHERE false").update() } shouldContain "permission denied"
        }
        failure { smtp.sql("INSERT INTO participant_workspace DEFAULT VALUES").update() } shouldContain "permission denied"

        val tables =
            listOf(
                "api_key",
                "idempotency_record",
                "human_account",
                "human_identity",
                "beta_cohort",
                "beta_admission",
                "human_session",
                "email_login_challenge",
                "human_auth_gate",
                "provider_login_state",
                "beta_waitlist",
                "human_request_global",
                "human_request_budget",
            )
        tables.forEach { table ->
            failure { smtp.sql("SELECT * FROM $table").query().listOfRows() } shouldContain "permission denied"
            failure { smtp.sql("DELETE FROM $table WHERE false").update() } shouldContain "permission denied"
        }
        failure { smtp.sql("UPDATE human_account SET state='SUSPENDED' WHERE false").update() } shouldContain "permission denied"
        failure { smtp.sql("UPDATE beta_cohort SET admissions_open=true WHERE false").update() } shouldContain "permission denied"
        client
            .sql("SELECT count(*) FROM human_account WHERE id=:id")
            .param("id", account.value)
            .query(Long::class.javaObjectType)
            .single() shouldBe 1
    }

    @Test
    fun `under psql the shipped file isolates a distinct ingestion role and keeps the API's access`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        db.login(ingestion)
        db.login(monitor)
        val run = db.psqlReference(api, ingestion, monitor)
        run.exitCode shouldBe 0
        db.can(api, "SELECT", "api_key") shouldBe true
        db.can(api, "INSERT", "idempotency_record") shouldBe true
        db.can(api, "SELECT", "human_account") shouldBe true
        db.can(ingestion, "SELECT", "api_key") shouldBe false
        db.can(ingestion, "SELECT", "human_session") shouldBe false
        db.can(ingestion, "INSERT", "message") shouldBe true
    }

    @Test
    fun `a role layout the file cannot express is refused before any grant changes`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        db.login(ingestion)
        db.login(monitor)
        db.psqlReference(api, ingestion, monitor).exitCode shouldBe 0
        val member = "ti_member_$suffix"
        db.login(member)
        db.jdbc.sql("GRANT \"$api\" TO \"$member\"").update()
        val reader = "ti_reader_$suffix"
        db.login(reader)
        db.jdbc.sql("GRANT pg_read_all_data TO \"$reader\"").update()

        val refused =
            mapOf(
                "identical API and ingestion (the shared production role)" to Triple(api, api, monitor),
                "identical ingestion and monitor" to Triple(api, ingestion, ingestion),
                "a role that does not exist" to Triple(api, "ti_absent_$suffix", monitor),
                "the owner running the file" to Triple(api, postgres.username, monitor),
                "an ingestion role that can become the API role" to Triple(api, member, monitor),
                "an ingestion role that reads all data" to Triple(api, reader, monitor),
            )
        refused.forEach { (case, roles) ->
            val run = db.psqlReference(roles.first, roles.second, roles.third)
            withClue(case) {
                (run.exitCode != 0) shouldBe true
                run.stderr shouldContain "nothing was changed"
                // The clean slate never ran: the grants of the last good run are intact.
                db.can(api, "SELECT", "api_key") shouldBe true
                db.can(ingestion, "INSERT", "message") shouldBe true
                db.can(ingestion, "SELECT", "api_key") shouldBe false
            }
        }
    }

    @Test
    fun `a blanket DML grant on every table - the earlier Ops role script - is exactly what gate F refuses`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        db.jdbc.sql("GRANT USAGE ON SCHEMA public TO $api").update()
        db.jdbc.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $api").update()

        val state = db.gateFState(api).replace(" ", "")
        state shouldContain "$api:INSERTonstorage_delta".replace(" ", "")
        state shouldContain "$api:UPDATEonstorage_footprint_trust.trusted_epoch".replace(" ", "")
        state shouldContain "$api:INSERTonstorage_filesystem_observation".replace(" ", "")
        state shouldContain "$api:INSERTonworkspace_deletion_charge"
        state shouldContain "$api:UPDATEonstorage_cohort_plan"
        state shouldContain "$api:DELETEonstorage_legacy_share"
        state shouldContain "$api:DELETEonparticipant_workspace"
    }
}
