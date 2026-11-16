package io.github.q0ochen.schafkopf.app.ui.settings

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
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
import java.time.Instant

/** Spieler verwalten, geprüft ohne Oberfläche: nur das ViewModel mit Ablagen im Arbeitsspeicher. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayersViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val round = (1..4).map { Player(PlayerId("spieler-$it"), "Spieler $it", colorIndex = it - 1) }
    private val guest = Player(PlayerId("gast"), "Gast", colorIndex = 4)

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    private val evening = Evening(
        id = EveningId("abend"),
        startedAt = Instant.parse("2026-11-14T19:00:00Z"),
        seating = round.map { it.id },
        firstDealer = round.first().id,
        rules = RuleSet.standard(),
    )

    @Test
    fun `ein Spieler ohne Abend laesst sich loeschen, einer mit Abend nicht`() = runTest(dispatcher) {
        val players = InMemoryPlayerRepository(round + guest)
        val viewModel = PlayersViewModel(players, InMemoryEveningRepository(listOf(evening))) { "neu" }
        backgroundScope.launch { viewModel.state.collect {} }
        val rows = withTimeout(5_000) { viewModel.state.first { it.isNotEmpty() } }
        assertEquals(listOf(1, 1, 1, 1, 0), rows.map { it.eveningCount })
        assertFalse(rows.first().canDelete)
        assertTrue(rows.last().canDelete)

        viewModel.delete(guest)
        viewModel.delete(round.first())
        advanceUntilIdle()

        assertEquals(round, players.players.first())
    }

    @Test
    fun `ein neuer Spieler bekommt die Farbe eines geloeschten zurueck`() = runTest(dispatcher) {
        val players = InMemoryPlayerRepository(round + guest)
        val viewModel = PlayersViewModel(players, InMemoryEveningRepository()) { "neu" }
        backgroundScope.launch { viewModel.state.collect {} }
        withTimeout(5_000) { viewModel.state.first { it.isNotEmpty() } }

        viewModel.delete(round[1])
        advanceUntilIdle()
        assertTrue(viewModel.add("Neu"))
        advanceUntilIdle()

        val added = players.players.first().single { it.id == PlayerId("neu") }
        assertEquals(1, added.colorIndex)
    }
}
