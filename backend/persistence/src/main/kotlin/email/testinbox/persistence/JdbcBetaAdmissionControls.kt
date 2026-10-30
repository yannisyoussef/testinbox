package email.testinbox.persistence

import email.testinbox.application.port.BetaAdmissionControls
import email.testinbox.domain.identity.BetaCohortPolicy
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Not a Spring repository bean: only the offline `admission` operator command
 * composes this, with explicit operator credentials. The runtime API role has
 * no UPDATE on `admissions_open` (roles.reference.sql), so even a wrongly
 * composed runtime caller is refused by PostgreSQL itself.
 */
class JdbcBetaAdmissionControls(
    private val jdbc: JdbcClient,
) : BetaAdmissionControls {
    override fun lockPolicy(): BetaCohortPolicy {
        requireTransaction()
        OperatorTransactionBounds.apply(jdbc)
        return policy("SELECT admissions_open, capacity_ready FROM beta_cohort WHERE id = :cohort FOR UPDATE")
    }

    override fun readPolicy(): BetaCohortPolicy = policy("SELECT admissions_open, capacity_ready FROM beta_cohort WHERE id = :cohort")

    override fun operatorAuthorized(): Boolean {
        requireTransaction()
        return jdbc
            .sql("SELECT has_column_privilege('public.beta_cohort', 'admissions_open', 'UPDATE')")
            .query(Boolean::class.javaObjectType)
            .single()
    }

    override fun setAdmissionsOpen(open: Boolean) {
        requireTransaction()
        val updated =
            jdbc
                .sql("UPDATE beta_cohort SET admissions_open = :open WHERE id = :cohort")
                .param("open", open)
                .param("cohort", BetaCohortPolicy.NAME)
                .update()
        check(updated == 1) { "the beta cohort row is missing" }
    }

    override fun consumedPlaces(): Int = jdbc.sql("SELECT count(*) FROM beta_admission").query(Int::class.javaObjectType).single()

    private fun policy(sql: String): BetaCohortPolicy =
        jdbc
            .sql(sql)
            .param("cohort", BetaCohortPolicy.NAME)
            .query { rs, _ -> BetaCohortPolicy(rs.getBoolean("admissions_open"), rs.getBoolean("capacity_ready")) }
            .single()

    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "admission control requires a database transaction" }
    }
}
