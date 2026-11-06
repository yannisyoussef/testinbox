package email.testinbox.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import email.testinbox.application.port.RateLimiter
import email.testinbox.application.storage.GuardedOutcome
import email.testinbox.application.storage.GuardedStorage
import email.testinbox.application.usecase.ReceiveInboundDelivery
import email.testinbox.architecture.fixtures.RogueRefundFixture
import org.junit.jupiter.api.Test

/**
 * The owner's authorization of 2026-11-06 (#124 comment 6678047827) covers
 * refunds for `451`s proven to occur before T1 starts, and nothing broader.
 * These rules keep the only path to a refund where that proof is made:
 *
 * - `RateLimiter.refund` is called by exactly one member,
 *   `ReceiveInboundDelivery.refundBeforeT1`; no adapter, query, API path or
 *   other use case can give a token back;
 * - the pre-T1 verdict, `GuardedOutcome.RefusedBeforeT1`, is constructed only
 *   by `GuardedStorage.attempt`, which sets its boundary immediately before
 *   T1 is invoked. Everything thrown from T1 on is rethrown, never wrapped.
 *
 * Behaviourally, `IngestRefundTest` (application) and
 * `IngestRefundIntegrationTest` (ingestion) drive every post-T1 failure and
 * prove none of them reaches a refund.
 */
class IngestRefundRuleTest {
    private val allClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("email.testinbox")

    /** "Owner.member" of every member that calls [owner].[method]; a value-class name mangling (`-XYZ`) is dropped. */
    private fun callersOf(
        classes: JavaClasses,
        owner: Class<*>,
        method: String,
    ): Set<String> =
        classes
            .flatMap { it.methods + it.constructors }
            .filter { member -> member.callsFromSelf.any { it.targetOwner.name == owner.name && it.name == method } }
            .map { "${it.owner.name}.${it.name.substringBefore('-')}" }
            .toSet()

    @Test
    fun `only the inbound use case gives INGEST tokens back, from its one pre-T1 path`() {
        val callers = callersOf(allClasses, RateLimiter::class.java, "refund")
        // Guard the guard: a rule that resolves no caller proves nothing.
        check(callers.isNotEmpty()) { "no caller of RateLimiter.refund was found: this rule checks nothing" }
        check(callers == setOf("${ReceiveInboundDelivery::class.java.name}.refundBeforeT1")) {
            "$callers give INGEST tokens back. Only ReceiveInboundDelivery.refundBeforeT1 may: the owner authorized " +
                "refunds for 451s proven before T1 only (2026-11-06), and that proof is made in one place."
        }
    }

    @Test
    fun `only the guarded protocol decides that an event was refused before T1`() {
        val constructors =
            allClasses
                .flatMap { it.methods + it.constructors }
                .filter { member ->
                    member.constructorCallsFromSelf.any { it.targetOwner.name == GuardedOutcome.RefusedBeforeT1::class.java.name }
                }.map { "${it.owner.name}.${it.name}" }
                .toSet()
        check(constructors == setOf("${GuardedStorage::class.java.name}.attempt")) {
            "$constructors construct RefusedBeforeT1. Only GuardedStorage.attempt may: it alone knows whether T1 was invoked."
        }
    }

    @Test
    fun `the refund rule catches a rogue refund, so it can fail`() {
        val rogue = callersOf(ClassFileImporter().importClasses(RogueRefundFixture::class.java), RateLimiter::class.java, "refund")
        check(rogue == setOf("${RogueRefundFixture::class.java.name}.afterAnyFailure")) {
            "the rule did not see the fixture's refund call: $rogue"
        }
    }
}
