package email.testinbox.persistence

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.testcontainers.utility.MountableFile
import java.io.File
import java.util.UUID

/**
 * The shared fixture of the reference role-model tests ([StorageRoleModelTest],
 * [StorageRolePsqlTest]): the three role names, the database helpers that apply
 * `deploy/database/roles.reference.sql` (through JDBC, or through psql exactly as
 * Ops runs it), run the deployables' operations as a role, and read gate F's own
 * privilege query, and the ingestion role's pinned allow-list.
 */
abstract class StorageRoleModelSupport : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    protected val suffix = UUID.randomUUID().toString().take(8)
    protected val api = "ti_api_$suffix"
    protected val ingestion = "ti_ingest_$suffix"
    protected val monitor = "ti_monitor_$suffix"

    protected fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .first { it.isFile }

    protected fun LedgerTestDatabase.login(role: String): JdbcClient {
        jdbc.sql("CREATE ROLE $role LOGIN PASSWORD 'pw-$role'").update()
        jdbc.sql("GRANT CONNECT ON DATABASE $name TO $role").update()
        return JdbcClient.create(
            SimpleDriverDataSource(org.postgresql.Driver(), postgres.jdbcUrl.substringBeforeLast('/') + "/" + name, role, "pw-$role"),
        )
    }

    protected fun LedgerTestDatabase.applyReferenceGrants() {
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
    protected fun LedgerTestDatabase.psqlReference(
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

    protected fun LedgerTestDatabase.can(
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
    protected fun LedgerTestDatabase.roleSource(role: String) =
        SimpleDriverDataSource(org.postgresql.Driver(), postgres.jdbcUrl.substringBeforeLast('/') + "/" + name, role, "pw-$role")

    /**
     * Every privilege [role] holds in `public`, per relation: table-level
     * privileges by name, column-level ones as `PRIV(col,...)`, sequences as
     * `USAGE`/`SELECT`/`UPDATE`. Relations it holds nothing on are absent.
     */
    protected fun LedgerTestDatabase.effectivePrivileges(role: String): Map<String, Set<String>> =
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
    protected fun LedgerTestDatabase.runDeliveryPathAs(role: String): JdbcClient {
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
        JdbcLifecyclePrivilegeProbe(smtp, "ingestion").ingestion() shouldBe null
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
        // V25: the share-partitioned T1 (PROPOSED) runs its whole statement, guard included, as this
        // role. This workspace holds no share, so the guard does not hold and mode a decides.
        val partitioned =
            JdbcStorageAdmission(
                smtp,
                tx,
                readsFootprint = true,
                locking = email.testinbox.application.storage.AdmissionLocking.COHORT_PARTITIONED,
            )
        partitioned.admit(
            email.testinbox.application.port
                .StorageAdmissionScope(setOf(workspaceId), setOf(inboxId)),
        ) {
            email.testinbox.application.port
                .StorageAdmissionPlan(emptyList(), true)
        } shouldBe true
        partitioned.partitionFallbacks shouldBe 1
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
    protected fun LedgerTestDatabase.gateFState(appRoles: String): String {
        val script = repoFile("scripts/check-storage-activation.sh").readText()
        val sql =
            script
                .substringAfter("FOOTPRINT_STATE_SQL=\"\$(cat <<'SQL'\n")
                .substringBefore("\nSQL\n)\"")
                .replace(":'app_roles'", "'$appRoles'")
        return jdbc.sql(sql).query(String::class.java).single()
    }

    protected fun failure(block: () -> Unit): String {
        val e = runCatching(block).exceptionOrNull() ?: error("expected a refusal")
        return generateSequence(e) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }

    protected companion object {
        val READ = setOf("SELECT")
        val PROTOCOL = setOf("SELECT", "INSERT", "UPDATE", "DELETE")

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
                "storage_global_base" to READ,
                "storage_delta" to READ,
                "storage_deletion_debt" to READ,
                "storage_debt_watermark" to READ,
                "storage_footprint_trust" to READ,
                "storage_filesystem_observation" to READ,
                "participant_workspace" to READ,
                "storage_legacy_share" to READ,
                "workspace_deletion_charge" to READ,
                "storage_cohort_plan" to READ,
                // V25: the share-partitioned T1's guard reads the recorded partition bound.
                "storage_partition_bound" to READ,
                "workspace_lifecycle_eligibility" to READ,
                "storage_ambiguity_id_seq" to setOf("USAGE"),
            )
    }
}
