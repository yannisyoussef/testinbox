package email.testinbox.persistence

import email.testinbox.application.port.BetaAdmissions
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.AdmissionApplicant
import email.testinbox.domain.identity.BetaAdmission
import email.testinbox.domain.identity.BetaCohortPolicy
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanAccountState
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** PostgreSQL owns the cohort lock and the unique seat/account/workspace constraints. */
@Repository
class JdbcBetaAdmissions(
    private val jdbc: JdbcClient,
) : BetaAdmissions {
    override fun lockPolicy(): BetaCohortPolicy {
        requireTransaction()
        return jdbc
            .sql("SELECT admissions_open, capacity_ready FROM beta_cohort WHERE id = :cohort FOR UPDATE")
            .param("cohort", BetaCohortPolicy.NAME)
            .query { rs, _ -> BetaCohortPolicy(rs.getBoolean("admissions_open"), rs.getBoolean("capacity_ready")) }
            .single()
    }

    override fun lockApplicant(id: HumanAccountId): AdmissionApplicant? {
        requireTransaction()
        return jdbc
            .sql("SELECT state, contact_verified_at FROM human_account WHERE id = :id FOR UPDATE")
            .param("id", id.value)
            .query { rs, _ ->
                AdmissionApplicant(
                    id,
                    HumanAccountState.valueOf(rs.getString("state")),
                    rs.getObject("contact_verified_at") != null,
                )
            }.optional()
            .orElse(null)
    }

    override fun findAdmission(id: HumanAccountId): BetaAdmission? =
        jdbc
            .sql("SELECT workspace_id, project_id, place, admitted_at FROM beta_admission WHERE account_id = :account")
            .param("account", id.value)
            .query { rs, _ ->
                BetaAdmission(
                    id,
                    WorkspaceId(checkNotNull(rs.getObject("workspace_id", UUID::class.java))),
                    ProjectId(checkNotNull(rs.getObject("project_id", UUID::class.java))),
                    rs.getInt("place"),
                    checkNotNull(Timestamps.fromDb(rs, "admitted_at")),
                )
            }.optional()
            .orElse(null)

    override fun nextPlace(): Int? {
        requireTransaction()
        val next = jdbc.sql("SELECT coalesce(max(place), 0) + 1 FROM beta_admission").query(Int::class.javaObjectType).single()
        return next.takeIf { it <= BetaCohortPolicy.MAX_PARTICIPANTS }
    }

    override fun create(
        admission: BetaAdmission,
        workspace: Workspace,
        project: Project,
    ) {
        requireTransaction()
        require(admission.workspaceId == workspace.id && admission.projectId == project.id && project.workspaceId == workspace.id)
        jdbc
            .sql("INSERT INTO workspace (id, name, created_at) VALUES (:id, :name, :at)")
            .param("id", workspace.id.value)
            .param("name", workspace.name)
            .param("at", Timestamps.toDb(workspace.createdAt))
            .update()
        jdbc
            .sql("INSERT INTO project (id, workspace_id, name, created_at) VALUES (:id, :workspace, :name, :at)")
            .param("id", project.id.value)
            .param("workspace", project.workspaceId.value)
            .param("name", project.name)
            .param("at", Timestamps.toDb(project.createdAt))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO beta_admission (cohort_id, place, account_id, workspace_id, project_id, admitted_at)
                VALUES (:cohort, :place, :account, :workspace, :project, :at)
                """.trimIndent(),
            ).param("cohort", BetaCohortPolicy.NAME)
            .param("place", admission.place)
            .param("account", admission.accountId.value)
            .param("workspace", admission.workspaceId.value)
            .param("project", admission.projectId.value)
            .param("at", Timestamps.toDb(admission.admittedAt))
            .update()
    }

    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "beta admission requires a database transaction" }
    }
}
