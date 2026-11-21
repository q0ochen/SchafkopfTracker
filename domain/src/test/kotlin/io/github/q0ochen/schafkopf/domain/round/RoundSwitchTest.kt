package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.EveningId
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Zwischen Runden wechseln, einer weiteren beitreten und eine neue anlegen, während das Gerät schon verbunden ist
 * (docs/ARCHITEKTUR.md, 8.7, seit dem 21.11.2026), dazu die eindeutigen Namen. Dabei darf nie verloren gehen, was ein
 * Gerät schon eingetragen hat: Vor dem Wechsel wird abgeglichen, und die Abende der bisherigen Runde bleiben auf dem
 * Server.
 */
class RoundSwitchTest {

    private val clock = TestClock()
    private val server = InMemoryRoundServer(clock)

    /** Handy eins (Konto 1): verbunden mit dem „Stammtisch“. */
    private val one = Device(server, "konto-1", clock)

    /** Handy zwei (Konto 2): legt den „Klub“ an. */
    private val two = Device(server, "konto-2", clock)

    /** Handy drei (Konto 3): spielt im „Stammtisch“ mit. */
    private val three = Device(server, "konto-3", clock)

    private val abendA = EveningId("abend-a")
    private val abendB = EveningId("abend-b")

    /** Der Fehler, mit dem [block] scheitert; `null`, wenn er durchläuft. */
    private suspend fun errorOf(block: suspend () -> Unit): RoundError? = try {
        block()
        null
    } catch (e: RoundException) {
        e.error
    }

    /**
     * Zwei Runden. Den „Stammtisch“ legt Handy eins mit vier Spielern und einem Abend an. Den „Klub“ legt Handy zwei mit
     * einem eigenen Abend an; Konto 1 tritt ihm über den Server bei (wie von einem anderen Gerät aus), Handy eins bleibt
     * dabei mit dem „Stammtisch“ verbunden.
     */
    private suspend fun twoRounds(): Pair<RoundId, RoundId> {
        one.firstStart()
        listOf("Nord", "Ost", "Süd", "West").forEachIndexed { index, name -> one.players.save(PLACEHOLDERS[index].copy(name = name)) }
        one.startEvening(abendA.value)
        clock.advance(60)
        one.addGame(abendA)
        one.connection.create("Stammtisch", one.userId, PLACEHOLDERS)
        val stammtisch = one.roundId()

        two.firstStart()
        two.startEvening(abendB.value)
        clock.advance(60)
        two.addGame(abendB, won = false)
        two.connection.create("Klub", two.userId, PLACEHOLDERS)
        val klub = two.roundId()
        one.api.joinRound(two.api.createInvite(klub))
        one.session.calls.clear()
        return stammtisch to klub
    }

    private suspend fun summary(device: Device, round: RoundId): RoundSummary = device.api.myRounds().single { it.id == round }

    private fun onServer(round: RoundId): List<Evening> = server.evenings(round).sortedBy { it.id.value }

    private suspend fun assertShows(device: Device, round: RoundId) {
        assertEquals(round, device.roundId())
        assertEquals(onServer(round), device.allEvenings(), "Abende auf ${device.userId}")
        assertEquals(server.players(round).map { it.toPlayer() }, device.allPlayers(), "Spieler auf ${device.userId}")
        assertEquals(0, device.sync.pendingCount(), "Wartendes auf ${device.userId}")
    }

    // --- Wechseln ---

    @Test
    fun `wechseln - das Geraet haelt danach die Daten der anderen Runde, die bisherige bleibt auf dem Server`() = runTest {
        val (stammtisch, klub) = twoRounds()
        val before = onServer(stammtisch)

        val outcome = one.connection.use(summary(one, klub), one.userId)

        assertTrue(outcome is SyncOutcome.Synced, "$outcome")
        assertShows(one, klub)
        assertEquals(listOf(abendB), one.allEvenings().map { it.id })
        assertFalse(checkNotNull(one.link()).isCreator)
        assertEquals(before, onServer(stammtisch))
        assertEquals(4, server.players(stammtisch).size)

        // Und zurück: Alles ist wieder da.
        assertTrue(one.connection.use(summary(one, stammtisch), one.userId) is SyncOutcome.Synced)
        assertShows(one, stammtisch)
        assertEquals(before, one.allEvenings())
        assertTrue(checkNotNull(one.link()).isCreator)
        assertEquals(listOf("Nord", "Ost", "Süd", "West"), one.allPlayers().map { it.name })
    }

    @Test
    fun `vor dem Wechseln geht hoch, was auf dem Geraet noch wartet`() = runTest {
        val (stammtisch, klub) = twoRounds()
        clock.advance(60)
        one.addGame(abendA, laufende = 3)
        assertEquals(1, one.sync.pendingCount())
        val target = summary(one, klub)
        one.session.calls.clear()

        one.connection.use(target, one.userId)

        assertEquals(2, server.evenings(stammtisch).single().entries.size)
        assertShows(one, klub)
    }

    @Test
    fun `ohne Netz bleibt beim Wechseln alles, wie es ist`() = runTest {
        val (stammtisch, klub) = twoRounds()
        clock.advance(60)
        one.addGame(abendA)
        val local = one.allEvenings()
        val target = summary(one, klub)
        one.session.offline = true

        assertEquals(RoundError.OFFLINE, errorOf { one.connection.use(target, one.userId) })

        assertEquals(stammtisch, one.roundId())
        assertEquals(local, one.allEvenings())
        assertEquals(1, one.sync.pendingCount())
        // Mit Netz klappt es, und die Änderung von vorhin kommt dabei im „Stammtisch“ an.
        one.session.offline = false
        assertTrue(one.connection.use(target, one.userId) is SyncOutcome.Synced)
        assertEquals(local, onServer(stammtisch))
    }

    @Test
    fun `ist die Anmeldung abgelaufen oder antwortet der Server mit einem Fehler, wird nicht gewechselt`() = runTest {
        val (stammtisch, klub) = twoRounds()
        val target = summary(one, klub)
        val local = one.allEvenings()

        one.session.signedOut = true
        assertEquals(RoundError.SIGNED_OUT, errorOf { one.connection.use(target, one.userId) })
        assertEquals(stammtisch, one.roundId())
        assertEquals(local, one.allEvenings())
        one.session.signedOut = false

        // Die Zielrunde gibt es nicht (mehr) für dieses Konto: Der Abgleich davor lief, gewechselt wird trotzdem nicht.
        server.removeMember(klub, one.userId)
        assertEquals(RoundError.NOT_MEMBER, errorOf { one.connection.use(target, one.userId) })
        assertEquals(stammtisch, one.roundId())
        assertEquals(local, one.allEvenings())
    }

    @Test
    fun `gibt es die bisherige Runde nicht mehr, wird nicht gewechselt und die Daten bleiben als Kopie`() = runTest {
        val (stammtisch, klub) = twoRounds()
        val local = one.allEvenings()
        server.removeMember(stammtisch, one.userId)

        assertEquals(SyncOutcome.Detached, one.connection.use(summary(one, klub), one.userId))

        assertNull(one.link())
        assertEquals(local, one.allEvenings())
        assertEquals(listOf("Nord", "Ost", "Süd", "West"), one.allPlayers().map { it.name })
        assertFalse("joinRound" in one.session.calls)
    }

    @Test
    fun `die verbundene Runde noch einmal waehlen gleicht nur ab`() = runTest {
        val (stammtisch, _) = twoRounds()
        val local = one.allEvenings()

        assertTrue(one.connection.use(summary(one, stammtisch), one.userId) is SyncOutcome.Synced)

        assertShows(one, stammtisch)
        assertEquals(local, one.allEvenings())
    }

    @Test
    fun `was der Server nicht annimmt, wartet auch nach dem Abgleich - und geht beim Wechseln verloren`() = runTest {
        val (stammtisch, klub) = twoRounds()
        // Ein Abend, den der Server ablehnt (zu groß); die Oberfläche nennt ihn in ihrer Rückfrage.
        val huge = Evening(
            id = EveningId("riesig"),
            startedAt = clock.instant(),
            seating = SEATING,
            firstDealer = P1,
            rules = one.allEvenings().single().rules,
            entries = List(InMemoryRoundServer.MAX_ENTRIES + 1) { game("g$it", seconds = it.toLong()) },
        )
        one.evenings.save(huge)
        val outcome = one.synced()
        assertEquals(1, outcome.refused)
        assertEquals(1, one.sync.pendingCount())
        val local = one.allEvenings()

        // Ohne Zustimmung (die Rückfrage nannte 0) wird nicht gewechselt.
        assertEquals(RoundError.CHANGED_MEANWHILE, errorOf { one.connection.use(summary(one, klub), one.userId) })
        assertEquals(stammtisch, one.roundId())
        assertEquals(local, one.allEvenings())

        one.connection.use(summary(one, klub), one.userId, SwitchFrom(stammtisch, acceptedLoss = 1))

        assertShows(one, klub)
        assertEquals(listOf(abendA), onServer(stammtisch).map { it.id })
    }

    @Test
    fun `ein Spiel, das mitten im Wechsel eingetragen wird, nimmt der letzte Abgleich mit`() = runTest {
        val (stammtisch, klub) = twoRounds()
        val target = summary(one, klub)
        // Während die App nach dem Kopf der Zielrunde fragt, trägt jemand auf diesem Handy noch ein Spiel ein.
        one.api.afterHead = { round ->
            if (round == klub) {
                one.api.afterHead = null
                clock.advance(60)
                one.addGame(abendA, laufende = 4)
            }
        }

        assertTrue(one.connection.use(target, one.userId, SwitchFrom(stammtisch)) is SyncOutcome.Synced)

        assertEquals(2, server.evenings(stammtisch).single().entries.size)
        assertShows(one, klub)
    }

    @Test
    fun `kommt der letzte Abgleich nicht mehr durch, bleibt alles, wie es ist`() = runTest {
        val (stammtisch, klub) = twoRounds()
        val target = summary(one, klub)
        one.api.afterHead = { round ->
            if (round == klub) {
                one.api.afterHead = null
                clock.advance(60)
                one.addGame(abendA)
                one.session.offline = true
            }
        }

        assertEquals(RoundError.OFFLINE, errorOf { one.connection.use(target, one.userId, SwitchFrom(stammtisch)) })

        assertEquals(stammtisch, one.roundId())
        assertEquals(2, one.allEvenings().single().entries.size)
        assertEquals(1, one.sync.pendingCount())
    }

    @Test
    fun `haengt das Geraet nicht mehr an der Runde der Rueckfrage, wird nicht gewechselt`() = runTest {
        val (stammtisch, klub) = twoRounds()
        val local = one.allEvenings()

        // Die Rückfrage ging von einer anderen Runde aus.
        assertEquals(RoundError.CHANGED_MEANWHILE, errorOf { one.connection.use(summary(one, klub), one.userId, SwitchFrom(klub)) })
        assertEquals(stammtisch, one.roundId())
        // Inzwischen getrennt: Die Kopie wird nicht ohne die Rückfrage „Daten dieses Geräts ersetzen?“ geleert.
        one.connection.disconnect()
        assertEquals(
            RoundError.CHANGED_MEANWHILE,
            errorOf { one.connection.use(summary(one, klub), one.userId, SwitchFrom(stammtisch)) },
        )
        assertEquals(
            RoundError.CHANGED_MEANWHILE,
            errorOf { one.connection.create("Neu", one.userId, PLACEHOLDERS, SwitchFrom(stammtisch)) },
        )
        assertNull(one.link())
        assertEquals(local, one.allEvenings())
    }

    @Test
    fun `wird der Aufrufer abgebrochen, laeuft der Wechsel trotzdem am Stueck zu Ende`() = runTest {
        val (_, klub) = twoRounds()
        val target = summary(one, klub)
        val job = launch { one.connection.use(target, one.userId) }
        // Abgebrochen, sobald der Kopf der Zielrunde da ist: Danach leert und verbindet der Wechsel ohne Unterbrechung.
        one.api.afterHead = { round -> if (round == klub) job.cancel() }

        job.join()

        // Verbunden und geleert ist am Stück; die Daten des Klubs holt der nächste Abgleich (der abgebrochene kam nicht mehr).
        assertEquals(klub, one.roundId())
        assertEquals(emptyList<Evening>(), one.allEvenings())
        one.synced()
        assertShows(one, klub)
    }

    @Test
    fun `wer zurueckwechselt, sieht, was die anderen inzwischen eingetragen haben`() = runTest {
        val (stammtisch, klub) = twoRounds()
        three.connection.join(one.api.createInvite(stammtisch), three.userId)
        one.connection.use(summary(one, klub), one.userId)

        clock.advance(60)
        three.addGame(abendA)
        three.addGame(abendA, won = false)
        three.synced()
        one.synced()
        assertShows(one, klub)

        one.connection.use(summary(one, stammtisch), one.userId)

        assertShows(one, stammtisch)
        assertEquals(3, one.allEvenings().single().entries.size)
        assertEquals(three.allEvenings(), one.allEvenings())
    }

    // --- Beitreten, während das Gerät verbunden ist ---

    @Test
    fun `einer weiteren Runde beitreten - erst abgleichen, dann wechseln`() = runTest {
        val (stammtisch, _) = twoRounds()
        three.firstStart()
        three.startEvening("abend-c")
        three.connection.create("Freitag", three.userId, PLACEHOLDERS)
        val freitag = three.roundId()
        clock.advance(60)
        one.addGame(abendA)

        val outcome = one.connection.join(three.api.createInvite(freitag), one.userId)

        assertTrue(outcome is SyncOutcome.Synced, "$outcome")
        assertShows(one, freitag)
        assertEquals(2, server.evenings(stammtisch).single().entries.size)
        assertEquals(listOf("Stammtisch", "Klub", "Freitag"), one.api.myRounds().map { it.name })
    }

    @Test
    fun `eine Einladung in die verbundene Runde gleicht nur ab`() = runTest {
        val (stammtisch, _) = twoRounds()
        val local = one.allEvenings()

        assertTrue(one.connection.join(one.api.createInvite(stammtisch), one.userId) is SyncOutcome.Synced)

        assertShows(one, stammtisch)
        assertEquals(local, one.allEvenings())
        assertFalse("joinRound" in one.session.calls)
    }

    @Test
    fun `eine ungueltige Einladung aendert nichts, auch nicht an der verbundenen Runde`() = runTest {
        val (stammtisch, _) = twoRounds()
        val local = one.allEvenings()

        assertEquals(RoundError.INVALID_INVITE, errorOf { one.connection.join("0".repeat(32), one.userId) })

        assertEquals(stammtisch, one.roundId())
        assertEquals(local, one.allEvenings())
    }

    // --- Anlegen, während das Gerät verbunden ist ---

    @Test
    fun `neue Runde anlegen, waehrend das Geraet verbunden ist - sie beginnt leer, die bisherige behaelt alles`() = runTest {
        val (stammtisch, _) = twoRounds()
        clock.advance(60)
        one.addGame(abendA)
        val before = one.allEvenings()
        val players = server.players(stammtisch)

        val outcome = one.connection.create("  Neue   Runde ", one.userId, PLACEHOLDERS)

        assertTrue(outcome is SyncOutcome.Synced, "$outcome")
        val neu = one.roundId()
        assertEquals("Neue Runde", one.link()?.roundName)
        assertTrue(checkNotNull(one.link()).isCreator)
        assertEquals(emptyList<Evening>(), one.allEvenings())
        assertEquals(emptyList<Any>(), one.allPlayers())
        assertEquals(emptyList<Evening>(), server.evenings(neu))
        assertEquals(emptyList<RemotePlayer>(), server.players(neu))
        assertEquals(before, onServer(stammtisch))
        assertEquals(players, server.players(stammtisch))
        assertEquals(listOf("Stammtisch", "Klub", "Neue Runde"), one.api.myRounds().map { it.name })
    }

    @Test
    fun `eine neue Runde mit dem Namen einer Runde des Kontos gibt es nicht - das Geraet bleibt, wie es ist`() = runTest {
        val (stammtisch, _) = twoRounds()
        val local = one.allEvenings()

        assertEquals(RoundError.NAME_TAKEN, errorOf { one.connection.create(" stammTISCH ", one.userId, PLACEHOLDERS) })
        // Auch der Name einer beigetretenen Runde ist vergeben.
        assertEquals(RoundError.NAME_TAKEN, errorOf { one.connection.create("klub", one.userId, PLACEHOLDERS) })

        assertEquals(stammtisch, one.roundId())
        assertEquals(local, one.allEvenings())
        assertEquals(2, one.api.myRounds().size)
        assertFalse("createRound" in one.session.calls)
    }

    @Test
    fun `ohne Netz laesst sich keine neue Runde anlegen - das Geraet bleibt verbunden`() = runTest {
        val (stammtisch, _) = twoRounds()
        val local = one.allEvenings()
        one.session.offline = true

        assertEquals(RoundError.OFFLINE, errorOf { one.connection.create("Neu", one.userId, PLACEHOLDERS) })

        assertEquals(stammtisch, one.roundId())
        assertEquals(local, one.allEvenings())
    }

    @Test
    fun `die verbundene, noch leere Runde wird nie wiederverwendet`() = runTest {
        // Eine Runde, deren erster Abgleich nicht durchkam, hat noch rev = 1 und sähe aus wie eine verwaiste leere Runde.
        // Solange das Gerät an ihr hängt, ist ihr Name trotzdem vergeben.
        one.api.afterHead = {
            one.api.afterHead = null
            one.session.offline = true
        }
        assertEquals(SyncOutcome.Offline, one.connection.create("Leer", one.userId, PLACEHOLDERS))
        one.session.offline = false
        val leer = one.roundId()
        assertTrue(RoundNames.reusable(summary(one, leer)))

        assertEquals(RoundError.NAME_TAKEN, errorOf { one.connection.create("leer", one.userId, PLACEHOLDERS) })
        assertEquals(leer, one.roundId())
    }

    @Test
    fun `ohne Runde scheitert das Anlegen mit einem vergebenen Namen, ohne etwas zu aendern`() = runTest {
        twoRounds()
        // Ein zweites Handy desselben Kontos, noch ohne Runde, mit eigenen Daten.
        val other = Device(server, one.userId, clock)
        other.firstStart()
        other.startEvening("eigener")

        assertEquals(RoundError.NAME_TAKEN, errorOf { other.connection.create("Klub", other.userId, PLACEHOLDERS) })

        assertNull(other.link())
        assertEquals(listOf("eigener"), other.allEvenings().map { it.id.value })
        assertEquals(PLACEHOLDERS, other.allPlayers())
    }

    // --- Mitglieder in der Übersicht ---

    @Test
    fun `die Uebersicht nennt die Namen der Mitglieder`() = runTest {
        val (stammtisch, klub) = twoRounds()
        one.connection.claim(P1, "Nord")
        three.connection.join(one.api.createInvite(stammtisch), three.userId)
        three.connection.claim(null, "anton")
        one.synced()

        assertEquals(listOf("anton", "Nord"), summary(one, stammtisch).memberNames)
        assertEquals(emptyList<String>(), summary(one, klub).memberNames)
        assertEquals(summary(one, stammtisch).memberNames, summary(three, stammtisch).memberNames)

        // Wer die Runde verlässt, fehlt.
        three.connection.leave()
        assertEquals(listOf("Nord"), summary(one, stammtisch).memberNames)
    }
}
