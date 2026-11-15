package io.github.q0ochen.schafkopf.domain.summary

import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.model.AmountFormatter
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import java.time.ZoneId
import kotlin.math.abs

/**
 * Formatierung für die Tests, so wie die Oberfläche sie mitgibt: Euro mit Komma, geschütztem Leerzeichen (U+00A0)
 * vor dem Euro-Zeichen und echtem Minuszeichen (U+2212); Punkte als bloße Zahl. In der App selbst liegt die
 * Formatierung im Modul `app` (`Money`), weil in Euro nur in der Oberfläche formatiert wird.
 */
object TestAmounts : AmountFormatter {
    override fun format(cents: Int, asMoney: Boolean, signed: Boolean): String {
        val sign = when {
            cents < 0 -> "−"
            cents > 0 && signed -> "+"
            else -> ""
        }
        val absolute = abs(cents)
        return if (asMoney) {
            "$sign${absolute / 100},${(absolute % 100).toString().padStart(2, '0')} €"
        } else {
            "$sign$absolute"
        }
    }
}

/** Kurzform für die Tests: baut die Fakten mit [TestAmounts]. */
fun SummaryFactsBuilder.build(state: EveningState, names: Map<PlayerId, String>, zone: ZoneId): SummaryFacts =
    build(state, names, TestAmounts, zone)

/** Kurzform für die Tests: baut die Fakten mit [TestAmounts] in der Zeitzone des Geräts. */
fun SummaryFactsBuilder.build(state: EveningState, names: Map<PlayerId, String>): SummaryFacts =
    build(state, names, TestAmounts)
