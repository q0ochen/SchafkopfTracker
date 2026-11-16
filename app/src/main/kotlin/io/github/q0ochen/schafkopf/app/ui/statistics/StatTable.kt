package io.github.q0ochen.schafkopf.app.ui.statistics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Eine Spalte der Tabelle.
 *
 * @property width Breite; die erste Spalte wird breiter, wenn Platz übrig ist.
 * @property numeric Zahlen stehen rechtsbündig, damit Stellen untereinander stehen.
 */
data class TableColumn(val title: String, val width: Dp, val numeric: Boolean = true)

/**
 * Eine Zeile der Tabelle.
 *
 * @property description Was ein Screenreader für die ganze Zeile vorliest, mit den Spaltennamen.
 * @property cells Eine Zelle je Spalte.
 */
class TableRow(val description: String, val cells: List<@Composable () -> Unit>)

/**
 * Schlichte Tabelle für die Statistik. Ist sie breiter als der Bildschirm, lässt sie sich seitlich schieben.
 * Screenreader lesen jede Zeile als einen Satz vor; die Kopfzeile überspringen sie, weil jede Zeile ihre
 * Spaltennamen schon nennt. Bei großer Schrift (Einstellung des Handys) wachsen die Spalten mit, damit kein Betrag
 * abgeschnitten wird; die Tabelle lässt sich dann seitlich schieben.
 */
@Composable
fun StatTable(columns: List<TableColumn>, rows: List<TableRow>, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier = modifier) {
        val scaled = columns.map { it.width * fontScale }
        val rest = scaled.drop(1).fold(0.dp) { sum, width -> sum + width }
        val firstWidth = maxOf(scaled.first(), maxWidth - rest)
        val widths = listOf(firstWidth) + scaled.drop(1)
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clearAndSetSemantics { },
            ) {
                columns.forEachIndexed { index, column ->
                    Text(
                        text = column.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(widths[index])
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.width(widths.fold(0.dp) { sum, it -> sum + it }))
            for (row in rows) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clearAndSetSemantics { contentDescription = row.description },
                ) {
                    row.cells.forEachIndexed { index, cell ->
                        Box(
                            contentAlignment = if (columns[index].numeric) Alignment.CenterEnd else Alignment.CenterStart,
                            modifier = Modifier
                                .width(widths[index])
                                .padding(horizontal = 4.dp),
                        ) { cell() }
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.width(widths.fold(0.dp) { sum, it -> sum + it }),
                )
            }
        }
    }
}

/** Zelle mit einfachem Text. */
@Composable
fun TextCell(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
