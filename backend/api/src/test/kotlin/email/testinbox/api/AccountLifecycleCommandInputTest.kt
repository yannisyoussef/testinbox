package email.testinbox.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID

/** Refusals that happen before any database connection: usage and explicit credentials. */
class AccountLifecycleCommandInputTest {
    private val account = UUID.randomUUID().toString()

    private fun run(
        vararg args: String,
        env: Map<String, String> = emptyMap(),
    ): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = AccountLifecycleCommand.run(arrayOf(*args), env::get, PrintStream(out), PrintStream(err))
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun `malformed invocations are usage errors and never read credentials`() {
        listOf(
            arrayOf("account-lifecycle"),
            arrayOf("account-lifecycle", "suspend"),
            arrayOf("account-lifecycle", "suspend", "not-a-uuid"),
            arrayOf("account-lifecycle", "suspend", account, "extra"),
            arrayOf("account-lifecycle", "approve", account),
            arrayOf("account-lifecycle", "restore-reconcile", account),
            // A short-form UUID that java.util.UUID would silently widen.
            arrayOf("account-lifecycle", "suspend", "1-1-1-1-1"),
        ).forEach { args ->
            val (code, out, err) = run(*args, env = mapOf("TI_LIFECYCLE_JDBC_URL" to "jdbc:postgresql://db/x"))
            code shouldBe AccountLifecycleCommand.EXIT_USAGE
            out shouldBe ""
            err shouldContain "usage: account-lifecycle"
        }
    }

    @Test
    fun `there is no development fallback - every operator credential is explicit`() {
        val complete =
            mapOf(
                "TI_LIFECYCLE_JDBC_URL" to "jdbc:postgresql://db.internal:5432/testinbox",
                "TI_LIFECYCLE_USER" to "testinbox_owner",
                "TI_LIFECYCLE_PASSWORD" to "s3cret-value",
            )
        for (missing in complete.keys) {
            val (code, out, err) = run("account-lifecycle", "status", account, env = complete - missing)
            code shouldBe AccountLifecycleCommand.EXIT_REFUSED
            err shouldContain "missing $missing"
            err shouldContain "nothing was changed"
            out shouldBe ""
        }
        val blank = complete + ("TI_LIFECYCLE_PASSWORD" to "  ")
        run("account-lifecycle", "status", account, env = blank).first shouldBe AccountLifecycleCommand.EXIT_REFUSED
    }

    @Test
    fun `credentials never travel inside the URL and the password is never printed`() {
        val base = mapOf("TI_LIFECYCLE_USER" to "owner", "TI_LIFECYCLE_PASSWORD" to "s3cret-value")
        listOf(
            "jdbc:postgresql://owner:pw@db/testinbox",
            "jdbc:postgresql://db/testinbox?password=pw",
            "jdbc:postgresql://db/testinbox?ssl=true&user=owner",
            "jdbc:mysql://db/testinbox",
        ).forEach { url ->
            val (code, _, err) = run("account-lifecycle", "suspend", account, env = base + ("TI_LIFECYCLE_JDBC_URL" to url))
            code shouldBe AccountLifecycleCommand.EXIT_REFUSED
            err.contains("pw@") shouldBe false
        }
        val credentials =
            AccountLifecycleCommand
                .credentials((base + ("TI_LIFECYCLE_JDBC_URL" to "jdbc:postgresql://db/testinbox"))::get)
                .getOrThrow()
        credentials.toString().contains("s3cret-value") shouldBe false
    }
}
