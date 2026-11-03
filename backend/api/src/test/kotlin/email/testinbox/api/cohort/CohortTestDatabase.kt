package email.testinbox.api.cohort

import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.FilesystemDeclarations
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.storage.StorageEnforcement
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration
import java.util.UUID

/**
 * A freshly created, fully migrated PostgreSQL database for the ADR-037 cohort
 * acceptance tests (TI-DELIVERY-002b). Isolated on purpose: the cohort pool,
 * the node fence and the ledger are GLOBAL state, so the shared API test
 * database (where other suites' contexts heartbeat OFF nodes and create
 * participants) would make every readiness answer depend on test order.
 *
 * Fixtures write only what an operator or the deployables would write: node
 * rows as the V21 registration writes them, the trust mark through
 * `storage_confirm_footprint_trust()`, observations through
 * `storage_begin_observation()` as the monitor role (here the test superuser,
 * declared as the monitor), and real message rows whose ledger triggers keep
 * the counts trusted.
 */
class CohortTestDatabase private constructor(
    val name: String,
) {
    val url: String = postgres.jdbcUrl.substringBeforeLast('/') + "/" + name
    val dataSource = DriverManagerDataSource(url, postgres.username, postgres.password)
    val jdbc: JdbcClient = JdbcClient.create(dataSource)
    private val template = TransactionTemplate(DataSourceTransactionManager(dataSource))
    val tx =
        object : TransactionRunner {
            override fun <T> required(block: () -> T): T = checkNotNull(template.execute { block() })
        }

    /** The test superuser: it writes the observations, so it is the declared monitor. */
    val monitor: String get() = postgres.username

    /**
     * The exact declarations of the persistence cohort suite: ALL, one api and one
     * smtp node, G = 10 GiB, a 32 GiB filesystem. Thirty shares, the worst envelope,
     * finalization and every reserve fit with room for a few legacy shares.
     */
    fun declarations(
        apiNodes: Set<String> = setOf(API),
        ingestionNodes: Set<String> = setOf(SMTP),
    ): StorageDeclarations =
        StorageDeclarations(
            enforcement = StorageEnforcement.ALL,
            globalLimitBytes = 10 * GIB,
            declaredBucketQuotaBytes = 20 * GIB,
            declaredMaxIngestionProcesses = 1,
            inboxShare = "1",
            expectedApiNodes = apiNodes,
            expectedIngestionNodes = ingestionNodes,
            filesystem =
                FilesystemDeclarations(
                    blockSizeBytes = 4096,
                    objectOverheadMaxBytes = 32768,
                    globalFootprintLimitBytes = 16 * GIB,
                    deletionDebtBudgetBytes = 2 * GIB,
                    metadataBudgetBytes = GIB,
                    operationalReserveBytes = 2 * GIB,
                    capacityBytes = CAPACITY,
                    inodes = 8_000_000,
                    observationMaxAge = Duration.ofMinutes(15),
                    probeBudgetBytes = 256L shl 20,
                    monitorRole = monitor,
                ),
        )

    fun binding(declarations: StorageDeclarations): String = checkNotNull(EffectiveStoragePolicy.footprint(declarations)?.cohort?.binding)

    /** A node row exactly as a cohort-capable node of [mode] registers it (V21). */
    fun node(
        id: String,
        binding: String?,
        mode: String = "ALL",
        capability: String? = "cohort-storage-v1",
    ) {
        jdbc
            .sql(
                "INSERT INTO storage_node(node_id,generation,capability,heartbeat_at,clean_shutdown," +
                    "cohort_capability,cohort_mode,cohort_binding) " +
                    "VALUES (?,?,'storage-guarded-v1',now(),false,?,?,?)",
            ).params(listOf(id, UUID.randomUUID(), capability, mode, binding))
            .update()
    }

    fun trust() {
        check(jdbc.sql("SELECT storage_confirm_footprint_trust()").query(Boolean::class.java).single()) { "trust was not confirmed" }
    }

    /** One monitor observation (written by the declared monitor), after every prior distrust event. */
    fun observe(
        trash: Long = 0,
        capacity: Long = CAPACITY,
    ) {
        val order = jdbc.sql("SELECT storage_begin_observation()").query(Long::class.java).single()
        jdbc
            .sql(
                """INSERT INTO storage_filesystem_observation(started_seq,source,block_size_bytes,capacity_bytes,used_bytes,avail_bytes,inodes_total,inodes_used,trash_bytes,minio_sys_bytes)
                VALUES (?,'cohort-acceptance',4096,?,0,?,8000000,0,?,0)""",
            ).params(order, capacity, capacity, trash)
            .update()
    }

    /** The full runtime precondition for a pool: the declared nodes, trusted counts, a fresh observation. */
    fun ready(declarations: StorageDeclarations) {
        val binding = binding(declarations)
        (declarations.expectedApiNodes + declarations.expectedIngestionNodes).forEach { node(it, binding) }
        trust()
        observe()
    }

    fun workspace(): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO workspace (id, name, created_at) VALUES (?, 'legacy', now())").param(id).update()
        jdbc.sql("INSERT INTO project (id, workspace_id, name, created_at) VALUES (?, ?, 'p', now())").params(id, id).update()
        return id
    }

    fun inbox(workspace: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at)
                VALUES (?, ?, ?, ?, 'GENERATED', 'ACTIVE', now(), now() + interval '1 hour')""",
            ).params(id, workspace, workspace, "$id@cohort.test")
            .update()
        return id
    }

    /** A committed message row of [rawBytes]: the ledger triggers account it, so the counts stay trusted. */
    fun message(
        workspace: UUID,
        inbox: UUID,
        rawBytes: Long,
    ) {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to,
                    raw_object_key, raw_size_bytes, content_fingerprint, parse_status)
                VALUES (?, ?, ?, now(), 'smtp', 'to@cohort.test', ?, ?, 'fp', 'OK')""",
            ).params(id, workspace, inbox, "$workspace/$inbox/$id/raw.eml", rawBytes)
            .update()
    }

    /** An ACTIVE account with a verified contact: eligible for admission (ADR-036). */
    fun account(): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                "INSERT INTO human_account (id, contact_email, contact_verified_at, state, created_at) VALUES (?, ?, now(), 'ACTIVE', now())",
            ).params(id, "$id@example.invalid")
            .update()
        return id
    }

    fun count(sql: String): Long = jdbc.sql(sql).query(Long::class.java).single()

    fun open(
        admissions: Boolean = true,
        capacity: Boolean = true,
    ) {
        jdbc.sql("UPDATE beta_cohort SET admissions_open=?, capacity_ready=?").params(admissions, capacity).update()
    }

    companion object {
        const val GIB: Long = 1L shl 30
        const val CAPACITY: Long = 32 * GIB
        const val API = "api"
        const val SMTP = "smtp"

        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        fun create(): CohortTestDatabase {
            val name = "cohort_${UUID.randomUUID().toString().replace("-", "")}"
            val admin = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            JdbcClient.create(admin).sql("CREATE DATABASE $name").update()
            return CohortTestDatabase(name).also {
                Flyway
                    .configure()
                    .dataSource(it.dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
            }
        }
    }
}
