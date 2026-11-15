package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.PayoutRule
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

class EveningCalculatorTest {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val seating = listOf(a, b, c, d)
    private val now: Instant = Instant.parse("2026-11-14T19:00:00Z")
    private var nextId = 0

    private fun id() = EntryId("e${nextId++}")

    private fun evening(vararg entries: EveningEntry, rules: RuleSet = RuleSet.sauspiel(), firstDealer: PlayerId = a) =
        Evening(
            id = EveningId("abend"),
            startedAt = now,
            seating = seating,
            firstDealer = firstDealer,
            rules = rules,
            entries = entries.toList(),
        )

    /** Rufspiel A mit B gewonnen, ohne Zusätze: Wert 20. */
    private fun rufspiel(override: BockOverride = BockOverride.AUTO) = GameEntry(
        id = id(), recordedAt = now, type = GameType.RUFSPIEL, declarer = a, partner = b, declarerWon = true,
        bockOverride = override,
    )

    private fun thrownIn() = ThrownInEntry(id(), now)

    private fun bockTrigger(handThrownIn: Boolean = true) =
        BockTriggerEntry(id(), now, BockReason.SECHS_NIXER, player = c, handThrownIn = handThrownIn)

    private fun valuesOfA(state: EveningState) = state.results.filter { it.entry is GameEntry }.map { it.amounts.getValue(a) }

    @Test
    fun `leerer Abend`() {
        val state = EveningCalculator.compute(evening(firstDealer = c))
        assertEquals(seating.associateWith { 0 }, state.balances)
        assertEquals(seating.associateWith { 1 }, state.ranks)
        assertEquals(c, state.nextDealer)
        assertEquals(0, state.gamesPlayed)
        assertEquals(0, state.bockGamesRemaining)
    }

    @Test
    fun `Salden laufen mit und die Spiele werden durchnummeriert`() {
        val solo = GameEntry(id(), now, GameType.FARBSOLO, declarer = c, declarerWon = false)
        val state = EveningCalculator.compute(evening(rufspiel(), solo, rufspiel()))

        assertEquals(listOf(1, 2, 3), state.results.map { it.gameNumber })
        assertEquals(mapOf(a to 20, b to 20, c to -20, d to -20), state.results[0].balances)
        assertEquals(mapOf(a to 70, b to 70, c to -170, d to 30), state.results[1].balances)
        assertEquals(mapOf(a to 90, b to 90, c to -190, d to 10), state.balances)
        assertEquals(mapOf(a to 1, b to 1, c to 4, d to 3), state.ranks)
        assertEquals(3, state.gamesPlayed)
    }

    @Test
    fun `der Geber wechselt nach jedem Spiel im Uhrzeigersinn`() {
        val state = EveningCalculator.compute(evening(rufspiel(), rufspiel(), rufspiel(), rufspiel(), rufspiel(), firstDealer = c))
        assertEquals(listOf(c, d, a, b, c), state.results.map { it.dealer })
        assertEquals(d, state.nextDealer)
    }

    @Test
    fun `zusammengeworfen kostet nichts, zaehlt nicht als Spiel und der naechste gibt`() {
        val state = EveningCalculator.compute(evening(rufspiel(), thrownIn(), rufspiel()))
        assertEquals(listOf(1, null, 2), state.results.map { it.gameNumber })
        assertEquals(seating.associateWith { 0 }, state.results[1].amounts)
        assertEquals(listOf(a, b, c), state.results.map { it.dealer })
        assertEquals(d, state.nextDealer)
    }

    @Test
    fun `wenn so vereinbart, gibt nach dem Zusammenwerfen derselbe noch einmal`() {
        val rules = RuleSet.sauspiel().copy(dealerAdvancesAfterThrowIn = false)
        val state = EveningCalculator.compute(evening(thrownIn(), thrownIn(), rufspiel(), rules = rules))
        assertEquals(listOf(a, a, a), state.results.map { it.dealer })
        assertEquals(b, state.nextDealer)
    }

    @Test
    fun `nach sechs Nixern zaehlen die naechsten vier Spiele doppelt`() {
        val games = Array(6) { rufspiel() }
        val state = EveningCalculator.compute(evening(games[0], bockTrigger(), *games.copyOfRange(1, 6)))

        assertEquals(listOf(20, 40, 40, 40, 40, 20), valuesOfA(state))
        assertEquals(listOf(0, 0, 1, 1, 1, 1, 0), state.results.map { it.bockExponent })
        assertEquals(0, state.bockGamesRemaining)
        assertEquals(0, state.nextBockExponent)
    }

    @Test
    fun `der Bock-Zaehler zeigt die offenen Bockspiele`() {
        val state = EveningCalculator.compute(evening(bockTrigger(), rufspiel()))
        assertEquals(3, state.bockGamesRemaining)
        assertEquals(1, state.nextBockExponent)
    }

    @Test
    fun `anhaengen - eine zweite Bockrunde verlaengert auf acht Spiele`() {
        val state = EveningCalculator.compute(
            evening(bockTrigger(), rufspiel(), rufspiel(), bockTrigger(), *Array(7) { rufspiel() }),
        )
        assertEquals(listOf(40, 40, 40, 40, 40, 40, 40, 40, 20), valuesOfA(state))
    }

    @Test
    fun `stapeln - ueberlagerte Bockrunden vervierfachen in der Ueberschneidung`() {
        val rules = RuleSet.sauspiel().let { it.copy(bock = it.bock.copy(stacking = BockStacking.STACK)) }
        val state = EveningCalculator.compute(
            evening(bockTrigger(), rufspiel(), rufspiel(), bockTrigger(), *Array(5) { rufspiel() }, rules = rules),
        )
        assertEquals(listOf(40, 40, 80, 80, 40, 40, 20), valuesOfA(state))
    }

    @Test
    fun `ignorieren - waehrend einer Bockrunde wird keine neue ausgeloest`() {
        val rules = RuleSet.sauspiel().let { it.copy(bock = it.bock.copy(stacking = BockStacking.IGNORE)) }
        val state = EveningCalculator.compute(
            evening(bockTrigger(), rufspiel(), rufspiel(), bockTrigger(), *Array(3) { rufspiel() }, rules = rules),
        )
        assertEquals(listOf(40, 40, 40, 40, 20), valuesOfA(state))
    }

    @Test
    fun `abgeschaltete Bockrunde wirkt nicht`() {
        val rules = RuleSet.sauspiel().let { it.copy(bock = it.bock.copy(enabled = false)) }
        val state = EveningCalculator.compute(evening(bockTrigger(), rufspiel(), rules = rules))
        assertEquals(listOf(20), valuesOfA(state))
    }

    @Test
    fun `ein zusammengeworfenes Blatt verbraucht standardmaessig kein Bockspiel`() {
        val state = EveningCalculator.compute(evening(bockTrigger(), thrownIn(), *Array(5) { rufspiel() }))
        assertEquals(listOf(40, 40, 40, 40, 20), valuesOfA(state))
    }

    @Test
    fun `wenn so vereinbart, verbraucht ein zusammengeworfenes Blatt ein Bockspiel`() {
        val rules = RuleSet.sauspiel().let { it.copy(bock = it.bock.copy(thrownInConsumesBockGame = true)) }
        val state = EveningCalculator.compute(evening(bockTrigger(), thrownIn(), *Array(4) { rufspiel() }, rules = rules))
        assertEquals(listOf(40, 40, 40, 20), valuesOfA(state))
    }

    @Test
    fun `ein Spiel laesst sich von Hand aus der Bockrunde nehmen oder hineinnehmen`() {
        val state = EveningCalculator.compute(
            evening(
                rufspiel(BockOverride.FORCE_ON),
                bockTrigger(),
                rufspiel(BockOverride.FORCE_OFF),
                *Array(5) { rufspiel() },
            ),
        )
        // Erzwungenes Bockspiel ohne Zähler, ausgenommenes Spiel verbraucht nichts, danach vier Bockspiele.
        assertEquals(listOf(40, 20, 40, 40, 40, 40, 20), valuesOfA(state))
    }

    @Test
    fun `wird die Bockrunde nachtraeglich geloescht, sind die Spiele danach wieder einfach`() {
        val trigger = bockTrigger()
        val full = evening(trigger, rufspiel(), rufspiel())
        assertEquals(listOf(40, 40), valuesOfA(EveningCalculator.compute(full)))
        assertEquals(listOf(20, 20), valuesOfA(EveningCalculator.compute(full.remove(trigger.id))))
    }

    @Test
    fun `sechs Nixer ohne Zusammenwerfen lassen den Geber stehen`() {
        val state = EveningCalculator.compute(evening(bockTrigger(handThrownIn = false), rufspiel()))
        assertEquals(listOf(a, a), state.results.map { it.dealer })
        val thrown = EveningCalculator.compute(evening(bockTrigger(handThrownIn = true), rufspiel()))
        assertEquals(listOf(a, b), thrown.results.map { it.dealer })
    }

    @Test
    fun `freie Buchung wird addiert und eine falsche Summe gemeldet`() {
        val ok = ManualEntry(id(), now, mapOf(a to 100, b to -100), "Wette")
        val wrong = ManualEntry(id(), now, mapOf(a to 100, PlayerId("X") to -50), "falsch")
        val state = EveningCalculator.compute(evening(ok, wrong))

        assertEquals(mapOf(a to 100, b to -100, c to 0, d to 0), state.results[0].balances)
        assertEquals(emptyList<EntryIssue>(), state.results[0].issues)
        assertNull(state.results[0].gameNumber)
        assertTrue(EntryIssue.AMOUNTS_NOT_ZERO_SUM in state.results[1].issues)
        assertTrue(EntryIssue.UNKNOWN_PLAYER in state.results[1].issues)
        assertEquals(a, state.nextDealer)
    }

    @Test
    fun `ein Spiel mit unbekanntem Spieler wird gemeldet und nicht gebucht`() {
        val broken = GameEntry(id(), now, GameType.RUFSPIEL, declarer = a, partner = PlayerId("X"), declarerWon = true)
        val state = EveningCalculator.compute(evening(broken))
        assertTrue(EntryIssue.RULE_VIOLATION in state.results[0].issues)
        assertEquals(seating.associateWith { 0 }, state.balances)
    }

    @Test
    fun `Eintraege lassen sich ersetzen, entfernen und wieder einfuegen`() {
        val first = rufspiel()
        val second = rufspiel()
        val start = evening(first, second)

        val changed = start.replace(first.copy(level = ResultLevel.SCHNEIDER))
        assertEquals(listOf(30, 20), valuesOfA(EveningCalculator.compute(changed)))

        val removed = start.remove(first.id)
        assertEquals(listOf(second.id), removed.entries.map { it.id })
        assertEquals(start.entries, removed.insertAt(0, first).entries)
        assertEquals(listOf(second.id, first.id), removed.insertAt(99, first).entries.map { it.id })
    }

    @Test
    fun `ein Abend nach dem Regelwerk der Runde mit Ueber Kreuz und Bockrunde`() {
        val ueberKreuz = GameEntry(
            id(), now, GameType.UEBER_KREUZ, declarer = b, partner = d, declarerWon = true, klopfer = setOf(a),
        )
        val solo = GameEntry(id(), now, GameType.FARBSOLO, declarer = c, declarerWon = true, level = ResultLevel.SCHNEIDER)
        val state = EveningCalculator.compute(evening(rufspiel(), bockTrigger(), ueberKreuz, solo, rules = RuleSet.standard()))

        // Rufspiel: 10.
        assertEquals(mapOf(a to 10, b to 10, c to -10, d to -10), state.results[0].amounts)
        // Über Kreuz als Bockspiel mit einem Leger: 10 × 2 × 2 = 40.
        assertEquals(mapOf(a to -40, b to 40, c to -40, d to 40), state.results[2].amounts)
        // Farbsolo mit Schneider als Bockspiel: (50 + 10) × 2 = 120, der Solist dreimal: 360.
        assertEquals(mapOf(a to -120, b to -120, c to 360, d to -120), state.results[3].amounts)
        assertEquals(mapOf(a to -150, b to -70, c to 310, d to -90), state.balances)
        assertEquals(mapOf(a to 4, b to 2, c to 1, d to 3), state.ranks)
        assertEquals(2, state.bockGamesRemaining)
    }

    @Test
    fun `die Gewinner eines Spiels ergeben sich aus Ansager, Mitspieler und Ausgang`() {
        val won = rufspiel()
        assertEquals(setOf(a, b), won.declarerSide)
        assertEquals(listOf(a, b), won.winners(seating))
        assertEquals(listOf(c, d), won.losers(seating))
        assertEquals(listOf(c, d), won.copy(declarerWon = false).winners(seating))

        val solo = GameEntry(id(), now, GameType.GEIER, declarer = d, declarerWon = false)
        assertEquals(setOf(d), solo.declarerSide)
        assertEquals(listOf(a, b, c), solo.winners(seating))
        assertEquals(listOf(d), solo.losers(seating))
        assertEquals(listOf(d), solo.copy(declarerWon = true).winners(seating))
    }

    @Test
    fun `ein Rufspiel mit sich selbst wird gemeldet und nicht gebucht`() {
        val broken = GameEntry(id(), now, GameType.RUFSPIEL, declarer = a, partner = a, declarerWon = true)
        val state = EveningCalculator.compute(evening(broken, rufspiel()))
        assertTrue(EntryIssue.RULE_VIOLATION in state.results[0].issues)
        assertEquals(seating.associateWith { 0 }, state.results[0].amounts)
        assertNull(state.results[0].breakdown)
        assertEquals(listOf(1, 2), state.results.map { it.gameNumber })
    }

    @Test
    fun `der ganze Abend bleibt ein Nullsummenspiel`() {
        val random = Random(42)
        val ruleSets = listOf(
            RuleSet.sauspiel(),
            RuleSet.standard(),
            RuleSet.standard().copy(payoutRule = PayoutRule.POT_SPLIT),
        )
        repeat(200) {
            val entries = List(random.nextInt(1, 60)) { randomEntry(random) }
            for (rules in ruleSets) {
                val state = EveningCalculator.compute(evening(*entries.toTypedArray(), rules = rules))
                for (result in state.results) {
                    assertEquals(0, result.amounts.values.sum())
                    assertEquals(0, result.balances.values.sum())
                }
                assertEquals(entries.count { it is GameEntry }, state.gamesPlayed)
                assertTrue(SettleUp.isBalanced(state.balances))
            }
        }
    }

    private fun randomEntry(random: Random): EveningEntry = when (random.nextInt(12)) {
        0 -> thrownIn()
        1 -> bockTrigger(handThrownIn = random.nextBoolean())
        else -> {
            val type = GameType.entries.random(random)
            val declarer = seating.random(random)
            GameEntry(
                id = id(),
                recordedAt = now,
                type = type,
                declarer = declarer,
                partner = if (type.isSolo) null else (seating - declarer).random(random),
                declarerWon = random.nextBoolean(),
                level = ResultLevel.entries.random(random),
                laufende = random.nextInt(0, 5),
                klopfer = seating.filter { random.nextInt(4) == 0 }.toSet(),
                kontra = KontraLevel.entries.random(random),
                bockOverride = BockOverride.entries.random(random),
            )
        }
    }
}
