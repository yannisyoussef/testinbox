package email.testinbox.api

import email.testinbox.api.config.TestInboxProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import kotlin.system.exitProcess

@SpringBootApplication(scanBasePackages = ["email.testinbox.api", "email.testinbox.persistence"])
@EnableConfigurationProperties(TestInboxProperties::class)
@EnableScheduling
class ApiApplication

fun main(args: Array<String>) {
    // Offline operator subcommands are dispatched BEFORE Spring: no HTTP
    // listener, scheduler, login provider or migration ever starts for them.
    if (args.firstOrNull() == "storage-budget") {
        CohortBudgetCommand.run(args)
        return
    }
    if (args.firstOrNull() == AccountLifecycleCommand.NAME) {
        exitProcess(AccountLifecycleCommand.run(args))
    }
    runApplication<ApiApplication>(*args)
}
