package email.testinbox.persistence

import email.testinbox.application.port.AccountRequestBudgets
import email.testinbox.application.port.AccountRequestDecision
import email.testinbox.application.port.AccountRequestPolicy
import email.testinbox.domain.identity.HumanAccountId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration

@Repository
class JdbcAccountRequestBudgets(
    private val jdbc: JdbcClient,
) : AccountRequestBudgets {
    override fun charge(
        accountId: HumanAccountId,
        policy: AccountRequestPolicy,
    ): AccountRequestDecision {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "account budget requires a transaction" }
        val global = spend("human_request_global", "id", true, policy.global)
        if (!global.allowed) return global
        // A per-account refusal still spends its global request; refusals are not free traffic.
        return spend("human_request_budget", "account_id", accountId.value, policy.perAccount)
    }

    private fun spend(
        table: String,
        column: String,
        key: Any,
        limit: Int,
    ): AccountRequestDecision {
        // Identifiers are private closed call-site literals, never request fields.
        val row =
            jdbc
                .sql(
                    """
                    INSERT INTO $table ($column, window_start, requests, last_allowed)
                    VALUES (:key, date_trunc('minute', clock_timestamp()), 1, true)
                    ON CONFLICT ($column) DO UPDATE SET
                      window_start = greatest($table.window_start, date_trunc('minute', statement_timestamp())),
                      last_allowed = $table.window_start < date_trunc('minute', statement_timestamp()) OR $table.requests < :limit,
                      requests = CASE WHEN $table.window_start < date_trunc('minute', statement_timestamp()) THEN 1
                                      WHEN $table.requests < :limit THEN $table.requests + 1 ELSE $table.requests END
                    RETURNING last_allowed, window_start
                    """.trimIndent(),
                ).param("key", key)
                .param("limit", limit)
                .query { rs, _ -> rs.getBoolean("last_allowed") to checkNotNull(Timestamps.fromDb(rs, "window_start")) }
                .single()
        if (row.first) return AccountRequestDecision(true)
        val seconds =
            jdbc
                .sql("SELECT greatest(1, ceil(extract(epoch FROM :start::timestamptz + interval '1 minute' - clock_timestamp())))::bigint")
                .param("start", Timestamps.toDb(row.second))
                .query(Long::class.javaObjectType)
                .single()
        return AccountRequestDecision(false, Duration.ofSeconds(seconds))
    }
}
