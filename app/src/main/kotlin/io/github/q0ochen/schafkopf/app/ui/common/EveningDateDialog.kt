package io.github.q0ochen.schafkopf.app.ui.common

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.q0ochen.schafkopf.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Datumsauswahl für einen Abend („Neuer Abend“ und „Abend bearbeiten“, docs/ARCHITEKTUR.md, 4.1 und 4.2) mit
 * „Übernehmen“ und „Abbrechen“. Jeder Tag lässt sich wählen, auch einer in der Zukunft. Die Auswahl von Material
 * rechnet in UTC-Mitternacht; nach außen geht nur der Tag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EveningDateDialog(selected: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = selected.utcMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { pickerState.selectedDateMillis?.let { onPicked(localDateOf(it)) } ?: onDismiss() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.edit_date_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun localDateOf(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
