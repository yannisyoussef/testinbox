package email.testinbox.persistence

import email.testinbox.application.port.AccountLifecycleStore
import email.testinbox.application.port.LifecycleMembership
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.BetaCohortPolicy
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanAccountState
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * The operator lifecycle adapter. Deliberately NOT a Spring bean: only the
 * offline `account-lifecycle` command composes it, with operator database
 * credentials, so no running API or SMTP process can obtain it. The runtime
 * roles could not use it anyway: V22's triggers refuse a state change, an
 * account erasure or a quarantine clearance by any role that is not the
 * operator authority.
 */
@Suppress("TooManyFunctions") // one statement per AccountLifecycleStore step
class JdbcAccountLifecycle(
    private val jdbc: JdbcClient,
) : AccountLifecycleStore {
    override fun operatorAuthorized(): Boolean {
        requireTransaction()
        return jdbc.sql("SELECT lifecycle_operator_authorized()").query(Boolean::class.javaObjectType).single()
    }

    override fun findWorkspace(accountId: HumanAccountId): WorkspaceId? =
        jdbc
            .sql("SELECT workspace_id FROM beta_admission WHERE account_id = :account AND workspace_id IS NOT NULL")
            .param("account", accountId.value)
            .query { rs, _ -> WorkspaceId(rs.getObject("workspace_id", UUID::class.java)) }
            .optional()
            .orElse(null)

    override fun lockStorageFence() {
        requireTransaction()
        jdbc
            .sql("SELECT pg_advisory_xact_lock(:class, :admission)")
            .param("class", JdbcStorageAdmission.STORAGE_LOCK_CLASS)
            .param("admission", JdbcStorageAdmission.ADMISSION_LOCK)
            .query()
            .listOfRows()
    }

    override fun lockAccountState(accountId: HumanAccountId): HumanAccountState? {
        requireTransaction()
        return jdbc
            .sql("SELECT state FROM human_account WHERE id = :id FOR UPDATE")
            .param("id", accountId.value)
            .query { rs, _ -> HumanAccountState.valueOf(rs.getString("state")) }
            .optional()
            .orElse(null)
    }

    override fun setState(
        accountId: HumanAccountId,
        state: HumanAccountState,
    ) {
        requireTransaction()
        val updated =
            jdbc
                .sql("UPDATE human_account SET state = :state WHERE id = :id")
                .param("state", state.name)
                .param("id", accountId.value)
                .update()
        check(updated == 1) { "the locked account row was not updated" }
    }

    override fun reservationsHeld(workspaceId: WorkspaceId): Long =
        jdbc
            .sql("SELECT count(*) FROM storage_reservation WHERE workspace_id = :workspace")
            .param("workspace", workspaceId.value)
            .query(Long::class.javaObjectType)
            .single()

    override fun inboxRows(workspaceId: WorkspaceId): Long =
        jdbc
            .sql("SELECT count(*) FROM inbox WHERE workspace_id = :workspace")
            .param("workspace", workspaceId.value)
            .query(Long::class.javaObjectType)
            .single()

    override fun undeletedInboxes(
        workspaceId: WorkspaceId,
        limit: Int,
    ): List<InboxId> =
        jdbc
            .sql(
                """
                SELECT id FROM inbox WHERE workspace_id = :workspace AND state IN ('ACTIVE', 'EXPIRING')
                 ORDER BY created_at, id LIMIT :limit
                """.trimIndent(),
            ).param("workspace", workspaceId.value)
            .param("limit", limit)
            .query { rs, _ -> InboxId(rs.getObject("id", UUID::class.java)) }
            .list()

    override fun quarantined(): Boolean =
        jdbc.sql("SELECT NOT EXISTS (SELECT 1 FROM lifecycle_restore_clearance)").query(Boolean::class.javaObjectType).single()

    override fun participants(): List<LifecycleMembership> =
        jdbc
            .sql(
                """
                SELECT p.workspace_id, a.account_id
                  FROM participant_workspace p
                  LEFT JOIN beta_admission a ON a.cohort_id = p.cohort_id AND a.place = p.place
                 ORDER BY p.workspace_id
                """.trimIndent(),
            ).query { rs, _ ->
                LifecycleMembership(
                    rs.getObject("account_id", UUID::class.java)?.let(::HumanAccountId),
                    WorkspaceId(rs.getObject("workspace_id", UUID::class.java)),
                )
            }.list()

    /** The admission lock order: cohort row, then the T1 fence. */
    override fun closeCohortAndLockFence() {
        requireTransaction()
        jdbc
            .sql("SELECT id FROM beta_cohort WHERE id = :cohort FOR UPDATE")
            .param("cohort", BetaCohortPolicy.NAME)
            .query(String::class.java)
            .single()
        jdbc
            .sql("UPDATE beta_cohort SET admissions_open = false, capacity_ready = false WHERE id = :cohort")
            .param("cohort", BetaCohortPolicy.NAME)
            .update()
        lockStorageFence()
    }

    override fun clearQuarantine() {
        requireTransaction()
        jdbc.sql("INSERT INTO lifecycle_restore_clearance (id) VALUES (1)").update()
    }

    override fun eraseIdentity(
        accountId: HumanAccountId,
        workspaceId: WorkspaceId?,
    ) {
        requireTransaction()
        // The place stays spent; the marker keeps the workspace classified.
        jdbc
            .sql("UPDATE beta_admission SET workspace_id = NULL, project_id = NULL WHERE account_id = :account")
            .param("account", accountId.value)
            .update()
        // Identities, sessions, waitlist and request budgets cascade; key and
        // idempotency provenance is set to NULL; the membership loses its account.
        val deleted = jdbc.sql("DELETE FROM human_account WHERE id = :account").param("account", accountId.value).update()
        check(deleted == 1) { "the locked account row was not erased" }
    }

    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "account lifecycle requires a database transaction" }
    }
}
