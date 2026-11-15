package io.github.q0ochen.schafkopf.app.ui.common

import io.github.q0ochen.schafkopf.domain.model.AmountFormatter
import kotlin.math.abs

/**
 * Einheitliche Darstellung von Beträgen. Gerechnet wird überall in ganzen Cent (1 Cent = 1 Punkt);
 * in Euro formatiert wird nur hier in der Oberfläche.
 */
object Money : AmountFormatter {
    private const val MINUS = "−"
    private const val NO_BREAK_SPACE = " "

    /**
     * @param asMoney `true`: Euro mit zwei Nachkommastellen („1,20 €“). `false`: Punkte („120“).
     * @param signed `true`: positive Beträge bekommen ein Plus, damit Gewinn und Verlust nie nur an der Farbe hängen.
     */
    override fun format(cents: Int, asMoney: Boolean, signed: Boolean): String {
        val sign = when {
            cents < 0 -> MINUS
            cents > 0 && signed -> "+"
            else -> ""
        }
        val absolute = abs(cents)
        return if (asMoney) {
            "$sign${absolute / 100},${(absolute % 100).toString().padStart(2, '0')}$NO_BREAK_SPACE€"
        } else {
            "$sign$absolute"
        }
    }

    /** Betrag ohne Plus vor positiven Werten, zum Beispiel ein Spielwert. */
    fun format(cents: Int, asMoney: Boolean): String = format(cents, asMoney, signed = false)
}
