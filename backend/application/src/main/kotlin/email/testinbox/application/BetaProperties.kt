package email.testinbox.application

import email.testinbox.domain.identity.BetaCohortPolicy
import email.testinbox.domain.identity.BetaParticipantCap

/**
 * Configuration binding for `testinbox.beta.*`, bound by BOTH deployables:
 * the API admits participants, and the ingestion gateway's INGEST envelope
 * (`IngestEnvelopeSafety`) is computed from the same N. One class, so the two
 * cannot read the key differently.
 *
 * [maxParticipants] is N (`TESTINBOX_BETA_MAX_PARTICIPANTS`), an integer in
 * 1..30, default 30, so an unset property changes nothing. It may only lower
 * the cohort's thirty places. A value outside the range, or one that is not an
 * integer, fails binding and the process refuses to start, in every
 * environment: a cap that silently fell back to thirty would be worse than none.
 */
data class BetaProperties(
    val maxParticipants: Int = BetaCohortPolicy.MAX_PARTICIPANTS,
) {
    /** Validated on construction, so binding fails on an out-of-range value. */
    val cap: BetaParticipantCap = BetaParticipantCap(maxParticipants)
}
