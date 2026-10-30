package email.testinbox.persistence

import email.testinbox.application.Clocks
import email.testinbox.application.ObjectKeys
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.WorkspaceInactiveException
import email.testinbox.application.port.WorkspaceStatus
import email.testinbox.application.storage.CohortStorageReadiness
import email.testinbox.application.usecase.AccountLifecycleStatus
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.application.usecase.AuthenticateApiKey
import email.testinbox.application.usecase.AuthenticateHumanSession
import email.testinbox.application.usecase.ChangeAccountState
import email.testinbox.application.usecase.CreateApiKey
import email.testinbox.application.usecase.DeleteInbox
import email.testinbox.application.usecase.EraseAccountIdentity
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.application.usecase.LifecyclePhase
import email.testinbox.application.usecase.OperatorAuthorityRequiredException
import email.testinbox.application.usecase.ReconcileRestoredDatabase
import email.testinbox.application.usecase.RemoveAccountContent
import email.testinbox.application.usecase.ResolveHumanWorkspace
import email.testinbox.application.usecase.RevokeAccountSessions
import email.testinbox.application.usecase.RevokeApiKey
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.application.usecase.StorageAdmissionCandidate
import email.testinbox.application.usecase.StorageAdmissionDecision
import email.testinbox.application.usecase.StorageAdmissionRequest
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.AccountLifecycleAction
import email.testinbox.domain.identity.BetaAdmission
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanAccountState
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
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
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Account lifecycle on real PostgreSQL (ACCOUNT-LIFECYCLE.md): the operator
 * authority, permanent revocation across resume, the T1 fence race, deletion
 * through the existing deleter paths, tombstones, and restore quarantine.
 * Every test gets its own freshly migrated database.
 */
class AccountLifecycleTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private val clock = Clocks.utcMicros()

    private inner class World(
        val db: LedgerTestDatabase = LedgerTestDatabase.create(postgres, admin),
        dataSource: javax.sql.DataSource = db.dataSource,
    ) {
        val jdbc = JdbcClient.create(dataSource)
        val txm = DataSourceTransactionManager(dataSource)
        val tx = SpringTransactionRunner(txm)
        val template = TransactionTemplate(txm)
        val sessions = JdbcHumanSessions(jdbc)
        val apiKeys = JdbcApiKeyRepository(jdbc)
        val eligibility = JdbcWorkspaceEligibility(jdbc)
        val quotas = JdbcWorkspaceQuotaState(jdbc)
        val lifecycle = JdbcAccountLifecycle(jdbc)
        val revokeSessions = RevokeAccountSessions(sessions, tx)
        val revokeKeys = RevokeApiKey(apiKeys, clock)
        val change = ChangeAccountState(lifecycle, quotas, revokeSessions, revokeKeys, tx)
        val authenticateSession = AuthenticateHumanSession(sessions, tx, clock)
        val authenticateKey = AuthenticateApiKey(apiKeys, clock, eligibility)
        val createKey = CreateApiKey(apiKeys, clock, guard = quotas, eligibility = eligibility)
        val admissions = JdbcBetaAdmissions(jdbc)
        val admit = AdmitBetaParticipant(admissions, tx, clock, CohortStorageReadiness { true })
        val inboxes = JdbcInboxRepository(jdbc)
        val deleteInbox = DeleteInbox(inboxes, JdbcExactAddressReservations(jdbc), tx, clock, TestInboxConfig(mailDomain = "ledger.test"))

        fun account(): HumanAccountId {
            val id = HumanAccountId(UUID.randomUUID())
            db.jdbc
                .sql(
                    "INSERT INTO human_account (id, contact_email, contact_verified_at, state, created_at) VALUES (?, ?, now(), 'ACTIVE', now())",
                ).params(id.value, "${id.value}@example.invalid")
                .update()
            return id
        }

        fun participant(): Pair<HumanAccountId, BetaAdmission> {
            db.jdbc.sql("UPDATE beta_cohort SET admissions_open = true, capacity_ready = true").update()
            val id = account()
            val admitted = admit.execute(id).shouldBeInstanceOf<AdmitBetaParticipant.Result.Admitted>()
            db.jdbc.sql("UPDATE beta_cohort SET admissions_open = false, capacity_ready = false").update()
            return id to admitted.admission
        }

        fun session(id: HumanAccountId) = checkNotNull(IssueHumanSession(sessions, tx, clock).execute(id)).credential.value

        fun key(admission: BetaAdmission): String =
            tx
                .required {
                    createKey.execute(
                        CreateApiKey.Command(
                            HumanWorkspaceOwner(admission.accountId, admission.workspaceId, admission.projectId),
                            "ci",
                            setOf(ApiScope.INBOXES_WRITE),
                            null,
                        ),
                    )
                }.shouldBeInstanceOf<CreateApiKey.Result.Created>()
                .credential
                .render()

        fun inbox(
            workspace: WorkspaceId,
            project: ProjectId,
        ): InboxId {
            val id = UUID.randomUUID()
            db.jdbc
                .sql(
                    "INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at) " +
                        "VALUES (?, ?, ?, ?, 'GENERATED', 'ACTIVE', now(), now() + interval '1 hour')",
                ).params(id, workspace.value, project.value, "$id@ledger.test")
                .update()
            return InboxId(id)
        }

        fun state(id: HumanAccountId): String? =
            db.jdbc
                .sql("SELECT state FROM human_account WHERE id = ?")
                .param(id.value)
                .query(String::class.java)
                .optional()
                .orElse(null)

        fun reservations(workspace: WorkspaceId): Long =
            db.jdbc
                .sql("SELECT count(*) FROM storage_reservation WHERE workspace_id = ?")
                .param(workspace.value)
                .query(Long::class.java)
                .single()

        val t1Store = JdbcStorageAdmission(jdbc, template)

        fun t1(
            workspace: WorkspaceId,
            inbox: InboxId,
            store: JdbcStorageAdmission = t1Store,
        ): StorageAdmissionDecision {
            val message = MessageId(UUID.randomUUID())
            val request =
                StorageAdmissionRequest(
                    10,
                    listOf(StorageAdmissionCandidate(message, workspace, inbox, listOf(ObjectKeys.raw(workspace, inbox, message)))),
                    "lifecycle-test",
                    UUID.randomUUID(),
                )
            return StorageAdmission(store, StorageCapacityPolicy.ADR_035_REFERENCE, StorageEnforcement.OFF)
                .admit(request)
                .decisions
                .single()
        }

        fun done(result: ChangeAccountState.Result) = result.shouldBeInstanceOf<ChangeAccountState.Result.Done>().report
    }

    @Test
    fun `suspension refuses sessions and keys at once and resume revives neither`() {
        val w = World()
        val (account, admission) = w.participant()
        val (bystander, bystanderAdmission) = w.participant()
        val sessions = listOf(w.session(account), w.session(account))
        val key = w.key(admission)
        val bystanderSession = w.session(bystander)
        val bystanderKey = w.key(bystanderAdmission)
        w.authenticateKey.authenticate(key).shouldNotBeNull()
        sessions.forEach { w.authenticateSession.execute(it).shouldNotBeNull() }

        val report = w.done(w.change.execute(account, AccountLifecycleAction.SUSPEND))
        report.revokedKeys shouldBe 1
        report.phase shouldBe LifecyclePhase.SETTLED
        w.state(account) shouldBe "SUSPENDED"
        sessions.forEach { w.authenticateSession.execute(it).shouldBeNull() }
        w.authenticateKey.authenticate(key).shouldBeNull()
        w.eligibility.status(admission.workspaceId) shouldBe WorkspaceStatus.INACTIVE_PARTICIPANT
        IssueHumanSession(w.sessions, w.tx, clock).execute(account).shouldBeNull()
        ResolveHumanWorkspace(w.authenticateSession, w.admissions, w.tx).execute(w.session(bystander)).shouldNotBeNull()
        // Another account's sessions and keys are untouched: logout/suspension are per account.
        w.authenticateSession.execute(bystanderSession).shouldNotBeNull()
        w.authenticateKey.authenticate(bystanderKey).shouldNotBeNull()

        w.done(w.change.execute(account, AccountLifecycleAction.RESUME)).to shouldBe HumanAccountState.ACTIVE
        w.eligibility.status(admission.workspaceId) shouldBe WorkspaceStatus.ACTIVE_PARTICIPANT
        // Permanently revoked: resuming changed the state, never the credentials.
        w.authenticateKey.authenticate(key).shouldBeNull()
        sessions.forEach { w.authenticateSession.execute(it).shouldBeNull() }
        // A fresh login and a newly issued key work.
        w.authenticateSession.execute(w.session(account)).shouldNotBeNull()
        w.authenticateKey.authenticate(w.key(admission)).shouldNotBeNull()
    }

    @Test
    fun `only the operator authority can change state, erase an account or lift quarantine`() {
        val w = World()
        val (account, _) = w.participant()
        val runtime = "ti_runtime_${UUID.randomUUID().toString().take(8)}"
        val operator = "ti_operator_${UUID.randomUUID().toString().take(8)}"
        for (role in listOf(runtime, operator)) {
            w.db.jdbc
                .sql("CREATE ROLE $role LOGIN PASSWORD 'pw-$role'")
                .update()
            w.db.jdbc
                .sql("GRANT CONNECT ON DATABASE ${w.db.name} TO $role")
                .update()
            w.db.jdbc
                .sql("GRANT USAGE ON SCHEMA public TO $role")
                .update()
            // The API's grant shape: plain DML on every table. Grants alone do not confer the authority.
            w.db.jdbc
                .sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $role")
                .update()
            w.db.jdbc
                .sql("GRANT TRUNCATE ON lifecycle_restore_clearance TO $role")
                .update()
        }
        val owner =
            w.db.jdbc
                .sql("SELECT relowner::regrole::text FROM pg_class WHERE oid = 'public.human_account'::regclass")
                .query(String::class.java)
                .single()
        w.db.jdbc
            .sql("GRANT \"$owner\" TO $operator")
            .update()

        fun source(role: String) =
            SimpleDriverDataSource(org.postgresql.Driver(), postgres.jdbcUrl.substringBeforeLast('/') + "/" + w.db.name, role, "pw-$role")
        val asRuntime = World(w.db, source(runtime))
        shouldThrow<OperatorAuthorityRequiredException> { asRuntime.change.execute(account, AccountLifecycleAction.SUSPEND) }
        w.state(account) shouldBe "ACTIVE"

        fun refusal(block: () -> Unit): String =
            generateSequence(runCatching(block).exceptionOrNull() ?: error("expected a refusal")) { it.cause }
                .joinToString(" | ") { it.message.orEmpty() }
        refusal {
            asRuntime.jdbc
                .sql(
                    "UPDATE human_account SET state = 'SUSPENDED' WHERE id = ?",
                ).param(account.value)
                .update()
        } shouldContain
            "requires the operator authority"
        refusal {
            asRuntime.jdbc
                .sql("DELETE FROM human_account WHERE id = ?")
                .param(account.value)
                .update()
        } shouldContain
            "requires the operator authority"
        refusal { asRuntime.jdbc.sql("DELETE FROM lifecycle_restore_clearance").update() } shouldContain "requires the operator authority"
        refusal { asRuntime.jdbc.sql("TRUNCATE lifecycle_restore_clearance").update() } shouldContain "requires the operator authority"
        // Non-state updates (sign-in never changes state) are not refused.
        asRuntime.jdbc
            .sql("UPDATE human_account SET state = state WHERE id = ?")
            .param(account.value)
            .update() shouldBe 1

        val asOperator = World(w.db, source(operator))
        asOperator.done(asOperator.change.execute(account, AccountLifecycleAction.SUSPEND)).to shouldBe HumanAccountState.SUSPENDED
        w.state(account) shouldBe "SUSPENDED"
    }

    @Test
    fun `a suspension committed before T1 makes the copy a distinct inactive discard with no reservation`() {
        val w = World()
        val (account, admission) = w.participant()
        val inbox = w.inbox(admission.workspaceId, admission.projectId)
        w.t1(admission.workspaceId, inbox).shouldBeInstanceOf<StorageAdmissionDecision.Admitted>()
        w.reservations(admission.workspaceId) shouldBe 1
        w.db.jdbc
            .sql("DELETE FROM storage_reservation")
            .update()

        w.done(w.change.execute(account, AccountLifecycleAction.SUSPEND))
        w.t1(admission.workspaceId, inbox).shouldBeInstanceOf<StorageAdmissionDecision.Inactive>()
        w.reservations(admission.workspaceId) shouldBe 0
        // No storage refusal is attributed to the inbox either.
        w.db.jdbc
            .sql("SELECT count(*) FROM inbox_storage WHERE inbox_id = ? AND refusal_count > 0")
            .param(inbox.value)
            .query(Long::class.java)
            .single() shouldBe 0
    }

    @Test
    fun `a T1 holding the fence finishes its reservation and the suspension then reports draining until it is released`() {
        val w = World()
        val (account, admission) = w.participant()
        val inbox = w.inbox(admission.workspaceId, admission.projectId)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pausing =
            object : JdbcStorageAdmission(w.jdbc, w.template) {
                override fun acquireAdmissionLock() {
                    super.acquireAdmissionLock()
                    inside.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
            }
        val t1 = CompletableFuture.supplyAsync { w.t1(admission.workspaceId, inbox, pausing) }
        check(inside.await(30, TimeUnit.SECONDS))
        val suspension = CompletableFuture.supplyAsync { w.change.execute(account, AccountLifecycleAction.SUSPEND) }
        w.db.awaitBlockedSessions() // the operator waits on (35, 1) behind T1
        w.state(account) shouldBe "ACTIVE"
        release.countDown()

        t1.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<StorageAdmissionDecision.Admitted>()
        val report = w.done(suspension.get(30, TimeUnit.SECONDS))
        report.reservationsHeld shouldBe 1
        report.phase shouldBe LifecyclePhase.DRAINING
        // T1 refuses every new copy now, so the count can only fall.
        w.t1(admission.workspaceId, inbox).shouldBeInstanceOf<StorageAdmissionDecision.Inactive>()
        AccountLifecycleStatus(w.lifecycle, w.tx).execute(account)!!.phase shouldBe LifecyclePhase.DRAINING
        w.db.jdbc
            .sql("DELETE FROM storage_reservation")
            .update() // consumed by T2 or released by cleanup
        AccountLifecycleStatus(w.lifecycle, w.tx).execute(account)!!.phase shouldBe LifecyclePhase.SETTLED
    }

    @Test
    fun `a T1 queued behind an uncommitted suspension reads the committed state from its one snapshot`() {
        val w = World()
        val (account, admission) = w.participant()
        val inbox = w.inbox(admission.workspaceId, admission.projectId)
        val suspended = CountDownLatch(1)
        val commit = CountDownLatch(1)
        // The operator's transaction holds guard, fence and account until released.
        val suspension =
            CompletableFuture.supplyAsync {
                w.template.execute {
                    w.change.execute(account, AccountLifecycleAction.SUSPEND).also {
                        suspended.countDown()
                        check(commit.await(30, TimeUnit.SECONDS))
                    }
                }
            }
        check(suspended.await(30, TimeUnit.SECONDS))
        val t1 = CompletableFuture.supplyAsync { w.t1(admission.workspaceId, inbox) }
        w.db.awaitBlockedSessions() // T1 waits on (35, 1)
        commit.countDown()
        w.done(checkNotNull(suspension.get(30, TimeUnit.SECONDS))).phase shouldBe LifecyclePhase.SETTLED
        t1.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<StorageAdmissionDecision.Inactive>()
        w.reservations(admission.workspaceId) shouldBe 0
    }

    @Test
    fun `a key minted concurrently with a suspension is serialized by the workspace guard and revoked`() {
        val w = World()
        val (account, admission) = w.participant()
        val minted = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val owner = HumanWorkspaceOwner(account, admission.workspaceId, admission.projectId)
        val minting =
            CompletableFuture.supplyAsync {
                w.template.execute {
                    w.createKey
                        .execute(CreateApiKey.Command(owner, "race", setOf(ApiScope.INBOXES_WRITE), null))
                        .also {
                            minted.countDown()
                            check(commit.await(30, TimeUnit.SECONDS))
                        }
                }
            }
        check(minted.await(30, TimeUnit.SECONDS))
        val suspension = CompletableFuture.supplyAsync { w.change.execute(account, AccountLifecycleAction.SUSPEND) }
        w.db.awaitBlockedSessions() // the operator waits on the key writer's workspace guard
        commit.countDown()
        val key =
            minting
                .get(30, TimeUnit.SECONDS)
                .shouldBeInstanceOf<CreateApiKey.Result.Created>()
                .credential
                .render()
        w.done(suspension.get(30, TimeUnit.SECONDS)).revokedKeys shouldBe 1
        w.authenticateKey.authenticate(key).shouldBeNull()
        // After the suspension, the same request mints nothing.
        shouldThrow<WorkspaceInactiveException> {
            w.tx.required { w.createKey.execute(CreateApiKey.Command(owner, "late", setOf(ApiScope.INBOXES_WRITE), null)) }
        }
    }

    @Test
    fun `deletion drains, removes content through the inbox lifecycle and keeps the spent place and marker`() {
        val w = World()
        val (account, admission) = w.participant()
        val inboxes = listOf(w.inbox(admission.workspaceId, admission.projectId), w.inbox(admission.workspaceId, admission.projectId))
        w.db.message(admission.workspaceId.value, inboxes[0].value, rawBytes = 100, attachments = listOf(10))
        w.key(admission)
        val remove = RemoveAccountContent(w.lifecycle, w.deleteInbox, w.tx)

        w.t1(admission.workspaceId, inboxes[1]).shouldBeInstanceOf<StorageAdmissionDecision.Admitted>()
        val pending = w.done(w.change.execute(account, AccountLifecycleAction.DELETE))
        pending.to shouldBe HumanAccountState.DELETION_PENDING
        pending.revokedKeys shouldBe 1
        pending.phase shouldBe LifecyclePhase.DRAINING
        remove.execute(account).shouldBeInstanceOf<RemoveAccountContent.Result.Done>().phase shouldBe RemoveAccountContent.Phase.DRAINING
        w.db.jdbc
            .sql("DELETE FROM storage_reservation")
            .update()

        val marked = remove.execute(account).shouldBeInstanceOf<RemoveAccountContent.Result.Done>()
        marked.phase shouldBe RemoveAccountContent.Phase.CONTENT_PENDING
        marked.inboxesMarkedDeleted shouldBe 2
        // The existing retention sweep then hard-deletes rows (objects are proven deleted first).
        w.inboxes.findHardDeletable(10).forEach { w.inboxes.hardDelete(it.id) }
        remove.execute(account).shouldBeInstanceOf<RemoveAccountContent.Result.Done>().phase shouldBe
            RemoveAccountContent.Phase.CONTENT_REMOVED
        w.db.jdbc
            .sql("SELECT count(*) FROM message WHERE workspace_id = ?")
            .param(admission.workspaceId.value)
            .query(Long::class.java)
            .single() shouldBe 0
        // Deletion-pending is not erasure: the account stays, blocked, until the owner-decided erase step.
        w.state(account) shouldBe "DELETION_PENDING"
        w.change.execute(account, AccountLifecycleAction.RESUME).shouldBeInstanceOf<ChangeAccountState.Result.Refused>()

        val erase = EraseAccountIdentity(w.lifecycle, w.quotas, w.revokeSessions, w.revokeKeys, w.tx)
        erase.execute(account) shouldBe EraseAccountIdentity.Result.Erased(admission.workspaceId)
        w.state(account).shouldBeNull()
        w.db.jdbc
            .sql("SELECT count(*) FROM human_identity WHERE account_id = ?")
            .param(account.value)
            .query(Long::class.java)
            .single() shouldBe 0
        // The spent place, the marker and the revoked key history remain; the workspace is inactive, never legacy.
        w.db.jdbc
            .sql("SELECT account_id IS NULL AND workspace_id IS NULL FROM beta_admission WHERE place = ?")
            .param(admission.place)
            .query(Boolean::class.java)
            .single() shouldBe true
        w.db.jdbc
            .sql("SELECT count(*) FROM participant_workspace WHERE workspace_id = ?")
            .param(admission.workspaceId.value)
            .query(Long::class.java)
            .single() shouldBe 1
        w.eligibility.status(admission.workspaceId) shouldBe WorkspaceStatus.INACTIVE_PARTICIPANT
        w.db.jdbc
            .sql("SELECT count(*) FROM api_key WHERE workspace_id = ? AND revoked_at IS NOT NULL")
            .param(admission.workspaceId.value)
            .query(Long::class.java)
            .single() shouldBe 1
        // Deletion never releases the place: the next participant gets a new one.
        w.participant().second.place shouldBe admission.place + 1
    }

    @Test
    fun `a restored database is quarantined until reconciled, and reconciliation revokes what the backup restored`() {
        val w = World()
        val (account, admission) = w.participant()
        val key = w.key(admission)
        w.session(account)
        // A restore never carries the clearance row (backup scope '-'); this is that database.
        w.db.jdbc
            .sql("DELETE FROM lifecycle_restore_clearance")
            .update()
        w.db.jdbc
            .sql("DELETE FROM human_session")
            .update()
        w.db.jdbc
            .sql("UPDATE beta_cohort SET admissions_open = true, capacity_ready = true")
            .update()

        w.eligibility.status(admission.workspaceId) shouldBe WorkspaceStatus.INACTIVE_PARTICIPANT
        w.authenticateKey.authenticate(key).shouldBeNull()
        IssueHumanSession(w.sessions, w.tx, clock).execute(account).shouldBeNull()
        w.admit.execute(w.account()) shouldBe AdmitBetaParticipant.Result.Ineligible
        w.change.execute(account, AccountLifecycleAction.RESUME).shouldBeInstanceOf<ChangeAccountState.Result.Refused>()
        w
            .t1(admission.workspaceId, w.inbox(admission.workspaceId, admission.projectId))
            .shouldBeInstanceOf<StorageAdmissionDecision.Inactive>()

        val reconcile = ReconcileRestoredDatabase(w.lifecycle, w.quotas, w.revokeSessions, w.revokeKeys, w.tx)
        reconcile.execute() shouldBe ReconcileRestoredDatabase.Result.Reconciled(1, 1, 1)
        w.state(account) shouldBe "SUSPENDED"
        w.db.jdbc
            .sql("SELECT admissions_open OR capacity_ready FROM beta_cohort")
            .query(Boolean::class.java)
            .single() shouldBe false
        reconcile.execute() shouldBe ReconcileRestoredDatabase.Result.NotQuarantined

        // Resuming per the operator's external record: the restored key stays revoked.
        w.done(w.change.execute(account, AccountLifecycleAction.RESUME)).to shouldBe HumanAccountState.ACTIVE
        w.authenticateKey.authenticate(key).shouldBeNull()
        w.authenticateSession.execute(w.session(account)).shouldNotBeNull()
    }

    @Test
    fun `upgrading a database that already has participants starts quarantined, an empty one does not`() {
        val populated = LedgerTestDatabase.create(postgres, admin, target = "21")
        val ws = populated.workspace()
        populated.jdbc
            .sql(
                "INSERT INTO human_account (id, contact_email, contact_verified_at, created_at) VALUES (?, 'a@example.invalid', now(), now())",
            ).param(ws)
            .update()
        populated.jdbc
            .sql(
                "INSERT INTO beta_admission (cohort_id, place, account_id, workspace_id, project_id, admitted_at) VALUES ('beta-30', 1, ?, ?, ?, now())",
            ).params(ws, ws, ws)
            .update()
        populated.jdbc
            .sql("INSERT INTO participant_workspace (workspace_id, cohort_id, place, allocated_at) VALUES (?, 'beta-30', 1, now())")
            .param(ws)
            .update()
        LedgerTestDatabase.flyway(populated.dataSource).migrate()
        JdbcAccountLifecycle(populated.jdbc).quarantined() shouldBe true
        JdbcWorkspaceEligibility(populated.jdbc).status(WorkspaceId(ws)) shouldBe WorkspaceStatus.INACTIVE_PARTICIPANT

        val empty = LedgerTestDatabase.create(postgres, admin)
        JdbcAccountLifecycle(empty.jdbc).quarantined() shouldBe false
        // A workspace without a marker is legacy and unaffected by lifecycle or quarantine.
        JdbcWorkspaceEligibility(empty.jdbc).status(WorkspaceId(empty.workspace())) shouldBe WorkspaceStatus.LEGACY
    }
}
