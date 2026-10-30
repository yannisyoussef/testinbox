package email.testinbox.api

import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.ControlBetaAdmissions
import email.testinbox.persistence.JdbcBetaAdmissionControls
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.util.Properties

/**
 * Offline operator command for the ADR-036 admission pause. Like
 * `storage-budget`, it is dispatched before Spring: no HTTP listener,
 * scheduler, login provider or migration starts. Authority is the database
 * role behind explicit credentials, rechecked inside the write transaction;
 * there is no public endpoint, API key or request field that reaches it.
 */
object AdmissionCommand {
    const val USAGE: String =
        "usage: admission status|pause|resume --open-beta; credentials in TI_ADMISSION_JDBC_URL/USER/PASSWORD"

    /** The flag that makes opening registration an explicit, typed decision. */
    const val OPEN_BETA: String = "--open-beta"

    const val RESUME_UNCONFIRMED: String =
        "admission resume sets admissions_open=true, even on a cohort that was never opened: it opens registration " +
            "and must only follow the release decision. Re-run as `admission resume $OPEN_BETA`; nothing was changed"
    const val APPLICATION_NAME: String = "ops:admission-control"

    fun run(
        args: Array<String>,
        env: (String) -> String? = System::getenv,
        out: (String) -> Unit = ::println,
    ) {
        require(args.size in 2..3 && args[1] in setOf("status", "pause", "resume")) { USAGE }
        require(args.size == 2 || (args[1] == "resume" && args[2] == OPEN_BETA)) { USAGE }
        // Never an accidental opening: `resume` alone would break Ops' closed-cohort backstop.
        require(args[1] != "resume" || args.getOrNull(2) == OPEN_BETA) { RESUME_UNCONFIRMED }
        val url = required(env, "TI_ADMISSION_JDBC_URL")
        require(
            url.startsWith("jdbc:postgresql://") && '@' !in url &&
                listOf("password", "user=").none { url.contains(it, ignoreCase = true) },
        ) { "TI_ADMISSION_JDBC_URL must be a jdbc:postgresql URL without credentials" }
        val dataSource =
            DriverManagerDataSource(url, required(env, "TI_ADMISSION_USER"), required(env, "TI_ADMISSION_PASSWORD")).apply {
                // The storage-activation session gate treats ops:* as a named exclusion.
                connectionProperties = Properties().apply { setProperty("ApplicationName", APPLICATION_NAME) }
            }
        val template = TransactionTemplate(DataSourceTransactionManager(dataSource))
        val tx =
            object : TransactionRunner {
                override fun <T> required(block: () -> T): T = checkNotNull(template.execute { block() })
            }
        val control = ControlBetaAdmissions(JdbcBetaAdmissionControls(JdbcClient.create(dataSource)), tx)
        when (args[1]) {
            "status" -> {
                out(describe(control.status()))
            }

            else -> {
                val transition = if (args[1] == "pause") control.pause() else control.resume()
                out(
                    "admission ${args[1]}: PASS admissions_open ${transition.before.admissionsOpen}->${transition.after.admissionsOpen}" +
                        (if (transition.changed) "" else " (unchanged)") +
                        "; existing participants, sessions, keys, inboxes and waitlist untouched",
                )
                out(describe(transition.after))
                if (transition.after.admissionsOpen && !transition.after.capacityReady) {
                    out("note: capacity_ready=false, so new admissions still answer CAPACITY_UNAVAILABLE")
                }
            }
        }
    }

    fun describe(status: ControlBetaAdmissions.Status): String =
        "admission status: admissions_open=${status.admissionsOpen} capacity_ready=${status.capacityReady} " +
            "consumed_places=${status.consumedPlaces}/${status.maxPlaces} " +
            "storage_budget_changes=${if (status.poolChangesAllowed) "allowed" else "refused"}"

    private fun required(
        env: (String) -> String?,
        name: String,
    ): String = checkNotNull(env(name)?.takeIf { it.isNotBlank() }) { "missing $name" }
}
