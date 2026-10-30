package email.testinbox.persistence

import email.testinbox.application.port.BetaWaitlist
import email.testinbox.domain.identity.BetaWaitlistEntry
import email.testinbox.domain.identity.HumanAccountId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

@Repository
class JdbcBetaWaitlist(
    private val jdbc: JdbcClient,
) : BetaWaitlist {
    override fun find(accountId: HumanAccountId): BetaWaitlistEntry? =
        jdbc
            .sql(
                "SELECT place,joined_at,expires_at FROM beta_waitlist WHERE account_id=:account",
            ).param("account", accountId.value)
            .query { rs, _ ->
                BetaWaitlistEntry(
                    accountId,
                    rs.getInt("place"),
                    checkNotNull(Timestamps.fromDb(rs, "joined_at")),
                    checkNotNull(Timestamps.fromDb(rs, "expires_at")),
                )
            }.optional()
            .orElse(null)

    override fun nextPlace(maximum: Int): Int? {
        requireTransaction()
        require(maximum in 1..BetaWaitlistEntry.MAX_ENTRIES)
        return jdbc
            .sql(
                """
                SELECT slot FROM generate_series(1,:maximum) AS slot
                WHERE (SELECT count(*) FROM beta_waitlist)<:maximum
                  AND NOT EXISTS (SELECT 1 FROM beta_waitlist w WHERE w.place=slot)
                ORDER BY slot LIMIT 1
                """.trimIndent(),
            ).param("maximum", maximum)
            .query(Int::class.javaObjectType)
            .optional()
            .orElse(null)
    }

    override fun insert(entry: BetaWaitlistEntry) {
        requireTransaction()
        jdbc
            .sql("INSERT INTO beta_waitlist(account_id,place,joined_at,expires_at) VALUES(:account,:place,:joined,:expires)")
            .param("account", entry.accountId.value)
            .param("place", entry.place)
            .param("joined", Timestamps.toDb(entry.joinedAt))
            .param("expires", Timestamps.toDb(entry.expiresAt))
            .update()
    }

    override fun remove(accountId: HumanAccountId) {
        requireTransaction()
        jdbc.sql("DELETE FROM beta_waitlist WHERE account_id=:account").param("account", accountId.value).update()
    }

    override fun purge(now: Instant) {
        requireTransaction()
        jdbc
            .sql(
                """
                DELETE FROM beta_waitlist w WHERE w.expires_at<=:now
                    OR EXISTS(SELECT 1 FROM beta_admission a WHERE a.account_id=w.account_id)
                """.trimIndent(),
            ).param("now", Timestamps.toDb(now))
            .update()
    }

    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "waitlist requires a database transaction" }
    }
}
