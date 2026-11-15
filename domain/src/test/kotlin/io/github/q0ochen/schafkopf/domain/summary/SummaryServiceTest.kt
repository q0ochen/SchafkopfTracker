package io.github.q0ochen.schafkopf.domain.summary

import io.github.q0ochen.schafkopf.domain.ports.GeneratedText
import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import io.github.q0ochen.schafkopf.domain.ports.TextGenerator
import io.github.q0ochen.schafkopf.domain.stats.SampleEvening
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SummaryServiceTest {

    private val facts = SummaryFactsBuilder.build(SampleEvening.state, SampleEvening.names, SampleEvening.zone)

    /** Ein Sprachmodell zum Testen: antwortet immer dasselbe und merkt sich, was es gefragt wurde. */
    private class FakeTextGenerator(private val answer: LlmResult<GeneratedText>) : TextGenerator {
        val systemPrompts = ArrayList<String>()
        val userTexts = ArrayList<String>()

        override suspend fun generate(systemPrompt: String, userText: String): LlmResult<GeneratedText> {
            systemPrompts += systemPrompt
            userTexts += userText
            return answer
        }

        override suspend fun listModels(): LlmResult<List<String>> = LlmResult.Success(listOf("test-modell"))
    }

    private fun answering(text: String) = FakeTextGenerator(LlmResult.Success(GeneratedText(text, "test-modell")))

    @Test
    fun `die Antwort wird aufbereitet und mit dem Namen des Modells zurueckgegeben`() = runTest {
        val generator = answering("**Schafkopf am 14.11.2026**\n\n\n[[S3]] gewinnt mit +11,40 €, [[S2]] zahlt.")
        val result = SummaryService(generator).generate(facts, useRealNames = false)

        assertEquals(
            LlmResult.Success(
                FinishedSummary(
                    text = "Schafkopf am 14.11.2026\n\nChris gewinnt mit +11,40 €, Bert zahlt.",
                    warnings = emptySet(),
                    model = "test-modell",
                ),
            ),
            result,
        )
    }

    @Test
    fun `gesendet werden die Anweisung und die Fakten mit Platzhaltern`() = runTest {
        val generator = answering("Servus")
        SummaryService(generator).generate(facts, useRealNames = false)

        assertEquals(listOf(SummaryPrompt.SYSTEM_PROMPT), generator.systemPrompts)
        assertEquals(listOf(SummaryPrompt.payload(facts, useRealNames = false).userText), generator.userTexts)
    }

    @Test
    fun `auf Wunsch werden die echten Namen gesendet`() = runTest {
        val generator = answering("Chris gewinnt, [[S2]] zahlt.")
        val result = SummaryService(generator).generate(facts, useRealNames = true)

        assertEquals(listOf(SummaryPrompt.payload(facts, useRealNames = true).userText), generator.userTexts)
        // Ohne Platzhalter in der Anfrage ist einer in der Antwort verdächtig und wird gemeldet.
        assertEquals(
            LlmResult.Success(
                FinishedSummary("Chris gewinnt, [[S2]] zahlt.", setOf(SummaryWarning.UNRESOLVED_PLACEHOLDER), "test-modell"),
            ),
            result,
        )
    }

    @Test
    fun `Warnungen aus der Aufbereitung kommen beim Aufrufer an`() = runTest {
        val result = SummaryService(answering("[[S3]] gewinnt mit 99,00 €.")).generate(facts, useRealNames = false)

        assertEquals(
            LlmResult.Success(
                FinishedSummary("Chris gewinnt mit 99,00 €.", setOf(SummaryWarning.UNKNOWN_AMOUNT), "test-modell"),
            ),
            result,
        )
    }

    @Test
    fun `ein Fehler des Sprachmodells wird unveraendert durchgereicht`() = runTest {
        for (error in LlmError.entries) {
            val failure = LlmResult.Failure(error, "HTTP 429")
            val result = SummaryService(FakeTextGenerator(failure)).generate(facts, useRealNames = false)
            assertSame(failure, result)
        }
    }

    @Test
    fun `bleibt nach dem Aufbereiten kein Text uebrig, gilt die Antwort als leer`() = runTest {
        for (text in listOf("", "   \n\n ", "```\n```", "** **")) {
            val result = SummaryService(answering(text)).generate(facts, useRealNames = false)
            assertEquals(LlmResult.Failure(LlmError.EMPTY_OR_TRUNCATED), result)
        }
    }
}
