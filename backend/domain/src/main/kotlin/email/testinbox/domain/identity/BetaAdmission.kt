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

/**
 * The configured participant cap N (`testinbox.beta.max-participants`): how many
 * of the cohort's places new admissions may consume. It may only LOWER
 * [BetaCohortPolicy.MAX_PARTICIPANTS], which stays the hard bound the database
 * guard (V12 `place BETWEEN 1 AND 30`) and the cohort pool are sized for.
 *
 * Lowering N below the places already consumed evicts nobody: it only stops
 * NEW admissions, which answer `Full` exactly as at thirty.
 */
@JvmInline
value class BetaParticipantCap(
    val value: Int,
) {
    init {
        require(value in 1..BetaCohortPolicy.MAX_PARTICIPANTS) {
            "$PROPERTY must be in 1..${BetaCohortPolicy.MAX_PARTICIPANTS} (it may only lower the cohort's " +
                "${BetaCohortPolicy.MAX_PARTICIPANTS} places), but is $value"
        }
    }

    /** Whether a new admission may take [place] (places are consumed in order, 1, 2, ...). */
    fun admits(place: Int): Boolean = place <= value

    /** Whether this is the hard bound itself, i.e. nothing was lowered. */
    val unlowered: Boolean get() = value == BetaCohortPolicy.MAX_PARTICIPANTS

    companion object {
        const val PROPERTY: String = "testinbox.beta.max-participants"

        /** The default: the cohort's full thirty places, so an unset property changes nothing. */
        val HARD_LIMIT: BetaParticipantCap = BetaParticipantCap(BetaCohortPolicy.MAX_PARTICIPANTS)
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
