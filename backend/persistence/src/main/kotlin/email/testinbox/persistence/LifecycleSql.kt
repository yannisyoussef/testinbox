package email.testinbox.persistence

/** Shared account-lifecycle SQL (V22). */
internal object LifecycleSql {
    /**
     * The account state every runtime authorization reads. While the database
     * carries no restore clearance (a restored backup, or an upgrade that
     * found participants), every account reads as SUSPENDED: no sign-in, no
     * session authentication, no admission, until the operator reconciles.
     * The stored state itself is untouched; only the operator writer reads it raw.
     */
    const val EFFECTIVE_STATE: String =
        "CASE WHEN EXISTS (SELECT 1 FROM lifecycle_restore_clearance) THEN state ELSE 'SUSPENDED' END"
}
