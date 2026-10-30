package email.testinbox.persistence

import email.testinbox.application.port.HumanSessions
import email.testinbox.domain.identity.HumanAccount
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanAccountState
import email.testinbox.domain.identity.HumanSession
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID

@Repository
class JdbcHumanSessions(
    private val jdbc: JdbcClient,
) : HumanSessions {
    override fun findAccountId(verifier: String): HumanAccountId? =
        jdbc
            .sql("SELECT account_id FROM human_session WHERE verifier = :verifier")
            .param("verifier", verifier)
            .query { rs, _ -> HumanAccountId(rs.getObject("account_id", UUID::class.java)) }
            .optional()
            .orElse(null)

    override fun lockAccount(accountId: HumanAccountId): HumanAccount? {
        requireTransaction()
        return jdbc
            .sql(
                "SELECT contact_email, ${LifecycleSql.EFFECTIVE_STATE} AS state, contact_verified_at FROM human_account WHERE id = :id FOR UPDATE",
            ).param("id", accountId.value)
            .query { rs, _ ->
                HumanAccount(
                    accountId,
                    rs.getString("contact_email"),
                    HumanAccountState.valueOf(rs.getString("state")),
                    rs.getObject("contact_verified_at") != null,
                )
            }.optional()
            .orElse(null)
    }

    override fun lockSession(
        accountId: HumanAccountId,
        verifier: String,
    ): HumanSession? {
        requireTransaction()
        return jdbc
            .sql("SELECT * FROM human_session WHERE account_id = :account AND verifier = :verifier FOR UPDATE")
            .param("verifier", verifier)
            .param("account", accountId.value)
            .query { rs, _ ->
                HumanSession(
                    rs.getString("verifier"),
                    HumanAccountId(rs.getObject("account_id", UUID::class.java)),
                    checkNotNull(Timestamps.fromDb(rs, "created_at")),
                    checkNotNull(Timestamps.fromDb(rs, "expires_at")),
                    checkNotNull(Timestamps.fromDb(rs, "last_seen_at")),
                )
            }.optional()
            .orElse(null)
    }

    override fun makeRoom(
        accountId: HumanAccountId,
        now: Instant,
    ) {
        requireTransaction()
        jdbc
            .sql("DELETE FROM human_session WHERE account_id = :id AND (expires_at <= :now OR last_seen_at <= :idle)")
            .param("id", accountId.value)
            .param("now", Timestamps.toDb(now))
            .param("idle", Timestamps.toDb(now.minus(HumanSession.IDLE_LIFETIME)))
            .update()
        jdbc
            .sql(
                """
                DELETE FROM human_session WHERE verifier IN (
                    SELECT verifier FROM human_session WHERE account_id = :id
                    ORDER BY created_at DESC, verifier DESC OFFSET :keep
                )
                """.trimIndent(),
            ).param("id", accountId.value)
            .param("keep", HumanSession.MAX_PER_ACCOUNT - 1)
            .update()
    }

    override fun insert(session: HumanSession) {
        requireTransaction()
        jdbc
            .sql(
                """
                INSERT INTO human_session (verifier, account_id, created_at, expires_at, last_seen_at)
                VALUES (:verifier, :account, :created, :expires, :seen)
                """.trimIndent(),
            ).param("verifier", session.verifier)
            .param("account", session.accountId.value)
            .param("created", Timestamps.toDb(session.createdAt))
            .param("expires", Timestamps.toDb(session.expiresAt))
            .param("seen", Timestamps.toDb(session.lastSeenAt))
            .update()
    }

    override fun touch(
        verifier: String,
        at: Instant,
    ) {
        requireTransaction()
        jdbc
            .sql("UPDATE human_session SET last_seen_at = :at WHERE verifier = :verifier")
            .param("at", Timestamps.toDb(at))
            .param("verifier", verifier)
            .update()
    }

    override fun delete(verifier: String) {
        requireTransaction()
        jdbc.sql("DELETE FROM human_session WHERE verifier = :verifier").param("verifier", verifier).update()
    }

    override fun deleteAll(accountId: HumanAccountId) {
        requireTransaction()
        jdbc.sql("DELETE FROM human_session WHERE account_id = :id").param("id", accountId.value).update()
    }

    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "human sessions require a database transaction" }
    }
}
