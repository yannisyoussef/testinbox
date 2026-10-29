package email.testinbox.persistence

import email.testinbox.application.identity.EmailLoginCrypto
import email.testinbox.application.port.EmailLoginDelivery
import email.testinbox.application.port.EmailLoginEvent
import email.testinbox.application.port.EmailLoginObservations
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.application.usecase.RequestEmailLogin
import email.testinbox.application.usecase.ResolveVerifiedIdentity
import email.testinbox.application.usecase.VerifyEmailLogin
import email.testinbox.domain.identity.ContactEmail
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

abstract class EmailLoginTestSupport : PersistenceIntegrationTest() {
    @Autowired protected lateinit var jdbc: JdbcClient

    @Autowired protected lateinit var store: JdbcEmailLogins

    @Autowired protected lateinit var identities: JdbcHumanIdentities

    @Autowired protected lateinit var registration: JdbcHumanAuthenticationLock

    @Autowired protected lateinit var sessions: JdbcHumanSessions

    @Autowired protected lateinit var transactions: TransactionRunner

    protected val clock = EmailLoginClock()
    protected val crypto = EmailLoginCrypto(ByteArray(32) { 7 })
    protected val sent = CopyOnWriteArrayList<Pair<ContactEmail, String>>()
    protected val events = CopyOnWriteArrayList<EmailLoginEvent>()
    protected val observations = EmailLoginObservations(events::add)
    protected val delivery = EmailLoginDelivery { address, code -> sent.add(address to code.value) }

    @BeforeEach
    fun cleanAuth() {
        jdbc.sql("DELETE FROM participant_workspace").update()
        jdbc.sql("DELETE FROM beta_admission").update()
        jdbc.sql("DELETE FROM human_account").update()
        jdbc.sql("DELETE FROM email_login_challenge").update()
        jdbc.sql("DELETE FROM human_auth_gate").update()
    }

    protected fun request(deliver: EmailLoginDelivery = delivery): RequestEmailLogin =
        RequestEmailLogin(JdbcEmailLogins(jdbc), JdbcHumanAuthenticationLock(jdbc), transactions, crypto, deliver, clock, observations)

    protected fun resolver(): ResolveVerifiedIdentity = ResolveVerifiedIdentity(identities, registration, transactions, clock)

    protected fun verifier(sessionStore: HumanSessions = JdbcHumanSessions(jdbc)): VerifyEmailLogin =
        VerifyEmailLogin(
            JdbcEmailLogins(jdbc),
            JdbcHumanAuthenticationLock(jdbc),
            transactions,
            crypto,
            resolver(),
            IssueHumanSession(sessionStore, transactions, clock),
            clock,
            observations,
        )

    protected fun email(value: String = "${UUID.randomUUID()}@example.invalid"): ContactEmail = checkNotNull(ContactEmail.parse(value))

    protected fun start(
        address: ContactEmail = email(),
        browser: String? = null,
    ): Started {
        val accepted = request().execute(address, "198.51.100.1", browser).shouldBeInstanceOf<RequestEmailLogin.Result.Accepted>()
        return Started(accepted, sent.last { it.first == address }.second, address)
    }

    protected fun finish(start: Started): VerifyEmailLogin.Result =
        verifier().execute(start.accepted.id, start.accepted.browser.value, start.code)

    protected fun count(table: String): Long = jdbc.sql("SELECT count(*) FROM $table").query(Long::class.javaObjectType).single()

    protected data class Started(
        val accepted: RequestEmailLogin.Result.Accepted,
        val code: String,
        val email: ContactEmail,
    ) {
        override fun toString(): String = "Started([REDACTED])"
    }
}

class EmailLoginClock : Clock() {
    private var current = Instant.parse("2026-10-29T12:00:00Z")

    fun advance(seconds: Long) {
        current = current.plusSeconds(seconds)
    }

    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
