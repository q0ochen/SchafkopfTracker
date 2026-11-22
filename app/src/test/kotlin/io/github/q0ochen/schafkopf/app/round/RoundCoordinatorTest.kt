package io.github.q0ochen.schafkopf.app.round

import io.github.q0ochen.schafkopf.data.round.Account
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.round.InMemoryRoundServer
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundException
import io.github.q0ochen.schafkopf.domain.round.SwitchFrom
import io.github.q0ochen.schafkopf.domain.round.SyncNotice
import io.github.q0ochen.schafkopf.domain.round.SyncOutcome
import io.github.q0ochen.schafkopf.domain.round.InviteLinks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Der Koordinator der gemeinsamen Runde: wann abgeglichen wird, was die Oberfläche davon erfährt und wie Links
 * (Rückkehr aus der Anmeldung, Einladung) behandelt werden. Mit nachgestelltem Server und gestellter Zeit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoundCoordinatorTest {

    /** Ein Handy im Vordergrund mit einer frisch angelegten Runde; die Aufrufe bis hierhin sind vergessen. */
    private suspend fun TestScope.linked(server: InMemoryRoundServer = InMemoryRoundServer()): RoundTestKit {
        val kit = RoundTestKit(backgroundScope, server)
        kit.seed()
        kit.coordinator.start()
        kit.coordinator.setForeground(true)
        runCurrent()
        kit.coordinator.create("Stammtisch")
        settle()
        kit.session.calls.clear()
        return kit
    }

    /** Lässt alles laufen, was jetzt und in der nächsten Sekunde fällig ist (die Entprellzeit nach einer Änderung). */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
    }

    // --- Start ---

    @Test
    fun `ohne eingerichteten Server ist die Funktion aus und nichts laeuft`() = runTest {
        val kit = RoundTestKit(backgroundScope, configured = false)

        kit.coordinator.start()
        kit.coordinator.setForeground(true)
        advanceTimeBy(60_000)

        assertEquals(RoundState(configured = false, loaded = true), kit.state)
        assertEquals(emptyList<String>(), kit.session.calls)
    }

    @Test
    fun `beim Start werden Anmeldung, Runde und eine wartende Einladung gelesen`() = runTest {
        val kit = RoundTestKit(backgroundScope)
        kit.invites.set("0123456789abcdef0123456789abcdef")
        assertFalse(kit.state.loaded)

        kit.coordinator.start()
        kit.coordinator.start()
        runCurrent()

        assertTrue(kit.state.loaded)
        assertEquals(Account("konto-1", "konto-1@example.org"), kit.state.account)
        assertNull(kit.state.link)
        assertEquals("0123456789abcdef0123456789abcdef", kit.state.pendingInvite)
        // Ohne Runde gibt es nichts abzugleichen.
        assertEquals(emptyList<String>(), kit.session.calls)
    }

    // --- Abgleichen ---

    @Test
    fun `nach dem Anlegen steht die Runde im Zustand, mit den Spielern des Servers`() = runTest {
        val kit = linked()

        assertEquals("Stammtisch", kit.state.link?.roundName)
        assertEquals(4, kit.state.players.size)
        assertEquals(0, kit.state.pending)
        assertNull(kit.state.problem)
        assertFalse(kit.state.syncing)
        assertFalse(kit.state.needsSignIn)
    }

    @Test
    fun `eine Aenderung geht nach einer halben Sekunde hoch, mehrere kurz hintereinander zusammen`() = runTest {
        val kit = linked()
        val evening = kit.evenings.evenings.first().single().id

        kit.addGame(evening)
        advanceTimeBy(300)
        kit.addGame(evening, won = false)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(emptyList<String>(), kit.session.calls)

        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("head", "saveEvening"), kit.session.calls)
        assertEquals(2, kit.server.evenings(kit.state.link!!.roundId).single().entries.size)
        assertEquals(0, kit.state.pending)
    }

    @Test
    fun `im Vordergrund wird alle vier Sekunden nachgefragt, mit einer einzigen kleinen Anfrage`() = runTest {
        val kit = linked()

        advanceTimeBy(12_500)
        runCurrent()

        assertEquals(listOf("head", "head", "head"), kit.session.calls)
    }

    @Test
    fun `im Hintergrund ruht der Abgleich und beim Zurueckkommen laeuft er sofort`() = runTest {
        val kit = linked()
        kit.coordinator.setForeground(false)
        runCurrent()
        kit.session.calls.clear()

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(emptyList<String>(), kit.session.calls)

        kit.coordinator.setForeground(true)
        runCurrent()
        assertEquals(listOf("head"), kit.session.calls)
    }

    @Test
    fun `was ein anderes Handy eintraegt, kommt beim naechsten Nachfragen an`() = runTest {
        val server = InMemoryRoundServer()
        val kit = linked(server)
        val other = RoundTestKit(backgroundScope, server, userId = "konto-2")
        other.coordinator.start()
        runCurrent()
        other.coordinator.join(kit.session.createInvite(kit.state.link!!.roundId))
        val evening = other.evenings.evenings.first().single().id

        other.addGame(evening)
        other.sync.sync()
        advanceTimeBy(4_500)
        runCurrent()

        assertEquals(1, kit.evenings.evenings.first().single().entries.size)
        assertEquals(2, kit.state.link?.members?.size)
    }

    @Test
    fun `Jetzt abgleichen prueft sofort und vollstaendig`() = runTest {
        val kit = linked()

        kit.coordinator.syncNow()
        runCurrent()

        assertEquals(listOf("head"), kit.session.calls)
        assertEquals(0, kit.state.pending)
    }

    // --- Störungen ---

    @Test
    fun `ohne Netz wartet die Aenderung, und die Versuche kommen mit wachsendem Abstand`() = runTest {
        val kit = linked()
        val evening = kit.evenings.evenings.first().single().id
        kit.session.offline = true

        kit.addGame(evening)
        settle()
        assertEquals(RoundProblem.OFFLINE, kit.state.problem)
        assertEquals(1, kit.state.pending)
        assertEquals(1, kit.session.calls.size)

        // Nach 4, dann 8, dann 16 Sekunden.
        advanceTimeBy(3_500)
        runCurrent()
        assertEquals(2, kit.session.calls.size)
        advanceTimeBy(7_900)
        runCurrent()
        assertEquals(2, kit.session.calls.size)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(3, kit.session.calls.size)
        advanceTimeBy(16_100)
        runCurrent()
        assertEquals(4, kit.session.calls.size)

        kit.session.offline = false
        advanceTimeBy(32_100)
        runCurrent()
        assertNull(kit.state.problem)
        assertEquals(0, kit.state.pending)
        assertEquals(1, kit.server.evenings(kit.state.link!!.roundId).single().entries.size)
    }

    @Test
    fun `geht die App mit Wartendem in den Hintergrund, uebernimmt der Auftrag im Hintergrund`() = runTest {
        val kit = linked()
        val evening = kit.evenings.evenings.first().single().id

        kit.coordinator.setForeground(false)
        assertEquals(0, kit.backgroundRequests)

        kit.coordinator.setForeground(true)
        kit.session.offline = true
        kit.addGame(evening)
        settle()
        kit.coordinator.setForeground(false)
        assertEquals(1, kit.backgroundRequests)

        kit.session.offline = false
        val outcome = kit.coordinator.syncInBackground()
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), outcome)
        assertNull(kit.state.problem)
    }

    @Test
    fun `eine abgelaufene Anmeldung wird gemeldet und nach dem Anmelden laeuft es weiter`() = runTest {
        val kit = linked()
        val evening = kit.evenings.evenings.first().single().id
        kit.session.signedOut = true

        kit.addGame(evening)
        settle()
        assertEquals(RoundProblem.SIGNED_OUT, kit.state.problem)
        assertTrue(kit.state.needsSignIn)

        kit.session.signedOut = false
        assertTrue(kit.coordinator.onLink("schafkopftracker://auth-callback?code=abc"))
        settle()
        assertNull(kit.state.problem)
        assertFalse(kit.state.needsSignIn)
        assertEquals(0, kit.state.pending)
        assertTrue(kit.state.openRound)
    }

    @Test
    fun `ein Fehler des Servers ist kein Grund, sich abzumelden`() = runTest {
        val kit = linked()
        val evening = kit.evenings.evenings.first().single().id
        kit.server.removeMember(kit.state.link!!.roundId, "niemand")
        kit.session.loseNextAnswer = true

        kit.addGame(evening)
        settle()

        // Die Antwort ging verloren: wie ohne Netz. Der nächste Versuch räumt auf, ohne doppelt hochzuladen.
        assertEquals(RoundProblem.OFFLINE, kit.state.problem)
        advanceTimeBy(4_100)
        runCurrent()
        assertNull(kit.state.problem)
        assertEquals(0, kit.state.pending)
        assertEquals(1, kit.server.evenings(kit.state.link!!.roundId).single().entries.size)
    }

    @Test
    fun `gibt es die Runde nicht mehr, wird das Geraet getrennt und die Daten bleiben`() = runTest {
        val kit = linked()
        kit.server.removeMember(kit.state.link!!.roundId, kit.userId)

        advanceTimeBy(4_100)
        runCurrent()

        assertNull(kit.state.link)
        assertEquals(RoundMessage.DETACHED, kit.state.message)
        assertEquals(emptyMap<PlayerId, Any>(), kit.state.players)
        assertEquals(1, kit.evenings.evenings.first().size)
        kit.coordinator.clearMessage()
        assertNull(kit.state.message)
    }

    @Test
    fun `Hinweise aus dem Abgleich sammeln sich, bis man sie ausblendet`() = runTest {
        val kit = linked()
        kit.players.save(Player(PlayerId("gast"), "spieler 1", colorIndex = 4))
        settle()

        assertEquals(listOf(SyncNotice(PlayerId("gast"), "spieler 1", RoundError.NAME_TAKEN, nowName = null)), kit.state.notices)

        kit.coordinator.clearNotices()
        assertEquals(emptyList<SyncNotice>(), kit.state.notices)
    }

    // --- Links ---

    @Test
    fun `die Rueckkehr aus der Anmeldung meldet an und oeffnet den Bildschirm`() = runTest {
        val kit = RoundTestKit(backgroundScope, signedIn = false)
        kit.coordinator.start()
        runCurrent()
        assertNull(kit.state.account)
        assertEquals("https://anmeldung.example/authorize", kit.coordinator.beginSignIn())

        assertTrue(kit.coordinator.onLink("schafkopftracker://auth-callback?code=abc"))
        runCurrent()

        assertEquals("konto-1", kit.state.account?.userId)
        assertTrue(kit.state.openRound)
        assertNull(kit.state.message)
        // Der Bildschirm ist offen: Der Wunsch ist verbraucht.
        kit.coordinator.roundOpened()
        assertFalse(kit.state.openRound)
    }

    @Test
    fun `waehrend die Anmeldung eingeloest wird, ist das zu sehen`() = runTest {
        val kit = RoundTestKit(backgroundScope, signedIn = false)
        kit.coordinator.start()
        runCurrent()
        val exchange = CompletableDeferred<Unit>()
        kit.account.exchange = exchange

        kit.coordinator.onLink("schafkopftracker://auth-callback?code=abc")
        runCurrent()
        assertTrue(kit.state.signingIn)

        exchange.complete(Unit)
        runCurrent()
        assertFalse(kit.state.signingIn)
        assertEquals("konto-1", kit.state.account?.userId)
    }

    @Test
    fun `kommt dieselbe Rueckkehr ein zweites Mal, gibt es keine Fehlermeldung`() = runTest {
        val kit = RoundTestKit(backgroundScope, signedIn = false)
        kit.coordinator.start()
        runCurrent()
        kit.coordinator.onLink("schafkopftracker://auth-callback?code=abc")
        runCurrent()
        // Eine alte Meldung räumt die geglückte Anmeldung weg.
        assertNull(kit.state.message)

        kit.account.noPendingSignIn = true
        kit.coordinator.onLink("schafkopftracker://auth-callback?code=abc")
        runCurrent()

        assertNull(kit.state.message)
        assertEquals("konto-1", kit.state.account?.userId)
    }

    @Test
    fun `eine geglueckte Anmeldung raeumt eine alte Meldung zur Anmeldung weg`() = runTest {
        val kit = RoundTestKit(backgroundScope, signedIn = false)
        kit.coordinator.start()
        runCurrent()
        kit.coordinator.onLink("schafkopftracker://auth-callback?error=access_denied")
        assertEquals(RoundMessage.SIGN_IN_CANCELLED, kit.state.message)

        kit.coordinator.onLink("schafkopftracker://auth-callback?code=abc")
        runCurrent()

        assertNull(kit.state.message)
    }

    @Test
    fun `eine abgebrochene oder gescheiterte Anmeldung wird gemeldet`() = runTest {
        val kit = RoundTestKit(backgroundScope, signedIn = false)
        kit.coordinator.start()
        runCurrent()

        kit.coordinator.onLink("schafkopftracker://auth-callback?error=access_denied")
        assertEquals(RoundMessage.SIGN_IN_CANCELLED, kit.state.message)
        kit.coordinator.onLink("schafkopftracker://auth-callback?error=server_error")
        assertEquals(RoundMessage.SIGN_IN_FAILED, kit.state.message)

        kit.coordinator.clearMessage()
        kit.account.fail = true
        kit.coordinator.onLink("schafkopftracker://auth-callback?code=abc")
        runCurrent()
        assertEquals(RoundMessage.SIGN_IN_FAILED, kit.state.message)
        assertNull(kit.state.account)
        assertTrue(kit.state.openRound)
        // Ohne Anmeldung ist auch eine zweite Rückkehr ohne begonnene Anmeldung ein Fehler.
        kit.coordinator.clearMessage()
        kit.account.fail = false
        kit.account.noPendingSignIn = true
        kit.coordinator.onLink("schafkopftracker://auth-callback?code=abc")
        runCurrent()
        assertEquals(RoundMessage.SIGN_IN_FAILED, kit.state.message)
    }

    @Test
    fun `meldet sich ein anderes Konto an, bleibt die Runde verbunden und das Konto wird wieder abgemeldet`() = runTest {
        val kit = linked()
        val evening = kit.evenings.evenings.first().single().id
        val round = kit.state.link!!.roundId
        // Die Anmeldung ist abgelaufen; inzwischen wurde ein Spiel eingetragen, das noch wartet.
        kit.session.signedOut = true
        kit.addGame(evening)
        settle()
        assertTrue(kit.state.needsSignIn)
        kit.session.signedOut = false
        kit.session.calls.clear()
        kit.account.next = Account("konto-9", "konto-9@example.org")

        kit.coordinator.onLink("schafkopftracker://auth-callback?code=abc")
        settle()

        // Nichts lief unter dem fremden Konto, und nichts ging verloren.
        assertEquals(round, kit.state.link?.roundId)
        assertEquals(RoundMessage.OTHER_ACCOUNT, kit.state.message)
        assertNull(kit.state.account)
        assertEquals(1, kit.account.signOuts)
        assertEquals(emptyList<String>(), kit.session.calls)
        assertEquals(1, kit.state.pending)

        // Mit dem richtigen Konto geht das wartende Spiel hoch.
        kit.account.next = Account("konto-1", "konto-1@example.org")
        kit.coordinator.onLink("schafkopftracker://auth-callback?code=def")
        settle()
        assertNull(kit.state.message)
        assertEquals(0, kit.state.pending)
        assertEquals(1, kit.server.evenings(round).single().entries.size)
    }

    @Test
    fun `im Hintergrund wird nie unter fremdem Konto abgeglichen`() = runTest {
        val kit = linked()
        kit.account.account.value = Account("konto-9", "konto-9@example.org")
        runCurrent()
        kit.session.calls.clear()

        assertEquals(SyncOutcome.SignedOut, kit.coordinator.syncInBackground())

        assertEquals(emptyList<String>(), kit.session.calls)
        assertNotNull(kit.state.link)
    }

    @Test
    fun `ein Einladungslink wird gemerkt, bis er eingeloest oder verworfen ist`() = runTest {
        val kit = RoundTestKit(backgroundScope)
        kit.coordinator.start()
        runCurrent()
        val token = "0123456789abcdef0123456789abcdef"

        assertTrue(kit.coordinator.onLink("schafkopftracker://einladung/$token"))
        assertEquals(token, kit.state.pendingInvite)
        assertEquals(token, kit.invites.get())
        assertTrue(kit.state.openRound)
        kit.coordinator.roundOpened()

        // Ein Link ohne gültigen Code öffnet den Bildschirm mit einer Meldung; fremde Links gehen die Runde nichts an.
        assertTrue(kit.coordinator.onLink("schafkopftracker://einladung/kaputt"))
        assertEquals(token, kit.state.pendingInvite)
        assertEquals(RoundMessage.INVALID_LINK, kit.state.message)
        assertTrue(kit.state.openRound)
        kit.coordinator.roundOpened()
        assertFalse(kit.coordinator.onLink("https://example.org/irgendwas"))
        assertFalse(kit.coordinator.onLink("io.github.q0ochen.schafkopf:/oauth2redirect?code=x"))
        assertFalse(kit.state.openRound)

        kit.coordinator.rememberInvite(null)
        assertNull(kit.state.pendingInvite)
        assertNull(kit.invites.get())
    }

    @Test
    fun `was beim Start schon wartet, zeigt die Statuszeile gleich`() = runTest {
        val kit = RoundTestKit(backgroundScope)
        val evening = kit.seed()
        kit.connection.create("Stammtisch", kit.userId, emptyList())
        kit.addGame(evening)
        // Das Netz ist langsam: Der erste Abgleich hängt.
        kit.api.gate = CompletableDeferred()

        kit.coordinator.start()
        runCurrent()

        assertTrue(kit.state.syncing)
        assertEquals(1, kit.state.pending)
    }

    @Test
    fun `bricht ein Abgleich ab, steht danach nicht mehr da, dass er laeuft`() = runTest {
        val kit = linked()
        kit.api.gate = CompletableDeferred()
        val job = launch { kit.coordinator.syncInBackground() }
        runCurrent()
        assertTrue(kit.state.syncing)

        job.cancel()
        runCurrent()

        assertFalse(kit.state.syncing)
    }

    // --- Runden ---

    /** Eine Uhr, die der Test stellt. */
    private class MovableClock(var now: Instant = Instant.parse("2026-12-01T18:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    @Test
    fun `einladen nimmt eine eigene Einladung, solange sie juenger als 13 Tage ist`() = runTest {
        val clock = MovableClock()
        val kit = RoundTestKit(backgroundScope, clock = clock)
        kit.seed()
        kit.coordinator.start()
        runCurrent()
        kit.coordinator.create("Stammtisch")
        runCurrent()
        kit.session.calls.clear()

        val first = kit.coordinator.inviteLink()
        clock.now = clock.now.plus(Duration.ofDays(12))
        val again = kit.coordinator.inviteLink()

        assertEquals(first, again)
        assertEquals(1, kit.session.calls.count { it == "createInvite" })
        // Kurz vor dem Ablauf gibt es eine neue; sie gilt wieder 14 Tage.
        clock.now = clock.now.plus(Duration.ofDays(1))
        val fresh = kit.coordinator.inviteLink()
        assertNotEquals(first, fresh)
        assertEquals(2, kit.session.calls.count { it == "createInvite" })
        // Beide Codes lassen sich einlösen, solange sie gelten.
        assertEquals("Stammtisch", kit.coordinator.inviteInfo(InviteLinks.tokenFrom(fresh)!!).roundName)

        // Nach dem Verlassen ist die gemerkte Einladung vergessen.
        kit.coordinator.leave()
        assertNull(kit.invites.issued())
    }

    @Test
    fun `eine Runde, die nicht verbunden ist, laesst sich verlassen, ohne dass sich auf dem Geraet etwas aendert`() = runTest {
        val server = InMemoryRoundServer()
        val host = linked(server)
        val token = host.session.createInvite(host.state.link!!.roundId)
        val kit = RoundTestKit(backgroundScope, server, userId = "konto-2")
        kit.seed("eigener-abend")
        kit.coordinator.start()
        runCurrent()
        kit.session.joinRound(token)
        kit.coordinator.refreshRounds()
        val round = kit.state.rounds!!.single()

        kit.coordinator.leaveRound(round)

        // Die Liste ist danach neu gelesen.
        assertEquals(emptyList<Any>(), kit.state.rounds)
        assertNull(kit.state.link)
        assertEquals(listOf("eigener-abend"), kit.evenings.evenings.first().map { it.id.value })
        assertEquals(1, host.session.myRounds().single().memberCount)
    }

    @Test
    fun `beitreten loest die Einladung ein und ersetzt die Daten des Geraets`() = runTest {
        val server = InMemoryRoundServer()
        val first = linked(server)
        val token = first.session.createInvite(first.state.link!!.roundId)
        val kit = RoundTestKit(backgroundScope, server, userId = "konto-2")
        kit.seed("eigener-abend")
        kit.coordinator.start()
        runCurrent()
        kit.coordinator.onLink("schafkopftracker://einladung/$token")

        assertTrue(kit.coordinator.hasOwnData())
        assertEquals("Stammtisch", kit.coordinator.inviteInfo(token).roundName)
        val outcome = kit.coordinator.join(token)
        runCurrent()

        assertTrue(outcome is SyncOutcome.Synced)
        assertNull(kit.state.pendingInvite)
        assertEquals("Stammtisch", kit.state.link?.roundName)
        assertEquals(listOf("abend-1"), kit.evenings.evenings.first().map { it.id.value })
    }

    @Test
    fun `Das bin ich, einladen, verlassen und abmelden`() = runTest {
        val kit = linked()
        val round = kit.state.link!!.roundId

        kit.coordinator.claim(PlayerId("spieler-1"), "Eins")
        runCurrent()
        assertEquals(PlayerId("spieler-1"), kit.state.link?.playerId)
        assertTrue(kit.state.players.getValue(PlayerId("spieler-1")).nameLocked)

        val link = kit.coordinator.inviteLink()
        assertTrue(link, link.startsWith("https://example.org/app/#/einladung/"))
        assertEquals(1, kit.state.rounds?.size)
        assertEquals(0, kit.coordinator.pendingCount())

        kit.coordinator.leave()
        runCurrent()
        assertNull(kit.state.link)
        assertNull(kit.session.head(round))
        assertNotNull(kit.state.account)

        kit.coordinator.signOut()
        runCurrent()
        assertNull(kit.state.account)
        assertEquals(1, kit.account.signOuts)
        assertEquals(1, kit.evenings.evenings.first().size)
    }

    @Test
    fun `ohne Anmeldung laesst sich keine Runde anlegen`() = runTest {
        val kit = RoundTestKit(backgroundScope, signedIn = false)
        kit.coordinator.start()
        runCurrent()

        val error = runCatching { kit.coordinator.create("Stammtisch") }.exceptionOrNull()

        assertEquals("SIGNED_OUT", error?.message)
        assertNull(kit.state.link)
    }

    // --- Runden des Kontos und Wechseln (docs/ARCHITEKTUR.md 8.7 und 8.9) ---

    @Test
    fun `die Runden des Kontos stehen im Zustand - eine gescheiterte Lesung behaelt die alte Liste`() = runTest {
        val kit = linked()
        assertEquals(listOf("Stammtisch"), kit.state.rounds?.map { it.name })
        assertFalse(kit.state.roundsFailed)

        kit.session.offline = true
        kit.coordinator.refreshRounds()
        assertEquals(listOf("Stammtisch"), kit.state.rounds?.map { it.name })
        assertTrue(kit.state.roundsFailed)

        kit.session.offline = false
        kit.coordinator.refreshRounds()
        assertFalse(kit.state.roundsFailed)

        // Ohne Anmeldung gibt es keine Liste (sie gehörte zum Konto).
        kit.coordinator.signOut()
        runCurrent()
        assertNull(kit.state.rounds)
        kit.coordinator.refreshRounds()
        assertNull(kit.state.rounds)
    }

    @Test
    fun `vor dem Wechseln wird vollstaendig abgeglichen und gezaehlt, was danach noch wartet`() = runTest {
        val kit = linked()
        kit.addGame(io.github.q0ochen.schafkopf.domain.model.EveningId("abend-1"))
        assertEquals(1, kit.coordinator.pendingCount())

        assertEquals(SwitchFrom(kit.state.link!!.roundId, acceptedLoss = 0), kit.coordinator.prepareSwitch())
        assertEquals(1, kit.server.evenings(kit.state.link!!.roundId).single().entries.size)

        // Ohne Netz: ein Fehler, keine Zahl.
        kit.session.offline = true
        val error = runCatching { kit.coordinator.prepareSwitch() }.exceptionOrNull()
        assertEquals(RoundError.OFFLINE, (error as RoundException).error)
        kit.session.offline = false

        // Gibt es die Runde nicht mehr: keine Rückfrage, sondern die Meldung.
        kit.server.removeMember(kit.state.link!!.roundId, kit.userId)
        assertNull(kit.coordinator.prepareSwitch())
        runCurrent()
        assertEquals(RoundMessage.DETACHED, kit.state.message)
        assertNull(kit.state.link)
        assertNull(kit.coordinator.prepareSwitch())
    }

    @Test
    fun `wechseln, beitreten und neu anlegen lesen danach die Runden neu`() = runTest {
        val server = InMemoryRoundServer()
        val host = linked(server)
        val stammtisch = host.state.link!!.roundId
        val kit = RoundTestKit(backgroundScope, server, userId = "konto-2")
        kit.coordinator.start()
        runCurrent()
        kit.coordinator.create("Eigene")
        runCurrent()
        assertEquals(listOf("Eigene"), kit.state.rounds?.map { it.name })

        kit.coordinator.join(host.session.createInvite(stammtisch))
        runCurrent()
        assertEquals(stammtisch, kit.state.link?.roundId)
        // Der Server im Speicher ordnet nach dem Anlegen der Runden, der echte nach dem Beitritt: hier nur die Namen.
        assertEquals(listOf("Eigene", "Stammtisch"), kit.state.rounds?.map { it.name }?.sorted())

        kit.coordinator.use(kit.state.rounds!!.single { it.name == "Eigene" })
        runCurrent()
        assertEquals("Eigene", kit.state.link?.roundName)

        kit.coordinator.create("Dritte")
        runCurrent()
        assertEquals("Dritte", kit.state.link?.roundName)
        assertEquals(listOf("Dritte", "Eigene", "Stammtisch"), kit.state.rounds?.map { it.name }?.sorted())
        // Die anderen Runden haben dabei nichts verloren.
        assertEquals(listOf("abend-1"), server.evenings(stammtisch).map { it.id.value })
    }

    @Test
    fun `nach einem Wechsel gelten die Hinweise der bisherigen Runde nicht mehr`() = runTest {
        val server = InMemoryRoundServer()
        val kit = linked(server)
        kit.players.save(Player(PlayerId("gast"), "spieler 1", colorIndex = 4))
        settle()
        assertEquals(1, kit.state.notices.size)
        val other = server.sessionFor("konto-2")
        val klub = other.createRound("Klub")
        kit.session.joinRound(other.createInvite(klub))
        kit.coordinator.refreshRounds()
        val from = kit.coordinator.prepareSwitch()!!
        assertEquals(1, kit.state.notices.size)

        kit.coordinator.use(kit.state.rounds!!.single { it.id == klub }, from)
        runCurrent()

        assertEquals(klub, kit.state.link?.roundId)
        assertEquals(emptyList<SyncNotice>(), kit.state.notices)
    }

    @Test
    fun `eine Einladung bleibt, wenn der Beitritt nicht zum Wechsel fuehrte`() = runTest {
        val server = InMemoryRoundServer()
        val kit = linked(server)
        val other = server.sessionFor("konto-2")
        val klub = other.createRound("Klub")
        val token = other.createInvite(klub)
        kit.coordinator.onLink("schafkopftracker://einladung/$token")
        // Die verbundene Runde gibt es inzwischen nicht mehr: Beim Beitreten wird nicht gewechselt.
        server.removeMember(kit.state.link!!.roundId, kit.userId)

        assertEquals(SyncOutcome.Detached, kit.coordinator.join(token))
        runCurrent()

        assertEquals(token, kit.state.pendingInvite)
        assertNull(kit.state.link)
        assertEquals(RoundMessage.DETACHED, kit.state.message)
    }

    @Test
    fun `ein vergebener Name wird gemeldet, und die Liste ist danach frisch`() = runTest {
        val kit = linked()
        val error = runCatching { kit.coordinator.create("STAMMTISCH") }.exceptionOrNull()

        assertEquals(RoundError.NAME_TAKEN, (error as RoundException).error)
        assertEquals("Stammtisch", kit.state.link?.roundName)
        assertEquals(listOf("Stammtisch"), kit.state.rounds?.map { it.name })
    }
}
