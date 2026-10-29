package email.testinbox.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class CohortBudgetInputTest {
    private val mapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    @Test fun `operator input keeps explicit byte budgets and duration without HTTP or environment defaults`() {
        val text = """{"declarations":{"enforcement":"ALL","filesystem":{"observationMaxAge":"PT15M"}},
            "legacy":[],"unattributedAllowance":1,"trashAllowance":2}"""
        val input = mapper.readValue(text, CohortBudgetCommand.Input::class.java)
        input.declarations.filesystem.observationMaxAge shouldBe java.time.Duration.ofMinutes(15)
        input.trashAllowance shouldBe 2
        shouldThrow<Exception> {
            mapper.readValue(text.replace("\"trashAllowance\":2", "\"trashAllowence\":2"), CohortBudgetCommand.Input::class.java)
        }
    }
}
