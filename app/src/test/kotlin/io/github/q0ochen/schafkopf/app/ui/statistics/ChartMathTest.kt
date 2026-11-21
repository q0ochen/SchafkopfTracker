package io.github.q0ochen.schafkopf.app.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

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
    fun `Linien, die genau gleich verlaufen, liegen nebeneinander statt uebereinander`() {
        // Dieselben Fälle wie in shareImage.test.ts der Web-App.
        val x = { index: Int -> 100f + index * 100f }
        val y = { value: Int -> 500f - value }
        // Anna und Cilli gewinnen zusammen, dann trennen sich ihre Wege; Bert verliert allein.
        val (anna, cilli, bert) = ChartMath.chartPaths(
            listOf(listOf(0, 10, 60), listOf(0, 10, 0), listOf(0, -20, -20)),
            x,
            y,
            6f,
        )
        fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)
        // Erstes Stück: Anna und Cilli je 3 Pixel quer zur Linie versetzt, zusammen genau eine Linienbreite auseinander.
        assertEquals(6f, distance(anna[0], cilli[0]), 0.001f)
        assertEquals(6f, distance(anna[1], cilli[1]), 0.001f)
        assertEquals(500f, (anna[0].second + cilli[0].second) / 2, 0.001f)
        assertEquals(490f, (anna[1].second + cilli[1].second) / 2, 0.001f)
        // Danach wieder genau auf den Werten; Bert ist nie versetzt.
        assertEquals(300f to 440f, anna.last())
        assertEquals(300f to 500f, cilli.last())
        assertEquals(listOf(100f to 500f, 200f to 520f, 300f to 520f), bert)
        // Drei gleiche Linien: in der Mitte die zweite, die anderen eine Linienbreite daneben.
        val three = ChartMath.chartPaths(listOf(listOf(5, 5), listOf(5, 5), listOf(5, 5)), x, y, 6f)
        assertEquals(listOf(listOf(489f, 489f), listOf(495f, 495f), listOf(501f, 501f)), three.map { path -> path.map { it.second } })
    }

    @Test
    fun `ohne zwei Punkte gibt es keine Linie`() {
        assertEquals(listOf(emptyList<Pair<Float, Float>>()), ChartMath.chartPaths(listOf(listOf(0)), { 0f }, { 0f }, 6f))
        assertEquals(emptyList<List<Pair<Float, Float>>>(), ChartMath.chartPaths(emptyList(), { 0f }, { 0f }, 6f))
    }

    @Test
    fun `der naechste Punkt zu einer Fingerposition`() {
        assertEquals(0, ChartMath.nearestIndex(-30f, 50f, 300f, 10))
        assertEquals(10, ChartMath.nearestIndex(900f, 50f, 300f, 10))
        assertEquals(5, ChartMath.nearestIndex(200f, 50f, 300f, 10))
        assertEquals(0, ChartMath.nearestIndex(200f, 50f, 0f, 10))
    }

    @Test
    fun `Balken wachsen von der Null in der Mitte, der groesste Betrag bis an den Rand`() {
        val scale = BarScale.of(listOf(-50, 150, 0))
        assertEquals(BarScale(150), scale)
        assertEquals(100f, scale.zero(200f))
        assertEquals(100f..200f, scale.span(150, 200f, 3f))
        assertEquals(50f..100f, scale.span(-75, 200f, 3f))
        assertEquals(100f..150f, scale.span(75, 200f, 3f))
        assertEquals(100f..100f, scale.span(0, 200f, 3f))
    }

    @Test
    fun `ein Verlust kann der groesste Betrag sein`() {
        val scale = BarScale.of(listOf(10, -40))
        assertEquals(BarScale(40), scale)
        assertEquals(0f..50f, scale.span(-40, 100f, 3f))
        assertEquals(50f..62.5f, scale.span(10, 100f, 3f))
    }

    @Test
    fun `ohne einen Wert ungleich null gibt es keinen Balken`() {
        val empty = BarScale.of(emptyList())
        assertEquals(BarScale(0), empty)
        assertEquals(50f, empty.zero(100f))
        assertEquals(50f..50f, empty.span(0, 100f, 3f))
        assertEquals(BarScale(0), BarScale.of(listOf(0, 0)))
    }

    @Test
    fun `ein kleiner Betrag bekommt die Mindestlaenge, aber hoechstens die halbe Breite`() {
        val scale = BarScale.of(listOf(1, -1000))
        assertEquals(50f..53f, scale.span(1, 100f, 3f))
        assertEquals(47f..50f, scale.span(-1, 100f, 3f))
        assertEquals(50f..100f, scale.span(5, 100f, 80f))
        // Ein Wert außerhalb der Skala reicht nur bis an den Rand.
        assertEquals(0f..50f, scale.span(-5000, 100f, 3f))
    }

    @Test
    fun `riesige Betraege laufen nicht ueber`() {
        val scale = BarScale.of(listOf(Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(BarScale(2_147_483_648L), scale)
        assertEquals(0f..50f, scale.span(Int.MIN_VALUE, 100f, 3f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `eine Skala mit negativem Betrag gibt es nicht`() {
        BarScale(-1)
    }
}
