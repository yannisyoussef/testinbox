package email.testinbox.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The real `java -jar api.jar account-lifecycle ...` entry point, run as its
 * own JVM against PostgreSQL: it is dispatched before Spring, so it never
 * starts a web listener, and its authority is the database role alone.
 */
class AccountLifecycleCommandProcessTest : ApiIntegrationTestBase() {
    @Autowired lateinit var jdbc: JdbcClient

    private val suffix = UUID.randomUUID().toString().take(8)

    private data class Run(
        val code: Int,
        val out: String,
        val err: String,
    )

    private fun cli(
        env: Map<String, String>,
        vararg args: String,
    ): Run {
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElseThrow()
        val outFile = File.createTempFile("lifecycle", ".out").also { it.deleteOnExit() }
        val errFile = File.createTempFile("lifecycle", ".err").also { it.deleteOnExit() }
        val process =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "email.testinbox.api.ApiApplicationKt", *args)
                .redirectOutput(outFile)
                .redirectError(errFile)
                .also { builder ->
                    // Only what this test passes: no inherited TI_LIFECYCLE_* or Spring settings.
                    builder.environment().keys.removeIf { it.startsWith("TI_") || it.startsWith("SPRING_") || it.startsWith("TESTINBOX_") }
                    builder.environment().putAll(env)
                }.start()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "the operator command did not exit" }
        return Run(process.exitValue(), outFile.readText(), errFile.readText())
    }

    private fun role(
        name: String,
        operator: Boolean,
    ): Map<String, String> {
        jdbc.sql("CREATE ROLE $name LOGIN PASSWORD 'pw-$name'").update()
        val database = jdbc.sql("SELECT current_database()").query(String::class.java).single()
        jdbc.sql("GRANT CONNECT ON DATABASE $database TO $name").update()
        jdbc.sql("GRANT USAGE ON SCHEMA public TO $name").update()
        jdbc.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $name").update()
        if (operator) {
            val owner =
                jdbc
                    .sql("SELECT relowner::regrole::text FROM pg_class WHERE oid = 'public.human_account'::regclass")
                    .query(String::class.java)
                    .single()
            jdbc.sql("GRANT \"$owner\" TO $name").update()
        }
        return mapOf(
            "TI_LIFECYCLE_JDBC_URL" to postgres.jdbcUrl.substringBefore('?'),
            "TI_LIFECYCLE_USER" to name,
            "TI_LIFECYCLE_PASSWORD" to "pw-$name",
        )
    }

    private fun account(): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO human_account VALUES (?, ?, now(), 'ACTIVE', now())").params(id, "$id@example.invalid").update()
        return id
    }

    private fun state(id: UUID) =
        jdbc
            .sql("SELECT state FROM human_account WHERE id = ?")
            .param(id)
            .query(String::class.java)
            .single()

    private fun Run.neverStartedSpring() {
        listOf(out, err).forEach { text ->
            text.contains("Tomcat") shouldBe false
            text.contains("Started ApiApplication") shouldBe false
            text.contains("Spring Boot") shouldBe false
        }
    }

    @Test
    fun `without credentials it refuses before any connection and starts no web listener`() {
        val run = cli(emptyMap(), "account-lifecycle", "status", UUID.randomUUID().toString())
        run.code shouldBe AccountLifecycleCommand.EXIT_REFUSED
        run.err shouldContain "missing TI_LIFECYCLE_JDBC_URL"
        run.neverStartedSpring()
    }

    @Test
    fun `a runtime role with plain DML is not the operator authority and changes nothing`() {
        val id = account()
        val run = cli(role("ti_cli_rt_$suffix", operator = false), "account-lifecycle", "suspend", id.toString())
        run.code shouldBe AccountLifecycleCommand.EXIT_REFUSED
        run.err shouldContain "operator database authority is required"
        run.neverStartedSpring()
        state(id) shouldBe "ACTIVE"
    }

    @Test
    fun `the operator role suspends and resumes with metadata-only output while the API keeps serving`() {
        val id = account()
        val operator = role("ti_cli_op_$suffix", operator = true)
        val suspended = cli(operator, "account-lifecycle", "suspend", id.toString())
        suspended.code shouldBe AccountLifecycleCommand.EXIT_DONE
        suspended.out shouldContain "event=account.lifecycle action=SUSPEND account=$id"
        suspended.out shouldContain "to=SUSPENDED"
        suspended.out shouldContain "phase=SETTLED"
        suspended.out.contains("@example.invalid") shouldBe false
        suspended.out.contains("pw-") shouldBe false
        suspended.neverStartedSpring()
        state(id) shouldBe "SUSPENDED"

        val status = cli(operator, "account-lifecycle", "status", id.toString())
        status.code shouldBe AccountLifecycleCommand.EXIT_DONE
        status.out shouldContain "state=SUSPENDED"
        status.out shouldContain "quarantined=false"

        cli(operator, "account-lifecycle", "resume", id.toString()).code shouldBe AccountLifecycleCommand.EXIT_DONE
        state(id) shouldBe "ACTIVE"
        cli(operator, "account-lifecycle", "status", UUID.randomUUID().toString()).code shouldBe AccountLifecycleCommand.EXIT_REFUSED
        // On a live (cleared) database, restore reconciliation refuses rather than suspending everyone.
        cli(operator, "account-lifecycle", "restore-reconcile").code shouldBe AccountLifecycleCommand.EXIT_REFUSED
        // Legacy traffic is unaffected throughout.
        get("/v1/workspace").statusCode.value() shouldBe 200
    }
}
