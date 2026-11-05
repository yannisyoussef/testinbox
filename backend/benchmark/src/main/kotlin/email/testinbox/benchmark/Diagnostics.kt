package email.testinbox.benchmark

import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** `pg_stat_statements` figures for one statement over one scenario's measured phase. */
data class StatementStats(
    val calls: Long,
    /** 0 unless `pg_stat_statements.track_planning` is on. */
    val plans: Long,
    val meanPlanMs: Double,
    val meanExecMs: Double,
    val maxExecMs: Double,
    val rowsPerCall: Double,
)

/**
 * Gate D step (1) evidence the verdict asks for (#120 6631813062 §2.3), written
 * beside the evidence file. None of it is a §11 criterion, and none of it
 * changes a verdict:
 * - each mode's T1 statement prepared under `plan_cache_mode =
 *   force_generic_plan` (its generic plan, proven by `pg_prepared_statements`
 *   counting only generic plans) and `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`
 *   of an execution of it;
 * - `pg_stat_statements` (plan and execution time per statement), when the
 *   server preloads it;
 * - the commit cost: `synchronous_commit = on` commits of one small row, timed
 *   alone;
 * - the settings the gate depends on, and the churn tables' live/dead tuples
 *   and reloptions.
 */
class Diagnostics(
    private val dataSource: DataSource,
) {
    private val jdbc = JdbcClient.create(dataSource)

    /** Null when `pg_stat_statements` is usable here; otherwise why not (recorded, never fatal). */
    val statementsUnavailable: String? =
        runCatching {
            jdbc.sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements").update()
            jdbc.sql("SELECT count(*) FROM pg_stat_statements").query(Long::class.java).single()
            null
        }.getOrElse {
            "pg_stat_statements unavailable (${it.message?.lineSequence()?.firstOrNull()}); preload it to record plan/exec times"
        }

    fun resetStatements() {
        if (statementsUnavailable == null) runCatching { jdbc.sql("SELECT pg_stat_statements_reset()").query().listOfRows() }
    }

    /** The T1 snapshot statement's figures since the last reset: the `SELECT 'GLOBAL'` statement with the most calls. */
    fun t1Stats(): StatementStats? {
        if (statementsUnavailable != null) return null
        return jdbc
            .sql(
                """
                SELECT calls, plans, mean_plan_time, mean_exec_time, max_exec_time, rows::float8 / greatest(calls, 1) AS rows_per_call
                  FROM pg_stat_statements
                 WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                   AND query LIKE '%SELECT ''GLOBAL''%'
                 ORDER BY calls DESC LIMIT 1
                """.trimIndent(),
            ).query { rs, _ ->
                StatementStats(
                    rs.getLong("calls"),
                    rs.getLong("plans"),
                    rs.getDouble("mean_plan_time"),
                    rs.getDouble("mean_exec_time"),
                    rs.getDouble("max_exec_time"),
                    rs.getDouble("rows_per_call"),
                )
            }.optional()
            .orElse(null)
    }

    /** Timed `synchronous_commit = on` commits of one small row: the commit cost inside every T1 hold. */
    fun commitProbe(samples: Int = COMMIT_SAMPLES): LatencySummary? =
        dataSource.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    "CREATE TABLE IF NOT EXISTS bench_commit_probe (id bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now())",
                )
            }
            c.autoCommit = false
            val nanos =
                List(samples) {
                    c.createStatement().use { st ->
                        st.execute("SET LOCAL synchronous_commit = on")
                        st.execute("INSERT INTO bench_commit_probe DEFAULT VALUES")
                    }
                    val started = System.nanoTime()
                    c.commit()
                    System.nanoTime() - started
                }
            c.autoCommit = true
            c.createStatement().use { it.execute("DELETE FROM bench_commit_probe") }
            LatencySummary.ofNanos(nanos)
        }

    /** The full text report for one population. [statements] maps a label to a T1 statement with `:workspaces`/`:inboxes`. */
    fun report(
        population: SeededPopulation,
        statements: Map<String, String>,
        commit: LatencySummary?,
    ): String =
        buildString {
            val scope = population.inboxes.take(SCOPE_INBOXES)
            appendLine("# Gate D diagnostics: ${population.workspaceCount} workspaces (NOT a §11 criterion)")
            appendLine()
            appendLine("## Settings")
            settings().forEach { appendLine(it) }
            appendLine()
            appendLine("## Commit cost (synchronous_commit = on, one small row, timed alone)")
            appendLine(
                commit?.let { "p50 ${it.p50Ms} ms, p95 ${it.p95Ms} ms, p99 ${it.p99Ms} ms, max ${it.maxMs} ms (${it.samples} commits)" }
                    ?: "n/a",
            )
            appendLine()
            for ((label, statement) in statements) {
                val text = statement.replace(":workspaces", "$1").replace(":inboxes", "$2")
                appendLine(
                    "## $label: the GENERIC plan (PREPARE, plan_cache_mode = force_generic_plan), EXPLAIN (ANALYZE, BUFFERS, SETTINGS) " +
                        "EXECUTE over ${scope.size} recipients",
                )
                appendLine(prepared(text, scope))
            }
            appendLine("## pg_stat_statements (top 15 by total execution time, since the last reset)")
            appendLine(statementsUnavailable ?: topStatements())
            appendLine()
            appendLine("## Churn tables")
            tables().forEach { appendLine(it) }
        }

    private fun settings(): List<String> =
        jdbc
            .sql(
                """
                SELECT name || ' = ' || setting || coalesce(' ' || unit, '') || ' (' || source || ')'
                  FROM pg_settings
                 WHERE name IN ('server_version', 'max_connections', 'shared_buffers', 'synchronous_commit', 'fsync', 'wal_sync_method',
                                'full_page_writes', 'commit_delay', 'plan_cache_mode', 'autovacuum', 'autovacuum_naptime',
                                'autovacuum_vacuum_cost_delay', 'autovacuum_max_workers', 'work_mem', 'effective_cache_size',
                                'random_page_cost', 'jit', 'max_wal_size', 'checkpoint_timeout', 'shared_preload_libraries',
                                'pg_stat_statements.track_planning', 'huge_pages', 'wal_buffers')
                 ORDER BY name
                """.trimIndent(),
            ).query(String::class.java)
            .list()
            .filterNotNull()

    private fun tables(): List<String> =
        jdbc
            .sql(
                """
                SELECT s.relname || ': live ' || s.n_live_tup || ', dead ' || s.n_dead_tup || ', autovacuums ' || s.autovacuum_count
                       || ', last autovacuum ' || coalesce(s.last_autovacuum::text, 'never') || ', reloptions '
                       || coalesce(array_to_string(c.reloptions, ','), '-')
                  FROM pg_stat_user_tables s JOIN pg_class c ON c.oid = s.relid
                 WHERE s.relname IN ('storage_reservation', 'storage_delta', 'workspace_storage_account', 'inbox_storage',
                                     'storage_global_base', 'storage_deletion_debt', 'workspace_deletion_charge', 'workspace')
                 ORDER BY s.relname
                """.trimIndent(),
            ).query(String::class.java)
            .list()
            .filterNotNull()

    private fun topStatements(): String =
        jdbc
            .sql(
                """
                SELECT calls || ' calls, ' || plans || ' plans, plan mean ' || round(mean_plan_time::numeric, 4) || ' ms, exec mean '
                       || round(mean_exec_time::numeric, 4) || ' ms, exec max ' || round(max_exec_time::numeric, 3) || ' ms, total '
                       || round(total_exec_time::numeric, 1) || ' ms: ' || left(regexp_replace(query, '\s+', ' ', 'g'), 160)
                  FROM pg_stat_statements
                 WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                 ORDER BY total_exec_time DESC LIMIT 15
                """.trimIndent(),
            ).query(String::class.java)
            .list()
            .joinToString("\n")

    /** One connection: PREPARE, six EXECUTEs (past any custom-plan phase), the last explained, DEALLOCATE. */
    private fun prepared(
        text: String,
        scope: List<SeededInbox>,
    ): String =
        runCatching {
            dataSource.connection.use { c ->
                c.createStatement().use { it.execute("SET plan_cache_mode = force_generic_plan") }
                c.createStatement().use { it.execute("PREPARE gate_d_t1 (uuid[], uuid[]) AS $text") }
                val ws = literal(scope.map { it.workspaceId }.distinct())
                val ib = literal(scope.map { it.inboxId })
                repeat(WARM_EXECUTIONS) { c.createStatement().use { it.executeQuery("EXECUTE gate_d_t1($ws, $ib)").close() } }
                val plan = lines(c, "EXPLAIN (ANALYZE, BUFFERS, SETTINGS) EXECUTE gate_d_t1($ws, $ib)")
                val kinds =
                    lines(
                        c,
                        "SELECT 'generic_plans = ' || generic_plans || ', custom_plans = ' || custom_plans FROM pg_prepared_statements WHERE name = 'gate_d_t1'",
                    )
                c.createStatement().use { it.execute("DEALLOCATE gate_d_t1") }
                c.createStatement().use { it.execute("RESET plan_cache_mode") }
                "$kinds\n$plan"
            }
        }.getOrElse { "unavailable: ${it.message?.lineSequence()?.firstOrNull()}" }

    private fun lines(
        c: Connection,
        sql: String,
    ): String =
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }.joinToString("\n")
            }
        }

    /** UUIDs only, formatted here: no input reaches this literal. */
    private fun literal(ids: List<UUID>): String = "'{${ids.joinToString(",")}}'::uuid[]"

    private companion object {
        const val COMMIT_SAMPLES = 500
        const val SCOPE_INBOXES = 10
        const val WARM_EXECUTIONS = 6
    }
}
