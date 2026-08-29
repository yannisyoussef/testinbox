package email.testinbox.persistence

import email.testinbox.application.port.ReserveOutcome
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.ExactReservation
import email.testinbox.domain.inbox.ReservationStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-021: concurrent EXACT reservations are decided solely by the
 * database unique constraint — exactly one winner, losers get Conflict
 * (HTTP 409). Cooldown reclaim is transactional and guarded.
 */
class ExactReservationConcurrencyTest : PersistenceIntegrationTest() {
    @Autowired lateinit var reservations: JdbcExactAddressReservations

    @Autowired lateinit var tx: TransactionRunner

    @Autowired lateinit var jdbc: JdbcClient

    private fun reservation(
        localPart: String,
        workspaceId: WorkspaceId,
    ): ExactReservation =
        ExactReservation(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            localPart = localPart,
            inboxId = InboxId(UUID.randomUUID()),
            status = ReservationStatus.ACTIVE,
            reservedAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
            availableAt = null,
        )

    @Test
    fun `N simultaneous reservations for the same local part produce exactly one winner`() {
        val (workspaceId, _) = Fixtures.provisionTenant(jdbc)
        val localPart = "race-${UUID.randomUUID().toString().take(8)}"
        val threads = 10
        val startGate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (1..threads).map {
                    executor.submit<ReserveOutcome> {
                        startGate.await(5, TimeUnit.SECONDS)
                        tx.required { reservations.reserve(reservation(localPart, workspaceId), Instant.now()) }
                    }
                }
            startGate.countDown()
            val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }
            outcomes.count { it is ReserveOutcome.Reserved } shouldBe 1
            outcomes.count { it is ReserveOutcome.Conflict } shouldBe threads - 1
        } finally {
            executor.shutdownNow()
        }
        val rows =
            jdbc
                .sql(
                    "SELECT count(*) AS c FROM exact_address_reservation WHERE local_part = :lp AND status = 'ACTIVE'",
                ).param("lp", localPart)
                .query { rs, _ -> rs.getLong("c") }
                .single()
        rows shouldBe 1L
    }

    @Test
    fun `loser blocked behind an uncommitted winner resolves to Conflict after commit`() {
        val (workspaceId, _) = Fixtures.provisionTenant(jdbc)
        val localPart = "hold-${UUID.randomUUID().toString().take(8)}"
        val winnerInserted = CountDownLatch(1)
        val releaseWinner = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val winner =
                executor.submit<ReserveOutcome> {
                    tx.required {
                        val outcome = reservations.reserve(reservation(localPart, workspaceId), Instant.now())
                        winnerInserted.countDown()
                        // Hold the transaction open so the loser blocks on the index entry.
                        releaseWinner.await(10, TimeUnit.SECONDS)
                        outcome
                    }
                }
            winnerInserted.await(10, TimeUnit.SECONDS) shouldBe true
            val loser =
                executor.submit<ReserveOutcome> {
                    tx.required { reservations.reserve(reservation(localPart, workspaceId), Instant.now()) }
                }
            // Give the loser time to reach the blocking insert, then commit the winner.
            Thread.sleep(300)
            releaseWinner.countDown()
            winner.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<ReserveOutcome.Reserved>()
            loser.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<ReserveOutcome.Conflict>()
        } finally {
            releaseWinner.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cooldown blocks re-reservation with availableAt, then transactional reclaim wins`() {
        val (workspaceId, _) = Fixtures.provisionTenant(jdbc)
        val localPart = "cool-${UUID.randomUUID().toString().take(8)}"
        val first = reservation(localPart, workspaceId)
        tx.required { reservations.reserve(first, Instant.now()) } shouldBe ReserveOutcome.Reserved

        val availableAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS)
        reservations.startCooldown(first.inboxId, availableAt)

        val duringCooldown =
            tx.required { reservations.reserve(reservation(localPart, workspaceId), Instant.now()) }
        duringCooldown.shouldBeInstanceOf<ReserveOutcome.Conflict>().availableAt shouldBe availableAt

        // Simulate cooldown elapse by evaluating "now" past availableAt (time is
        // an input, never part of the index predicate).
        val afterCooldown =
            tx.required {
                reservations.reserve(reservation(localPart, workspaceId), availableAt.plusSeconds(1))
            }
        afterCooldown shouldBe ReserveOutcome.Reserved
        val released =
            jdbc
                .sql(
                    "SELECT count(*) AS c FROM exact_address_reservation WHERE local_part = :lp AND status = 'RELEASED'",
                ).param("lp", localPart)
                .query { rs, _ -> rs.getLong("c") }
                .single()
        released shouldBe 1L
    }
}
