package email.testinbox.benchmark

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `t1Stats` against a real `pg_stat_statements` (Testcontainers, the `--local` target, which
 * preloads it with `track_planning`): the statement is found although its stored text has every
 * constant normalized to `$n`, and its plans are the ones a new connection makes.
 */
class DiagnosticsTest {
    @Test
    fun `the T1 snapshot is found in pg_stat_statements' normalized text, with its plans`() {
        BenchmarkTarget.local().use { target ->
            target.pool(1).use { pool ->
                val diagnostics = Diagnostics(pool)
                diagnostics.resetStatements()
                // The snapshot's row as JdbcStorageAdmission writes it ('GLOBAL' is stored as $1), one T1-shaped
                // transaction per execution, under T1's own SET LOCAL plan_cache_mode = force_generic_plan.
                pool.connection.use { c ->
                    c.autoCommit = false
                    repeat(EXECUTIONS) {
                        c.createStatement().use { it.execute("SET LOCAL plan_cache_mode = force_generic_plan") }
                        c
                            .prepareStatement(
                                "SELECT 'GLOBAL' AS scope, NULL::uuid AS id, NULL::uuid AS owner, now() AS t0, ?::int AS n",
                            ).use {
                                it.setInt(1, 1)
                                it.executeQuery().close()
                            }
                        c.commit()
                    }
                    c.autoCommit = true
                }
                val stats = diagnostics.t1Stats().shouldNotBeNull()
                stats.calls shouldBe EXECUTIONS.toLong()
                // A new connection plans its first prepareThreshold executions (four unnamed, then the server-prepared
                // one's generic plan) and none after: the model ConnectionLedger counts cold-connection plans by.
                stats.plans shouldBe PoolLifecycle.PREPARE_THRESHOLD.toLong()
                (stats.totalPlanMs > 0.0) shouldBe true
            }
        }
    }

    private companion object {
        const val EXECUTIONS = 8
    }
}
