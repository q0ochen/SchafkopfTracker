package io.github.q0ochen.schafkopf.domain.rules

import io.github.q0ochen.schafkopf.domain.model.PlayerId

/** Welche Auszahlungsregel in einem Regelwerk gilt. */
enum class PayoutRule {
    /** Schafkopf-Regel: Beim Einzelspiel rechnet der Solist mit jedem der drei Gegner einzeln ab (±3 T). */
    SCHAFKOPF,

    /** Topf-Teilung: Beim Einzelspiel geht es nur um einen Spielwert; die drei Gegner teilen ihn sich (je T/3). */
    POT_SPLIT,
}

/**
 * Verteilt den Spielwert T eines Spiels auf die Spieler. Jede Umsetzung muss ein Nullsummenspiel liefern:
 * Die Beträge aller Spieler summieren sich zu 0.
 */
fun interface PayoutCalculator {
    /**
     * @param value Der Spielwert T in Cent.
     * @param winners Die Gewinner (2 beim Rufspiel und bei Über Kreuz; 1 oder 3 beim Einzelspiel).
     * @param losers Die Verlierer, in Sitzreihenfolge.
     * @return Betrag je Spieler in Cent, positiv für Gewinner.
     */
    fun payout(value: Int, winners: List<PlayerId>, losers: List<PlayerId>): Map<PlayerId, Int>

    companion object {
        fun of(rule: PayoutRule): PayoutCalculator = when (rule) {
            PayoutRule.SCHAFKOPF -> SchafkopfPayout
            PayoutRule.POT_SPLIT -> PotSplitPayout
        }
    }
}

/**
 * Die übliche Schafkopf-Regel.
 *
 * Zwei gegen zwei: Jeder Verlierer zahlt T, jeder Gewinner bekommt T.
 * Einer gegen drei: Jeder der drei zahlt bzw. bekommt T, der Einzelne bekommt bzw. zahlt 3 T.
 */
object SchafkopfPayout : PayoutCalculator {
    override fun payout(value: Int, winners: List<PlayerId>, losers: List<PlayerId>): Map<PlayerId, Int> {
        // Ohne Gegenseite gibt es nichts zu verteilen.
        if (winners.isEmpty() || losers.isEmpty()) return (winners + losers).associateWith { 0 }

        // Die größere Seite rechnet pro Kopf mit T; die kleinere Seite teilt sich deren Summe.
        val (perWinner, perLoser) = if (winners.size <= losers.size) {
            value * losers.size / winners.size to value
        } else {
            value to value * winners.size / losers.size
        }
        return winners.associateWith { perWinner } + losers.associateWith { -perLoser }
    }
}

/**
 * Topf-Teilung: Im Einzelspiel bekommt oder zahlt der Solist nur T; die drei Gegner teilen sich diesen Betrag.
 * Geht T nicht durch drei, bekommen bzw. zahlen die in der Sitzreihenfolge vorderen Gegner je einen Cent mehr,
 * damit kein Cent verloren geht. Zwei gegen zwei wird wie bei der Schafkopf-Regel abgerechnet.
 */
object PotSplitPayout : PayoutCalculator {
    override fun payout(value: Int, winners: List<PlayerId>, losers: List<PlayerId>): Map<PlayerId, Int> {
        if (winners.size == losers.size || winners.isEmpty() || losers.isEmpty()) {
            return SchafkopfPayout.payout(value, winners, losers)
        }
        val (small, big, smallSign) = if (winners.size < losers.size) Triple(winners, losers, 1) else Triple(losers, winners, -1)
        val pot = value * small.size
        return small.associateWith { smallSign * value } + big.zip(splitEvenly(pot, big.size)).associate { (player, share) -> player to -smallSign * share }
    }

    /** Teilt [total] Cent auf [parts] Teile auf; ein Rest wird vorn beginnend centweise verteilt. */
    private fun splitEvenly(total: Int, parts: Int): List<Int> {
        val base = total / parts
        val remainder = total % parts
        return List(parts) { index -> if (index < remainder) base + 1 else base }
    }
}
