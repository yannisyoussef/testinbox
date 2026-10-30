package email.testinbox.api

import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.usecase.ReserveCohortStorage
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.persistence.JdbcCohortBudgets
import email.testinbox.persistence.JdbcFootprintGate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

/** Offline owner-credential command; never starts Spring, HTTP or scheduled work. */
object CohortBudgetCommand {
    data class Legacy(
        val workspaceId: UUID,
        val payloadBytes: Long,
        val footprintBytes: Long,
    )

    data class Input(
        val declarations: StorageDeclarations,
        val legacy: List<Legacy>,
        val unattributedAllowance: Long,
        val trashAllowance: Long,
    )

    /** The session tag the storage-activation session gate treats as a named `ops:*` exclusion (cohort-storage.md). */
    const val APPLICATION_NAME: String = "ops:cohort-budget"

    fun run(
        args: Array<String>,
        env: (String) -> String? = System::getenv,
        out: (String) -> Unit = ::println,
    ) {
        require(args.size == 3 && args[1] in setOf("check", "reserve", "revoke")) {
            "usage: storage-budget check|reserve|revoke <reviewed-config.json>; credentials in TI_BUDGET_JDBC_URL/USER/PASSWORD"
        }
        val path = Path.of(args[2])
        require(Files.size(path) <= 131072) { "budget file too large" }
        val mapper =
            JsonMapper
                .builder()
                .addModule(KotlinModule.Builder().build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()
        val text = Files.readString(path)
        val tree = mapper.readTree(text)
        require(
            tree["declarations"]?.isObject == true && tree["legacy"]?.isArray == true,
        ) { "explicit declarations and legacy inventory required" }
        for (name in listOf("unattributedAllowance", "trashAllowance")) {
            val value = tree[name]
            require(
                value != null && value.isIntegralNumber && value.bigIntegerValue().signum() >= 0,
            ) { "explicit nonnegative $name required" }
        }
        val input = mapper.readValue(text, Input::class.java)
        require(
            input.legacy
                .map { it.workspaceId }
                .distinct()
                .size == input.legacy.size,
        ) { "duplicate legacy workspace" }
        val legacy = input.legacy.associate { WorkspaceId(it.workspaceId) to LegacyStorageShare(it.payloadBytes, it.footprintBytes) }
        val url = required(env, "TI_BUDGET_JDBC_URL")
        require(
            url.startsWith("jdbc:postgresql://") && '@' !in url &&
                listOf("password", "user=").none { url.contains(it, ignoreCase = true) },
        ) { "TI_BUDGET_JDBC_URL must be a jdbc:postgresql URL without credentials" }
        val dataSource =
            DriverManagerDataSource(url, required(env, "TI_BUDGET_USER"), required(env, "TI_BUDGET_PASSWORD")).apply {
                connectionProperties = Properties().apply { setProperty("ApplicationName", APPLICATION_NAME) }
            }
        val jdbc = JdbcClient.create(dataSource)
        val template = TransactionTemplate(DataSourceTransactionManager(dataSource))
        val tx =
            object : TransactionRunner {
                override fun <T> required(block: () -> T): T = checkNotNull(template.execute { block() })
            }
        val gate = JdbcFootprintGate(jdbc)
        val useCase = ReserveCohortStorage(JdbcCohortBudgets(jdbc), tx, gate, input.declarations)
        when (args[1]) {
            "reserve" -> {
                useCase.execute(legacy, input.unattributedAllowance, input.trashAllowance)
            }

            "revoke" -> {
                useCase.revoke()
            }

            "check" -> {
                val policy = checkNotNull(EffectiveStoragePolicy.footprint(input.declarations))
                val observed = gate.observe()
                check(
                    policy.unavailability(observed) == null && policy.cohort?.ready(observed?.cohort) == true,
                ) { "cohort storage is not ready" }
                val pool = checkNotNull(observed?.cohort)
                check(
                    pool.legacy == legacy && pool.unattributedAllowance == input.unattributedAllowance &&
                        pool.trashAllowance == input.trashAllowance,
                ) { "operator file differs from installed pool" }
            }
        }
        out("cohort storage ${args[1]}: PASS; admissions unchanged")
    }

    private fun required(
        env: (String) -> String?,
        name: String,
    ): String = checkNotNull(env(name)?.takeIf { it.isNotBlank() }) { "missing $name" }
}
