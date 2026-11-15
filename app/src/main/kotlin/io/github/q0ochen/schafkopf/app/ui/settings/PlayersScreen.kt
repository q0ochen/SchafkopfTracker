package io.github.q0ochen.schafkopf.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.common.SubScreenTopBar
import io.github.q0ochen.schafkopf.app.ui.common.SwitchRow
import org.koin.androidx.compose.koinViewModel
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.theme.playerColor
import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlayersViewModel(private val players: PlayerRepository, private val idGenerator: IdGenerator) : ViewModel() {
    val state: StateFlow<List<Player>> =
        players.players.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** @return `false`, wenn der Name leer oder zu lang ist. */
    fun add(nameInput: String): Boolean {
        val name = Player.cleanName(nameInput) ?: return false
        viewModelScope.launch {
            val existing = players.players.first()
            players.save(Player(PlayerId(idGenerator.newId()), name, colorIndex = existing.size))
        }
        return true
    }

    /** @return `false`, wenn der Name leer oder zu lang ist. */
    fun update(player: Player, nameInput: String, archived: Boolean): Boolean {
        val name = Player.cleanName(nameInput) ?: return false
        viewModelScope.launch { players.save(player.copy(name = name, archived = archived)) }
        return true
    }
}

/** Spieler anlegen, umbenennen und archivieren. */
@Composable
fun PlayersScreen(navigator: Navigator) {
    val viewModel: PlayersViewModel = koinViewModel()
    val players by viewModel.state.collectAsStateWithLifecycle()
    var newName by rememberSaveable { mutableStateOf("") }
    var newNameError by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Player?>(null) }

    Scaffold(
        topBar = { SubScreenTopBar(title = stringResource(R.string.players_title), onBack = { navigator.back() }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val submit = {
                    if (viewModel.add(newName)) {
                        newName = ""
                        newNameError = false
                    } else {
                        newNameError = true
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        newNameError = false
                    },
                    label = { Text(stringResource(R.string.new_add_player)) },
                    singleLine = true,
                    isError = newNameError,
                    supportingText = if (newNameError) {
                        { Text(stringResource(R.string.new_player_name_error)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = submit,
                    enabled = newName.isNotBlank(),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.new_player_add_confirm)) }
            }

            if (players.isEmpty()) {
                Text(
                    stringResource(R.string.players_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(players, key = { it.id.value }) { player ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.Button) { editing = player },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(playerColor(player.colorIndex), CircleShape),
                        )
                        Text(
                            text = player.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        )
                        if (player.archived) {
                            Text(
                                stringResource(R.string.players_archived_badge),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { player ->
        EditPlayerDialog(
            player = player,
            onDismiss = { editing = null },
            onSave = { name, archived ->
                if (viewModel.update(player, name, archived)) {
                    editing = null
                    true
                } else {
                    false
                }
            },
        )
    }
}

@Composable
private fun EditPlayerDialog(player: Player, onDismiss: () -> Unit, onSave: (String, Boolean) -> Boolean) {
    var name by remember(player.id) { mutableStateOf(player.name) }
    var archived by remember(player.id) { mutableStateOf(player.archived) }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.players_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = false
                    },
                    label = { Text(stringResource(R.string.new_player_name)) },
                    singleLine = true,
                    isError = error,
                    supportingText = if (error) {
                        { Text(stringResource(R.string.new_player_name_error)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                SwitchRow(
                    title = stringResource(R.string.players_archive_label),
                    subtitle = stringResource(R.string.players_archive_hint),
                    checked = archived,
                    onCheckedChange = { archived = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!onSave(name, archived)) error = true }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
