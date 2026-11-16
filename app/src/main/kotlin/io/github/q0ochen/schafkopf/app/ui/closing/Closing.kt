package io.github.q0ochen.schafkopf.app.ui.closing

import android.content.res.Resources
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.common.Money
import io.github.q0ochen.schafkopf.app.ui.common.formatDate
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.evening.SettleUp
import io.github.q0ochen.schafkopf.domain.evening.Transfer
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import java.time.ZoneId

/** Ein Platz im Endstand. */
data class FinalStanding(val player: PlayerId, val name: String, val balance: Int, val rank: Int)

/**
 * Der Abschluss eines Abends: Endstand und wer wem wie viel zahlt (wie in der Web-App, `web/src/ui/closing.ts`).
 *
 * @property standings Alle vier Spieler nach Platz; bei gleichem Platz in Sitzreihenfolge.
 * @property transfers Die Zahlungen, mit denen alle Stände auf null gehen ([SettleUp]).
 * @property balanced `false` nur bei fehlerhaften freien Buchungen: Dann bleibt beim Ausgleich ein Rest offen.
 */
data class Closing(val standings: List<FinalStanding>, val transfers: List<Transfer>, val balanced: Boolean) {
    companion object {
        fun of(state: EveningState, names: Map<PlayerId, String>): Closing = Closing(
            standings = state.evening.seating
                .map { player ->
                    FinalStanding(
                        player = player,
                        name = names[player] ?: "?",
                        balance = state.balances[player] ?: 0,
                        rank = state.ranks[player] ?: 0,
                    )
                }
                .sortedBy { it.rank },
            transfers = SettleUp.transfers(state.balances),
            balanced = SettleUp.isBalanced(state.balances),
        )
    }
}

/** Der Abschluss als schlichter Text, zum Beispiel für eine Nachricht an die Runde (wie `closingText` der Web-App). */
fun closingShareText(
    resources: Resources,
    state: EveningState,
    closing: Closing,
    names: Map<PlayerId, String>,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val evening = state.evening
    val asMoney = evening.rules.displayAsMoney
    val date = formatDate(evening.startedAt, zone)
    val location = evening.location.trim()
    val lines = ArrayList<String>()
    lines += if (location.isEmpty()) {
        resources.getString(R.string.share_closing_heading, date)
    } else {
        resources.getString(R.string.share_closing_heading_location, date, location)
    }
    lines += resources.getQuantityString(R.plurals.games_count, state.gamesPlayed, state.gamesPlayed)
    lines += ""
    for (standing in closing.standings) {
        lines += resources.getString(
            R.string.share_standing_line,
            standing.rank,
            standing.name,
            Money.format(standing.balance, asMoney, signed = true),
        )
    }
    if (closing.transfers.isNotEmpty()) {
        lines += ""
        lines += resources.getString(R.string.share_transfers_heading)
        for (transfer in closing.transfers) {
            lines += resources.getString(
                R.string.share_transfer_line,
                names[transfer.from] ?: "?",
                names[transfer.to] ?: "?",
                Money.format(transfer.amount, asMoney),
            )
        }
    }
    return lines.joinToString("\n")
}
