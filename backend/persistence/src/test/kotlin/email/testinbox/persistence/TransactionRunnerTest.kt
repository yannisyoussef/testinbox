package email.testinbox.persistence

import email.testinbox.application.port.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

class TransactionRunnerTest : PersistenceIntegrationTest() {
    @Autowired lateinit var transactions: TransactionRunner

    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun `a nullable result commits without becoming an exception`() {
        val id = UUID.randomUUID()
        val result =
            transactions.required<String?> {
                insert(id)
                null
            }
        result shouldBe null
        exists(id) shouldBe true
    }

    @Test
    fun `non null values unit and nested nullable results are preserved`() {
        transactions.required { "ok" } shouldBe "ok"
        transactions.required { Unit } shouldBe Unit
        transactions.required { transactions.required<String?> { null } } shouldBe null
    }

    @Test
    fun `an exception still rolls back and preserves its original cause`() {
        val id = UUID.randomUUID()
        val expected = IllegalStateException("deliberate transaction failure")
        shouldThrow<IllegalStateException> {
            transactions.required {
                insert(id)
                throw expected
            }
        } shouldBe expected
        exists(id) shouldBe false
    }

    private fun insert(id: UUID) {
        jdbc
            .sql("INSERT INTO workspace (id, name, created_at) VALUES (:id, 'transaction-test', now())")
            .param("id", id)
            .update()
    }

    private fun exists(id: UUID): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM workspace WHERE id = :id)")
            .param("id", id)
            .query(Boolean::class.javaObjectType)
            .single()
}
