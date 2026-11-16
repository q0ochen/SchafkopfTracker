package io.github.q0ochen.schafkopf.app.ui.statistics

import android.content.res.Resources
import androidx.annotation.StringRes
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.common.Money
import io.github.q0ochen.schafkopf.app.ui.common.formatDate
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.stats.GelegtBadge
import io.github.q0ochen.schafkopf.domain.stats.Statistics
import java.time.Instant
import java.time.ZoneId

/** Der Text zu einem Abzeichen des „Sollte-er-es-lassen?“-Index (wie in der Web-App). */
@StringRes
fun GelegtBadge.labelRes(): Int = when (this) {
    GelegtBadge.STREICHHOLZ_SPARER -> R.string.badge_streichholz_sparer
    GelegtBadge.NOCH_OFFEN -> R.string.badge_noch_offen
    GelegtBadge.SICHERE_BANK -> R.string.badge_sichere_bank
    GelegtBadge.BRANDSTIFTER -> R.string.badge_brandstifter
    GelegtBadge.GLUECKSRITTER -> R.string.badge_gluecksritter
    GelegtBadge.WACKELKANDIDAT -> R.string.badge_wackelkandidat
}

/** „4 Abende · 31 Spiele · Umsatz 12,40 €“; bei einem einzelnen Abend ohne die Zahl der Abende. */
fun statisticsSummary(resources: Resources, stats: Statistics, single: Boolean, asMoney: Boolean): String {
    val parts = ArrayList<String>()
    if (!single) parts += resources.getQuantityString(R.plurals.stats_evenings_count, stats.eveningCount, stats.eveningCount)
    parts += resources.getQuantityString(R.plurals.games_count, stats.gameCount, stats.gameCount)
    parts += resources.getString(R.string.stats_turnover, Money.format(stats.finance.turnover, asMoney))
    return parts.joinToString(" · ")
}

/** Die Auszeichnungen als Paare aus Titel und Wert; ohne Träger steht „noch niemand“ da. */
fun awardLines(
    resources: Resources,
    stats: Statistics,
    names: (PlayerId) -> String,
    asMoney: Boolean,
    short: Boolean,
): List<Pair<String, String>> {
    val nobody = resources.getString(R.string.award_nobody)
    fun title(long: Int, shortRes: Int) = resources.getString(if (short) shortRes else long)
    val bock = stats.bock.king?.let {
        resources.getString(
            R.string.award_bock_king_value,
            names(it.player),
            Money.format(it.balance, asMoney, signed = true),
            resources.getQuantityString(R.plurals.stats_bock_games_dative, it.games, it.games),
        )
    }
    val solo = stats.solo.specialist?.let {
        resources.getString(R.string.award_solo_value, names(it.player), it.solos, it.announced)
    }
    val wallflower = stats.solo.wallflower?.let {
        resources.getString(R.string.award_solo_value, names(it.player), it.solos, it.announced)
    }
    val partners = stats.partners.favorite?.let {
        resources.getString(
            R.string.award_partners_value,
            names(it.first),
            names(it.second),
            it.won,
            resources.getQuantityString(R.plurals.stats_games_dative, it.games, it.games),
            Money.format(it.balance, asMoney, signed = true),
        )
    }
    return listOf(
        title(R.string.award_bock_king, R.string.award_bock_king_short) to (bock ?: nobody),
        title(R.string.award_solo, R.string.award_solo_short) to (solo ?: nobody),
        title(R.string.award_wallflower, R.string.award_wallflower_short) to (wallflower ?: nobody),
        title(R.string.award_partners, R.string.award_partners_short) to (partners ?: nobody),
    )
}

/**
 * Die Statistik als schlichter Text für eine Nachricht an die Runde: Überschrift, Kontostand, Auszeichnungen und
 * das Fazit zum Legen. Die ausführlichen Tabellen bleiben in der App.
 *
 * @param eveningStart Beginn des ausgewerteten Abends; `null` für alle Abende.
 */
fun statisticsShareText(
    resources: Resources,
    stats: Statistics,
    eveningStart: Instant?,
    names: (PlayerId) -> String,
    asMoney: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val lines = ArrayList<String>()
    lines += if (eveningStart == null) {
        resources.getString(R.string.share_stats_heading_all)
    } else {
        resources.getString(R.string.share_stats_heading_evening, formatDate(eveningStart, zone))
    }
    lines += statisticsSummary(resources, stats, single = eveningStart != null, asMoney = asMoney)
    lines += ""
    lines += resources.getString(R.string.share_stats_balance_heading)
    for (standing in stats.finance.standings.sortedBy { it.rank }) {
        lines += resources.getString(
            R.string.share_standing_line,
            standing.rank,
            names(standing.player),
            Money.format(standing.balance, asMoney, signed = true),
        )
    }
    lines += ""
    lines += resources.getString(R.string.share_stats_awards_heading)
    for ((title, value) in awardLines(resources, stats, names, asMoney, short = true)) {
        lines += resources.getString(R.string.share_stats_line, title, value)
    }
    lines += ""
    lines += resources.getString(R.string.share_stats_gelegt_heading)
    for (record in stats.gelegt) {
        lines += resources.getString(R.string.share_stats_line, names(record.player), resources.getString(record.badge.labelRes()))
    }
    return lines.joinToString("\n")
}
