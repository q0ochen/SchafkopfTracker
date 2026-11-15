package io.github.q0ochen.schafkopf.app.ui.gameform

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Das Formular für ein Spiel, geprüft ohne Oberfläche: nur das ViewModel mit Ablagen im Arbeitsspeicher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameFormViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val a = PlayerId("a")
    private val b = PlayerId("b")
    private val c = PlayerId("c")
    private val d = PlayerId("d")
    private val seating = listOf(a, b, c, d)
    private val start: Instant = Instant.parse("2026-11-14T18:30:00Z")

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    private fun viewModel(evening: Evening, entryId: EntryId?): GameFormViewModel = setup(evening, entryId).first

    /** Das ViewModel samt der Ablage der Abende, um zu prüfen, was gespeichert wurde. */
    private fun setup(evening: Evening, entryId: EntryId?): Pair<GameFormViewModel, InMemoryEveningRepository> {
        val evenings = InMemoryEveningRepository(listOf(evening))
        val players = InMemoryPlayerRepository(seating.mapIndexed { index, id -> Player(id, "Spieler ${index + 1}", colorIndex = index) })
        var next = 0
        val editor = EveningEditor(evenings, Clock.fixed(start, ZoneOffset.UTC)) { "neu-${++next}" }
        return GameFormViewModel(evening.id, entryId, evenings, players, editor) to evenings
    }

    private fun evening(vararg games: GameEntry) = Evening(
        id = EveningId("abend"),
        startedAt = start,
        seating = seating,
        firstDealer = a,
        rules = RuleSet.standard(),
        entries = games.toList(),
    )

    @Test
    fun `ein Farbsolo zeigt den Betrag des Alleinspielers`() = runTest(dispatcher) {
        val game = GameEntry(EntryId("spiel"), start, GameType.FARBSOLO, declarer = b, declarerWon = true)

        val state = withTimeout(5_000) { viewModel(evening(game), game.id).state.first { !it.loading } }

        assertTrue(state.editing)
        assertTrue(state.canSave)
        // Farbsolo ohne Zusätze: 50 Cent, der Alleinspieler bekommt das Dreifache.
        assertEquals(50, state.breakdown?.value)
        assertEquals(150, state.declarerAmount)
    }

    @Test
    fun `doppeltes Antippen von Gewonnen traegt das Spiel nur einmal ein`() = runTest(dispatcher) {
        val (viewModel, evenings) = setup(evening(), null)
        backgroundScope.launch { viewModel.state.collect {} }
        withTimeout(5_000) { viewModel.state.first { !it.loading } }
        viewModel.selectType(GameType.WENZ)
        viewModel.tapPlayer(b)
        withTimeout(5_000) { viewModel.state.first { it.canSave } }

        var saved = 0
        viewModel.save(won = true) { saved++ }
        viewModel.save(won = true) { saved++ }
        advanceUntilIdle()

        val games = evenings.evening(EveningId("abend")).first()?.entries.orEmpty()
        assertEquals(1, games.size)
        assertEquals(1, saved)
    }

    @Test
    fun `ein Spiel, dessen Ansager nicht am Tisch sitzt, laesst sich trotzdem zum Aendern oeffnen`() = runTest(dispatcher) {
        // So ein Spiel kann nur aus beschädigten Daten stammen. Es bucht nichts; das Formular darf daran nicht abstürzen.
        val stranger = PlayerId("fremd")
        val game = GameEntry(EntryId("spiel"), start, GameType.WENZ, declarer = stranger, declarerWon = true)

        val state = withTimeout(5_000) { viewModel(evening(game), game.id).state.first { !it.loading } }

        assertTrue(state.editing)
        assertFalse(state.loading)
        assertEquals(stranger, state.draft.declarer)
        assertEquals(0, state.declarerAmount)
    }
}
