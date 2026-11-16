package io.github.q0ochen.schafkopf.app.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dieselben Fälle wie `BalanceChart.test.ts` der Web-App, dazu die Kotlin-Besonderheiten (ganze Cent). */
class ChartMathTest {

    @Test
    fun `runde Teilstriche decken den ganzen Bereich ab`() {
        assertEquals(listOf(-200, -100, 0, 100), ChartMath.niceTicks(-160, 60))
        assertEquals(listOf(0, 250, 500, 750, 1000), ChartMath.niceTicks(0, 1000))
        val ticks = ChartMath.niceTicks(-1234, 5678)
        assertTrue(ticks.first() <= -1234)
        assertTrue(ticks.last() >= 5678)
        assertTrue(0 in ticks)
    }

    @Test
    fun `lauter gleiche Werte und winzige Bereiche ergeben ganze Cent`() {
        val flat = ChartMath.niceTicks(0, 0)
        assertTrue(flat.size >= 2)
        assertTrue(flat.first() < 0 && flat.last() > 0)
        // 0 bis 10 Cent: Schritt 5 statt 2,5.
        assertEquals(listOf(0, 5, 10), ChartMath.niceTicks(0, 10))
        assertEquals(listOf(0, 1, 2, 3), ChartMath.niceTicks(0, 3))
        val step = ChartMath.niceTicks(-7, 0).zipWithNext { a, b -> b - a }.distinct()
        assertEquals(1, step.size)
    }

    @Test
    fun `zu nahe Beschriftungen werden auseinandergeschoben und bleiben im Rahmen`() {
        val placed = ChartMath.spreadLabels(listOf(100f, 102f, 104f, 200f), 16f, 10f, 250f)
        val sorted = placed.sorted()
        for (i in 1 until sorted.size) assertTrue(sorted[i] - sorted[i - 1] >= 16f - 1e-4f)
        assertEquals(200f, placed[3])
        assertTrue(placed.min() >= 10f && placed.max() <= 250f)
    }

    @Test
    fun `unten anstossende Beschriftungen wandern nach oben`() {
        val placed = ChartMath.spreadLabels(listOf(248f, 249f, 250f), 16f, 10f, 250f)
        assertEquals(listOf(218f, 234f, 250f), placed.sorted())
    }

    @Test
    fun `der naechste Punkt zu einer Fingerposition`() {
        assertEquals(0, ChartMath.nearestIndex(-30f, 50f, 300f, 10))
        assertEquals(10, ChartMath.nearestIndex(900f, 50f, 300f, 10))
        assertEquals(5, ChartMath.nearestIndex(200f, 50f, 300f, 10))
        assertEquals(0, ChartMath.nearestIndex(200f, 50f, 0f, 10))
    }
}
