package io.github.q0ochen.schafkopf.app.ai

import io.github.q0ochen.schafkopf.app.ui.TestEvenings
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.model.AiProvider
import io.github.q0ochen.schafkopf.domain.model.AiSettings
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.ports.InMemorySecretStore
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import io.github.q0ochen.schafkopf.domain.summary.SummaryFactsBuilder
import io.github.q0ochen.schafkopf.domain.summary.TemplateSummary
import io.github.q0ochen.schafkopf.app.ui.common.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Der Abend-Kommentator: Textbausteine ohne Sprachmodell, Zustimmung vor dem Senden, Platzhalter statt Namen. */
class CommentatorTest {

    private val state = EveningCalculator.compute(TestEvenings.evening())
    private val names = TestEvenings.names
    private val template = TemplateSummary.render(SummaryFactsBuilder.build(state, names, Money))
    private val gemini = "generativelanguage.googleapis.com"

    private fun commentator(ai: AiSettings, key: String? = "geheim", factory: FakeGeneratorFactory = FakeGeneratorFactory()) =
        Triple(Commentator(InMemorySettingsRepository(AppSettings(ai = ai)), InMemorySecretStore(key), factory), factory, ai)

    @Test
    fun `ohne Sprachmodell gibt es die Zusammenfassung aus Textbausteinen, ohne etwas zu senden`() = runBlocking {
        val (commentator, factory) = commentator(AiSettings(enabled = false))
        assertEquals(CommentaryOutcome.Done(Commentary(template, CommentarySource.TEMPLATE)), commentator.write(state, names))
        assertTrue(factory.generator.prompts.isEmpty())
        assertTrue(template.contains("Anna"))
    }

    @Test
    fun `vor dem ersten Senden wird gefragt, danach schreibt das Sprachmodell mit Platzhaltern`() = runBlocking {
        val settings = InMemorySettingsRepository(AppSettings(ai = AiSettings(enabled = true)))
        val factory = FakeGeneratorFactory()
        val commentator = Commentator(settings, InMemorySecretStore("geheim"), factory)

        assertEquals(CommentaryOutcome.NeedsConsent(gemini, realNames = false), commentator.write(state, names))
        assertTrue(factory.generator.prompts.isEmpty())

        commentator.consent(gemini)
        assertEquals(gemini, settings.settings.first().ai.consentHost)
        val outcome = commentator.write(state, names)
        assertEquals(CommentaryOutcome.Done(Commentary("Servus!", CommentarySource.AI, "gemini-test")), outcome)
        assertEquals("geheim", factory.lastKey)
        val sent = factory.generator.prompts.single().second
        for (name in names.values) assertFalse("$name wurde gesendet", sent.contains(name))
    }

    @Test
    fun `mit echten Namen gehen die Namen mit`() = runBlocking {
        val (commentator, factory) = commentator(AiSettings(enabled = true, sendRealNames = true, consentHost = gemini))
        commentator.write(state, names)
        assertTrue(factory.generator.prompts.single().second.contains("Anna"))
    }

    @Test
    fun `ein Fehler des Sprachmodells liefert den Grund und die Textbausteine`() = runBlocking {
        val factory = FakeGeneratorFactory(FakeTextGenerator(LlmResult.Failure(LlmError.QUOTA_EXCEEDED, "HTTP 429")))
        val (commentator) = commentator(AiSettings(enabled = true, consentHost = gemini), factory = factory)
        assertEquals(
            CommentaryOutcome.Failed(LlmError.QUOTA_EXCEEDED, "HTTP 429", Commentary(template, CommentarySource.TEMPLATE)),
            commentator.write(state, names),
        )
    }

    @Test
    fun `ein eigener Server braucht eine erlaubte Adresse und eine eigene Zustimmung`() = runBlocking {
        val bad = commentator(AiSettings(enabled = true, provider = AiProvider.OPENAI_COMPATIBLE, baseUrl = "http://example.com/v1")).first
        val failed = bad.write(state, names)
        assertTrue(failed is CommentaryOutcome.Failed && failed.error == LlmError.INVALID_ENDPOINT)

        val local = AiSettings(enabled = true, provider = AiProvider.OPENAI_COMPATIBLE, baseUrl = "http://192.168.1.20:11434/v1", consentHost = gemini)
        assertEquals(CommentaryOutcome.NeedsConsent("192.168.1.20", realNames = false), commentator(local).first.write(state, names))
    }

    @Test
    fun `ohne gewaehltes Modell nimmt Gemini das neueste schnelle`() {
        val generator = TextGeneratorFactory(okhttp3.OkHttpClient()).create(AiSettings(enabled = true), "x")
        assertTrue(generator.toString(), generator.toString().contains(TextGeneratorFactory.DEFAULT_GEMINI_MODEL))
    }
}
