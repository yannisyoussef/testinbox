package email.testinbox.persistence

import email.testinbox.application.port.ProviderLoginStates
import email.testinbox.application.port.ProviderLoginVolume
import email.testinbox.domain.identity.LoginProvider
import email.testinbox.domain.identity.ProviderLoginState
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class JdbcProviderLoginStates(
    private val jdbc: JdbcClient,
) : ProviderLoginStates {
    override fun purge(
        before: Instant,
        now: Instant,
    ) {
        requireHumanAuthTransaction()
        jdbc.sql("DELETE FROM provider_login_state WHERE created_at<=:before").param("before", Timestamps.toDb(before)).update()
        jdbc
            .sql("UPDATE provider_login_state SET encrypted_request=NULL WHERE expires_at<=:now AND encrypted_request IS NOT NULL")
            .param("now", Timestamps.toDb(now))
            .update()
    }

    override fun volume(
        sourceHash: String,
        now: Instant,
    ): ProviderLoginVolume {
        requireHumanAuthTransaction()
        return jdbc
            .sql(
                """
                SELECT count(*) AS retained, count(*) FILTER (WHERE created_at>:hour) AS last_hour,
                    count(*) FILTER (WHERE source_hash=:source AND created_at>:quarter) AS source_count FROM provider_login_state
                """.trimIndent(),
            ).param("hour", Timestamps.toDb(now.minusSeconds(3600)))
            .param("quarter", Timestamps.toDb(now.minusSeconds(900)))
            .param("source", sourceHash)
            .query { rs, _ -> ProviderLoginVolume(rs.getLong("retained"), rs.getLong("last_hour"), rs.getLong("source_count")) }
            .single()
    }

    override fun replaceForBrowser(browserHash: String) {
        requireHumanAuthTransaction()
        jdbc
            .sql("UPDATE provider_login_state SET encrypted_request=NULL WHERE browser_hash=:browser AND encrypted_request IS NOT NULL")
            .param("browser", browserHash)
            .update()
    }

    override fun insert(state: ProviderLoginState) {
        requireHumanAuthTransaction()
        jdbc
            .sql(
                """
                INSERT INTO provider_login_state (state_hash,browser_hash,source_hash,provider,encrypted_request,created_at,expires_at)
                VALUES (:state,:browser,:source,:provider,:payload,:created,:expires)
                """.trimIndent(),
            ).param("state", state.stateHash)
            .param("browser", state.browserHash)
            .param("source", state.sourceHash)
            .param("provider", state.provider.name)
            .param("payload", state.encryptedRequest)
            .param("created", Timestamps.toDb(state.createdAt))
            .param("expires", Timestamps.toDb(state.expiresAt))
            .update()
    }

    override fun consume(
        stateHash: String,
        browserHash: String,
        provider: LoginProvider,
        now: Instant,
    ): String? {
        requireHumanAuthTransaction()
        return jdbc
            .sql(
                """
                WITH candidate AS (
                    SELECT state_hash,encrypted_request FROM provider_login_state
                    WHERE state_hash=:state AND browser_hash=:browser AND provider=:provider
                        AND created_at<=:now AND expires_at>:now AND encrypted_request IS NOT NULL FOR UPDATE
                )
                UPDATE provider_login_state s SET encrypted_request=NULL FROM candidate c
                WHERE s.state_hash=c.state_hash RETURNING c.encrypted_request
                """.trimIndent(),
            ).param("state", stateHash)
            .param("browser", browserHash)
            .param("provider", provider.name)
            .param("now", Timestamps.toDb(now))
            .query(String::class.java)
            .optional()
            .orElse(null)
    }
}
