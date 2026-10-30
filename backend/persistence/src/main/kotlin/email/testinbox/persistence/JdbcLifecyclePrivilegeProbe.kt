package email.testinbox.persistence

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.concurrent.atomic.AtomicReference

/**
 * Readiness probe for the account-lifecycle relations (V22). A deployable
 * whose role lacks the grant would otherwise run with every T1 failing
 * (SMTP: a uniform `451` for all mail) or every key authentication failing,
 * while reporting healthy. Zero-row reads only; nothing is locked or written.
 */
class JdbcLifecyclePrivilegeProbe(
    private val jdbc: JdbcClient,
    private val deployable: String,
) {
    /** Closed vocabulary: the only detail readiness or the log ever reports. */
    enum class Failure {
        ELIGIBILITY_VIEW_UNREADABLE,
        RESTORE_CLEARANCE_UNREADABLE,
    }

    private val last = AtomicReference<Failure?>(null)

    /** Ingestion reads the eligibility view in recipient resolution and T1. */
    fun ingestion(): Failure? = report(read(ELIGIBILITY, Failure.ELIGIBILITY_VIEW_UNREADABLE))

    /** The API reads the view (key authentication, writes) and the clearance (every account read). */
    fun api(): Failure? =
        report(read(ELIGIBILITY, Failure.ELIGIBILITY_VIEW_UNREADABLE) ?: read(CLEARANCE, Failure.RESTORE_CLEARANCE_UNREADABLE))

    private fun read(
        sql: String,
        failure: Failure,
    ): Failure? = if (runCatching { jdbc.sql(sql).query().listOfRows() }.isSuccess) null else failure

    /** Logs on a change only, never per probe; no SQL text, role or exception message. */
    private fun report(now: Failure?): Failure? {
        val before = last.getAndSet(now)
        if (now != before) {
            if (now == null) {
                log.info("lifecycle_privileges deployable={} state=READABLE", deployable)
            } else {
                log.warn("lifecycle_privileges deployable={} state=NOT_READY reason={}", deployable, now)
            }
        }
        return now
    }

    private companion object {
        val log = LoggerFactory.getLogger(JdbcLifecyclePrivilegeProbe::class.java)
        const val ELIGIBILITY = "SELECT workspace_id, active FROM workspace_lifecycle_eligibility LIMIT 0"
        const val CLEARANCE = "SELECT id FROM lifecycle_restore_clearance LIMIT 0"
    }
}
