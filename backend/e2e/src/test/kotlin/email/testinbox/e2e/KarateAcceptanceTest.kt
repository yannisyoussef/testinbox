package email.testinbox.e2e

import com.intuit.karate.junit5.Karate

class KarateAcceptanceTest {
    @Karate.Test
    fun contractAcceptance(): Karate {
        System.setProperty("testinbox.e2e.baseUrl", E2eStack.apiBaseUrl)
        System.setProperty("testinbox.e2e.apiKey", E2eStack.API_KEY)
        System.setProperty("testinbox.e2e.mailDomain", E2eStack.MAIL_DOMAIN)
        return Karate.run("classpath:karate/testinbox.feature")
    }
}
