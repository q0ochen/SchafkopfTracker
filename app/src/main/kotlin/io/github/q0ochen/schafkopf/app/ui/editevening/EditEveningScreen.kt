package io.github.q0ochen.schafkopf.app.ui.editevening

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.common.SectionHeading
import io.github.q0ochen.schafkopf.app.ui.common.SubScreenTopBar
import io.github.q0ochen.schafkopf.app.ui.common.formatDate
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * „Abend bearbeiten“ (docs/ARCHITEKTUR.md, Abschnitt 4.1): Datum, Ort, Sitzordnung und erster Geber. Gespeichert wird
 * erst mit „Speichern“; zurück ohne Speichern verwirft die Änderungen.
 */
@Composable
fun EditEveningScreen(eveningId: String, navigator: Navigator) {
    val viewModel: EditEveningViewModel = koinViewModel { parametersOf(eveningId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickDate by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { SubScreenTopBar(title = stringResource(R.string.edit_title), onBack = { navigator.back() }) },
        bottomBar = {
            if (!state.loading && !state.missing) {
                Surface(shadowElevation = 8.dp) {
                    Button(
                        onClick = { viewModel.save { navigator.back() } },
                        enabled = state.canSave,
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                    ) { Text(stringResource(R.string.common_save), style = MaterialTheme.typography.titleMedium) }
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> CenteredText(stringResource(R.string.common_loading), Modifier.padding(padding))
            state.missing -> CenteredText(stringResource(R.string.board_missing), Modifier.padding(padding))
            else -> EditEveningForm(
                state = state,
                onPickDate = { pickDate = true },
                onLocation = viewModel::setLocation,
                onMove = viewModel::move,
                onFirstDealer = viewModel::setFirstDealer,
                modifier = Modifier.padding(padding),
            )
        }
    }

    val date = state.date
    val today = state.today
    if (pickDate && date != null && today != null) {
        DateDialog(
            selected = date,
            latest = today,
            onDismiss = { pickDate = false },
            onPicked = {
                pickDate = false
                viewModel.setDate(it)
            },
        )
    }
}

@Composable
private fun CenteredText(text: String, modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EditEveningForm(
    state: EditEveningUiState,
    onPickDate: () -> Unit,
    onLocation: (String) -> Unit,
    onMove: (PlayerId, Int) -> Unit,
    onFirstDealer: (PlayerId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SectionHeading(stringResource(R.string.edit_date_heading))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = state.startedAt?.let { formatDate(it) }.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onPickDate, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.edit_date_change))
            }
        }

        OutlinedTextField(
            value = state.location,
            onValueChange = onLocation,
            label = { Text(stringResource(R.string.new_location)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        SectionHeading(stringResource(R.string.new_seating_heading))
        Text(
            stringResource(R.string.new_seating_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.seating.forEachIndexed { index, playerId ->
            val name = state.name(playerId)
            val isDealer = playerId == state.firstDealer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = isDealer, role = Role.RadioButton, onClick = { onFirstDealer(playerId) }),
            ) {
                RadioButton(selected = isDealer, onClick = null)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    Text(
                        text = "${index + 1}. $name",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isDealer) {
                        Text(
                            stringResource(R.string.new_first_dealer_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = { onMove(playerId, -1) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.new_move_up, name))
                }
                IconButton(onClick = { onMove(playerId, 1) }, enabled = index < state.seating.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.new_move_down, name))
                }
            }
        }

        Text(
            text = stringResource(R.string.edit_hint_unchanged),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (state.linked) {
            Text(
                text = stringResource(R.string.edit_hint_round),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
        }
    }
}

/** Datumsauswahl; Tage nach [latest] lassen sich nicht wählen. Die Auswahl rechnet in UTC-Mitternacht. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(selected: LocalDate, latest: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    val latestMillis = latest.utcMillis()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selected.utcMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= latestMillis

            override fun isSelectableYear(year: Int): Boolean = year <= latest.year
        },
    )
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
