package io.github.q0ochen.schafkopf.app.ui.closing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.ai.CommentaryConsentDialog
import io.github.q0ochen.schafkopf.app.ui.ai.CommentarySection
import io.github.q0ochen.schafkopf.app.ui.ai.CommentaryUi
import io.github.q0ochen.schafkopf.app.ui.common.AmountText
import io.github.q0ochen.schafkopf.app.ui.common.Money
import io.github.q0ochen.schafkopf.app.ui.common.PlayerName
import io.github.q0ochen.schafkopf.app.ui.common.SectionHeading
import io.github.q0ochen.schafkopf.app.ui.common.ShareButtons
import io.github.q0ochen.schafkopf.app.ui.common.SubScreenTopBar
import io.github.q0ochen.schafkopf.app.ui.common.captureInto
import io.github.q0ochen.schafkopf.app.ui.common.formatDate
import io.github.q0ochen.schafkopf.app.ui.common.rememberCapture
import io.github.q0ochen.schafkopf.app.ui.common.rememberShareActions
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.navigation.Screen
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** Abschluss eines Abends: Endstand, wer wem wie viel zahlt, Teilen als Text oder Bild und der Weg zur Statistik. */
@Composable
fun ClosingScreen(eveningId: String, navigator: Navigator) {
    val viewModel: ClosingViewModel = koinViewModel { parametersOf(eveningId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val capture = rememberCapture()
    val resources = LocalContext.current.resources

    Scaffold(
        topBar = { SubScreenTopBar(title = stringResource(R.string.closing_title), onBack = { navigator.back() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val computed = state.computed
        val closing = state.closing
        if (computed == null || closing == null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier
                .padding(padding)
                .fillMaxSize()) {
                Text(
                    stringResource(if (state.loading) R.string.common_loading else R.string.board_missing),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            ClosingCard(
                computed = computed,
                closing = closing,
                names = state.names,
                colorIndex = state.colorIndex,
                modifier = Modifier
                    .captureInto(capture, MaterialTheme.colorScheme.background)
                    .padding(16.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                ShareButtons(
                    rememberShareActions(
                        subject = stringResource(R.string.share_closing_subject),
                        text = { closingShareText(resources, computed, closing, state.names) },
                        capture = capture,
                        imageName = "schafkopf-abschluss.png",
                        onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                    ),
                )
                OutlinedButton(
                    onClick = { navigator.open(Screen.Statistics(eveningId)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.closing_statistics), style = MaterialTheme.typography.titleMedium) }
                CommentarySection(
                    commentary = state.commentary,
                    onWrite = viewModel::writeCommentary,
                    onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                    shareSubject = stringResource(R.string.commentary_share_subject),
                )
            }
        }
    }

    (state.commentary as? CommentaryUi.NeedsConsent)?.let { consent ->
        CommentaryConsentDialog(consent, allEvenings = false, onConsent = viewModel::consent, onDecline = viewModel::declineConsent)
    }
}

/** Was auch im geteilten Bild steht: Datum, Endstand und „Wer zahlt wem“. */
@Composable
private fun ClosingCard(
    computed: EveningState,
    closing: Closing,
    names: Map<PlayerId, String>,
    colorIndex: Map<PlayerId, Int>,
    modifier: Modifier = Modifier,
) {
    val evening = computed.evening
    val asMoney = evening.rules.displayAsMoney
    Column(modifier = modifier.fillMaxWidth()) {
        val date = formatDate(evening.startedAt)
        Text(
            text = if (evening.location.isBlank()) date else stringResource(R.string.evening_date_location, date, evening.location.trim()),
            style = MaterialTheme.typography.titleLarge,
        )
        val games = pluralStringResource(R.plurals.games_count, computed.gamesPlayed, computed.gamesPlayed)
        Text(
            text = if (evening.isRunning) "$games · ${stringResource(R.string.closing_running)}" else games,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeading(stringResource(R.string.closing_standings))
        for (standing in closing.standings) {
            val amount = Money.format(standing.balance, asMoney, signed = true)
            val description = stringResource(R.string.closing_standing_description, standing.rank, standing.name, amount)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clearAndSetSemantics { contentDescription = description },
            ) {
                Text(
                    text = stringResource(R.string.common_rank, standing.rank),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(32.dp),
                )
                PlayerName(
                    name = standing.name,
                    colorIndex = colorIndex[standing.player] ?: 0,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                AmountText(
                    cents = standing.balance,
                    asMoney = asMoney,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        SectionHeading(stringResource(R.string.closing_transfers))
        if (closing.transfers.isEmpty()) {
            Text(
                stringResource(R.string.closing_nobody_pays),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (transfer in closing.transfers) {
            val from = names[transfer.from] ?: "?"
            val to = names[transfer.to] ?: "?"
            val amount = Money.format(transfer.amount, asMoney)
            val description = stringResource(R.string.closing_transfer_description, from, to, amount)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clearAndSetSemantics { contentDescription = description },
            ) {
                Text(
                    text = stringResource(R.string.closing_transfer, from, to),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(text = amount, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        if (!closing.balanced) {
            Text(
                text = stringResource(R.string.closing_unbalanced),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
