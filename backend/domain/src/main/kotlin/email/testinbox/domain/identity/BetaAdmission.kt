package email.testinbox.domain.identity

import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import java.time.Instant
import java.util.UUID

@JvmInline
value class HumanAccountId(
    val value: UUID,
)

enum class HumanAccountState {
    ACTIVE,
    SUSPENDED,
    DELETION_PENDING,
}

/** A verified account is not yet a participant (ADR-036). */
data class AdmissionApplicant(
    val id: HumanAccountId,
    val state: HumanAccountState,
    val contactVerified: Boolean,
) {
    fun eligible(): Boolean = state == HumanAccountState.ACTIVE && contactVerified
}

/** Both controls start closed. Neither can be supplied by a signup request. */
data class BetaCohortPolicy(
    val admissionsOpen: Boolean,
    val capacityReady: Boolean,
) {
    companion object {
        const val NAME: String = "beta-30"
        const val MAX_PARTICIPANTS: Int = 30
    }
}

data class BetaAdmission(
    val accountId: HumanAccountId,
    val workspaceId: WorkspaceId,
    val projectId: ProjectId,
    val place: Int,
    val admittedAt: Instant,
) {
    init {
        require(place in 1..BetaCohortPolicy.MAX_PARTICIPANTS) { "place is outside the beta cohort" }
    }
}
