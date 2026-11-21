package io.github.q0ochen.schafkopf.app.ui.start

import io.github.q0ochen.schafkopf.app.round.RoundTestKit
import io.github.q0ochen.schafkopf.app.ui.round.RoundUiError
import io.github.q0ochen.schafkopf.app.ui.round.SwitchRequest
import io.github.q0ochen.schafkopf.domain.round.InMemoryRoundServer
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.SwitchFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock

/** Der Wechsel über die Auswahl auf der Startseite (docs/ARCHITEKTUR.md 8.9), ohne Oberfläche gegen den Server im Speicher. */
@OptIn(ExperimentalCoroutinesApi::class)
class StartRoundSwitchTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val server = InMemoryRoundServer()

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    /** Handy eins ist mit der „Eigenen“ verbunden und gehört außerdem dem „Stammtisch“ eines anderen Handys an. */
    private suspend fun TestScope.device(): Pair<RoundTestKit, StartViewModel> {
        val host = RoundTestKit(backgroundScope, server, userId = "konto-9")
        host.seed()
        host.coordinator.start()
        host.coordinator.create("Stammtisch")
        val token = host.session.createInvite(host.state.link!!.roundId)

        val kit = RoundTestKit(backgroundScope, server)
        kit.seed("eigener-abend")
        kit.coordinator.start()
        kit.coordinator.create("Eigene")
        kit.session.joinRound(token)
        val viewModel = StartViewModel(kit.evenings, kit.players, kit.coordinator, Clock.systemUTC())
        viewModel.refreshRounds()
        advanceUntilIdle()
        return kit to viewModel
    }

    @Test
    fun `eine andere Runde waehlen fuehrt nach der Rueckfrage dorthin`() = runTest(dispatcher) {
        val (kit, viewModel) = device()
        val stammtisch = kit.state.rounds!!.single { it.name == "Stammtisch" }

        viewModel.choose(stammtisch)
        advanceUntilIdle()
        assertEquals(SwitchRequest.Use(stammtisch), viewModel.roundSwitch.value.switching)
        assertEquals(SwitchFrom(kit.state.link!!.roundId, acceptedLoss = 0), viewModel.roundSwitch.value.from)
        viewModel.cancelSwitch()
        assertNull(viewModel.roundSwitch.value.switching)
        assertEquals("Eigene", kit.state.link?.roundName)

        viewModel.choose(stammtisch)
        advanceUntilIdle()
        viewModel.confirmSwitch()
        advanceUntilIdle()

        assertEquals("Stammtisch", kit.state.link?.roundName)
        assertEquals(listOf("abend-1"), kit.evenings.evenings.first().map { it.id.value })
        assertFalse(viewModel.roundSwitch.value.busy)
        assertNull(viewModel.roundSwitch.value.error)
    }

    @Test
    fun `ohne Netz steht der Fehler unter der Auswahl, und es bleibt die Runde`() = runTest(dispatcher) {
        val (kit, viewModel) = device()
        kit.session.offline = true

        viewModel.choose(kit.state.rounds!!.single { it.name == "Stammtisch" })
        advanceUntilIdle()

        assertEquals(RoundUiError.Server(RoundError.OFFLINE), viewModel.roundSwitch.value.error)
        assertNull(viewModel.roundSwitch.value.switching)
        assertEquals("Eigene", kit.state.link?.roundName)
        viewModel.clearError()
        assertNull(viewModel.roundSwitch.value.error)
    }
}
