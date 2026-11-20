package io.github.q0ochen.schafkopf.app.ui.settings

import io.github.q0ochen.schafkopf.app.round.RoundTestKit
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
        val viewModel = PlayersViewModel(players, InMemoryEveningRepository(listOf(evening)), { "neu" }, RoundTestKit(backgroundScope).coordinator)
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
        val viewModel = PlayersViewModel(players, InMemoryEveningRepository(), { "neu" }, RoundTestKit(backgroundScope).coordinator)
        backgroundScope.launch { viewModel.state.collect {} }
        withTimeout(5_000) { viewModel.state.first { it.isNotEmpty() } }

        viewModel.delete(round[1])
        advanceUntilIdle()
        assertTrue(viewModel.add("Neu"))
        advanceUntilIdle()

        val added = players.players.first().single { it.id == PlayerId("neu") }
        assertEquals(1, added.colorIndex)
    }

    @Test
    fun `in der gemeinsamen Runde ist ein selbst festgelegter Name fest und sein Spieler nicht loeschbar`() = runTest(dispatcher) {
        // Handy eins legt die Runde an, Handy zwei tritt bei und sagt „Das bin ich“.
        val one = RoundTestKit(backgroundScope)
        one.seed()
        one.players.save(guest)
        one.coordinator.start()
        one.coordinator.create("Stammtisch")
        val two = RoundTestKit(backgroundScope, one.server, userId = "konto-2")
        two.coordinator.start()
        two.coordinator.join(one.session.createInvite(one.state.link!!.roundId))
        two.coordinator.claim(round[1].id, "Zwei")
        one.sync.sync()
        one.coordinator.claim(round[0].id, "Eins")
        two.sync.sync()
        two.coordinator.syncNow()
        advanceUntilIdle()

        // Auf dem Handy des Mitglieds: beide Namen sind fest, Gäste bleiben frei.
        val member = PlayersViewModel(two.players, two.evenings, { "neu" }, two.coordinator)
        backgroundScope.launch { member.state.collect {} }
        val rows = withTimeout(5_000) { member.state.first { list -> list.any { it.nameLocked } } }
        assertEquals(listOf(true, true, false, false, false), rows.map { it.nameLocked })
        assertEquals(listOf(true, true, false, false, false), rows.map { it.owned })
        // Der Gast kommt in keinem Abend vor und gehört niemandem: Nur er lässt sich löschen.
        assertEquals(listOf(false, false, false, false, true), rows.map { it.canDelete })

        // Der Ersteller der Runde darf einen festen Namen korrigieren; löschen kann auch er die Spieler nicht.
        val creator = PlayersViewModel(one.players, one.evenings, { "neu" }, one.coordinator)
        backgroundScope.launch { creator.state.collect {} }
        val creatorRows = withTimeout(5_000) { creator.state.first { list -> list.count { it.owned } == 2 } }
        assertEquals(listOf(false, false, false, false, false), creatorRows.map { it.nameLocked })
        assertEquals(listOf(true, true, false, false, false), creatorRows.map { it.owned })
    }
}
