package email.testinbox.domain.limits

import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class TokenBucketTest {
    private val start = Instant.parse("2026-08-30T12:00:00Z")
    private val policy = RatePolicy(capacity = 3, refillPerSecond = 1.0)

    private fun spendTimes(
        count: Int,
        state: TokenBucket.State,
        at: Instant,
    ): List<TokenBucket.Decision> {
        var current = state
        return (1..count).map {
            val decision = TokenBucket.spend(current, policy, at)
            current = decision.state
            decision
        }
    }

    @Test
    fun `a fresh bucket allows exactly its capacity in a burst, then refuses`() {
        val decisions = spendTimes(4, TokenBucket.initial(policy, start), start)
        decisions.take(3).forEach { it.allowed shouldBe true }
        decisions[3].allowed shouldBe false
        // The boundary is exact: N allowed, N+1 refused.
        decisions.count { it.allowed } shouldBe 3
    }

    @Test
    fun `remaining counts down and never goes negative`() {
        val decisions = spendTimes(5, TokenBucket.initial(policy, start), start)
        decisions.map { it.remaining } shouldBe listOf(2L, 1L, 0L, 0L, 0L)
    }

    @Test
    fun `a refused request reports the exact wait for one token`() {
        var state = TokenBucket.initial(policy, start)
        repeat(3) { state = TokenBucket.spend(state, policy, start).state }
        val refused = TokenBucket.spend(state, policy, start)
        refused.allowed shouldBe false
        // 1 token/s and an empty bucket: one second.
        refused.retryAfter shouldBe Duration.ofSeconds(1)
    }

    @Test
    fun `capacity is restored by elapsed time and a request then succeeds`() {
        var state = TokenBucket.initial(policy, start)
        repeat(3) { state = TokenBucket.spend(state, policy, start).state }
        TokenBucket.spend(state, policy, start).allowed shouldBe false
        // Deterministic: advance the clock instead of sleeping.
        val afterOneSecond = TokenBucket.spend(state, policy, start.plusSeconds(1))
        afterOneSecond.allowed shouldBe true
    }

    @Test
    fun `an idle bucket refills to capacity but never beyond it`() {
        val state = TokenBucket.State(tokens = 0.0, updatedAt = start)
        val afterAnHour = TokenBucket.spend(state, policy, start.plus(Duration.ofHours(1)))
        afterAnHour.allowed shouldBe true
        // Capacity 3, one spent — an idle century cannot bank an unbounded burst.
        afterAnHour.remaining shouldBe 2L
    }

    @Test
    fun `a clock moving backwards never fabricates tokens`() {
        var state = TokenBucket.initial(policy, start)
        repeat(3) { state = TokenBucket.spend(state, policy, start).state }
        val rewound = TokenBucket.spend(state, policy, start.minusSeconds(3600))
        rewound.allowed shouldBe false
        rewound.remaining shouldBe 0L
    }

    @Test
    fun `fractional refill rates below one per second are honoured exactly`() {
        val slow = RatePolicy(capacity = 1, refillPerSecond = 0.5)
        var state = TokenBucket.initial(slow, start)
        state = TokenBucket.spend(state, slow, start).state
        TokenBucket.spend(state, slow, start.plusSeconds(1)).allowed shouldBe false
        TokenBucket.spend(state, slow, start.plusSeconds(2)).allowed shouldBe true
        slow.timeToOneToken shouldBe Duration.ofSeconds(2)
    }

    @Test
    fun `policy rejects non-positive or non-finite configuration`() {
        listOf(
            { RatePolicy(capacity = 0, refillPerSecond = 1.0) },
            { RatePolicy(capacity = -1, refillPerSecond = 1.0) },
            { RatePolicy(capacity = 1, refillPerSecond = 0.0) },
            { RatePolicy(capacity = 1, refillPerSecond = -1.0) },
            { RatePolicy(capacity = 1, refillPerSecond = Double.POSITIVE_INFINITY) },
        ).forEach { build ->
            runCatching { build() }.isFailure shouldBe true
        }
    }

    @Test
    fun `property - tokens stay within bounds and remaining never exceeds capacity`() {
        runBlocking {
            checkAll(
                Arb.long(0L..100_000L),
                Arb.double(0.0..10.0),
            ) { elapsedMillis, startingTokens ->
                val bounded = RatePolicy(capacity = 5, refillPerSecond = 2.0)
                val state = TokenBucket.State(startingTokens.coerceIn(0.0, 5.0), start)
                val decision = TokenBucket.spend(state, bounded, start.plusMillis(elapsedMillis))
                decision.state.tokens shouldBeGreaterThanOrEqual 0.0
                (decision.state.tokens <= bounded.capacity.toDouble()) shouldBe true
                decision.remaining shouldBeGreaterThanOrEqual 0L
                (decision.remaining <= bounded.capacity) shouldBe true
                if (!decision.allowed) {
                    // A refusal must always carry a positive, finite retry hint.
                    (decision.retryAfter!!.toNanos() > 0) shouldBe true
                }
            }
        }
    }

    @Test
    fun `a corrupted non-finite token value recovers instead of refusing forever`() {
        // Fail-closed is right, fail-stuck is not: without sanitising, NaN >= 1.0 is
        // false for every future request and the bucket never recovers.
        val corrupted = TokenBucket.State(tokens = Double.NaN, updatedAt = start)
        TokenBucket.spend(corrupted, policy, start).allowed shouldBe false
        val afterRefill = TokenBucket.spend(corrupted, policy, start.plusSeconds(2))
        afterRefill.allowed shouldBe true
        afterRefill.state.tokens.isFinite() shouldBe true
    }

    @Test
    fun `property - arithmetic cannot overflow for extreme but valid elapsed time`() {
        val huge = RatePolicy(capacity = Long.MAX_VALUE / 2, refillPerSecond = 1e9)
        val state = TokenBucket.State(0.0, start)
        val decision = TokenBucket.spend(state, huge, start.plus(Duration.ofDays(365 * 100L)))
        decision.allowed shouldBe true
        (decision.remaining <= huge.capacity) shouldBe true
        (decision.state.tokens.isFinite()) shouldBe true
    }
}
