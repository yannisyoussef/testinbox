package email.testinbox.api

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.AccountLifecycleStatus
import email.testinbox.application.usecase.ChangeAccountState
import email.testinbox.application.usecase.DeleteInbox
import email.testinbox.application.usecase.EraseAccountIdentity
import email.testinbox.application.usecase.LifecyclePhase
import email.testinbox.application.usecase.OperatorAuthorityRequiredException
import email.testinbox.application.usecase.ReconcileRestoredDatabase
import email.testinbox.application.usecase.RemoveAccountContent
import email.testinbox.application.usecase.RevokeAccountSessions
import email.testinbox.application.usecase.RevokeApiKey
import email.testinbox.domain.identity.AccountLifecycleAction
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.persistence.JdbcAccountLifecycle
import email.testinbox.persistence.JdbcApiKeyRepository
import email.testinbox.persistence.JdbcExactAddressReservations
import email.testinbox.persistence.JdbcHumanSessions
import email.testinbox.persistence.JdbcInboxRepository
import email.testinbox.persistence.JdbcWorkspaceQuotaState
import email.testinbox.persistence.OperatorTransactionBounds
import email.testinbox.persistence.SpringTransactionRunner
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.io.PrintStream
import java.time.Clock
import java.util.Properties
import java.util.UUID

/**
 * The account-lifecycle operator command (ACCOUNT-LIFECYCLE.md "Operator
 * authority and writes"): a non-web subcommand of the API artifact,
 * dispatched by `main` BEFORE Spring starts, so it never opens an HTTP
 * listener, runs a scheduler, a login provider or a migration.
 *
 * Authority is the database role in the explicit `TI_LIFECYCLE_*`
 * credentials: the schema owner of the identity tables or a member of it,
 * rechecked inside every write transaction (and by V22's triggers). There is
 * no development fallback, no API key and no request field that can stand in
 * for it. Output is metadata only: ids, states and counts, never contact data
 * or credentials. Each line is the audit record Ops keeps OUTSIDE the
 * database, which a restore cannot roll back.
 */
object AccountLifecycleCommand {
    const val NAME = "account-lifecycle"

    /** Completed: settled, active, content removed, erased or reconciled. */
    const val EXIT_DONE = 0

    /** Refused, unknown account, missing authority or invalid credentials: nothing was changed. */
    const val EXIT_REFUSED = 1
    const val EXIT_USAGE = 2

    /** Applied, but in-flight reservations or inbox rows remain: re-run the same command later. */
    const val EXIT_PENDING = 3

    /**
     * Failed for another reason (a lock or statement timeout, a lost
     * connection, a constraint): whether the transaction committed is not
     * known to this process. Run `status` before anything else.
     */
    const val EXIT_FAILED = 4

    private const val USAGE =
        "usage: account-lifecycle suspend|resume|delete|status|erase-identity <account-uuid> | restore-reconcile; " +
            "operator credentials in TI_LIFECYCLE_JDBC_URL, TI_LIFECYCLE_USER, TI_LIFECYCLE_PASSWORD"

    private sealed interface Command {
        data class Change(
            val action: AccountLifecycleAction,
            val account: HumanAccountId,
        ) : Command

        data class Status(
            val account: HumanAccountId,
        ) : Command

        data class Erase(
            val account: HumanAccountId,
        ) : Command

        data object Reconcile : Command
    }

    fun run(
        args: Array<String>,
        env: (String) -> String? = System::getenv,
        out: PrintStream = System.out,
        err: PrintStream = System.err,
    ): Int {
        val command =
            parse(args) ?: run {
                err.println(USAGE)
                return EXIT_USAGE
            }
        val credentials =
            credentials(env).getOrElse {
                err.println("account-lifecycle: ${it.message}; nothing was changed")
                return EXIT_REFUSED
            }
        val cooldown =
            exactCooldown(env).getOrElse {
                err.println("account-lifecycle: ${it.message}; nothing was changed")
                return EXIT_REFUSED
            }
        return try {
            execute(command, operations(credentials, cooldown), out)
        } catch (e: OperatorAuthorityRequiredException) {
            err.println("account-lifecycle: ${e.message}")
            EXIT_REFUSED
        } catch (e: RuntimeException) {
            // Metadata only: the exception type, never its message (which can quote SQL or values).
            err.println("account-lifecycle: failed (${e.javaClass.simpleName}); the outcome is unknown: run status")
            EXIT_FAILED
        }
    }

    private fun parse(args: Array<String>): Command? {
        if (args.firstOrNull() != NAME) return null
        if (args.size == 2 && args[1] == "restore-reconcile") return Command.Reconcile
        if (args.size != 3) return null
        val account = runCatching { HumanAccountId(UUID.fromString(args[2])) }.getOrNull() ?: return null
        if (account.value.toString() != args[2].lowercase()) return null
        return when (args[1]) {
            "suspend" -> Command.Change(AccountLifecycleAction.SUSPEND, account)
            "resume" -> Command.Change(AccountLifecycleAction.RESUME, account)
            "delete" -> Command.Change(AccountLifecycleAction.DELETE, account)
            "status" -> Command.Status(account)
            "erase-identity" -> Command.Erase(account)
            else -> null
        }
    }

    internal data class Credentials(
        val url: String,
        val user: String,
        val password: String,
    ) {
        override fun toString(): String = "Credentials(url=$url, user=$user, password=[REDACTED])"
    }

    /** Explicit operator credentials only: no default URL, role or password, and no secret inside the URL. */
    internal fun credentials(env: (String) -> String?): Result<Credentials> =
        runCatching {
            fun required(name: String) = checkNotNull(env(name)?.takeIf { it.isNotBlank() }) { "missing $name" }
            val url = required("TI_LIFECYCLE_JDBC_URL")
            check(url.startsWith("jdbc:postgresql://")) { "TI_LIFECYCLE_JDBC_URL must be a jdbc:postgresql:// URL" }
            val authority = url.removePrefix("jdbc:postgresql://").substringBefore('/')
            check('@' !in authority && !Regex("(?i)[?&](user|password)=").containsMatchIn(url)) {
                "TI_LIFECYCLE_JDBC_URL must not carry credentials; use TI_LIFECYCLE_USER and TI_LIFECYCLE_PASSWORD"
            }
            Credentials(url, required("TI_LIFECYCLE_USER"), required("TI_LIFECYCLE_PASSWORD"))
        }

    private class Operations(
        val change: ChangeAccountState,
        val remove: RemoveAccountContent,
        val erase: EraseAccountIdentity,
        val reconcile: ReconcileRestoredDatabase,
        val status: AccountLifecycleStatus,
    )

    /**
     * The ADR-021 EXACT-address cooldown a deleted account's addresses re-enter.
     * Read from the API's own `TESTINBOX_EXACT_COOLDOWN` when the operator
     * passes the API environment file (Spring's duration syntax, e.g. `24h`);
     * otherwise the shipped `exact-cooldown: 24h` from application.yaml.
     */
    internal fun exactCooldown(env: (String) -> String?): Result<java.time.Duration> =
        runCatching {
            val raw = env("TESTINBOX_EXACT_COOLDOWN")?.takeIf { it.isNotBlank() } ?: return@runCatching SHIPPED_EXACT_COOLDOWN
            val parsed =
                runCatching {
                    org.springframework.boot.convert.DurationStyle
                        .detectAndParse(raw.trim())
                }.getOrElse { error("TESTINBOX_EXACT_COOLDOWN is not a duration") }
            check(!parsed.isNegative && !parsed.isZero) { "TESTINBOX_EXACT_COOLDOWN must be positive" }
            parsed
        }

    private val SHIPPED_EXACT_COOLDOWN: java.time.Duration = java.time.Duration.ofHours(24)

    private fun operations(
        credentials: Credentials,
        exactCooldown: java.time.Duration,
    ): Operations {
        val dataSource =
            DriverManagerDataSource(credentials.url, credentials.user, credentials.password).apply {
                // Visible to Ops in pg_stat_activity and the session allowlist.
                connectionProperties =
                    Properties().apply {
                        setProperty("ApplicationName", "ops:account-lifecycle")
                        // Session defaults, so the per-inbox deletion transactions are bounded too, not
                        // only the ones that call OperatorTransactionBounds.apply (which SET LOCAL the same values).
                        setProperty("options", OperatorTransactionBounds.sessionOptions())
                    }
            }
        val jdbc = JdbcClient.create(dataSource)
        val tx: TransactionRunner = SpringTransactionRunner(DataSourceTransactionManager(dataSource))
        val clock = Clock.systemUTC()
        val store = JdbcAccountLifecycle(jdbc)
        val guard = JdbcWorkspaceQuotaState(jdbc)
        val sessions = RevokeAccountSessions(JdbcHumanSessions(jdbc), tx)
        val keys = RevokeApiKey(JdbcApiKeyRepository(jdbc), clock)
        // The existing inbox deleter, with the deployment's EXACT cooldown (ADR-021).
        val deleteInbox =
            DeleteInbox(
                JdbcInboxRepository(jdbc),
                JdbcExactAddressReservations(jdbc),
                tx,
                clock,
                TestInboxConfig(mailDomain = "operator.invalid", exactCooldown = exactCooldown),
            )
        return Operations(
            ChangeAccountState(store, guard, sessions, keys, tx),
            RemoveAccountContent(store, deleteInbox, tx),
            EraseAccountIdentity(store, guard, sessions, keys, tx),
            ReconcileRestoredDatabase(store, guard, sessions, keys, tx),
            AccountLifecycleStatus(store, tx),
        )
    }

    private fun execute(
        command: Command,
        ops: Operations,
        out: PrintStream,
    ): Int =
        when (command) {
            is Command.Change -> change(command, ops, out)
            is Command.Status -> status(command.account, ops, out)
            is Command.Erase -> erase(command.account, ops, out)
            Command.Reconcile -> reconcile(ops, out)
        }

    private fun change(
        command: Command.Change,
        ops: Operations,
        out: PrintStream,
    ): Int {
        val report =
            when (val result = ops.change.execute(command.account, command.action)) {
                ChangeAccountState.Result.AccountNotFound -> {
                    return notFound(command.account, out)
                }

                is ChangeAccountState.Result.Refused -> {
                    out.println(
                        "event=account.lifecycle action=${command.action} account=${command.account.value} " +
                            "outcome=REFUSED state=${result.from} reason=\"${result.reason}\"",
                    )
                    return EXIT_REFUSED
                }

                is ChangeAccountState.Result.Done -> {
                    result.report
                }
            }
        out.println(
            "event=account.lifecycle action=${report.action} account=${report.accountId.value} " +
                "workspace=${report.workspaceId?.value ?: "-"} from=${report.from} to=${report.to} changed=${report.changed} " +
                "revokedKeys=${report.revokedKeys} reservationsHeld=${report.reservationsHeld} phase=${report.phase}",
        )
        if (report.phase == LifecyclePhase.DRAINING) return EXIT_PENDING
        if (command.action != AccountLifecycleAction.DELETE) return EXIT_DONE
        // Deletion continues with the content step once drained; re-running is idempotent.
        return when (val removed = ops.remove.execute(command.account)) {
            is RemoveAccountContent.Result.Done -> {
                out.println(
                    "event=account.content account=${command.account.value} workspace=${removed.workspaceId?.value ?: "-"} " +
                        "phase=${removed.phase} inboxesMarkedDeleted=${removed.inboxesMarkedDeleted} " +
                        "inboxRowsRemaining=${removed.inboxRowsRemaining}",
                )
                if (removed.phase == RemoveAccountContent.Phase.CONTENT_REMOVED) EXIT_DONE else EXIT_PENDING
            }

            RemoveAccountContent.Result.AccountNotFound -> {
                notFound(command.account, out)
            }

            is RemoveAccountContent.Result.NotDeletionPending -> {
                EXIT_REFUSED
            }
        }
    }

    private fun status(
        account: HumanAccountId,
        ops: Operations,
        out: PrintStream,
    ): Int {
        val status = ops.status.execute(account) ?: return notFound(account, out)
        out.println(
            "event=account.status account=${account.value} workspace=${status.workspaceId?.value ?: "-"} state=${status.state} " +
                "reservationsHeld=${status.reservationsHeld} inboxRows=${status.inboxRows} quarantined=${status.quarantined} " +
                "phase=${status.phase}",
        )
        return if (status.phase == LifecyclePhase.DRAINING) EXIT_PENDING else EXIT_DONE
    }

    private fun erase(
        account: HumanAccountId,
        ops: Operations,
        out: PrintStream,
    ): Int =
        when (val result = ops.erase.execute(account)) {
            is EraseAccountIdentity.Result.Erased -> {
                out.println("event=account.erased account=${account.value} workspace=${result.workspaceId?.value ?: "-"}")
                EXIT_DONE
            }

            EraseAccountIdentity.Result.AccountNotFound -> {
                notFound(account, out)
            }

            is EraseAccountIdentity.Result.NotDeletionPending -> {
                out.println("event=account.erased account=${account.value} outcome=REFUSED state=${result.state}")
                EXIT_REFUSED
            }

            is EraseAccountIdentity.Result.ContentRemains -> {
                out.println(
                    "event=account.erased account=${account.value} outcome=PENDING reservationsHeld=${result.reservationsHeld} " +
                        "inboxRows=${result.inboxRows}",
                )
                EXIT_PENDING
            }
        }

    private fun reconcile(
        ops: Operations,
        out: PrintStream,
    ): Int =
        when (val result = ops.reconcile.execute()) {
            ReconcileRestoredDatabase.Result.NotQuarantined -> {
                out.println("event=restore.reconcile outcome=REFUSED reason=\"the database is not restore-quarantined\"")
                EXIT_REFUSED
            }

            is ReconcileRestoredDatabase.Result.Reconciled -> {
                out.println(
                    "event=restore.reconcile outcome=CLEARED participantWorkspaces=${result.participantWorkspaces} " +
                        "suspendedAccounts=${result.suspendedAccounts} revokedKeys=${result.revokedKeys} admissions=CLOSED",
                )
                EXIT_DONE
            }
        }

    private fun notFound(
        account: HumanAccountId,
        out: PrintStream,
    ): Int {
        out.println("event=account.lifecycle account=${account.value} outcome=NOT_FOUND")
        return EXIT_REFUSED
    }
}
