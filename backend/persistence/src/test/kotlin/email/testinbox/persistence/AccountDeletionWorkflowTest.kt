package email.testinbox.persistence

import email.testinbox.application.Clocks
import email.testinbox.application.ObjectKeys
import email.testinbox.application.Sha256
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.AccountLifecycleStore
import email.testinbox.application.port.IdentityErasure
import email.testinbox.application.port.WorkspaceStatus
import email.testinbox.application.storage.CohortStorageReadiness
import email.testinbox.application.usecase.AccountDeletionReport
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.application.usecase.AuthenticateApiKey
import email.testinbox.application.usecase.ChangeAccountState
import email.testinbox.application.usecase.CreateApiKey
import email.testinbox.application.usecase.DeleteInbox
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.application.usecase.ProcessAccountDeletion
import email.testinbox.application.usecase.ReconcileRestoredDatabase
import email.testinbox.application.usecase.RevokeAccountSessions
import email.testinbox.application.usecase.RevokeApiKey
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.application.usecase.StorageAdmissionCandidate
import email.testinbox.application.usecase.StorageAdmissionDecision
import email.testinbox.application.usecase.StorageAdmissionRequest
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.AccountDeletionPolicy
import email.testinbox.domain.identity.AccountDeletionStage
import email.testinbox.domain.identity.AccountLifecycleAction
import email.testinbox.domain.identity.BetaAdmission
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.tenant.ApiKeyFormat
import email.testinbox.domain.tenant.ApiScope
import email.testinbox.domain.tenant.HumanWorkspaceOwner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The TI-DELIVERY-002b deletion workflow and restore-credential policy on real
 * PostgreSQL: DRAINING → CONTENT_DELETING → ERASED for an admitted account
 * holding every kind of identity and content, what erasure removes and what it
 * must retain, resumability after a crash at each step, refusal while content
 * remains, overdue reporting, operator-only records, and restore
 * reconciliation revoking legacy keys. Every test gets its own database.
 */
class AccountDeletionWorkflowTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private val clock = Clocks.utcMicros()

    private inner class World(
        val db: LedgerTestDatabase = LedgerTestDatabase.create(postgres, admin),
    ) {
        val jdbc = JdbcClient.create(db.dataSource)
        val txm = DataSourceTransactionManager(db.dataSource)
        val tx = SpringTransactionRunner(txm)
        val sessions = JdbcHumanSessions(jdbc)
        val apiKeys = JdbcApiKeyRepository(jdbc)
        val eligibility = JdbcWorkspaceEligibility(jdbc)
        val quotas = JdbcWorkspaceQuotaState(jdbc)
        val lifecycle = JdbcAccountLifecycle(jdbc)
        val revokeSessions = RevokeAccountSessions(sessions, tx)
        val revokeKeys = RevokeApiKey(apiKeys, clock)
        val change = ChangeAccountState(lifecycle, quotas, revokeSessions, revokeKeys, tx, clock)
        val createKey = CreateApiKey(apiKeys, clock, guard = quotas, eligibility = eligibility)
        val admissions = JdbcBetaAdmissions(jdbc)
        val admit = AdmitBetaParticipant(admissions, tx, clock, CohortStorageReadiness { true })
        val inboxes = JdbcInboxRepository(jdbc)
        val deleteInbox = DeleteInbox(inboxes, JdbcExactAddressReservations(jdbc), tx, clock, TestInboxConfig(mailDomain = "ledger.test"))

        /** The workflow as the timer runs it, [later] after now (the clock the content gate reads). */
        fun process(
            later: Duration = Duration.ZERO,
            store: AccountLifecycleStore = lifecycle,
        ) = ProcessAccountDeletion(store, quotas, revokeSessions, revokeKeys, deleteInbox, tx, Clock.offset(clock, later))

        fun advance(
            account: HumanAccountId,
            later: Duration = Duration.ZERO,
            store: AccountLifecycleStore = lifecycle,
        ) = process(later, store)
            .execute(account)
            .shouldBeInstanceOf<ProcessAccountDeletion.Result.Advanced>()
            .progress

        fun participant(): Pair<HumanAccountId, BetaAdmission> {
            db.jdbc.sql("UPDATE beta_cohort SET admissions_open = true, capacity_ready = true").update()
            val id = HumanAccountId(UUID.randomUUID())
            db.jdbc
                .sql(
                    "INSERT INTO human_account (id, contact_email, contact_verified_at, state, created_at) VALUES (?, ?, now(), 'ACTIVE', now())",
                ).params(id.value, "${id.value}@example.invalid")
                .update()
            val admitted = admit.execute(id).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()
            db.jdbc.sql("UPDATE beta_cohort SET admissions_open = false, capacity_ready = false").update()
            return id to admitted.admission
        }

        fun key(
            admission: BetaAdmission,
            name: String,
        ) = tx
            .required {
                createKey.execute(
                    CreateApiKey.Command(
                        HumanWorkspaceOwner(admission.accountId, admission.workspaceId, admission.projectId),
                        name,
                        setOf(ApiScope.INBOXES_WRITE),
                        null,
                    ),
                )
            }.shouldBeInstanceOf<CreateApiKey.Result.Created>()

        fun inbox(
            admission: BetaAdmission,
            exactLocalPart: String? = null,
        ): InboxId {
            val id = UUID.randomUUID()
            val address = (exactLocalPart ?: id.toString()) + "@ledger.test"
            db.jdbc
                .sql(
                    "INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'ACTIVE', now(), now() + interval '1 hour')",
                ).params(
                    id,
                    admission.workspaceId.value,
                    admission.projectId.value,
                    address,
                    if (exactLocalPart !=
                        null
                    ) {
                        "EXACT"
                    } else {
                        "GENERATED"
                    },
                ).update()
            if (exactLocalPart != null) {
                db.jdbc
                    .sql(
                        "INSERT INTO exact_address_reservation (id, workspace_id, local_part, inbox_id, status, reserved_at) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', now())",
                    ).params(UUID.randomUUID(), admission.workspaceId.value, exactLocalPart, id)
                    .update()
            }
            return InboxId(id)
        }

        /** Holds a reservation from before the fence, as an in-flight SMTP copy does. */
        fun reserve(
            workspace: WorkspaceId,
            inbox: InboxId,
        ) {
            val message = MessageId(UUID.randomUUID())
            val request =
                StorageAdmissionRequest(
                    10,
                    listOf(StorageAdmissionCandidate(message, workspace, inbox, listOf(ObjectKeys.raw(workspace, inbox, message)))),
                    "deletion-test",
                    UUID.randomUUID(),
                )
            StorageAdmission(
                JdbcStorageAdmission(jdbc, TransactionTemplate(txm)),
                StorageCapacityPolicy.ADR_035_REFERENCE,
                StorageEnforcement.OFF,
            ).admit(request)
                .decisions
                .single()
                .shouldBeInstanceOf<StorageAdmissionDecision.Admitted>()
        }

        fun count(
            sql: String,
            vararg params: Any,
        ): Long =
            db.jdbc
                .sql(sql)
                .params(*params)
                .query(Long::class.java)
                .single()

        fun stage(account: HumanAccountId): String? =
            db.jdbc
                .sql("SELECT stage FROM account_deletion WHERE account_id = ?")
                .param(account.value)
                .query(String::class.java)
                .optional()
                .orElse(null)

        fun sweep() = inboxes.findHardDeletable(100).forEach { inboxes.hardDelete(it.id) }
    }

    /** Everything that holds the person's identity, plus content, plus the tombstones that must survive. */
    private fun World.fullyPopulated(): Pair<HumanAccountId, BetaAdmission> {
        val (account, admission) = participant()
        val ws = admission.workspaceId.value
        val email = "${account.value}@example.invalid"
        IssueHumanSession(sessions, tx, clock).execute(account).shouldNotBeNull()
        IssueHumanSession(sessions, tx, clock).execute(account).shouldNotBeNull()
        key(admission, "Jane Doe's laptop")
        key(admission, "jane@corp ci")
        db.jdbc
            .sql(
                "INSERT INTO human_identity (provider, issuer, subject, account_id, created_at) VALUES ('GOOGLE', 'https://accounts.google.com', ?, ?, now())",
            ).params("fixture-subject-${account.value}", account.value)
            .update()
        db.jdbc
            .sql("INSERT INTO beta_waitlist (account_id, place, joined_at, expires_at) VALUES (?, 1, now(), now() + interval '30 days')")
            .param(account.value)
            .update()
        db.jdbc
            .sql("INSERT INTO human_request_budget (account_id, window_start, requests, last_allowed) VALUES (?, now(), 3, true)")
            .param(account.value)
            .update()
        for ((address, browser) in listOf(email to "a", "someone-else@example.invalid" to "b")) {
            db.jdbc
                .sql(
                    """
                    INSERT INTO email_login_challenge (id, email, browser_hash, source_hash, code_verifier, created_at, expires_at,
                                                       attempts_remaining, state)
                    VALUES (?, ?, ?, ?, ?, now(), now() + interval '5 minutes', 5, 'CONSUMED')
                    """.trimIndent(),
                ).params(UUID.randomUUID(), address, browser.repeat(64), "c".repeat(64), "d".repeat(64))
                .update()
        }
        db.jdbc
            .sql(
                """
                INSERT INTO idempotency_record (id, workspace_id, project_id, operation, key_hash, fingerprint, snapshot_version, snapshot,
                                                created_at, expires_at, created_by_human_account_id)
                VALUES (?, ?, ?, 'CREATE_API_KEY', 'k', 'f', 1, '{"name":"Jane Doe''s laptop"}', now(), now() + interval '1 day', ?)
                """.trimIndent(),
            ).params(UUID.randomUUID(), ws, admission.projectId.value, account.value)
            .update()
        // A released reservation from earlier use: the local part someone chose.
        db.jdbc
            .sql(
                "INSERT INTO exact_address_reservation (id, workspace_id, local_part, inbox_id, status, reserved_at) " +
                    "VALUES (?, ?, 'jane-doe-old', ?, 'RELEASED', now() - interval '3 days')",
            ).params(UUID.randomUUID(), ws, UUID.randomUUID())
            .update()
        // A pending physical-cleanup charge: freed only by the proven zero-trash observation, never by deletion.
        db.jdbc
            .sql(
                "INSERT INTO workspace_deletion_charge (debt_id, workspace_id, bytes, objects, seq, incurred_at) VALUES (9001, ?, 4096, 2, 1, now())",
            ).param(ws)
            .update()
        val generated = inbox(admission)
        inbox(admission, exactLocalPart = "jane-doe")
        db.message(ws, generated.value, rawBytes = 100, attachments = listOf(10))
        return account to admission
    }

    @Test
    fun `an admitted account goes DRAINING, CONTENT_DELETING, ERASED, erasing identity and keeping every tombstone`() {
        val w = World()
        val (account, admission) = w.fullyPopulated()
        val ws = admission.workspaceId.value
        val email = "${account.value}@example.invalid"
        val keyHashes =
            w.db.jdbc
                .sql(
                    "SELECT key_hash FROM api_key WHERE workspace_id = ? ORDER BY id",
                ).param(ws)
                .query(String::class.java)
                .list()
        w.reserve(admission.workspaceId, w.inbox(admission))

        // Request: access revoked at once; the record starts in the same transaction.
        w.change.execute(account, AccountLifecycleAction.DELETE).shouldBeInstanceOf<ChangeAccountState.Result.Done>()
        w.stage(account) shouldBe "DRAINING"

        // DRAINING: a reservation from before the fence holds everything back.
        w.advance(account).stage shouldBe AccountDeletionStage.DRAINING
        w.count("SELECT count(*) FROM inbox WHERE workspace_id = ? AND state = 'DELETED'", ws) shouldBe 0
        w.db.jdbc
            .sql("DELETE FROM storage_reservation")
            .update()

        // CONTENT_DELETING: inboxes through the existing deleter; the EXACT one enters its cooldown.
        val deleting = w.advance(account)
        deleting.stage shouldBe AccountDeletionStage.CONTENT_DELETING
        deleting.inboxesMarkedDeleted shouldBe 3
        w.stage(account) shouldBe "CONTENT_DELETING"
        w.count("SELECT count(*) FROM human_account WHERE id = ?", account.value) shouldBe 1

        // The retention sweep removes rows (objects first); the EXACT cooldown still holds the erasure.
        w.sweep()
        w.count("SELECT count(*) FROM message WHERE workspace_id = ?", ws) shouldBe 0
        val cooling = w.advance(account)
        cooling.stage shouldBe AccountDeletionStage.CONTENT_DELETING
        cooling.remaining.inboxRows shouldBe 0
        cooling.remaining.exactCooldowns shouldBe 1
        w.count("SELECT count(*) FROM human_account WHERE id = ?", account.value) shouldBe 1

        // ERASED, once the cooldown (24 h shipped) has elapsed.
        val erased = w.advance(account, later = Duration.ofHours(25))
        erased.stage shouldBe AccountDeletionStage.ERASED
        erased.erasure shouldBe
            IdentityErasure(anonymizedKeyLabels = 2, loginChallenges = 1, idempotencyRecords = 1, addressReservations = 2)

        // Erased: contact, login identities, sessions, waitlist, budget, codes, labels, chosen addresses.
        w.count("SELECT count(*) FROM human_account WHERE id = ? OR contact_email = ?", account.value, email) shouldBe 0
        w.count("SELECT count(*) FROM human_identity WHERE account_id = ? OR subject LIKE ?", account.value, "%${account.value}") shouldBe 0
        w.count("SELECT count(*) FROM human_session WHERE account_id = ?", account.value) shouldBe 0
        w.count("SELECT count(*) FROM beta_waitlist WHERE account_id = ?", account.value) shouldBe 0
        w.count("SELECT count(*) FROM human_request_budget WHERE account_id = ?", account.value) shouldBe 0
        w.count("SELECT count(*) FROM email_login_challenge WHERE email = ?", email) shouldBe 0
        w.count("SELECT count(*) FROM email_login_challenge WHERE email = 'someone-else@example.invalid'") shouldBe 1
        w.count("SELECT count(*) FROM api_key WHERE workspace_id = ? AND name IS NOT NULL", ws) shouldBe 0
        w.count("SELECT count(*) FROM api_key WHERE created_by_human_account_id = ?", account.value) shouldBe 0
        w.count("SELECT count(*) FROM idempotency_record WHERE workspace_id = ?", ws) shouldBe 0
        w.count("SELECT count(*) FROM exact_address_reservation WHERE workspace_id = ?", ws) shouldBe 0
        w.count("SELECT count(*) FROM beta_admission WHERE account_id = ? OR workspace_id = ?", account.value, ws) shouldBe 0

        // Retained: the spent place, the marker, the charge, and the keys' technical audit rows.
        w.count(
            "SELECT count(*) FROM beta_admission WHERE place = ? AND account_id IS NULL AND workspace_id IS NULL",
            admission.place,
        ) shouldBe
            1
        w.count("SELECT count(*) FROM participant_workspace WHERE workspace_id = ?", ws) shouldBe 1
        w.count("SELECT count(*) FROM workspace_deletion_charge WHERE workspace_id = ? AND debt_id = 9001", ws) shouldBe 1
        w.db.jdbc
            .sql("SELECT key_hash FROM api_key WHERE workspace_id = ? ORDER BY id")
            .param(ws)
            .query(String::class.java)
            .list() shouldBe
            keyHashes
        w.count(
            "SELECT count(*) FROM api_key WHERE workspace_id = ? AND revoked_at IS NOT NULL AND public_id IS NOT NULL AND scopes = '{inboxes:write}'",
            ws,
        ) shouldBe 2
        // The 20 usable / 1000 retained inventory still counts the anonymized rows.
        w.apiKeys.managedInventory(admission.workspaceId, clock.instant()).let {
            it.usable shouldBe 0
            it.retained shouldBe 2
        }
        w.eligibility.status(admission.workspaceId) shouldBe WorkspaceStatus.INACTIVE_PARTICIPANT
        w.db.jdbc
            .sql("SELECT erased_at IS NOT NULL AND stage = 'ERASED' FROM account_deletion WHERE account_id = ?")
            .param(account.value)
            .query(Boolean::class.java)
            .single() shouldBe true

        // Idempotent afterwards, and the place is never released.
        w.advance(account).let {
            it.stage shouldBe AccountDeletionStage.ERASED
            it.erasure.shouldBeNull()
        }
        w.change.execute(account, AccountLifecycleAction.DELETE) shouldBe ChangeAccountState.Result.AccountNotFound
        w.participant().second.place shouldBe admission.place + 1
    }

    @Test
    fun `a crash inside the erasing transaction rolls all of it back and the next run completes it`() {
        val w = World()
        val (account, admission) = w.fullyPopulated()
        w.change.execute(account, AccountLifecycleAction.DELETE)
        w.advance(account).stage shouldBe AccountDeletionStage.CONTENT_DELETING
        w.sweep()
        // Every erasure statement runs, then the process dies before commit.
        val crashing =
            object : AccountLifecycleStore by w.lifecycle {
                override fun eraseIdentity(
                    accountId: HumanAccountId,
                    workspaceId: WorkspaceId?,
                    at: java.time.Instant,
                ): IdentityErasure {
                    w.lifecycle.eraseIdentity(accountId, workspaceId, at)
                    error("simulated crash after the erasure statements")
                }
            }
        shouldThrow<IllegalStateException> { w.process(Duration.ofHours(25), crashing).execute(account) }
        w.stage(account) shouldBe "CONTENT_DELETING"
        w.count("SELECT count(*) FROM human_account WHERE id = ?", account.value) shouldBe 1
        w.count("SELECT count(*) FROM human_identity WHERE account_id = ?", account.value) shouldBe 1
        w.count("SELECT count(*) FROM api_key WHERE workspace_id = ? AND name IS NOT NULL", admission.workspaceId.value) shouldBe 2

        w.advance(account, later = Duration.ofHours(25)).stage shouldBe AccountDeletionStage.ERASED
        w.count("SELECT count(*) FROM human_account WHERE id = ?", account.value) shouldBe 0
    }

    @Test
    fun `a run that dies while marking inboxes resumes with the rest, and erasure waits for every inbox row`() {
        val w = World()
        val (account, admission) = w.participant()
        val inboxes = List(3) { w.inbox(admission) }
        w.change.execute(account, AccountLifecycleAction.DELETE)
        var batches = 0
        val dying =
            object : AccountLifecycleStore by w.lifecycle {
                override fun undeletedInboxes(
                    workspaceId: WorkspaceId,
                    limit: Int,
                ): List<InboxId> {
                    check(batches++ == 0) { "simulated crash between batches" }
                    return w.lifecycle.undeletedInboxes(workspaceId, 1)
                }
            }
        shouldThrow<IllegalStateException> { w.process(store = dying).execute(account) }
        w.stage(account) shouldBe "CONTENT_DELETING"
        w.count("SELECT count(*) FROM inbox WHERE workspace_id = ? AND state = 'DELETED'", admission.workspaceId.value) shouldBe 1

        val resumed = w.advance(account)
        resumed.stage shouldBe AccountDeletionStage.CONTENT_DELETING
        resumed.inboxesMarkedDeleted shouldBe 2
        resumed.remaining.inboxRows shouldBe 3
        // Refused while any row remains: the sweep has removed one of three.
        w.inboxes.hardDelete(inboxes.first())
        w.advance(account).remaining.inboxRows shouldBe 2
        w.count("SELECT count(*) FROM human_account WHERE id = ?", account.value) shouldBe 1
        w.sweep()
        w.advance(account).stage shouldBe AccountDeletionStage.ERASED
    }

    @Test
    fun `the timer adopts a deletion-pending account without a record, and overdue deletions are reported`() {
        val w = World()
        val (account, admission) = w.participant()
        w.inbox(admission)
        w.change.execute(account, AccountLifecycleAction.DELETE)
        // A DELETION_PENDING account with no record (a pre-V23 deletion, or hand-edited data).
        val (orphan, _) = w.participant()
        w.change.execute(orphan, AccountLifecycleAction.DELETE)
        w.db.jdbc
            .sql("DELETE FROM account_deletion WHERE account_id = ?")
            .param(orphan.value)
            .update()

        val policy = AccountDeletionPolicy.derived(Duration.ofHours(24), Duration.ofHours(24))
        val now = AccountDeletionReport(w.lifecycle, w.tx, clock, policy).execute()
        now.entries.map { it.record.accountId } shouldBe listOf(account)
        now.entries.single().overdue shouldBe false
        now.entries
            .single()
            .remaining.inboxRows shouldBe 1
        now.unrecorded shouldBe listOf(orphan)
        now.overdue shouldBe 1

        w
            .process()
            .pending()
            .map { it.accountId }
            .toSet() shouldBe setOf(account, orphan)
        w.stage(orphan) shouldBe "DRAINING"
        val later = AccountDeletionReport(w.lifecycle, w.tx, Clock.offset(clock, Duration.ofHours(73)), policy).execute()
        later.unrecorded shouldBe emptyList()
        later.entries.all { it.overdue } shouldBe true
        later.overdue shouldBe 2
    }

    @Test
    fun `only the operator authority writes deletion records`() {
        val w = World()
        val (account, _) = w.participant()
        w.change.execute(account, AccountLifecycleAction.DELETE)
        val role = "ti_rt_${UUID.randomUUID().toString().take(8)}"
        w.db.jdbc
            .sql("CREATE ROLE $role LOGIN PASSWORD 'pw-$role'")
            .update()
        w.db.jdbc
            .sql("GRANT CONNECT ON DATABASE ${w.db.name} TO $role")
            .update()
        w.db.jdbc
            .sql("GRANT USAGE ON SCHEMA public TO $role")
            .update()
        w.db.jdbc
            .sql("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON account_deletion TO $role")
            .update()
        val runtime =
            JdbcClient.create(
                SimpleDriverDataSource(
                    org.postgresql.Driver(),
                    postgres.jdbcUrl.substringBeforeLast('/') + "/" + w.db.name,
                    role,
                    "pw-$role",
                ),
            )

        fun refusal(block: () -> Unit): String =
            generateSequence(runCatching(block).exceptionOrNull() ?: error("expected a refusal")) { it.cause }
                .joinToString(" | ") { it.message.orEmpty() }
        refusal {
            runtime
                .sql("UPDATE account_deletion SET stage = 'ERASED', erased_at = now() WHERE account_id = ?")
                .param(account.value)
                .update()
        } shouldContain "requires the operator authority"
        refusal { runtime.sql("DELETE FROM account_deletion").update() } shouldContain "requires the operator authority"
        refusal { runtime.sql("TRUNCATE account_deletion").update() } shouldContain "requires the operator authority"
        refusal {
            runtime
                .sql(
                    "INSERT INTO account_deletion (account_id, stage, requested_at, stage_changed_at) VALUES (?, 'DRAINING', now(), now())",
                ).param(UUID.randomUUID())
                .update()
        } shouldContain "requires the operator authority"
        w.stage(account) shouldBe "DRAINING"
    }

    @Test
    fun `restore reconciliation revokes legacy owner and monitoring keys too, and none authenticates while quarantined`() {
        val w = World()
        val (account, admission) = w.participant()
        val participantKey = w.key(admission, "ci").credential.render()
        // An owner/monitoring workspace: no participant marker.
        val legacy = w.db.workspace()
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(11L) }
        val credentials =
            List(2) {
                ApiKeyFormat.generate(random).also { c ->
                    w.db.jdbc
                        .sql(
                            "INSERT INTO api_key (id, workspace_id, project_id, key_hash, scopes, created_at, kind, public_id, name) " +
                                "VALUES (?, ?, ?, ?, '{inboxes:write}', now(), 'MANAGED', ?, 'synthetics')",
                        ).params(UUID.randomUUID(), legacy, legacy, Sha256.hex(c.secret), c.publicId)
                        .update()
                }
            }
        val authenticate = AuthenticateApiKey(w.apiKeys, clock, w.eligibility, restoreQuarantine = JdbcRestoreQuarantine(w.jdbc))
        credentials.forEach { authenticate.authenticate(it.render()).shouldNotBeNull() }

        // The restored database: no clearance row.
        w.db.jdbc
            .sql("DELETE FROM lifecycle_restore_clearance")
            .update()
        credentials.forEach { authenticate.authenticate(it.render()).shouldBeNull() }
        authenticate.authenticate(participantKey).shouldBeNull()

        ReconcileRestoredDatabase(w.lifecycle, w.quotas, w.revokeSessions, w.revokeKeys, w.tx, clock).execute() shouldBe
            ReconcileRestoredDatabase.Result.Reconciled(1, 1, 1, 2)
        w.count("SELECT count(*) FROM api_key WHERE kind = 'MANAGED' AND revoked_at IS NULL") shouldBe 0
        // Cleared, and still refused: they are revoked, not merely quarantined.
        credentials.forEach { authenticate.authenticate(it.render()).shouldBeNull() }
        w.lifecycle.quarantined() shouldBe false
        w.count("SELECT count(*) FROM human_account WHERE id = ? AND state = 'SUSPENDED'", account.value) shouldBe 1
    }
}
