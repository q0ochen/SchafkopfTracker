package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.rules.BockRules
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.PayoutRule
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Ganze kleine Abende, vollständig von Hand durchgerechnet: Betrag, Saldo, Platz, Geber und Bock-Zähler nach jedem
 * Eintrag. Die erwarteten Zahlen stammen aus der Vorgabe (Regelwerk der Runde, [RuleSet.standard]), nicht aus dem
 * Code. Danach werden Einträge nachträglich geändert, gelöscht, eingefügt und angehängt; auch dafür ist jeder
 * Stand von Hand neu gerechnet.
 *
 * Am Tisch sitzen A, B, C und D in dieser Reihenfolge; alle Zahlenlisten haben diese Reihenfolge.
 */
class HandCalculatedEveningTest {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val seating = listOf(a, b, c, d)
    private val now: Instant = Instant.parse("2026-11-15T19:00:00Z")
    private val standard = RuleSet.standard()

    private fun evening(entries: List<EveningEntry>, firstDealer: PlayerId, rules: RuleSet = standard) = Evening(
        id = EveningId("abend"),
        startedAt = now,
        seating = seating,
        firstDealer = firstDealer,
        rules = rules,
        entries = entries,
    )

    private fun game(
        id: String,
        type: GameType,
        declarer: PlayerId,
        partner: PlayerId? = null,
        won: Boolean = true,
        level: ResultLevel = ResultLevel.NORMAL,
        laufende: Int = 0,
        gelegt: Set<PlayerId> = emptySet(),
        bock: BockOverride = BockOverride.AUTO,
    ) = GameEntry(
        id = EntryId(id), recordedAt = now, type = type, declarer = declarer, partner = partner, declarerWon = won,
        level = level, laufende = laufende, klopfer = gelegt, bockOverride = bock,
    )

    private fun thrownIn(id: String) = ThrownInEntry(EntryId(id), now)

    private fun sixNixer(id: String, thrownIn: Boolean) =
        BockTriggerEntry(EntryId(id), now, BockReason.SECHS_NIXER, player = b, handThrownIn = thrownIn)

    /** Eine von Hand gerechnete Zeile: was nach einem Eintrag gelten muss. */
    private data class Row(
        val dealer: PlayerId,
        val gameNumber: Int?,
        val bock: Int,
        val value: Int?,
        val amounts: List<Int>,
        val balances: List<Int>,
    )

    private fun rows(state: EveningState): List<Row> = state.results.map { result ->
        Row(
            dealer = result.dealer,
            gameNumber = result.gameNumber,
            bock = result.bockExponent,
            value = result.breakdown?.value,
            amounts = seating.map { result.amounts.getValue(it) },
            balances = seating.map { result.balances.getValue(it) },
        )
    }

    private fun assertRows(expected: List<Row>, state: EveningState) {
        val actual = rows(state)
        assertEquals(expected.size, actual.size, "Anzahl der Einträge")
        expected.forEachIndexed { index, row ->
            assertEquals(0, row.amounts.sum(), "Die Handrechnung zu Eintrag ${index + 1} muss aufgehen")
            assertEquals(row, actual[index], "Eintrag ${index + 1}")
        }
        assertTrue(state.results.all { it.issues.isEmpty() }, "kein Eintrag ist fehlerhaft")
    }

    private fun balances(state: EveningState) = seating.map { state.balances.getValue(it) }

    private fun ranks(state: EveningState) = seating.map { state.ranks.getValue(it) }

    // ---------------------------------------------------------------- Abend 1: Regelwerk der Runde

    private val e1 = game("e1", GameType.RUFSPIEL, a, c)
    private val e2 = thrownIn("e2")
    private val e3 = game("e3", GameType.FARBSOLO, d, level = ResultLevel.SCHNEIDER)
    private val e4 = sixNixer("e4", thrownIn = true)
    private val e5 = game("e5", GameType.RUFSPIEL, b, d, won = false, gelegt = setOf(a))
    private val e6 = game("e6", GameType.WENZ, c, laufende = 2)
    private val e7 = ManualEntry(EntryId("e7"), now, mapOf(a to 50, b to -50), "Wette")
    private val e8 = game("e8", GameType.UEBER_KREUZ, a, c, won = false, level = ResultLevel.SCHWARZ, bock = BockOverride.FORCE_OFF)
    private val e9 = game("e9", GameType.GEIER, a, gelegt = setOf(b, d))
    private val e10 = game("e10", GameType.RUFSPIEL, c, d, laufende = 3)
    private val e11 = game("e11", GameType.RUFSPIEL, a, b)

    /** Erster Geber ist B. */
    private val first = evening(listOf(e1, e2, e3, e4, e5, e6, e7, e8, e9, e10, e11), firstDealer = b)

    @Test
    fun `Abend 1 - jeder Eintrag mit Geber, Spielnummer, Bock, Spielwert, Betraegen und Salden`() {
        val state = EveningCalculator.compute(first)

        assertRows(
            listOf(
                // e1 Rufspiel A mit C gewonnen: T = 10. B gibt.
                Row(b, 1, 0, 10, listOf(10, -10, 10, -10), listOf(10, -10, 10, -10)),
                // e2 zusammengeworfen: nichts gebucht, C hat gegeben, danach gibt D.
                Row(c, null, 0, null, listOf(0, 0, 0, 0), listOf(10, -10, 10, -10)),
                // e3 Farbsolo D gewonnen, Schneider: T = 50 + 10 = 60; D +180, die anderen −60.
                Row(d, 2, 0, 60, listOf(-60, -60, -60, 180), listOf(-50, -70, -50, 170)),
                // e4 sechs Nixer, Blatt zusammengeworfen: vier Bockspiele im Zähler; A hat gegeben, danach gibt B.
                Row(a, null, 0, null, listOf(0, 0, 0, 0), listOf(-50, -70, -50, 170)),
                // e5 Rufspiel B mit D verloren, A hat gelegt, Bock: T = 10 × 2 × 2 = 40; A und C je +40.
                Row(b, 3, 1, 40, listOf(40, -40, 40, -40), listOf(-10, -110, -10, 130)),
                // e6 Wenz C gewonnen, zwei Laufende, Bock: T = (50 + 20) × 2 = 140; C +420.
                Row(c, 4, 1, 140, listOf(-140, -140, 420, -140), listOf(-150, -250, 410, -10)),
                // e7 freie Buchung A +50, B −50: kein Spiel, der Geber bleibt D.
                Row(d, null, 0, null, listOf(50, -50, 0, 0), listOf(-100, -300, 410, -10)),
                // e8 Über Kreuz A mit C verloren, Schwarz, von Hand kein Bock: T = 10 + 20 = 30; der Zähler bleibt bei 2.
                Row(d, 5, 0, 30, listOf(-30, 30, -30, 30), listOf(-130, -270, 380, 20)),
                // e9 Geier A gewonnen, B und D haben gelegt, Bock: T = 50 × 2 × 4 = 400; A +1200.
                Row(a, 6, 1, 400, listOf(1200, -400, -400, -400), listOf(1070, -670, -20, -380)),
                // e10 Rufspiel C mit D gewonnen, drei Laufende, letztes Bockspiel: T = (10 + 30) × 2 = 80.
                Row(b, 7, 1, 80, listOf(-80, -80, 80, 80), listOf(990, -750, 60, -300)),
                // e11 Rufspiel A mit B gewonnen, die Bockrunde ist vorbei: T = 10.
                Row(c, 8, 0, 10, listOf(10, 10, -10, -10), listOf(1000, -740, 50, -310)),
            ),
            state,
        )

        // Endstand: 1000 − 740 + 50 − 310 = 0. A ist Erster, dann C, D, B.
        assertEquals(listOf(1000, -740, 50, -310), balances(state))
        assertEquals(listOf(1, 4, 2, 3), ranks(state))
        assertEquals(8, state.gamesPlayed)
        assertEquals(d, state.nextDealer)
        assertEquals(0, state.bockGamesRemaining)
        assertEquals(0, state.nextBockExponent)
    }

    @Test
    fun `Abend 1 - Plaetze nach jedem Eintrag, Gleichstand teilt den Platz`() {
        val state = EveningCalculator.compute(first)
        assertEquals(
            listOf(
                listOf(1, 3, 1, 3), // 10, −10, 10, −10: A und C vorn, B und D teilen Platz 3
                listOf(1, 3, 1, 3),
                listOf(2, 4, 2, 1), // −50, −70, −50, 170
                listOf(2, 4, 2, 1),
                listOf(2, 4, 2, 1), // −10, −110, −10, 130
                listOf(3, 4, 1, 2), // −150, −250, 410, −10
                listOf(3, 4, 1, 2), // −100, −300, 410, −10
                listOf(3, 4, 1, 2), // −130, −270, 380, 20
                listOf(1, 4, 2, 3), // 1070, −670, −20, −380
                listOf(1, 4, 2, 3), // 990, −750, 60, −300
                listOf(1, 4, 2, 3), // 1000, −740, 50, −310
            ),
            state.results.map { result -> seating.map { result.ranks.getValue(it) } },
        )
    }

    @Test
    fun `Abend 1 - der Bock-Zaehler unterwegs`() {
        fun after(count: Int) = EveningCalculator.compute(first.copy(entries = first.entries.take(count)))

        // Vor den sechs Nixern: kein Bock. Danach vier Bockspiele, B gibt.
        assertEquals(0, after(3).bockGamesRemaining)
        assertEquals(a, after(3).nextDealer)
        assertEquals(4, after(4).bockGamesRemaining)
        assertEquals(1, after(4).nextBockExponent)
        assertEquals(b, after(4).nextDealer)
        // Nach e5 und e6 sind zwei verbraucht.
        assertEquals(2, after(6).bockGamesRemaining)
        assertEquals(d, after(6).nextDealer)
        // Die freie Buchung und das von Hand ausgenommene Spiel e8 verbrauchen nichts.
        assertEquals(2, after(7).bockGamesRemaining)
        assertEquals(2, after(8).bockGamesRemaining)
        assertEquals(1, after(8).nextBockExponent)
        assertEquals(1, after(9).bockGamesRemaining)
        assertEquals(0, after(10).bockGamesRemaining)
        assertEquals(0, after(10).nextBockExponent)
    }

    @Test
    fun `Abend 1 - wer zahlt wem`() {
        // Endstand A 1000, B −740, C 50, D −310. Größter Schuldner B zahlt an den größten Gläubiger A 740 (A hat
        // noch 260 offen). Dann zahlt D an A 260 (D schuldet noch 50) und zuletzt D an C 50.
        val state = EveningCalculator.compute(first)
        assertTrue(SettleUp.isBalanced(state.balances))
        assertEquals(
            listOf(Transfer(b, a, 740), Transfer(d, a, 260), Transfer(d, c, 50)),
            SettleUp.transfers(state.balances),
        )
    }

    // ---------------------------------------------------------------- Abend 1 nachträglich geändert

    @Test
    fun `Abend 1 - die sechs Nixer werden geloescht - kein Bock mehr und die Geber ruecken auf`() {
        val state = EveningCalculator.compute(first.remove(e4.id))

        assertRows(
            listOf(
                Row(b, 1, 0, 10, listOf(10, -10, 10, -10), listOf(10, -10, 10, -10)),
                Row(c, null, 0, null, listOf(0, 0, 0, 0), listOf(10, -10, 10, -10)),
                Row(d, 2, 0, 60, listOf(-60, -60, -60, 180), listOf(-50, -70, -50, 170)),
                // e5 ohne Bock: T = 10 × 2 = 20. Jetzt gibt A (das zusammengeworfene Blatt der sechs Nixer fehlt).
                Row(a, 3, 0, 20, listOf(20, -20, 20, -20), listOf(-30, -90, -30, 150)),
                // e6 ohne Bock: T = 50 + 20 = 70; C +210.
                Row(b, 4, 0, 70, listOf(-70, -70, 210, -70), listOf(-100, -160, 180, 80)),
                Row(c, null, 0, null, listOf(50, -50, 0, 0), listOf(-50, -210, 180, 80)),
                // e8: T = 30 wie zuvor.
                Row(c, 5, 0, 30, listOf(-30, 30, -30, 30), listOf(-80, -180, 150, 110)),
                // e9 ohne Bock: T = 50 × 4 = 200; A +600.
                Row(d, 6, 0, 200, listOf(600, -200, -200, -200), listOf(520, -380, -50, -90)),
                // e10 ohne Bock: T = 10 + 30 = 40.
                Row(a, 7, 0, 40, listOf(-40, -40, 40, 40), listOf(480, -420, -10, -50)),
                Row(b, 8, 0, 10, listOf(10, 10, -10, -10), listOf(490, -410, -20, -60)),
            ),
            state,
        )
        // 490 − 410 − 20 − 60 = 0.
        assertEquals(listOf(490, -410, -20, -60), balances(state))
        assertEquals(listOf(1, 4, 2, 3), ranks(state))
        assertEquals(c, state.nextDealer)
        assertEquals(8, state.gamesPlayed)
        assertEquals(0, state.bockGamesRemaining)
    }

    @Test
    fun `Abend 1 - das Farbsolo wird nachtraeglich auf verloren geaendert`() {
        // e3 kehrt sich um: D −180, die anderen +60. Alle späteren Salden verschieben sich um +120 (A, B, C)
        // und −360 (D).
        val changed = first.replace(e3.copy(declarerWon = false))
        val state = EveningCalculator.compute(changed)

        assertEquals(first.entries.map { it.id }, changed.entries.map { it.id })
        assertEquals(listOf(60, 60, 60, -180), seating.map { state.results[2].amounts.getValue(it) })
        assertEquals(listOf(70, 50, 70, -190), seating.map { state.results[2].balances.getValue(it) })
        // Nach e5 (A und C +40, B und D −40): 110, 10, 110, −230.
        assertEquals(listOf(110, 10, 110, -230), seating.map { state.results[4].balances.getValue(it) })
        // Nach e9 (A +1200, die anderen −400): 1190, −550, 100, −740.
        assertEquals(listOf(1190, -550, 100, -740), seating.map { state.results[8].balances.getValue(it) })
        // Endstand: 1000 + 120, −740 + 120, 50 + 120, −310 − 360. Summe 1120 − 620 + 170 − 670 = 0.
        assertEquals(listOf(1120, -620, 170, -670), balances(state))
        assertEquals(listOf(1, 3, 2, 4), ranks(state))
        // Geber, Spielnummern und Bock bleiben, wie sie waren.
        val before = EveningCalculator.compute(first)
        assertEquals(before.results.map { it.dealer }, state.results.map { it.dealer })
        assertEquals(before.results.map { it.gameNumber }, state.results.map { it.gameNumber })
        assertEquals(before.results.map { it.bockExponent }, state.results.map { it.bockExponent })
        assertEquals(d, state.nextDealer)
    }

    @Test
    fun `Abend 1 - eine zweite Bockrunde wird nachtraeglich eingefuegt und haengt sich an`() {
        // Hinter e6 (Zähler 2) kommen noch einmal sechs Nixer, diesmal ohne Zusammenwerfen: Zähler 2 + 4 = 6.
        // e8 ist von Hand ausgenommen (bleibt 6), e9 und e10 verbrauchen je eines (4), e11 ist jetzt auch ein
        // Bockspiel: T = 10 × 2 = 20 statt 10. Danach sind noch 3 offen.
        val second = sixNixer("neu", thrownIn = false)
        val changed = first.insertAt(6, second)
        val state = EveningCalculator.compute(changed)

        assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "e6", "neu", "e7", "e8", "e9", "e10", "e11"), changed.entries.map { it.id.value })
        assertEquals(listOf(b, c, d, a, b, c, d, d, d, a, b, c), state.results.map { it.dealer })
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1), state.results.map { it.bockExponent })
        assertEquals(listOf(20, 20, -20, -20), seating.map { state.results[11].amounts.getValue(it) })
        // 990 + 20, −750 + 20, 60 − 20, −300 − 20. Summe 1010 − 730 + 40 − 320 = 0.
        assertEquals(listOf(1010, -730, 40, -320), balances(state))
        assertEquals(3, state.bockGamesRemaining)
        assertEquals(1, state.nextBockExponent)
        assertEquals(d, state.nextDealer)
        assertEquals(8, state.gamesPlayed)
    }

    @Test
    fun `Abend 1 - ein weiteres Spiel wird angehaengt`() {
        // Farbsolo B verloren, kein Bock mehr: T = 50; B −150, die anderen +50. D gibt, danach A.
        val state = EveningCalculator.compute(first.append(game("e12", GameType.FARBSOLO, b, won = false)))

        val last = state.results.last()
        assertEquals(d, last.dealer)
        assertEquals(9, last.gameNumber)
        assertEquals(50, last.breakdown?.value)
        assertEquals(listOf(50, -150, 50, 50), seating.map { last.amounts.getValue(it) })
        // 1000 + 50, −740 − 150, 50 + 50, −310 + 50. Summe 1050 − 890 + 100 − 260 = 0.
        assertEquals(listOf(1050, -890, 100, -260), balances(state))
        assertEquals(listOf(1, 4, 2, 3), ranks(state))
        assertEquals(a, state.nextDealer)
        assertEquals(9, state.gamesPlayed)
    }

    @Test
    fun `Abend 1 - loeschen und an derselben Stelle wieder einfuegen ergibt wieder denselben Stand`() {
        val original = EveningCalculator.compute(first)
        first.entries.forEachIndexed { index, entry ->
            val removed = first.remove(entry.id)
            assertEquals(first.entries.size - 1, removed.entries.size)
            val restored = removed.insertAt(index, entry)
            assertEquals(first, restored)
            assertEquals(original, EveningCalculator.compute(restored))
        }
    }

    @Test
    fun `Abend 1 - ersetzen mit unbekannter Kennung und loeschen einer unbekannten Kennung aendern nichts`() {
        assertEquals(first, first.replace(game("gibt-es-nicht", GameType.WENZ, a)))
        assertEquals(first, first.remove(EntryId("gibt-es-nicht")))
        // Eine Stelle vor dem Anfang oder hinter dem Ende wird auf den Rand gelegt.
        val extra = thrownIn("neu")
        assertEquals(listOf(extra) + first.entries, first.insertAt(-5, extra).entries)
        assertEquals(first.entries + extra, first.insertAt(99, extra).entries)
    }

    @Test
    fun `Abend 1 ueber den Editor - anhaengen, aendern und loeschen werden gespeichert und stimmen danach`() = runTest {
        val start = evening(emptyList(), firstDealer = b)
        val storage = InMemoryEveningRepository(listOf(start))
        val editor = EveningEditor(storage, Clock.fixed(now, ZoneOffset.UTC), IdGenerator { "unbenutzt" })

        for (entry in first.entries) editor.update(start.id) { it.append(entry) }
        assertEquals(first, storage.evening(start.id).first())

        // Wie oben von Hand gerechnet: Farbsolo auf verloren, dann die sechs Nixer löschen.
        editor.update(start.id) { it.replace(e3.copy(declarerWon = false)) }
        val saved = editor.update(start.id) { it.remove(e4.id) }

        val stored = checkNotNull(storage.evening(start.id).first())
        assertEquals(stored, saved)
        val state = EveningCalculator.compute(stored)
        // Ohne Bock und mit verlorenem Farbsolo: Endstand ohne Bock 490, −410, −20, −60, dazu +120, +120, +120, −360.
        assertEquals(listOf(610, -290, 100, -420), balances(state))
        assertEquals(listOf(1, 3, 2, 4), ranks(state))
        assertEquals(c, state.nextDealer)
    }

    // ---------------------------------------------------------------- Abend 2: zwei Bockrunden, alle drei Arten

    private val t1 = sixNixer("t1", thrownIn = true)
    private val g1 = game("g1", GameType.RUFSPIEL, a, b)
    private val t2 = sixNixer("t2", thrownIn = false)
    private val g2 = game("g2", GameType.RUFSPIEL, a, b)
    private val g3 = game("g3", GameType.FARBSOLO, c)
    private val g4 = game("g4", GameType.RUFSPIEL, a, b)
    private val g5 = game("g5", GameType.RUFSPIEL, a, b)
    private val g6 = game("g6", GameType.RUFSPIEL, a, b)
    private val twoRounds = listOf(t1, g1, t2, g2, g3, g4, g5, g6)

    private fun withBock(change: (BockRules) -> BockRules) = standard.copy(bock = change(standard.bock))

    private fun values(state: EveningState) = state.results.mapNotNull { it.breakdown?.value }

    @Test
    fun `Abend 2 anhaengen - die zweite Bockrunde verlaengert die erste`() {
        // t1: Zähler 4. g1 Bock (3). t2: 3 + 4 = 7. g2 bis g6 sind alle Bockspiele (2 bleiben offen).
        // Rufspiele 10 × 2 = 20, das Farbsolo 50 × 2 = 100.
        val state = EveningCalculator.compute(evening(twoRounds, firstDealer = a))

        assertEquals(listOf(20, 20, 100, 20, 20, 20), values(state))
        assertEquals(listOf(0, 1, 0, 1, 1, 1, 1, 1), state.results.map { it.bockExponent })
        // A und B: 20 + 20 − 100 + 20 + 20 + 20 = 0. C: −20 − 20 + 300 − 20 − 20 − 20 = 200. D: −200.
        assertEquals(listOf(0, 0, 200, -200), balances(state))
        assertEquals(listOf(2, 2, 1, 4), ranks(state))
        assertEquals(2, state.bockGamesRemaining)
        assertEquals(1, state.nextBockExponent)
        // Geber: t1 A (zusammengeworfen, weiter), g1 B, t2 C (nicht zusammengeworfen, C bleibt), g2 C, g3 D, g4 A, g5 B, g6 C.
        assertEquals(listOf(a, b, c, c, d, a, b, c), state.results.map { it.dealer })
        assertEquals(d, state.nextDealer)
    }

    @Test
    fun `Abend 2 stapeln - in der Ueberschneidung wird vervierfacht`() {
        // t1: eine Runde mit 4. g1 einfach Bock (3). t2: zweite Runde mit 4 dazu. g2, g3, g4 doppelt Bock
        // (erste Runde 2, 1, 0; zweite 3, 2, 1). g5 einfach Bock (0). g6 ohne Bock.
        // Werte: 20, 10 × 4 = 40, 50 × 4 = 200, 40, 20, 10.
        val rules = withBock { it.copy(stacking = BockStacking.STACK) }
        val state = EveningCalculator.compute(evening(twoRounds, firstDealer = a, rules = rules))

        assertEquals(listOf(20, 40, 200, 40, 20, 10), values(state))
        assertEquals(listOf(0, 1, 0, 2, 2, 2, 1, 0), state.results.map { it.bockExponent })
        // A und B: 20 + 40 − 200 + 40 + 20 + 10 = −70. C: −20 − 40 + 600 − 40 − 20 − 10 = 470.
        // D: −20 − 40 − 200 − 40 − 20 − 10 = −330. Summe −70 − 70 + 470 − 330 = 0.
        assertEquals(listOf(-70, -70, 470, -330), balances(state))
        assertEquals(0, state.bockGamesRemaining)

        // Nach g3 laufen noch beide Runden: Die nächsten zwei Spiele sind Bockspiele, das nächste doppelt.
        val afterG3 = EveningCalculator.compute(evening(twoRounds.take(5), firstDealer = a, rules = rules))
        assertEquals(2, afterG3.bockGamesRemaining)
        assertEquals(2, afterG3.nextBockExponent)
    }

    @Test
    fun `Abend 2 ignorieren - die zweiten sechs Nixer loesen nichts aus`() {
        // t1: 4. g1, g2, g3, g4 Bock. g5 und g6 ohne Bock. Werte: 20, 20, 100, 20, 10, 10.
        val rules = withBock { it.copy(stacking = BockStacking.IGNORE) }
        val state = EveningCalculator.compute(evening(twoRounds, firstDealer = a, rules = rules))

        assertEquals(listOf(20, 20, 100, 20, 10, 10), values(state))
        assertEquals(listOf(0, 1, 0, 1, 1, 1, 0, 0), state.results.map { it.bockExponent })
        // A und B: 20 + 20 − 100 + 20 + 10 + 10 = −20. C: −20 − 20 + 300 − 20 − 10 − 10 = 220. D: −180.
        assertEquals(listOf(-20, -20, 220, -180), balances(state))
        assertEquals(0, state.bockGamesRemaining)
    }

    @Test
    fun `Abend 2 ohne Bockrunde - abgeschaltet oder mit Laenge null oder negativ`() {
        // Kein Spiel ist ein Bockspiel: 10, 10, 50, 10, 10, 10.
        // A und B: 10 + 10 − 50 + 10 + 10 + 10 = 0. C: −10 − 10 + 150 − 10 − 10 − 10 = 100. D: −100.
        val variants = listOf(
            withBock { it.copy(enabled = false) },
            withBock { it.copy(gamesPerTrigger = 0) },
            withBock { it.copy(gamesPerTrigger = -3) },
        )
        for (rules in variants) {
            val state = EveningCalculator.compute(evening(twoRounds, firstDealer = a, rules = rules))
            assertEquals(listOf(10, 10, 50, 10, 10, 10), values(state))
            assertTrue(state.results.all { it.bockExponent == 0 })
            assertEquals(listOf(0, 0, 100, -100), balances(state))
            assertEquals(0, state.bockGamesRemaining)
            // Das zusammengeworfene Blatt der sechs Nixer lässt den Geber trotzdem weiterrücken.
            assertEquals(listOf(a, b, c, c, d, a, b, c), state.results.map { it.dealer })
        }
    }

    @Test
    fun `der Bock-Schalter von Hand - erzwungen, ausgenommen und beim Stapeln`() {
        // Ohne Zähler erzwungen: 10 × 2 = 20, der Zähler bleibt leer.
        val forcedGame = game("x", GameType.RUFSPIEL, a, b, bock = BockOverride.FORCE_ON)
        val forced = EveningCalculator.compute(evening(listOf(forcedGame, g1), a))
        assertEquals(listOf(20, 10), values(forced))
        assertEquals(0, forced.bockGamesRemaining)

        // Mit laufender Runde erzwungen: zählt wie ein gewöhnliches Bockspiel und verbraucht eines (4 − 1 = 3).
        val forcedInRound = EveningCalculator.compute(evening(listOf(t1, forcedGame), a))
        assertEquals(listOf(20), values(forcedInRound))
        assertEquals(3, forcedInRound.bockGamesRemaining)

        // Ausgenommen: kein Bock, und es wird nichts verbraucht (es bleiben 4).
        val exempt = EveningCalculator.compute(evening(listOf(t1, game("x", GameType.RUFSPIEL, a, b, bock = BockOverride.FORCE_OFF)), a))
        assertEquals(listOf(10), values(exempt))
        assertEquals(4, exempt.bockGamesRemaining)

        // Beim Stapeln mit zwei laufenden Runden bleibt ein erzwungenes Bockspiel doppelt: 10 × 4 = 40.
        val stacked = EveningCalculator.compute(
            evening(listOf(t1, t2, forcedGame), a, withBock { it.copy(stacking = BockStacking.STACK) }),
        )
        assertEquals(listOf(40), values(stacked))
        assertEquals(listOf(0, 0, 2), stacked.results.map { it.bockExponent })
    }

    @Test
    fun `ein zusammengeworfenes Blatt verbraucht nur dann ein Bockspiel, wenn es so vereinbart ist`() {
        val entries = listOf(t1, thrownIn("z1"), g1, thrownIn("z2"), g2, g4)

        // Standard: Die zusammengeworfenen Blätter zählen nicht. g1, g2, g4 sind Bockspiele, eines bleibt offen.
        val standardState = EveningCalculator.compute(evening(entries, firstDealer = a))
        assertEquals(listOf(20, 20, 20), values(standardState))
        assertEquals(1, standardState.bockGamesRemaining)

        // Vereinbart: 4, z1 → 3, g1 → 2, z2 → 1, g2 → 0, g4 ohne Bock.
        val consuming = EveningCalculator.compute(evening(entries, a, withBock { it.copy(thrownInConsumesBockGame = true) }))
        assertEquals(listOf(20, 20, 10), values(consuming))
        assertEquals(0, consuming.bockGamesRemaining)
    }

    // ---------------------------------------------------------------- Geberfolge

    @Test
    fun `Geberfolge - bleibt der Geber nach dem Zusammenwerfen, wechselt er nur nach gespielten Spielen`() {
        val rules = standard.copy(dealerAdvancesAfterThrowIn = false)
        val entries = listOf(thrownIn("z1"), sixNixer("t", thrownIn = true), g1, thrownIn("z2"), g2)
        val state = EveningCalculator.compute(evening(entries, firstDealer = c, rules = rules))

        // C gibt, bis das erste Spiel gespielt ist; dann D bis zum zweiten Spiel; danach A.
        assertEquals(listOf(c, c, c, d, d), state.results.map { it.dealer })
        assertEquals(a, state.nextDealer)
    }

    @Test
    fun `Geberfolge - einmal rund um den Tisch und weiter`() {
        val games = (1..6).map { game("r$it", GameType.RUFSPIEL, a, b) }
        val state = EveningCalculator.compute(evening(games, firstDealer = d))
        assertEquals(listOf(d, a, b, c, d, a), state.results.map { it.dealer })
        assertEquals(b, state.nextDealer)
    }

    // ---------------------------------------------------------------- Abend 3: Topf-Teilung

    @Test
    fun `Abend 3 mit Topf-Teilung - Rest-Cent, Salden, Plaetze, Geber und wer zahlt wem`() {
        val rules = standard.copy(payoutRule = PayoutRule.POT_SPLIT)
        val entries = listOf(
            game("p1", GameType.WENZ, c),
            game("p2", GameType.FARBSOLO, a, won = false, level = ResultLevel.SCHNEIDER, gelegt = setOf(c)),
            game("p3", GameType.RUFSPIEL, b, d, level = ResultLevel.SCHWARZ),
            game("p4", GameType.GEIER, d, won = false, laufende = 2),
        )
        val state = EveningCalculator.compute(evening(entries, firstDealer = d, rules = rules))

        assertRows(
            listOf(
                // p1 Wenz C gewonnen: T = 50; 50 : 3 = 16 Rest 2, die Gegner A, B, D zahlen 17, 17, 16.
                Row(d, 1, 0, 50, listOf(-17, -17, 50, -16), listOf(-17, -17, 50, -16)),
                // p2 Farbsolo A verloren, Schneider, einmal gelegt: T = 60 × 2 = 120; B, C, D bekommen je 40.
                Row(a, 2, 0, 120, listOf(-120, 40, 40, 40), listOf(-137, 23, 90, 24)),
                // p3 Rufspiel B mit D gewonnen, Schwarz: T = 30, je ±30 wie immer.
                Row(b, 3, 0, 30, listOf(-30, 30, -30, 30), listOf(-167, 53, 60, 54)),
                // p4 Geier D verloren, zwei Laufende: T = 70; 70 : 3 = 23 Rest 1, A, B, C bekommen 24, 23, 23.
                Row(c, 4, 0, 70, listOf(24, 23, 23, -70), listOf(-143, 76, 83, -16)),
            ),
            state,
        )
        // −143 + 76 + 83 − 16 = 0.
        assertEquals(listOf(-143, 76, 83, -16), balances(state))
        assertEquals(listOf(4, 2, 1, 3), ranks(state))
        assertEquals(d, state.nextDealer)
        // A (−143) zahlt an C 83 und an B 60; dann zahlt D (−16) an B die restlichen 16.
        assertEquals(
            listOf(Transfer(a, c, 83), Transfer(a, b, 60), Transfer(d, b, 16)),
            SettleUp.transfers(state.balances),
        )
    }
}
