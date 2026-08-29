package email.testinbox.application.usecase

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.ExactAddressReservations
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.InsertInboxOutcome
import email.testinbox.application.port.ReserveOutcome
import email.testinbox.application.port.TransactionRunner
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
    private val clock: Clock,
    private val config: TestInboxConfig,
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
        return when (command.addressMode) {
            AddressMode.GENERATED -> createGenerated(command, now, ttl)
            AddressMode.EXACT -> createExact(command, now, ttl)
        }
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
