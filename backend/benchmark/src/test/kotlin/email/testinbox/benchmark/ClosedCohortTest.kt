package email.testinbox.benchmark

import email.testinbox.domain.storage.ParticipantStorageShare
import email.testinbox.persistence.JdbcFootprintGate
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.random.Random

/**
 * `--focused-cohort --cohort-participants N` against a real schema (Testcontainers, the `--local`
 * target): N participants with the ADR-037 participant share plus one legacy share, and nothing
 * else, reserved and approved through production's `ReserveCohortStorage`, so the pool is REQUIRED.
 */
class ClosedCohortTest {
    @Test
    fun `ten participants seed eleven workspaces whose pool is approved and REQUIRED`() {
        BenchmarkTarget.local().use { target ->
            target.pool(2).use { pool ->
                target.migrate(pool)
                val admin = JdbcClient.create(pool)
                val options =
                    BenchmarkOptions.parse(
                        listOf("--local", "--focused-cohort", "--cohort-participants", "10", "--reservation-backlog", "20"),
                    )
                val closed =
                    ClosedCohort(
                        admin,
                        TransactionTemplate(DataSourceTransactionManager(pool)),
                        Seeder(admin, Random(options.seed)),
                        FootprintSeed.monitorRole(admin),
                        options.cohortParticipants,
                    )

                val cohort = closed.seed(options)

                cohort.workspaceCount shouldBe 11
                cohort.workspaceIds.size shouldBe 11
                cohort.inboxes.size shouldBe 11 * options.inboxesPerWorkspace
                count(admin, "SELECT count(*) FROM workspace") shouldBe 11
                // The participants are sized as the thirty are: the ADR-037 share, by V19's defaults.
                count(
                    admin,
                    "SELECT count(*) FROM participant_workspace WHERE payload_limit_bytes = ${ParticipantStorageShare.PAYLOAD_BYTES} " +
                        "AND footprint_limit_bytes = ${ParticipantStorageShare.FOOTPRINT_BYTES}",
                ) shouldBe 10
                admin
                    .sql("SELECT workspace_id FROM storage_legacy_share")
                    .query(UUID::class.java)
                    .list() shouldBe listOf(closed.legacyWorkspace(cohort))
                (closed.legacyWorkspace(cohort) in cohort.workspaceIds) shouldBe true
                count(admin, "SELECT count(*) FROM participant_workspace WHERE workspace_id = '${closed.legacyWorkspace(cohort)}'") shouldBe
                    0
                count(admin, "SELECT count(*) FROM storage_cohort_plan WHERE approved") shouldBe 1

                val state =
                    JdbcFootprintGate(admin)
                        .observe()
                        .shouldNotBeNull()
                        .cohort
                        .shouldNotBeNull()
                state.hasParticipants shouldBe true
                state.required shouldBe true
                state.unknownWorkspaces shouldBe 0
                state.binding.shouldNotBeNull()
                state.loads.size shouldBe 11
                state.loads.values.count { it.participant } shouldBe 10
            }
        }
    }

    private fun count(
        jdbc: JdbcClient,
        sql: String,
    ): Long = jdbc.sql(sql).query(Long::class.java).single()
}
