package io.github.q0ochen.schafkopf.app.ui.newevening

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import io.github.q0ochen.schafkopf.app.ui.common.EveningDateDialog
import io.github.q0ochen.schafkopf.app.ui.common.SectionHeading
import io.github.q0ochen.schafkopf.app.ui.common.SubScreenTopBar
import io.github.q0ochen.schafkopf.app.ui.common.formatDate
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.navigation.Screen
import io.github.q0ochen.schafkopf.app.ui.place.PlaceSuggestionList
import org.koin.androidx.compose.koinViewModel

/**
 * Neuer Abend: vier Spieler wählen, Sitzordnung und ersten Geber festlegen, Datum (auch in der Zukunft,
 * docs/ARCHITEKTUR.md, 4.2) und Ort (mit Vorschlägen, 4.3), starten.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewEveningScreen(navigator: Navigator) {
    val viewModel: NewEveningViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var newName by rememberSaveable { mutableStateOf("") }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    val names = state.players.associate { it.id to it.name }

    Scaffold(
        topBar = { SubScreenTopBar(title = stringResource(R.string.new_title), onBack = { navigator.back() }) },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(
                    onClick = { viewModel.start { id -> navigator.replaceTop(Screen.Board(id)) } },
                    enabled = state.canStart,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) {
                    Text(
                        text = when {
                            state.missing > 0 -> stringResource(R.string.new_need_four, state.missing)
                            state.planned -> stringResource(R.string.new_create)
                            else -> stringResource(R.string.new_start)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (state.canUseLast) {
                OutlinedButton(
                    onClick = { viewModel.useLastSeating() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.new_like_last_time)) }
            }

            SectionHeading(stringResource(R.string.new_players_heading))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (player in state.players) {
                    FilterChip(
                        selected = player.id in state.seating,
                        onClick = { viewModel.toggle(player.id) },
                        label = { Text(player.name, style = MaterialTheme.typography.titleMedium) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        viewModel.clearNameError()
                    },
                    label = { Text(stringResource(R.string.new_add_player)) },
                    singleLine = true,
                    isError = state.nameError,
                    supportingText = if (state.nameError) {
                        { Text(stringResource(R.string.new_player_name_error)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            viewModel.addPlayer(newName)
                            newName = ""
                        },
                    ),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        viewModel.addPlayer(newName)
                        newName = ""
                    },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.new_player_add_confirm)) }
            }

            SectionHeading(stringResource(R.string.new_seating_heading))
            if (state.seating.isEmpty()) {
                Text(stringResource(R.string.new_seating_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    stringResource(R.string.new_seating_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.seating.forEachIndexed { index, playerId ->
                val name = names[playerId].orEmpty()
                val isDealer = playerId == state.firstDealer
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(selected = isDealer, role = Role.RadioButton, onClick = { viewModel.setFirstDealer(playerId) }),
                ) {
                    RadioButton(selected = isDealer, onClick = null)
                    Column(modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)) {
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
                    IconButton(onClick = { viewModel.move(playerId, -1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.new_move_up, name))
                    }
                    IconButton(onClick = { viewModel.move(playerId, 1) }, enabled = index < state.seating.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.new_move_down, name))
                    }
                }
            }

            SectionHeading(stringResource(R.string.edit_date_heading))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = state.date?.let { formatDate(it) }.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.edit_date_change))
                }
            }
            if (state.planned) {
                Text(
                    text = stringResource(R.string.new_date_planned_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            OutlinedTextField(
                value = state.location,
                onValueChange = viewModel::setLocation,
                label = { Text(stringResource(R.string.new_location)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
            PlaceSuggestionList(state = state.places, onPick = viewModel::pickPlace)

            Text(
                text = stringResource(R.string.new_rules_label, state.rulesName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
        }
    }

    val date = state.date
    if (pickDate && date != null) {
        EveningDateDialog(
            selected = date,
            onDismiss = { pickDate = false },
            onPicked = {
                pickDate = false
                viewModel.setDate(it)
            },
        )
    }
}
