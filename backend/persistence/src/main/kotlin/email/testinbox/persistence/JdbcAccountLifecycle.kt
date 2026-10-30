package email.testinbox.persistence

import email.testinbox.application.port.AccountDeletionRecord
import email.testinbox.application.port.AccountLifecycleStore
import email.testinbox.application.port.IdentityErasure
import email.testinbox.application.port.LifecycleMembership
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.AccountDeletionStage
import email.testinbox.domain.identity.BetaCohortPolicy
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanAccountState
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.time.Instant
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
        // The first statement of every operator transaction: bound its holds first.
        OperatorTransactionBounds.apply(jdbc)
        return jdbc.sql("SELECT public.lifecycle_operator_authorized()").query(Boolean::class.javaObjectType).single()
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
            // FOR NO KEY UPDATE, not FOR UPDATE: it still serializes with sign-in,
            // session authentication and admission (all FOR UPDATE), but it does
            // not wait on the FOR KEY SHARE an idempotency claim's foreign key
            // takes before that writer reaches the workspace guard this
            // transaction already holds, which would be a deadlock.
            .sql("SELECT state FROM human_account WHERE id = :id FOR NO KEY UPDATE")
            .param("id", accountId.value)
            .query { rs, _ -> HumanAccountState.valueOf(rs.getString("state")) }
            .optional()
            .orElse(null)
    }

    override fun accountState(accountId: HumanAccountId): HumanAccountState? =
        jdbc
            .sql("SELECT state FROM human_account WHERE id = :id")
            .param("id", accountId.value)
            .query { rs, _ -> HumanAccountState.valueOf(rs.getString("state")) }
            .optional()
            .orElse(null)

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
    override fun lockCohortAndFence() {
        requireTransaction()
        jdbc
            .sql("SELECT id FROM beta_cohort WHERE id = :cohort FOR UPDATE")
            .param("cohort", BetaCohortPolicy.NAME)
            .query(String::class.java)
            .single()
        lockStorageFence()
    }

    override fun closeCohort() {
        requireTransaction()
        jdbc
            .sql("UPDATE beta_cohort SET admissions_open = false, capacity_ready = false WHERE id = :cohort")
            .param("cohort", BetaCohortPolicy.NAME)
            .update()
    }

    override fun clearQuarantine() {
        requireTransaction()
        // A concurrent reconciliation that committed first makes this a no-op, never a failure.
        jdbc.sql("INSERT INTO lifecycle_restore_clearance (id) VALUES (1) ON CONFLICT (id) DO NOTHING").update()
    }

    override fun exactCooldowns(
        workspaceId: WorkspaceId,
        now: Instant,
    ): Long =
        jdbc
            .sql(
                """
                SELECT count(*) FROM exact_address_reservation
                 WHERE workspace_id = :workspace
                   AND (status = 'ACTIVE' OR (status = 'COOLDOWN' AND available_at > :now))
                """.trimIndent(),
            ).param("workspace", workspaceId.value)
            .param("now", Timestamps.toDb(now))
            .query(Long::class.javaObjectType)
            .single()

    override fun eraseIdentity(
        accountId: HumanAccountId,
        workspaceId: WorkspaceId?,
        at: Instant,
    ): IdentityErasure {
        requireTransaction()
        // Sign-in codes sent to the contact address are not keyed by account: match the address
        // (stored lower-cased by ContactEmail) while the account row still names it.
        val challenges =
            jdbc
                .sql(
                    """
                    DELETE FROM email_login_challenge
                     WHERE lower(email) = (SELECT lower(contact_email) FROM human_account WHERE id = :account)
                    """.trimIndent(),
                ).param("account", accountId.value)
                .update()
        var labels = 0
        var idempotency = 0
        var reservations = 0
        if (workspaceId != null) {
            // Free text a person chose. The rows themselves (ids, verifier, scopes, timestamps,
            // revocation, creator key) stay: audit history (ADR-032 §5) and the 20/1000 inventory.
            labels =
                jdbc
                    .sql("UPDATE api_key SET name = NULL WHERE workspace_id = :workspace AND name IS NOT NULL")
                    .param("workspace", workspaceId.value)
                    .update()
            // Request fingerprints and projections (key names, addresses); transient by design.
            idempotency =
                jdbc
                    .sql("DELETE FROM idempotency_record WHERE workspace_id = :workspace")
                    .param("workspace", workspaceId.value)
                    .update()
            // Chosen EXACT local parts. The content gate proved none is ACTIVE or still cooling
            // down, so deleting them releases nothing early (ADR-021).
            reservations =
                jdbc
                    .sql(
                        """
                        DELETE FROM exact_address_reservation
                         WHERE workspace_id = :workspace AND (status = 'RELEASED' OR (status = 'COOLDOWN' AND available_at <= :at))
                        """.trimIndent(),
                    ).param("workspace", workspaceId.value)
                    .param("at", Timestamps.toDb(at))
                    .update()
        }
        // The place stays spent; the marker keeps the workspace classified.
        jdbc
            .sql("UPDATE beta_admission SET workspace_id = NULL, project_id = NULL WHERE account_id = :account")
            .param("account", accountId.value)
            .update()
        // Identities, sessions, waitlist and request budgets cascade; key and
        // idempotency provenance is set to NULL; the membership loses its account.
        val deleted = jdbc.sql("DELETE FROM human_account WHERE id = :account").param("account", accountId.value).update()
        check(deleted == 1) { "the locked account row was not erased" }
        openDeletion(accountId, at)
        advanceDeletion(accountId, AccountDeletionStage.ERASED, at)
        return IdentityErasure(labels, challenges, idempotency, reservations)
    }

    override fun deletion(accountId: HumanAccountId): AccountDeletionRecord? =
        jdbc
            .sql("SELECT $DELETION_COLUMNS FROM account_deletion WHERE account_id = :account")
            .param("account", accountId.value)
            .query { rs, _ -> deletionRecord(rs) }
            .optional()
            .orElse(null)

    override fun openDeletion(
        accountId: HumanAccountId,
        at: Instant,
    ) {
        requireTransaction()
        jdbc
            .sql(
                """
                INSERT INTO account_deletion (account_id, stage, requested_at, stage_changed_at)
                VALUES (:account, 'DRAINING', :at, :at) ON CONFLICT (account_id) DO NOTHING
                """.trimIndent(),
            ).param("account", accountId.value)
            .param("at", Timestamps.toDb(at))
            .update()
    }

    override fun advanceDeletion(
        accountId: HumanAccountId,
        stage: AccountDeletionStage,
        at: Instant,
    ) {
        requireTransaction()
        // Forward only: the stage order is the CASE below. ERASED is terminal.
        jdbc
            .sql(
                """
                UPDATE account_deletion
                   SET stage = :stage,
                       stage_changed_at = greatest(:at, requested_at),
                       erased_at = CASE WHEN :stage = 'ERASED' THEN greatest(:at, requested_at) END
                 WHERE account_id = :account
                   AND $STAGE_ORDER < (CASE :stage WHEN 'DRAINING' THEN 0 WHEN 'CONTENT_DELETING' THEN 1 ELSE 2 END)
                """.trimIndent(),
            ).param("stage", stage.name)
            .param("at", Timestamps.toDb(at))
            .param("account", accountId.value)
            .update()
    }

    override fun pendingDeletions(): List<AccountDeletionRecord> =
        jdbc
            .sql("SELECT $DELETION_COLUMNS FROM account_deletion WHERE stage <> 'ERASED' ORDER BY requested_at, account_id")
            .query { rs, _ -> deletionRecord(rs) }
            .list()

    override fun unrecordedDeletions(): List<HumanAccountId> =
        jdbc
            .sql(
                """
                SELECT h.id FROM human_account h
                 WHERE h.state = 'DELETION_PENDING' AND NOT EXISTS (SELECT 1 FROM account_deletion d WHERE d.account_id = h.id)
                 ORDER BY h.id
                """.trimIndent(),
            ).query { rs, _ -> HumanAccountId(rs.getObject("id", UUID::class.java)) }
            .list()

    override fun quiesceAccount(accountId: HumanAccountId): Boolean {
        requireTransaction()
        return jdbc
            .sql("SELECT 1 FROM human_account WHERE id = :account FOR UPDATE")
            .param("account", accountId.value)
            .query()
            .listOfRows()
            .isNotEmpty()
    }

    override fun revokeEveryManagedKey(at: Instant): Int {
        requireTransaction()
        return jdbc
            .sql("UPDATE api_key SET revoked_at = :at WHERE kind = 'MANAGED' AND revoked_at IS NULL")
            .param("at", Timestamps.toDb(at))
            .update()
    }

    private fun deletionRecord(rs: ResultSet) =
        AccountDeletionRecord(
            HumanAccountId(rs.getObject("account_id", UUID::class.java)),
            AccountDeletionStage.valueOf(rs.getString("stage")),
            checkNotNull(Timestamps.fromDb(rs, "requested_at")),
            checkNotNull(Timestamps.fromDb(rs, "stage_changed_at")),
            Timestamps.fromDb(rs, "erased_at"),
        )

    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "account lifecycle requires a database transaction" }
    }

    private companion object {
        const val DELETION_COLUMNS = "account_id, stage, requested_at, stage_changed_at, erased_at"
        const val STAGE_ORDER = "(CASE stage WHEN 'DRAINING' THEN 0 WHEN 'CONTENT_DELETING' THEN 1 ELSE 2 END)"
    }
}
