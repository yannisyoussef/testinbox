package email.testinbox.api.account

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class AccountClientSourceTest {
    private val secret = "fixture-only-not-a-real-secret-9876543210"
    private val registry = SimpleMeterRegistry()
    private val source = AccountClientSource(secret, registry)

    private fun request(
        client: List<String> = emptyList(),
        presented: List<String> = emptyList(),
        peer: String = "172.18.0.5",
    ) = MockHttpServletRequest().apply {
        remoteAddr = peer
        client.forEach { addHeader(AccountClientSource.CLIENT_HEADER, it) }
        presented.forEach { addHeader(AccountClientSource.SECRET_HEADER, it) }
        addHeader("X-Forwarded-For", "203.0.113.250")
        addHeader("Forwarded", "for=203.0.113.251")
    }

    private fun count(outcome: String) =
        registry
            .get(AccountClientSource.METRIC)
            .tag("outcome", outcome)
            .counter()
            .count()

    @Test
    fun `only the authenticated proxy names a client and forwarded headers are never read`() {
        source.resolve(request(listOf("198.51.100.7"), listOf(secret))) shouldBe "client:198.51.100.7"
        source.resolve(request()) shouldBe "peer:172.18.0.5"
        source.resolve(request(listOf("198.51.100.7"))) shouldBe "peer:172.18.0.5"
        source.resolve(request(listOf("198.51.100.7"), listOf(secret + "x"))) shouldBe "peer:172.18.0.5"
        source.resolve(request(listOf("198.51.100.7"), listOf(secret.dropLast(1)))) shouldBe "peer:172.18.0.5"
        source.resolve(request(listOf("198.51.100.7"), listOf(secret, secret))) shouldBe "peer:172.18.0.5"
        source.resolve(request(listOf("198.51.100.7"), listOf(""))) shouldBe "peer:172.18.0.5"
        source.resolve(request(listOf("198.51.100.7", "198.51.100.8"), listOf(secret))) shouldBe "peer:172.18.0.5"
        source.resolve(request(listOf("not an address"), listOf(secret))) shouldBe "peer:172.18.0.5"
        count("trusted") shouldBe 1.0
        count("absent") shouldBe 1.0
        count("unauthenticated") shouldBe 5.0
        count("malformed") shouldBe 2.0
        count("not_configured") shouldBe 0.0
    }

    @Test
    fun `without a configured secret a claimed client is ignored and counted`() {
        val own = SimpleMeterRegistry()
        val unconfigured = AccountClientSource("", own)
        unconfigured.resolve(request(listOf("198.51.100.7"), listOf(secret))) shouldBe "peer:172.18.0.5"
        own
            .get(AccountClientSource.METRIC)
            .tag("outcome", "not_configured")
            .counter()
            .count() shouldBe 1.0
        own.get("testinbox_account_client_address_configured").gauge().value() shouldBe 0.0
        registry.get("testinbox_account_client_address_configured").gauge().value() shouldBe 1.0
    }

    @Test
    fun `IPv4 is kept exactly and IPv6 is grouped by its 64-bit prefix`() {
        AccountClientSource.normalize("198.51.100.7") shouldBe "198.51.100.7"
        AccountClientSource.normalize("0.0.0.0") shouldBe "0.0.0.0"
        AccountClientSource.normalize("::ffff:198.51.100.7") shouldBe "198.51.100.7"
        val a = AccountClientSource.normalize("2001:db8:1:2::1")
        a shouldBe "2001:db8:1:2:0:0:0:0/64"
        AccountClientSource.normalize("2001:DB8:1:2:ffff:ffff:ffff:ffff") shouldBe a
        AccountClientSource.normalize("2001:db8:1:3::1") shouldBe "2001:db8:1:3:0:0:0:0/64"
        AccountClientSource.normalize("::1") shouldBe "0:0:0:0:0:0:0:0/64"
    }

    @Test
    fun `hostnames lists ports zones brackets and legacy IPv4 forms are malformed`() {
        listOf(
            "",
            "localhost",
            "example.invalid",
            "198.51.100.7, 203.0.113.1",
            "198.51.100.7:443",
            "[2001:db8::1]",
            "[2001:db8::1]:443",
            "fe80::1%eth0",
            "fe80::1%1",
            "1",
            "1.2.3",
            "010.1.1.1",
            "256.1.1.1",
            "1.2.3.4.5",
            " 198.51.100.7",
            "198.51.100.7 ",
            "2001:db8::g",
            "2001:db8:::1",
            ":::",
            "0x7f.0.0.1",
            "1".repeat(46),
            "for=198.51.100.7",
        ).forEach { AccountClientSource.normalize(it) shouldBe null }
    }

    @Test
    fun `a configured secret must be long printable and not trivially weak and is never echoed`() {
        listOf(
            "short-secret",
            "a".repeat(64),
            "abababababababababababababababab",
            "fixture-only-not-a-real secret-9876543210",
            "fixture-only-not-a-real-secret-9876543210\n",
            "fixture-only-not-a-real-secret-987654321é",
            "k".repeat(300) + "7Qx2Lp9Vw4Zr8Tn3Hs6Jd1Fg5Mb0Yc".repeat(10),
        ).forEach { weak ->
            val failure = shouldThrow<IllegalStateException> { AccountClientSource(weak, SimpleMeterRegistry()) }
            failure.message!! shouldNotContain weak.trim()
        }
        AccountClientSource("0123456789abcdef".repeat(4), SimpleMeterRegistry()) // openssl rand -hex 32 shape
        HumanAccountProperties(clientAddressSecret = secret).toString() shouldNotContain secret
    }
}
