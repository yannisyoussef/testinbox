package email.testinbox.application.usecase

import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryReservations
import email.testinbox.application.MutableClock
import email.testinbox.application.NoopTx
import email.testinbox.application.TestInboxConfig
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.InboxState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class CreateInboxTest {
    private val workspaceId = WorkspaceId(UUID.randomUUID())
    private val projectId = ProjectId(UUID.randomUUID())
    private lateinit var inboxes: InMemoryInboxRepository
    private lateinit var reservations: InMemoryReservations
    private lateinit var clock: MutableClock
    private lateinit var useCase: CreateInbox

    private val config =
        TestInboxConfig(
            mailDomain = "testinbox.local",
            defaultTtl = Duration.ofMinutes(15),
            maxTtl = Duration.ofHours(24),
            exactCooldown = Duration.ofHours(24),
        )

    @BeforeEach
    fun setUp() {
        inboxes = InMemoryInboxRepository()
        reservations = InMemoryReservations()
        clock = MutableClock(Instant.parse("2026-08-29T12:00:00Z"))
        useCase = CreateInbox(inboxes, reservations, NoopTx, clock, config)
    }

    private fun command(
        mode: AddressMode = AddressMode.GENERATED,
        ttlSeconds: Long? = null,
        aliasHint: String? = null,
        localPart: String? = null,
    ) = CreateInbox.Command(workspaceId, projectId, mode, ttlSeconds, aliasHint, localPart)

    @Test
    fun `generated mode creates an active inbox with default ttl and hinted address`() {
        val result = useCase.execute(command(aliasHint = "signup"))
        val inbox = result.shouldBeInstanceOf<CreateInbox.Result.Created>().inbox
        inbox.state shouldBe InboxState.ACTIVE
        inbox.addressMode shouldBe AddressMode.GENERATED
        inbox.address shouldStartWith "signup-"
        inbox.address shouldEndWith "@testinbox.local"
        inbox.expiresAt shouldBe clock.now.plus(Duration.ofMinutes(15))
        inboxes.inboxes.size shouldBe 1
    }

    @Test
    fun `generated mode retries transparently on the negligible token collision`() {
        inboxes.forcedAddressTakenCount = 2
        useCase.execute(command()).shouldBeInstanceOf<CreateInbox.Result.Created>()
    }

    @Test
    fun `ttl is validated against the configured cap`() {
        useCase.execute(command(ttlSeconds = 0)).shouldBeInstanceOf<CreateInbox.Result.InvalidRequest>()
        useCase
            .execute(command(ttlSeconds = Duration.ofHours(25).seconds))
            .shouldBeInstanceOf<CreateInbox.Result.InvalidRequest>()
        val created = useCase.execute(command(ttlSeconds = 600))
        created.shouldBeInstanceOf<CreateInbox.Result.Created>().inbox.expiresAt shouldBe
            clock.now.plusSeconds(600)
    }

    @Test
    fun `exact mode reserves the normalized local part`() {
        val result = useCase.execute(command(mode = AddressMode.EXACT, localPart = "QA.Signup"))
        val inbox = result.shouldBeInstanceOf<CreateInbox.Result.Created>().inbox
        inbox.address shouldBe "qa.signup@testinbox.local"
        inbox.addressMode shouldBe AddressMode.EXACT
        reservations.byLocalPart.containsKey("qa.signup") shouldBe true
    }

    @Test
    fun `exact mode rejects invalid, reserved and missing local parts`() {
        useCase
            .execute(command(mode = AddressMode.EXACT, localPart = null))
            .shouldBeInstanceOf<CreateInbox.Result.InvalidRequest>()
        useCase
            .execute(command(mode = AddressMode.EXACT, localPart = "two..dots"))
            .shouldBeInstanceOf<CreateInbox.Result.InvalidRequest>()
        useCase
            .execute(command(mode = AddressMode.EXACT, localPart = "postmaster"))
            .shouldBeInstanceOf<CreateInbox.Result.InvalidRequest>()
    }

    @Test
    fun `second exact reservation for the same local part conflicts`() {
        useCase
            .execute(command(mode = AddressMode.EXACT, localPart = "qa"))
            .shouldBeInstanceOf<CreateInbox.Result.Created>()
        val second = useCase.execute(command(mode = AddressMode.EXACT, localPart = "QA"))
        second.shouldBeInstanceOf<CreateInbox.Result.AddressConflict>().localPart shouldBe "qa"
    }

    @Test
    fun `mode-specific fields are rejected on the other mode`() {
        useCase
            .execute(command(mode = AddressMode.GENERATED, localPart = "x"))
            .shouldBeInstanceOf<CreateInbox.Result.InvalidRequest>()
        useCase
            .execute(command(mode = AddressMode.EXACT, localPart = "x", aliasHint = "y"))
            .shouldBeInstanceOf<CreateInbox.Result.InvalidRequest>()
    }
}
