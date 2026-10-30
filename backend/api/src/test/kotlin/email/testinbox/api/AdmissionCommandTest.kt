package email.testinbox.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** The offline `admission` operator command against the real migrated schema. */
class AdmissionCommandTest : ApiIntegrationTestBase() {
    @Autowired lateinit var jdbc: JdbcClient

    private val output = mutableListOf<String>()

    private fun owner(name: String): String? =
        mapOf(
            "TI_ADMISSION_JDBC_URL" to postgres.jdbcUrl,
            "TI_ADMISSION_USER" to postgres.username,
            "TI_ADMISSION_PASSWORD" to postgres.password,
        )[name]

    private fun run(
        vararg args: String,
        env: (String) -> String? = ::owner,
    ) = AdmissionCommand.run(arrayOf("admission", *args), env) { output += it }

    private fun open(): Boolean = jdbc.sql("SELECT admissions_open FROM beta_cohort").query(Boolean::class.javaObjectType).single()

    @BeforeEach
    @AfterEach
    fun closed() {
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("UPDATE beta_cohort SET admissions_open=false, capacity_ready=false").update()
    }

    @Test
    fun `status pause and resume change only the admission switch and report it`() {
        run("status")
        output.last() shouldBe
            "admission status: admissions_open=false capacity_ready=false consumed_places=0/30 storage_budget_changes=allowed"
        run("resume", AdmissionCommand.OPEN_BETA)
        open() shouldBe true
        output.contains(
            "admission resume: PASS admissions_open false->true; existing participants, sessions, keys, inboxes and waitlist untouched",
        ) shouldBe true
        output.last() shouldBe "note: capacity_ready=false, so new admissions still answer CAPACITY_UNAVAILABLE"
        run("resume", AdmissionCommand.OPEN_BETA)
        output.any { it.contains("true->true (unchanged)") } shouldBe true
        run("pause")
        open() shouldBe false
        output.last() shouldBe
            "admission status: admissions_open=false capacity_ready=false consumed_places=0/30 storage_budget_changes=allowed"
        jdbc.sql("SELECT capacity_ready FROM beta_cohort").query(Boolean::class.javaObjectType).single() shouldBe false
    }

    @Test
    fun `usage and credentials are explicit and never taken from the URL or defaults`() {
        // `resume` opens registration: without the explicit flag it is refused and changes nothing.
        shouldThrow<IllegalArgumentException> { run("resume") }.message shouldBe AdmissionCommand.RESUME_UNCONFIRMED
        open() shouldBe false
        listOf(
            arrayOf<String>(),
            arrayOf("open"),
            arrayOf("pause", "now"),
            arrayOf("pause", AdmissionCommand.OPEN_BETA),
            arrayOf("resume", "--yes"),
        ).forEach { args ->
            shouldThrow<IllegalArgumentException> { run(*args) }.message shouldBe AdmissionCommand.USAGE
        }
        shouldThrow<IllegalStateException> { run("pause") { null } }.message shouldBe "missing TI_ADMISSION_JDBC_URL"
        shouldThrow<IllegalStateException> { run("pause") { if (it == "TI_ADMISSION_PASSWORD") " " else owner(it) } }
            .message shouldBe "missing TI_ADMISSION_PASSWORD"
        listOf(
            postgres.jdbcUrl + "&password=x",
            postgres.jdbcUrl + "&user=postgres",
            "jdbc:postgresql://u:p@localhost/test",
            "jdbc:h2:mem:x",
        ).forEach { url ->
            shouldThrow<IllegalArgumentException> { run("pause") { if (it == "TI_ADMISSION_JDBC_URL") url else owner(it) } }
                .message shouldContain "without credentials"
        }
        open() shouldBe false
    }

    @Test
    fun `a role without the operator privilege cannot pause or resume`() {
        val role = "ti_admission_probe_${UUID.randomUUID().toString().take(8)}"
        jdbc.sql("CREATE ROLE $role LOGIN PASSWORD 'pw-$role'").update()
        try {
            // The runtime API role's shape (roles.reference.sql): read and row-lock only.
            jdbc.sql("GRANT SELECT, UPDATE (id) ON beta_cohort TO $role").update()
            jdbc.sql("GRANT SELECT ON beta_admission TO $role").update()
            val env = { name: String ->
                when (name) {
                    "TI_ADMISSION_USER" -> role
                    "TI_ADMISSION_PASSWORD" -> "pw-$role"
                    else -> owner(name)
                }
            }
            run("status", env = env)
            output.last() shouldContain "admissions_open=false"
            shouldThrow<IllegalStateException> { run("resume", AdmissionCommand.OPEN_BETA, env = env) }.message shouldContain
                "not authorized"
            open() shouldBe false
            jdbc.sql("UPDATE beta_cohort SET admissions_open=true").update()
            shouldThrow<IllegalStateException> { run("pause", env = env) }.message shouldContain "not authorized"
            open() shouldBe true
        } finally {
            jdbc.sql("DROP OWNED BY $role").update()
            jdbc.sql("DROP ROLE $role").update()
        }
    }
}
