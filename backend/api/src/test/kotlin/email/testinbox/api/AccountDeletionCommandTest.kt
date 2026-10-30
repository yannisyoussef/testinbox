package email.testinbox.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID

/**
 * The operator's deletion workflow subcommands against real PostgreSQL, with
 * the schema owner's credentials (the operator authority): `delete` running
 * to ERASED, `status` of an erased account, and `process-deletions` /
 * `status` reporting an overdue deletion with exit 5 until it completes.
 */
class AccountDeletionCommandTest : ApiIntegrationTestBase() {
    @Autowired lateinit var jdbc: JdbcClient

    private data class Run(
        val code: Int,
        val out: String,
        val err: String,
    )

    private fun cli(vararg args: String): Run {
        val env =
            mapOf(
                "TI_LIFECYCLE_JDBC_URL" to postgres.jdbcUrl.substringBefore('?'),
                "TI_LIFECYCLE_USER" to postgres.username,
                "TI_LIFECYCLE_PASSWORD" to postgres.password,
            )
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = AccountLifecycleCommand.run(arrayOf("account-lifecycle", *args), env::get, PrintStream(out), PrintStream(err))
        return Run(code, out.toString(), err.toString())
    }

    private fun account(): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO human_account VALUES (?, ?, now(), 'ACTIVE', now())").params(id, "$id@example.invalid").update()
        return id
    }

    private fun count(
        sql: String,
        vararg params: Any,
    ): Long =
        jdbc
            .sql(sql)
            .params(*params)
            .query(Long::class.java)
            .single()

    @Test
    fun `delete erases an unadmitted account in one run, and re-running it or status reports ERASED`() {
        val id = account()
        jdbc
            .sql(
                "INSERT INTO human_identity (provider, issuer, subject, account_id, created_at) VALUES ('GITHUB', 'https://github.com', ?, ?, now())",
            ).params("fixture-$id", id)
            .update()

        val deleted = cli("delete", id.toString())
        deleted.code shouldBe AccountLifecycleCommand.EXIT_DONE
        deleted.out shouldContain "event=account.lifecycle action=DELETE account=$id"
        deleted.out shouldContain "event=account.deletion account=$id workspace=- stage=ERASED"
        deleted.out shouldContain "erasedNow=true"
        deleted.out shouldNotContain "@example.invalid"
        count("SELECT count(*) FROM human_account WHERE id = ?", id) shouldBe 0
        count("SELECT count(*) FROM human_identity WHERE account_id = ?", id) shouldBe 0

        // A restore replay re-running `delete` for an erased account succeeds without change.
        cli("delete", id.toString()).let {
            it.code shouldBe AccountLifecycleCommand.EXIT_DONE
            it.out shouldContain "state=ERASED stage=ERASED"
        }
        cli("status", id.toString()).let {
            it.code shouldBe AccountLifecycleCommand.EXIT_DONE
            it.out shouldContain "event=account.status account=$id state=ERASED"
        }
        cli("status", UUID.randomUUID().toString()).code shouldBe AccountLifecycleCommand.EXIT_REFUSED
    }

    @Test
    fun `process-deletions and status exit 5 while a deletion is overdue, and 0 once the timer completes it`() {
        val id = account()
        val workspace = UUID.randomUUID()
        val place = count("SELECT coalesce(max(place), 0) + 1 FROM beta_admission").toInt()
        jdbc.sql("INSERT INTO workspace (id, name, created_at) VALUES (?, 'My workspace', now())").param(workspace).update()
        jdbc
            .sql(
                "INSERT INTO project (id, workspace_id, name, created_at) VALUES (?, ?, 'Default', now())",
            ).params(workspace, workspace)
            .update()
        jdbc
            .sql(
                "INSERT INTO beta_admission (cohort_id, place, account_id, workspace_id, project_id, admitted_at) VALUES ('beta-30', ?, ?, ?, ?, now())",
            ).params(place, id, workspace, workspace)
            .update()
        jdbc
            .sql("INSERT INTO participant_workspace (workspace_id, cohort_id, place, allocated_at) VALUES (?, 'beta-30', ?, now())")
            .params(workspace, place)
            .update()
        // A copy reserved before the fence: the deletion drains.
        jdbc
            .sql(
                """
                INSERT INTO storage_reservation (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at,
                                                 write_deadline_at, node_id, generation)
                VALUES (?, ?, ?, '{k}', 10, 'RESERVED', now(), now() + interval '2 minutes', 'deletion-test', ?)
                """.trimIndent(),
            ).params(UUID.randomUUID(), workspace, UUID.randomUUID(), UUID.randomUUID())
            .update()
        try {
            cli("delete", id.toString()).let {
                it.code shouldBe AccountLifecycleCommand.EXIT_PENDING
                it.out shouldContain "stage=DRAINING"
                it.out shouldContain "overdue=false"
            }
            cli("process-deletions").code shouldBe AccountLifecycleCommand.EXIT_DONE

            // Requested four days ago: past the shipped 72 h threshold.
            jdbc
                .sql(
                    "UPDATE account_deletion SET requested_at = now() - interval '4 days', stage_changed_at = now() - interval '4 days' WHERE account_id = ?",
                ).param(id)
                .update()
            cli("process-deletions").let {
                it.code shouldBe AccountLifecycleCommand.EXIT_OVERDUE
                it.out shouldContain "event=account.deletion account=$id workspace=$workspace stage=DRAINING"
                it.out shouldContain "overdue=true"
                it.out shouldContain "overdue=1"
            }
            cli("status").let {
                it.code shouldBe AccountLifecycleCommand.EXIT_OVERDUE
                it.out shouldContain "account=$id"
                it.out shouldContain "reservationsHeld=1"
            }
            cli("status", id.toString()).let {
                it.code shouldBe AccountLifecycleCommand.EXIT_OVERDUE
                it.out shouldContain "deletionStage=DRAINING"
                it.out shouldContain "deletionOverdue=true"
            }

            jdbc.sql("DELETE FROM storage_reservation WHERE workspace_id = ?").param(workspace).update()
            cli("process-deletions").let {
                it.code shouldBe AccountLifecycleCommand.EXIT_DONE
                it.out shouldContain "account=$id workspace=$workspace stage=ERASED"
                it.out shouldContain "erased=1"
            }
            cli("status").let {
                it.code shouldBe AccountLifecycleCommand.EXIT_DONE
                it.out shouldNotContain "account=$id"
            }
            count("SELECT count(*) FROM human_account WHERE id = ?", id) shouldBe 0
            count("SELECT count(*) FROM beta_admission WHERE place = ? AND account_id IS NULL AND workspace_id IS NULL", place) shouldBe 1
            count("SELECT count(*) FROM participant_workspace WHERE workspace_id = ?", workspace) shouldBe 1
        } finally {
            jdbc.sql("DELETE FROM storage_reservation WHERE workspace_id = ?").param(workspace).update()
            jdbc.sql("DELETE FROM participant_workspace WHERE workspace_id = ?").param(workspace).update()
            jdbc.sql("DELETE FROM beta_admission WHERE place = ?").param(place).update()
        }
    }

    @Test
    fun `an adopted deletion and a record without its account row are anomalies with their own exit codes`() {
        // DELETION_PENDING with no record: a hand-edited or restored database.
        val unrecorded = account()
        jdbc.sql("UPDATE human_account SET state = 'DELETION_PENDING' WHERE id = ?").param(unrecorded).update()
        cli("status").let {
            it.code shouldBe AccountLifecycleCommand.EXIT_OVERDUE
            it.out shouldContain "account=$unrecorded stage=UNRECORDED"
        }
        cli("process-deletions").let {
            // Adopting resets the age, so the adopting run itself raises the signal.
            it.code shouldBe AccountLifecycleCommand.EXIT_OVERDUE
            it.out shouldContain "account=$unrecorded stage=DRAINING anomaly=ADOPTED_UNRECORDED"
            it.out shouldContain "adopted=1"
            it.out shouldContain "exit=5"
        }
        // Adopted and then erased in that same run (it had no content).
        count("SELECT count(*) FROM human_account WHERE id = ?", unrecorded) shouldBe 0

        // A pending record whose account row was deleted by hand.
        val gone = account()
        cli("delete", gone.toString()).code shouldBe AccountLifecycleCommand.EXIT_DONE
        jdbc
            .sql("UPDATE account_deletion SET stage = 'CONTENT_DELETING', erased_at = NULL WHERE account_id = ?")
            .param(gone)
            .update()
        try {
            cli("status", gone.toString()).let {
                it.code shouldBe AccountLifecycleCommand.EXIT_ANOMALY
                it.out shouldContain "state=ABSENT stage=CONTENT_DELETING"
                it.out shouldContain "anomaly=ACCOUNT_ROW_ABSENT"
                it.out shouldNotContain "state=ERASED"
            }
            cli("delete", gone.toString()).code shouldBe AccountLifecycleCommand.EXIT_ANOMALY
            cli("status").code shouldBe AccountLifecycleCommand.EXIT_ANOMALY
            cli("process-deletions").let {
                it.code shouldBe AccountLifecycleCommand.EXIT_ANOMALY
                it.out shouldContain "anomalies=1"
            }
            count("SELECT count(*) FROM account_deletion WHERE account_id = ? AND stage = 'CONTENT_DELETING'", gone) shouldBe 1
        } finally {
            // The operator's documented resolution, so later suites see a clean worklist.
            jdbc
                .sql("UPDATE account_deletion SET stage = 'ERASED', stage_changed_at = now(), erased_at = now() WHERE account_id = ?")
                .param(gone)
                .update()
        }
    }
}
