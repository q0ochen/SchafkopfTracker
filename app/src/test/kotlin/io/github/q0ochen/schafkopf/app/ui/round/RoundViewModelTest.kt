package io.github.q0ochen.schafkopf.app.ui.round

import io.github.q0ochen.schafkopf.app.round.RoundTestKit
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.round.InMemoryRoundServer
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Die Abläufe des Bildschirms „Gemeinsame Runde“, geprüft ohne Oberfläche gegen den nachgestellten Server. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoundViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val server = InMemoryRoundServer()

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    private fun TestScope.device(userId: String = "konto-1", signedIn: Boolean = true): Pair<RoundTestKit, RoundViewModel> {
        val kit = RoundTestKit(backgroundScope, server, userId, signedIn)
        kit.coordinator.start()
        val viewModel = RoundViewModel(kit.coordinator, kit.players, kit.evenings)
        backgroundScope.launch { viewModel.players.collect {} }
        return kit to viewModel
    }

    /** Eine Runde, die ein anderes Handy angelegt hat, und eine Einladung dorthin. */
    private suspend fun TestScope.existingRound(): Pair<RoundId, String> {
        val host = RoundTestKit(backgroundScope, server, userId = "konto-9")
        host.seed()
        host.coordinator.start()
        host.coordinator.create("Stammtisch")
        val round = host.state.link!!.roundId
        return round to host.session.createInvite(round)
    }

    // --- Anmelden ---

    @Test
    fun `anmelden oeffnet den Browser - ohne Browser gibt es eine Meldung`() = runTest(dispatcher) {
        val (_, viewModel) = device(signedIn = false)

        viewModel.signIn()
        assertEquals("https://anmeldung.example/authorize", viewModel.ui.value.signInUrl)
        viewModel.signInOpened(found = true)
        assertNull(viewModel.ui.value.signInUrl)
        assertNull(viewModel.ui.value.error)

        viewModel.signIn()
        viewModel.signInOpened(found = false)
        assertEquals(RoundUiError.NoBrowser, viewModel.ui.value.error)
        viewModel.clearError()
        assertNull(viewModel.ui.value.error)
    }

    @Test
    fun `nach dem Anmelden stehen die eigenen Runden und die wartende Einladung da`() = runTest(dispatcher) {
        val (round, token) = existingRound()
        val (kit, viewModel) = device(signedIn = false)
        assertNull(viewModel.ui.value.rounds)

        kit.coordinator.onLink("schafkopftracker://einladung/$token")
        kit.coordinator.onLink("schafkopftracker://auth-callback?code=abc")
        advanceUntilIdle()

        assertEquals(emptyList<Any>(), viewModel.ui.value.rounds)
        assertEquals(round, viewModel.ui.value.invite?.roundId)
        assertEquals("Stammtisch", viewModel.ui.value.invite?.roundName)
        assertEquals(1, viewModel.ui.value.invite?.memberCount)
    }

    @Test
    fun `eine abgelaufene Einladung wird gemeldet und vergessen`() = runTest(dispatcher) {
        val (kit, viewModel) = device()

        kit.coordinator.onLink("schafkopftracker://einladung/${"f".repeat(32)}")
        advanceUntilIdle()

        assertEquals(RoundUiError.Server(RoundError.INVALID_INVITE), viewModel.ui.value.error)
        assertNull(kit.state.pendingInvite)
        assertNull(viewModel.ui.value.invite)
    }

    // --- Anlegen und Beitreten ---

    @Test
    fun `eine Runde anlegen bringt die Daten des Geraets mit`() = runTest(dispatcher) {
        val (kit, viewModel) = device()
        kit.seed()

        viewModel.create("  Stammtisch ")
        advanceUntilIdle()

        assertEquals("Stammtisch", kit.state.link?.roundName)
        assertEquals(1, server.evenings(kit.state.link!!.roundId).size)
        assertFalse(viewModel.ui.value.busy)
        assertNull(viewModel.ui.value.error)
        // Mit verbundener Runde braucht der Bildschirm die Liste der Runden nicht mehr.
        assertNull(viewModel.ui.value.rounds)
    }

    @Test
    fun `ein leerer Name fuer die Runde wird als solcher gemeldet`() = runTest(dispatcher) {
        val (kit, viewModel) = device()

        viewModel.create("   ")
        advanceUntilIdle()

        assertEquals(RoundUiError.Server(RoundError.INVALID_NAME, roundName = true), viewModel.ui.value.error)
        assertNull(kit.state.link)
    }

    @Test
    fun `ein unberuehrtes Geraet tritt ohne Rueckfrage bei`() = runTest(dispatcher) {
        val (_, token) = existingRound()
        val (kit, viewModel) = device()
        io.github.q0ochen.schafkopf.app.SchafkopfApp.DEFAULT_PLAYERS.forEach { kit.players.save(it) }

        viewModel.redeem("Komm in unsere Runde: https://example.org/app/#/einladung/$token")
        advanceUntilIdle()

        assertNull(viewModel.ui.value.replace)
        assertEquals("Stammtisch", kit.state.link?.roundName)
        assertEquals(1, kit.evenings.evenings.first().size)
    }

    @Test
    fun `liegen eigene Abende auf dem Geraet, kommt erst die Rueckfrage`() = runTest(dispatcher) {
        val (_, token) = existingRound()
        val (kit, viewModel) = device()
        kit.seed("eigener-abend")
        kit.coordinator.onLink("schafkopftracker://einladung/$token")
        advanceUntilIdle()

        viewModel.joinInvited()
        advanceUntilIdle()
        assertEquals(PendingAttach.Join(token), viewModel.ui.value.replace)
        assertEquals(1, viewModel.ui.value.eveningCount)
        assertNull(kit.state.link)

        // Abbrechen lässt alles, wie es ist.
        viewModel.cancelReplace()
        assertNull(viewModel.ui.value.replace)
        assertEquals(listOf("eigener-abend"), kit.evenings.evenings.first().map { it.id.value })

        viewModel.joinInvited()
        advanceUntilIdle()
        viewModel.confirmReplace()
        advanceUntilIdle()
        assertEquals("Stammtisch", kit.state.link?.roundName)
        assertEquals(listOf("abend-1"), kit.evenings.evenings.first().map { it.id.value })
        assertNull(kit.state.pendingInvite)
    }

    @Test
    fun `eingefuegter Text ohne Code und eine ungueltige Einladung werden gemeldet`() = runTest(dispatcher) {
        val (kit, viewModel) = device()

        viewModel.redeem("das ist kein Link")
        assertEquals(RoundUiError.NoCode, viewModel.ui.value.error)

        viewModel.redeem("0".repeat(32))
        advanceUntilIdle()
        assertEquals(RoundUiError.Server(RoundError.INVALID_INVITE), viewModel.ui.value.error)
        assertNull(kit.state.link)
    }

    @Test
    fun `eine Runde des Kontos laesst sich auf einem neuen Handy verwenden`() = runTest(dispatcher) {
        val (first, firstModel) = device()
        first.seed()
        firstModel.create("Stammtisch")
        advanceUntilIdle()
        val (second, viewModel) = device()
        advanceUntilIdle()

        val round = viewModel.ui.value.rounds!!.single()
        assertEquals("Stammtisch", round.name)
        viewModel.use(round)
        advanceUntilIdle()

        assertEquals(first.state.link?.roundId, second.state.link?.roundId)
        assertEquals(1, second.evenings.evenings.first().size)
        assertTrue(second.state.link!!.isCreator)
    }

    @Test
    fun `die Einladung verwerfen vergisst sie`() = runTest(dispatcher) {
        val (_, token) = existingRound()
        val (kit, viewModel) = device()
        kit.coordinator.onLink("schafkopftracker://einladung/$token")
        advanceUntilIdle()
        assertNotNull(viewModel.ui.value.invite)

        viewModel.discardInvite()
        advanceUntilIdle()

        assertNull(kit.state.pendingInvite)
        assertNull(viewModel.ui.value.invite)
    }

    // --- Verbunden ---

    @Test
    fun `Das bin ich legt den Namen fest - ein vergebener Name wird gemeldet`() = runTest(dispatcher) {
        val (kit, viewModel) = device()
        kit.seed()
        viewModel.create("Stammtisch")
        advanceUntilIdle()

        viewModel.claim(PlayerId("spieler-1"), "Spieler 2")
        advanceUntilIdle()
        assertEquals(RoundUiError.Server(RoundError.NAME_TAKEN), viewModel.ui.value.error)
        assertNull(kit.state.link?.playerId)

        viewModel.claim(PlayerId("spieler-1"), "Eins")
        advanceUntilIdle()
        assertNull(viewModel.ui.value.error)
        assertEquals(PlayerId("spieler-1"), kit.state.link?.playerId)
        assertEquals("Eins", viewModel.players.value.first().name)

        // Wer neu ist, bekommt einen eigenen Spieler.
        val (other, otherModel) = device("konto-2")
        other.coordinator.join(kit.session.createInvite(kit.state.link!!.roundId))
        otherModel.claim(null, "Zwei")
        advanceUntilIdle()
        assertEquals(5, other.players.players.first().size)
        assertNotNull(other.state.link?.playerId)
    }

    @Test
    fun `einladen erzeugt einen Link zum Weitergeben`() = runTest(dispatcher) {
        val (kit, viewModel) = device()
        viewModel.create("Stammtisch")
        advanceUntilIdle()

        viewModel.invite()
        advanceUntilIdle()

        val link = viewModel.ui.value.shareLink
        assertTrue(link.orEmpty(), link.orEmpty().startsWith("https://example.org/app/#/einladung/"))
        assertEquals(kit.state.link?.roundId, kit.session.inviteInfo(link!!.substringAfterLast('/')).roundId)
        viewModel.shared()
        assertNull(viewModel.ui.value.shareLink)
    }

    @Test
    fun `vor dem Verlassen steht in der Rueckfrage, wie viel noch wartet`() = runTest(dispatcher) {
        val (kit, viewModel) = device()
        val evening = kit.seed()
        viewModel.create("Stammtisch")
        advanceUntilIdle()
        kit.session.offline = true
        kit.addGame(evening)

        viewModel.askFarewell(Farewell.LEAVE)
        advanceUntilIdle()
        assertEquals(Farewell.LEAVE, viewModel.ui.value.farewell)
        assertEquals(1, viewModel.ui.value.farewellPending)
        viewModel.cancelFarewell()
        assertNull(viewModel.ui.value.farewell)

        // Ohne Netz lässt sich die Runde nicht verlassen; das Gerät bleibt verbunden.
        viewModel.askFarewell(Farewell.LEAVE)
        advanceUntilIdle()
        viewModel.confirmFarewell()
        advanceUntilIdle()
        assertEquals(RoundUiError.Server(RoundError.OFFLINE), viewModel.ui.value.error)
        assertNotNull(kit.state.link)

        // Abmelden geht immer: Das Gerät ist danach getrennt und abgemeldet, die Daten bleiben.
        viewModel.askFarewell(Farewell.SIGN_OUT)
        advanceUntilIdle()
        viewModel.confirmFarewell()
        advanceUntilIdle()
        assertNull(kit.state.link)
        assertNull(kit.state.account)
        assertEquals(1, kit.evenings.evenings.first().single().entries.size)
    }

    @Test
    fun `scheitert nur der erste Abgleich, steht die Verbindung trotzdem`() = runTest(dispatcher) {
        val (_, token) = existingRound()
        val (kit, viewModel) = device()
        kit.session.loseNextAnswer = true

        viewModel.redeem(token)
        advanceUntilIdle()

        // Beigetreten ist das Konto; die Antwort ging verloren. Ein zweiter Versuch räumt auf.
        assertEquals(RoundUiError.Server(RoundError.OFFLINE), viewModel.ui.value.error)
        viewModel.redeem(token)
        advanceUntilIdle()
        assertEquals("Stammtisch", kit.state.link?.roundName)
        assertNull(viewModel.ui.value.error)
    }
}
