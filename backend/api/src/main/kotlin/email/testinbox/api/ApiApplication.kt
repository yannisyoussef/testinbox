package email.testinbox.api

import email.testinbox.api.config.TestInboxProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["email.testinbox.api", "email.testinbox.persistence"])
@EnableConfigurationProperties(TestInboxProperties::class)
@EnableScheduling
class ApiApplication

fun main(args: Array<String>) {
    // Offline operator commands: dispatched before Spring, so no listener or scheduler starts.
    when (args.firstOrNull()) {
        "storage-budget" -> return CohortBudgetCommand.run(args)
        "admission" -> return AdmissionCommand.run(args)
    }
    runApplication<ApiApplication>(*args)
}
