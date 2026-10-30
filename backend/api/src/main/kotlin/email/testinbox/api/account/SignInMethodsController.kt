package email.testinbox.api.account

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Public configuration, never an account-existence or cohort-capacity lookup. */
@RestController
class SignInMethodsController(
    private val email: EmailAccountRuntime,
    private val providers: ProviderAccountRuntime,
) {
    data class View(
        val methods: List<String>,
    )

    @GetMapping("/account/methods")
    fun methods(): View =
        View(
            buildList {
                if (email.availability == EmailAccountAvailability.READY) add("email")
                for (provider in listOf("google", "github")) {
                    if (providers.flows
                            ?.protocols
                            ?.registrations
                            ?.findByRegistrationId(provider) != null
                    ) {
                        add(provider)
                    }
                }
            },
        )
}
