package email.testinbox.persistence

import email.testinbox.application.port.InboxCursor
import email.testinbox.application.port.MessageCursor
import email.testinbox.domain.ProjectId
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.limits.RatePolicy
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The console API's reads against real Postgres: scoping, ordering, keyset paging, servability. */
class WorkspaceReadsTest : PersistenceIntegrationTest() {
    @Autowired lateinit var inboxes: JdbcInboxRepository

    @Autowired lateinit var messages: JdbcMessageRepository

    @Autowired lateinit var tenants: JdbcTenantReader

    @Autowired lateinit var waitSlots: JdbcWaitSlots

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `inbox listing is workspace-scoped, newest first, keyset-paged and state-filterable`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val (otherWorkspace, otherProject) = Fixtures.provisionTenant(jdbc)
        val t0 = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(100)
        val mine = (1..5).map { i -> Fixtures.inbox(workspaceId, projectId, now = t0.plusSeconds(i.toLong())).also { inboxes.insert(it) } }
        inboxes.insert(Fixtures.inbox(otherWorkspace, otherProject, now = t0.plusSeconds(10)))
        inboxes.markDeleted(workspaceId, mine[1].id, t0.plusSeconds(50))

        // Newest first, every state still present, only this workspace.
        val all = inboxes.listPage(workspaceId, emptySet(), before = null, limit = 50)
        all.map { it.id } shouldContainExactly mine.reversed().map { it.id }
        all.first { it.id == mine[1].id }.state shouldBe InboxState.DELETED

        // Keyset paging: the second page starts strictly after the first's last row.
        val page1 = inboxes.listPage(workspaceId, emptySet(), before = null, limit = 2)
        val page2 = inboxes.listPage(workspaceId, emptySet(), before = InboxCursor(page1.last().createdAt, page1.last().id), limit = 2)
        val page3 = inboxes.listPage(workspaceId, emptySet(), before = InboxCursor(page2.last().createdAt, page2.last().id), limit = 2)
        (page1 + page2 + page3).map { it.id } shouldContainExactly mine.reversed().map { it.id }

        // The state filter.
        inboxes.listPage(workspaceId, setOf(InboxState.DELETED), before = null, limit = 50).map { it.id } shouldContainExactly
            listOf(mine[1].id)
        inboxes.listPage(workspaceId, setOf(InboxState.ACTIVE), before = null, limit = 50).size shouldBe 4
        inboxes.listPage(otherWorkspace, setOf(InboxState.ACTIVE), before = null, limit = 50).size shouldBe 1
    }

    @Test
    fun `workspace message listing joins on inbox state and walks both ways on one cursor shape`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val (otherWorkspace, otherProject) = Fixtures.provisionTenant(jdbc)
        val t0 = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(100)
        val serving = Fixtures.inbox(workspaceId, projectId).also { inboxes.insert(it) }
        val expired = Fixtures.inbox(workspaceId, projectId, state = InboxState.EXPIRED).also { inboxes.insert(it) }
        val foreign = Fixtures.inbox(otherWorkspace, otherProject).also { inboxes.insert(it) }
        val m = (1..4).map { i -> Fixtures.message(serving, receivedAt = t0.plusSeconds(i.toLong())).also { messages.appendVisible(it) } }
        messages.appendVisible(Fixtures.message(expired, receivedAt = t0.plusSeconds(2)))
        messages.appendVisible(Fixtures.message(foreign, receivedAt = t0.plusSeconds(3)))

        // Newest first by default; the expired inbox's and the foreign row never appear.
        messages.listWorkspacePage(workspaceId, cursor = null, limit = 50, descending = true).map { it.id } shouldContainExactly
            m.reversed().map { it.id }
        // Descending keyset: strictly before the cursor.
        val c = MessageCursor(m[2].receivedAt, m[2].id)
        messages.listWorkspacePage(workspaceId, c, 50, descending = true).map { it.id } shouldContainExactly listOf(m[1].id, m[0].id)
        // Ascending from the same cursor: strictly after it — how a client catches up.
        messages.listWorkspacePage(workspaceId, c, 50, descending = false).map { it.id } shouldContainExactly listOf(m[3].id)
        messages.listWorkspacePage(otherWorkspace, null, 50, descending = true).size shouldBe 1

        // Once the serving inbox is deleted its messages stop being listed, rows or not.
        inboxes.markDeleted(workspaceId, serving.id, Instant.now())
        messages.listWorkspacePage(workspaceId, null, 50, descending = true).shouldBeEmpty()
    }

    @Test
    fun `the tenant reader answers only the caller's own workspace and project`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val (otherWorkspace, otherProject) = Fixtures.provisionTenant(jdbc)
        val workspace = tenants.findWorkspace(workspaceId).shouldNotBeNull()
        workspace.name shouldBe "w"
        tenants.findProject(workspaceId, projectId).shouldNotBeNull().workspaceId shouldBe workspaceId
        // A foreign project id, even a real one, reads as absent.
        tenants.findProject(workspaceId, otherProject).shouldBeNull()
        tenants.findProject(otherWorkspace, projectId).shouldBeNull()
        tenants.findWorkspace(email.testinbox.domain.WorkspaceId(UUID.randomUUID())).shouldBeNull()
        tenants.findProject(workspaceId, ProjectId(UUID.randomUUID())).shouldBeNull()
    }

    @Test
    fun `a rate peek reports the bucket without spending, and a bucket never seen reads as full`() {
        val (workspaceId, _) = Fixtures.provisionTenant(jdbc)
        val policy = RatePolicy(capacity = 5, refillPerSecond = 0.001)
        val limiter = JdbcRateLimiter(jdbc, transactionManager) { _, _ -> policy }
        limiter.peek(workspaceId, RateCategory.READ) shouldBe 5
        limiter.tryConsume(workspaceId, RateCategory.READ).allowed shouldBe true
        limiter.tryConsume(workspaceId, RateCategory.READ).allowed shouldBe true
        limiter.peek(workspaceId, RateCategory.READ) shouldBe 3
        // Peeking repeatedly changes nothing.
        repeat(10) { limiter.peek(workspaceId, RateCategory.READ) }
        limiter.peek(workspaceId, RateCategory.READ) shouldBe 3
        limiter.tryConsume(workspaceId, RateCategory.READ).remaining shouldBe 2
    }

    @Test
    fun `held slots count live leases of one workspace only`() {
        val (workspaceId, _) = Fixtures.provisionTenant(jdbc)
        val (otherWorkspace, _) = Fixtures.provisionTenant(jdbc)
        waitSlots.held(workspaceId) shouldBe 0
        val a = waitSlots.acquire(workspaceId, 10, Duration.ofMinutes(1)).shouldNotBeNull()
        val b = waitSlots.acquire(workspaceId, 10, Duration.ofMinutes(1)).shouldNotBeNull()
        val other = waitSlots.acquire(otherWorkspace, 10, Duration.ofMinutes(1)).shouldNotBeNull()
        waitSlots.held(workspaceId) shouldBe 2
        waitSlots.held(otherWorkspace) shouldBe 1
        a.close()
        waitSlots.held(workspaceId) shouldBe 1
        b.close()
        other.close()
        waitSlots.held(workspaceId) shouldBe 0
    }
}
