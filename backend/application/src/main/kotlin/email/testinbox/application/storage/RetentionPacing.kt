package email.testinbox.application.storage

import email.testinbox.application.port.StorageLedger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Retention pacing (filesystem-containment contract §5.4; TI-STORAGE-006E PR D):
 * whether the next teardown batch may run. Liveness only — a teardown is the
 * Φ-neutral transfer L → D, and the theorem holds with or without pacing.
 */
fun interface RetentionPacing {
    /** [waitingSince]: since when the inbox has waited for teardown, or null when unknown. */
    fun mayTearDown(waitingSince: Instant?): Boolean

    companion object {
        /** Today's behaviour, and the only one under `OFF` and `TENANT_LIMITS`: never paced. */
        val UNPACED = RetentionPacing { true }
    }
}

/**
 * The `ALL` pacing: the next batch runs only while
 * `D_est = W + F(D) ≤ D_budget`, so a mass expiry does not push rule (C) below
 * the policy ceiling. Bounded by [maxDelay] (`T_max`): an inbox that has waited
 * longer is torn down anyway, and logged as the alert the runbook escalates, so
 * a tenant's deleted content never stays on disk indefinitely because
 * observations stopped. A ledger that cannot be read pauses (the next sweep
 * retries; `T_max` still bounds it).
 */
class DebtPacing(
    private val ledger: StorageLedger,
    private val footprint: FootprintPolicy,
    private val deletionDebtBudgetBytes: Long,
    private val maxDelay: Duration,
    private val clock: Clock,
) : RetentionPacing {
    init {
        require(deletionDebtBudgetBytes > 0) { "D_budget must be positive" }
        require(!maxDelay.isNegative && !maxDelay.isZero) { "T_max must be positive" }
    }

    override fun mayTearDown(waitingSince: Instant?): Boolean {
        if (waitingSince != null && Duration.between(waitingSince, clock.instant()) > maxDelay) {
            log.error("storage_retention_t_max_exceeded an inbox waited longer than T_max for teardown; torn down regardless of D_est")
            return true
        }
        val estimate =
            runCatching {
                val debt = ledger.deletionDebt()
                Math.addExact(
                    debt.observation?.trashBytes ?: 0,
                    footprint.model.bound(debt.unsupersededBytes, debt.unsupersededObjects),
                )
            }.getOrElse {
                log.warn("storage_retention_paused the deletion debt could not be read; teardown waits for the next sweep")
                return false
            }
        return estimate <= deletionDebtBudgetBytes
    }

    private companion object {
        val log = LoggerFactory.getLogger(DebtPacing::class.java)
    }
}
