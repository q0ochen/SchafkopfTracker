package io.github.q0ochen.schafkopf.app.ui.statistics

import io.github.q0ochen.schafkopf.app.ui.TestEvenings
import io.github.q0ochen.schafkopf.app.ui.awaitFirst
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
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

    private fun viewModel(initial: String?) = StatisticsViewModel(
        initial?.let(::EveningId),
        InMemoryEveningRepository(listOf(older, newer)),
        InMemoryPlayerRepository(TestEvenings.players),
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
    }
}
