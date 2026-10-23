package email.testinbox.persistence

import email.testinbox.application.port.SweepRuns
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * [SweepRuns] on V10's `storage_sweep_run`, written only through its two
 * `SECURITY DEFINER` functions: the API role holds `EXECUTE` on them and no
 * privilege on the table. Each call is its own autocommitted statement, so a
 * run's start is durable before the listing begins.
 */
class JdbcSweepRuns(
    private val jdbc: JdbcClient,
    private val nodeId: String,
) : SweepRuns {
    override fun begin(): Long =
        jdbc
            .sql("SELECT storage_begin_sweep(:node)")
            .param("node", nodeId)
            .query(Long::class.java)
            .single()

    override fun complete(
        run: Long,
        listedBytes: Long,
    ) {
        jdbc
            .sql("SELECT storage_complete_sweep(:run, :node, :listed)")
            .param("run", run)
            .param("node", nodeId)
            .param("listed", listedBytes)
            .query()
            .listOfRows()
    }
}
