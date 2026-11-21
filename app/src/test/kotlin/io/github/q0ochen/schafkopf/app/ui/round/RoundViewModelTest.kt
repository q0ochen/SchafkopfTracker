package io.github.q0ochen.schafkopf.app.ui.round

import io.github.q0ochen.schafkopf.app.round.RoundTestKit
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.round.InMemoryRoundServer
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundSummary
import io.github.q0ochen.schafkopf.domain.round.SwitchFrom
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

/** Die Abläufe des Bildschirms „Gemeinsame Runden“, geprüft ohne Oberfläche gegen den nachgestellten Server. */
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
        assertNull(kit.state.rounds)

        kit.coordinator.onLink("schafkopftracker://einladung/$token")
        kit.coordinator.onLink("schafkopftracker://auth-callback?code=abc")
        advanceUntilIdle()

        assertEquals(emptyList<Any>(), kit.state.rounds)
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
        // Die Runden des Kontos sind danach neu gelesen (für „Deine anderen Runden“ und die Startseite).
        assertEquals(listOf("Stammtisch"), kit.state.rounds?.map { it.name })
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

        val round = second.state.rounds!!.single()
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
    @Test
    fun `eine nicht verbundene Runde verlassen, mit Rueckfrage`() = runTest(dispatcher) {
        val (_, token) = existingRound()
        val (kit, viewModel) = device()
        kit.session.joinRound(token)
        viewModel.refresh()
        advanceUntilIdle()
        val round = kit.state.rounds!!.single()

        viewModel.askLeaveRound(round)
        assertEquals(round, viewModel.ui.value.leaving)
        viewModel.cancelLeaveRound()
        assertNull(viewModel.ui.value.leaving)
        viewModel.askLeaveRound(round)
        viewModel.confirmLeaveRound()
        advanceUntilIdle()

        assertNull(viewModel.ui.value.leaving)
        assertEquals(emptyList<RoundSummary>(), kit.state.rounds)
        assertEquals(emptyList<RoundSummary>(), kit.session.myRounds())
        assertNull(kit.state.link)
    }

    // --- Mit verbundener Runde: wechseln, beitreten, neu anlegen (docs/ARCHITEKTUR.md 8.7 und 8.9) ---

    /** Handy eins ist mit seiner „Eigenen“ verbunden (ein Abend) und gehört außerdem dem „Stammtisch“ an. */
    private suspend fun TestScope.linkedWithAnother(): Triple<RoundTestKit, RoundViewModel, RoundId> {
        val (stammtisch, token) = existingRound()
        val (kit, viewModel) = device()
        kit.seed("eigener-abend")
        viewModel.create("Eigene")
        advanceUntilIdle()
        kit.session.joinRound(token)
        viewModel.refresh()
        advanceUntilIdle()
        return Triple(kit, viewModel, stammtisch)
    }

    @Test
    fun `wechseln - erst abgleichen, dann die Rueckfrage, dann die Daten der anderen Runde`() = runTest(dispatcher) {
        val (kit, viewModel, stammtisch) = linkedWithAnother()
        val eigene = kit.state.link!!.roundId
        val target = kit.state.rounds!!.single { it.id == stammtisch }
        kit.addGame(io.github.q0ochen.schafkopf.domain.model.EveningId("eigener-abend"))

        viewModel.use(target)
        advanceUntilIdle()

        // Vor der Rückfrage ist alles hochgeladen; es wartet nichts, was verloren ginge.
        assertEquals(SwitchRequest.Use(target), viewModel.ui.value.switching)
        assertEquals(SwitchFrom(eigene, acceptedLoss = 0), viewModel.ui.value.switchFrom)
        assertEquals(1, server.evenings(eigene).single().entries.size)
        viewModel.cancelSwitch()
        assertNull(viewModel.ui.value.switching)
        assertEquals(eigene, kit.state.link?.roundId)

        viewModel.use(target)
        advanceUntilIdle()
        viewModel.confirmSwitch()
        advanceUntilIdle()

        assertEquals(stammtisch, kit.state.link?.roundId)
        assertEquals(listOf("abend-1"), kit.evenings.evenings.first().map { it.id.value })
        assertEquals(listOf("eigener-abend"), server.evenings(eigene).map { it.id.value })
        assertNull(viewModel.ui.value.error)
        assertFalse(viewModel.ui.value.busy)
    }

    @Test
    fun `ohne Netz kommt statt der Rueckfrage der Fehler, und nichts aendert sich`() = runTest(dispatcher) {
        val (kit, viewModel, stammtisch) = linkedWithAnother()
        val eigene = kit.state.link!!.roundId
        kit.session.offline = true

        viewModel.use(kit.state.rounds!!.single { it.id == stammtisch })
        advanceUntilIdle()

        assertEquals(RoundUiError.Server(RoundError.OFFLINE), viewModel.ui.value.error)
        assertNull(viewModel.ui.value.switching)
        assertEquals(eigene, kit.state.link?.roundId)
        assertEquals(listOf("eigener-abend"), kit.evenings.evenings.first().map { it.id.value })
    }

    @Test
    fun `gibt es die verbundene Runde nicht mehr, kommt statt der Rueckfrage die Meldung`() = runTest(dispatcher) {
        val (kit, viewModel, stammtisch) = linkedWithAnother()
        server.removeMember(kit.state.link!!.roundId, kit.userId)

        viewModel.use(kit.state.rounds!!.single { it.id == stammtisch })
        advanceUntilIdle()

        assertNull(viewModel.ui.value.switching)
        assertEquals(io.github.q0ochen.schafkopf.app.round.RoundMessage.DETACHED, kit.state.message)
        assertNull(kit.state.link)
        // Die Daten bleiben als Kopie.
        assertEquals(listOf("eigener-abend"), kit.evenings.evenings.first().map { it.id.value })
    }

    @Test
    fun `mit verbundener Runde beginnt eine neue Runde leer - erst nach der Rueckfrage`() = runTest(dispatcher) {
        val (kit, viewModel, _) = linkedWithAnother()
        val eigene = kit.state.link!!.roundId

        // Ein Name, den das Konto schon hat, wird gleich gemeldet, ohne Rückfrage.
        viewModel.create(" stammtisch ")
        advanceUntilIdle()
        assertEquals(RoundUiError.Server(RoundError.NAME_TAKEN, roundName = true), viewModel.ui.value.error)
        assertNull(viewModel.ui.value.switching)

        viewModel.create("Freitagsrunde")
        advanceUntilIdle()
        assertEquals(SwitchRequest.Create("Freitagsrunde"), viewModel.ui.value.switching)
        assertEquals(eigene, kit.state.link?.roundId)
        viewModel.confirmSwitch()
        advanceUntilIdle()

        assertEquals("Freitagsrunde", kit.state.link?.roundName)
        assertTrue(kit.evenings.evenings.first().isEmpty())
        assertTrue(kit.players.players.first().isEmpty())
        assertEquals(listOf("eigener-abend"), server.evenings(eigene).map { it.id.value })
        assertEquals(listOf("Stammtisch", "Eigene", "Freitagsrunde").sorted(), kit.state.rounds!!.map { it.name }.sorted())
    }

    @Test
    fun `mit verbundener Runde fuehrt eine Einladung ueber die Rueckfrage in die andere Runde`() = runTest(dispatcher) {
        val (round, token) = existingRound()
        val (kit, viewModel) = device()
        kit.seed("eigener-abend")
        viewModel.create("Eigene")
        advanceUntilIdle()

        viewModel.redeem("Komm in unsere Runde: https://example.org/app/#/einladung/$token")
        advanceUntilIdle()
        assertEquals(SwitchRequest.Join(token, "Stammtisch"), viewModel.ui.value.switching)
        viewModel.confirmSwitch()
        advanceUntilIdle()

        assertEquals(round, kit.state.link?.roundId)
        assertEquals(listOf("abend-1"), kit.evenings.evenings.first().map { it.id.value })
        assertEquals(2, kit.state.rounds!!.size)
    }

    @Test
    fun `eine Einladung in die verbundene Runde sagt nur, dass man schon drin ist`() = runTest(dispatcher) {
        val (round, token) = existingRound()
        val (kit, viewModel) = device()
        kit.coordinator.join(token)
        advanceUntilIdle()
        kit.coordinator.onLink("schafkopftracker://einladung/$token")
        advanceUntilIdle()
        assertEquals(round, viewModel.ui.value.invite?.roundId)

        viewModel.joinInvited()
        advanceUntilIdle()

        assertEquals(RoundUiError.AlreadyInRound, viewModel.ui.value.error)
        assertNull(viewModel.ui.value.switching)
        assertNull(kit.state.pendingInvite)
        assertEquals(round, kit.state.link?.roundId)
    }

    @Test
    fun `solange der Wechsel vorbereitet wird, ist alles gesperrt`() = runTest(dispatcher) {
        val (kit, viewModel, stammtisch) = linkedWithAnother()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        kit.api.gate = gate

        viewModel.use(kit.state.rounds!!.single { it.id == stammtisch })
        assertTrue(viewModel.ui.value.switchBusy)
        assertTrue(viewModel.ui.value.busy)
        // Ein zweiter Anstoß während des Wartens zählt nicht.
        viewModel.create("Noch eine")
        kit.api.gate = null
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.ui.value.switchBusy)
        assertTrue(viewModel.ui.value.switching is SwitchRequest.Use)
    }

    @Test
    fun `hat sich das Geraet seit der Rueckfrage geaendert, wird nicht gewechselt`() = runTest(dispatcher) {
        val (kit, viewModel, stammtisch) = linkedWithAnother()
        viewModel.use(kit.state.rounds!!.single { it.id == stammtisch })
        advanceUntilIdle()
        assertNotNull(viewModel.ui.value.switching)
        // Zwischen Rückfrage und Bestätigung wird das Gerät getrennt (etwa abgemeldet auf einem anderen Bildschirm).
        kit.coordinator.signOut()
        advanceUntilIdle()

        viewModel.confirmSwitch()
        advanceUntilIdle()

        assertNull(kit.state.link)
        assertEquals(listOf("eigener-abend"), kit.evenings.evenings.first().map { it.id.value })
        assertTrue(viewModel.ui.value.error is RoundUiError.Server)
    }
}
