package io.github.q0ochen.schafkopf.app.ui.common

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d. MMM yyyy", Locale.GERMANY)

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d. MMM yyyy, HH:mm", Locale.GERMANY)
private const val NO_BREAK_SPACE = "\u00A0"

/** Datum eines Abends, zum Beispiel „Sa., 14. Nov. 2026“. */
fun formatDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = DATE_FORMAT.withZone(zone).format(instant)

/** Datum und Uhrzeit, zum Beispiel „14. Nov. 2026, 21:05“. */
fun formatDateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DATE_TIME_FORMAT.withZone(zone).format(instant)

/** Anteil als ganze Prozent, zum Beispiel „67 %“ (wie in der Web-App); ohne einen einzigen Fall ein Gedankenstrich. */
fun formatPercent(rate: Double?): String =
    if (rate == null || rate.isNaN() || rate.isInfinite()) "–" else "${(rate * 100).roundToLong()}$NO_BREAK_SPACE%"

/** Lässt den Bildschirm an, solange diese Composable angezeigt wird (ohne Berechtigung und ohne Wake-Lock). */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view: View = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
