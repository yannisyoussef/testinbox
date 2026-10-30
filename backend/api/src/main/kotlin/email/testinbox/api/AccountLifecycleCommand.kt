package email.testinbox.api

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.AccountDeletionRecord
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.storage.FilesystemDeclarations
import email.testinbox.application.usecase.AccountDeletionReport
import email.testinbox.application.usecase.AccountLifecycleStatus
import email.testinbox.application.usecase.ChangeAccountState
import email.testinbox.application.usecase.DeleteInbox
import email.testinbox.application.usecase.LifecyclePhase
import email.testinbox.application.usecase.OperatorAuthorityRequiredException
import email.testinbox.application.usecase.ProcessAccountDeletion
import email.testinbox.application.usecase.ReconcileRestoredDatabase
import email.testinbox.application.usecase.RevokeAccountSessions
import email.testinbox.application.usecase.RevokeApiKey
import email.testinbox.domain.identity.AccountDeletionPolicy
import email.testinbox.domain.identity.AccountDeletionStage
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
import java.time.Duration
import java.time.Instant
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

    /** Completed: settled, active, erased, reconciled, or (process-deletions/status) nothing overdue. */
    const val EXIT_DONE = 0

    /** Refused, unknown account, missing authority or invalid credentials: nothing was changed. */
    const val EXIT_REFUSED = 1
    const val EXIT_USAGE = 2

    /** Applied, but in-flight reservations or content remain: re-run the same command later (or let the timer). */
    const val EXIT_PENDING = 3

    /**
     * Failed for another reason (a lock or statement timeout, a lost
     * connection, a constraint): whether the transaction committed is not
     * known to this process. Run `status` before anything else.
     */
    const val EXIT_FAILED = 4

    /**
     * `process-deletions` / `status`: at least one deletion has been unfinished
     * for longer than the overdue threshold (or a DELETION_PENDING account has
     * no deletion record). The Ops timer alerts on it (POLICY-002b.md).
     */
    const val EXIT_OVERDUE = 5

    /**
     * `process-deletions`, `status`, `delete`: a deletion record is not ERASED
     * but its account row is gone (removed outside the workflow). Nothing more
     * is erased automatically and the record keeps its stage; an operator
     * resolves it by hand (LIFECYCLE.md, "Anomalies"). Alert.
     */
    const val EXIT_ANOMALY = 6

    /**
     * The first line of the usage text: a stable marker an Ops preflight
     * greps for to prove an image contains the deletion workflow (an older
     * image ignores the subcommand and boots the whole API instead).
     */
    const val USAGE_MARKER =
        "account-lifecycle-cli v2 subcommands=suspend,resume,delete,status,process-deletions,restore-reconcile"

    private const val USAGE =
        "$USAGE_MARKER\n" +
            "usage: account-lifecycle suspend|resume|delete|status <account-uuid> | status | process-deletions | restore-reconcile; " +
            "operator credentials in TI_LIFECYCLE_JDBC_URL, TI_LIFECYCLE_USER, TI_LIFECYCLE_PASSWORD"

    private const val ERASE_REPLACED =
        "account-lifecycle: erase-identity was replaced by the deletion workflow (TI-DELIVERY-002b): `delete` and " +
            "`process-deletions` erase the identity automatically once content deletion is complete; nothing was changed"

    private sealed interface Command {
        data class Change(
            val action: AccountLifecycleAction,
            val account: HumanAccountId,
        ) : Command

        data class Status(
            val account: HumanAccountId,
        ) : Command

        data object Deletions : Command

        data object Process : Command

        data object Reconcile : Command

        data object EraseReplaced : Command
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
        if (command == Command.EraseReplaced) {
            err.println(ERASE_REPLACED)
            return EXIT_USAGE
        }
        val credentials =
            credentials(env).getOrElse {
                err.println("account-lifecycle: ${it.message}; nothing was changed")
                return EXIT_REFUSED
            }
        val settings =
            runCatching { exactCooldown(env).getOrThrow().let { it to deletionPolicy(env, it).getOrThrow() } }.getOrElse {
                err.println("account-lifecycle: ${it.message}; nothing was changed")
                return EXIT_REFUSED
            }
        return try {
            execute(command, operations(credentials, settings.first, settings.second), out)
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
        if (args.size == 2) {
            return when (args[1]) {
                "restore-reconcile" -> Command.Reconcile
                "process-deletions" -> Command.Process
                "status" -> Command.Deletions
                else -> null
            }
        }
        if (args.size != 3) return null
        val account = runCatching { HumanAccountId(UUID.fromString(args[2])) }.getOrNull() ?: return null
        if (account.value.toString() != args[2].lowercase()) return null
        return when (args[1]) {
            "suspend" -> Command.Change(AccountLifecycleAction.SUSPEND, account)
            "resume" -> Command.Change(AccountLifecycleAction.RESUME, account)
            "delete" -> Command.Change(AccountLifecycleAction.DELETE, account)
            "status" -> Command.Status(account)
            "erase-identity" -> Command.EraseReplaced
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
        val deletion: ProcessAccountDeletion,
        val reconcile: ReconcileRestoredDatabase,
        val status: AccountLifecycleStatus,
        val report: AccountDeletionReport,
        val clock: Clock,
    )

    /**
     * The ADR-021 EXACT-address cooldown a deleted account's addresses re-enter.
     * Read from the API's own `TESTINBOX_EXACT_COOLDOWN` when the operator
     * passes the API environment file (Spring's duration syntax, e.g. `24h`);
     * otherwise the shipped `exact-cooldown: 24h` from application.yaml.
     */
    internal fun exactCooldown(env: (String) -> String?): Result<Duration> =
        runCatching { duration(env, "TESTINBOX_EXACT_COOLDOWN") ?: SHIPPED_EXACT_COOLDOWN }

    /**
     * When an unfinished deletion is overdue (POLICY-002b.md). An explicit
     * `TI_LIFECYCLE_DELETION_OVERDUE_AFTER` wins; otherwise it is derived from
     * the deployment's own timing: the EXACT cooldown, plus the paced-retention
     * `T_max` (`TESTINBOX_STORAGE_FS_RETENTION_PACING_MAX_DELAY`, shipped 24 h),
     * plus 24 h of slack. With shipped values that is 72 h.
     */
    internal fun deletionPolicy(
        env: (String) -> String?,
        exactCooldown: Duration,
    ): Result<AccountDeletionPolicy> =
        runCatching {
            duration(env, "TI_LIFECYCLE_DELETION_OVERDUE_AFTER")?.let(::AccountDeletionPolicy)
                ?: AccountDeletionPolicy.derived(
                    exactCooldown,
                    duration(env, "TESTINBOX_STORAGE_FS_RETENTION_PACING_MAX_DELAY")
                        ?: FilesystemDeclarations.DEFAULT_RETENTION_PACING_MAX_DELAY,
                )
        }

    /** A positive Spring-style duration (`24h`, `90m`, `PT72H`) from [name], or null when unset. */
    private fun duration(
        env: (String) -> String?,
        name: String,
    ): Duration? {
        val raw = env(name)?.takeIf { it.isNotBlank() } ?: return null
        val parsed =
            runCatching {
                org.springframework.boot.convert.DurationStyle
                    .detectAndParse(raw.trim())
            }.getOrElse { error("$name is not a duration") }
        check(!parsed.isNegative && !parsed.isZero) { "$name must be positive" }
        return parsed
    }

    private val SHIPPED_EXACT_COOLDOWN: Duration = Duration.ofHours(24)

    private fun operations(
        credentials: Credentials,
        exactCooldown: Duration,
        policy: AccountDeletionPolicy,
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
            ChangeAccountState(store, guard, sessions, keys, tx, clock),
            ProcessAccountDeletion(store, guard, sessions, keys, deleteInbox, tx, clock),
            ReconcileRestoredDatabase(store, guard, sessions, keys, tx, clock),
            AccountLifecycleStatus(store, tx),
            AccountDeletionReport(store, tx, clock, policy),
            clock,
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
            Command.Deletions -> deletions(ops, out)
            Command.Process -> process(ops, out)
            Command.Reconcile -> reconcile(ops, out)
            Command.EraseReplaced -> EXIT_USAGE
        }

    private fun change(
        command: Command.Change,
        ops: Operations,
        out: PrintStream,
    ): Int {
        val report =
            when (val result = ops.change.execute(command.account, command.action)) {
                ChangeAccountState.Result.AccountNotFound -> {
                    // Re-running `delete` for an account already ERASED (e.g. a restore replay) is a success.
                    val record = ops.status.deletionRecord(command.account) ?: return notFound(command.account, out)
                    if (record.stage != AccountDeletionStage.ERASED) return rowAbsent(record, "account.lifecycle", out)
                    if (command.action != AccountLifecycleAction.DELETE) return notFound(command.account, out)
                    out.println(erasedLine(record))
                    return EXIT_DONE
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
        if (command.action != AccountLifecycleAction.DELETE) return if (report.phase == LifecyclePhase.DRAINING) EXIT_PENDING else EXIT_DONE
        // Deletion continues with the workflow at once; re-running (or the timer) resumes it.
        return when (advance(command.account, ops, out)) {
            Advance.ERASED -> EXIT_DONE
            Advance.PENDING, Advance.OVERDUE -> EXIT_PENDING
            Advance.REFUSED -> EXIT_REFUSED
            Advance.ANOMALY -> EXIT_ANOMALY
        }
    }

    private enum class Advance { ERASED, PENDING, OVERDUE, REFUSED, ANOMALY }

    /** A record that is not ERASED for an account whose row is gone: reported with its real stage, never as erased. */
    private fun rowAbsent(
        record: AccountDeletionRecord,
        event: String,
        out: PrintStream,
    ): Int {
        out.println(
            "event=$event account=${record.accountId.value} state=ABSENT stage=${record.stage} requestedAt=${record.requestedAt} " +
                "anomaly=ACCOUNT_ROW_ABSENT",
        )
        return EXIT_ANOMALY
    }

    /** One workflow step for one account, printed as one metadata line. */
    private fun advance(
        account: HumanAccountId,
        ops: Operations,
        out: PrintStream,
    ): Advance =
        when (val result = ops.deletion.execute(account)) {
            ProcessAccountDeletion.Result.AccountNotFound -> {
                notFound(account, out)
                Advance.REFUSED
            }

            is ProcessAccountDeletion.Result.NotDeletionPending -> {
                out.println("event=account.deletion account=${account.value} outcome=REFUSED state=${result.state}")
                Advance.REFUSED
            }

            is ProcessAccountDeletion.Result.AccountRowAbsent -> {
                rowAbsent(result.record, "account.deletion", out)
                Advance.ANOMALY
            }

            is ProcessAccountDeletion.Result.Advanced -> {
                val p = result.progress
                val now = ops.clock.instant()
                val overdue = p.requestedAt?.let { ops.report.overdue(record(p, it)) } ?: false
                val erasure =
                    p.erasure?.let {
                        " erasedNow=true anonymizedKeyLabels=${it.anonymizedKeyLabels} loginChallenges=${it.loginChallenges} " +
                            "idempotencyRecords=${it.idempotencyRecords} addressReservations=${it.addressReservations}"
                    } ?: ""
                out.println(
                    "event=account.deletion account=${account.value} workspace=${p.workspaceId?.value ?: "-"} stage=${p.stage} " +
                        "requestedAt=${p.requestedAt ?: "-"} ageSeconds=${p.requestedAt?.let { age(it, now) } ?: "-"} " +
                        "reservationsHeld=${p.remaining.reservationsHeld} inboxRows=${p.remaining.inboxRows} " +
                        "exactCooldowns=${p.remaining.exactCooldowns} inboxesMarkedDeleted=${p.inboxesMarkedDeleted} " +
                        "overdue=$overdue$erasure",
                )
                when {
                    p.stage == AccountDeletionStage.ERASED -> Advance.ERASED
                    overdue -> Advance.OVERDUE
                    else -> Advance.PENDING
                }
            }
        }

    private fun record(
        p: ProcessAccountDeletion.Progress,
        requestedAt: Instant,
    ) = AccountDeletionRecord(p.accountId, p.stage, requestedAt, requestedAt, null)

    private fun age(
        from: Instant,
        now: Instant,
    ): Long = maxOf(0L, Duration.between(from, now).seconds)

    /**
     * The Ops timer's command: advances EVERY pending deletion one step. One
     * account's failure does not stop the others. The exit code, in order of
     * precedence: 4 when any step failed, 6 when any record's account row is
     * gone, 5 when any deletion is overdue, refused or had to be adopted,
     * otherwise 0 (deletions still within the threshold are normal). The last
     * line is a stable, machine-readable summary for a textfile metric.
     */
    private fun process(
        ops: Operations,
        out: PrintStream,
    ): Int {
        val pending = ops.deletion.pending()
        pending.adopted.forEach {
            out.println("event=account.deletion account=${it.value} stage=DRAINING anomaly=ADOPTED_UNRECORDED")
        }
        val counts = IntArray(Advance.entries.size)
        var failed = 0
        for (record in pending.records) {
            try {
                counts[advance(record.accountId, ops, out).ordinal]++
            } catch (e: OperatorAuthorityRequiredException) {
                throw e
            } catch (e: RuntimeException) {
                failed++
                out.println(
                    "event=account.deletion account=${record.accountId.value} outcome=FAILED error=${e.javaClass.simpleName}",
                )
            }
        }
        val anomalies = counts[Advance.ANOMALY.ordinal]
        val overdue = counts[Advance.OVERDUE.ordinal]
        val refused = counts[Advance.REFUSED.ordinal]
        val code =
            when {
                failed > 0 -> EXIT_FAILED
                anomalies > 0 -> EXIT_ANOMALY
                overdue > 0 || refused > 0 || pending.adopted.isNotEmpty() -> EXIT_OVERDUE
                else -> EXIT_DONE
            }
        out.println(
            "event=account.deletions processed=${pending.records.size} erased=${counts[Advance.ERASED.ordinal]} " +
                "pending=${counts[Advance.PENDING.ordinal] + overdue} overdue=$overdue adopted=${pending.adopted.size} " +
                "anomalies=$anomalies refused=$refused failed=$failed exit=$code completedAt=${ops.clock.instant().epochSecond}",
        )
        return code
    }

    /** Read-only: each unfinished deletion's stage and age, and the overdue verdict. */
    private fun deletions(
        ops: Operations,
        out: PrintStream,
    ): Int {
        val report = ops.report.execute()
        report.entries.forEach { e ->
            out.println(
                "event=account.deletion account=${e.record.accountId.value} workspace=${e.workspaceId?.value ?: "-"} " +
                    "stage=${e.record.stage} requestedAt=${e.record.requestedAt} ageSeconds=${maxOf(0L, e.age.seconds)} " +
                    "stageChangedAt=${e.record.stageChangedAt} reservationsHeld=${e.remaining.reservationsHeld} " +
                    "inboxRows=${e.remaining.inboxRows} exactCooldowns=${e.remaining.exactCooldowns} overdue=${e.overdue}" +
                    if (e.accountRowAbsent) " state=ABSENT anomaly=ACCOUNT_ROW_ABSENT" else "",
            )
        }
        report.unrecorded.forEach {
            out.println("event=account.deletion account=${it.value} stage=UNRECORDED overdue=true anomaly=UNRECORDED")
        }
        val anomalies = report.entries.count { it.accountRowAbsent }
        out.println(
            "event=account.deletions pending=${report.entries.size + report.unrecorded.size} overdue=${report.overdue} " +
                "unrecorded=${report.unrecorded.size} anomalies=$anomalies",
        )
        return when {
            anomalies > 0 -> EXIT_ANOMALY
            report.overdue > 0 -> EXIT_OVERDUE
            else -> EXIT_DONE
        }
    }

    private fun status(
        account: HumanAccountId,
        ops: Operations,
        out: PrintStream,
    ): Int {
        val status =
            ops.status.execute(account) ?: run {
                val record = ops.status.deletionRecord(account) ?: return notFound(account, out)
                if (record.stage != AccountDeletionStage.ERASED) return rowAbsent(record, "account.status", out)
                out.println(erasedLine(record, "account.status"))
                return EXIT_DONE
            }
        val deletion =
            status.deletion?.let {
                " deletionStage=${it.stage} deletionRequestedAt=${it.requestedAt} " +
                    "deletionAgeSeconds=${age(it.requestedAt, ops.clock.instant())} deletionOverdue=${ops.report.overdue(it)}"
            } ?: ""
        out.println(
            "event=account.status account=${account.value} workspace=${status.workspaceId?.value ?: "-"} state=${status.state} " +
                "reservationsHeld=${status.reservationsHeld} inboxRows=${status.inboxRows} quarantined=${status.quarantined} " +
                "phase=${status.phase}$deletion",
        )
        return when {
            status.deletion?.let(ops.report::overdue) == true -> EXIT_OVERDUE
            status.phase == LifecyclePhase.DRAINING || status.deletion != null -> EXIT_PENDING
            else -> EXIT_DONE
        }
    }

    private fun erasedLine(
        record: AccountDeletionRecord,
        event: String = "account.deletion",
    ) = "event=$event account=${record.accountId.value} state=ERASED stage=${record.stage} " +
        "requestedAt=${record.requestedAt} erasedAt=${record.erasedAt ?: "-"}"

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
                        "suspendedAccounts=${result.suspendedAccounts} revokedKeys=${result.revokedKeys} " +
                        "legacyRevokedKeys=${result.legacyRevokedKeys} admissions=CLOSED",
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
