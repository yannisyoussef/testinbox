package email.testinbox.persistence

import email.testinbox.application.port.WorkspaceEligibility
import email.testinbox.application.port.WorkspaceStatus
import email.testinbox.domain.WorkspaceId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Reads `workspace_lifecycle_eligibility` (V22), the only lifecycle relation
 * the SMTP role may read: workspace id and a boolean, never identity data.
 * No row means no participant marker, i.e. a legacy workspace.
 */
@Repository
class JdbcWorkspaceEligibility(
    private val jdbc: JdbcClient,
) : WorkspaceEligibility {
    override fun status(workspaceId: WorkspaceId): WorkspaceStatus =
        jdbc
            .sql("SELECT active FROM workspace_lifecycle_eligibility WHERE workspace_id = :workspace")
            .param("workspace", workspaceId.value)
            .query(Boolean::class.javaObjectType)
            .optional()
            .map { active -> if (active) WorkspaceStatus.ACTIVE_PARTICIPANT else WorkspaceStatus.INACTIVE_PARTICIPANT }
            .orElse(WorkspaceStatus.LEGACY)
}
