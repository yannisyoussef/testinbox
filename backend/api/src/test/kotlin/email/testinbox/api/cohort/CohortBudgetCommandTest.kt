package email.testinbox.api.cohort

import email.testinbox.api.CohortBudgetCommand
import email.testinbox.application.storage.StorageDeclarations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TI-DELIVERY-002b acceptance item 2: the offline `storage-budget` operator
 * command end to end — `CohortBudgetCommand.run` with a reviewed JSON file,
 * owner credentials from the environment seam, against a real migrated
 * PostgreSQL. `check`, `reserve` and `revoke` as Ops run them; refusal while
 * admissions or capacity are open; wrong declarations; a missing or incomplete
 * legacy inventory; and the bounded operator transaction (a held cohort row
 * aborts the command at the 5 s lock bound, tagged `ops:cohort-budget`, with
 * nothing written).
 */
class CohortBudgetCommandTest {
    private lateinit var db: CohortTestDatabase
    private lateinit var declarations: StorageDeclarations
    private lateinit var legacy: UUID
    private val output = mutableListOf<String>()
    private val files = mutableListOf<Path>()

    @BeforeEach
    fun database() {
        db = CohortTestDatabase.create()
        declarations = db.declarations()
        legacy = db.workspace()
        db.ready(declarations)
    }

    @AfterEach
    fun files() = files.forEach { Files.deleteIfExists(it) }

    private fun owner(name: String): String? =
        mapOf(
            "TI_BUDGET_JDBC_URL" to db.url,
            "TI_BUDGET_USER" to CohortTestDatabase.postgres.username,
            "TI_BUDGET_PASSWORD" to CohortTestDatabase.postgres.password,
        )[name]

    private fun run(
        verb: String,
        file: Path,
        env: (String) -> String? = ::owner,
    ) = CohortBudgetCommand.run(arrayOf("storage-budget", verb, file.toString()), env) { output += it }

    /** The reviewed operator file, written the way Ops write it: explicit members only, no defaults. */
    private fun declarationsJson(
        enforcement: String = "ALL",
        globalLimitBytes: Long = checkNotNull(declarations.globalLimitBytes),
        globalFootprintLimitBytes: Long = checkNotNull(declarations.filesystem.globalFootprintLimitBytes),
    ): String {
        val fs = declarations.filesystem
        return """{"enforcement":"$enforcement","globalLimitBytes":$globalLimitBytes,
            "declaredBucketQuotaBytes":${declarations.declaredBucketQuotaBytes},
            "declaredMaxIngestionProcesses":${declarations.declaredMaxIngestionProcesses},"inboxShare":"${declarations.inboxShare}",
            "expectedApiNodes":["${CohortTestDatabase.API}"],"expectedIngestionNodes":["${CohortTestDatabase.SMTP}"],
            "filesystem":{"blockSizeBytes":${fs.blockSizeBytes},"objectOverheadMaxBytes":${fs.objectOverheadMaxBytes},
              "globalFootprintLimitBytes":$globalFootprintLimitBytes,"deletionDebtBudgetBytes":${fs.deletionDebtBudgetBytes},
              "metadataBudgetBytes":${fs.metadataBudgetBytes},"operationalReserveBytes":${fs.operationalReserveBytes},
              "capacityBytes":${fs.capacityBytes},"inodes":${fs.inodes},"observationMaxAge":"PT15M",
              "probeBudgetBytes":${fs.probeBudgetBytes},"monitorRole":"${fs.monitorRole}"}}"""
    }

    private fun legacyJson(vararg workspaces: UUID) =
        workspaces.joinToString(",", "[", "]") {
            """{"workspaceId":"$it","payloadBytes":${CohortTestDatabase.GIB},"footprintBytes":${2 * CohortTestDatabase.GIB}}"""
        }

    private fun file(
        declarations: String = declarationsJson(),
        legacy: String? = legacyJson(this.legacy),
        unattributed: String = "${CohortTestDatabase.GIB}",
        trash: String = "${CohortTestDatabase.GIB}",
    ): Path {
        val members =
            listOfNotNull(
                "\"declarations\":$declarations",
                legacy?.let { "\"legacy\":$it" },
                "\"unattributedAllowance\":$unattributed",
                "\"trashAllowance\":$trash",
            )
        return Files.createTempFile("reviewed-pool", ".json").also {
            files.add(it)
            Files.writeString(it, members.joinToString(",", "{", "}"))
        }
    }

    private fun plans() = db.count("SELECT count(*) FROM storage_cohort_plan")

    private fun approved() = db.count("SELECT count(*) FROM storage_cohort_plan WHERE approved")

    private fun shares() = db.count("SELECT count(*) FROM storage_legacy_share")

    private fun closed() = db.count("SELECT count(*) FROM beta_cohort WHERE NOT admissions_open AND NOT capacity_ready") shouldBe 1

    private fun nothingWritten() {
        plans() shouldBe 0
        shares() shouldBe 0
    }

    @Test
    fun `check, reserve, check and revoke end to end - and the binary binding matches the running nodes`() {
        val reviewed = file()
        shouldThrow<IllegalStateException> { run("check", reviewed) }.message shouldBe "cohort storage is not ready"

        run("reserve", reviewed)
        output.last() shouldBe "cohort storage reserve: PASS; admissions unchanged"
        approved() shouldBe 1
        shares() shouldBe 1
        db.jdbc
            .sql("SELECT binding FROM storage_cohort_plan")
            .query(String::class.java)
            .single() shouldBe db.binding(declarations)
        closed()

        run("check", reviewed)
        output.last() shouldBe "cohort storage check: PASS; admissions unchanged"
        // The line before the PASS binds it to the exact reviewed file (activation gate G).
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(
                    java.nio.file.Files
                        .readAllBytes(reviewed),
                ).joinToString("") { "%02x".format(it) }
        output[output.size - 2] shouldBe "cohort storage check: reviewed-file sha256=$digest"

        run("revoke", reviewed)
        output.last() shouldBe "cohort storage revoke: PASS; admissions unchanged"
        approved() shouldBe 0
        shares() shouldBe 1 // revocation never erases an allocation
        shouldThrow<IllegalStateException> { run("check", reviewed) }.message shouldBe "cohort storage is not ready"
        closed()
    }

    @Test
    fun `check refuses a file that differs from the installed pool`() {
        run("reserve", file())
        shouldThrow<IllegalStateException> { run("check", file(trash = "${2 * CohortTestDatabase.GIB}")) }.message shouldBe
            "operator file differs from installed pool"
        shouldThrow<IllegalStateException> {
            run("check", file(legacy = legacyJson(legacy).replace("${2 * CohortTestDatabase.GIB}", "${3 * CohortTestDatabase.GIB}")))
        }.message shouldBe "operator file differs from installed pool"
    }

    @Test
    fun `reserve and revoke refuse while admissions or capacity are open, and write nothing`() {
        for ((admissions, capacity) in listOf(true to false, false to true, true to true)) {
            db.open(admissions, capacity)
            shouldThrow<IllegalStateException> { run("reserve", file()) }.message shouldBe
                "close admissions and capacity before changing a pool"
            nothingWritten()
            shouldThrow<IllegalStateException> { run("revoke", file()) }.message shouldBe
                "close admissions and capacity before changing a pool"
        }
        output shouldBe emptyList()
    }

    @Test
    fun `wrong declarations are refused before any write`() {
        shouldThrow<IllegalArgumentException> { run("reserve", file(declarations = declarationsJson(enforcement = "OFF"))) }
            .message shouldBe "cohort approval requires ALL declarations"
        // G_F of 4 GiB cannot hold thirty 256 MiB shares.
        shouldThrow<IllegalArgumentException> {
            run("reserve", file(declarations = declarationsJson(globalFootprintLimitBytes = 4 * CohortTestDatabase.GIB)))
        }.message shouldBe "all thirty shares and reserves must fit"
        // Declarations the running nodes were not started with: another binding, rolled back after the write.
        shouldThrow<IllegalStateException> {
            run("reserve", file(declarations = declarationsJson(globalLimitBytes = 9 * CohortTestDatabase.GIB)))
        }.message shouldBe "pool inventory, live charges, nodes or observation not ready"
        // A misspelt member is never silently a default.
        shouldThrow<Exception> { run("reserve", file(declarations = declarationsJson().replace("inboxShare", "inboxShares"))) }
        shouldThrow<IllegalArgumentException> { run("reserve", file(unattributed = "-1")) }.message shouldBe
            "explicit nonnegative unattributedAllowance required"
        shouldThrow<IllegalArgumentException> { run("reserve", file(trash = "1.5")) }.message shouldBe
            "explicit nonnegative trashAllowance required"
        nothingWritten()
        output shouldBe emptyList()
    }

    @Test
    fun `a missing or incomplete legacy inventory is refused, and an unknown workspace rolls the pool back`() {
        shouldThrow<IllegalArgumentException> { run("reserve", file(legacy = null)) }.message shouldBe
            "explicit declarations and legacy inventory required"
        shouldThrow<IllegalArgumentException> { run("reserve", file(legacy = legacyJson(legacy, legacy))) }.message shouldBe
            "duplicate legacy workspace"
        // An empty list while a non-participant workspace exists: written, re-observed, rolled back.
        shouldThrow<IllegalStateException> { run("reserve", file(legacy = "[]")) }.message shouldBe
            "pool inventory, live charges, nodes or observation not ready"
        // A second workspace the file forgot: the same.
        db.workspace()
        shouldThrow<IllegalStateException> { run("reserve", file()) }.message shouldBe
            "pool inventory, live charges, nodes or observation not ready"
        nothingWritten()
    }

    @Test
    fun `credentials come only from the environment, never from the URL`() {
        shouldThrow<IllegalStateException> { run("check", file()) { null } }.message shouldBe "missing TI_BUDGET_JDBC_URL"
        shouldThrow<IllegalStateException> { run("check", file()) { if (it == "TI_BUDGET_PASSWORD") " " else owner(it) } }
            .message shouldBe "missing TI_BUDGET_PASSWORD"
        for (url in listOf("${db.url}?password=x", "${db.url}?user=postgres", db.url.replace("//", "//postgres:x@"))) {
            shouldThrow<IllegalArgumentException> { run("check", file()) { if (it == "TI_BUDGET_JDBC_URL") url else owner(it) } }
                .message shouldBe "TI_BUDGET_JDBC_URL must be a jdbc:postgresql URL without credentials"
        }
        shouldThrow<IllegalArgumentException> { CohortBudgetCommand.run(arrayOf("storage-budget", "open", "x"), ::owner) }
            .message shouldContain "usage: storage-budget check|reserve|revoke"
    }

    @Test
    fun `the operator transaction is bounded - a held cohort row aborts reserve at the lock bound, tagged ops-cohort-budget`() {
        val holder = DriverManager.getConnection(db.url, CohortTestDatabase.postgres.username, CohortTestDatabase.postgres.password)
        val pool = Executors.newSingleThreadExecutor()
        try {
            holder.autoCommit = false
            holder.createStatement().use { it.execute("SELECT 1 FROM beta_cohort WHERE id='beta-30' FOR UPDATE") }
            val started = System.nanoTime()
            val reserve = pool.submit<Throwable?> { runCatching { run("reserve", file()) }.exceptionOrNull() }
            // While it waits, the waiting session is the tagged operator session.
            var waiting = 0L
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (waiting == 0L && System.nanoTime() < deadline) {
                waiting =
                    db.count(
                        "SELECT count(*) FROM pg_stat_activity WHERE application_name = '${CohortBudgetCommand.APPLICATION_NAME}' " +
                            "AND wait_event_type = 'Lock'",
                    )
                if (waiting == 0L) Thread.onSpinWait()
            }
            waiting shouldBe 1
            val failure = checkNotNull(reserve.get(30, TimeUnit.SECONDS)) { "reserve must not succeed past a held cohort row" }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("lock timeout") } shouldBe true
            elapsedMs shouldBeGreaterThanOrEqual 4_000
            elapsedMs shouldBeLessThan 9_000
        } finally {
            holder.rollback()
            holder.close()
            pool.shutdownNow()
        }
        nothingWritten()
        output shouldBe emptyList()
        // Released, the same file reserves normally.
        run("reserve", file())
        approved() shouldBe 1
    }
}
