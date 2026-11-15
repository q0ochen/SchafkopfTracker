package io.github.q0ochen.schafkopf.app.ui.common

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d. MMM yyyy", Locale.GERMANY)

/** Datum eines Abends, zum Beispiel „Sa., 14. Nov. 2026“. */
fun formatDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = DATE_FORMAT.withZone(zone).format(instant)

/** Lässt den Bildschirm an, solange diese Composable angezeigt wird (ohne Berechtigung und ohne Wake-Lock). */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view: View = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
