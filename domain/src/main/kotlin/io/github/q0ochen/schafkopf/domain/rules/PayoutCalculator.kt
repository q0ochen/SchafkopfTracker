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
 * Die Beträge aller Spieler summieren sich zu 0 – auch bei unsinnigen Eingaben (beschädigte Daten). Lässt sich
 * nichts sinnvoll verteilen, bekommen alle 0.
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
    override fun payout(value: Int, winners: List<PlayerId>, losers: List<PlayerId>): Map<PlayerId, Int> =
        // Die größere Seite rechnet pro Kopf mit T; die kleinere Seite teilt sich deren Summe.
        distribute(value, winners, losers, winnersPerHead = winners.size > losers.size)
}

/**
 * Topf-Teilung: Im Einzelspiel bekommt oder zahlt der Solist nur T; die drei Gegner teilen sich diesen Betrag.
 * Geht T nicht durch drei, bekommen bzw. zahlen die in der Sitzreihenfolge vorderen Gegner je einen Cent mehr,
 * damit kein Cent verloren geht. Zwei gegen zwei wird wie bei der Schafkopf-Regel abgerechnet.
 */
object PotSplitPayout : PayoutCalculator {
    override fun payout(value: Int, winners: List<PlayerId>, losers: List<PlayerId>): Map<PlayerId, Int> =
        // Die kleinere Seite rechnet pro Kopf mit T; die größere Seite teilt sich deren Summe.
        distribute(value, winners, losers, winnersPerHead = winners.size < losers.size)
}

/**
 * Gemeinsamer Kern beider Regeln: Die eine Seite rechnet pro Kopf mit T ab, die andere teilt sich deren Summe.
 * Geht die Summe nicht auf, wird der Rest vorn beginnend centweise verteilt. So ist das Ergebnis immer ein
 * Nullsummenspiel; bei zwei gegen zwei und einem gegen drei mit der Schafkopf-Regel bleibt nie ein Rest.
 *
 * Wo sich nichts sinnvoll verteilen lässt, bekommen alle 0: ohne Gegenseite, wenn ein Spieler doppelt genannt ist,
 * bei negativem Spielwert und wenn die Summe nicht mehr in einen `Int` passt.
 *
 * @param winnersPerHead `true`: Jeder Gewinner bekommt T, die Verlierer teilen sich die Summe.
 *   `false`: Jeder Verlierer zahlt T, die Gewinner teilen sich die Summe.
 */
private fun distribute(
    value: Int,
    winners: List<PlayerId>,
    losers: List<PlayerId>,
    winnersPerHead: Boolean,
): Map<PlayerId, Int> {
    val nothing = (winners + losers).associateWith { 0 }
    val perHead = if (winnersPerHead) winners else losers
    val sharing = if (winnersPerHead) losers else winners
    // Als Long gerechnet, damit ein riesiger Spielwert nicht überläuft.
    val total = value.toLong() * perHead.size
    val everyoneOnce = nothing.size == winners.size + losers.size
    if (winners.isEmpty() || losers.isEmpty() || !everyoneOnce || total !in 0..Int.MAX_VALUE) return nothing

    // Gewinner bekommen (+), Verlierer zahlen (−).
    val perHeadSign = if (winnersPerHead) 1 else -1
    val shares = splitEvenly(total.toInt(), sharing.size)
    return perHead.associateWith { perHeadSign * value } +
        sharing.zip(shares).associate { (player, share) -> player to -perHeadSign * share }
}

/** Teilt [total] Cent (nie negativ) auf [parts] Teile auf; ein Rest wird vorn beginnend centweise verteilt. */
private fun splitEvenly(total: Int, parts: Int): List<Int> {
    val base = total / parts
    val remainder = total % parts
    return List(parts) { index -> if (index < remainder) base + 1 else base }
}
