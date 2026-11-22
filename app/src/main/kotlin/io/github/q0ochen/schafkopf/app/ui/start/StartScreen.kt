package io.github.q0ochen.schafkopf.app.ui.start

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.round.RoundState
import io.github.q0ochen.schafkopf.app.ui.common.AmountText
import io.github.q0ochen.schafkopf.app.ui.round.MessageCard
import io.github.q0ochen.schafkopf.app.ui.round.RoundChoice
import io.github.q0ochen.schafkopf.app.ui.round.StatusRow
import io.github.q0ochen.schafkopf.app.ui.round.SwitchDialog
import io.github.q0ochen.schafkopf.app.ui.round.SwitchProgress
import io.github.q0ochen.schafkopf.app.round.RoundMessage
import io.github.q0ochen.schafkopf.app.ui.round.errorText
import io.github.q0ochen.schafkopf.app.ui.round.messageText
import io.github.q0ochen.schafkopf.app.ui.round.roundSelection
import io.github.q0ochen.schafkopf.domain.round.RoundSummary
import io.github.q0ochen.schafkopf.app.ui.common.SectionHeading
import org.koin.androidx.compose.koinViewModel
import io.github.q0ochen.schafkopf.app.ui.common.formatDate
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.navigation.Screen

/** Startseite: laufender Abend, neuer Abend, frühere Abende und die Wege zu Statistik, Spielern und Einstellungen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartScreen(navigator: Navigator) {
    val viewModel: StartViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val round by viewModel.round.collectAsStateWithLifecycle()
    val roundSwitch by viewModel.roundSwitch.collectAsStateWithLifecycle()

    // Die Runden des Kontos für die Auswahl: jedes Mal, wenn die Startseite erscheint, und sobald die Anmeldung gelesen
    // ist (beim Start der App kommt sie oft erst nach dem ersten Bild; docs/ARCHITEKTUR.md 8.9).
    LaunchedEffect(viewModel, round.account?.userId) { viewModel.refreshRounds() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            state.running?.let { running ->
                item(key = "running") {
                    Card(
                        onClick = { navigator.open(Screen.Board(running.id)) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.start_running_title), style = MaterialTheme.typography.titleLarge)
                            EveningSummary(running, round.link?.roundName)
                            Button(
                                onClick = { navigator.open(Screen.Board(running.id)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp),
                            ) { Text(stringResource(R.string.start_continue), style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                }
            }

            item(key = "new") {
                Button(
                    onClick = { navigator.open(Screen.NewEvening) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.start_new_evening), style = MaterialTheme.typography.titleMedium) }
            }

            item(key = "links") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LinkButton(R.string.start_statistics, Modifier.weight(1f)) { navigator.open(Screen.Statistics(null)) }
                    LinkButton(R.string.start_players, Modifier.weight(1f)) { navigator.open(Screen.Players) }
                    LinkButton(R.string.start_settings, Modifier.weight(1f)) { navigator.open(Screen.Settings) }
                }
            }

            item(key = "round") {
                RoundRow(
                    round = round,
                    switch = roundSwitch,
                    onOpen = { navigator.open(Screen.Round) },
                    onChoose = viewModel::choose,
                    onClearMessage = viewModel::clearMessage,
                    onClearError = viewModel::clearError,
                )
            }

            item(key = "heading") { SectionHeading(stringResource(R.string.start_past_evenings)) }

            if (!state.loading && state.evenings.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(R.string.start_no_evenings), style = MaterialTheme.typography.bodyLarge)
                }
            }

            items(state.evenings, key = { it.id }) { evening ->
                Card(onClick = { navigator.open(Screen.Board(evening.id)) }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) { EveningSummary(evening, round.link?.roundName) }
                }
            }
        }
    }

    roundSwitch.switching?.let { request ->
        SwitchDialog(
            request = request,
            from = round.link?.roundName.orEmpty(),
            pending = roundSwitch.from?.acceptedLoss ?: 0,
            autoBackup = roundSwitch.autoBackup,
            onConfirm = viewModel::confirmSwitch,
            onCancel = viewModel::cancelSwitch,
        )
    }
    if (roundSwitch.busy) SwitchProgress()
}

@Composable
private fun LinkButton(labelRes: Int, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(stringResource(labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * „Gemeinsame Runden“: der Weg zum Bildschirm (Überschrift und Statuszeile) und, wenn es etwas zu wählen gibt, die
 * Auswahl der Runde (docs/ARCHITEKTUR.md 8.9). Wer eine andere Runde wählt, bekommt erst die Rückfrage.
 */
@Composable
private fun RoundRow(
    round: RoundState,
    switch: StartRoundUi,
    onOpen: () -> Unit,
    onChoose: (RoundSummary) -> Unit,
    onClearMessage: () -> Unit,
    onClearError: () -> Unit,
) {
    val link = round.link
    val selection = roundSelection(round)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.round_title), style = MaterialTheme.typography.titleMedium)
            if (link == null) {
                Text(
                    stringResource(R.string.round_entry_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Steht die Auswahl da, zeigt sie den Namen; sonst steht er hier.
                if (selection == null) {
                    Text(
                        stringResource(R.string.round_entry_linked, link.roundName),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusRow(round)
            }
        }
        // Von den Meldungen der Runde gehört hierher nur die nach einem gescheiterten Wechsel: Die Runde gibt es nicht mehr.
        val message = round.message?.takeIf { it == RoundMessage.DETACHED }
        val error = switch.error
        if (selection != null || message != null || error != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            ) {
                selection?.let {
                    RoundChoice(
                        current = it.current,
                        choices = it.choices,
                        duplicates = it.duplicates,
                        enabled = !switch.busy,
                        onChoose = onChoose,
                    )
                }
                message?.let { MessageCard(text = messageText(it), onDismiss = onClearMessage) }
                error?.let { MessageCard(text = errorText(it), onDismiss = onClearError, isError = true) }
            }
        }
    }
}

/**
 * Datum, Zahl der Spiele und die Rangliste eines Abends.
 *
 * @param roundName Die verbundene Runde. Ein Gerät hängt an höchstens einer Runde, und dann gehören alle Abende darauf
 *   zu ihr (docs/ARCHITEKTUR.md 8.7 und 8.9); `null` ohne Runde, dann gibt es keine Marke.
 */
@Composable
private fun EveningSummary(evening: EveningCard, roundName: String?) {
    val date = formatDate(evening.startedAt)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (evening.location.isBlank()) date else stringResource(R.string.evening_date_location, date, evening.location),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (evening.running) {
                Text(
                    text = stringResource(if (evening.planned) R.string.start_evening_planned_badge else R.string.start_evening_running_badge),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        roundName?.let { RoundLabel(it) }
        Text(
            text = pluralStringResource(R.plurals.games_count, evening.games, evening.games),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (standing in evening.standings) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.common_rank, standing.rank) + " " + standing.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AmountText(standing.balance, evening.asMoney, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * Die Marke „Runde: ‹Name›“ in der Karte eines Abends: umrandet in der Farbe des Textes, damit sie neben „geplant“ und
 * „läuft“ nicht auffällt und in der hervorgehobenen Karte „Abend läuft“ ebenso gut zu lesen ist.
 */
@Composable
private fun RoundLabel(roundName: String) {
    Text(
        text = stringResource(R.string.start_evening_round, roundName),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(vertical = 2.dp)
            .border(1.dp, LocalContentColor.current.copy(alpha = 0.7f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}
