package io.github.q0ochen.schafkopf.domain.rules

import io.github.q0ochen.schafkopf.domain.model.PlayerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** „Kassensturz“: Wie der Spielwert T auf die vier Spieler verteilt wird. */
class PayoutCalculatorTest {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")

    @Test
    fun `Schafkopf-Regel - zwei gegen zwei zahlt jeder Verlierer T`() {
        assertEquals(
            mapOf(a to 30, c to 30, b to -30, d to -30),
            SchafkopfPayout.payout(30, winners = listOf(a, c), losers = listOf(b, d)),
        )
    }

    @Test
    fun `Schafkopf-Regel - Solist gewinnt und bekommt dreimal T`() {
        assertEquals(
            mapOf(b to 150, a to -50, c to -50, d to -50),
            SchafkopfPayout.payout(50, winners = listOf(b), losers = listOf(a, c, d)),
        )
    }

    @Test
    fun `Schafkopf-Regel - Solist verliert und zahlt dreimal T`() {
        assertEquals(
            mapOf(a to 50, c to 50, d to 50, b to -150),
            SchafkopfPayout.payout(50, winners = listOf(a, c, d), losers = listOf(b)),
        )
    }

    @Test
    fun `Topf-Teilung - zwei gegen zwei bleibt wie bei der Schafkopf-Regel`() {
        assertEquals(
            SchafkopfPayout.payout(40, listOf(a, b), listOf(c, d)),
            PotSplitPayout.payout(40, listOf(a, b), listOf(c, d)),
        )
    }

    @Test
    fun `Topf-Teilung - Solist gewinnt T und die drei Gegner teilen sich die Schuld`() {
        // 60 Cent durch drei = je 20.
        assertEquals(
            mapOf(b to 60, a to -20, c to -20, d to -20),
            PotSplitPayout.payout(60, winners = listOf(b), losers = listOf(a, c, d)),
        )
    }

    @Test
    fun `Topf-Teilung - geht T nicht durch drei, zahlen die vorderen Gegner einen Cent mehr`() {
        // 50 Cent durch drei = 16 Rest 2: also 17, 17, 16.
        assertEquals(
            mapOf(b to 50, a to -17, c to -17, d to -16),
            PotSplitPayout.payout(50, winners = listOf(b), losers = listOf(a, c, d)),
        )
    }

    @Test
    fun `Topf-Teilung - Solist verliert T und die drei Gegner teilen sich den Gewinn`() {
        // 100 Cent durch drei = 33 Rest 1: also 34, 33, 33.
        assertEquals(
            mapOf(b to -100, a to 34, c to 33, d to 33),
            PotSplitPayout.payout(100, winners = listOf(a, c, d), losers = listOf(b)),
        )
    }

    @Test
    fun `zur Auszahlungsregel gehoert die passende Umsetzung`() {
        assertSame(SchafkopfPayout, PayoutCalculator.of(PayoutRule.SCHAFKOPF))
        assertSame(PotSplitPayout, PayoutCalculator.of(PayoutRule.POT_SPLIT))
    }

    @Test
    fun `jede Auszahlung ist ein Nullsummenspiel und trifft alle vier Spieler`() {
        val random = Random(4711)
        val table = listOf(a, b, c, d)
        repeat(2_000) {
            val value = random.nextInt(0, 5_000)
            val shuffled = table.shuffled(random)
            val winnerCount = listOf(1, 2, 3).random(random)
            val winners = shuffled.take(winnerCount)
            val losers = shuffled.drop(winnerCount)
            for (calculator in listOf(SchafkopfPayout, PotSplitPayout)) {
                val result = calculator.payout(value, winners, losers)
                assertEquals(0, result.values.sum(), "Summe ungleich 0 bei $value, $winners, $losers")
                assertEquals(table.toSet(), result.keys)
                assertEquals(true, winners.all { result.getValue(it) >= 0 })
                assertEquals(true, losers.all { result.getValue(it) <= 0 })
            }
        }
    }

    @Test
    fun `leere Seiten fuehren nicht zum Absturz`() {
        assertEquals(mapOf(a to 0), SchafkopfPayout.payout(50, winners = listOf(a), losers = emptyList()))
        assertEquals(mapOf(a to 0), SchafkopfPayout.payout(50, winners = emptyList(), losers = listOf(a)).mapValues { -it.value })
        assertEquals(mapOf(a to 0), PotSplitPayout.payout(50, winners = listOf(a), losers = emptyList()))
    }
}
