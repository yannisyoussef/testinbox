package email.testinbox.persistence

import email.testinbox.application.port.HumanIdentities
import email.testinbox.application.usecase.ResolveVerifiedIdentity
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.LoginProvider
import email.testinbox.domain.identity.VerifiedLoginIdentity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Identity persistence assumes protocol proof; these are not OAuth signature/state tests. */
class HumanIdentitiesTest : EmailLoginTestSupport() {
    @Test
    fun `stable provider subject returns the original account without changing its contact email`() {
        val original = VerifiedLoginIdentity(LoginProvider.GITHUB, "https://github.com", "12345", email())
        val id = resolver().execute(original).shouldBeInstanceOf<ResolveVerifiedIdentity.Result.Resolved>().accountId
        val again = resolver().execute(original.copy(email = email())).shouldBeInstanceOf<ResolveVerifiedIdentity.Result.Resolved>()
        again.accountId shouldBe id
        count("human_account") shouldBe 1
        jdbc
            .sql("SELECT contact_email FROM human_account WHERE id=:id")
            .param("id", id.value)
            .query(String::class.java)
            .single() shouldBe original.email.value
    }

    @Test
    fun `another identity with the same contact cannot claim or link an account`() {
        val address = email()
        resolver().execute(VerifiedLoginIdentity.email(address)).shouldBeInstanceOf<ResolveVerifiedIdentity.Result.Resolved>()
        resolver().execute(VerifiedLoginIdentity(LoginProvider.GITHUB, "https://github.com", "67890", address)) shouldBe
            ResolveVerifiedIdentity.Result.DifferentMethod
        count("human_account") shouldBe 1
        count("human_identity") shouldBe 1
    }

    @Test
    fun `registration failure rolls back both account and identity`() {
        val broken =
            object : HumanIdentities by identities {
                override fun create(
                    id: HumanAccountId,
                    identity: VerifiedLoginIdentity,
                    at: Instant,
                ) {
                    identities.create(id, identity, at)
                    error("identity write failed")
                }
            }
        shouldThrow<IllegalStateException> {
            ResolveVerifiedIdentity(
                broken,
                registration,
                transactions,
                clock,
                SERVICE_DOMAINS,
            ).execute(VerifiedLoginIdentity.email(email()))
        }
        count("human_account") shouldBe 0
        count("human_identity") shouldBe 0
    }

    @Test
    fun `concurrent registrations for one immutable identity create one account`() {
        val identity = VerifiedLoginIdentity.email(email())
        val results = race(List(8) { identity }).map { it.shouldBeInstanceOf<ResolveVerifiedIdentity.Result.Resolved>().accountId }
        results.toSet().size shouldBe 1
        count("human_account") shouldBe 1
        count("human_identity") shouldBe 1
    }

    @Test
    fun `independent registrars cannot exceed the account inventory bound at its final place`() {
        seedAccounts(ResolveVerifiedIdentity.MAX_ACCOUNTS - 1)
        val results = race(List(8) { VerifiedLoginIdentity.email(email()) })
        results.count { it is ResolveVerifiedIdentity.Result.Resolved } shouldBe 1
        results.count { it == ResolveVerifiedIdentity.Result.CapacityUnavailable } shouldBe 7
        count("human_account") shouldBe 1030
        count("beta_admission") shouldBe 0
    }

    @Test
    fun `an existing identity can log in when the registration inventory is full`() {
        val original = VerifiedLoginIdentity.email(email())
        val id = resolver().execute(original).shouldBeInstanceOf<ResolveVerifiedIdentity.Result.Resolved>().accountId
        seedAccounts(ResolveVerifiedIdentity.MAX_ACCOUNTS - 1)
        resolver().execute(original).shouldBeInstanceOf<ResolveVerifiedIdentity.Result.Resolved>().accountId shouldBe id
        resolver().execute(VerifiedLoginIdentity.email(email())) shouldBe ResolveVerifiedIdentity.Result.CapacityUnavailable
        count("human_account") shouldBe 1030
    }

    @Test
    fun `authentication mutations require a real transaction`() {
        val identity = VerifiedLoginIdentity.email(email())
        shouldThrow<IllegalStateException> { registration.lock() }
        shouldThrow<IllegalStateException> { identities.create(HumanAccountId(java.util.UUID.randomUUID()), identity, clock.instant()) }
        shouldThrow<IllegalStateException> { store.purge(clock.instant()) }
        shouldThrow<IllegalStateException> { store.volume(identity.email, "a".repeat(64), clock.instant()) }
        shouldThrow<IllegalStateException> { store.replaceForBrowser("a".repeat(64)) }
        shouldThrow<IllegalStateException> { store.finishDelivery(java.util.UUID.randomUUID(), true) }
        shouldThrow<IllegalStateException> { store.lockChallenge(java.util.UUID.randomUUID()) }
        shouldThrow<IllegalStateException> { store.failedAttempt(java.util.UUID.randomUUID()) }
        shouldThrow<IllegalStateException> { store.consume(java.util.UUID.randomUUID()) }
    }

    private fun seedAccounts(total: Int) {
        jdbc
            .sql(
                """
                INSERT INTO human_account (id, contact_email, contact_verified_at, state, created_at)
                SELECT gen_random_uuid(), 'seed-' || n || '@example.invalid', now(), 'ACTIVE', now()
                FROM generate_series(1,:total) n
                """.trimIndent(),
            ).param("total", total)
            .update()
    }

    private fun race(identitiesToResolve: List<VerifiedLoginIdentity>): List<ResolveVerifiedIdentity.Result> {
        val ready = CountDownLatch(identitiesToResolve.size)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(identitiesToResolve.size)
        return try {
            val futures =
                identitiesToResolve.map { identity ->
                    executor.submit<ResolveVerifiedIdentity.Result> {
                        val independent =
                            ResolveVerifiedIdentity(
                                JdbcHumanIdentities(jdbc),
                                JdbcHumanAuthenticationLock(jdbc),
                                transactions,
                                clock,
                                SERVICE_DOMAINS,
                            )
                        ready.countDown()
                        check(go.await(10, TimeUnit.SECONDS))
                        independent.execute(identity)
                    }
                }
            check(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            go.countDown()
            executor.shutdownNow()
        }
    }
}
