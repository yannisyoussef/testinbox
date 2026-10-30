package email.testinbox.application.usecase

import email.testinbox.application.InMemoryApiKeyRepository
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryReservations
import email.testinbox.application.MutableClock
import email.testinbox.application.NoopTx
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.AccountDeletionRecord
import email.testinbox.application.port.AccountLifecycleStore
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.port.IdentityErasure
import email.testinbox.application.port.LifecycleMembership
import email.testinbox.application.port.WorkspaceQuotaState
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.AccountContentRemaining
import email.testinbox.domain.identity.AccountDeletionPolicy
import email.testinbox.domain.identity.AccountDeletionStage
import email.testinbox.domain.identity.AccountLifecycleAction
import email.testinbox.domain.identity.HumanAccount
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanAccountState
import email.testinbox.domain.identity.HumanSession
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiKeyKind
import email.testinbox.domain.tenant.ApiScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The lifecycle rules over fakes; PostgreSQL locking, authority and races are proven in the persistence suite. */
class AccountLifecycleOperationsTest {
    private val clock = MutableClock(Instant.parse("2026-10-30T12:00:00Z"))
    private val events = mutableListOf<String>()
    private val inboxes = InMemoryInboxRepository()
    private val apiKeys = InMemoryApiKeyRepository()
    private val store = FakeLifecycleStore(events, inboxes, apiKeys)
    private val guard = RecordingGuard(events)
    private val sessions = FakeSessions(events)
    private val revokeKeys = RevokeApiKey(apiKeys, clock)
    private val revokeSessions = RevokeAccountSessions(sessions, NoopTx)
    private val change = ChangeAccountState(store, guard, revokeSessions, revokeKeys, NoopTx, clock)

    private val account = HumanAccountId(UUID.randomUUID())
    private val workspace = WorkspaceId(UUID.randomUUID())
    private val project = ProjectId(UUID.randomUUID())

    private fun participant(state: HumanAccountState = HumanAccountState.ACTIVE) {
        store.states[account] = state
        store.memberships[account] = workspace
        sessions.add(account)
        sessions.add(account)
    }

    private fun key(
        revoked: Boolean = false,
        ws: WorkspaceId = workspace,
    ): ApiKey =
        apiKeys.put(
            ApiKey(
                id = ApiKeyId(UUID.randomUUID()),
                workspaceId = ws,
                projectId = project,
                keyHash = "0".repeat(64),
                scopes = setOf(ApiScope.INBOXES_WRITE),
                createdAt = clock.now,
                revokedAt = if (revoked) clock.now else null,
                kind = ApiKeyKind.MANAGED,
                publicId =
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(16),
            ),
        )

    private fun inbox(state: InboxState = InboxState.ACTIVE): Inbox =
        Inbox(
            id = InboxId(UUID.randomUUID()),
            workspaceId = workspace,
            projectId = project,
            address = "${UUID.randomUUID()}@testinbox.local",
            addressMode = AddressMode.GENERATED,
            state = state,
            createdAt = clock.now,
            expiresAt = clock.now.plusSeconds(600),
        ).also { inboxes.insert(it) }

    private fun done(result: ChangeAccountState.Result) = result.shouldBeInstanceOf<ChangeAccountState.Result.Done>().report

    @Test
    fun `suspension takes guard, fence and account in that order and revokes every session and managed key`() {
        participant()
        val keys = listOf(key(), key(), key(revoked = true))
        val report = done(change.execute(account, AccountLifecycleAction.SUSPEND))
        events.take(4) shouldBe listOf("authority", "guard:$workspace", "fence", "lock:$account")
        report.from shouldBe HumanAccountState.ACTIVE
        report.to shouldBe HumanAccountState.SUSPENDED
        report.changed shouldBe true
        report.revokedKeys shouldBe 2
        report.phase shouldBe LifecyclePhase.SETTLED
        store.states[account] shouldBe HumanAccountState.SUSPENDED
        sessions.count(account) shouldBe 0
        keys.all { apiKeys.keys.getValue(it.id).isRevoked() } shouldBe true
    }

    @Test
    fun `reservations made before the fence report draining until they are consumed or released`() {
        participant()
        store.reservations[workspace] = 2
        done(change.execute(account, AccountLifecycleAction.SUSPEND)).phase shouldBe LifecyclePhase.DRAINING
        // Re-running is idempotent: nothing changes, it only re-checks the drain.
        val again = done(change.execute(account, AccountLifecycleAction.SUSPEND))
        again.changed shouldBe false
        again.phase shouldBe LifecyclePhase.DRAINING
        store.reservations[workspace] = 0
        done(change.execute(account, AccountLifecycleAction.SUSPEND)).phase shouldBe LifecyclePhase.SETTLED
    }

    @Test
    fun `resume never resurrects revoked sessions or keys`() {
        participant()
        val k = key()
        done(change.execute(account, AccountLifecycleAction.SUSPEND))
        val resumed = done(change.execute(account, AccountLifecycleAction.RESUME))
        resumed.to shouldBe HumanAccountState.ACTIVE
        resumed.revokedKeys shouldBe 0
        resumed.phase shouldBe LifecyclePhase.ACTIVE
        apiKeys.keys.getValue(k.id).isRevoked() shouldBe true
        sessions.count(account) shouldBe 0
    }

    @Test
    fun `resume is refused while the database is restore-quarantined`() {
        participant(HumanAccountState.SUSPENDED)
        store.quarantined = true
        change.execute(account, AccountLifecycleAction.RESUME).shouldBeInstanceOf<ChangeAccountState.Result.Refused>()
        store.states[account] shouldBe HumanAccountState.SUSPENDED
    }

    @Test
    fun `deletion pending cannot be suspended or resumed, and nothing is revoked twice by a refusal`() {
        participant(HumanAccountState.DELETION_PENDING)
        for (action in listOf(AccountLifecycleAction.SUSPEND, AccountLifecycleAction.RESUME)) {
            change.execute(account, action).shouldBeInstanceOf<ChangeAccountState.Result.Refused>()
        }
        store.states[account] shouldBe HumanAccountState.DELETION_PENDING
        sessions.count(account) shouldBe 2
    }

    @Test
    fun `without the operator authority nothing is read or written`() {
        participant()
        store.authorized = false
        shouldThrow<OperatorAuthorityRequiredException> { change.execute(account, AccountLifecycleAction.SUSPEND) }
        store.states[account] shouldBe HumanAccountState.ACTIVE
        events shouldBe listOf("authority")
        sessions.count(account) shouldBe 2
    }

    @Test
    fun `an unknown account is reported, not created`() {
        change.execute(account, AccountLifecycleAction.SUSPEND) shouldBe ChangeAccountState.Result.AccountNotFound
    }

    @Test
    fun `an account admitted between the unlocked read and the locks is retried under its workspace guard`() {
        store.states[account] = HumanAccountState.ACTIVE
        // The first unlocked read sees no membership; the admission commits before the account lock.
        store.admitOnLock = account to workspace
        val report = done(change.execute(account, AccountLifecycleAction.SUSPEND))
        report.workspaceId shouldBe workspace
        events.count { it == "guard:$workspace" } shouldBe 1
    }

    @Test
    fun `an unadmitted account is suspended without any workspace work`() {
        store.states[account] = HumanAccountState.ACTIVE
        sessions.add(account)
        val report = done(change.execute(account, AccountLifecycleAction.SUSPEND))
        report.workspaceId shouldBe null
        report.phase shouldBe LifecyclePhase.SETTLED
        events.none { it.startsWith("guard:") } shouldBe true
        sessions.count(account) shouldBe 0
    }

    private val deleteInbox =
        DeleteInbox(inboxes, InMemoryReservations(), NoopTx, clock, TestInboxConfig(mailDomain = "testinbox.local"))
    private val deletion = ProcessAccountDeletion(store, guard, revokeSessions, revokeKeys, deleteInbox, NoopTx, clock)

    private fun advance() = deletion.execute(account).shouldBeInstanceOf<ProcessAccountDeletion.Result.Advanced>().progress

    @Test
    fun `deletion opens its record with the state change and walks DRAINING, CONTENT_DELETING, ERASED`() {
        participant()
        val live = listOf(inbox(), inbox(InboxState.EXPIRING))
        val expired = inbox(InboxState.EXPIRED)
        deletion.execute(account) shouldBe ProcessAccountDeletion.Result.NotDeletionPending(HumanAccountState.ACTIVE)
        store.reservations[workspace] = 1
        done(change.execute(account, AccountLifecycleAction.DELETE)).to shouldBe HumanAccountState.DELETION_PENDING
        store.deletions.getValue(account).stage shouldBe AccountDeletionStage.DRAINING
        store.deletions.getValue(account).requestedAt shouldBe clock.now

        // DRAINING: nothing is deleted while a reservation from before the fence is in flight.
        val draining = advance()
        draining.stage shouldBe AccountDeletionStage.DRAINING
        draining.remaining shouldBe AccountContentRemaining(1, 3, 0)
        live.all { inboxes.inboxes.getValue(it.id).state != InboxState.DELETED } shouldBe true

        // CONTENT_DELETING: every live inbox through the existing deleter; the sweep has not run yet.
        store.reservations[workspace] = 0
        clock.advanceSeconds(60)
        val deleting = advance()
        deleting.stage shouldBe AccountDeletionStage.CONTENT_DELETING
        deleting.inboxesMarkedDeleted shouldBe 2
        deleting.remaining.inboxRows shouldBe 3
        live.all { inboxes.inboxes.getValue(it.id).state == InboxState.DELETED } shouldBe true
        inboxes.inboxes.getValue(expired.id).state shouldBe InboxState.EXPIRED
        store.deletions.getValue(account).stage shouldBe AccountDeletionStage.CONTENT_DELETING
        store.erased shouldBe emptyList()

        // The existing retention sweep removes the rows (objects first); the next run erases.
        inboxes.findHardDeletable(10).forEach { inboxes.hardDelete(it.id) }
        val erased = advance()
        erased.stage shouldBe AccountDeletionStage.ERASED
        erased.erasure shouldBe IdentityErasure(0, 0, 0, 0)
        store.erased shouldBe listOf(account to workspace)
        store.deletions.getValue(account).stage shouldBe AccountDeletionStage.ERASED
        // Lock order of the erasing transaction: guard, fence, account.
        val erasing = events.lastIndexOf("authority")
        events.subList(erasing, erasing + 4) shouldBe listOf("authority", "guard:$workspace", "fence", "lock:$account")

        // Idempotent after the end: reports ERASED and touches nothing.
        val again = advance()
        again.stage shouldBe AccountDeletionStage.ERASED
        again.erasure shouldBe null
        again.accountRowAbsent shouldBe false
        store.erased.size shouldBe 1
    }

    @Test
    fun `identity is not erased while content remains, including a live EXACT cooldown`() {
        participant()
        done(change.execute(account, AccountLifecycleAction.DELETE))
        store.cooldownUntil[workspace] = clock.now.plusSeconds(3600)
        val held = advance()
        held.stage shouldBe AccountDeletionStage.CONTENT_DELETING
        held.remaining shouldBe AccountContentRemaining(0, 0, 1)
        store.erased shouldBe emptyList()
        clock.advanceSeconds(3601)
        advance().stage shouldBe AccountDeletionStage.ERASED
    }

    @Test
    fun `a run that dies between stages resumes from where it stopped`() {
        participant()
        inbox()
        done(change.execute(account, AccountLifecycleAction.DELETE))
        inboxes.findHardDeletable(10).size shouldBe 0
        // Crash inside the erasing transaction: nothing of the erasure survives (the real one is one transaction).
        store.failNextErase = true
        advance().stage shouldBe AccountDeletionStage.CONTENT_DELETING
        inboxes.findHardDeletable(10).forEach { inboxes.hardDelete(it.id) }
        shouldThrow<IllegalStateException> { deletion.execute(account) }
        store.erased shouldBe emptyList()
        store.deletions.getValue(account).stage shouldBe AccountDeletionStage.CONTENT_DELETING
        // The next run (the timer's) completes it.
        advance().stage shouldBe AccountDeletionStage.ERASED
        store.erased shouldBe listOf(account to workspace)
    }

    @Test
    fun `an unadmitted account is erased in one run, and a missing record is adopted by the timer`() {
        store.states[account] = HumanAccountState.DELETION_PENDING
        // Deletion-pending with no record: a restored or hand-edited database.
        deletion.pending().map { it.accountId } shouldBe listOf(account)
        val erased = advance()
        erased.stage shouldBe AccountDeletionStage.ERASED
        erased.workspaceId shouldBe null
        store.erased shouldBe listOf(account to null)
        deletion.pending() shouldBe emptyList()
    }

    @Test
    fun `a pending record whose account row is already gone is closed as ERASED, an unknown account is not found`() {
        store.deletions[account] = AccountDeletionRecord(account, AccountDeletionStage.CONTENT_DELETING, clock.now, clock.now, null)
        val closed = advance()
        closed.stage shouldBe AccountDeletionStage.ERASED
        closed.accountRowAbsent shouldBe true
        store.deletions.getValue(account).stage shouldBe AccountDeletionStage.ERASED
        deletion.execute(HumanAccountId(UUID.randomUUID())) shouldBe ProcessAccountDeletion.Result.AccountNotFound
    }

    @Test
    fun `the deletion report gives every pending deletion its live figures and the overdue verdict`() {
        participant()
        inbox()
        done(change.execute(account, AccountLifecycleAction.DELETE))
        val unrecorded = HumanAccountId(UUID.randomUUID())
        store.states[unrecorded] = HumanAccountState.DELETION_PENDING
        val report = AccountDeletionReport(store, NoopTx, clock, AccountDeletionPolicy(Duration.ofHours(72)))
        report.execute().let {
            it.entries.single().overdue shouldBe false
            it.entries
                .single()
                .remaining.inboxRows shouldBe 1
            it.unrecorded shouldBe listOf(unrecorded)
            it.overdue shouldBe 1
        }
        store.states.remove(unrecorded)
        clock.advanceSeconds(Duration.ofHours(72).seconds + 1)
        report.execute().let {
            it.entries.single().overdue shouldBe true
            it.entries.single().age shouldBe Duration.ofHours(72).plusSeconds(1)
            it.overdue shouldBe 1
        }
        // Read-only: nothing was adopted or advanced.
        store.deletions.keys shouldBe setOf(account)
        store.deletions.getValue(account).stage shouldBe AccountDeletionStage.DRAINING
    }

    private val reconcile = ReconcileRestoredDatabase(store, guard, revokeSessions, revokeKeys, NoopTx, clock)

    @Test
    fun `restore reconciliation refuses a database that is not quarantined`() {
        participant()
        reconcile.execute() shouldBe ReconcileRestoredDatabase.Result.NotQuarantined
        store.states[account] shouldBe HumanAccountState.ACTIVE
    }

    @Test
    fun `restore reconciliation suspends every restored participant, revokes their keys, closes the cohort, then clears`() {
        participant()
        val other = HumanAccountId(UUID.randomUUID())
        val otherWorkspace = WorkspaceId(UUID.randomUUID())
        store.states[other] = HumanAccountState.SUSPENDED
        store.memberships[other] = otherWorkspace
        val erasedWorkspace = WorkspaceId(UUID.randomUUID())
        store.orphanMarkers += erasedWorkspace
        val k = key()
        // An owner/monitoring (legacy) workspace's managed keys, one already revoked.
        val legacy = WorkspaceId(UUID.randomUUID())
        val legacyKeys = listOf(key(ws = legacy), key(ws = legacy), key(revoked = true, ws = legacy))
        store.quarantined = true

        reconcile.execute() shouldBe ReconcileRestoredDatabase.Result.Reconciled(3, 1, 1, 2)
        store.states[account] shouldBe HumanAccountState.SUSPENDED
        store.states[other] shouldBe HumanAccountState.SUSPENDED
        apiKeys.keys.getValue(k.id).isRevoked() shouldBe true
        legacyKeys.all { apiKeys.keys.getValue(it.id).isRevoked() } shouldBe true
        sessions.count(account) shouldBe 0
        store.quarantined shouldBe false
        // Guards (sorted), then the cohort row and fence, then accounts; clearance last.
        val guards = events.filter { it.startsWith("guard:") }
        guards shouldBe guards.sortedBy { UUID.fromString(it.removePrefix("guard:")) }
        events.indexOf("cohort+fence") shouldBe events.indexOfLast { it.startsWith("guard:") } + 1
        events.indexOf("close") shouldBe events.indexOf("cohort+fence") + 1
        events.takeLast(2) shouldBe listOf("revoke-every-managed-key", "clear")
        reconcile.execute() shouldBe ReconcileRestoredDatabase.Result.NotQuarantined
    }

    @Test
    fun `a reconciliation that loses the race to a concurrent one is a clean no-op`() {
        participant()
        store.quarantined = true
        store.clearedWhileWaiting = true
        reconcile.execute() shouldBe ReconcileRestoredDatabase.Result.NotQuarantined
        store.states[account] shouldBe HumanAccountState.ACTIVE
        events.none { it == "close" || it == "clear" || it.startsWith("state:") } shouldBe true
    }

    @Test
    fun `status reports the drain without changing anything`() {
        participant(HumanAccountState.SUSPENDED)
        store.reservations[workspace] = 1
        inbox()
        val status = AccountLifecycleStatus(store, NoopTx).execute(account)!!
        status.phase shouldBe LifecyclePhase.DRAINING
        status.inboxRows shouldBe 1
        status.quarantined shouldBe false
        AccountLifecycleStatus(store, NoopTx).execute(HumanAccountId(UUID.randomUUID())) shouldBe null
    }

    private class RecordingGuard(
        private val events: MutableList<String>,
    ) : WorkspaceQuotaState {
        override fun guardAdmission(workspaceId: WorkspaceId) {
            events += "guard:$workspaceId"
        }

        override fun activeInboxCount(workspaceId: WorkspaceId): Long = error("not used")

        override fun storedBytes(workspaceId: WorkspaceId): Long = error("not used")
    }

    private class FakeSessions(
        private val events: MutableList<String>,
    ) : HumanSessions {
        private val rows = mutableListOf<HumanAccountId>()

        fun add(account: HumanAccountId) {
            rows += account
        }

        fun count(account: HumanAccountId) = rows.count { it == account }

        override fun findAccountId(verifier: String): HumanAccountId? = null

        override fun lockAccount(accountId: HumanAccountId): HumanAccount =
            HumanAccount(accountId, "x@example.invalid", HumanAccountState.ACTIVE, true)

        override fun lockSession(
            accountId: HumanAccountId,
            verifier: String,
        ): HumanSession? = null

        override fun makeRoom(
            accountId: HumanAccountId,
            now: Instant,
        ) = Unit

        override fun insert(session: HumanSession) = Unit

        override fun touch(
            verifier: String,
            at: Instant,
        ) = Unit

        override fun delete(verifier: String) = Unit

        override fun deleteAll(accountId: HumanAccountId) {
            events += "sessions:$accountId"
            rows.removeAll { it == accountId }
        }
    }

    private class FakeLifecycleStore(
        private val events: MutableList<String>,
        private val inboxes: InMemoryInboxRepository,
        private val apiKeys: InMemoryApiKeyRepository,
    ) : AccountLifecycleStore {
        val deletions = LinkedHashMap<HumanAccountId, AccountDeletionRecord>()
        val cooldownUntil = HashMap<WorkspaceId, Instant>()
        var failNextErase = false

        var authorized = true
        var quarantined = false
        val states = HashMap<HumanAccountId, HumanAccountState>()
        val memberships = HashMap<HumanAccountId, WorkspaceId>()
        val reservations = HashMap<WorkspaceId, Long>()
        val orphanMarkers = mutableListOf<WorkspaceId>()
        val erased = mutableListOf<Pair<HumanAccountId, WorkspaceId?>>()
        var admitOnLock: Pair<HumanAccountId, WorkspaceId>? = null

        override fun operatorAuthorized(): Boolean {
            events += "authority"
            return authorized
        }

        override fun findWorkspace(accountId: HumanAccountId): WorkspaceId? = memberships[accountId]

        override fun lockStorageFence() {
            events += "fence"
        }

        override fun lockAccountState(accountId: HumanAccountId): HumanAccountState? {
            events += "lock:$accountId"
            admitOnLock?.takeIf { it.first == accountId }?.let {
                memberships[it.first] = it.second
                admitOnLock = null
            }
            return states[accountId]
        }

        override fun setState(
            accountId: HumanAccountId,
            state: HumanAccountState,
        ) {
            events += "state:$accountId:$state"
            states[accountId] = state
        }

        override fun reservationsHeld(workspaceId: WorkspaceId): Long = reservations[workspaceId] ?: 0

        override fun inboxRows(workspaceId: WorkspaceId): Long =
            inboxes.inboxes.values
                .count { it.workspaceId == workspaceId }
                .toLong()

        override fun undeletedInboxes(
            workspaceId: WorkspaceId,
            limit: Int,
        ): List<InboxId> =
            inboxes.inboxes.values
                .filter { it.workspaceId == workspaceId && it.state in setOf(InboxState.ACTIVE, InboxState.EXPIRING) }
                .take(limit)
                .map { it.id }

        override fun quarantined(): Boolean = quarantined

        override fun participants(): List<LifecycleMembership> =
            memberships.map { (account, ws) -> LifecycleMembership(account, ws) } + orphanMarkers.map { LifecycleMembership(null, it) }

        /** A reconciliation that commits while this one waits for the cohort lock. */
        var clearedWhileWaiting = false

        override fun lockCohortAndFence() {
            events += "cohort+fence"
            if (clearedWhileWaiting) quarantined = false
        }

        override fun closeCohort() {
            events += "close"
        }

        override fun clearQuarantine() {
            events += "clear"
            quarantined = false
        }

        override fun eraseIdentity(
            accountId: HumanAccountId,
            workspaceId: WorkspaceId?,
            at: Instant,
        ): IdentityErasure {
            check(!failNextErase.also { failNextErase = false }) { "simulated crash inside the erasing transaction" }
            val labels = workspaceId?.let { ws -> apiKeys.keys.values.count { it.workspaceId == ws && it.name != null } } ?: 0
            erased += accountId to workspaceId
            states.remove(accountId)
            memberships.remove(accountId)
            openDeletion(accountId, at)
            advanceDeletion(accountId, AccountDeletionStage.ERASED, at)
            return IdentityErasure(labels, 0, 0, 0)
        }

        override fun exactCooldowns(
            workspaceId: WorkspaceId,
            now: Instant,
        ): Long = if (cooldownUntil[workspaceId]?.isAfter(now) == true) 1 else 0

        override fun deletion(accountId: HumanAccountId): AccountDeletionRecord? = deletions[accountId]

        override fun openDeletion(
            accountId: HumanAccountId,
            at: Instant,
        ) {
            deletions.putIfAbsent(accountId, AccountDeletionRecord(accountId, AccountDeletionStage.DRAINING, at, at, null))
        }

        override fun advanceDeletion(
            accountId: HumanAccountId,
            stage: AccountDeletionStage,
            at: Instant,
        ) {
            val record = deletions[accountId] ?: return
            if (stage.ordinal <= record.stage.ordinal) return
            deletions[accountId] =
                record.copy(stage = stage, stageChangedAt = at, erasedAt = at.takeIf { stage == AccountDeletionStage.ERASED })
        }

        override fun pendingDeletions(): List<AccountDeletionRecord> =
            deletions.values.filter { it.stage != AccountDeletionStage.ERASED }.sortedBy { it.requestedAt }

        override fun unrecordedDeletions(): List<HumanAccountId> =
            states.filter { (id, state) -> state == HumanAccountState.DELETION_PENDING && id !in deletions }.keys.toList()

        override fun revokeEveryManagedKey(at: Instant): Int {
            events += "revoke-every-managed-key"
            return apiKeys.keys.values
                .map { it.workspaceId }
                .distinct()
                .sumOf { apiKeys.revokeAllManaged(it, at) }
        }
    }
}
