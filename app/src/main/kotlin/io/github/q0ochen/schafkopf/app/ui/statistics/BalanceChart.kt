package io.github.q0ochen.schafkopf.app.ui.statistics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.q0ochen.schafkopf.app.ui.common.Money
import io.github.q0ochen.schafkopf.app.ui.theme.playerColor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Eine Linie im Diagramm.
 *
 * @property values Ein Wert je Punkt in Cent; Punkt 0 ist der Start.
 */
data class ChartSeries(val id: String, val name: String, val colorIndex: Int, val values: List<Int>)

private val ChartHeight = 260.dp
private val MarginTop = 14.dp
private val MarginBottom = 30.dp
private val LabelGap = 16.dp
private const val MAX_LABEL_CHARS = 10

/**
 * Liniendiagramm „Vermögensverlauf“: der Kontostand je Spieler nach jedem Spiel, wie in der Web-App gestaltet
 * (`web/src/ui/charts/BalanceChart.tsx`): dünne Linien (2 dp), zurückhaltende Gitterlinien, eine einzige Achse, die
 * Null deutlich, Namen in Textfarbe direkt am Linienende, Legende darunter. Antippen oder Wischen zeigt ein
 * Fadenkreuz mit den Ständen aller Spieler nach diesem Spiel; dieselben Werte gibt es als Tabelle. Die Farben folgen
 * dem Spieler, nie seinem Platz.
 *
 * @param boundaries Punkte, an denen ein neuer Abend beginnt (feine gestrichelte senkrechte Linien).
 * @param pointLabel Beschriftung eines Punktes, zum Beispiel „nach Spiel 7“.
 * @param description Was ein Screenreader statt der Grafik vorliest.
 * @param emptyText Steht da, solange es noch keinen Verlauf gibt.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BalanceChart(
    series: List<ChartSeries>,
    asMoney: Boolean,
    boundaries: List<Int>,
    pointLabel: (Int) -> String,
    description: String,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    val points = series.maxOfOrNull { it.values.size } ?: 0
    if (points < 2) {
        Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val last = max(1, points - 1)
    var active by remember(series) { mutableStateOf<Int?>(null) }

    val colors = MaterialTheme.colorScheme
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = colors.onSurface)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val seriesColors = series.map { playerColor(it.colorIndex) }

    val allValues = series.flatMap { it.values }
    val ticks = remember(allValues) { ChartMath.niceTicks(minOf(0, allValues.min()), maxOf(0, allValues.max())) }
    val tickLabels = remember(ticks, asMoney) { ticks.map { Money.format(it, asMoney) } }
    val endLabels = remember(series) {
        series.map { if (it.name.length > MAX_LABEL_CHARS) it.name.take(MAX_LABEL_CHARS - 1) + "…" else it.name }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartHeight)
                .semantics { contentDescription = description },
        ) {
            val width = constraints.maxWidth.toFloat()
            val height = with(density) { ChartHeight.toPx() }
            val left = with(density) {
                val widest = tickLabels.maxOf { measurer.measure(it, tickStyle).size.width }
                max(40.dp.toPx(), widest + 8.dp.toPx())
            }
            val right = with(density) {
                val widest = endLabels.maxOf { measurer.measure(it, labelStyle).size.width }
                (widest + 14.dp.toPx()).coerceIn(40.dp.toPx(), 104.dp.toPx())
            }
            val top = with(density) { MarginTop.toPx() }
            val bottom = height - with(density) { MarginBottom.toPx() }
            val plotWidth = (width - left - right).coerceAtLeast(1f)
            val yMin = ticks.first().toFloat()
            val yMax = ticks.last().toFloat()
            fun x(index: Int): Float = left + index.toFloat() / last * plotWidth
            fun y(value: Int): Float = top + (1f - (value - yMin) / (yMax - yMin).coerceAtLeast(1f)) * (bottom - top)
            val labelYs = ChartMath.spreadLabels(
                positions = series.map { y(it.values.lastOrNull() ?: 0) },
                gap = with(density) { LabelGap.toPx() },
                min = top + with(density) { 6.dp.toPx() },
                max = bottom,
            )
            fun pick(position: Float) = ChartMath.nearestIndex(position, left, plotWidth, last)

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(series, width) {
                        // Antippen zeigt die Stände nach diesem Spiel; noch einmal auf denselben Punkt blendet sie aus.
                        detectTapGestures { offset ->
                            val index = pick(offset.x)
                            active = if (active == index) null else index
                        }
                    }
                    .pointerInput(series, width) {
                        // Waagerechtes Wischen fährt durch die Spiele; senkrecht scrollt die Seite wie gewohnt.
                        detectHorizontalDragGestures(onDragStart = { active = pick(it.x) }) { change, _ ->
                            active = pick(change.position.x)
                        }
                    },
            ) {
                val hairline = 1.dp.toPx()
                // Gitterlinien mit Beschriftung; die Null kräftiger.
                ticks.forEachIndexed { index, tick ->
                    val lineY = y(tick)
                    drawLine(
                        color = if (tick == 0) colors.outline else colors.outlineVariant,
                        start = Offset(left, lineY),
                        end = Offset(width - right, lineY),
                        strokeWidth = if (tick == 0) 1.5.dp.toPx() else hairline,
                    )
                    val text = measurer.measure(tickLabels[index], tickStyle)
                    drawText(text, topLeft = Offset(left - 8.dp.toPx() - text.size.width, lineY - text.size.height / 2f))
                }
                // Wo ein neuer Abend beginnt.
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                for (boundary in boundaries.filter { it in 1 until last }) {
                    drawLine(
                        color = colors.outline.copy(alpha = 0.6f),
                        start = Offset(x(boundary), top),
                        end = Offset(x(boundary), bottom),
                        strokeWidth = hairline,
                        pathEffect = dash,
                    )
                }
                val start = measurer.measure(pointLabel(0), tickStyle)
                drawText(start, topLeft = Offset(left, height - start.size.height - 4.dp.toPx()))
                val end = measurer.measure(pointLabel(last), tickStyle)
                drawText(end, topLeft = Offset(width - right - end.size.width, height - end.size.height - 4.dp.toPx()))

                // Die Linien, dann Endpunkte mit einem Ring in Hintergrundfarbe und die Namen.
                series.forEachIndexed { index, line ->
                    val path = Path()
                    line.values.forEachIndexed { point, value ->
                        if (point == 0) path.moveTo(x(point), y(value)) else path.lineTo(x(point), y(value))
                    }
                    drawPath(
                        path,
                        color = seriesColors[index],
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                series.forEachIndexed { index, line ->
                    val lastPoint = line.values.lastIndex
                    val center = Offset(x(lastPoint), y(line.values[lastPoint]))
                    drawCircle(colors.background, radius = 6.dp.toPx(), center = center)
                    drawCircle(seriesColors[index], radius = 4.dp.toPx(), center = center)
                    val text = measurer.measure(
                        endLabels[index],
                        labelStyle,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                    drawText(text, topLeft = Offset(center.x + 8.dp.toPx(), labelYs[index] - text.size.height / 2f))
                }

                // Fadenkreuz am gewählten Punkt.
                active?.let { point ->
                    drawLine(
                        color = colors.onSurface.copy(alpha = 0.6f),
                        start = Offset(x(point), top),
                        end = Offset(x(point), bottom),
                        strokeWidth = hairline,
                    )
                    series.forEachIndexed { index, line ->
                        val center = Offset(x(point), y(line.values[minOf(point, line.values.lastIndex)]))
                        drawCircle(colors.background, radius = 7.dp.toPx(), center = center)
                        drawCircle(seriesColors[index], radius = 5.dp.toPx(), center = center)
                    }
                }
            }

            active?.let { point ->
                val crossX = x(point)
                val onLeft = crossX > width * 0.55f
                val gap = with(density) { 8.dp.toPx() }
                ChartTooltip(
                    title = pointLabel(point),
                    rows = series.indices
                        .map { index -> index to series[index].values[minOf(point, series[index].values.lastIndex)] }
                        .sortedByDescending { it.second },
                    series = series,
                    seriesColors = seriesColors,
                    asMoney = asMoney,
                    modifier = Modifier
                        .align(if (onLeft) Alignment.TopEnd else Alignment.TopStart)
                        .offset {
                            IntOffset(
                                x = if (onLeft) -(width - crossX + gap).roundToInt() else (crossX + gap).roundToInt(),
                                y = top.roundToInt(),
                            )
                        },
                )
            }
        }

        // Legende: Linie und Name, Name in Textfarbe.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .padding(top = 8.dp)
                .clearAndSetSemantics { },
        ) {
            series.forEachIndexed { index, line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LineKey(seriesColors[index], Modifier.width(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(line.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Kurzer Strich in der Farbe einer Linie: kennzeichnet sie in Legende und Fadenkreuz. */
@Composable
private fun LineKey(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.height(12.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Die Stände aller Spieler nach einem Spiel; der Betrag steht vorn und kräftig, der Name dahinter. */
@Composable
private fun ChartTooltip(
    title: String,
    rows: List<Pair<Int, Int>>,
    series: List<ChartSeries>,
    seriesColors: List<Color>,
    asMoney: Boolean,
    modifier: Modifier = Modifier,
) {
    val valueStyle: TextStyle = MaterialTheme.typography.bodyMedium
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = modifier
            .widthIn(max = 220.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((index, value) in rows) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    LineKey(seriesColors[index], Modifier.size(width = 12.dp, height = 12.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = Money.format(value, asMoney, signed = true),
                        style = valueStyle,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = series[index].name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
