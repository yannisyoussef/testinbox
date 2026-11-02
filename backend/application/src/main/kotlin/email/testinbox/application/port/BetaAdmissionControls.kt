package email.testinbox.application.port

import email.testinbox.domain.identity.BetaCohortPolicy

/**
 * Operator-only cohort switch (ADR-036 §5). Runtime API/SMTP roles cannot
 * write `admissions_open`; the operator command composes this with explicit
 * owner-level credentials. Every call runs inside one transaction.
 */
interface BetaAdmissionControls {
    /** Locks the cohort row: the same first lock admission and pool changes take. */
    fun lockPolicy(): BetaCohortPolicy

    /** A plain read for status; needs no write privilege. */
    fun readPolicy(): BetaCohortPolicy

    /** Whether the connected database role may write the admission switch, asked inside the transaction. */
    fun operatorAuthorized(): Boolean

    /** Changes only `admissions_open`; never `capacity_ready`, places, accounts or waitlist entries. */
    fun setAdmissionsOpen(open: Boolean)

    /** Consumed places, including tombstones of deleted accounts. */
    fun consumedPlaces(): Int
}
