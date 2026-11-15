package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.BockRules
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.PayoutRule
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Abende aus beschädigten Daten: leere, falsche oder doppelte Sitzordnung, unbekannte Spieler, unsinnige Werte im
 * Regelwerk, riesige freie Buchungen.
 *
 * Dafür gilt immer: [EveningCalculator.compute] und [SettleUp] werfen nie. Ein Spiel bucht entweder ein
 * Nullsummenspiel oder nichts; bucht es nichts, ist es als fehlerhaft markiert. Eine freie Buchung, die nicht
 * aufgeht, ist als fehlerhaft markiert. Die Salden sind immer genau die Summe der gebuchten Beträge (als `Long`
 * nachgerechnet, damit ein Überlauf nicht täuscht).
 */
class EveningCalculatorRobustnessTest {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val e = PlayerId("E")
    private val x = PlayerId("X")
    private val table = listOf(a, b, c, d)
    private val now: Instant = Instant.parse("2026-11-15T19:00:00Z")
    private val standard = RuleSet.standard()
    private var nextId = 0

    private fun id() = EntryId("e${nextId++}")

    private fun evening(
        entries: List<EveningEntry>,
        seating: List<PlayerId> = table,
        firstDealer: PlayerId = a,
        rules: RuleSet = standard,
    ) = Evening(EveningId("abend"), startedAt = now, seating = seating, firstDealer = firstDealer, rules = rules, entries = entries)

    private fun rufspiel(declarer: PlayerId = a, partner: PlayerId? = b, klopfer: Set<PlayerId> = emptySet()) =
        GameEntry(id(), now, GameType.RUFSPIEL, declarer = declarer, partner = partner, declarerWon = true, klopfer = klopfer)

    private fun solo(declarer: PlayerId, type: GameType = GameType.FARBSOLO) =
        GameEntry(id(), now, type, declarer = declarer, declarerWon = true)

    private fun thrownIn() = ThrownInEntry(id(), now)

    private fun sixNixer(thrownIn: Boolean = true) =
        BockTriggerEntry(id(), now, BockReason.SECHS_NIXER, player = null, handThrownIn = thrownIn)

    private fun manual(vararg amounts: Pair<PlayerId, Int>) = ManualEntry(id(), now, mapOf(*amounts), "frei")

    private fun exactSum(amounts: Map<PlayerId, Int>): Long = amounts.values.sumOf { it.toLong() }

    // ---------------------------------------------------------------- Sitzordnung

    @Test
    fun `leere Sitzordnung - kein Absturz, nichts wird gebucht, die Spiele sind als fehlerhaft markiert`() {
        // Vor der Korrektur: IndexOutOfBoundsException schon beim Geber des ersten Eintrags.
        val broken = evening(
            listOf(rufspiel(), thrownIn(), sixNixer(), manual(a to 10, b to -10), solo(c)),
            seating = emptyList(),
            firstDealer = c,
        )
        val state = EveningCalculator.compute(broken)

        assertEquals(5, state.results.size)
        assertEquals(emptyMap<PlayerId, Int>(), state.balances)
        assertEquals(emptyMap<PlayerId, Int>(), state.ranks)
        assertTrue(state.results.all { it.amounts.isEmpty() && it.balances.isEmpty() })
        // Ohne Sitzordnung gibt es keine Geberfolge: Es bleibt beim eingetragenen ersten Geber.
        assertTrue(state.results.all { it.dealer == c })
        assertEquals(c, state.nextDealer)
        // Die Spiele sind gezählt, aber nicht abgerechnet und als fehlerhaft markiert.
        assertEquals(listOf(1, null, null, null, 2), state.results.map { it.gameNumber })
        assertEquals(2, state.gamesPlayed)
        for (index in listOf(0, 4)) {
            assertNull(state.results[index].breakdown)
            assertTrue(EntryIssue.RULE_VIOLATION in state.results[index].issues)
        }
        assertTrue(EntryIssue.UNKNOWN_PLAYER in state.results[3].issues)
        // Der Bock-Zähler läuft unabhängig davon: vier Bockspiele, eines davon gespielt.
        assertEquals(3, state.bockGamesRemaining)
        assertEquals(emptyList<Transfer>(), SettleUp.transfers(state.balances))
        assertTrue(SettleUp.isBalanced(state.balances))
    }

    @Test
    fun `leere Sitzordnung ohne Eintraege - kein Absturz`() {
        // Vor der Korrektur: IndexOutOfBoundsException beim nächsten Geber.
        val state = EveningCalculator.compute(evening(emptyList(), seating = emptyList(), firstDealer = b))
        assertEquals(b, state.nextDealer)
        assertEquals(emptyList<EntryResult>(), state.results)
        assertEquals(0, state.gamesPlayed)
    }

    @Test
    fun `doppelt besetzter Platz - das Spiel wird nicht gebucht statt falsch`() {
        // A sitzt doppelt. Vor der Korrektur wurde gebucht: A +10, B +10, C −30 (zehn Cent verschwunden).
        val twice = EveningCalculator.compute(evening(listOf(rufspiel(a, b)), seating = listOf(a, a, b, c)))
        val result = twice.results.single()
        assertEquals(mapOf(a to 0, b to 0, c to 0), result.amounts)
        assertNull(result.breakdown)
        assertTrue(EntryIssue.RULE_VIOLATION in result.issues)
        assertEquals(1, result.gameNumber)
        assertEquals(mapOf(a to 0, b to 0, c to 0), twice.balances)

        // C sitzt doppelt. Vor der Korrektur: A +10, B +10, C −10 (zehn Cent entstanden).
        val other = EveningCalculator.compute(evening(listOf(rufspiel(a, b), solo(c)), seating = listOf(a, b, c, c)))
        assertTrue(other.results.all { it.amounts == mapOf(a to 0, b to 0, c to 0) && it.breakdown == null })
        assertTrue(other.results.all { EntryIssue.RULE_VIOLATION in it.issues })

        // Eine freie Buchung zwischen zwei Spielern, die am Tisch sitzen, geht trotzdem.
        val withManual = EveningCalculator.compute(evening(listOf(manual(a to 5, b to -5)), seating = listOf(a, a, b, c)))
        assertEquals(mapOf(a to 5, b to -5, c to 0), withManual.balances)
        assertEquals(emptyList<EntryIssue>(), withManual.results.single().issues)
    }

    @Test
    fun `drei am Tisch - gemeldet, zwei gegen einen bleibt ein Nullsummenspiel`() {
        // So war es schon vor der Korrektur (der CSV-Export rechnet damit): A und B je +10, C zahlt 20.
        val state = EveningCalculator.compute(evening(listOf(rufspiel(a, b)), seating = listOf(a, b, c)))
        assertEquals(mapOf(a to 10, b to 10, c to -20), state.balances)
        assertTrue(EntryIssue.RULE_VIOLATION in state.results.single().issues)
    }

    @Test
    fun `fuenf am Tisch und ein ungerader Tarif - kein Cent geht verloren`() {
        // Rufspiel zu 5 Cent: Die drei Verlierer zahlen zusammen 15, die Gewinner bekommen 8 und 7.
        // Vor der Korrektur bekamen beide 7.
        val rules = standard.copy(prices = mapOf(GameType.RUFSPIEL to 5))
        val state = EveningCalculator.compute(evening(listOf(rufspiel(a, b)), seating = listOf(a, b, c, d, e), rules = rules))
        assertEquals(mapOf(a to 8, b to 7, c to -5, d to -5, e to -5), state.balances)
        assertTrue(EntryIssue.RULE_VIOLATION in state.results.single().issues)
    }

    @Test
    fun `erster Geber sitzt nicht am Tisch - es beginnt der erste Platz`() {
        val state = EveningCalculator.compute(evening(listOf(rufspiel(), rufspiel()), firstDealer = x))
        assertEquals(listOf(a, b), state.results.map { it.dealer })
        assertEquals(c, state.nextDealer)
    }

    // ---------------------------------------------------------------- Spiele, die sich nicht abrechnen lassen

    @Test
    fun `nicht abrechenbare Spiele buchen nichts, zaehlen aber als gespielt`() {
        val unknownDeclarer = solo(x)
        val noPartner = rufspiel(a, partner = null)
        val unknownPartner = rufspiel(a, partner = x)
        val ownPartner = rufspiel(a, partner = a)
        val state = EveningCalculator.compute(
            evening(listOf(sixNixer(), unknownDeclarer, noPartner, unknownPartner, ownPartner, rufspiel())),
        )

        for (result in state.results.subList(1, 5)) {
            assertEquals(table.associateWith { 0 }, result.amounts)
            assertNull(result.breakdown)
            assertTrue(EntryIssue.RULE_VIOLATION in result.issues)
            assertEquals(1, result.bockExponent)
        }
        // Sie zählen als gespielt: Nummer, Geberwechsel und je ein verbrauchtes Bockspiel.
        assertEquals(listOf(null, 1, 2, 3, 4, 5), state.results.map { it.gameNumber })
        assertEquals(listOf(a, b, c, d, a, b), state.results.map { it.dealer })
        // Die vier Bockspiele sind damit verbraucht: Das letzte Rufspiel kostet wieder 10.
        assertEquals(mapOf(a to 10, b to 10, c to -10, d to -10), state.balances)
        assertEquals(0, state.bockGamesRemaining)
    }

    @Test
    fun `gelegt hat jemand, der nicht am Tisch sitzt - das Spiel ist als fehlerhaft markiert`() {
        // Der Unbekannte verdoppelt den Betrag (10 × 2 = 20). Vor der Korrektur war das nirgends vermerkt.
        val state = EveningCalculator.compute(evening(listOf(rufspiel(a, b, klopfer = setOf(x)), rufspiel(a, b, klopfer = setOf(c)))))
        assertEquals(mapOf(a to 20, b to 20, c to -20, d to -20), state.results[0].amounts)
        assertEquals(listOf(EntryIssue.UNKNOWN_PLAYER), state.results[0].issues)
        // Ein Leger vom Tisch ist in Ordnung.
        assertEquals(emptyList<EntryIssue>(), state.results[1].issues)
    }

    // ---------------------------------------------------------------- Unsinnige Werte im Regelwerk

    @Test
    fun `riesiger Grundwert - das Spiel wird gemeldet und nicht gebucht`() {
        // Farbsolo zu 8 Mio. Euro: Der dreifache Betrag des Solisten passt in keinen Int.
        // Vor der Korrektur: A −1 894 967 296, die anderen je −800 000 000, und nichts war gemeldet.
        val rules = standard.copy(prices = mapOf(GameType.FARBSOLO to 800_000_000))
        val state = EveningCalculator.compute(evening(listOf(solo(a), rufspiel(c, d)), rules = rules))

        assertEquals(table.associateWith { 0 }, state.results[0].amounts)
        assertNull(state.results[0].breakdown)
        assertEquals(listOf(EntryIssue.RULE_VIOLATION), state.results[0].issues)
        // Das Rufspiel danach (10 Cent, Standardwert) wird normal gebucht.
        assertEquals(mapOf(a to -10, b to -10, c to 10, d to 10), state.balances)
        assertEquals(emptyList<EntryIssue>(), state.results[1].issues)
    }

    @Test
    fun `negativer Grundwert mit Topf-Teilung - nichts wird gebucht statt zwei Cent zu verlieren`() {
        // Vor der Korrektur: B −50, A, C und D je +16, zusammen −2, und nichts war gemeldet.
        val rules = standard.copy(prices = mapOf(GameType.WENZ to -50), payoutRule = PayoutRule.POT_SPLIT)
        val state = EveningCalculator.compute(evening(listOf(solo(b, GameType.WENZ)), rules = rules))
        assertEquals(table.associateWith { 0 }, state.balances)
        assertEquals(listOf(EntryIssue.RULE_VIOLATION), state.results.single().issues)
        assertNull(state.results.single().breakdown)
    }

    @Test
    fun `vierzig gestapelte Bockrunden - das Spiel wird gemeldet statt kostenlos`() {
        // Vor der Korrektur lief 2⁴⁰ auf 0 über: Das Spiel kostete nichts und nichts war gemeldet.
        val rules = standard.copy(bock = standard.bock.copy(stacking = BockStacking.STACK))
        val state = EveningCalculator.compute(evening(List(40) { sixNixer(thrownIn = false) } + rufspiel(), rules = rules))
        val game = state.results.last()
        assertEquals(40, game.bockExponent)
        assertEquals(table.associateWith { 0 }, game.amounts)
        assertNull(game.breakdown)
        assertEquals(listOf(EntryIssue.RULE_VIOLATION), game.issues)
    }

    @Test
    fun `unsinnig lange Bockrunde - der Zaehler laeuft beim Anhaengen nicht ins Negative`() {
        // Zweimal sechs Nixer mit einer Bockrunde von 2 147 483 647 Spielen. Vor der Korrektur ergab die Summe −2:
        // Der Zähler war negativ und die Bockrunde nach einem einzigen Spiel vorbei.
        val rules = standard.copy(bock = standard.bock.copy(gamesPerTrigger = Int.MAX_VALUE))
        val triggers = listOf(sixNixer(thrownIn = false), sixNixer(thrownIn = false))
        assertEquals(Int.MAX_VALUE, EveningCalculator.compute(evening(triggers, rules = rules)).bockGamesRemaining)

        val state = EveningCalculator.compute(evening(triggers + rufspiel() + rufspiel(), rules = rules))
        assertEquals(listOf(0, 0, 1, 1), state.results.map { it.bockExponent })
        assertEquals(mapOf(a to 40, b to 40, c to -40, d to -40), state.balances)
        assertEquals(Int.MAX_VALUE - 2, state.bockGamesRemaining)
    }

    // ---------------------------------------------------------------- Freie Buchungen und Salden

    @Test
    fun `freie Buchung - eine Summe, die nur durch Ueberlauf null ergaebe, wird gemeldet`() {
        // −2 147 483 648 zweimal ergibt in 32 Bit scheinbar 0. Vor der Korrektur war die Buchung nicht gemeldet.
        val low = EveningCalculator.compute(evening(listOf(manual(a to Int.MIN_VALUE, b to Int.MIN_VALUE))))
        assertEquals(listOf(EntryIssue.AMOUNTS_NOT_ZERO_SUM), low.results.single().issues)
        assertFalse(SettleUp.isBalanced(low.balances))

        // 2 147 483 647 + 2 147 483 647 + 2 ergibt in 32 Bit ebenfalls scheinbar 0.
        val high = EveningCalculator.compute(evening(listOf(manual(a to Int.MAX_VALUE, b to Int.MAX_VALUE, c to 2))))
        assertEquals(listOf(EntryIssue.AMOUNTS_NOT_ZERO_SUM), high.results.single().issues)
        assertFalse(SettleUp.isBalanced(high.balances))
    }

    @Test
    fun `freie Buchung an Unbekannte oder mit falscher Summe - gemeldet, der Rest wird gebucht`() {
        val state = EveningCalculator.compute(evening(listOf(manual(a to 100, x to -100), manual(a to 30, b to -20))))
        assertEquals(listOf(EntryIssue.UNKNOWN_PLAYER), state.results[0].issues)
        assertEquals(listOf(EntryIssue.AMOUNTS_NOT_ZERO_SUM), state.results[1].issues)
        assertEquals(mapOf(a to 130, b to -20, c to 0, d to 0), state.balances)
        // Dass die Salden nicht aufgehen, ist erkennbar; ausgeglichen wird, so weit es geht.
        assertFalse(SettleUp.isBalanced(state.balances))
        assertEquals(listOf(Transfer(b, a, 20)), SettleUp.transfers(state.balances))
    }

    @Test
    fun `ein Saldo, der nicht mehr in den Zahlenbereich passt - der Eintrag wird gemeldet und nicht gebucht`() {
        val big = 2_000_000_000
        // Zweimal +2 Mrd. für A passt nicht in einen Int. Vor der Korrektur lief der Saldo über: A −294 967 296
        // und B +294 967 296, also Gewinner und Verlierer vertauscht, und nichts war gemeldet.
        val state = EveningCalculator.compute(
            evening(listOf(manual(a to big, b to -big), manual(a to big, b to -big), rufspiel(c, d))),
        )
        assertEquals(emptyList<EntryIssue>(), state.results[0].issues)
        assertEquals(listOf(EntryIssue.BALANCE_OUT_OF_RANGE), state.results[1].issues)
        assertEquals(table.associateWith { 0 }, state.results[1].amounts)
        assertEquals(mapOf(a to big, b to -big, c to 0, d to 0), state.results[1].balances)
        // Danach wird normal weitergerechnet: Rufspiel C mit D gewonnen, je ±10.
        assertEquals(mapOf(a to 1_999_999_990, b to -2_000_000_010, c to 10, d to 10), state.balances)
        assertTrue(SettleUp.isBalanced(state.balances))
    }

    @Test
    fun `ein Spiel, das einen Saldo sprengen wuerde, wird gemeldet und nicht gebucht`() {
        // 26 gestapelte Bockrunden: 10 × 2²⁶ = 671 088 640 je Spiel. Drei Spiele passen (2 013 265 920),
        // das vierte (2 684 354 560) nicht mehr.
        val rules = standard.copy(bock = standard.bock.copy(stacking = BockStacking.STACK))
        val state = EveningCalculator.compute(
            evening(List(26) { sixNixer(thrownIn = false) } + List(4) { rufspiel() }, rules = rules),
        )
        val games = state.results.takeLast(4)
        assertTrue(games.take(3).all { it.issues.isEmpty() && it.breakdown?.value == 671_088_640 })
        assertEquals(listOf(EntryIssue.BALANCE_OUT_OF_RANGE), games[3].issues)
        assertNull(games[3].breakdown)
        assertEquals(table.associateWith { 0 }, games[3].amounts)
        assertEquals(mapOf(a to 2_013_265_920, b to 2_013_265_920, c to -2_013_265_920, d to -2_013_265_920), state.balances)
        assertEquals(4, state.gamesPlayed)
    }

    // ---------------------------------------------------------------- Wer zahlt wem

    @Test
    fun `wer zahlt wem - auch die groesste denkbare Schuld ergibt keine negative Zahlung`() {
        // −(−2 147 483 648) passt nicht in einen Int. Vor der Korrektur: eine Zahlung über −2 147 483 648.
        assertEquals(listOf(Transfer(a, b, 5)), SettleUp.transfers(mapOf(a to Int.MIN_VALUE, b to 5)))
        // A schuldet 2 147 483 648: an B 2 147 483 647 und an C 1.
        val balances = mapOf(a to Int.MIN_VALUE, b to Int.MAX_VALUE, c to 1)
        assertTrue(SettleUp.isBalanced(balances))
        assertEquals(listOf(Transfer(a, b, Int.MAX_VALUE), Transfer(a, c, 1)), SettleUp.transfers(balances))
    }

    @Test
    fun `ob die Salden aufgehen, wird ohne Ueberlauf geprueft`() {
        // Vor der Korrektur galten beide als ausgeglichen, weil die Summe in 32 Bit auf 0 überläuft.
        assertFalse(SettleUp.isBalanced(mapOf(a to Int.MIN_VALUE, b to Int.MIN_VALUE)))
        assertFalse(SettleUp.isBalanced(mapOf(a to Int.MAX_VALUE, b to Int.MAX_VALUE, c to 2)))
        assertTrue(SettleUp.isBalanced(mapOf(a to Int.MAX_VALUE, b to -Int.MAX_VALUE)))
    }

    @Test
    fun `wer zahlt wem - Salden, die nicht aufgehen`() {
        // Zu viel Schuld: B bekommt seine 60, die übrigen 40 von A bleiben offen.
        assertEquals(listOf(Transfer(a, b, 60)), SettleUp.transfers(mapOf(a to -100, b to 60)))
        // Nur Schuldner oder nur Gläubiger: keine Zahlung.
        assertEquals(emptyList<Transfer>(), SettleUp.transfers(mapOf(a to -10, b to -20)))
        assertEquals(emptyList<Transfer>(), SettleUp.transfers(mapOf(a to 10, b to 20)))
        assertEquals(emptyList<Transfer>(), SettleUp.transfers(emptyMap()))
    }

    @Test
    fun `wer zahlt wem - beliebige Salden - endet immer, hoechstens n minus 1 Zahlungen, nie ein negativer Betrag`() {
        val random = Random(77)
        val extremes = listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, -1_000_000_000, -1, 0, 0, 1, 1_000_000_000, Int.MAX_VALUE)
        repeat(5_000) {
            val players = List(random.nextInt(0, 8)) { PlayerId("P$it") }
            val balances = players.associateWith { if (random.nextInt(3) == 0) extremes.random(random) else random.nextInt(-5_000, 5_000) }

            val transfers = SettleUp.transfers(balances)

            assertTrue(transfers.size <= maxOf(0, players.size - 1), "zu viele Zahlungen bei $balances")
            val open = balances.mapValues { it.value.toLong() }.toMutableMap()
            for (transfer in transfers) {
                assertTrue(transfer.amount > 0, "Zahlung ohne Betrag bei $balances")
                assertTrue(open.getValue(transfer.from) < 0 && open.getValue(transfer.to) > 0, "falsche Richtung bei $balances")
                open[transfer.from] = open.getValue(transfer.from) + transfer.amount
                open[transfer.to] = open.getValue(transfer.to) - transfer.amount
                // Niemand zahlt mehr, als er schuldet, und niemand bekommt mehr, als ihm zusteht.
                assertTrue(open.getValue(transfer.from) <= 0 && open.getValue(transfer.to) >= 0, "zu viel gezahlt bei $balances")
            }
            // So weit wie möglich ausgeglichen: Es gibt nicht mehr zugleich einen Schuldner und einen Gläubiger.
            assertFalse(open.values.any { it < 0 } && open.values.any { it > 0 }, "nicht fertig ausgeglichen bei $balances")
            val balanced = balances.values.sumOf { it.toLong() } == 0L
            assertEquals(balanced, SettleUp.isBalanced(balances))
            if (balanced) assertTrue(open.values.all { it == 0L }, "nicht ausgeglichen bei $balances")
        }
    }

    // ---------------------------------------------------------------- Alles durcheinander

    private val pool = listOf(a, b, c, d, e, x, PlayerId(""))
    private val extremes = listOf(
        Int.MIN_VALUE, Int.MIN_VALUE + 1, -2_000_000_000, -1_000_000, -100, -10, -2, -1, 0, 1, 2, 3, 4, 5, 8, 9, 10, 50, 1_000,
        1_000_000, 715_827_882, 715_827_883, 1_073_741_824, 2_000_000_000, Int.MAX_VALUE - 1, Int.MAX_VALUE,
    )

    private fun Random.anyInt(): Int = when (nextInt(4)) {
        0 -> extremes.random(this)
        1 -> nextInt()
        else -> nextInt(-20, 200)
    }

    private fun Random.sometimes(usual: Int): Int = if (nextInt(4) == 0) anyInt() else usual

    private fun Random.brokenRules(): RuleSet = standard.copy(
        unit = sometimes(standard.unit),
        prices = GameType.entries.filter { nextInt(6) != 0 }.associateWith { sometimes(standard.price(it)) },
        laufendeMin = GameType.entries.filter { nextInt(6) != 0 }.associateWith { sometimes(standard.laufendeMin(it)) },
        laufendeMax = GameType.entries.filter { nextInt(6) != 0 }.associateWith { sometimes(standard.laufendeMax(it)) },
        enabledTypes = GameType.entries.filter { nextInt(5) != 0 }.toSet(),
        klopfenEnabled = nextInt(4) != 0,
        kontraEnabled = nextInt(4) != 0,
        retourEnabled = nextInt(4) != 0,
        toutFactor = sometimes(2),
        sieFactor = sometimes(2),
        sieLaufende = sometimes(8),
        bock = BockRules(
            enabled = nextInt(5) != 0,
            gamesPerTrigger = sometimes(4),
            factor = sometimes(2),
            stacking = BockStacking.entries.random(this),
            thrownInConsumesBockGame = nextBoolean(),
        ),
        dealerAdvancesAfterThrowIn = nextBoolean(),
        payoutRule = PayoutRule.entries.random(this),
    )

    /** Meist einer vom Tisch, manchmal irgendwer (auch Unbekannte und die leere Kennung). */
    private fun Random.player(): PlayerId = if (nextInt(6) == 0) pool.random(this) else table.random(this)

    private fun Random.brokenEntry(): EveningEntry = when (nextInt(12)) {
        0 -> thrownIn()
        1, 2 -> BockTriggerEntry(
            id(), now, BockReason.entries.random(this), player = player().takeIf { nextBoolean() }, handThrownIn = nextBoolean(),
        )
        3 -> {
            // Eine freie Buchung, die aufgeht – mit gewöhnlichen oder mit riesigen Beträgen.
            val amount = if (nextInt(3) == 0) extremes.random(this) else nextInt(-500, 500)
            val (from, to) = pool.shuffled(this).take(2)
            if (amount == Int.MIN_VALUE) {
                manual(from to amount, to to Int.MAX_VALUE, player() to 1)
            } else {
                manual(from to amount, to to -amount)
            }
        }
        4 -> ManualEntry(id(), now, List(nextInt(0, 5)) { player() }.associateWith { anyInt() }, "Unsinn")
        else -> {
            val type = GameType.entries.random(this)
            val declarer = player()
            GameEntry(
                id = id(),
                recordedAt = now,
                type = type,
                declarer = declarer,
                partner = when {
                    nextInt(8) == 0 -> player()
                    type.isSolo -> null
                    nextInt(12) == 0 -> null
                    else -> (table - declarer).random(this)
                },
                declarerWon = nextBoolean(),
                level = ResultLevel.entries.random(this),
                laufende = if (nextInt(8) == 0) anyInt() else nextInt(0, 9),
                announcement = if (nextInt(8) == 0) Announcement.entries.random(this) else Announcement.NONE,
                klopfer = pool.filter { nextInt(5) == 0 }.toSet(),
                kontra = KontraLevel.entries.random(this),
                kontraBy = player().takeIf { nextInt(4) == 0 },
                bockOverride = BockOverride.entries.random(this),
            )
        }
    }

    private fun Random.brokenEvening(): Evening = evening(
        entries = List(nextInt(0, 40)) { brokenEntry() },
        seating = when (nextInt(5)) {
            0 -> List(nextInt(0, 7)) { pool.random(this) }
            1 -> pool.shuffled(this).take(nextInt(0, 7))
            else -> table
        },
        firstDealer = player(),
        rules = if (nextInt(3) == 0) standard.copy(payoutRule = PayoutRule.entries.random(this)) else brokenRules(),
    )

    @Test
    fun `beliebiger Unsinn - nie eine Ausnahme, kein Betrag verschwindet oder entsteht unbemerkt`() {
        val random = Random(20261115)
        var unbooked = 0
        var booked = 0
        repeat(1_500) {
            val evening = random.brokenEvening()
            val state = EveningCalculator.compute(evening)
            val seats = evening.seating.toSet()

            assertEquals(evening.entries.size, state.results.size)
            val exact = seats.associateWith { 0L }.toMutableMap()
            for ((index, result) in state.results.withIndex()) {
                val context = "Eintrag ${index + 1} (${result.entry}) in $evening"
                assertEquals(seats, result.amounts.keys, context)
                val sum = exactSum(result.amounts)

                when (val entry = result.entry) {
                    is GameEntry -> {
                        // Ein Spiel bucht ein Nullsummenspiel oder nichts.
                        assertEquals(0L, sum, context)
                        val breakdown = result.breakdown
                        if (breakdown == null) {
                            unbooked++
                            assertTrue(result.amounts.values.all { it == 0 }, context)
                            assertTrue(result.issues.isNotEmpty(), context)
                        } else {
                            booked++
                            assertTrue(breakdown.value >= 0, context)
                            // Gewinner bekommen nie etwas abgezogen, Verlierer nie etwas dazu.
                            assertTrue(entry.winners(evening.seating).all { result.amounts.getValue(it) >= 0 }, context)
                            assertTrue(entry.losers(evening.seating).all { result.amounts.getValue(it) <= 0 }, context)
                        }
                    }

                    is ManualEntry -> if (sum != 0L || exactSum(entry.amounts) != 0L) assertTrue(result.issues.isNotEmpty(), context)
                    is ThrownInEntry, is BockTriggerEntry -> assertTrue(result.amounts.values.all { it == 0 }, context)
                }

                // Die Salden sind genau die Summe der gebuchten Beträge – nichts läuft über.
                for ((player, amount) in result.amounts) exact[player] = exact.getValue(player) + amount
                assertEquals(exact, result.balances.mapValues { it.value.toLong() }, context)
                assertEquals(seats, result.ranks.keys, context)
                if (seats.isEmpty()) {
                    assertEquals(evening.firstDealer, result.dealer, context)
                } else {
                    assertTrue(result.dealer in seats, context)
                }
                assertTrue(result.bockExponent >= 0, context)
            }

            assertEquals(exact, state.balances.mapValues { it.value.toLong() })
            // Gehen die Salden nicht auf, erklärt das vollständig eine als fehlerhaft markierte freie Buchung.
            val unexplained = exact.values.sum() - state.results.filter { it.issues.isNotEmpty() }.sumOf { exactSum(it.amounts) }
            assertEquals(0L, unexplained, "Betrag verschwunden oder entstanden in $evening")
            assertEquals(exact.values.sum() == 0L, SettleUp.isBalanced(state.balances))

            val gameCount = evening.entries.count { it is GameEntry }
            assertEquals(gameCount, state.gamesPlayed)
            assertEquals((1..gameCount).toList(), state.results.mapNotNull { it.gameNumber })
            assertTrue(state.bockGamesRemaining >= 0 && state.nextBockExponent >= 0, "Bock-Zähler in $evening")
            if (seats.isEmpty()) assertEquals(evening.firstDealer, state.nextDealer) else assertTrue(state.nextDealer in seats)

            val transfers = SettleUp.transfers(state.balances)
            assertTrue(transfers.all { it.amount > 0 } && transfers.size <= maxOf(0, seats.size - 1), "Zahlungen in $evening")
        }
        assertTrue(booked > 5_000 && unbooked > 1_000, "Der Test hat beide Fälle wirklich geprüft ($booked gebucht, $unbooked nicht)")
    }

    @Test
    fun `beliebige Aenderungen - danach stimmt alles so, als waere der Abend gleich so eingetragen worden`() {
        val random = Random(5)
        repeat(300) {
            var edited = evening(List(random.nextInt(0, 12)) { random.brokenEntry() }, rules = standard)
            val expected = edited.entries.toMutableList()
            repeat(20) {
                when (random.nextInt(4)) {
                    0 -> random.brokenEntry().let { entry ->
                        edited = edited.append(entry)
                        expected.add(entry)
                    }

                    1 -> if (expected.isNotEmpty()) {
                        val index = random.nextInt(expected.size)
                        val replacement = when (val entry = random.brokenEntry()) {
                            is GameEntry -> entry.copy(id = expected[index].id)
                            is ThrownInEntry -> entry.copy(id = expected[index].id)
                            is BockTriggerEntry -> entry.copy(id = expected[index].id)
                            is ManualEntry -> entry.copy(id = expected[index].id)
                        }
                        edited = edited.replace(replacement)
                        expected[index] = replacement
                    }

                    2 -> if (expected.isNotEmpty()) {
                        val index = random.nextInt(expected.size)
                        edited = edited.remove(expected[index].id)
                        expected.removeAt(index)
                    }

                    else -> random.brokenEntry().let { entry ->
                        val index = random.nextInt(expected.size + 1)
                        edited = edited.insertAt(index, entry)
                        expected.add(index, entry)
                    }
                }
                assertEquals(expected, edited.entries)
            }
            // Derselbe Abend, in einem Zug mit der fertigen Liste angelegt, ergibt exakt denselben Stand.
            val fresh = evening(expected.toList(), rules = standard)
            assertEquals(EveningCalculator.compute(fresh), EveningCalculator.compute(edited))
        }
    }
}
