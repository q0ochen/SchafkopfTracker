package io.github.q0ochen.schafkopf.app.ui.board

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.common.Money
import io.github.q0ochen.schafkopf.app.ui.common.labelRes
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.EntryResult
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel

/** Überschrift und Einzelheiten eines Eintrags, so wie sie in der Liste stehen. */
data class EntryText(val title: String, val detail: String)

/** Baut die Texte eines Eintrags aus den Tatsachen und den Spielernamen. */
@Composable
fun entryText(result: EntryResult, names: Map<PlayerId, String>, asMoney: Boolean): EntryText {
    fun name(player: PlayerId?): String = names[player] ?: "?"

    return when (val entry = result.entry) {
        is ThrownInEntry -> EntryText(stringResource(R.string.entry_thrown_in), "")

        is BockTriggerEntry -> EntryText(
            title = if (entry.player != null) {
                stringResource(R.string.entry_bock_trigger_player, name(entry.player))
            } else {
                stringResource(R.string.entry_bock_trigger)
            },
            detail = if (entry.handThrownIn) stringResource(R.string.entry_thrown_in) else "",
        )

        is ManualEntry -> EntryText(stringResource(R.string.entry_manual), entry.note)

        is GameEntry -> {
            val who = when {
                entry.partner == null -> name(entry.declarer)
                // Über Kreuz kennt keinen Ansager: Beide stehen gleichberechtigt nebeneinander.
                !entry.type.hasDeclarer -> stringResource(R.string.entry_pair, name(entry.declarer), name(entry.partner))
                else -> stringResource(R.string.entry_with_partner, name(entry.declarer), name(entry.partner))
            }
            val type = buildString {
                append(stringResource(entry.type.labelRes()))
                entry.suit?.let { append(" (").append(stringResource(it.labelRes())).append(")") }
            }
            val details = ArrayList<String>()
            details += stringResource(if (entry.declarerWon) R.string.entry_won else R.string.entry_lost)
            when (entry.announcement) {
                Announcement.NONE -> if (entry.level != ResultLevel.NORMAL) details += stringResource(entry.level.labelRes())
                Announcement.TOUT -> details += stringResource(R.string.announcement_tout)
                Announcement.SIE -> details += stringResource(R.string.announcement_sie)
            }
            val counted = result.breakdown?.laufendeCounted ?: 0
            if (counted > 0) details += pluralStringResource(R.plurals.entry_laufende, counted, counted)
            if (entry.klopfer.isNotEmpty()) details += stringResource(R.string.entry_klopfer, entry.klopfer.size)
            if (entry.kontra != KontraLevel.NONE) details += stringResource(entry.kontra.labelRes())
            when {
                result.bockExponent == 1 -> details += stringResource(R.string.entry_bock)
                result.bockExponent > 1 -> details += stringResource(R.string.entry_bock_multi, 1 shl result.bockExponent)
            }
            result.breakdown?.let { details += stringResource(R.string.entry_value, Money.format(it.value, asMoney)) }

            EntryText(
                title = stringResource(R.string.entry_game_title, result.gameNumber ?: 0, type, who),
                detail = details.joinToString(" · "),
            )
        }
    }
}
