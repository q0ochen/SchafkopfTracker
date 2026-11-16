package io.github.q0ochen.schafkopf.app.ui.statistics

import kotlin.math.abs
import kotlin.math.floor
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
     * damit auch ein kleiner Gewinn oder Verlust zu sehen ist, höchstens aber die halbe Breite. Bei 0 liegen Anfang
     * und Ende auf der Null.
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
