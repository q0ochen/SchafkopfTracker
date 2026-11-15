package io.github.q0ochen.schafkopf.domain.rules

import io.github.q0ochen.schafkopf.domain.model.PlayerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

/**
 * Unsinnige Eingaben, wie sie aus beschädigten oder von Hand bearbeiteten Daten kommen können: falsche Sitzordnung,
 * unbekannte Spieler, negative oder riesige Werte im Regelwerk, Laufende und Leger außerhalb des Bereichs,
 * negative oder riesige Bock-Stufe.
 *
 * Dafür gilt immer: Nichts wirft eine Ausnahme. Die Beträge eines Spiels ergeben zusammen genau 0 (als `Long`
 * nachgerechnet, damit ein Überlauf nicht täuscht). Wo sich nichts sinnvoll abrechnen lässt, wird nichts verteilt,
 * und [ScoreCalculator.validate] sagt, warum.
 */
class ScoreCalculatorRobustnessTest {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val e = PlayerId("E")
    private val x = PlayerId("X")
    private val table = listOf(a, b, c, d)
    private val standard = RuleSet.standard()
    private val nothing = mapOf(a to 0, b to 0, c to 0, d to 0)

    private fun facts(
        type: GameType,
        declarer: PlayerId,
        partner: PlayerId? = null,
        won: Boolean = true,
        level: ResultLevel = ResultLevel.NORMAL,
        laufende: Int = 0,
        announcement: Announcement = Announcement.NONE,
        gelegt: Int = 0,
        kontra: KontraLevel = KontraLevel.NONE,
        bock: Int = 0,
        players: List<PlayerId> = table,
    ) = GameFacts(type, players, declarer, partner, won, level, laufende, announcement, gelegt, kontra, bock)

    private fun value(facts: GameFacts, rules: RuleSet = standard) = ScoreCalculator.breakdown(facts, rules).value

    private fun amounts(facts: GameFacts, rules: RuleSet = standard) = ScoreCalculator.amounts(facts, rules)

    private fun violations(facts: GameFacts, rules: RuleSet = standard) = ScoreCalculator.validate(facts, rules)

    private fun exactSum(amounts: Map<PlayerId, Int>): Long = amounts.values.sumOf { it.toLong() }

    // ---------------------------------------------------------------- Verteilung (PayoutCalculator)

    @Test
    fun `Topf-Teilung - ein negativer Spielwert laesst keinen Cent verschwinden`() {
        // Vor der Korrektur: B −50, die drei Gegner je +16, zusammen −2. Ein negativer Spielwert kann nur aus einem
        // beschädigten Regelwerk stammen: Es wird nichts verteilt.
        assertEquals(nothing, PotSplitPayout.payout(-50, winners = listOf(b), losers = listOf(a, c, d)))
        assertEquals(nothing, PotSplitPayout.payout(-50, winners = listOf(a, c, d), losers = listOf(b)))
        assertEquals(nothing, SchafkopfPayout.payout(-50, winners = listOf(b), losers = listOf(a, c, d)))
        assertEquals(nothing, SchafkopfPayout.payout(-10, winners = listOf(a, b), losers = listOf(c, d)))
    }

    @Test
    fun `Schafkopf-Regel - eine Summe, die nicht aufgeht, wird auf den Cent verteilt`() {
        // Fünf am Tisch (falsche Sitzordnung) und ein ungerader Spielwert: Die drei Verlierer zahlen je 5, zusammen 15.
        // 15 : 2 = 7 Rest 1, der vordere Gewinner bekommt den Cent. Vor der Korrektur bekamen beide 7: ein Cent fehlte.
        assertEquals(
            mapOf(a to 8, b to 7, c to -5, d to -5, e to -5),
            SchafkopfPayout.payout(5, winners = listOf(a, b), losers = listOf(c, d, e)),
        )
        // Umgekehrt: Die drei Gewinner bekommen je 5, die zwei Verlierer zahlen 8 und 7.
        assertEquals(
            mapOf(a to -8, b to -7, c to 5, d to 5, e to 5),
            SchafkopfPayout.payout(5, winners = listOf(c, d, e), losers = listOf(a, b)),
        )
        // Topf-Teilung: Die kleinere Seite rechnet pro Kopf (zweimal 5 = 10), die größere teilt 10 in 4, 3, 3.
        assertEquals(
            mapOf(a to 5, b to 5, c to -4, d to -3, e to -3),
            PotSplitPayout.payout(5, winners = listOf(a, b), losers = listOf(c, d, e)),
        )
    }

    @Test
    fun `drei am Tisch - der Einzelne zahlt oder bekommt beide Anteile`() {
        // Dieses Verhalten bleibt, wie es war (der CSV-Export rechnet damit): zwei gegen einen.
        assertEquals(mapOf(a to 10, b to 10, c to -20), SchafkopfPayout.payout(10, winners = listOf(a, b), losers = listOf(c)))
        assertEquals(mapOf(a to 100, b to -50, c to -50), SchafkopfPayout.payout(50, winners = listOf(a), losers = listOf(b, c)))
    }

    @Test
    fun `ein doppelt genannter Spieler - es wird nichts verteilt`() {
        // Vor der Korrektur: A +10, B +10, C −30, zusammen −10.
        assertEquals(mapOf(a to 0, b to 0, c to 0), SchafkopfPayout.payout(10, winners = listOf(a, a, b), losers = listOf(c)))
        // Vor der Korrektur: A +10, B −10, C −10, zusammen −10.
        assertEquals(mapOf(a to 0, b to 0, c to 0), SchafkopfPayout.payout(10, winners = listOf(a, b), losers = listOf(b, c)))
        assertEquals(mapOf(a to 0, b to 0, c to 0), PotSplitPayout.payout(10, winners = listOf(a), losers = listOf(b, c, c)))
    }

    @Test
    fun `riesige Spielwerte laufen bei der Verteilung nicht ueber`() {
        // 3 × 1 000 000 000 passt nicht in einen Int: nichts verteilen statt mit einem falschen Betrag weiterrechnen.
        assertEquals(nothing, SchafkopfPayout.payout(1_000_000_000, winners = listOf(a), losers = listOf(b, c, d)))
        // 2 × 2 147 483 647 passt nicht.
        assertEquals(nothing, SchafkopfPayout.payout(Int.MAX_VALUE, winners = listOf(a, b), losers = listOf(c, d)))
        // Der größte Spielwert, dessen Dreifaches noch passt: 715 827 882 × 3 = 2 147 483 646.
        assertEquals(
            mapOf(a to 2_147_483_646, b to -715_827_882, c to -715_827_882, d to -715_827_882),
            SchafkopfPayout.payout(715_827_882, winners = listOf(a), losers = listOf(b, c, d)),
        )
        // Topf-Teilung: 2 147 483 647 : 3 = 715 827 882 Rest 1.
        assertEquals(
            mapOf(a to Int.MAX_VALUE, b to -715_827_883, c to -715_827_882, d to -715_827_882),
            PotSplitPayout.payout(Int.MAX_VALUE, winners = listOf(a), losers = listOf(b, c, d)),
        )
    }

    @Test
    fun `jede Auszahlung ist mit beliebigen Listen ein Nullsummenspiel`() {
        val random = Random(815)
        val anyone = listOf(a, b, c, d, e, x)
        val values = listOf(Int.MIN_VALUE, -1_000_000, -51, -1, 0, 1, 5, 7, 10, 50, 715_827_882, 715_827_883, 1_073_741_824, Int.MAX_VALUE)
        repeat(20_000) {
            val value = if (random.nextBoolean()) values.random(random) else random.nextInt(0, 10_000)
            // Mal sauber getrennt, mal mit doppelten oder auf beiden Seiten genannten Spielern.
            val winners = List(random.nextInt(0, 5)) { anyone.random(random) }
            val losers = List(random.nextInt(0, 5)) { anyone.random(random) }
            for (calculator in listOf(SchafkopfPayout, PotSplitPayout)) {
                val result = calculator.payout(value, winners, losers)
                assertEquals((winners + losers).toSet(), result.keys)
                assertEquals(0L, exactSum(result), "Summe ungleich 0 bei $value, $winners, $losers")
                assertTrue(winners.all { result.getValue(it) >= 0 } && losers.all { result.getValue(it) <= 0 })
            }
        }
    }

    @Test
    fun `saubere Seiten - eine Seite rechnet pro Kopf, die andere teilt sich die Summe fast gleich`() {
        val random = Random(4)
        repeat(5_000) {
            val shuffled = listOf(a, b, c, d, e, x).shuffled(random)
            val winners = shuffled.take(random.nextInt(1, 4))
            val losers = shuffled.drop(winners.size).take(random.nextInt(1, 4))
            val value = random.nextInt(0, 100_000)

            val schafkopf = SchafkopfPayout.payout(value, winners, losers)
            val (bigSide, smallSide) = if (winners.size > losers.size) winners to losers else losers to winners
            assertTrue(bigSide.all { abs(schafkopf.getValue(it)) == value }, "Die größere Seite rechnet pro Kopf mit T")
            assertEquals(0L, exactSum(schafkopf))
            val shares = smallSide.map { abs(schafkopf.getValue(it)) }
            assertTrue(shares.max() - shares.min() <= 1 && shares == shares.sortedDescending(), "Rest-Cent an die vorderen")

            val potSplit = PotSplitPayout.payout(value, winners, losers)
            val (perHead, sharing) = if (winners.size < losers.size) winners to losers else losers to winners
            assertTrue(perHead.all { abs(potSplit.getValue(it)) == value }, "Die kleinere Seite rechnet pro Kopf mit T")
            assertEquals(0L, exactSum(potSplit))
            val potShares = sharing.map { abs(potSplit.getValue(it)) }
            assertTrue(potShares.max() - potShares.min() <= 1 && potShares == potShares.sortedDescending())
        }
    }

    // ---------------------------------------------------------------- Spielwert (ScoreCalculator)

    @Test
    fun `riesiger Grundwert - gemeldet und nicht abgerechnet statt uebergelaufen`() {
        val huge = standard.copy(prices = mapOf(GameType.FARBSOLO to 800_000_000))
        val solo = facts(GameType.FARBSOLO, a)
        // T = 800 Mio. passt noch in einen Int, das Dreifache für den Solisten aber nicht.
        // Vor der Korrektur: A bekam −1 894 967 296 (übergelaufen) und nichts war gemeldet.
        assertEquals(800_000_000, value(solo, huge))
        assertTrue(RuleViolation.VALUE_OUT_OF_RANGE in violations(solo, huge))
        assertEquals(nothing, amounts(solo, huge))

        // Mit vier Legern (× 16) passt auch T selbst nicht mehr: am Rand des Zahlenbereichs begrenzt.
        // Vor der Korrektur lief 800 Mio. × 16 zu −84 901 888 über.
        val gelegt = facts(GameType.FARBSOLO, a, gelegt = 4)
        assertEquals(Int.MAX_VALUE, value(gelegt, huge))
        assertTrue(RuleViolation.VALUE_OUT_OF_RANGE in violations(gelegt, huge))
        assertEquals(nothing, amounts(gelegt, huge))
        // Auch zwei gegen zwei wird ein solcher Wert nicht verteilt, obwohl ±T für sich noch darstellbar wäre.
        val pair = facts(GameType.RUFSPIEL, a, b)
        val hugePair = standard.copy(prices = mapOf(GameType.RUFSPIEL to 800_000_000))
        assertTrue(RuleViolation.VALUE_OUT_OF_RANGE in violations(pair, hugePair))
        assertEquals(nothing, amounts(pair, hugePair))
    }

    @Test
    fun `der groesste Spielwert, mit dem noch gerechnet wird`() {
        assertEquals(715_827_882, ScoreCalculator.MAX_VALUE)
        val limit = standard.copy(prices = mapOf(GameType.FARBSOLO to 715_827_882))
        val solo = facts(GameType.FARBSOLO, a)
        // 3 × 715 827 882 = 2 147 483 646 passt gerade noch.
        assertEquals(emptyList<RuleViolation>(), violations(solo, limit))
        assertEquals(mapOf(a to 2_147_483_646, b to -715_827_882, c to -715_827_882, d to -715_827_882), amounts(solo, limit))
        // Ein Cent mehr ist zu viel.
        val above = standard.copy(prices = mapOf(GameType.FARBSOLO to 715_827_883))
        assertEquals(listOf(RuleViolation.VALUE_OUT_OF_RANGE), violations(solo, above))
        assertEquals(nothing, amounts(solo, above))
    }

    @Test
    fun `riesige Bock-Stufe - begrenzt statt uebergelaufen`() {
        // 26 überlagerte Bockrunden: 10 × 2²⁶ = 671 088 640, das geht noch.
        val last = facts(GameType.RUFSPIEL, a, b, bock = 26)
        assertEquals(671_088_640, value(last))
        assertEquals(emptyList<RuleViolation>(), violations(last))
        assertEquals(mapOf(a to 671_088_640, b to 671_088_640, c to -671_088_640, d to -671_088_640), amounts(last))

        // 27: 10 × 2²⁷ = 1 342 177 280 liegt über dem größten Spielwert.
        val tooMuch = facts(GameType.RUFSPIEL, a, b, bock = 27)
        assertEquals(1_342_177_280, value(tooMuch))
        assertEquals(listOf(RuleViolation.VALUE_OUT_OF_RANGE), violations(tooMuch))
        assertEquals(nothing, amounts(tooMuch))

        // Vor der Korrektur lief die Rechnung über: 10 × 2²⁸ ergab −1 610 612 736, und ab 2³¹ kam 0 heraus. Das Spiel
        // war dann kostenlos, ohne dass etwas gemeldet war.
        for (bock in listOf(28, 31, 32, 33, 40, 1_000, Int.MAX_VALUE)) {
            val game = facts(GameType.RUFSPIEL, a, b, bock = bock)
            assertEquals(Int.MAX_VALUE, value(game), "Bock-Stufe $bock")
            assertTrue(RuleViolation.VALUE_OUT_OF_RANGE in violations(game), "Bock-Stufe $bock")
            assertEquals(nothing, amounts(game), "Bock-Stufe $bock")
        }
    }

    @Test
    fun `negative Werte im Regelwerk - gemeldet und nicht abgerechnet`() {
        // Einheit −10: Rufspiel schwarz = 10 − 20 = −10.
        val negativeUnit = standard.copy(unit = -10)
        val schwarz = facts(GameType.RUFSPIEL, a, b, level = ResultLevel.SCHWARZ)
        assertEquals(-10, value(schwarz, negativeUnit))
        assertEquals(listOf(RuleViolation.VALUE_OUT_OF_RANGE), violations(schwarz, negativeUnit))
        assertEquals(nothing, amounts(schwarz, negativeUnit))

        // Grundwert −50 mit Topf-Teilung: Vor der Korrektur fehlten hier zwei Cent, ohne dass etwas gemeldet war.
        val negativePrice = standard.copy(prices = mapOf(GameType.WENZ to -50), payoutRule = PayoutRule.POT_SPLIT)
        val wenz = facts(GameType.WENZ, b)
        assertEquals(-50, value(wenz, negativePrice))
        assertEquals(listOf(RuleViolation.VALUE_OUT_OF_RANGE), violations(wenz, negativePrice))
        assertEquals(nothing, amounts(wenz, negativePrice))

        // Faktoren: Tout × −2 und Bock × −2 ergeben einen negativen Wert; Bock × 0 ergibt 0 (das ist harmlos).
        val tout = facts(GameType.WENZ, a, announcement = Announcement.TOUT)
        assertEquals(-100, value(tout, standard.copy(toutFactor = -2)))
        assertTrue(RuleViolation.VALUE_OUT_OF_RANGE in violations(tout, standard.copy(toutFactor = -2)))
        val bock = facts(GameType.RUFSPIEL, a, b, bock = 1)
        assertEquals(-20, value(bock, standard.copy(bock = standard.bock.copy(factor = -2))))
        assertEquals(nothing, amounts(bock, standard.copy(bock = standard.bock.copy(factor = -2))))
        assertEquals(0, value(bock, standard.copy(bock = standard.bock.copy(factor = 0))))
        assertEquals(emptyList<RuleViolation>(), violations(bock, standard.copy(bock = standard.bock.copy(factor = 0))))
        assertEquals(nothing, amounts(bock, standard.copy(bock = standard.bock.copy(factor = 0))))
    }

    @Test
    fun `Bock-Faktor minus eins - das Vorzeichen stimmt auch bei riesiger Bock-Stufe`() {
        // (−1)^n ist +1 für gerades und −1 für ungerades n, egal wie groß n ist.
        val rules = standard.copy(bock = standard.bock.copy(factor = -1))
        assertEquals(10, value(facts(GameType.RUFSPIEL, a, b, bock = 2), rules))
        assertEquals(-10, value(facts(GameType.RUFSPIEL, a, b, bock = 3), rules))
        assertEquals(10, value(facts(GameType.RUFSPIEL, a, b, bock = 100), rules))
        assertEquals(-10, value(facts(GameType.RUFSPIEL, a, b, bock = 101), rules))
        assertEquals(-10, value(facts(GameType.RUFSPIEL, a, b, bock = Int.MAX_VALUE), rules))
        // Faktor −2: riesig und im Vorzeichen richtig.
        val minusTwo = standard.copy(bock = standard.bock.copy(factor = -2))
        assertTrue(value(facts(GameType.RUFSPIEL, a, b, bock = 100), minusTwo) >= Int.MAX_VALUE)
        assertTrue(value(facts(GameType.RUFSPIEL, a, b, bock = 101), minusTwo) <= -Int.MAX_VALUE)
    }

    @Test
    fun `Laufende ausserhalb des Bereichs`() {
        // Riesig: gemeldet (auch als Laufende außerhalb des Bereichs), begrenzt und nicht abgerechnet.
        val huge = facts(GameType.RUFSPIEL, a, b, laufende = Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, value(huge))
        assertEquals(listOf(RuleViolation.LAUFENDE_OUT_OF_RANGE, RuleViolation.VALUE_OUT_OF_RANGE), violations(huge))
        assertEquals(nothing, amounts(huge))

        // Negativ: unter der Schwelle, zählt also nicht. T = 10; gemeldet wird nur die unmögliche Zahl.
        val negative = facts(GameType.RUFSPIEL, a, b, laufende = Int.MIN_VALUE)
        assertEquals(10, value(negative))
        assertEquals(listOf(RuleViolation.LAUFENDE_OUT_OF_RANGE), violations(negative))
        assertEquals(mapOf(a to 10, b to 10, c to -10, d to -10), amounts(negative))

        // Neun Laufende sind mehr, als die Vorgabe kennt: gemeldet, aber wie eingetragen gerechnet (10 + 90 = 100).
        val nine = facts(GameType.RUFSPIEL, a, b, laufende = 9)
        assertEquals(listOf(RuleViolation.LAUFENDE_OUT_OF_RANGE), violations(nine))
        assertEquals(mapOf(a to 100, b to 100, c to -100, d to -100), amounts(nine))

        // Ein Sie mit unsinnig vielen Laufenden im Regelwerk.
        val sie = facts(GameType.FARBSOLO, a, announcement = Announcement.SIE)
        assertTrue(RuleViolation.VALUE_OUT_OF_RANGE in violations(sie, standard.copy(sieLaufende = Int.MAX_VALUE)))
        assertEquals(nothing, amounts(sie, standard.copy(sieLaufende = Int.MAX_VALUE)))
    }

    @Test
    fun `grosse Werte mit verschiedenen Vorzeichen heben sich nicht scheinbar auf`() {
        // Grundwert −2 Mrd., dazu 3000 Laufende zu je 1 Mio. = +3 Mrd.: Die Summe ist genau 1 Mrd. und damit zu groß.
        // Würde der Zuschlag für sich auf den Int-Bereich begrenzt (2 147 483 647), käme scheinbar gültig 147 483 647 heraus.
        val rules = standard.copy(unit = 1_000_000, prices = mapOf(GameType.RUFSPIEL to -2_000_000_000))
        val game = facts(GameType.RUFSPIEL, a, b, laufende = 3000)
        assertEquals(1_000_000_000, value(game, rules))
        assertTrue(RuleViolation.VALUE_OUT_OF_RANGE in violations(game, rules))
        assertEquals(nothing, amounts(game, rules))
    }

    @Test
    fun `falsche Sitzordnung - niemand bekommt oder zahlt etwas, wenn jemand doppelt am Tisch sitzt`() {
        // A sitzt doppelt: Vor der Korrektur A +10, B +10, C −30 (zusammen −10).
        val twice = facts(GameType.RUFSPIEL, a, b, players = listOf(a, a, b, c))
        assertTrue(RuleViolation.DUPLICATE_PLAYERS in violations(twice))
        assertEquals(mapOf(a to 0, b to 0, c to 0), amounts(twice))
        // C sitzt doppelt: Vor der Korrektur A +10, B +10, C −10 (zusammen +10).
        assertEquals(mapOf(a to 0, b to 0, c to 0), amounts(facts(GameType.RUFSPIEL, a, b, players = listOf(a, b, c, c))))
        assertEquals(mapOf(a to 0, b to 0, c to 0), amounts(facts(GameType.WENZ, c, players = listOf(a, b, c, c))))

        // Leerer Tisch: keine Beträge und keine Ausnahme.
        val empty = facts(GameType.RUFSPIEL, a, b, players = emptyList())
        assertEquals(emptyMap<PlayerId, Int>(), amounts(empty))
        assertTrue(RuleViolation.WRONG_PLAYER_COUNT in violations(empty))
        assertTrue(RuleViolation.DECLARER_NOT_AT_TABLE in violations(empty))
        assertEquals(10, value(empty))
    }

    @Test
    fun `drei oder fuenf am Tisch - gemeldet, aber immer ein Nullsummenspiel`() {
        // Drei am Tisch: A und B gewinnen je 10, C zahlt beides allein.
        val three = facts(GameType.RUFSPIEL, a, b, players = listOf(a, b, c))
        assertTrue(RuleViolation.WRONG_PLAYER_COUNT in violations(three))
        assertEquals(mapOf(a to 10, b to 10, c to -20), amounts(three))

        // Fünf am Tisch und ein Rufspiel zu 5 Cent: Die drei Verlierer zahlen zusammen 15, die Gewinner bekommen 8 und 7.
        // Vor der Korrektur bekamen beide 7, ein Cent fehlte.
        val five = facts(GameType.RUFSPIEL, a, b, players = listOf(a, b, c, d, e))
        val fiveCent = standard.copy(prices = mapOf(GameType.RUFSPIEL to 5))
        assertEquals(mapOf(a to 8, b to 7, c to -5, d to -5, e to -5), amounts(five, fiveCent))
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

    private fun Random.sometimes(usual: Int): Int = if (nextInt(3) == 0) anyInt() else usual

    private fun Random.brokenRules(): RuleSet = standard.copy(
        unit = sometimes(standard.unit),
        prices = GameType.entries.filter { nextInt(5) != 0 }.associateWith { sometimes(standard.price(it)) },
        laufendeMin = GameType.entries.filter { nextInt(5) != 0 }.associateWith { sometimes(standard.laufendeMin(it)) },
        laufendeMax = GameType.entries.filter { nextInt(5) != 0 }.associateWith { sometimes(standard.laufendeMax(it)) },
        enabledTypes = GameType.entries.filter { nextInt(4) != 0 }.toSet(),
        klopfenEnabled = nextBoolean(),
        kontraEnabled = nextBoolean(),
        retourEnabled = nextBoolean(),
        toutFactor = sometimes(2),
        sieFactor = sometimes(2),
        sieLaufende = sometimes(8),
        bock = standard.bock.copy(factor = sometimes(2)),
        payoutRule = PayoutRule.entries.random(this),
    )

    private fun Random.brokenFacts(): GameFacts {
        val type = GameType.entries.random(this)
        return GameFacts(
            type = type,
            players = if (nextInt(3) == 0) List(nextInt(0, 7)) { pool.random(this) } else table,
            declarer = if (nextInt(5) == 0) pool.random(this) else table.random(this),
            partner = if (nextInt(4) == 0) null else pool.random(this),
            declarerWon = nextBoolean(),
            level = ResultLevel.entries.random(this),
            laufende = sometimes(nextInt(0, 9)),
            announcement = if (nextInt(4) == 0) Announcement.entries.random(this) else Announcement.NONE,
            klopferCount = sometimes(nextInt(0, 5)),
            kontra = KontraLevel.entries.random(this),
            bockExponent = listOf(-5, -1, 0, 0, 0, 1, 1, 2, 3, 5, 30, 31, 32, 33, 64, 200).random(this),
        )
    }

    /** Der Spielwert nach der Formel der Vorgabe, mit beliebig großen Zahlen nachgerechnet (kann nicht überlaufen). */
    private fun exactValue(facts: GameFacts, rules: RuleSet): BigInteger {
        fun big(number: Int) = BigInteger.valueOf(number.toLong())
        val laufende = if (facts.announcement == Announcement.SIE) rules.sieLaufende else facts.laufende
        val counted = if (laufende >= rules.laufendeMin(facts.type)) laufende else 0
        val steps = if (facts.announcement == Announcement.NONE) facts.level.steps else 0
        val sum = big(rules.price(facts.type)) + big(counted) * big(rules.unit) + big(steps) * big(rules.unit)
        val announcement = when (facts.announcement) {
            Announcement.NONE -> 1
            Announcement.TOUT -> rules.toutFactor
            Announcement.SIE -> rules.sieFactor
        }
        return sum * big(announcement) * BigInteger.TWO.pow(facts.klopferCount.coerceIn(0, 4)) * big(facts.kontra.factor) *
            big(rules.bock.factor).pow(facts.bockExponent.coerceAtLeast(0))
    }

    @Test
    fun `beliebiger Unsinn - nie eine Ausnahme, immer ein Nullsummenspiel, und was nicht geht, ist gemeldet`() {
        val random = Random(20261115)
        val limit = BigInteger.valueOf(ScoreCalculator.MAX_VALUE.toLong())
        val intMax = BigInteger.valueOf(Int.MAX_VALUE.toLong())
        var outOfRange = 0
        repeat(40_000) {
            val facts = random.brokenFacts()
            val rules = random.brokenRules()
            val context = "$facts mit $rules"

            val value = ScoreCalculator.breakdown(facts, rules).value
            val result = ScoreCalculator.amounts(facts, rules)
            val found = ScoreCalculator.validate(facts, rules)
            val exact = exactValue(facts, rules)

            // Der Spielwert stimmt exakt, solange sein Betrag in einen Int passt; sonst steht er am Rand des
            // Zahlenbereichs, mit dem richtigen Vorzeichen und nie übergelaufen.
            assertEquals(exact.signum(), value.sign, context)
            if (exact.abs() <= intMax) {
                assertEquals(exact.intValueExact(), value, context)
            } else {
                assertTrue(abs(value.toLong()) >= Int.MAX_VALUE, context)
            }

            assertEquals(facts.players.toSet(), result.keys, context)
            assertEquals(0L, exactSum(result), context)
            assertTrue(facts.winners.all { result.getValue(it) >= 0 } && facts.losers.all { result.getValue(it) <= 0 }, context)

            if (exact.signum() >= 0 && exact <= limit) {
                assertFalse(RuleViolation.VALUE_OUT_OF_RANGE in found, context)
            } else {
                outOfRange++
                assertTrue(RuleViolation.VALUE_OUT_OF_RANGE in found, context)
                assertTrue(result.values.all { it == 0 }, context)
            }
        }
        assertTrue(outOfRange in 2_000..38_000, "Der Test hat beide Fälle wirklich geprüft ($outOfRange von 40000 außerhalb)")
    }

    @Test
    fun `gueltige Spiele - der Spielwert entspricht exakt der Formel und nichts ist gemeldet`() {
        val random = Random(99)
        for (rules in listOf(standard, standard.copy(payoutRule = PayoutRule.POT_SPLIT), RuleSet.sauspiel())) {
            repeat(5_000) {
                val type = rules.enabledTypes.random(random)
                val declarer = table.random(random)
                val announcement = when {
                    type == GameType.FARBSOLO && random.nextInt(20) == 0 -> Announcement.SIE
                    type.isSolo && random.nextInt(8) == 0 -> Announcement.TOUT
                    else -> Announcement.NONE
                }
                val game = GameFacts(
                    type = type,
                    players = table,
                    declarer = declarer,
                    partner = if (type.isSolo) null else (table - declarer).random(random),
                    declarerWon = announcement == Announcement.SIE || random.nextBoolean(),
                    level = ResultLevel.entries.random(random),
                    laufende = random.nextInt(0, rules.laufendeMax(type) + 1),
                    announcement = announcement,
                    klopferCount = random.nextInt(0, 5),
                    kontra = KontraLevel.entries.random(random),
                    bockExponent = random.nextInt(0, 4),
                )
                val exact = exactValue(game, rules).intValueExact()
                assertEquals(exact, ScoreCalculator.breakdown(game, rules).value)
                assertEquals(emptyList<RuleViolation>(), ScoreCalculator.validate(game, rules))
                val result = ScoreCalculator.amounts(game, rules)
                assertEquals(0L, exactSum(result))
                // Jeder Gewinner bekommt etwas, jeder Verlierer zahlt etwas; zwei gegen zwei genau T.
                assertTrue(game.winners.all { result.getValue(it) > 0 } && game.losers.all { result.getValue(it) < 0 })
                if (!type.isSolo) assertTrue(result.values.all { abs(it) == exact })
                if (type.isSolo && rules.payoutRule == PayoutRule.SCHAFKOPF) assertEquals(3 * exact, abs(result.getValue(declarer)))
                if (type.isSolo && rules.payoutRule == PayoutRule.POT_SPLIT) assertEquals(exact, abs(result.getValue(declarer)))
            }
        }
    }
}
