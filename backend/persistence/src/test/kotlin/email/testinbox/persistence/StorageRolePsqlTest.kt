package email.testinbox.persistence

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.testcontainers.utility.MountableFile
import java.io.File
import java.util.UUID

/**
 * The reference role model applied the way Ops applies it — psql, `-v` role
 * variables, `ON_ERROR_STOP`, as the schema owner — and what the resulting roles
 * can and cannot do (split from [StorageRoleModelTest]; same fixture,
 * [StorageRoleModelSupport]).
 */
class StorageRolePsqlTest : StorageRoleModelSupport() {
    @Test
    fun `under psql the shipped file isolates a distinct ingestion role and keeps the API's access`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        db.login(ingestion)
        db.login(monitor)
        val run = db.psqlReference(api, ingestion, monitor)
        run.exitCode shouldBe 0
        db.can(api, "SELECT", "api_key") shouldBe true
        db.can(api, "INSERT", "idempotency_record") shouldBe true
        db.can(api, "SELECT", "human_account") shouldBe true
        db.can(ingestion, "SELECT", "api_key") shouldBe false
        db.can(ingestion, "SELECT", "human_session") shouldBe false
        db.can(ingestion, "INSERT", "message") shouldBe true
    }

    @Test
    fun `a role layout the file cannot express is refused before any grant changes`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        db.login(ingestion)
        db.login(monitor)
        db.psqlReference(api, ingestion, monitor).exitCode shouldBe 0
        val member = "ti_member_$suffix"
        db.login(member)
        db.jdbc.sql("GRANT \"$api\" TO \"$member\"").update()
        val reader = "ti_reader_$suffix"
        db.login(reader)
        db.jdbc.sql("GRANT pg_read_all_data TO \"$reader\"").update()
        val ownerMember = "ti_ownermember_$suffix"
        db.login(ownerMember)
        db.jdbc.sql("GRANT \"${postgres.username}\" TO \"$ownerMember\"").update()

        val refused =
            mapOf(
                "identical API and ingestion (the shared production role)" to Triple(api, api, monitor),
                "identical ingestion and monitor" to Triple(api, ingestion, ingestion),
                "a role that does not exist" to Triple(api, "ti_absent_$suffix", monitor),
                "the owner running the file" to Triple(api, postgres.username, monitor),
                "an ingestion role that can become the API role" to Triple(api, member, monitor),
                "an ingestion role that reads all data" to Triple(api, reader, monitor),
                "an API role that is a member of the schema owner (the lifecycle operator authority)" to
                    Triple(ownerMember, ingestion, monitor),
            )
        refused.forEach { (case, roles) ->
            val run = db.psqlReference(roles.first, roles.second, roles.third)
            withClue(case) {
                (run.exitCode != 0) shouldBe true
                run.stderr shouldContain "nothing was changed"
                // The clean slate never ran: the grants of the last good run are intact.
                db.can(api, "SELECT", "api_key") shouldBe true
                db.can(ingestion, "INSERT", "message") shouldBe true
                db.can(ingestion, "SELECT", "api_key") shouldBe false
            }
        }
    }

    @Test
    fun `under psql ingestion holds exactly its delivery allow-list, runs the delivery path, reaches no new relation`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        db.login(ingestion)
        db.login(monitor)
        db.psqlReference(api, ingestion, monitor).exitCode shouldBe 0

        val smtp = db.runDeliveryPathAs(ingestion)

        // Exactly the allow-list: nothing else in public, no view, no other sequence.
        db.effectivePrivileges(ingestion) shouldBe INGESTION_ALLOW_LIST
        // Of the definer functions, the probe pair only.
        db.jdbc
            .sql(
                "SELECT p.oid::regprocedure::text FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace " +
                    "AND p.prosecdef AND has_function_privilege(?, p.oid, 'EXECUTE') ORDER BY 1",
            ).param(ingestion)
            .query(String::class.java)
            .list() shouldBe listOf("storage_record_probe_debt(text)", "storage_resolve_probe_debt(text)")

        // A relation created later - here a view over identity data, as the owner
        // with the API's default privileges - never reaches ingestion.
        db.jdbc.sql("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $api").update()
        db.jdbc
            .sql(
                "CREATE VIEW workspace_eligibility AS SELECT a.contact_email, b.workspace_id " +
                    "FROM human_account a JOIN beta_admission b ON b.account_id = a.id",
            ).update()
        db.jdbc.sql("CREATE TABLE later_table (id integer)").update()
        failure { smtp.sql("SELECT * FROM workspace_eligibility").query().listOfRows() } shouldContain "permission denied"
        failure { smtp.sql("SELECT * FROM later_table").query().listOfRows() } shouldContain "permission denied"
        db.psqlReference(api, ingestion, monitor).exitCode shouldBe 0 // a re-run after a migration adds nothing either
        failure { smtp.sql("SELECT * FROM workspace_eligibility").query().listOfRows() } shouldContain "permission denied"
        db.can(api, "SELECT", "workspace_eligibility") shouldBe true
    }

    @Test
    fun `under psql the operator admission pause refuses the API role and works for the owner`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        db.login(ingestion)
        db.login(monitor)
        db.psqlReference(api, ingestion, monitor).exitCode shouldBe 0

        fun control(source: javax.sql.DataSource): email.testinbox.application.usecase.ControlBetaAdmissions {
            val template =
                org.springframework.transaction.support.TransactionTemplate(
                    org.springframework.jdbc.datasource
                        .DataSourceTransactionManager(source),
                )
            val runner =
                object : email.testinbox.application.port.TransactionRunner {
                    override fun <T> required(block: () -> T): T = checkNotNull(template.execute { block() })
                }
            return email.testinbox.application.usecase
                .ControlBetaAdmissions(JdbcBetaAdmissionControls(JdbcClient.create(source)), runner)
        }

        fun open(): Boolean =
            db.jdbc
                .sql("SELECT admissions_open FROM beta_cohort")
                .query(Boolean::class.javaObjectType)
                .single()
        val asApi = control(db.roleSource(api))
        val asOwner = control(db.dataSource)
        asApi.status().admissionsOpen shouldBe false // status is a read; the runtime role may observe it
        failure { asApi.resume() } shouldContain "not authorized"
        open() shouldBe false
        asOwner.resume().after.admissionsOpen shouldBe true
        failure { asApi.pause() } shouldContain "not authorized"
        open() shouldBe true
        asOwner.pause().after.admissionsOpen shouldBe false
        open() shouldBe false
        db.jdbc
            .sql("SELECT capacity_ready FROM beta_cohort")
            .query(Boolean::class.javaObjectType)
            .single() shouldBe false
    }

    @Test
    fun `under psql the API locks the cohort row but can never open admissions or ready capacity`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        db.login(ingestion)
        db.login(monitor)
        db.psqlReference(api, ingestion, monitor).exitCode shouldBe 0
        val source = db.roleSource(api)
        val asApi = JdbcClient.create(source)
        val tx =
            org.springframework.transaction.support.TransactionTemplate(
                org.springframework.jdbc.datasource
                    .DataSourceTransactionManager(source),
            )
        // The one statement the API runs on beta_cohort (JdbcBetaAdmissions).
        tx.execute {
            asApi
                .sql("SELECT admissions_open, capacity_ready FROM beta_cohort WHERE id = 'beta-30' FOR UPDATE")
                .query { rs, _ -> rs.getBoolean(1) to rs.getBoolean(2) }
                .single()
        } shouldBe (false to false)
        for (column in listOf("admissions_open", "capacity_ready")) {
            failure { asApi.sql("UPDATE beta_cohort SET $column = true").update() } shouldContain "permission denied"
        }
        failure { asApi.sql("INSERT INTO beta_cohort (id) VALUES ('beta-30')").update() } shouldContain "permission denied"
        failure { asApi.sql("DELETE FROM beta_cohort").update() } shouldContain "permission denied"
        db.effectivePrivileges(api)["beta_cohort"] shouldBe setOf("SELECT", "UPDATE(id)")
        db.jdbc
            .sql("SELECT admissions_open OR capacity_ready FROM beta_cohort")
            .query(Boolean::class.javaObjectType)
            .single() shouldBe false
    }

    @Test
    fun `under psql the API cannot write lifecycle state by grant, yet still signs in, locks accounts and admits`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        db.login(ingestion)
        db.login(monitor)
        db.psqlReference(api, ingestion, monitor).exitCode shouldBe 0
        // Grant-level barriers on top of the V22 triggers (STATUS "Required Ops changes" 10): the
        // API keeps exactly the column privilege a row lock needs on human_account.
        val privileges = db.effectivePrivileges(api)
        privileges.getValue("human_account").filter { it.startsWith("UPDATE") } shouldBe listOf("UPDATE(id)")
        privileges.getValue("beta_admission").none { it.startsWith("UPDATE") || it == "DELETE" } shouldBe true
        privileges.getValue("lifecycle_restore_clearance") shouldBe READ
        val account =
            email.testinbox.domain.identity
                .HumanAccountId(UUID.randomUUID())
        db.jdbc
            .sql("INSERT INTO human_account VALUES (:id,'grant-fixture@example.invalid',now(),'ACTIVE',now())")
            .param("id", account.value)
            .update()
        val source = db.roleSource(api)
        val asApi = JdbcClient.create(source)
        val tx =
            SpringTransactionRunner(
                org.springframework.jdbc.datasource
                    .DataSourceTransactionManager(source),
            )
        // Refused by the grant itself, not only by the trigger behind it.
        failure { asApi.sql("UPDATE human_account SET state = 'SUSPENDED' WHERE id = ?").param(account.value).update() } shouldContain
            "permission denied"
        failure { asApi.sql("UPDATE human_account SET contact_email = contact_email WHERE false").update() } shouldContain
            "permission denied"
        failure { asApi.sql("UPDATE beta_admission SET account_id = account_id WHERE false").update() } shouldContain
            "permission denied"
        failure { asApi.sql("DELETE FROM beta_admission WHERE false").update() } shouldContain "permission denied"
        failure { asApi.sql("INSERT INTO lifecycle_restore_clearance (id) VALUES (1)").update() } shouldContain
            "permission denied"
        failure { asApi.sql("DELETE FROM lifecycle_restore_clearance WHERE false").update() } shouldContain "permission denied"
        // ...while every API path that touches these tables still runs under the same role:
        // session issuance and authentication (FOR NO KEY UPDATE on the account), admission
        // (FOR UPDATE on the account and the cohort row, INSERT into beta_admission), and the
        // V22 readiness probe (SELECT on the clearance).
        val sessions = JdbcHumanSessions(asApi)
        val clock = java.time.Clock.systemUTC()
        val issued =
            checkNotNull(
                email.testinbox.application.usecase
                    .IssueHumanSession(sessions, tx, clock)
                    .execute(account),
            )
        email.testinbox.application.usecase
            .AuthenticateHumanSession(sessions, tx, clock)
            .execute(issued.credential.value)!!
            .account.id shouldBe account
        db.jdbc.sql("UPDATE beta_cohort SET admissions_open = true, capacity_ready = true").update()
        email.testinbox.application.usecase
            .AdmitBetaParticipant(
                JdbcBetaAdmissions(asApi),
                tx,
                clock,
                email.testinbox.application.storage
                    .CohortStorageReadiness { true },
            ).execute(account)
            .shouldBeInstanceOf<email.testinbox.application.usecase.AdmitBetaParticipant.Result.Admitted>()
        JdbcLifecyclePrivilegeProbe(asApi, "api").api() shouldBe null
        asApi
            .sql("SELECT count(*) FROM lifecycle_restore_clearance")
            .query(Long::class.javaObjectType)
            .single() shouldBe 1
        db.jdbc
            .sql("SELECT state FROM human_account WHERE id = ?")
            .param(account.value)
            .query(String::class.java)
            .single() shouldBe "ACTIVE"
    }
}
