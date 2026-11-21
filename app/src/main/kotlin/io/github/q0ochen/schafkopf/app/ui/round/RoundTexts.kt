package io.github.q0ochen.schafkopf.app.ui.round

import android.content.res.Resources
import androidx.annotation.DrawableRes
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.round.RoundProblem
import io.github.q0ochen.schafkopf.app.round.RoundState
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.SyncNotice

/*
 * Die Texte der gemeinsamen Runde, wie docs/ARCHITEKTUR.md, Abschnitt 8.9 sie festlegt: Statuszeile, Fehler und
 * Hinweise aus dem Abgleich. Ohne Compose, damit sie sich einfach prüfen lassen.
 */

/** Wie es um den Abgleich steht; bestimmt das Symbol. Der Text daneben sagt dasselbe in Worten. */
enum class RoundStatusKind(@DrawableRes val icon: Int) {
    SYNCED(R.drawable.ic_cloud_done),
    PENDING(R.drawable.ic_cloud_upload),
    PROBLEM(R.drawable.ic_cloud_off),
}

data class RoundStatus(val kind: RoundStatusKind, val text: String)

/** Die Statuszeile einer verbundenen Runde: Es gilt die erste zutreffende Zeile der Tabelle in Abschnitt 8.9. */
fun roundStatus(state: RoundState, resources: Resources): RoundStatus = when {
    state.needsSignIn -> RoundStatus(RoundStatusKind.PROBLEM, resources.getString(R.string.round_status_signed_out))
    state.unreadable > 0 -> RoundStatus(
        RoundStatusKind.PROBLEM,
        resources.getQuantityString(R.plurals.round_status_unreadable, state.unreadable, state.unreadable),
    )
    state.refused > 0 -> RoundStatus(
        RoundStatusKind.PROBLEM,
        resources.getQuantityString(R.plurals.round_status_refused, state.refused, state.refused),
    )
    state.problem == RoundProblem.OFFLINE -> RoundStatus(
        RoundStatusKind.PROBLEM,
        if (state.pending > 0) {
            resources.getQuantityString(R.plurals.round_status_offline_pending, state.pending, state.pending)
        } else {
            resources.getString(R.string.round_status_offline)
        },
    )
    state.problem == RoundProblem.SERVER -> RoundStatus(
        RoundStatusKind.PROBLEM,
        if (state.pending > 0) {
            resources.getQuantityString(R.plurals.round_status_server_pending, state.pending, state.pending)
        } else {
            resources.getString(R.string.round_status_server)
        },
    )
    state.pending > 0 -> RoundStatus(
        RoundStatusKind.PENDING,
        resources.getQuantityString(R.plurals.round_status_pending, state.pending, state.pending),
    )
    else -> RoundStatus(RoundStatusKind.SYNCED, resources.getString(R.string.round_status_synced))
}

/**
 * Der Text zu einem gescheiterten Aufruf.
 *
 * @param roundName `true`, wenn es um den Namen einer Runde ging (1 bis 40 Zeichen statt 1 bis 24; vergeben heißt dann:
 *   Das Konto hat schon eine Runde dieses Namens).
 */
fun roundErrorText(error: RoundError, resources: Resources, roundName: Boolean = false): String = resources.getString(
    when (error) {
        RoundError.INVALID_INVITE -> R.string.round_error_invalid_invite
        RoundError.NAME_TAKEN -> if (roundName) R.string.round_error_round_name_taken else R.string.round_error_name_taken
        RoundError.INVALID_NAME -> if (roundName) R.string.round_error_invalid_round_name else R.string.round_error_invalid_name
        RoundError.PLAYER_TAKEN -> R.string.round_error_player_taken
        RoundError.ALREADY_CLAIMED -> R.string.round_error_already_claimed
        RoundError.LIMIT_REACHED -> R.string.round_error_limit
        RoundError.OFFLINE -> R.string.round_error_offline
        RoundError.SIGNED_OUT -> R.string.round_status_signed_out
        else -> R.string.round_error_generic
    },
)

/** Der Hinweis zu einer Änderung an einem Spieler, die der Server abgelehnt hat. */
fun noticeText(notice: SyncNotice, resources: Resources): String {
    val now = notice.nowName
    return when (notice.reason) {
        RoundError.NAME_LOCKED -> resources.getString(R.string.round_notice_name_locked, now ?: notice.name)
        RoundError.NAME_TAKEN ->
            if (now == null) {
                resources.getString(R.string.round_notice_name_taken_dropped, notice.name)
            } else {
                resources.getString(R.string.round_notice_name_taken_renamed, notice.name, now)
            }
        RoundError.PLAYER_OWNED -> resources.getString(R.string.round_notice_player_owned, notice.name)
        RoundError.LIMIT_REACHED -> resources.getString(R.string.round_notice_limit, notice.name)
        else -> resources.getString(R.string.round_notice_invalid_name, notice.name)
    }
}
