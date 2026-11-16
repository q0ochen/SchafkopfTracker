package io.github.q0ochen.schafkopf.app.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.common.AmountText
import io.github.q0ochen.schafkopf.app.ui.common.Money
import io.github.q0ochen.schafkopf.app.ui.common.PlayerName
import io.github.q0ochen.schafkopf.app.ui.common.SectionHeading
import io.github.q0ochen.schafkopf.app.ui.common.ShareButtons
import io.github.q0ochen.schafkopf.app.ui.common.SubScreenTopBar
import io.github.q0ochen.schafkopf.app.ui.common.captureInto
import io.github.q0ochen.schafkopf.app.ui.common.formatDate
import io.github.q0ochen.schafkopf.app.ui.common.formatPercent
import io.github.q0ochen.schafkopf.app.ui.common.labelRes
import io.github.q0ochen.schafkopf.app.ui.common.rememberCapture
import io.github.q0ochen.schafkopf.app.ui.common.rememberShareActions
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.stats.Statistics
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Statistik eines Abends oder aller Abende („ewige Tabelle“): Kontostand, Vermögensverlauf, Spielarten,
 * „Sollte-er-es-lassen?“-Index, Auszeichnungen und weitere Zahlen. Oben lässt sich der Abend wählen.
 *
 * @param eveningId Der Abend, mit dem die Statistik öffnet; `null` für alle Abende.
 */
@Composable
fun StatisticsScreen(eveningId: String?, navigator: Navigator) {
    val viewModel: StatisticsViewModel = koinViewModel { parametersOf(eveningId.orEmpty()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val capture = rememberCapture()
    val resources = LocalContext.current.resources
    val stats = state.stats?.takeIf { it.gameCount > 0 }
    val share = rememberShareActions(
        subject = stringResource(R.string.share_stats_subject),
        text = {
            stats?.let {
                statisticsShareText(resources, it, state.selectedChoice?.startedAt, state::name, state.asMoney)
            }.orEmpty()
        },
        capture = capture,
        imageName = "schafkopf-statistik.png",
        onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
    )
    var shareMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            SubScreenTopBar(
                title = stringResource(R.string.stats_title),
                onBack = { navigator.back() },
                actions = {
                    if (stats != null) {
                        Box {
                            IconButton(onClick = { shareMenu = true }) {
                                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.stats_share))
                            }
                            DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_as_text)) },
                                    onClick = {
                                        shareMenu = false
                                        share.shareText()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_as_image)) },
                                    enabled = !share.busy,
                                    onClick = {
                                        shareMenu = false
                                        share.shareImage()
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            EveningFilter(
                state = state,
                onSelect = viewModel::select,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                state.loading -> Message(stringResource(R.string.common_loading))
                state.stats == null -> Message(stringResource(R.string.board_missing))
                stats == null -> Message(stringResource(R.string.stats_empty))
                else -> {
                    // Was auch im geteilten Bild steht: Überschrift, Kontostand und Vermögensverlauf.
                    Column(
                        modifier = Modifier
                            .captureInto(capture, MaterialTheme.colorScheme.background)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Overview(state, stats)
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Details(state, stats)
                        ShareButtons(share, modifier = Modifier.padding(top = 24.dp, bottom = 24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** „Auswertung für“: alle Abende oder einer davon. */
@Composable
private fun EveningFilter(state: StatisticsUiState, onSelect: (EveningId?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val all = stringResource(R.string.stats_filter_all)
    val current = state.selectedChoice?.let { choiceLabel(it) } ?: if (state.selected == null) all else "?"
    val description = stringResource(R.string.stats_filter_description, current)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.stats_filter_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = description },
            ) {
                Text(
                    text = current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(all) },
                    onClick = {
                        open = false
                        onSelect(null)
                    },
                )
                for (choice in state.choices) {
                    DropdownMenuItem(
                        text = { Text(choiceLabel(choice), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            open = false
                            onSelect(choice.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun choiceLabel(choice: EveningChoice): String {
    val date = formatDate(choice.startedAt)
    return if (choice.location.isEmpty()) date else stringResource(R.string.evening_date_location, date, choice.location)
}

/** Überschrift, Kontostand und Vermögensverlauf: der Teil, der auch als Bild geteilt wird. */
@Composable
private fun Overview(state: StatisticsUiState, stats: Statistics) {
    val resources = LocalContext.current.resources
    val single = state.selected != null
    val asMoney = state.asMoney
    Text(
        text = state.selectedChoice?.let { choiceLabel(it) } ?: stringResource(R.string.stats_filter_all),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        text = statisticsSummary(resources, stats, single, asMoney),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SectionHeading(stringResource(R.string.stats_balance))
    val historyStats = stats.history.playerStats.associateBy { it.player }
    val standings = stats.finance.standings.sortedBy { it.rank }
    StatTable(
        columns = listOf(
            // Zusammen 320 dp: passt auch auf schmale Handys (360 dp) und damit ganz ins geteilte Bild.
            TableColumn(stringResource(R.string.stats_col_player), 104.dp, numeric = false),
            TableColumn(stringResource(R.string.stats_col_balance), 88.dp),
            TableColumn(stringResource(R.string.stats_col_games), 52.dp),
            TableColumn(stringResource(R.string.stats_col_won), 76.dp),
        ),
        rows = standings.map { standing ->
            val stat = historyStats[standing.player]
            val games = stat?.games ?: 0
            val won = formatPercent(if (stat != null && stat.games > 0) stat.gamesWon.toDouble() / stat.games else null)
            TableRow(
                description = stringResource(
                    R.string.stats_balance_row,
                    standing.rank,
                    state.name(standing.player),
                    Money.format(standing.balance, asMoney, signed = true),
                    games,
                    won,
                ),
                cells = listOf<@Composable () -> Unit>(
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.common_rank, standing.rank),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            PlayerName(
                                state.name(standing.player),
                                state.colorIndex[standing.player] ?: 0,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    },
                    { AmountText(standing.balance, asMoney, fontWeight = FontWeight.Bold) },
                    { TextCell(games.toString()) },
                    { TextCell(won) },
                ),
            )
        },
    )

    SectionHeading(stringResource(R.string.stats_history))
    val series = stats.players.map { player ->
        ChartSeries(
            id = player.value,
            name = state.name(player),
            colorIndex = state.colorIndex[player] ?: 0,
            values = stats.finance.balanceSeries[player] ?: listOf(0),
        )
    }
    val start = stringResource(R.string.stats_chart_start)
    val finalValues = series
        .sortedByDescending { it.values.lastOrNull() ?: 0 }
        .joinToString(", ") { "${it.name} ${Money.format(it.values.lastOrNull() ?: 0, asMoney, signed = true)}" }
    BalanceChart(
        series = series,
        asMoney = asMoney,
        boundaries = if (single) emptyList() else stats.finance.eveningStarts,
        pointLabel = { index -> if (index == 0) start else resources.getString(R.string.stats_chart_after_game, index) },
        description = stringResource(R.string.stats_chart_description, finalValues),
        emptyText = stringResource(R.string.stats_chart_empty),
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        stringResource(R.string.stats_history_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Alles unterhalb des Diagramms. */
@Composable
private fun Details(state: StatisticsUiState, stats: Statistics) {
    val resources = LocalContext.current.resources
    val single = state.selected != null
    val asMoney = state.asMoney
    val history = stats.history

    HistoryTable(state, stats)

    // Spielarten: nur die, die überhaupt gespielt wurden.
    val typesPlayed = GameType.entries.filter { (history.gameTypeCounts[it] ?: 0) > 0 }
    if (typesPlayed.isNotEmpty()) {
        SectionHeading(stringResource(R.string.stats_modes))
        StatTable(
            columns = listOf(TableColumn(stringResource(R.string.stats_col_player), 96.dp, numeric = false)) +
                typesPlayed.map { TableColumn(stringResource(it.labelRes()), 76.dp) },
            rows = stats.gameModes.map { modes ->
                val name = state.name(modes.player)
                val parts = typesPlayed.map { type ->
                    val record = modes.asPlayer[type]
                    val label = resources.getString(type.labelRes())
                    if (record == null || record.games == 0) {
                        resources.getString(R.string.stats_modes_item_none, label)
                    } else {
                        resources.getString(R.string.stats_modes_item, label, record.won, record.games)
                    }
                }
                TableRow(
                    description = resources.getString(R.string.share_stats_line, name, parts.joinToString(", ")),
                    cells = listOf<@Composable () -> Unit>({ TextCell(name) }) + typesPlayed.map<GameType, @Composable () -> Unit> { type ->
                        val record = modes.asPlayer[type]
                        val text = if (record == null || record.games == 0) {
                            stringResource(R.string.stats_none)
                        } else {
                            stringResource(R.string.stats_modes_cell, record.won, record.games)
                        }
                        { TextCell(text) }
                    },
                )
            },
        )
        Hint(stringResource(R.string.stats_modes_hint))
    }

    SectionHeading(stringResource(R.string.stats_gelegt))
    for (record in stats.gelegt) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .semantics(mergeDescendants = true) { },
        ) {
            PlayerName(
                state.name(record.player),
                state.colorIndex[record.player] ?: 0,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(record.badge.labelRes()),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            if (record.count > 0) {
                Hint(
                    stringResource(
                        R.string.stats_gelegt_detail,
                        record.count,
                        record.won,
                        Money.format(record.netBalance, asMoney, signed = true),
                        Money.format(record.effect, asMoney, signed = true),
                    ),
                )
            }
        }
    }

    SectionHeading(stringResource(R.string.stats_awards))
    for ((title, value) in awardLines(resources, stats, state::name, asMoney, short = false)) {
        Fact(title, value)
    }

    SectionHeading(stringResource(R.string.stats_more))
    val expensive = history.mostExpensiveGame
    Fact(
        stringResource(R.string.stats_most_expensive),
        when {
            expensive == null -> stringResource(R.string.stats_none)
            single -> stringResource(R.string.stats_most_expensive_value, expensive.gameNumber, Money.format(expensive.value, asMoney))
            else -> stringResource(
                R.string.stats_most_expensive_value_date,
                expensive.gameNumber,
                state.startOf(expensive.eveningId)?.let { formatDate(it) } ?: "?",
                Money.format(expensive.value, asMoney),
            )
        },
    )
    Fact(stringResource(R.string.stats_lead_changes), history.leadChanges.toString())
    Fact(stringResource(R.string.stats_bock_counts), "${history.bockGamesCount} / ${history.bockTriggerCount}")
    Fact(stringResource(R.string.stats_thrown_in), history.thrownInCount.toString())

    SectionHeading(stringResource(R.string.stats_records))
    val none = stringResource(R.string.stats_none)
    StatTable(
        columns = listOf(
            TableColumn(stringResource(R.string.stats_col_player), 96.dp, numeric = false),
            TableColumn(stringResource(R.string.stats_col_biggest_win), 92.dp),
            TableColumn(stringResource(R.string.stats_col_biggest_loss), 92.dp),
            TableColumn(stringResource(R.string.stats_col_streak), 84.dp),
            TableColumn(stringResource(R.string.stats_col_schneider), 92.dp),
        ),
        rows = history.playerStats.map { stat ->
            val win = stat.biggestWin?.let { Money.format(it.amount, asMoney, signed = true) } ?: none
            val loss = stat.biggestLoss?.let { Money.format(it.amount, asMoney, signed = true) } ?: none
            TableRow(
                description = stringResource(
                    R.string.stats_records_row,
                    state.name(stat.player),
                    win,
                    loss,
                    stat.longestWinStreak,
                    stat.schneiderWon,
                    stat.schneiderLost,
                ),
                cells = listOf<@Composable () -> Unit>(
                    { TextCell(state.name(stat.player)) },
                    { stat.biggestWin?.let { AmountText(it.amount, asMoney) } ?: TextCell(none) },
                    { stat.biggestLoss?.let { AmountText(it.amount, asMoney) } ?: TextCell(none) },
                    { TextCell(stat.longestWinStreak.toString()) },
                    { TextCell("${stat.schneiderWon}/${stat.schneiderLost}") },
                ),
            )
        },
    )
}

/** „Verlauf als Tabelle“: bei einem Abend jedes Spiel, bei vielen Abenden der Stand nach jedem Abend. */
@Composable
private fun HistoryTable(state: StatisticsUiState, stats: Statistics) {
    var shown by rememberSaveable { mutableStateOf(false) }
    val single = state.selected != null
    val asMoney = state.asMoney
    val series = stats.players.map { it to (stats.finance.balanceSeries[it] ?: listOf(0)) }
    val lastPoint = series.maxOfOrNull { it.second.size - 1 } ?: 0
    if (lastPoint <= 0) return
    TextButton(
        onClick = { shown = !shown },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .padding(top = 4.dp),
    ) {
        Text(stringResource(if (shown) R.string.stats_history_table_hide else R.string.stats_history_table_show))
    }
    if (!shown) return
    val resources = LocalContext.current.resources
    val rows: List<Pair<String, Int>> = if (single) {
        (1..lastPoint).map { resources.getString(R.string.stats_row_game, it) to it }
    } else {
        stats.finance.eveningIds.mapIndexed { k, id ->
            val end = stats.finance.eveningStarts.getOrNull(k + 1) ?: lastPoint
            (state.startOf(id)?.let { formatDate(it) } ?: "?") to end
        }
    }
    StatTable(
        columns = listOf(
            TableColumn(
                stringResource(if (single) R.string.stats_col_after else R.string.stats_col_evening),
                if (single) 76.dp else 128.dp,
                numeric = false,
            ),
        ) + series.map { (player, _) -> TableColumn(state.name(player), 88.dp) },
        rows = rows.map { (label, index) ->
            val values = series.map { (_, points) -> points[minOf(index, points.lastIndex)] }
            TableRow(
                description = resources.getString(
                    R.string.stats_history_row,
                    label,
                    series.indices.joinToString(", ") { i ->
                        "${state.name(series[i].first)} ${Money.format(values[i], asMoney, signed = true)}"
                    },
                ),
                cells = listOf<@Composable () -> Unit>({ TextCell(label) }) +
                    values.map<Int, @Composable () -> Unit> { value -> { AmountText(value, asMoney) } },
            )
        },
    )
}

@Composable
private fun Fact(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = "$label: $value" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}
