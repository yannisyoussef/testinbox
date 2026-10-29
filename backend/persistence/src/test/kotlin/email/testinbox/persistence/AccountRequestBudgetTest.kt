package email.testinbox.persistence

import email.testinbox.application.port.AccountRequestPolicy
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.AuthenticateHumanSession
import email.testinbox.application.usecase.ChargeAccountRequest
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.domain.identity.HumanAccountId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AccountRequestBudgetTest : PersistenceIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var budgets: JdbcAccountRequestBudgets

    @Autowired lateinit var tx: TransactionRunner

    @Autowired lateinit var sessions: JdbcHumanSessions
    private val clock = Clock.systemUTC()

    @BeforeEach fun resetBudgets() {
        jdbc.sql("DELETE FROM human_request_budget").update()
        // A fixed future window keeps boundary/concurrency assertions independent
        // of the wall clock crossing a minute during a test, without sleeps.
        jdbc.sql("UPDATE human_request_global SET window_start=now()+interval '1 hour',requests=0,last_allowed=false").update()
    }

    private fun account(): HumanAccountId {
        val id = HumanAccountId(UUID.randomUUID())
        jdbc
            .sql("INSERT INTO human_account VALUES (:id,:email,now(),'ACTIVE',now())")
            .param("id", id.value)
            .param("email", "${id.value}@example.invalid")
            .update()
        jdbc.sql("INSERT INTO human_request_budget VALUES (:id,now()+interval '1 hour',0,false)").param("id", id.value).update()
        return id
    }

    private fun charge(
        id: HumanAccountId,
        policy: AccountRequestPolicy = AccountRequestPolicy(),
    ) = tx.required { budgets.charge(id, policy) }

    @Test fun `account budget is shared across independent adapters and sessions`() {
        val account = account()
        val first = checkNotNull(IssueHumanSession(sessions, tx, clock).execute(account))
        val second = checkNotNull(IssueHumanSession(sessions, tx, clock).execute(account))

        fun useCase(adapter: JdbcAccountRequestBudgets) =
            ChargeAccountRequest(
                AuthenticateHumanSession(sessions, tx, clock),
                adapter,
                tx,
                AccountRequestPolicy(perAccount = 2),
            )
        useCase(budgets).execute(first.credential.value)!!.allowed shouldBe true
        useCase(JdbcAccountRequestBudgets(jdbc)).execute(second.credential.value)!!.allowed shouldBe true
        val refused = useCase(budgets).execute(first.credential.value)!!
        refused.allowed shouldBe false
        (refused.retryAfter.seconds > 0) shouldBe true
        jdbc
            .sql("SELECT count(*) FROM human_request_budget WHERE account_id=:id")
            .param("id", account.value)
            .query(Long::class.javaObjectType)
            .single() shouldBe 1
    }

    @Test fun `a per-account refusal still spends the global budget across different accounts`() {
        val a = account()
        val b = account()
        val policy = AccountRequestPolicy(perAccount = 1, global = 3)
        charge(a, policy).allowed shouldBe true
        charge(a, policy).allowed shouldBe false
        charge(b, policy).allowed shouldBe true
        charge(account(), policy).allowed shouldBe false
        jdbc.sql("SELECT requests FROM human_request_global").query(Int::class.javaObjectType).single() shouldBe 3
    }

    @Test fun `eight concurrent independent adapters cannot overspend one account`() {
        val id = account()
        concurrent(List(8) { id }, AccountRequestPolicy(perAccount = 3)).count { it } shouldBe 3
        jdbc
            .sql("SELECT requests FROM human_request_budget WHERE account_id=:id")
            .param("id", id.value)
            .query(Int::class.javaObjectType)
            .single() shouldBe 3
    }

    @Test fun `global budget serializes different accounts without an in-process mutex`() {
        concurrent(List(8) { account() }, AccountRequestPolicy(global = 3)).count { it } shouldBe 3
        jdbc.sql("SELECT requests FROM human_request_global").query(Int::class.javaObjectType).single() shouldBe 3
    }

    private fun concurrent(
        ids: List<HumanAccountId>,
        policy: AccountRequestPolicy,
    ): List<Boolean> {
        val ready = CountDownLatch(ids.size)
        val go = CountDownLatch(1)
        return Executors.newFixedThreadPool(ids.size).use { pool ->
            val futures =
                ids.map { id ->
                    pool.submit<Boolean> {
                        ready.countDown()
                        check(go.await(10, TimeUnit.SECONDS))
                        tx.required { JdbcAccountRequestBudgets(jdbc).charge(id, policy).allowed }
                    }
                }
            check(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        }
    }

    @Test fun `only the database clock advances a spent window and no history accumulates`() {
        val id = account()
        val policy = AccountRequestPolicy(perAccount = 1)
        charge(id, policy).allowed shouldBe true
        charge(id, policy).allowed shouldBe false
        jdbc.sql("UPDATE human_request_budget SET window_start=now()-interval '2 minutes'").update()
        jdbc.sql("UPDATE human_request_global SET window_start=now()-interval '2 minutes'").update()
        charge(id, policy).allowed shouldBe true
        jdbc
            .sql("SELECT requests FROM human_request_budget WHERE account_id=:id")
            .param("id", id.value)
            .query(Int::class.javaObjectType)
            .single() shouldBe 1
        jdbc.sql("SELECT count(*) FROM human_request_global").query(Long::class.javaObjectType).single() shouldBe 1
    }

    @Test fun `rollback spends neither budget and adapter refuses a missing transaction`() {
        val id = account()
        shouldThrow<IllegalStateException> { budgets.charge(id, AccountRequestPolicy()) }
        shouldThrow<IllegalStateException> {
            tx.required {
                budgets.charge(id, AccountRequestPolicy())
                error("abort")
            }
        }
        jdbc.sql("SELECT requests FROM human_request_global").query(Int::class.javaObjectType).single() shouldBe 0
        jdbc
            .sql("SELECT requests FROM human_request_budget WHERE account_id=:id")
            .param("id", id.value)
            .query(Int::class.javaObjectType)
            .single() shouldBe 0
    }

    @Test fun `invalid or suspended sessions cannot allocate a request budget`() {
        val gate = ChargeAccountRequest(AuthenticateHumanSession(sessions, tx, clock), budgets, tx)
        gate.execute(null) shouldBe null
        gate.execute("invalid") shouldBe null
        val id = account()
        val credential = checkNotNull(IssueHumanSession(sessions, tx, clock).execute(id)).credential
        jdbc.sql("UPDATE human_account SET state='SUSPENDED' WHERE id=:id").param("id", id.value).update()
        gate.execute(credential.value) shouldBe null
        jdbc.sql("SELECT requests FROM human_request_global").query(Int::class.javaObjectType).single() shouldBe 0
    }

    @Test fun `fixed maximum ceilings cannot be raised by configuration or request fields`() {
        shouldThrow<IllegalArgumentException> { AccountRequestPolicy(perAccount = 61) }
        shouldThrow<IllegalArgumentException> { AccountRequestPolicy(global = 3601) }
        shouldThrow<IllegalArgumentException> { AccountRequestPolicy(perAccount = 0) }
    }
}
