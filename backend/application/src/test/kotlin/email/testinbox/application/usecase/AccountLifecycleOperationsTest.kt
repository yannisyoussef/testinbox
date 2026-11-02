package email.testinbox.application.usecase

import email.testinbox.application.InMemoryApiKeyRepository
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryReservations
import email.testinbox.application.MutableClock
import email.testinbox.application.NoopTx
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.AccountLifecycleStore
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.port.LifecycleMembership
import email.testinbox.application.port.WorkspaceQuotaState
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
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
import java.time.Instant
import java.util.UUID

/** The lifecycle rules over fakes; PostgreSQL locking, authority and races are proven in the persistence suite. */
class AccountLifecycleOperationsTest {
    private val clock = MutableClock(Instant.parse("2026-10-30T12:00:00Z"))
    private val events = mutableListOf<String>()
    private val inboxes = InMemoryInboxRepository()
    private val store = FakeLifecycleStore(events, inboxes)
    private val guard = RecordingGuard(events)
    private val sessions = FakeSessions(events)
    private val apiKeys = InMemoryApiKeyRepository()
    private val revokeKeys = RevokeApiKey(apiKeys, clock)
    private val revokeSessions = RevokeAccountSessions(sessions, NoopTx)
    private val change = ChangeAccountState(store, guard, revokeSessions, revokeKeys, NoopTx)

    private val account = HumanAccountId(UUID.randomUUID())
    private val workspace = WorkspaceId(UUID.randomUUID())
    private val project = ProjectId(UUID.randomUUID())

    private fun participant(state: HumanAccountState = HumanAccountState.ACTIVE) {
        store.states[account] = state
        store.memberships[account] = workspace
        sessions.add(account)
        sessions.add(account)
    }

    private fun key(revoked: Boolean = false): ApiKey =
        apiKeys.put(
            ApiKey(
                id = ApiKeyId(UUID.randomUUID()),
                workspaceId = workspace,
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

    private fun legacyKey(
        workspaceId: WorkspaceId,
        scopes: Set<ApiScope>,
        revokedAt: Instant? = null,
        kind: ApiKeyKind = ApiKeyKind.MANAGED,
    ): ApiKey =
        apiKeys.put(
            ApiKey(
                id = ApiKeyId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = project,
                keyHash =
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .repeat(2),
                scopes = scopes,
                createdAt = clock.now.minusSeconds(3600),
                revokedAt = revokedAt,
                kind = kind,
                publicId =
                    if (kind == ApiKeyKind.MANAGED) {
                        UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                            .take(16)
                    } else {
                        null
                    },
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
    private val removeContent = RemoveAccountContent(store, deleteInbox, NoopTx)

    @Test
    fun `content removal waits for the drain, then deletes through the existing inbox lifecycle`() {
        participant()
        val live = listOf(inbox(), inbox(InboxState.EXPIRING))
        val expired = inbox(InboxState.EXPIRED)
        removeContent.execute(account) shouldBe RemoveAccountContent.Result.NotDeletionPending(HumanAccountState.ACTIVE)
        done(change.execute(account, AccountLifecycleAction.DELETE)).to shouldBe HumanAccountState.DELETION_PENDING

        store.reservations[workspace] = 1
        val draining = removeContent.execute(account).shouldBeInstanceOf<RemoveAccountContent.Result.Done>()
        draining.phase shouldBe RemoveAccountContent.Phase.DRAINING
        live.all { inboxes.inboxes.getValue(it.id).state != InboxState.DELETED } shouldBe true

        store.reservations[workspace] = 0
        val pending = removeContent.execute(account).shouldBeInstanceOf<RemoveAccountContent.Result.Done>()
        pending.phase shouldBe RemoveAccountContent.Phase.CONTENT_PENDING
        pending.inboxesMarkedDeleted shouldBe 2
        live.all { inboxes.inboxes.getValue(it.id).state == InboxState.DELETED } shouldBe true
        inboxes.inboxes.getValue(expired.id).state shouldBe InboxState.EXPIRED

        // The existing retention sweep removes the rows (and objects) later.
        inboxes.findHardDeletable(10).forEach { inboxes.hardDelete(it.id) }
        val removed = removeContent.execute(account).shouldBeInstanceOf<RemoveAccountContent.Result.Done>()
        removed.phase shouldBe RemoveAccountContent.Phase.CONTENT_REMOVED
        removed.inboxesMarkedDeleted shouldBe 0
        // Deletion never touches the place, the marker or the account row itself.
        store.erased shouldBe emptyList()
        store.states[account] shouldBe HumanAccountState.DELETION_PENDING
    }

    private val erase = EraseAccountIdentity(store, guard, revokeSessions, revokeKeys, NoopTx)

    @Test
    fun `identity erasure requires deletion pending and no remaining content, then keeps the workspace marker`() {
        participant()
        inbox()
        erase.execute(account) shouldBe EraseAccountIdentity.Result.NotDeletionPending(HumanAccountState.ACTIVE)
        done(change.execute(account, AccountLifecycleAction.DELETE))
        erase.execute(account) shouldBe EraseAccountIdentity.Result.ContentRemains(0, 1)
        removeContent.execute(account)
        inboxes.findHardDeletable(10).forEach { inboxes.hardDelete(it.id) }
        erase.execute(account) shouldBe EraseAccountIdentity.Result.Erased(workspace)
        store.erased shouldBe listOf(account to workspace)
    }

    private val reconcile = ReconcileRestoredDatabase(store, guard, revokeSessions, revokeKeys, NoopTx)

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
        store.quarantined = true

        reconcile.execute() shouldBe ReconcileRestoredDatabase.Result.Reconciled(3, 1, 1, 0)
        store.states[account] shouldBe HumanAccountState.SUSPENDED
        store.states[other] shouldBe HumanAccountState.SUSPENDED
        apiKeys.keys.getValue(k.id).isRevoked() shouldBe true
        sessions.count(account) shouldBe 0
        store.quarantined shouldBe false
        // Guards (sorted), then the cohort row and fence, then every credential write; clearance last.
        val guards = events.filter { it.startsWith("guard:") }
        guards shouldBe guards.sortedBy { UUID.fromString(it.removePrefix("guard:")) }
        events.indexOf("cohort+fence") shouldBe events.indexOfLast { it.startsWith("guard:") } + 1
        events.indexOf("credentials") shouldBe events.indexOf("cohort+fence") + 1
        events.indexOf("close") shouldBe events.indexOf("credentials") + 1
        events.last() shouldBe "clear"
        reconcile.execute() shouldBe ReconcileRestoredDatabase.Result.NotQuarantined
    }

    @Test
    fun `restore reconciliation also revokes every owner and monitoring key, and only the restored ones`() {
        participant()
        val participantKey = key()
        val owner = WorkspaceId(UUID.randomUUID())
        val ownerAdmin = legacyKey(owner, setOf(ApiScope.API_KEYS_MANAGE, ApiScope.INBOXES_WRITE))
        val synthetic = legacyKey(owner, setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ))
        val alreadyRevoked = legacyKey(owner, setOf(ApiScope.MESSAGES_READ), revokedAt = clock.now.minusSeconds(60))
        val bootstrap = legacyKey(owner, ApiScope.entries.toSet(), kind = ApiKeyKind.BOOTSTRAP)
        store.quarantined = true

        reconcile.execute() shouldBe ReconcileRestoredDatabase.Result.Reconciled(1, 1, 1, 2)
        listOf(participantKey, ownerAdmin, synthetic).forEach { apiKeys.keys.getValue(it.id).revokedAt shouldBe clock.now }
        // History is kept: an earlier revocation keeps its own time, and nothing is deleted.
        apiKeys.keys.getValue(alreadyRevoked.id).revokedAt shouldBe clock.now.minusSeconds(60)
        apiKeys.keys.size shouldBe 5
        // Configuration, not restored data: the bootstrap row is not revoked. It reopens
        // because no usable managed administrator remains (ADR-032 §8), which is the
        // owner's way to mint a new administrator and re-key monitoring.
        apiKeys.keys.getValue(bootstrap.id).isRevoked() shouldBe false
        apiKeys.hasUsableManagedAdmin(owner, clock.now) shouldBe false
        events.indexOf("credentials") shouldBe events.indexOf("cohort+fence") + 1
        events.last() shouldBe "clear"
    }

    @Test
    fun `a reconciliation that is refused revokes no owner or monitoring key`() {
        val owner = WorkspaceId(UUID.randomUUID())
        val ownerAdmin = legacyKey(owner, setOf(ApiScope.API_KEYS_MANAGE))
        reconcile.execute() shouldBe ReconcileRestoredDatabase.Result.NotQuarantined
        store.quarantined = true
        store.clearedWhileWaiting = true
        reconcile.execute() shouldBe ReconcileRestoredDatabase.Result.NotQuarantined
        apiKeys.keys.getValue(ownerAdmin.id).isRevoked() shouldBe false
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
    ) : AccountLifecycleStore {
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

        override fun lockCredentials() {
            events += "credentials"
        }

        override fun clearQuarantine() {
            events += "clear"
            quarantined = false
        }

        override fun eraseIdentity(
            accountId: HumanAccountId,
            workspaceId: WorkspaceId?,
        ) {
            erased += accountId to workspaceId
            states.remove(accountId)
            memberships.remove(accountId)
        }
    }
}
