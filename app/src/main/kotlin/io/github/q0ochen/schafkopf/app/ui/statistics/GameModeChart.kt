package io.github.q0ochen.schafkopf.app.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
// Die beiden Zahlenspalten werden bei großer Schrift breiter (siehe [rememberModeColumns]).
private val NameWidth = 80.dp
private val AmountWidth = 68.dp
private val RateWidth = 68.dp

/** Mindestabstand einer Zahlenspalte zu ihrem Nachbarn links. */
private val ColumnGap = 8.dp
private val RowHeight = 44.dp
private val BarThickness = 12.dp
private val BarRadius = 4.dp
private val MinBarLength = 3.dp

/** Abstand der Skala zum Rand der Balkenfläche, damit Nulllinie und Balkenende nicht am Rand kleben. */
private val BarInset = 4.dp

/** Unter dieser Breite bleibt neben Name und Zahlen kein Platz für Balken; sie stehen dann in einer eigenen Zeile. */
private val MinBarArea = 64.dp

/** Bis zu diesem Faktor wächst die Namensspalte mit der Schrift. */
private const val MAX_NAME_SCALE = 1.5f

/**
 * Spielarten-Analyse als Balkendiagramm: je Spielart eine Gruppe, je Spieler eine Zeile mit Name, Balken,
 * Durchschnitt je Spiel und Gewinnquote. Der Balken wächst von der Null in der Mitte nach rechts (Gewinn) oder nach
 * links (Verlust). Jede Spielart hat ihren eigenen Maßstab ([BarScale]), weil ein Solo ein Vielfaches eines
 * Rufspiels bringt; verglichen wird innerhalb einer Spielart. Jede Zahl steht als Text daneben, die Farbe
 * kennzeichnet nur den Spieler (wie im Vermögensverlauf), und Screenreader lesen jede Zeile als einen Satz.
 *
 * Bei großer Schrift auf einem schmalen Handy wäre neben Name und Zahlen kein Platz mehr für den Balken; dann steht
 * er in einer eigenen Zeile darunter.
 */
@Composable
fun GameModeChart(groups: List<ModeChartGroup>, asMoney: Boolean, modifier: Modifier = Modifier) {
    val scales = remember(groups) {
        groups.map { group -> BarScale.of(group.rows.mapNotNull { it.played?.averageAmount }) }
    }
    val columns = rememberModeColumns(groups, asMoney)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val stacked = maxWidth - columns.name - columns.amount - columns.rate < MinBarArea
        Column(modifier = Modifier.fillMaxWidth()) {
            ColumnTitles(columns, stacked)
            groups.forEachIndexed { index, group ->
                GroupTitle(group)
                for (row in group.rows) ModeRow(group.title, row, scales[index], columns, asMoney, stacked)
            }
        }
    }
}

/** Breite der Namens- und der beiden Zahlenspalten, für alle Zeilen gleich, damit die Nulllinie durchläuft. */
private data class ModeColumns(val name: Dp, val amount: Dp, val rate: Dp)

/**
 * Die Zahlenspalten sind mindestens so breit wie vorgesehen, bei großer Schrift (Einstellung des Handys) aber so
 * breit wie ihr längster Text: Ein Betrag wie „+12,05 €“ darf nie abgeschnitten werden. Die Namensspalte wächst mit
 * der Schrift bis zum Anderthalbfachen. Den Platz gibt die Balkenfläche her.
 */
@Composable
private fun rememberModeColumns(groups: List<ModeChartGroup>, asMoney: Boolean): ModeColumns {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val resources = LocalContext.current.resources
    val body = MaterialTheme.typography.bodyMedium
    val small = MaterialTheme.typography.bodySmall
    val label = MaterialTheme.typography.labelMedium
    return remember(groups, asMoney, density, body, small, label, measurer) {
        fun width(text: String, style: TextStyle): Int =
            measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
        val records = groups.flatMap { group -> group.rows.mapNotNull { it.played } }
        val amount = records.mapNotNull { it.averageAmount }.map { width(Money.format(it, asMoney, signed = true), body) } +
            width(resources.getString(R.string.stats_modes_col_average), label)
        val rate = records.flatMap { record ->
            listOf(
                width(formatPercent(record.winRate), body),
                width(resources.getString(R.string.stats_modes_cell, record.won, record.games), small),
            )
        } + width(resources.getString(R.string.stats_col_won), label)
        with(density) {
            ModeColumns(
                name = NameWidth * fontScale.coerceIn(1f, MAX_NAME_SCALE),
                amount = maxOf(AmountWidth, amount.max().toDp() + ColumnGap),
                rate = maxOf(RateWidth, rate.max().toDp() + ColumnGap),
            )
        }
    }
}

/** Spaltentitel über allen Gruppen; Screenreader überspringen sie, weil jede Zeile ihre Werte benennt. */
@Composable
private fun ColumnTitles(columns: ModeColumns, stacked: Boolean) {
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { },
    ) {
        Text(
            stringResource(R.string.stats_col_player),
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (stacked) Modifier.weight(1f) else Modifier.width(columns.name),
        )
        if (!stacked) Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.stats_modes_col_average),
            style = style,
            color = color,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(columns.amount),
        )
        Text(
            stringResource(R.string.stats_col_won),
            style = style,
            color = color,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(columns.rate),
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

/**
 * Eine Zeile je Spieler: nebeneinander Name, Balken und Zahlen; bei Platzmangel ([stacked]) Name und Zahlen oben
 * und der Balken in einer eigenen Zeile darunter.
 */
@Composable
private fun ModeRow(
    type: String,
    row: ModeChartRow,
    scale: BarScale,
    columns: ModeColumns,
    asMoney: Boolean,
    stacked: Boolean,
) {
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
    val readAsOne = Modifier
        .fillMaxWidth()
        .clearAndSetSemantics { contentDescription = description }
    if (stacked) {
        Column(modifier = readAsOne.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                PlayerName(
                    row.name,
                    row.colorIndex,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                )
                AmountCell(average, asMoney, columns.amount)
                RateCell(record, columns.rate)
            }
            // Ohne eigenes Spiel bliebe die Balkenzeile leer; dann entfällt sie.
            if (average != null) {
                ModeBar(
                    average,
                    scale,
                    barColor,
                    Modifier
                        .fillMaxWidth()
                        .height(BarThickness + 8.dp),
                )
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = readAsOne
                .height(IntrinsicSize.Min)
                .heightIn(min = RowHeight),
        ) {
            PlayerName(
                row.name,
                row.colorIndex,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .width(columns.name)
                    .padding(end = 4.dp),
            )
            ModeBar(
                average,
                scale,
                barColor,
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            AmountCell(average, asMoney, columns.amount)
            RateCell(record, columns.rate)
        }
    }
}

/** Der Balken eines Spielers mit der Nulllinie in der Mitte; ohne Spiel nur die Nulllinie. */
@Composable
private fun ModeBar(average: Int?, scale: BarScale, color: Color, modifier: Modifier) {
    val zeroColor = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier) {
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
                drawPath(Path().apply { addRoundRect(bar) }, color = color)
            }
        }
        // Die Nulllinie zuletzt, damit sie über alle Zeilen einer Spielart durchläuft.
        val zero = inset + scale.zero(width)
        drawLine(zeroColor, Offset(zero, 0f), Offset(zero, size.height), strokeWidth = 1.dp.toPx())
    }
}

/** Der Durchschnitt je Spiel, rechtsbündig; ohne Spiel ein Strich. */
@Composable
private fun AmountCell(average: Int?, asMoney: Boolean, width: Dp) {
    Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.width(width)) {
        if (average == null) {
            Text(
                stringResource(R.string.stats_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AmountText(average, asMoney)
        }
    }
}

/** Gewinnquote und darunter gewonnen von gespielt, rechtsbündig; ohne Spiel ein Strich. */
@Composable
private fun RateCell(record: ModeRecord?, width: Dp) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(width)) {
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
