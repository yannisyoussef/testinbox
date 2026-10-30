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

    /** One role-bound DataSource for JDBC and transactions alike (an admin transaction would be a false proof). */
    private fun LedgerTestDatabase.roleSource(role: String) =
        SimpleDriverDataSource(org.postgresql.Driver(), postgres.jdbcUrl.substringBeforeLast('/') + "/" + name, role, "pw-$role")

    /**
     * Every privilege [role] holds in `public`, per relation: table-level
     * privileges by name, column-level ones as `PRIV(col,...)`, sequences as
     * `USAGE`/`SELECT`/`UPDATE`. Relations it holds nothing on are absent.
     */
    private fun LedgerTestDatabase.effectivePrivileges(role: String): Map<String, Set<String>> =
        jdbc
            .sql(
                """
                WITH rel AS (
                    SELECT c.oid, c.relname, c.relkind FROM pg_class c
                     WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')),
                tab AS (
                    SELECT relname, p AS privilege FROM rel,
                           unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER']) p
                     WHERE relkind <> 'S' AND has_table_privilege(:role, oid, p)),
                col AS (
                    SELECT relname, p || '(' || string_agg(a.attname, ',' ORDER BY a.attname) || ')' AS privilege
                      FROM rel JOIN pg_attribute a ON a.attrelid = rel.oid AND a.attnum > 0 AND NOT a.attisdropped,
                           unnest(ARRAY['SELECT','INSERT','UPDATE','REFERENCES']) p
                     WHERE relkind <> 'S' AND NOT has_table_privilege(:role, rel.oid, p)
                       AND has_column_privilege(:role, rel.oid, a.attnum, p)
                     GROUP BY relname, p),
                seq AS (
                    SELECT relname, p AS privilege FROM rel, unnest(ARRAY['USAGE','SELECT','UPDATE']) p
                     WHERE relkind = 'S' AND has_sequence_privilege(:role, oid, p))
                SELECT relname, privilege FROM tab UNION ALL SELECT relname, privilege FROM col
                UNION ALL SELECT relname, privilege FROM seq
                """.trimIndent(),
            ).param("role", role)
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }
            .list()
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    /**
     * Every statement kind of SMTP delivery, as [role], through the real adapters
     * the ingestion context builds: schema history, recipient lookup, message and
     * attachment append, fingerprint lookup, the rate limiter, node registration,
     * ambiguity and latch, the inbox FOR SHARE lock, refusals, clock episodes,
     * T1 admission with the footprint and cohort snapshot, the footprint gate,
     * observations, the activation inventory and the probe pair. Returns the
     * role's client.
     */
    private fun LedgerTestDatabase.runDeliveryPathAs(role: String): JdbcClient {
        val source = roleSource(role)
        val smtp = JdbcClient.create(source)
        val txManager =
            org.springframework.jdbc.datasource
                .DataSourceTransactionManager(source)
        val tx =
            org.springframework.transaction.support
                .TransactionTemplate(txManager)
        val ws = workspace()
        val inbox = inbox(ws)
        val inboxId = email.testinbox.domain.InboxId(inbox)
        val workspaceId = email.testinbox.domain.WorkspaceId(ws)
        JdbcSchemaHistory(smtp).read()
        checkNotNull(JdbcInboxRepository(smtp).findReceivableByAddress("$inbox@ledger.test"))
        // Account lifecycle: the early eligibility read (a legacy workspace has no row).
        JdbcWorkspaceEligibility(smtp).status(workspaceId) shouldBe email.testinbox.application.port.WorkspaceStatus.LEGACY
        val delivered = checkNotNull(message(ws, inbox, rawBytes = 100, attachments = listOf(10), connection = smtp))
        JdbcMessageRepository(smtp).findEarliestIdByFingerprint(inboxId, "fingerprint-absent") shouldBe null
        JdbcRateLimiter(smtp, txManager) { _, _ ->
            email.testinbox.domain.limits
                .RatePolicy(capacity = 5, refillPerSecond = 1.0)
        }.tryConsume(workspaceId, email.testinbox.domain.limits.RateCategory.INGEST, inboxId)
            .allowed shouldBe true
        val ambiguity = JdbcStorageAmbiguity(smtp, tx)
        val generation = UUID.randomUUID()
        ambiguity.registerGeneration("ing-1", generation, "storage-v1")
        ambiguity.heartbeat("ing-1", generation, "storage-v1") shouldBe true
        ambiguity.record("ing-1", "$ws/$inbox/late/raw.eml", 10, java.time.Duration.ofSeconds(1))
        ambiguity.unresolvedTotal() shouldBe 1
        ambiguity.latched() shouldBe null
        val reservations = JdbcStorageReservations(smtp, tx)
        // FOR SHARE: the reason ingestion holds UPDATE (id) on inbox.
        tx.execute { reservations.lockInboxes(listOf(inboxId)) }!!.keys shouldBe setOf(inboxId)
        reservations.committedKeys(listOf(email.testinbox.domain.MessageId(delivered)))
        tx.executeWithoutResult {
            reservations.recordRefusals(mapOf(inboxId to email.testinbox.domain.storage.StorageRefusalReason.INBOX_LIMIT))
        }
        reservations.recordClockEpisode(java.time.Duration.ofSeconds(3))
        reservations.applyClockEpisode(java.time.Duration.ofSeconds(1))
        val reserved = email.testinbox.domain.MessageId(UUID.randomUUID())
        val now = java.time.Instant.now()
        JdbcStorageAdmission(smtp, tx, readsFootprint = true).admit(
            email.testinbox.application.port
                .StorageAdmissionScope(setOf(workspaceId), setOf(inboxId)),
        ) {
            email.testinbox.application.port.StorageAdmissionPlan(
                listOf(
                    email.testinbox.application.port.StorageReservationDraft(
                        reserved,
                        workspaceId,
                        inboxId,
                        listOf("$ws/$inbox/$reserved/raw.eml"),
                        10,
                        now,
                        now.plusSeconds(60),
                        "ing-1",
                        generation,
                    ),
                ),
                true,
            )
        } shouldBe true
        reservations.reservationExists(reserved) shouldBe true
        reservations.countsByState().values.sum() shouldBe 1
        reservations.expireOverdue(java.time.Duration.ofSeconds(1))
        JdbcFootprintGate(smtp).observe()
        JdbcFilesystemObservations(smtp).snapshot()
        JdbcActivationInventory(smtp).nodes().any { it.nodeId == "ing-1" } shouldBe true
        smtp.sql("SELECT storage_record_probe_debt('_probe/ing-1/1')").query().listOfRows()
        smtp.sql("SELECT storage_resolve_probe_debt('_probe/ing-1/1')").query().listOfRows()
        return smtp
    }

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
                "lifecycle_restore_clearance",
            )
        tables.forEach { table ->
            failure { smtp.sql("SELECT * FROM $table").query().listOfRows() } shouldContain "permission denied"
            failure { smtp.sql("DELETE FROM $table WHERE false").update() } shouldContain "permission denied"
        }
        failure { smtp.sql("UPDATE human_account SET state='SUSPENDED' WHERE false").update() } shouldContain "permission denied"
        // The lifecycle view gives SMTP exactly a workspace id and a boolean: no account, contact or quarantine detail.
        smtp
            .sql("SELECT * FROM workspace_lifecycle_eligibility WHERE workspace_id = ?")
            .param(admission.admission.workspaceId.value)
            .query { rs, _ -> (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) } to rs.getBoolean("active") }
            .single() shouldBe (listOf("workspace_id", "active") to true)
        failure { smtp.sql("INSERT INTO lifecycle_restore_clearance (id) VALUES (1)").update() } shouldContain "permission denied"
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
    fun `under psql ingestion holds exactly its delivery allow-list, runs the delivery path, reaches no new relation`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        db.login(ingestion)
        db.login(monitor)
        db.psqlReference(api, ingestion, monitor).exitCode shouldBe 0

        val smtp = db.runDeliveryPathAs(ingestion)

        // Exactly the allow-list: nothing else in public, no view, no other sequence.
        db.effectivePrivileges(ingestion) shouldBe INGESTION_ALLOW_LIST
        // Of the definer functions, the probe pair only.
        db.jdbc
            .sql(
                "SELECT p.oid::regprocedure::text FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace " +
                    "AND p.prosecdef AND has_function_privilege(?, p.oid, 'EXECUTE') ORDER BY 1",
            ).param(ingestion)
            .query(String::class.java)
            .list() shouldBe listOf("storage_record_probe_debt(text)", "storage_resolve_probe_debt(text)")

        // A relation created later - here a view over identity data, as the owner
        // with the API's default privileges - never reaches ingestion.
        db.jdbc.sql("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $api").update()
        db.jdbc
            .sql(
                "CREATE VIEW workspace_eligibility AS SELECT a.contact_email, b.workspace_id " +
                    "FROM human_account a JOIN beta_admission b ON b.account_id = a.id",
            ).update()
        db.jdbc.sql("CREATE TABLE later_table (id integer)").update()
        failure { smtp.sql("SELECT * FROM workspace_eligibility").query().listOfRows() } shouldContain "permission denied"
        failure { smtp.sql("SELECT * FROM later_table").query().listOfRows() } shouldContain "permission denied"
        db.psqlReference(api, ingestion, monitor).exitCode shouldBe 0 // a re-run after a migration adds nothing either
        failure { smtp.sql("SELECT * FROM workspace_eligibility").query().listOfRows() } shouldContain "permission denied"
        db.can(api, "SELECT", "workspace_eligibility") shouldBe true
    }

    @Test
    fun `under psql the API locks the cohort row but can never open admissions or ready capacity`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        db.login(ingestion)
        db.login(monitor)
        db.psqlReference(api, ingestion, monitor).exitCode shouldBe 0
        val source = db.roleSource(api)
        val asApi = JdbcClient.create(source)
        val tx =
            org.springframework.transaction.support.TransactionTemplate(
                org.springframework.jdbc.datasource
                    .DataSourceTransactionManager(source),
            )
        // The one statement the API runs on beta_cohort (JdbcBetaAdmissions).
        tx.execute {
            asApi
                .sql("SELECT admissions_open, capacity_ready FROM beta_cohort WHERE id = 'beta-30' FOR UPDATE")
                .query { rs, _ -> rs.getBoolean(1) to rs.getBoolean(2) }
                .single()
        } shouldBe (false to false)
        for (column in listOf("admissions_open", "capacity_ready")) {
            failure { asApi.sql("UPDATE beta_cohort SET $column = true").update() } shouldContain "permission denied"
        }
        failure { asApi.sql("INSERT INTO beta_cohort (id) VALUES ('beta-30')").update() } shouldContain "permission denied"
        failure { asApi.sql("DELETE FROM beta_cohort").update() } shouldContain "permission denied"
        db.effectivePrivileges(api)["beta_cohort"] shouldBe setOf("SELECT", "UPDATE(id)")
        db.jdbc
            .sql("SELECT admissions_open OR capacity_ready FROM beta_cohort")
            .query(Boolean::class.javaObjectType)
            .single() shouldBe false
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

    private companion object {
        private val READ = setOf("SELECT")
        private val PROTOCOL = setOf("SELECT", "INSERT", "UPDATE", "DELETE")

        /** The ingestion role's complete privileges in `public` (app STATUS §G); anything else is a regression. */
        val INGESTION_ALLOW_LIST: Map<String, Set<String>> =
            mapOf(
                "flyway_schema_history" to READ,
                "workspace" to setOf("SELECT(id)"),
                "inbox" to setOf("SELECT", "UPDATE(id)"),
                "message" to setOf("SELECT", "INSERT"),
                "attachment" to setOf("SELECT", "INSERT"),
                "inbox_storage" to
                    setOf(
                        "SELECT",
                        "INSERT(inbox_id,last_refusal_at,last_refusal_reason,refusal_count,workspace_id)",
                        "UPDATE(last_refusal_at,last_refusal_reason,refusal_count)",
                    ),
                "rate_bucket" to setOf("SELECT", "INSERT", "UPDATE"),
                "storage_reservation" to PROTOCOL,
                "storage_ambiguity" to PROTOCOL,
                "storage_node" to PROTOCOL,
                "storage_clock_episode" to PROTOCOL,
                "storage_admission_latch" to setOf("SELECT", "INSERT"),
                "workspace_storage_account" to READ,
                "storage_delta" to READ,
                "storage_deletion_debt" to READ,
                "storage_debt_watermark" to READ,
                "storage_footprint_trust" to READ,
                "storage_filesystem_observation" to READ,
                "participant_workspace" to READ,
                "storage_legacy_share" to READ,
                "workspace_deletion_charge" to READ,
                "storage_cohort_plan" to READ,
                "workspace_lifecycle_eligibility" to READ,
                "storage_ambiguity_id_seq" to setOf("USAGE"),
            )
    }
}
