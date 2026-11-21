package io.github.q0ochen.schafkopf.app.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import io.github.q0ochen.schafkopf.app.ui.round.RoundStatusKind
import io.github.q0ochen.schafkopf.app.ui.round.roundStatus
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.common.AmountText
import io.github.q0ochen.schafkopf.app.ui.common.PlayerName
import io.github.q0ochen.schafkopf.app.ui.common.KeepScreenOn
import io.github.q0ochen.schafkopf.app.ui.common.SubScreenTopBar
import io.github.q0ochen.schafkopf.app.ui.common.formatDate
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.navigation.Screen
import io.github.q0ochen.schafkopf.domain.evening.EntryResult
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.app.ui.common.Money
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Die Liste eines Abends: oben der Stand je Spieler, darunter jeder Eintrag mit den Beträgen,
 * unten die großen Knöpfe zum Eintragen.
 */
@Composable
fun BoardScreen(eveningId: String, navigator: Navigator) {
    val viewModel: BoardViewModel = koinViewModel { parametersOf(eveningId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val computed = state.computed
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var bockDialog by rememberSaveable { mutableStateOf(false) }
    val removedMessage = stringResource(R.string.board_entry_removed)
    val restoreLabel = stringResource(R.string.common_restore)

    KeepScreenOn(enabled = state.keepScreenOn && computed?.evening?.isRunning == true)

    Scaffold(
        topBar = {
            SubScreenTopBar(
                title = computed?.let { stringResource(R.string.board_title, formatDate(it.evening.startedAt)) }.orEmpty(),
                onBack = { navigator.back() },
                actions = {
                    // Mit gemeinsamer Runde: der Stand des Abgleichs als Symbol; Tippen öffnet „Gemeinsame Runde“.
                    state.round?.let { round ->
                        val status = roundStatus(round, LocalContext.current.resources)
                        IconButton(onClick = { navigator.open(Screen.Round) }) {
                            Icon(
                                painter = painterResource(status.kind.icon),
                                contentDescription = stringResource(R.string.round_status_icon, status.text),
                                tint = if (status.kind == RoundStatusKind.PROBLEM) MaterialTheme.colorScheme.error else LocalContentColor.current,
                            )
                        }
                    }
                    if (computed != null) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.board_menu_statistics)) },
                                onClick = {
                                    menuOpen = false
                                    navigator.open(Screen.Statistics(eveningId))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.board_menu_closing)) },
                                onClick = {
                                    menuOpen = false
                                    navigator.open(Screen.Closing(eveningId))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.board_menu_edit)) },
                                onClick = {
                                    menuOpen = false
                                    navigator.open(Screen.EditEvening(eveningId))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.board_menu_rules)) },
                                onClick = {
                                    menuOpen = false
                                    navigator.open(Screen.Rules(eveningId))
                                },
                            )
                            if (computed.evening.isRunning) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_menu_finish)) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.finish()
                                        navigator.open(Screen.Closing(eveningId))
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_menu_reopen)) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.reopen()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.board_menu_delete)) },
                                onClick = {
                                    menuOpen = false
                                    confirmDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (computed != null && computed.evening.isRunning) {
                BoardActions(
                    onEnterGame = { navigator.open(Screen.GameForm(eveningId, null)) },
                    onThrownIn = { viewModel.addThrownIn() },
                    onBock = { bockDialog = true },
                    bockEnabled = computed.evening.rules.bock.enabled,
                    undoEnabled = computed.evening.entries.isNotEmpty(),
                    onUndo = {
                        scope.launch {
                            val removed = viewModel.removeLast() ?: return@launch
                            val result = snackbar.showSnackbar(
                                message = removedMessage,
                                actionLabel = restoreLabel,
                                duration = SnackbarDuration.Long,
                            )
                            if (result == SnackbarResult.ActionPerformed) viewModel.restore(removed)
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                state.loading -> CenteredText(stringResource(R.string.common_loading))
                computed == null -> CenteredText(stringResource(R.string.board_missing))
                else -> BoardContent(
                    computed = computed,
                    state = state,
                    onEntryClick = { result ->
                        if (result.entry is GameEntry) navigator.open(Screen.GameForm(eveningId, result.entry.id.value))
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.board_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.board_delete_text))
                    // Mit gemeinsamer Runde verschwindet der Abend bei allen: Das soll vorher dastehen.
                    if (state.round?.link != null) {
                        Text(stringResource(R.string.round_delete_evening), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete { navigator.back() }
                    },
                ) { Text(stringResource(R.string.board_delete_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.board_delete_no)) }
            },
        )
    }

    if (bockDialog && computed != null) {
        BockDialog(
            seating = computed.evening.seating,
            name = state::name,
            onDismiss = { bockDialog = false },
            onConfirm = { player, thrownIn ->
                bockDialog = false
                viewModel.addBockTrigger(player, thrownIn)
            },
        )
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BoardContent(computed: EveningState, state: BoardUiState, onEntryClick: (EntryResult) -> Unit) {
    val asMoney = computed.evening.rules.displayAsMoney
    val seating = computed.evening.seating
    val listState = rememberLazyListState()

    // Nach jedem neuen Eintrag ans Ende der Liste springen.
    LaunchedEffect(computed.results.size) {
        if (computed.results.isNotEmpty()) listState.animateScrollToItem(computed.results.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (player in seating) {
                        PlayerHeaderCell(
                            name = state.name(player),
                            balance = computed.balances.getValue(player),
                            rank = computed.ranks.getValue(player),
                            isDealer = computed.evening.isRunning && player == computed.nextDealer,
                            asMoney = asMoney,
                            colorIndex = state.colorIndex[player] ?: 0,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                StatusLine(computed = computed, dealerName = state.name(computed.nextDealer))
                if (state.round != null && computed.evening.isRunning) {
                    DealerHint(isMe = state.me == computed.nextDealer, dealerName = state.name(computed.nextDealer))
                }
            }
        }

        if (computed.results.isEmpty()) {
            CenteredText(stringResource(R.string.board_no_entries))
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(computed.results, key = { _, result -> result.entry.id.value }) { _, result ->
                    EntryRow(
                        result = result,
                        seating = seating,
                        names = state.names,
                        asMoney = asMoney,
                        duplicate = result.entry.id in state.duplicates,
                        onClick = { onEntryClick(result) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PlayerHeaderCell(
    name: String,
    balance: Int,
    rank: Int,
    isDealer: Boolean,
    asMoney: Boolean,
    colorIndex: Int,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(
        R.string.board_balance_description,
        name,
        Money.format(balance, asMoney, signed = true),
        rank,
    ) + if (isDealer) ", " + stringResource(R.string.common_dealer) else ""
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(horizontal = 2.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        // Der Name steht in Textfarbe, die Spielerfarbe als Punkt davor (wie im Diagramm der Statistik).
        PlayerName(
            name = name,
            colorIndex = colorIndex,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        AmountText(balance, asMoney, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = stringResource(R.string.common_rank, rank) +
                if (isDealer) " · " + stringResource(R.string.common_dealer) else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusLine(computed: EveningState, dealerName: String) {
    val parts = ArrayList<String>()
    if (computed.evening.isRunning) {
        parts += stringResource(R.string.board_status_next_game, computed.gamesPlayed + 1)
        parts += stringResource(R.string.board_status_dealer, dealerName)
        if (computed.bockGamesRemaining > 0) {
            parts += pluralStringResource(R.plurals.board_status_bock, computed.bockGamesRemaining, computed.bockGamesRemaining)
        }
    } else {
        parts += stringResource(R.string.board_finished_banner)
        parts += pluralStringResource(R.plurals.games_count, computed.gamesPlayed, computed.gamesPlayed)
    }
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    )
}

/**
 * In der gemeinsamen Runde: wer gibt und deshalb in der Regel einträgt. Nur ein Hinweis; eintragen kann jeder.
 */
@Composable
private fun DealerHint(isMe: Boolean, dealerName: String) {
    Text(
        text = if (isMe) stringResource(R.string.round_dealer_you) else stringResource(R.string.round_dealer_other, dealerName),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal,
        color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    )
}

@Composable
private fun EntryRow(
    result: EntryResult,
    seating: List<PlayerId>,
    names: Map<PlayerId, String>,
    asMoney: Boolean,
    duplicate: Boolean,
    onClick: () -> Unit,
) {
    val text = entryText(result, names, asMoney)
    val isGame = result.entry is GameEntry
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isGame) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = text.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (text.detail.isNotEmpty()) {
            Text(
                text = text.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.issues.isNotEmpty()) {
            Text(
                text = stringResource(R.string.entry_issue),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (duplicate) {
            // Dasselbe Spiel steht direkt davor schon einmal: vermutlich haben zwei gleichzeitig eingetragen.
            Text(
                text = stringResource(R.string.round_duplicate),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (result.amounts.values.any { it != 0 }) {
            Row(modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)) {
                for (player in seating) {
                    AmountText(
                        cents = result.amounts[player] ?: 0,
                        asMoney = asMoney,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Die Knöpfe am unteren Rand: groß und mit dem Daumen erreichbar. */
@Composable
private fun BoardActions(
    onEnterGame: () -> Unit,
    onThrownIn: () -> Unit,
    onBock: () -> Unit,
    bockEnabled: Boolean,
    undoEnabled: Boolean,
    onUndo: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onThrownIn, modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.board_thrown_in), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (bockEnabled) {
                    OutlinedButton(onClick = onBock, modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.board_bock), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val undoDescription = stringResource(R.string.board_undo_last_description)
                OutlinedButton(
                    onClick = onUndo,
                    enabled = undoEnabled,
                    modifier = Modifier
                        .heightIn(min = 64.dp)
                        .semantics { contentDescription = undoDescription },
                ) {
                    Text(stringResource(R.string.board_undo_last), maxLines = 1)
                }
                Button(onClick = onEnterGame, modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)) {
                    Text(stringResource(R.string.board_enter_game), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                }
            }
        }
    }
}

/** Fragt, wer die 6 Nixer hatte und ob das Blatt zusammengeworfen wurde. */
@Composable
private fun BockDialog(
    seating: List<PlayerId>,
    name: (PlayerId?) -> String,
    onDismiss: () -> Unit,
    onConfirm: (PlayerId?, Boolean) -> Unit,
) {
    var player by remember { mutableStateOf<PlayerId?>(null) }
    var thrownIn by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bock_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.bock_dialog_who), style = MaterialTheme.typography.titleSmall)
                for (candidate in seating + listOf<PlayerId?>(null)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = player == candidate, role = Role.RadioButton, onClick = { player = candidate }),
                    ) {
                        RadioButton(selected = player == candidate, onClick = null)
                        Text(
                            text = if (candidate == null) stringResource(R.string.bock_dialog_unknown) else name(candidate),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = thrownIn, role = Role.Checkbox, onValueChange = { thrownIn = it }),
                ) {
                    Checkbox(checked = thrownIn, onCheckedChange = null)
                    Text(
                        stringResource(R.string.bock_dialog_thrown_in),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(player, thrownIn) }) { Text(stringResource(R.string.bock_dialog_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
