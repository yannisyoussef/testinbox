package email.testinbox.application.usecase

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.ExactAddressReservations
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.InsertInboxOutcome
import email.testinbox.application.port.LimitMetrics
import email.testinbox.application.port.ReserveOutcome
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.port.WorkspaceQuotaState
import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.ExactReservation
import email.testinbox.domain.inbox.GeneratedAddress
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.inbox.LocalPartPolicy
import email.testinbox.domain.inbox.ReservationStatus
import email.testinbox.domain.limits.QuotaDimension
import email.testinbox.domain.limits.QuotaExceeded
import email.testinbox.domain.limits.QuotaPolicy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Creates an inbox in GENERATED or EXACT address mode (ADR-021).
 * EXACT-mode concurrency is decided solely by the Postgres unique
 * constraint on the reservation table; the loser observes Conflict.
 */
class CreateInbox(
    private val inboxes: InboxRepository,
    private val reservations: ExactAddressReservations,
    private val tx: TransactionRunner,
    private val quotas: WorkspaceQuotaState,
    private val policy: QuotaPolicy,
    private val clock: Clock,
    private val config: TestInboxConfig,
    private val metrics: LimitMetrics = LimitMetrics.NOOP,
) {
    data class Command(
        val workspaceId: WorkspaceId,
        val projectId: ProjectId,
        val addressMode: AddressMode,
        val ttlSeconds: Long?,
        val aliasHint: String? = null,
        val localPart: String? = null,
    )

    sealed interface Result {
        data class Created(
            val inbox: Inbox,
        ) : Result

        /** EXACT local-part already reserved or in cooldown — maps to 409 (ADR-021). */
        data class AddressConflict(
            val localPart: String,
            val availableAt: Instant?,
        ) : Result

        /**
         * A workspace allowance is exhausted — maps to 409, not 429 (ADR-027
         * §8): waiting does not help, the caller must free capacity.
         */
        data class QuotaRejected(
            val exceeded: QuotaExceeded,
        ) : Result

        data class InvalidRequest(
            val reason: String,
        ) : Result
    }

    fun execute(command: Command): Result {
        val now = clock.instant()
        val ttl =
            when {
                command.ttlSeconds == null -> {
                    config.defaultTtl
                }

                command.ttlSeconds <= 0 -> {
                    return Result.InvalidRequest("ttlSeconds must be positive")
                }

                command.ttlSeconds > config.maxTtl.seconds -> {
                    return Result.InvalidRequest("ttlSeconds exceeds the maximum of ${config.maxTtl.seconds}")
                }

                else -> {
                    Duration.ofSeconds(command.ttlSeconds)
                }
            }
        // One transaction for the whole admission decision, with the workspace
        // guard as its first statement (ADR-027 §6): deriving a count and then
        // inserting is check-then-act, so without it N concurrent creates all
        // observe capacity for one. The guard must precede any inbox write, or
        // it closes a lock-order cycle with the retention sweep's DELETE.
        return tx.required {
            quotas.guardAdmission(command.workspaceId)
            admit(command.workspaceId)?.let {
                metrics.quotaRejected(it.dimension)
                return@required Result.QuotaRejected(it)
            }
            when (command.addressMode) {
                AddressMode.GENERATED -> createGenerated(command, now, ttl)
                AddressMode.EXACT -> createExact(command, now, ttl)
            }
        }
    }

    /** Null when the workspace may take one more inbox; otherwise why not. */
    private fun admit(workspaceId: WorkspaceId): QuotaExceeded? {
        val activeInboxes = quotas.activeInboxCount(workspaceId)
        if (!policy.admits(QuotaDimension.ACTIVE_INBOXES, activeInboxes)) {
            return QuotaExceeded(QuotaDimension.ACTIVE_INBOXES, policy.maxActiveInboxes, activeInboxes)
        }
        // A workspace at its storage ceiling may keep receiving mail for the
        // inboxes it already holds (ADR-027 §2), but may not enlarge its
        // footprint by adding more.
        val stored = quotas.storedBytes(workspaceId)
        if (!policy.admits(QuotaDimension.STORED_BYTES, stored, amount = 0)) {
            return QuotaExceeded(QuotaDimension.STORED_BYTES, policy.maxStoredBytes, stored)
        }
        return null
    }

    private fun createGenerated(
        command: Command,
        now: Instant,
        ttl: Duration,
    ): Result {
        if (command.localPart != null) {
            return Result.InvalidRequest("localPart is only valid with addressMode EXACT")
        }
        repeat(MAX_TOKEN_RETRIES) {
            val inbox =
                newInbox(command, now, ttl, AddressMode.GENERATED, GeneratedAddress.localPart(command.aliasHint))
            when (inboxes.insert(inbox)) {
                InsertInboxOutcome.Inserted -> {
                    return Result.Created(inbox)
                }

                // Statistically negligible token collision: regenerate and retry (ADR-021).
                InsertInboxOutcome.AddressTaken -> {}
            }
        }
        error("Generated-address collision persisted across $MAX_TOKEN_RETRIES retries")
    }

    private fun createExact(
        command: Command,
        now: Instant,
        ttl: Duration,
    ): Result {
        if (command.aliasHint != null) {
            return Result.InvalidRequest("aliasHint is only valid with addressMode GENERATED")
        }
        val requested =
            command.localPart ?: return Result.InvalidRequest("localPart is required with addressMode EXACT")
        val localPart =
            when (val validation = LocalPartPolicy.validate(requested)) {
                is LocalPartPolicy.Result.Invalid -> {
                    return Result.InvalidRequest(validation.reason)
                }

                is LocalPartPolicy.Result.Denied -> {
                    return Result.InvalidRequest("localPart '${validation.localPart}' is reserved")
                }

                is LocalPartPolicy.Result.Valid -> {
                    validation.normalized
                }
            }
        val inbox = newInbox(command, now, ttl, AddressMode.EXACT, localPart)
        return tx.required {
            val reservation =
                ExactReservation(
                    id = UUID.randomUUID(),
                    workspaceId = command.workspaceId,
                    localPart = localPart,
                    inboxId = inbox.id,
                    status = ReservationStatus.ACTIVE,
                    reservedAt = now,
                    availableAt = null,
                )
            when (val outcome = reservations.reserve(reservation, now)) {
                is ReserveOutcome.Conflict -> {
                    Result.AddressConflict(localPart, outcome.availableAt)
                }

                ReserveOutcome.Reserved -> {
                    when (inboxes.insert(inbox)) {
                        InsertInboxOutcome.Inserted -> Result.Created(inbox)
                        InsertInboxOutcome.AddressTaken -> Result.AddressConflict(localPart, null)
                    }
                }
            }
        }
    }

    private fun newInbox(
        command: Command,
        now: Instant,
        ttl: Duration,
        mode: AddressMode,
        localPart: String,
    ): Inbox =
        Inbox(
            id = InboxId(UUID.randomUUID()),
            workspaceId = command.workspaceId,
            projectId = command.projectId,
            address = "$localPart@${config.mailDomain}",
            addressMode = mode,
            state = InboxState.ACTIVE,
            createdAt = now,
            expiresAt = now.plus(ttl),
        )

    private companion object {
        const val MAX_TOKEN_RETRIES = 3
    }
}
