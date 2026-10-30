package email.testinbox.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import email.testinbox.application.usecase.ResolveHumanWorkspace
import email.testinbox.architecture.fixtures.RogueHumanOwnerFixture
import email.testinbox.domain.tenant.HumanWorkspaceOwner
import org.junit.jupiter.api.Test

class HumanOwnerRuleTest {
    @Test fun `only the live session membership resolver constructs human product authority`() {
        verify(ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("email.testinbox"))
    }

    @Test fun `an adapter that constructs authority from supplied IDs is rejected`() {
        val failure = runCatching { verify(ClassFileImporter().importClasses(RogueHumanOwnerFixture::class.java)) }.exceptionOrNull()
        check(failure is IllegalStateException && RogueHumanOwnerFixture::class.java.name in failure.message.orEmpty())
    }

    private fun verify(classes: JavaClasses) {
        val creators =
            classes
                .flatMap { it.methods + it.constructors }
                .filter { member ->
                    member.owner.name != HumanWorkspaceOwner::class.java.name &&
                        member.constructorCallsFromSelf.any { it.targetOwner.name == HumanWorkspaceOwner::class.java.name }
                }.map { it.owner.name }
                .toSet()
        check(creators == setOf(ResolveHumanWorkspace::class.java.name)) { "unverified human authority creators: $creators" }
    }
}
