package email.testinbox.persistence

import email.testinbox.application.port.EmailLoginVolume
import email.testinbox.application.port.EmailLogins
import email.testinbox.domain.identity.ContactEmail
import email.testinbox.domain.identity.EmailChallengeState
import email.testinbox.domain.identity.EmailLoginChallenge
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JdbcEmailLogins(
    private val jdbc: JdbcClient,
) : EmailLogins {
    override fun purge(before: Instant) {
        requireHumanAuthTransaction()
        jdbc.sql("DELETE FROM email_login_challenge WHERE created_at <= :before").param("before", Timestamps.toDb(before)).update()
    }

    override fun volume(
        email: ContactEmail,
        sourceHash: String,
        now: Instant,
    ): EmailLoginVolume {
        requireHumanAuthTransaction()
        return jdbc
            .sql(
                """
                SELECT count(*) AS retained,
                    count(*) FILTER (WHERE created_at > :hour) AS last_hour,
                    count(*) FILTER (WHERE email=:email AND created_at > :quarter) AS recipient_count,
                    count(*) FILTER (WHERE source_hash=:source AND created_at > :quarter) AS source_count,
                    max(created_at) FILTER (WHERE email=:email) AS recipient_last_at
                FROM email_login_challenge
                """.trimIndent(),
            ).param("hour", Timestamps.toDb(now.minusSeconds(3600)))
            .param("quarter", Timestamps.toDb(now.minusSeconds(900)))
            .param("email", email.value)
            .param("source", sourceHash)
            .query { rs, _ ->
                EmailLoginVolume(
                    rs.getLong("retained"),
                    rs.getLong("last_hour"),
                    rs.getLong("recipient_count"),
                    rs.getLong("source_count"),
                    Timestamps.fromDb(rs, "recipient_last_at"),
                )
            }.single()
    }

    override fun replaceForBrowser(browserHash: String) {
        requireHumanAuthTransaction()
        jdbc
            .sql("UPDATE email_login_challenge SET state='REPLACED' WHERE browser_hash=:browser AND state IN ('PENDING','SENT')")
            .param("browser", browserHash)
            .update()
    }

    override fun insert(challenge: EmailLoginChallenge) {
        requireHumanAuthTransaction()
        jdbc
            .sql(
                """
                INSERT INTO email_login_challenge
                    (id,email,browser_hash,source_hash,code_verifier,created_at,expires_at,attempts_remaining,state)
                VALUES (:id,:email,:browser,:source,:code,:created,:expires,:attempts,:state)
                """.trimIndent(),
            ).param("id", challenge.id)
            .param("email", challenge.email.value)
            .param("browser", challenge.browserHash)
            .param("source", challenge.sourceHash)
            .param("code", challenge.codeVerifier)
            .param("created", Timestamps.toDb(challenge.createdAt))
            .param("expires", Timestamps.toDb(challenge.expiresAt))
            .param("attempts", challenge.attemptsRemaining)
            .param("state", challenge.state.name)
            .update()
    }

    override fun finishDelivery(
        id: UUID,
        sent: Boolean,
    ) {
        requireHumanAuthTransaction()
        jdbc
            .sql("UPDATE email_login_challenge SET state=:state WHERE id=:id AND state='PENDING'")
            .param("id", id)
            .param("state", if (sent) "SENT" else "FAILED")
            .update()
    }

    override fun lockChallenge(id: UUID): EmailLoginChallenge? {
        requireHumanAuthTransaction()
        return jdbc
            .sql("SELECT * FROM email_login_challenge WHERE id=:id FOR UPDATE")
            .param("id", id)
            .query { rs, _ ->
                EmailLoginChallenge(
                    rs.getObject("id", UUID::class.java),
                    checkNotNull(ContactEmail.parse(rs.getString("email"))),
                    rs.getString("browser_hash"),
                    rs.getString("source_hash"),
                    rs.getString("code_verifier"),
                    checkNotNull(Timestamps.fromDb(rs, "created_at")),
                    checkNotNull(Timestamps.fromDb(rs, "expires_at")),
                    rs.getInt("attempts_remaining"),
                    EmailChallengeState.valueOf(rs.getString("state")),
                )
            }.optional()
            .orElse(null)
    }

    override fun failedAttempt(id: UUID) {
        requireHumanAuthTransaction()
        check(
            jdbc
                .sql(
                    """
                    UPDATE email_login_challenge SET attempts_remaining=attempts_remaining-1,
                        state=CASE WHEN attempts_remaining=1 THEN 'EXHAUSTED' ELSE state END
                    WHERE id=:id AND state='SENT' AND attempts_remaining>0
                    """.trimIndent(),
                ).param("id", id)
                .update() == 1,
        )
    }

    override fun consume(id: UUID) {
        requireHumanAuthTransaction()
        check(
            jdbc
                .sql(
                    """
                    UPDATE email_login_challenge SET state='CONSUMED', attempts_remaining=0
                    WHERE id=:id AND state='SENT' AND attempts_remaining>0
                    """.trimIndent(),
                ).param("id", id)
                .update() == 1,
        )
    }
}
