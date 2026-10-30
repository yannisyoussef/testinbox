package email.testinbox.persistence

import email.testinbox.application.port.HumanAuthenticationLock
import email.testinbox.application.port.HumanIdentities
import email.testinbox.domain.identity.ContactEmail
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.VerifiedLoginIdentity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID

@Repository
class JdbcHumanAuthenticationLock(
    private val jdbc: JdbcClient,
) : HumanAuthenticationLock {
    override fun lock() {
        requireHumanAuthTransaction()
        jdbc
            .sql("INSERT INTO human_auth_gate (id) VALUES (1) ON CONFLICT (id) DO UPDATE SET id=EXCLUDED.id RETURNING id")
            .query(Int::class.javaObjectType)
            .single()
    }
}

@Repository
class JdbcHumanIdentities(
    private val jdbc: JdbcClient,
) : HumanIdentities {
    override fun find(identity: VerifiedLoginIdentity): HumanAccountId? =
        jdbc
            .sql("SELECT account_id FROM human_identity WHERE provider=:provider AND issuer=:issuer AND subject=:subject")
            .param("provider", identity.provider.name)
            .param("issuer", identity.issuer)
            .param("subject", identity.subject)
            .query { rs, _ -> HumanAccountId(rs.getObject("account_id", UUID::class.java)) }
            .optional()
            .orElse(null)

    override fun findByContact(email: ContactEmail): HumanAccountId? =
        jdbc
            .sql("SELECT id FROM human_account WHERE contact_email=:email")
            .param("email", email.value)
            .query { rs, _ -> HumanAccountId(rs.getObject("id", UUID::class.java)) }
            .optional()
            .orElse(null)

    override fun count(): Long = jdbc.sql("SELECT count(*) FROM human_account").query(Long::class.javaObjectType).single()

    override fun create(
        id: HumanAccountId,
        identity: VerifiedLoginIdentity,
        at: Instant,
    ) {
        requireHumanAuthTransaction()
        jdbc
            .sql(
                """
                INSERT INTO human_account (id, contact_email, contact_verified_at, state, created_at)
                VALUES (:id, :email, :at, 'ACTIVE', :at)
                """.trimIndent(),
            ).param("id", id.value)
            .param("email", identity.email.value)
            .param("at", Timestamps.toDb(at))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO human_identity (provider, issuer, subject, account_id, created_at)
                VALUES (:provider, :issuer, :subject, :id, :at)
                """.trimIndent(),
            ).param("provider", identity.provider.name)
            .param("issuer", identity.issuer)
            .param("subject", identity.subject)
            .param("id", id.value)
            .param("at", Timestamps.toDb(at))
            .update()
    }
}

internal fun requireHumanAuthTransaction() {
    check(TransactionSynchronizationManager.isActualTransactionActive()) { "human authentication requires a database transaction" }
}
