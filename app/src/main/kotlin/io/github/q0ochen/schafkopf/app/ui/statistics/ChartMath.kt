package io.github.q0ochen.schafkopf.app.ui.statistics

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Rechenteil des Verlaufsdiagramms, ohne Android und deshalb einfach zu prüfen (wie `BalanceChart.tsx` der Web-App). */
object ChartMath {

    /**
     * Runde Teilstriche für die y-Achse (etwa [target] Abschnitte), die den Bereich von [min] bis [max] ganz abdecken.
     * Beträge sind ganze Cent; deshalb sind auch die Teilstriche ganze Zahlen (nie „2,5 Cent“).
     */
    fun niceTicks(min: Int, max: Int, target: Int = 4): List<Int> {
        var low = min.toDouble()
        var high = max.toDouble()
        if (min == max) {
            val pad = max(1.0, abs(low) * 0.1)
            low -= pad
            high += pad
        }
        val rough = (high - low) / target
        val magnitude = 10.0.pow(floor(log10(rough)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0)
            .map { it * magnitude }
            .filter { it >= 1.0 && it == floor(it) }
            .firstOrNull { it >= rough }
            ?: max(1.0, 10 * magnitude)
        val start = floor(low / step) * step
        val ticks = ArrayList<Int>()
        var index = 0
        while (start + index * step <= high + step * 1e-9) {
            ticks += (start + index * step).roundToLong().toInt()
            index++
        }
        if (ticks.last() < high) ticks += (ticks.last() + step).roundToLong().toInt()
        return ticks
    }

    /**
     * Schiebt Beschriftungen auseinander, damit sie sich nicht überlappen: Abstand mindestens [gap], alle zwischen
     * [min] und [max]. Die Reihenfolge von oben nach unten bleibt erhalten.
     *
     * @param positions Die gewünschten senkrechten Positionen (zum Beispiel die Linienenden).
     * @return Die Positionen in derselben Reihenfolge wie [positions].
     */
    fun spreadLabels(positions: List<Float>, gap: Float, min: Float, max: Float): List<Float> {
        val order = positions.withIndex().sortedBy { it.value }
        val placed = order.map { it.value }.toMutableList()
        for (i in 1 until placed.size) placed[i] = maxOf(placed[i], placed[i - 1] + gap)
        val overflow = if (placed.isEmpty()) 0f else placed.last() - max
        if (overflow > 0) for (i in placed.indices) placed[i] -= overflow
        for (i in placed.size - 2 downTo 0) placed[i] = minOf(placed[i], placed[i + 1] - gap)
        if (placed.isNotEmpty() && placed.first() < min) {
            val shift = min - placed.first()
            for (i in placed.indices) placed[i] += shift
        }
        val result = MutableList(positions.size) { 0f }
        order.forEachIndexed { rank, item -> result[item.index] = placed[rank] }
        return result
    }

    /** Der Punkt, der der waagerechten Position [x] am nächsten liegt (0 bis [last]). */
    fun nearestIndex(x: Float, plotLeft: Float, plotWidth: Float, last: Int): Int {
        if (plotWidth <= 0f || last <= 0) return 0
        return ((x - plotLeft) / plotWidth * last).roundToInt().coerceIn(0, last)
    }

    /**
     * Die Linien des Diagramms als Punktfolgen, gerade von Punkt zu Punkt. Wo Linien auf einem Stück genau gleich
     * verlaufen (etwa die Partner eines Rufspiels), liegen sie dort nebeneinander statt übereinander, jede um [width]
     * quer zum Stück versetzt; so bleibt jede Farbe zu sehen. Die Reihenfolge nebeneinander folgt der Liste. Wie
     * `chartPaths` im geteilten Bild der Web-App (`web/src/ui/shareImage.ts`).
     *
     * @param series Je Linie ein Wert je Punkt.
     * @param x Waagerechte Position eines Punktes.
     * @param y Senkrechte Position eines Wertes.
     * @return Je Linie die Punkte als (x, y); zwei gleiche Punkte hintereinander stehen nur einmal da.
     */
    fun chartPaths(series: List<List<Int>>, x: (Int) -> Float, y: (Int) -> Float, width: Float): List<List<Pair<Float, Float>>> {
        val paths = series.map { ArrayList<Pair<Float, Float>>() }
        val segments = (series.maxOfOrNull { it.size } ?: 0) - 1
        fun add(path: MutableList<Pair<Float, Float>>, point: Pair<Float, Float>) {
            if (path.lastOrNull() != point) path += point
        }
        for (segment in 0 until segments) {
            // Linien mit genau demselben Stück (gleicher Wert davor und danach) bilden ein Bündel.
            val bundles = LinkedHashMap<Pair<Int, Int>, MutableList<Int>>()
            series.forEachIndexed { index, values ->
                if (segment + 1 < values.size) bundles.getOrPut(values[segment] to values[segment + 1]) { ArrayList() } += index
            }
            for (bundle in bundles.values) {
                bundle.forEachIndexed { rank, index ->
                    val values = series[index]
                    val x0 = x(segment)
                    val y0 = y(values[segment])
                    val x1 = x(segment + 1)
                    val y1 = y(values[segment + 1])
                    val length = hypot(x1 - x0, y1 - y0).takeIf { it > 0f } ?: 1f
                    val shift = (rank - (bundle.size - 1) / 2f) * width
                    val dx = -(y1 - y0) / length * shift
                    val dy = (x1 - x0) / length * shift
                    add(paths[index], (x0 + dx) to (y0 + dy))
                    add(paths[index], (x1 + dx) to (y1 + dy))
                }
            }
        }
        return paths
    }
}

/**
 * Waagerechte Skala der Balken einer Spielart im Spielarten-Diagramm: die Null in der Mitte, nach beiden Seiten so
 * weit wie der größte Betrag [max] in Cent. Jede Spielart hat ihre eigene Skala, damit sich die Spieler innerhalb
 * einer Spielart gut vergleichen lassen (Soli bringen ein Vielfaches eines Rufspiels); die Beträge stehen als Zahl
 * daneben.
 */
data class BarScale(val max: Long) {
    init {
        require(max >= 0) { "Der größte Betrag kann nicht negativ sein: $max" }
    }

    /** Wo die Null über die Breite [width] liegt: immer in der Mitte. */
    fun zero(width: Float): Float = width / 2f

    /**
     * Anfang und Ende des Balkens für [value] über die Breite [width]: von der Null nach rechts (Gewinn) oder links
     * (Verlust), beim größten Betrag bis an den Rand. Ein Wert ungleich 0 bekommt mindestens die Länge [minLength],
     * damit auch ein kleiner Gewinn oder Verlust zu sehen ist, höchstens aber die halbe Breite. Bei 0 und in einer
     * Skala ohne Betrag ([max] 0) liegen Anfang und Ende auf der Null.
     */
    fun span(value: Int, width: Float, minLength: Float): ClosedFloatingPointRange<Float> {
        val zero = zero(width)
        if (value == 0 || max == 0L) return zero..zero
        val half = width / 2f
        val share = (abs(value.toLong()).toDouble() / max).coerceAtMost(1.0)
        val length = maxOf((share * half).toFloat(), minLength).coerceAtMost(half)
        return if (value > 0) zero..zero + length else zero - length..zero
    }

    companion object {
        /** Die Skala für [values]: so weit wie der größte Betrag, egal ob Gewinn oder Verlust. */
        fun of(values: Iterable<Int>): BarScale = BarScale(values.maxOfOrNull { abs(it.toLong()) } ?: 0L)
    }
}
