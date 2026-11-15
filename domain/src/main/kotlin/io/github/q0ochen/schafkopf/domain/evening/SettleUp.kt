package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.PlayerId

/** Eine Ausgleichszahlung am Ende des Abends. */
data class Transfer(val from: PlayerId, val to: PlayerId, val amount: Int)

/**
 * „Wer zahlt wem“: gleicht die Endstände mit möglichst wenigen Zahlungen aus.
 *
 * Verfahren: Der größte Schuldner zahlt an den größten Gläubiger, bis alle bei 0 sind.
 * Das ergibt höchstens (Spielerzahl − 1) Zahlungen; bei vier Spielern ist das zugleich das Minimum.
 */
object SettleUp {

    /** Ob sich die Salden zu 0 summieren. Nur eine fehlerhafte freie Buchung kann das verhindern. */
    fun isBalanced(balances: Map<PlayerId, Int>): Boolean = balances.values.sum() == 0

    /**
     * Die Zahlungen, mit denen alle auf 0 kommen. Gehen die Salden nicht auf (siehe [isBalanced]), wird so weit
     * wie möglich ausgeglichen; der Rest bleibt offen. Die App stürzt deshalb nie ab.
     */
    fun transfers(balances: Map<PlayerId, Int>): List<Transfer> {
        val open = balances.toMutableMap()
        val transfers = ArrayList<Transfer>()
        while (true) {
            val debtor = open.entries.filter { it.value < 0 }.minByOrNull { it.value } ?: break
            val creditor = open.entries.filter { it.value > 0 }.maxByOrNull { it.value } ?: break
            val amount = minOf(-debtor.value, creditor.value)
            transfers += Transfer(from = debtor.key, to = creditor.key, amount = amount)
            open[debtor.key] = debtor.value + amount
            open[creditor.key] = creditor.value - amount
        }
        return transfers
    }
}
