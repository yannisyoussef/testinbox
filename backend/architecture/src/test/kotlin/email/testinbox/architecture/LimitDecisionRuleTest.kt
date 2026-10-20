package email.testinbox.architecture

import com.tngtech.archunit.base.DescribedPredicate.describe
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import email.testinbox.application.port.RateLimiter
import email.testinbox.application.port.WaitSlots
import email.testinbox.application.port.WorkspaceQuotaState
import email.testinbox.architecture.fixtures.RogueQueryFixture
import org.junit.jupiter.api.Test

/**
 * ADR-027 §3 / ADR-024: a limit is *decided* by a use case. A query service
 * in `application.query` may read a limiter (`peek`, `held`) for a usage
 * report; the members that spend, claim or refuse are not its to call.
 * Without this rule the KDoc "a usage read, never a decision" is the only
 * guard. Deliberately scoped to `application.query`: the api adapter's
 * `RateLimitInterceptor` calls `tryConsume` by design (it maps a route to a
 * category and charges it), so widening this rule to `api..` is not a
 * one-line change.
 */
class LimitDecisionRuleTest {
    private val allClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("email.testinbox")

    @Test
    fun `a limit is decided by a use case, never by a query service (ADR-024, ADR-027 §3)`() {
        limitDecisionRule("email.testinbox.application.query..").check(allClasses)
    }

    @Test
    fun `the rule catches a query service that claims a slot, so it can fail`() {
        val violating = ClassFileImporter().importClasses(RogueQueryFixture::class.java)
        val failure = runCatching { limitDecisionRule("email.testinbox.architecture.fixtures..").check(violating) }.exceptionOrNull()
        check(failure is AssertionError && "acquire" in failure.message.orEmpty()) {
            "the rule did not catch a query service acquiring a slot: $failure"
        }
    }

    private fun limitDecisionRule(queryPackage: String) =
        noClasses()
            .that()
            .resideInAPackage(queryPackage)
            .should()
            .callMethodWhere(
                describe("decides a limit") { call: JavaMethodCall ->
                    // Kotlin mangles a value-class parameter into the JVM name (`acquire-XYZ`), hence the prefix match.
                    val deciding =
                        mapOf(
                            RateLimiter::class.java.name to "tryConsume",
                            WaitSlots::class.java.name to "acquire",
                            WorkspaceQuotaState::class.java.name to "guardAdmission",
                        )
                    deciding[call.targetOwner.name]?.let { call.name == it || call.name.startsWith("$it-") } ?: false
                },
            ).because("limit decisions are use-case concerns; a query service only reads them")
}
