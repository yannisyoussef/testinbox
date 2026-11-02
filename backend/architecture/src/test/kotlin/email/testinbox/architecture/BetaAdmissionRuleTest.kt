package email.testinbox.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import email.testinbox.application.port.BetaAdmissionControls
import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.usecase.AdmitBetaParticipant
import email.testinbox.application.usecase.ControlBetaAdmissions
import email.testinbox.architecture.fixtures.RogueParticipantFixture
import org.junit.jupiter.api.Test

/** Participant provisioning cannot bypass the application cohort transaction. */
class BetaAdmissionRuleTest {
    @Test
    fun `only the admission use case provisions participant workspaces (ADR-036)`() {
        val production =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("email.testinbox")
        assertParticipantProvisioning(production)
    }

    @Test
    fun `the participant rule catches a caller of the concrete adapter as well as the port`() {
        val violating = ClassFileImporter().importClasses(RogueParticipantFixture::class.java)
        val failure = runCatching { assertParticipantProvisioning(violating) }.exceptionOrNull()
        check(failure is IllegalStateException && RogueParticipantFixture::class.java.name in failure.message.orEmpty()) {
            "the admission guard did not catch concrete-adapter provisioning: $failure"
        }
    }

    @Test
    fun `only the operator admission use case writes the admission pause (ADR-036 section 5)`() {
        val production =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("email.testinbox")
        val writers =
            production
                .flatMap { it.methods + it.constructors }
                .filter { member ->
                    member.callsFromSelf.any { call ->
                        call.targetOwner.isAssignableTo(BetaAdmissionControls::class.java) && call.name == "setAdmissionsOpen"
                    }
                }.map { it.owner.name }
                .toSet()
        // Equality, not a subset: an empty scan must not pass vacuously.
        check(writers == setOf(ControlBetaAdmissions::class.java.name)) {
            "$writers write the admission pause. Only ControlBetaAdmissions may (ADR-036 section 5)."
        }
    }

    private fun assertParticipantProvisioning(classes: JavaClasses) {
        val writers =
            classes
                .flatMap { it.methods + it.constructors }
                .filter { member ->
                    member.callsFromSelf.any { call ->
                        call.targetOwner.isAssignableTo(BetaAdmissions::class.java) && call.name == "create"
                    }
                }.map { it.owner.name }
                .toSet()
        // Equality also proves the guard found a real call; an empty scan
        // must not make an invariant-bearing write rule pass vacuously.
        check(writers == setOf(AdmitBetaParticipant::class.java.name)) {
            "$writers provision participants. Only AdmitBetaParticipant may hold the cohort transaction (ADR-036)."
        }
    }
}
