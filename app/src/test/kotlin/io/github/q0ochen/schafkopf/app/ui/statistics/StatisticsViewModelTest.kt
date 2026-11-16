package io.github.q0ochen.schafkopf.app.ui.statistics

import io.github.q0ochen.schafkopf.app.ai.Commentary
import io.github.q0ochen.schafkopf.app.ai.CommentarySource
import io.github.q0ochen.schafkopf.app.ai.Commentator
import io.github.q0ochen.schafkopf.app.ai.FakeGeneratorFactory
import io.github.q0ochen.schafkopf.app.ai.FakeTextGenerator
import io.github.q0ochen.schafkopf.app.ui.TestEvenings
import io.github.q0ochen.schafkopf.app.ui.ai.CommentaryUi
import io.github.q0ochen.schafkopf.app.ui.awaitFirst
import io.github.q0ochen.schafkopf.domain.model.AiSettings
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySecretStore
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.summary.SummaryPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** Auswahl des Abends und die Auswertung dazu, ohne Oberfläche geprüft. */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val older = TestEvenings.evening(id = "alt", startedAt = Instant.parse("2026-11-01T18:30:00Z"), location = " Post ")
    private val newer = TestEvenings.evening(
        id = "neu",
        startedAt = Instant.parse("2026-11-14T18:30:00Z"),
        rules = RuleSet.standard().copy(displayAsMoney = false),
    )

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    private val generator = FakeTextGenerator()

    private fun viewModel(initial: String?, ai: AiSettings = AiSettings()) = StatisticsViewModel(
        initial?.let(::EveningId),
        InMemoryEveningRepository(listOf(older, newer)),
        InMemoryPlayerRepository(TestEvenings.players),
        Commentator(InMemorySettingsRepository(AppSettings(ai = ai)), InMemorySecretStore("geheim"), FakeGeneratorFactory(generator)),
        dispatcher,
    )

    @Test
    fun `alle Abende zusammen, die Auswahl mit dem neuesten zuerst`() = runTest(dispatcher) {
        val viewModel = viewModel(null)
        backgroundScope.launch { viewModel.state.collect {} }
        val state = viewModel.state.awaitFirst { !it.loading }
        assertNull(state.selected)
        assertEquals(listOf("neu", "alt"), state.choices.map { it.id.value })
        assertEquals("Post", state.choices.last().location)
        assertEquals(2, state.stats?.eveningCount)
        assertEquals(4, state.stats?.gameCount)
        // Über alle Abende wie beim neuesten: in Punkten.
        assertFalse(state.asMoney)
        assertEquals(Instant.parse("2026-11-01T18:30:00Z"), state.startOf(EveningId("alt")))
    }

    @Test
    fun `ein Abend, dann wieder alle, und ein verschwundener Abend`() = runTest(dispatcher) {
        val viewModel = viewModel("alt")
        backgroundScope.launch { viewModel.state.collect {} }
        val single = viewModel.state.awaitFirst { !it.loading && it.selected == EveningId("alt") }
        assertEquals(2, single.stats?.gameCount)
        assertEquals(1, single.stats?.eveningCount)
        assertTrue(single.asMoney)
        assertEquals("alt", single.selectedChoice?.id?.value)

        viewModel.select(null)
        val all = viewModel.state.awaitFirst { it.selected == null && !it.loading }
        assertEquals(4, all.stats?.gameCount)

        viewModel.select(EveningId("weg"))
        val missing = viewModel.state.awaitFirst { it.selected == EveningId("weg") }
        assertNull(missing.stats)
        assertNull(missing.selectedChoice)
        assertEquals("?", missing.name(PlayerId("unbekannt")))
        // Ohne Auswertung gibt es auch nichts zu kommentieren.
        viewModel.writeCommentary()
        assertEquals(CommentaryUi.Idle, viewModel.state.value.commentary)
    }

    @Test
    fun `ueber alle Abende schreibt der Kommentator die Bilanz, fuer einen Abend den Abendbericht`() = runTest(dispatcher) {
        val viewModel = viewModel(null)
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.state.awaitFirst { !it.loading }

        viewModel.writeCommentary()
        val all = viewModel.state.awaitFirst { it.commentary is CommentaryUi.Ready }.commentary as CommentaryUi.Ready
        assertTrue(all.commentary.text, all.commentary.text.startsWith("Schafkopf-Bilanz vom 01.11.2026 bis 14.11.2026"))
        assertEquals(CommentarySource.TEMPLATE, all.commentary.source)

        // Ein anderer Abend: Der Kommentar zur bisherigen Auswahl verschwindet.
        viewModel.select(EveningId("alt"))
        assertEquals(CommentaryUi.Idle, viewModel.state.awaitFirst { it.selected == EveningId("alt") && !it.loading }.commentary)
        viewModel.writeCommentary()
        val single = viewModel.state.awaitFirst { it.commentary is CommentaryUi.Ready }.commentary as CommentaryUi.Ready
        assertTrue(single.commentary.text, single.commentary.text.startsWith("Schafkopf am 01.11.2026"))
        assertTrue(generator.prompts.isEmpty())
    }

    @Test
    fun `vor dem ersten Senden der Bilanz wird gefragt, danach schreibt das Sprachmodell einmal`() = runTest(dispatcher) {
        val viewModel = viewModel(null, AiSettings(enabled = true))
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.state.awaitFirst { !it.loading }

        viewModel.writeCommentary()
        val asking = viewModel.state.awaitFirst { it.commentary is CommentaryUi.NeedsConsent }.commentary as CommentaryUi.NeedsConsent
        viewModel.declineConsent()
        assertEquals(CommentaryUi.Idle, viewModel.state.awaitFirst { it.commentary == CommentaryUi.Idle }.commentary)

        viewModel.writeCommentary()
        viewModel.state.awaitFirst { it.commentary is CommentaryUi.NeedsConsent }
        viewModel.consent(asking.host)
        viewModel.consent(asking.host)
        val ready = viewModel.state.awaitFirst { it.commentary is CommentaryUi.Ready }.commentary as CommentaryUi.Ready
        assertEquals(Commentary("Servus!", CommentarySource.AI, "gemini-test"), ready.commentary)
        assertEquals(SummaryPrompt.HISTORY_SYSTEM_PROMPT, generator.prompts.single().first)
    }
}
