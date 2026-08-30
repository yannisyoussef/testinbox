package email.testinbox.e2e

import email.testinbox.client.AddressMode
import email.testinbox.client.CreateInboxOptions
import email.testinbox.client.MessageMatcher
import email.testinbox.client.TestInboxClient
import email.testinbox.client.TestInboxConflictException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Black-box regression flows for the ADR-019/020/021 invariants. */
class InvariantFlowsTest {
    private val testInbox = TestInboxClient(apiKey = E2eStack.API_KEY, baseUrl = E2eStack.apiBaseUrl)

    @Test
    fun `exact reservation - concurrent second caller receives 409 (ADR-021)`() {
        val localPart = "e2e-race-${UUID.randomUUID().toString().take(8)}"
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts =
                (1..2).map {
                    executor.submit<Result<Any>> {
                        gate.await(5, TimeUnit.SECONDS)
                        runCatching {
                            testInbox.createInboxBlocking(
                                CreateInboxOptions(addressMode = AddressMode.EXACT, localPart = localPart),
                            )
                        }
                    }
                }
            gate.countDown()
            val outcomes = attempts.map { it.get(30, TimeUnit.SECONDS) }
            outcomes.count { it.isSuccess } shouldBe 1
            val failure = outcomes.single { it.isFailure }.exceptionOrNull()
            (failure is TestInboxConflictException) shouldBe true
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `identical MIME twice over SMTP - two observable messages (ADR-019)`() {
        val inbox = testInbox.createInboxBlocking()
        val raw = E2eStack.verificationEmail(inbox.address)
        E2eStack.sendRawSmtp("no-reply@example.com", inbox.address, raw)
        E2eStack.sendRawSmtp("no-reply@example.com", inbox.address, raw)
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            inbox.messagesBlocking().size shouldBe 2
        }
        val messages = inbox.messagesBlocking()
        messages[0].id shouldNotBe messages[1].id
        inbox.deleteBlocking()
    }

    @Test
    fun `waiter parked - LISTEN killed - matching email arrives - waiter still resolves (ADR-020)`() {
        val inbox = testInbox.createInboxBlocking()
        val waiterStarted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiter =
                executor.submit<email.testinbox.client.Message> {
                    waiterStarted.countDown()
                    inbox.awaitMessageBlocking(
                        Duration.ofSeconds(30),
                        MessageMatcher.builder().subjectContains("post-recovery").build(),
                    )
                }
            waiterStarted.await(5, TimeUnit.SECONDS)
            // Let the wait request reach its parked state, then sever LISTEN.
            Thread.sleep(700)
            E2eStack.killListenConnection()
            // Message arrives while (or just after) the LISTEN connection is down.
            E2eStack.sendRawSmtp(
                "no-reply@example.com",
                inbox.address,
                E2eStack.verificationEmail(inbox.address, subject = "post-recovery hello"),
            )
            // Reconnect re-query / degraded re-query must resolve the waiter (never stranded).
            val message = waiter.get(30, TimeUnit.SECONDS)
            message.subject shouldBe "post-recovery hello"
        } finally {
            executor.shutdownNow()
            inbox.deleteBlocking()
        }
    }
}
