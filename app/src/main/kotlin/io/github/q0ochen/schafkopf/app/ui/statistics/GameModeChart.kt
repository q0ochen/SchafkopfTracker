package io.github.q0ochen.schafkopf.app.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.common.AmountText
import io.github.q0ochen.schafkopf.app.ui.common.Money
import io.github.q0ochen.schafkopf.app.ui.common.PlayerName
import io.github.q0ochen.schafkopf.app.ui.common.formatPercent
import io.github.q0ochen.schafkopf.app.ui.theme.playerColor
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.stats.ModeRecord
import io.github.q0ochen.schafkopf.domain.stats.Statistics

/**
 * Ein Spieler in einer Spielart.
 *
 * @property record Seine selbst gespielten Spiele dieser Spielart; `null` oder ohne Spiel heißt: nicht gespielt.
 */
data class ModeChartRow(val name: String, val colorIndex: Int, val record: ModeRecord?) {
    /** Die Spiele, sofern es welche gab. */
    val played: ModeRecord? get() = record?.takeIf { it.games > 0 }
}

/**
 * Eine Spielart im Diagramm.
 *
 * @property title Name der Spielart.
 * @property games Wie oft die Spielart insgesamt gespielt wurde.
 * @property rows Eine Zeile je Spieler, in der Reihenfolge der Statistik.
 */
data class ModeChartGroup(val title: String, val games: Int, val rows: List<ModeChartRow>)

/**
 * Die Gruppen des Diagramms aus der Statistik: nur Spielarten, die gespielt wurden, in der festen Reihenfolge der
 * Spielarten; je Gruppe alle Spieler der Statistik mit ihren selbst gespielten Spielen.
 */
fun modeChartGroups(
    stats: Statistics,
    typeLabel: (GameType) -> String,
    name: (PlayerId) -> String,
    colorIndex: (PlayerId) -> Int,
): List<ModeChartGroup> = GameType.entries
    .filter { (stats.history.gameTypeCounts[it] ?: 0) > 0 }
    .map { type ->
        ModeChartGroup(
            title = typeLabel(type),
            games = stats.history.gameTypeCounts[type] ?: 0,
            rows = stats.gameModes.map { modes ->
                ModeChartRow(name(modes.player), colorIndex(modes.player), modes.asPlayer[type])
            },
        )
    }

// Zusammen mit der Balkenfläche passt das auf schmale Handys (360 dp, abzüglich Rand bleiben gut 110 dp für Balken).
private val NameWidth = 80.dp
private val AmountWidth = 68.dp
private val RateWidth = 68.dp
private val RowHeight = 44.dp
private val BarThickness = 12.dp
private val BarRadius = 4.dp
private val MinBarLength = 3.dp

/** Abstand der Skala zum Rand der Balkenfläche, damit Nulllinie und Balkenende nicht am Rand kleben. */
private val BarInset = 4.dp

/**
 * Spielarten-Analyse als Balkendiagramm: je Spielart eine Gruppe, je Spieler eine Zeile mit Name, Balken,
 * Durchschnitt je Spiel und Gewinnquote. Der Balken wächst von der Null in der Mitte nach rechts (Gewinn) oder nach
 * links (Verlust). Jede Spielart hat ihren eigenen Maßstab ([BarScale]), weil ein Solo ein Vielfaches eines
 * Rufspiels bringt; verglichen wird innerhalb einer Spielart. Jede Zahl steht als Text daneben, die Farbe
 * kennzeichnet nur den Spieler (wie im Vermögensverlauf), und Screenreader lesen jede Zeile als einen Satz.
 */
@Composable
fun GameModeChart(groups: List<ModeChartGroup>, asMoney: Boolean, modifier: Modifier = Modifier) {
    val scales = remember(groups) {
        groups.map { group -> BarScale.of(group.rows.mapNotNull { it.played?.averageAmount }) }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        ColumnTitles()
        groups.forEachIndexed { index, group ->
            GroupTitle(group)
            for (row in group.rows) ModeRow(group.title, row, scales[index], asMoney)
        }
    }
}

/** Spaltentitel über allen Gruppen; Screenreader überspringen sie, weil jede Zeile ihre Werte benennt. */
@Composable
private fun ColumnTitles() {
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { },
    ) {
        Text(stringResource(R.string.stats_col_player), style = style, color = color, modifier = Modifier.width(NameWidth))
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.stats_modes_col_average),
            style = style,
            color = color,
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.width(AmountWidth),
        )
        Text(
            stringResource(R.string.stats_col_won),
            style = style,
            color = color,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(RateWidth),
        )
    }
}

@Composable
private fun GroupTitle(group: ModeChartGroup) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp)
            .semantics(mergeDescendants = true) { heading() },
    ) {
        Text(
            group.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            pluralStringResource(R.plurals.games_count, group.games, group.games),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeRow(type: String, row: ModeChartRow, scale: BarScale, asMoney: Boolean) {
    val record = row.played
    val average = record?.averageAmount
    val description = if (record == null || average == null) {
        stringResource(R.string.stats_modes_row_none, row.name, type)
    } else {
        stringResource(
            R.string.stats_modes_row,
            row.name,
            type,
            Money.format(average, asMoney, signed = true),
            record.won,
            record.games,
            formatPercent(record.winRate),
        )
    }
    val barColor = playerColor(row.colorIndex)
    val zeroColor = MaterialTheme.colorScheme.outline
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = RowHeight)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        PlayerName(
            row.name,
            row.colorIndex,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .width(NameWidth)
                .padding(end = 4.dp),
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            val inset = BarInset.toPx()
            val width = (size.width - 2 * inset).coerceAtLeast(1f)
            if (average != null) {
                val span = scale.span(average, width, MinBarLength.toPx())
                val length = span.endInclusive - span.start
                if (length > 0f) {
                    // Rund nur am Ende des Wertes, gerade an der Null.
                    val radius = CornerRadius(minOf(BarRadius.toPx(), length, BarThickness.toPx() / 2f))
                    val top = (size.height - BarThickness.toPx()) / 2f
                    val gain = average > 0
                    val bar = RoundRect(
                        left = inset + span.start,
                        top = top,
                        right = inset + span.endInclusive,
                        bottom = top + BarThickness.toPx(),
                        topLeftCornerRadius = if (gain) CornerRadius.Zero else radius,
                        topRightCornerRadius = if (gain) radius else CornerRadius.Zero,
                        bottomRightCornerRadius = if (gain) radius else CornerRadius.Zero,
                        bottomLeftCornerRadius = if (gain) CornerRadius.Zero else radius,
                    )
                    drawPath(Path().apply { addRoundRect(bar) }, color = barColor)
                }
            }
            // Die Nulllinie zuletzt, damit sie über alle Zeilen einer Spielart durchläuft.
            val zero = inset + scale.zero(width)
            drawLine(zeroColor, Offset(zero, 0f), Offset(zero, size.height), strokeWidth = 1.dp.toPx())
        }
        Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.width(AmountWidth)) {
            if (average == null) {
                Text(stringResource(R.string.stats_none), style = MaterialTheme.typography.bodyMedium, color = muted)
            } else {
                AmountText(average, asMoney)
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(RateWidth)) {
            if (record == null) {
                Text(stringResource(R.string.stats_none), style = MaterialTheme.typography.bodyMedium, color = muted)
            } else {
                Text(formatPercent(record.winRate), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    stringResource(R.string.stats_modes_cell, record.won, record.games),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 1,
                )
            }
        }
    }
}
