package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Der Abgleich mit der gemeinsamen Runde (docs/ARCHITEKTUR.md, Abschnitt 8.6): mehrere nachgestellte Handys an einem
 * Server im Speicher, mit Funklöchern, verlorenen Antworten und gleichzeitigen Eingaben.
 */
class RoundSyncTest {

    private val clock = TestClock()
    private val server = InMemoryRoundServer(clock)
    private val one = Device(server, "konto-1", clock)
    private val two = Device(server, "konto-2", clock)
    private val three = Device(server, "konto-3", clock)
    private val abend = EveningId("abend-1")

    /** Die Runde des Tests: Handy eins legt sie mit vier Spielern und einem Abend an, Handy zwei tritt bei. */
    private suspend fun round(): RoundId {
        one.firstStart()
        listOf("Nord", "Ost", "Süd", "West").forEachIndexed { index, name -> one.players.save(PLACEHOLDERS[index].copy(name = name)) }
        one.startEvening(abend.value)
        clock.advance(60)
        one.addGame(abend)
        one.connection.create("Stammtisch", one.userId, PLACEHOLDERS)
        join(two)
        one.session.calls.clear()
        two.session.calls.clear()
        return one.roundId()
    }

    private suspend fun join(device: Device) {
        val outcome = device.connection.join(one.api.createInvite(one.roundId()), device.userId)
        check(outcome is SyncOutcome.Synced) { "Beitritt von ${device.userId}: $outcome" }
        one.synced()
    }

    /** Der Fehler, mit dem [block] scheitert; `null`, wenn er durchläuft. */
    private suspend fun errorOf(block: suspend () -> Unit): RoundError? = try {
        block()
        null
    } catch (e: RoundException) {
        e.error
    }

    private suspend fun assertSameEverywhere(round: RoundId, vararg devices: Device) {
        val onServer = server.evenings(round).sortedBy { it.id.value }
        for (device in devices) {
            assertEquals(onServer, device.allEvenings(), "Abende auf ${device.userId}")
            assertEquals(server.players(round).map { it.toPlayer() }, device.allPlayers(), "Spieler auf ${device.userId}")
            assertEquals(0, device.sync.pendingCount(), "Wartendes auf ${device.userId}")
            assertEquals(server.rev(round), device.link()?.cursor, "Stand von ${device.userId}")
        }
    }

    // --- Verbinden ---

    @Test
    fun `wer eine Runde anlegt, bringt Spieler, Abende und Regelwerk mit`() = runTest {
        one.firstStart()
        one.players.save(PLACEHOLDERS[0].copy(name = "Nord"))
        one.settings.update { it.copy(defaultRules = RuleSet.sauspiel()) }
        one.startEvening(abend.value)
        one.addGame(abend)

        val outcome = one.connection.create("  Stammtisch ", one.userId, PLACEHOLDERS)

        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 6, pending = 0), outcome)
        val round = one.roundId()
        assertEquals(listOf("Nord", "Spieler 2", "Spieler 3", "Spieler 4"), server.players(round).map { it.name })
        assertEquals(listOf(1, 2, 3, 4), server.players(round).map { it.position })
        assertEquals(one.allEvenings(), server.evenings(round))
        assertEquals(RuleSet.sauspiel(), one.api.head(round)?.rules)
        assertEquals(
            RoundLink(
                round, "Stammtisch", one.userId, isCreator = true, cursor = 7, rulesBase = RuleSet.sauspiel(),
                members = listOf(RoundMember(one.userId, null)),
            ),
            one.link(),
        )
        assertEquals(
            listOf(
                "myRounds", "createRound", "head", "members", "playersSince", "eveningsSince",
                "savePlayer", "savePlayer", "savePlayer", "savePlayer", "saveRules", "saveEvening", "head",
            ),
            one.session.calls,
        )
    }

    @Test
    fun `geht die Antwort auf das Anlegen verloren, entsteht beim zweiten Versuch keine zweite Runde`() = runTest {
        one.firstStart()
        one.session.loseNextAnswer = true

        assertEquals(RoundError.OFFLINE, errorOf { one.connection.create("Stammtisch", one.userId, PLACEHOLDERS) })

        assertNull(one.link())
        assertEquals(1, one.api.myRounds().size)
        // Der zweite Versuch nimmt die leere Runde vom ersten, auch mit anderer Groß- und Kleinschreibung.
        assertTrue(one.connection.create("  STAMMTISCH ", one.userId, PLACEHOLDERS) is SyncOutcome.Synced)
        assertEquals(listOf(one.roundId()), one.api.myRounds().map { it.id })
        assertEquals("Stammtisch", one.link()?.roundName)
        // Eine Runde, in der schon etwas liegt, wird nie wiederverwendet; ihren Namen gibt es dann kein zweites Mal.
        one.connection.disconnect()
        assertEquals(RoundError.NAME_TAKEN, errorOf { one.connection.create("Stammtisch", one.userId, PLACEHOLDERS) })
        assertNull(one.link())
        assertEquals(1, one.api.myRounds().size)
        // Eine leere Runde mit anderem Namen wird ebenso wenig wiederverwendet.
        one.session.loseNextAnswer = true
        assertEquals(RoundError.OFFLINE, errorOf { one.connection.create("Leer", one.userId, PLACEHOLDERS) })
        assertTrue(one.connection.create("Zweite Runde", one.userId, PLACEHOLDERS) is SyncOutcome.Synced)
        assertEquals(listOf("Stammtisch", "Leer", "Zweite Runde"), one.api.myRounds().map { it.name })
    }

    @Test
    fun `ohne Netz bleibt das Geraet beim Verwenden einer Runde, wie es ist`() = runTest {
        round()
        three.firstStart()
        three.startEvening("eigener")
        three.api.joinRound(one.api.createInvite(one.roundId()))
        val summary = three.api.myRounds().single()
        three.session.offline = true

        assertEquals(RoundError.OFFLINE, errorOf { three.connection.use(summary, three.userId) })

        assertNull(three.link())
        assertEquals(listOf("eigener"), three.allEvenings().map { it.id.value })
        assertEquals(PLACEHOLDERS, three.allPlayers())
        // Mit Netz klappt es, und erst dann werden die Daten des Geräts ersetzt.
        three.session.offline = false
        assertTrue(three.connection.use(summary, three.userId) is SyncOutcome.Synced)
        one.synced()
        assertEquals(one.allEvenings(), three.allEvenings())
        assertEquals(one.allPlayers(), three.allPlayers())
    }

    @Test
    fun `eine Runde, die es nicht mehr gibt, laesst das Geraet unberuehrt`() = runTest {
        three.firstStart()
        three.startEvening("eigener")
        val gone = RoundSummary(RoundId("gibt-es-nicht"), "Verschwunden", rev = 3, memberCount = 1, myPlayerId = null, isCreator = false)

        assertEquals(RoundError.NOT_MEMBER, errorOf { three.connection.use(gone, three.userId) })

        assertNull(three.link())
        assertEquals(1, three.allEvenings().size)
        assertEquals(PLACEHOLDERS, three.allPlayers())
    }

    @Test
    fun `beim Anlegen entfallen vorbelegte Spieler, die niemand umbenannt hat und kein Abend braucht`() = runTest {
        one.firstStart()
        one.players.save(PLACEHOLDERS[2].copy(name = "Süd"))

        one.connection.create("Stammtisch", one.userId, PLACEHOLDERS)

        assertEquals(listOf("Süd"), one.allPlayers().map { it.name })
        assertEquals(listOf("Süd"), server.players(one.roundId()).map { it.name })
    }

    @Test
    fun `wer beitritt, uebernimmt die Daten der Runde und verliert die eigenen`() = runTest {
        two.firstStart()
        two.startEvening("eigener-abend")
        two.settings.update { it.copy(defaultRules = RuleSet.sauspiel()) }
        assertTrue(two.connection.hasOwnData(PLACEHOLDERS))
        one.firstStart()
        one.players.save(PLACEHOLDERS[0].copy(name = "Nord"))
        one.startEvening(abend.value)
        one.connection.create("Stammtisch", one.userId, PLACEHOLDERS)

        val outcome = two.connection.join(one.api.createInvite(one.roundId()), two.userId)

        // Vier Spieler, das Regelwerk und ein Abend kamen vom Server.
        assertEquals(SyncOutcome.Synced(pulled = 6, pushed = 0, pending = 0), outcome)
        assertEquals(one.allEvenings(), two.allEvenings())
        assertEquals(one.allPlayers(), two.allPlayers())
        assertEquals(RuleSet.standard(), two.settings.settings.first().defaultRules)
        assertEquals("Stammtisch", two.link()?.roundName)
        assertFalse(two.link()!!.isCreator)
        one.synced()
        assertEquals(listOf(RoundMember(one.userId, null), RoundMember(two.userId, null)), one.link()?.members)
        assertSameEverywhere(one.roundId(), one, two)
    }

    @Test
    fun `ein unberuehrtes Geraet hat nichts, was beim Beitreten verloren ginge`() = runTest {
        assertFalse(one.connection.hasOwnData(PLACEHOLDERS))
        one.firstStart()
        assertFalse(one.connection.hasOwnData(PLACEHOLDERS))
        one.players.save(PLACEHOLDERS[0].copy(name = "Nord"))
        assertTrue(one.connection.hasOwnData(PLACEHOLDERS))
    }

    @Test
    fun `ein zweites Handy desselben Kontos verwendet die Runde und bekommt alles`() = runTest {
        val round = round()
        one.connection.claim(P1, "Eins")
        val second = Device(server, one.userId, clock)
        second.firstStart()

        val outcome = second.connection.use(second.api.myRounds().single(), second.userId)

        assertTrue(outcome is SyncOutcome.Synced)
        assertEquals(P1, second.link()?.playerId)
        assertTrue(second.link()!!.isCreator)
        assertSameEverywhere(round, one, second)
    }

    // --- Spiele eintragen ---

    @Test
    fun `ein eingetragenes Spiel kommt mit zwei Anfragen hoch und beim anderen Handy an`() = runTest {
        val round = round()
        clock.advance(300)
        one.addGame(abend, won = false)

        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), one.sync.sync())
        assertEquals(listOf("head", "saveEvening"), one.session.calls)

        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 0), two.sync.sync(quick = true))
        assertEquals(listOf("head", "members", "playersSince", "eveningsSince"), two.session.calls)
        assertEquals(2, two.evening(abend)?.entries?.size)
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `das regelmaessige Nachfragen kostet eine Anfrage, solange nichts Neues da ist`() = runTest {
        round()

        assertEquals(SyncOutcome.Unchanged, two.sync.sync(quick = true))
        assertEquals(SyncOutcome.Unchanged, two.sync.sync(quick = true))

        assertEquals(listOf("head", "head"), two.session.calls)
    }

    @Test
    fun `das schnelle Nachfragen prueft nicht, ob hier etwas wartet - der volle Abgleich schon`() = runTest {
        round()
        two.addGame(abend)

        assertEquals(SyncOutcome.Unchanged, two.sync.sync(quick = true))
        assertEquals(1, two.sync.pendingCount())
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), two.sync.sync())
    }

    @Test
    fun `gleichzeitig eingetragene Spiele stehen danach auf beiden Handys in derselben Reihenfolge`() = runTest {
        val round = round()
        clock.advance(100)
        val later = one.addGame(abend, laufende = 3)
        clock.advance(-10)
        val earlier = two.addGame(abend, won = false)

        one.synced()
        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 1, pending = 0), two.sync.sync())
        one.synced()

        assertEquals(listOf(earlier, later), one.evening(abend)!!.entries.drop(1).map { it.id })
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `tragen zwei dasselbe Spiel ein, steht es doppelt da und faellt auf`() = runTest {
        round()
        clock.advance(100)
        one.addGame(abend, won = false, laufende = 4)
        clock.advance(8)
        val second = two.addGame(abend, won = false, laufende = 4)

        one.synced()
        two.synced()
        one.synced()

        val evening = one.evening(abend)!!
        assertEquals(3, evening.entries.size)
        assertEquals(setOf(second), DuplicateCheck.suspects(evening))
        assertEquals(evening, two.evening(abend))
    }

    // --- Ohne Netz ---

    @Test
    fun `ohne Netz wird weiter eingetragen, und spaeter kommt alles zusammen`() = runTest {
        val round = round()
        one.session.offline = true
        clock.advance(100)
        one.addGame(abend)
        clock.advance(100)
        one.addGame(abend)

        assertEquals(SyncOutcome.Offline, one.sync.sync())
        assertEquals(SyncOutcome.Offline, one.sync.sync(quick = true))
        assertEquals(1, one.sync.pendingCount())

        clock.advance(-50)
        two.addGame(abend, won = false)
        two.synced()
        one.session.offline = false
        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 1, pending = 0), one.sync.sync())
        two.synced()

        assertEquals(4, one.evening(abend)?.entries?.size)
        assertEquals(listOf(60L, 160L, 210L, 260L), one.evening(abend)!!.entries.map { it.recordedAt.epochSecond - START.epochSecond })
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `ein ohne Netz begonnener Abend geht spaeter als neuer Abend hoch`() = runTest {
        val round = round()
        two.session.offline = true
        val neu = two.startEvening("abend-2")
        two.addGame(neu)
        assertEquals(SyncOutcome.Offline, two.sync.sync())

        two.session.offline = false
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), two.sync.sync())
        one.synced()

        assertEquals(1, server.eveningRow(round, neu)?.version)
        assertEquals(two.evening(neu), one.evening(neu))
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `geht die Antwort auf das Hochladen verloren, entsteht nichts doppelt`() = runTest {
        val round = round()
        one.addGame(abend)
        one.session.loseNextAnswer = true

        assertEquals(SyncOutcome.Offline, one.sync.sync())
        assertEquals(2, server.eveningRow(round, abend)?.version)
        assertEquals(1, one.sync.pendingCount())

        one.session.calls.clear()
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 0), one.sync.sync())
        assertEquals(listOf("head", "members", "playersSince", "eveningsSince"), one.session.calls)
        assertEquals(2, server.eveningRow(round, abend)?.version)
        assertSameEverywhere(round, one)
    }

    @Test
    fun `geht die Antwort auf einen neuen Abend verloren, wird er nicht noch einmal angelegt`() = runTest {
        val round = round()
        val neu = one.startEvening("abend-2")
        one.session.loseNextAnswer = true
        assertEquals(SyncOutcome.Offline, one.sync.sync())

        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 0), one.sync.sync())

        assertEquals(1, server.eveningRow(round, neu)?.version)
        assertSameEverywhere(round, one)
    }

    // --- Jemand anders war schneller ---

    @Test
    fun `schreibt jemand genau vor dem Hochladen, wird neu gelesen, zusammengefuehrt und noch einmal hochgeladen`() = runTest {
        val round = round()
        clock.advance(100)
        one.addGame(abend)
        one.api.beforeSave = {
            clock.advance(5)
            two.addGame(abend, won = false)
            two.synced()
        }

        val outcome = one.sync.sync()

        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 1, pending = 0), outcome)
        assertEquals(3, server.eveningRow(round, abend)?.version)
        assertEquals(3, one.evening(abend)?.entries?.size)
        two.synced()
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `nach mehreren vergeblichen Versuchen wartet die Aenderung bis zum naechsten Abgleich`() = runTest {
        val round = round()
        one.addGame(abend)
        var interruptions = 0
        fun interrupt() {
            one.api.beforeSave = {
                clock.advance(5)
                two.addGame(abend, won = false)
                two.synced()
                if (++interruptions < 6) interrupt()
            }
        }
        interrupt()

        // Zwei Durchgänge mit je drei Versuchen, und jedes Mal war Handy zwei schneller.
        val first = one.sync.sync() as SyncOutcome.Synced

        assertEquals(0, first.pushed)
        assertEquals(1, first.pending)
        assertEquals(7, server.eveningRow(round, abend)?.version)
        assertEquals(6, one.session.calls.count { it == "saveEvening" })

        val second = one.sync.sync() as SyncOutcome.Synced
        assertEquals(1, second.pushed)
        assertEquals(0, second.pending)
        // Das erste Spiel des Abends, sechs von Handy zwei und das eine von Handy eins.
        assertEquals(8, server.eveningRow(round, abend)?.evening?.entries?.size)
        two.synced()
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `kennt der Server einen neuen Abend schon, wird mit ihm zusammengefuehrt`() = runTest {
        val round = round()
        // Beide Handys haben denselben Abend (gleiche Kennung), ohne voneinander zu wissen.
        val neu = one.startEvening("abend-2")
        one.addGame(neu)
        two.evenings.save(one.evening(neu)!!.copy(entries = emptyList()))
        clock.advance(30)
        two.addGame(neu, won = false)
        one.api.beforeSave = { two.synced() }

        one.synced()
        two.synced()

        assertEquals(2, one.evening(neu)?.entries?.size)
        assertSameEverywhere(round, one, two)
    }

    // --- Abende löschen ---

    @Test
    fun `ein geloeschter Abend verschwindet auch auf dem anderen Handy`() = runTest {
        val round = round()
        one.evenings.delete(abend)
        assertEquals(1, one.sync.pendingCount())

        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), one.sync.sync())
        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 0), two.sync.sync())

        assertTrue(server.eveningRow(round, abend)!!.deleted)
        assertNull(two.evening(abend))
        assertTrue(two.store.eveningBases().isEmpty())
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `wird ein Abend geloescht, waehrend ein anderer darin eintraegt, bleibt er erhalten`() = runTest {
        val round = round()
        one.evenings.delete(abend)
        one.synced()
        two.addGame(abend)

        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), two.sync.sync())
        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 0), one.sync.sync())

        assertFalse(server.eveningRow(round, abend)!!.deleted)
        assertEquals(2, one.evening(abend)?.entries?.size)
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `hier geloescht, aber auf dem Server inzwischen geaendert - der Abend kommt zurueck`() = runTest {
        val round = round()
        two.addGame(abend)
        two.synced()
        one.evenings.delete(abend)

        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 0), one.sync.sync())

        assertEquals(2, one.evening(abend)?.entries?.size)
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `hier geloescht und der Server schickt dieselbe Fassung noch einmal - er bleibt geloescht`() = runTest {
        val round = round()
        one.evenings.delete(abend)
        // Das Handy liest alles neu (wie nach einem zurückgesetzten Lesezeichen).
        one.store.setLink(one.link()!!.copy(cursor = 0))

        val outcome = one.sync.sync() as SyncOutcome.Synced

        assertEquals(1, outcome.pushed)
        assertTrue(server.eveningRow(round, abend)!!.deleted)
        assertNull(one.evening(abend))
        assertSameEverywhere(round, one)
    }

    @Test
    fun `loeschen beide denselben Abend, ist das kein Konflikt`() = runTest {
        val round = round()
        one.evenings.delete(abend)
        two.evenings.delete(abend)

        one.synced()
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 0), two.sync.sync())

        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `ein auf dem Server geloeschter Abend, den dieses Handy nie hatte, bleibt weg`() = runTest {
        val round = round()
        val neu = one.startEvening("abend-2")
        one.synced()
        one.evenings.delete(neu)
        one.synced()

        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 0), two.sync.sync())

        assertNull(two.evening(neu))
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `traegt jemand genau vor dem Loeschen etwas ein, kommt der Abend mit dem neuen Spiel zurueck`() = runTest {
        val round = round()
        one.evenings.delete(abend)
        one.api.beforeSave = {
            two.addGame(abend)
            two.synced()
        }

        val outcome = one.sync.sync() as SyncOutcome.Synced

        assertEquals(0, outcome.pushed)
        assertFalse(server.eveningRow(round, abend)!!.deleted)
        assertEquals(2, one.evening(abend)?.entries?.size)
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `auch beim erneuten Lesen gilt - eine Aenderung von anderswo schlaegt das Loeschen hier`() = runTest {
        val round = round()
        one.evenings.delete(abend)
        // Der Server schickt die bekannte Fassung noch einmal; genau vor dem Löschen trägt Handy zwei etwas ein.
        one.store.setLink(one.link()!!.copy(cursor = 0))
        one.api.beforeSave = {
            two.addGame(abend)
            two.synced()
        }

        one.synced()

        assertEquals(2, one.evening(abend)?.entries?.size)
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `wird ein geloeschter Abend von zwei Handys zugleich zurueckgeholt, kommen beide Aenderungen an`() = runTest {
        val round = round()
        join(three)
        one.evenings.delete(abend)
        one.synced()
        clock.advance(100)
        two.addGame(abend)
        clock.advance(10)
        three.addGame(abend, won = false)
        two.api.beforeSave = { three.synced() }

        two.synced()
        three.synced()
        one.synced()

        assertEquals(3, one.evening(abend)?.entries?.size)
        assertSameEverywhere(round, one, two, three)
    }

    @Test
    fun `kennt der Server einen Abend entgegen der eigenen Notiz gar nicht, wird er neu angelegt`() = runTest {
        val round = round()
        two.addGame(abend)
        server.forgetEvening(round, abend)

        // Die erwartete Fassung wird abgelehnt, das Nachlesen findet nichts, also geht der Abend als neuer hoch.
        val outcome = two.sync.sync() as SyncOutcome.Synced

        assertEquals(1, outcome.pushed)
        assertEquals(0, outcome.pending)
        assertEquals(RemoteEvening(abend, version = 1, deleted = false, rev = server.rev(round), evening = two.evening(abend)), server.eveningRow(round, abend))
        assertSameEverywhere(round, two)
    }

    // --- Dokumente, die diese Version nicht lesen kann ---

    @Test
    fun `ein Abend, den diese Version nicht lesen kann, bleibt unberuehrt und wird nicht ueberschrieben`() = runTest {
        val round = round()
        val before = two.evening(abend)
        server.makeUnreadable(round, abend)

        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 0, unreadable = 1), two.sync.sync())
        assertEquals(before, two.evening(abend))

        two.addGame(abend)
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 1, unreadable = 1), two.sync.sync())
        assertNull(server.eveningRow(round, abend)?.evening)

        // Eine neuere App schreibt wieder etwas Lesbares: Jetzt kommt auch das wartende Spiel an.
        one.api.saveEvening(round, before!!, expectedVersion = 2)
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), two.sync.sync())
        assertEquals(2, server.eveningRow(round, abend)?.evening?.entries?.size)
    }

    @Test
    fun `ein Abend, den der Server nicht annimmt, haelt die anderen nicht auf`() = runTest {
        val round = round()
        val huge = two.startEvening("riesig")
        two.editor.update(huge) { evening ->
            evening.copy(entries = List(InMemoryRoundServer.MAX_ENTRIES + 1) { game("g$it", it.toLong()) })
        }
        two.addGame(abend)
        val normal = two.startEvening("abend-2")

        val first = two.sync.sync() as SyncOutcome.Synced

        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 2, pending = 1, refused = 1), first)
        assertNull(server.eveningRow(round, huge))
        assertEquals(two.evening(normal), server.eveningRow(round, normal)?.evening)

        // Derselbe Stand wird nicht noch einmal versucht, bleibt aber gezählt; nach einer Änderung geht er hoch.
        two.session.calls.clear()
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 1, refused = 1), two.sync.sync())
        assertFalse("saveEvening" in two.session.calls)
        two.editor.update(huge) { it.copy(entries = it.entries.take(3)) }
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), two.sync.sync())
        assertEquals(3, server.eveningRow(round, huge)?.evening?.entries?.size)
        assertSameEverywhere(round, two)
    }

    @Test
    fun `was eine Runde abgelehnt hat, wird in einer neuen Runde wieder versucht`() = runTest {
        round()
        val huge = two.startEvening("riesig")
        two.editor.update(huge) { evening ->
            evening.copy(entries = List(InMemoryRoundServer.MAX_ENTRIES + 1) { game("g$it", it.toLong()) })
        }
        assertEquals(1, (two.sync.sync() as SyncOutcome.Synced).refused)
        two.session.calls.clear()
        two.sync.sync()
        assertFalse("saveEvening" in two.session.calls)

        // Handy zwei verlässt die Runde und legt mit seinen Daten eine eigene an: Dort wird alles neu versucht.
        two.connection.leave()
        two.session.calls.clear()
        val outcome = two.connection.create("Eigene Runde", two.userId, PLACEHOLDERS) as SyncOutcome.Synced

        assertEquals(2, two.session.calls.count { it == "saveEvening" })
        assertEquals(1, outcome.refused)
        assertEquals(two.evening(abend), server.eveningRow(two.roundId(), abend)?.evening)
    }

    @Test
    fun `nimmt der Server das Ergebnis des Zusammenfuehrens nicht an, bleibt es hier und wartet`() = runTest {
        val round = round()
        one.addGame(abend)
        one.synced()
        two.editor.update(abend) { evening ->
            evening.copy(entries = evening.entries + List(InMemoryRoundServer.MAX_ENTRIES) { game("g$it", it.toLong()) })
        }

        val outcome = two.sync.sync() as SyncOutcome.Synced

        // Das Spiel von Handy eins ist hier angekommen, das Hochladen wurde abgelehnt.
        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 1, refused = 1), outcome)
        assertEquals(InMemoryRoundServer.MAX_ENTRIES + 2, two.evening(abend)?.entries?.size)
        assertEquals(2, server.eveningRow(round, abend)?.evening?.entries?.size)
        two.session.calls.clear()
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 1, refused = 1), two.sync.sync())
        assertFalse("saveEvening" in two.session.calls)
        // Löscht man den Abend hier, ist auch die Zählung weg (der Server hat ihn weiter in der alten Fassung).
        two.evenings.delete(abend)
        assertEquals(0, (two.sync.sync() as SyncOutcome.Synced).refused)
    }

    @Test
    fun `ein unlesbarer Abend bleibt gemerkt und kommt an, sobald er sich lesen laesst`() = runTest {
        val round = round()
        val newer = one.startEvening("abend-neu")
        one.addGame(newer)
        one.synced()
        val content = one.evening(newer)!!
        // Eine neuere App schreibt den Abend in einer Form, die Handy zwei nicht lesen kann.
        server.makeUnreadable(round, newer)

        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 0, unreadable = 1), two.sync.sync())
        assertNull(two.evening(newer))
        assertEquals(setOf(newer), two.link()?.unreadable)

        // Der Abend liegt jetzt hinter dem Lesezeichen. Er bleibt trotzdem gemerkt und gezählt.
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 0, unreadable = 1), two.sync.sync())
        two.addGame(abend)
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0, unreadable = 1), two.sync.sync())
        assertEquals(SyncOutcome.Unchanged, two.sync.sync(quick = true))

        // Nach dem Update der App lässt er sich lesen; auf dem Server hat sich dabei nichts geändert.
        server.makeReadable(round, newer, content)
        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 0), two.sync.sync())
        assertEquals(content, two.evening(newer))
        assertEquals(emptySet<EveningId>(), two.link()?.unreadable)
        one.synced()
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `ein unlesbarer Abend, den es nicht mehr gibt, ist vergessen`() = runTest {
        val round = round()
        val newer = one.startEvening("abend-neu")
        one.synced()
        server.makeUnreadable(round, newer)
        assertEquals(1, (two.sync.sync() as SyncOutcome.Synced).unreadable)

        server.forgetEvening(round, newer)

        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 0), two.sync.sync())
        assertEquals(emptySet<EveningId>(), two.link()?.unreadable)
    }

    @Test
    fun `kommt ein Abend beim Lesen zweimal, zaehlt die neuere Fassung`() = runTest {
        val round = round()
        val old = two.evening(abend)!!
        one.addGame(abend)
        one.synced()
        val row = server.eveningRow(round, abend)!!
        val twice = object : RoundApi by two.session {
            override suspend fun eveningsSince(round: RoundId, rev: Long): List<RemoteEvening> =
                listOf(row.copy(version = 1, evening = old), row, row.copy(version = 1, evening = old))
        }
        val sync = RoundSync(twice, two.store, two.players, two.evenings, two.settings, two.editor)

        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 0), sync.sync())

        assertEquals(one.evening(abend), two.evening(abend))
    }

    // --- Spieler ---

    @Test
    fun `ein umbenannter Gast heisst danach ueberall so, ein neuer steht ueberall hinten`() = runTest {
        val round = round()
        one.players.save(one.allPlayers()[1].copy(name = "Osten"))
        val guest = Player(PlayerId("gast"), "Wirt", colorIndex = 4)
        two.players.save(guest)

        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), one.sync.sync())
        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 1, pending = 0), two.sync.sync())
        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 0), one.sync.sync())

        assertEquals(listOf("Nord", "Osten", "Süd", "West", "Wirt"), one.allPlayers().map { it.name })
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `benennen beide denselben Gast um, gilt der Name, der zuerst auf dem Server war`() = runTest {
        val round = round()
        one.players.save(one.allPlayers()[0].copy(name = "Norden"))
        two.players.save(two.allPlayers()[0].copy(name = "N."))

        one.synced()
        val outcome = two.sync.sync() as SyncOutcome.Synced

        assertEquals(0, outcome.pushed)
        assertEquals("Norden", two.allPlayers()[0].name)
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `archivieren und Farbe wechseln kommen beim anderen Handy an`() = runTest {
        val round = round()
        one.players.save(one.allPlayers()[3].copy(archived = true, colorIndex = 6))

        one.synced()
        two.synced()

        assertEquals(Player(P4, "West", colorIndex = 6, archived = true), two.allPlayers()[3])
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `eine Farbnummer ausserhalb von 0 bis 31 wird vor dem Hochladen umgestellt`() = runTest {
        val round = round()
        one.players.save(Player(PlayerId("gast"), "Wirt", colorIndex = 39))

        one.synced()

        assertEquals(7, one.allPlayers().last().colorIndex)
        assertEquals(7, server.players(round).last().colorIndex)
        assertSameEverywhere(round, one)
    }

    @Test
    fun `ein geloeschter Gast verschwindet auch auf dem anderen Handy`() = runTest {
        val round = round()
        two.players.save(Player(PlayerId("gast"), "Wirt", colorIndex = 4))
        two.synced()
        one.synced()

        one.players.delete(PlayerId("gast"))
        assertEquals(1, one.sync.pendingCount())
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), one.sync.sync())
        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 0), two.sync.sync())

        assertEquals(4, two.allPlayers().size)
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `ein auf dem Server geloeschter Gast, den hier ein Abend braucht, bleibt und geht wieder hoch`() = runTest {
        val round = round()
        val gast = PlayerId("gast")
        two.players.save(Player(gast, "Wirt", colorIndex = 4))
        two.synced()
        one.synced()
        // Handy eins löscht den Gast; Handy zwei hat ihn inzwischen ohne Netz an den Tisch gesetzt.
        one.players.delete(gast)
        one.synced()
        two.startEvening("abend-2", seating = listOf(P1, P2, P3, gast))

        val outcome = two.sync.sync() as SyncOutcome.Synced
        one.synced()

        assertEquals(0, outcome.pending)
        assertEquals("Wirt", two.allPlayers().last().name)
        assertEquals("Wirt", one.allPlayers().last().name)
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `ein hier geloeschter Gast kommt zurueck, wenn ihn ein Abend eines anderen Handys nennt`() = runTest {
        val round = round()
        val gast = PlayerId("gast")
        two.players.save(Player(gast, "Wirt", colorIndex = 4))
        two.synced()
        one.synced()
        two.startEvening("abend-2", seating = listOf(P1, P2, P3, gast))
        two.synced()
        // Handy eins weiß von dem neuen Abend noch nichts und löscht den Gast.
        one.players.delete(gast)

        val outcome = one.sync.sync() as SyncOutcome.Synced

        assertEquals(0, outcome.pushed)
        assertEquals("Wirt", one.allPlayers().last().name)
        assertEquals("Wirt", server.players(round).last().name)
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `ein neuer Gast mit vergebenem Namen entfaellt mit Hinweis`() = runTest {
        val round = round()
        two.players.save(Player(PlayerId("gast"), "nord", colorIndex = 4))

        val outcome = two.sync.sync() as SyncOutcome.Synced

        assertEquals(listOf(SyncNotice(PlayerId("gast"), "nord", RoundError.NAME_TAKEN, nowName = null)), outcome.notices)
        assertEquals(4, two.allPlayers().size)
        assertSameEverywhere(round, two)
    }

    @Test
    fun `ein neuer Gast mit vergebenem Namen, der schon am Tisch sitzt, bekommt eine Nummer`() = runTest {
        val round = round()
        val gast = PlayerId("gast")
        two.players.save(Player(gast, "Nord", colorIndex = 4))
        two.players.save(Player(PlayerId("gast-2"), "Nord 2", colorIndex = 5))
        two.startEvening("abend-2", seating = listOf(gast, P2, P3, P4))

        val outcome = two.sync.sync() as SyncOutcome.Synced
        one.synced()

        assertEquals(listOf(SyncNotice(gast, "Nord", RoundError.NAME_TAKEN, nowName = "Nord 3")), outcome.notices)
        assertEquals(0, outcome.pending)
        assertEquals(listOf("Nord", "Ost", "Süd", "West", "Nord 2", "Nord 3"), server.players(round).map { it.name })
        assertEquals(server.players(round).map { it.toPlayer() }, one.allPlayers())
        // Auf dem Handy, das beide Gäste angelegt hat, stehen sie in der Reihenfolge, in der sie dort entstanden sind.
        assertEquals(one.allPlayers().toSet(), two.allPlayers().toSet())
        assertEquals(0, two.sync.pendingCount())
        assertEquals(one.allEvenings(), two.allEvenings())
    }

    @Test
    fun `ein langer Name wird fuer die Nummer gekuerzt`() = runTest {
        val round = round()
        val long = "Der Nachbar vom Wirtshaus"
        assertEquals(Player.MAX_NAME_LENGTH + 1, long.length)
        val name = long.dropLast(1)
        one.players.save(Player(PlayerId("gast-1"), name, colorIndex = 4))
        one.synced()
        two.players.save(Player(PlayerId("gast-2"), name.uppercase(), colorIndex = 5))
        two.startEvening("abend-2", seating = listOf(PlayerId("gast-2"), P2, P3, P4))

        two.synced()

        assertEquals("DER NACHBAR VOM WIRTSH 2", two.allPlayers().single { it.id == PlayerId("gast-2") }.name)
        assertEquals("DER NACHBAR VOM WIRTSH 2", server.players(round).last().name)
    }

    @Test
    fun `einen festen Namen kann nur sein Besitzer vergeben haben - andere werden zurueckgesetzt`() = runTest {
        val round = round()
        one.connection.claim(P1, "Eins")
        two.synced()
        two.players.save(two.allPlayers()[0].copy(name = "Witzbold", colorIndex = 5))

        val outcome = two.sync.sync() as SyncOutcome.Synced

        assertEquals(listOf(SyncNotice(P1, "Witzbold", RoundError.NAME_LOCKED, nowName = "Eins")), outcome.notices)
        assertEquals(Player(P1, "Eins", colorIndex = 0), two.allPlayers()[0])
        assertTrue(server.players(round)[0].nameLocked)
        assertSameEverywhere(round, two)
    }

    @Test
    fun `Farbe und Archiv eines festen Spielers darf jeder aendern, den Namen nur der Ersteller der Runde`() = runTest {
        val round = round()
        two.connection.claim(P2, "Zwei")
        one.synced()

        two.players.save(two.allPlayers()[1].copy(colorIndex = 7))
        two.synced()
        one.synced()
        one.players.save(one.allPlayers()[1].copy(name = "Zwo"))
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), one.sync.sync())
        two.synced()

        assertEquals(Player(P2, "Zwo", colorIndex = 7), two.allPlayers()[1])
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `ein Spieler mit Besitzer laesst sich nicht loeschen und kommt zurueck`() = runTest {
        val round = round()
        val neu = (two.connection.claim(null, "Zwei").let { two.link()!!.playerId })!!
        one.synced()
        one.players.delete(neu)

        val outcome = one.sync.sync() as SyncOutcome.Synced

        assertEquals(listOf(SyncNotice(neu, "Zwei", RoundError.PLAYER_OWNED, nowName = "Zwei")), outcome.notices)
        assertEquals("Zwei", one.allPlayers().last().name)
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `ist die Runde voll, bleibt ein gebrauchter Gast hier und wird nicht bei jedem Abgleich neu versucht`() = runTest {
        val round = round()
        repeat(InMemoryRoundServer.MAX_PLAYERS - 4) { one.players.save(Player(PlayerId("voll-$it"), "Gast $it", colorIndex = it)) }
        one.synced()
        two.synced()
        val gast = PlayerId("einer-zu-viel")
        two.players.save(Player(gast, "Nachzügler", colorIndex = 1))
        two.startEvening("abend-2", seating = listOf(gast, P2, P3, P4))

        val first = two.sync.sync() as SyncOutcome.Synced
        assertEquals(listOf(SyncNotice(gast, "Nachzügler", RoundError.LIMIT_REACHED, nowName = "Nachzügler")), first.notices)
        assertEquals(1, first.pending)

        two.session.calls.clear()
        val second = two.sync.sync() as SyncOutcome.Synced
        assertEquals(emptyList<SyncNotice>(), second.notices)
        assertFalse("savePlayer" in two.session.calls)

        // Sobald sich an dem Spieler etwas ändert, wird es wieder versucht.
        two.players.save(two.allPlayers().last().copy(colorIndex = 2))
        assertEquals(1, (two.sync.sync() as SyncOutcome.Synced).notices.size)
        assertEquals(InMemoryRoundServer.MAX_PLAYERS, server.players(round).size)
    }

    @Test
    fun `ein unbenutzter Gast zu viel entfaellt mit Hinweis`() = runTest {
        round()
        repeat(InMemoryRoundServer.MAX_PLAYERS - 4) { one.players.save(Player(PlayerId("voll-$it"), "Gast $it", colorIndex = it)) }
        one.synced()
        two.synced()
        two.players.save(Player(PlayerId("einer-zu-viel"), "Nachzügler", colorIndex = 1))

        val outcome = two.sync.sync() as SyncOutcome.Synced

        assertEquals(RoundError.LIMIT_REACHED, outcome.notices.single().reason)
        assertNull(outcome.notices.single().nowName)
        assertEquals(0, outcome.pending)
    }

    @Test
    fun `das eigene Echo ueberschreibt keine neuere Aenderung von hier`() = runTest {
        val round = round()
        one.players.save(one.allPlayers()[0].copy(name = "Norden"))
        one.synced()
        one.players.save(one.allPlayers()[0].copy(name = "Nordlicht"))
        // Das Handy liest alles neu und bekommt dabei auch den eigenen, älteren Namen wieder.
        one.store.setLink(one.link()!!.copy(cursor = 0))

        one.synced()

        assertEquals("Nordlicht", server.players(round)[0].name)
        assertSameEverywhere(round, one)
    }

    @Test
    fun `Namen und Farben aus alten Daten werden vor dem Hochladen in Form gebracht`() = runTest {
        val round = round()
        two.players.save(Player(PlayerId("gast-1"), "  Wirt   vom\tEck ", colorIndex = -1))
        two.players.save(Player(PlayerId("gast-2"), "Ein viel zu langer Name für den Server", colorIndex = 4))

        two.synced()
        one.synced()

        assertEquals(Player(PlayerId("gast-1"), "Wirt vom Eck", colorIndex = 31), one.allPlayers()[4])
        assertEquals(Player(PlayerId("gast-2"), "Ein viel zu langer Name", colorIndex = 4), one.allPlayers()[5])
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `ein Spieler, den der Server gar nicht annimmt, entfaellt oder bleibt hier, ohne den Abgleich aufzuhalten`() = runTest {
        val round = round()
        val long = PlayerId("x".repeat(InMemoryRoundServer.MAX_ID_LENGTH + 1))
        val used = PlayerId("y".repeat(InMemoryRoundServer.MAX_ID_LENGTH + 1))
        two.players.save(Player(long, "Unbenutzt", colorIndex = 4))
        two.players.save(Player(used, "Am Tisch", colorIndex = 5))
        two.players.save(Player(PlayerId("leer"), "   ", colorIndex = 6))
        two.addGame(abend)
        two.editor.update(abend) { it.copy(firstDealer = used) }

        val outcome = two.sync.sync() as SyncOutcome.Synced

        assertEquals(
            listOf(
                SyncNotice(long, "Unbenutzt", RoundError.INVALID_DOC, nowName = null),
                SyncNotice(used, "Am Tisch", RoundError.INVALID_DOC, nowName = "Am Tisch"),
                SyncNotice(PlayerId("leer"), "   ", RoundError.INVALID_NAME, nowName = null),
            ),
            outcome.notices,
        )
        // Das Spiel ist trotzdem oben; nur der eine Spieler wartet.
        assertEquals(1, outcome.pushed)
        assertEquals(1, outcome.pending)
        assertEquals(two.evening(abend), server.eveningRow(round, abend)?.evening)
    }

    // --- Regelwerk für neue Abende ---

    @Test
    fun `ein geaendertes Regelwerk fuer neue Abende gilt danach auf allen Handys`() = runTest {
        round()
        one.settings.update { it.copy(defaultRules = RuleSet.sauspiel()) }
        assertEquals(1, one.sync.pendingCount())

        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), one.sync.sync())
        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 0), two.sync.sync())

        assertEquals(RuleSet.sauspiel(), two.settings.settings.first().defaultRules)
        assertEquals(RuleSet.sauspiel(), two.link()?.rulesBase)
    }

    @Test
    fun `aendern beide das Regelwerk, gilt das, was zuerst auf dem Server war`() = runTest {
        round()
        val mine = RuleSet.standard().copy(name = "von eins")
        one.settings.update { it.copy(defaultRules = mine) }
        two.settings.update { it.copy(defaultRules = RuleSet.sauspiel()) }

        one.synced()
        val outcome = two.sync.sync() as SyncOutcome.Synced

        assertEquals(0, outcome.pushed)
        assertEquals(mine, two.settings.settings.first().defaultRules)
    }

    @Test
    fun `ein Regelwerk, das diese Version nicht lesen kann, wird weder uebernommen noch ueberschrieben`() = runTest {
        val round = round()
        server.makeRulesUnreadable(round)
        two.settings.update { it.copy(defaultRules = RuleSet.sauspiel()) }

        val outcome = two.sync.sync() as SyncOutcome.Synced

        assertEquals(0, outcome.pushed)
        assertEquals(1, outcome.pending)
        assertFalse("saveRules" in two.session.calls)
        assertEquals(RuleSet.sauspiel(), two.settings.settings.first().defaultRules)
    }

    @Test
    fun `ein Regelwerk, das der Server nicht annimmt, wird nicht bei jedem Abgleich neu versucht`() = runTest {
        round()
        val wordy = RuleSet.standard().copy(name = "x".repeat(InMemoryRoundServer.MAX_RULES_NAME_LENGTH + 1))
        two.settings.update { it.copy(defaultRules = wordy) }
        two.addGame(abend)

        val first = two.sync.sync() as SyncOutcome.Synced
        two.session.calls.clear()
        val second = two.sync.sync() as SyncOutcome.Synced

        assertEquals(1, first.pushed)
        assertEquals(1, first.pending)
        assertEquals(1, second.pending)
        assertFalse("saveRules" in two.session.calls)
        // Ein anderes Regelwerk geht wieder hoch.
        two.settings.update { it.copy(defaultRules = RuleSet.sauspiel()) }
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), two.sync.sync())
    }

    // --- Das bin ich ---

    @Test
    fun `Das bin ich - ein vorhandener Spieler bekommt den eigenen, festen Namen`() = runTest {
        val round = round()

        val outcome = two.connection.claim(P3, "  Zwei ")
        one.synced()

        assertTrue(outcome is SyncOutcome.Synced)
        assertEquals(P3, two.link()?.playerId)
        assertEquals(Player(P3, "Zwei", colorIndex = 2), one.allPlayers()[2])
        val row = server.players(round)[2]
        assertEquals(two.userId, row.owner)
        assertTrue(row.nameLocked)
        assertEquals(listOf(RoundMember(one.userId, null), RoundMember(two.userId, P3)), one.link()?.members)
        assertTrue(one.store.playerBases().getValue(P3).nameLocked)
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `Das bin ich - wer neu ist, wird als Spieler angelegt und steht bei allen in der Liste`() = runTest {
        val round = round()

        two.connection.claim(null, "Zwei")
        one.synced()

        val me = two.link()?.playerId
        assertNotNull(me)
        // Die erste freie Farbe nach den vier vorhandenen Spielern.
        assertEquals(Player(me!!, "Zwei", colorIndex = 4), one.allPlayers().last())
        assertEquals(5, one.allPlayers().size)
        assertSameEverywhere(round, one, two)
    }

    @Test
    fun `Das bin ich - nur einmal, kein fremder Spieler und kein vergebener Name`() = runTest {
        round()
        one.connection.claim(P1, "Eins")

        assertEquals(RoundError.ALREADY_CLAIMED, errorOf { one.connection.claim(P2, "Noch einer") })
        two.synced()
        assertEquals(RoundError.PLAYER_TAKEN, errorOf { two.connection.claim(P1, "Zwei") })
        assertEquals(RoundError.NAME_TAKEN, errorOf { two.connection.claim(P2, "eins") })
        assertEquals(RoundError.NAME_TAKEN, errorOf { two.connection.claim(null, "Süd") })
        assertEquals(RoundError.INVALID_NAME, errorOf { two.connection.claim(P2, "   ") })
        assertNull(two.link()?.playerId)
    }

    @Test
    fun `ohne Runde gibt es kein Das-bin-ich`() = runTest {
        assertEquals(SyncOutcome.NotLinked, one.connection.claim(null, "Eins"))
    }

    // --- Trennen ---

    @Test
    fun `gehoert das Konto nicht mehr zur Runde, loest sich die Verbindung und die Daten bleiben`() = runTest {
        val round = round()
        server.removeMember(round, two.userId)

        assertEquals(SyncOutcome.Detached, two.sync.sync())

        assertNull(two.link())
        assertTrue(two.store.eveningBases().isEmpty())
        assertTrue(two.store.playerBases().isEmpty())
        assertEquals(1, two.allEvenings().size)
        assertEquals(4, two.allPlayers().size)
        assertEquals(SyncOutcome.NotLinked, two.sync.sync())
        assertEquals(0, two.sync.pendingCount())
    }

    @Test
    fun `eine einzelne seltsame Antwort trennt die Runde nicht vom Handy`() = runTest {
        round()
        val headless = object : RoundApi by two.session {
            override suspend fun head(round: RoundId): RoundHead? = null
        }
        val sync = RoundSync(headless, two.store, two.players, two.evenings, two.settings, two.editor)

        assertEquals(SyncOutcome.Failed(RoundError.SERVER), sync.sync())

        assertNotNull(two.link())
    }

    @Test
    fun `Runde verlassen - das Konto ist drausen, die Daten bleiben als Kopie`() = runTest {
        val round = round()
        two.connection.claim(P2, "Zwei")

        two.connection.leave()
        one.synced()

        assertNull(two.link())
        assertEquals(1, two.allEvenings().size)
        assertEquals(listOf(RoundMember(one.userId, null)), one.link()?.members)
        // Der Spieler bleibt in der Runde; wer wiederkommt, ist wieder er.
        assertEquals("Zwei", server.players(round)[1].name)
        two.connection.join(one.api.createInvite(round), two.userId)
        assertEquals(P2, two.link()?.playerId)
    }

    @Test
    fun `verlaesst der Letzte die Runde, gibt es sie nicht mehr`() = runTest {
        one.firstStart()
        one.connection.create("Stammtisch", one.userId, PLACEHOLDERS)
        val round = one.roundId()

        one.connection.leave()

        assertNull(one.link())
        assertEquals(emptyList<RoundSummary>(), one.api.myRounds())
        assertNull(one.api.head(round))
        one.connection.leave()
    }

    @Test
    fun `ohne Netz laesst sich die Runde nicht verlassen, aber die Verbindung auf dem Handy loesen`() = runTest {
        round()
        two.session.offline = true

        assertEquals(RoundError.OFFLINE, errorOf { two.connection.leave() })
        assertNotNull(two.link())

        two.connection.disconnect()
        assertNull(two.link())
        assertTrue(two.store.playerBases().isEmpty())
        two.session.offline = false
        assertEquals(2, one.api.members(one.roundId()).size)
    }

    // --- Fehler ---

    @Test
    fun `ist die Anmeldung abgelaufen, bleibt alles, wie es ist`() = runTest {
        val round = round()
        two.addGame(abend)
        two.session.signedOut = true

        assertEquals(SyncOutcome.SignedOut, two.sync.sync())
        assertNotNull(two.link())
        assertEquals(1, two.sync.pendingCount())

        two.session.signedOut = false
        two.synced()
        assertSameEverywhere(round, two)
    }

    @Test
    fun `ein Fehler des Servers wird gemeldet`() = runTest {
        round()
        val broken = object : RoundApi by two.session {
            override suspend fun head(round: RoundId): RoundHead? = throw RoundException(RoundError.SERVER, "503")
        }
        val sync = RoundSync(broken, two.store, two.players, two.evenings, two.settings, two.editor)

        assertEquals(SyncOutcome.Failed(RoundError.SERVER), sync.sync())
    }

    @Test
    fun `ohne Runde gibt es nichts abzugleichen`() = runTest {
        one.firstStart()

        assertEquals(SyncOutcome.NotLinked, one.sync.sync())
        assertEquals(0, one.sync.pendingCount())
        assertEquals(emptyList<String>(), one.session.calls)
    }

    @Test
    fun `bricht es zwischen Abend und Lesezeichen ab, fuehrt der naechste Abgleich zum selben Ergebnis`() = runTest {
        val round = round()
        clock.advance(100)
        two.addGame(abend, won = false)
        two.synced()
        clock.advance(10)
        one.addGame(abend)
        // Der Abend ist schon zusammengeführt gespeichert, die Basis noch nicht – wie nach einem Absturz genau dazwischen.
        val merged = EveningMerge.merge3(one.store.eveningBases().getValue(abend).evening, one.evening(abend)!!, two.evening(abend)!!)
        one.evenings.save(merged)

        one.synced()
        two.synced()

        assertEquals(3, server.eveningRow(round, abend)?.evening?.entries?.size)
        assertSameEverywhere(round, one, two)
    }

    // --- Ein Server, der zurückfällt ---

    @Test
    fun `faellt der Server auf einen alten Stand zurueck, geht nichts verloren`() = runTest {
        val round = round()
        val old = one.evening(abend)!!
        one.addGame(abend)
        one.synced()
        one.addGame(abend)
        one.synced()
        assertEquals(3, server.eveningRow(round, abend)?.version)

        server.rollBack(round, abend, version = 1, evening = old, rev = 3)
        val outcome = one.sync.sync() as SyncOutcome.Synced

        assertEquals(1, outcome.pushed)
        assertEquals(3, one.evening(abend)?.entries?.size)
        assertEquals(one.evening(abend), server.eveningRow(round, abend)?.evening)
        assertEquals(2, server.eveningRow(round, abend)?.version)
    }

    // --- Wartendes zählen ---

    @Test
    fun `gezaehlt wird, was noch nicht auf dem Server ist`() = runTest {
        round()
        assertEquals(0, two.sync.pendingCount())

        two.addGame(abend)
        two.startEvening("abend-2")
        assertEquals(2, two.sync.pendingCount())
        two.players.save(two.allPlayers()[0].copy(name = "Norden"))
        two.players.save(Player(PlayerId("gast"), "Wirt", colorIndex = 4))
        two.players.delete(P4)
        assertEquals(5, two.sync.pendingCount())
        two.settings.update { it.copy(defaultRules = RuleSet.sauspiel()) }
        assertEquals(6, two.sync.pendingCount())
        two.evenings.delete(abend)
        assertEquals(6, two.sync.pendingCount())
    }

    @Test
    fun `drei Handys kommen auch bei verschraenkten Eingaben zum selben Stand`() = runTest {
        val round = round()
        join(three)
        val games = ArrayList<GameEntry>()
        suspend fun play(device: Device, won: Boolean) {
            clock.advance(40)
            val id = device.addGame(abend, won = won)
            games += device.evening(abend)!!.entries.first { it.id == id } as GameEntry
        }
        play(one, true)
        play(two, false)
        one.synced()
        play(three, true)
        three.synced()
        play(two, true)
        two.synced()
        play(one, false)
        one.synced()
        three.synced()
        two.synced()

        val expected: Evening = one.evening(abend)!!
        assertEquals(games.map { it.id }, expected.entries.drop(1).map { it.id })
        assertSameEverywhere(round, one, two, three)
    }
}
