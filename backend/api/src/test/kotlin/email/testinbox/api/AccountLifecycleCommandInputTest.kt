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
            arrayOf("account-lifecycle", "process-deletions", account),
            arrayOf("account-lifecycle", "process-deletions", "now"),
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

    @Test
    fun `an infrastructure failure exits 4 with metadata only, never the password or SQL`() {
        val env =
            mapOf(
                // Nothing listens on port 1: the connection fails before any statement.
                "TI_LIFECYCLE_JDBC_URL" to "jdbc:postgresql://127.0.0.1:1/testinbox?connectTimeout=2",
                "TI_LIFECYCLE_USER" to "owner",
                "TI_LIFECYCLE_PASSWORD" to "s3cret-value",
            )
        val (code, out, err) = run("account-lifecycle", "suspend", account, env = env)
        code shouldBe AccountLifecycleCommand.EXIT_FAILED
        out shouldBe ""
        err shouldContain "the outcome is unknown: run status"
        err.contains("s3cret-value") shouldBe false
        err.contains("127.0.0.1") shouldBe false
    }

    @Test
    fun `the exact-address cooldown follows the API configuration and refuses a malformed one`() {
        AccountLifecycleCommand.exactCooldown { null }.getOrThrow() shouldBe java.time.Duration.ofHours(24)
        AccountLifecycleCommand.exactCooldown { if (it == "TESTINBOX_EXACT_COOLDOWN") "36h" else null }.getOrThrow() shouldBe
            java.time.Duration.ofHours(36)
        val env =
            mapOf(
                "TI_LIFECYCLE_JDBC_URL" to "jdbc:postgresql://127.0.0.1:1/testinbox",
                "TI_LIFECYCLE_USER" to "owner",
                "TI_LIFECYCLE_PASSWORD" to "s3cret-value",
            )
        for (bad in listOf("soon", "-1h", "0s")) {
            val (code, _, err) = run("account-lifecycle", "delete", account, env = env + ("TESTINBOX_EXACT_COOLDOWN" to bad))
            code shouldBe AccountLifecycleCommand.EXIT_REFUSED
            err shouldContain "TESTINBOX_EXACT_COOLDOWN"
        }
    }

    @Test
    fun `erase-identity is replaced by the deletion workflow and refused before any connection`() {
        val (code, out, err) = run("account-lifecycle", "erase-identity", account)
        code shouldBe AccountLifecycleCommand.EXIT_USAGE
        out shouldBe ""
        err shouldContain "replaced by the deletion workflow"
        err shouldContain "nothing was changed"
    }

    @Test
    fun `status without an account and process-deletions are valid commands that still need explicit credentials`() {
        for (args in listOf(arrayOf("account-lifecycle", "status"), arrayOf("account-lifecycle", "process-deletions"))) {
            val (code, _, err) = run(*args)
            code shouldBe AccountLifecycleCommand.EXIT_REFUSED
            err shouldContain "missing TI_LIFECYCLE_JDBC_URL"
        }
    }

    @Test
    fun `the overdue threshold derives from the deployment's cooldown and retention T_max, or is set explicitly`() {
        fun policy(vararg env: Pair<String, String>) =
            AccountLifecycleCommand
                .deletionPolicy(mapOf(*env)::get, AccountLifecycleCommand.exactCooldown(mapOf(*env)::get).getOrThrow())
                .getOrThrow()
                .overdueAfter
        policy() shouldBe java.time.Duration.ofHours(72)
        policy("TESTINBOX_EXACT_COOLDOWN" to "36h", "TESTINBOX_STORAGE_FS_RETENTION_PACING_MAX_DELAY" to "48h") shouldBe
            java.time.Duration.ofHours(108)
        policy("TI_LIFECYCLE_DELETION_OVERDUE_AFTER" to "12h", "TESTINBOX_EXACT_COOLDOWN" to "36h") shouldBe java.time.Duration.ofHours(12)
        val env =
            mapOf(
                "TI_LIFECYCLE_JDBC_URL" to "jdbc:postgresql://127.0.0.1:1/testinbox",
                "TI_LIFECYCLE_USER" to "owner",
                "TI_LIFECYCLE_PASSWORD" to "s3cret-value",
            )
        for ((name, bad) in listOf(
            "TI_LIFECYCLE_DELETION_OVERDUE_AFTER" to "soon",
            "TESTINBOX_STORAGE_FS_RETENTION_PACING_MAX_DELAY" to "0s",
        )) {
            val (code, out, err) = run("account-lifecycle", "process-deletions", env = env + (name to bad))
            code shouldBe AccountLifecycleCommand.EXIT_REFUSED
            out shouldBe ""
            err shouldContain name
        }
    }
}
