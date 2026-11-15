package io.github.q0ochen.schafkopf.app.ui.gameform

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.common.ChoiceRow
import io.github.q0ochen.schafkopf.app.ui.common.ChoiceTile
import io.github.q0ochen.schafkopf.app.ui.common.SectionHeading
import io.github.q0ochen.schafkopf.app.ui.common.SubScreenTopBar
import io.github.q0ochen.schafkopf.app.ui.common.labelRes
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.theme.LocalExtraColors
import io.github.q0ochen.schafkopf.domain.evening.BockOverride
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.app.ui.common.Money
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.Suit
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Ein Spiel eintragen oder ändern. Alles steht auf einem Bildschirm; ein neues Spiel wird mit
 * „Gewonnen“ oder „Verloren“ sofort gespeichert.
 */
@Composable
fun GameFormScreen(eveningId: String, entryId: String?, navigator: Navigator) {
    val viewModel: GameFormViewModel = koinViewModel { parametersOf(eveningId, entryId.orEmpty()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val evening = state.evening

    Scaffold(
        topBar = {
            SubScreenTopBar(
                title = if (state.editing) {
                    stringResource(R.string.form_title_edit)
                } else {
                    stringResource(R.string.form_title_new, state.gameNumber)
                },
                onBack = { navigator.back() },
                actions = {
                    if (state.editing) {
                        IconButton(onClick = { viewModel.delete { navigator.back() } }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.form_delete))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (evening != null) {
                SaveBar(
                    state = state,
                    asMoney = evening.rules.displayAsMoney,
                    onResult = { won ->
                        if (state.editing) viewModel.selectResult(won) else viewModel.save(won) { navigator.back() }
                    },
                    onSaveChanges = { viewModel.save(null) { navigator.back() } },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (evening == null) {
                Text(
                    text = stringResource(if (state.loading) R.string.common_loading else R.string.board_missing),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                FormContent(state = state, evening = evening, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun FormContent(state: GameFormUiState, evening: Evening, viewModel: GameFormViewModel) {
    val draft = state.draft
    val rules = evening.rules
    val asMoney = rules.displayAsMoney
    val opponents = evening.seating.filter { it != draft.declarer && it != draft.partner }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        SectionHeading(stringResource(R.string.form_type))
        ChoiceRow(
            options = GameType.entries.filter { it in rules.enabledTypes || it == draft.type },
            selected = draft.type,
            label = { stringResource(it.labelRes()) },
            onSelect = viewModel::selectType,
            minHeight = 56.dp,
            // Fünf Spielarten passen nicht lesbar in eine Zeile: drei oben, zwei unten.
            maxPerRow = 3,
        )

        SectionHeading(stringResource(R.string.form_players))
        PlayerGrid(
            seating = evening.seating,
            name = state::name,
            selected = { it == draft.declarer || it == draft.partner },
            supporting = {
                when {
                    // Über Kreuz kennt keinen Ansager: Beide Gewählten bilden einfach ein Paar.
                    !draft.type.hasDeclarer && (it == draft.declarer || it == draft.partner) ->
                        stringResource(R.string.form_role_pair)

                    it == draft.declarer -> stringResource(R.string.form_role_declarer)
                    it == draft.partner -> stringResource(R.string.form_role_partner)
                    else -> null
                }
            },
            onTap = viewModel::tapPlayer,
        )
        if (!draft.type.isSolo && draft.partner == null) {
            Text(
                stringResource(
                    if (draft.type.hasDeclarer) R.string.form_players_hint_rufspiel else R.string.form_players_hint_ueber_kreuz,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (rules.klopfenEnabled || draft.klopfer.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_matchbox),
                    contentDescription = stringResource(R.string.form_klopfer_icon),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                SectionHeading(stringResource(R.string.form_klopfer))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (player in evening.seating) {
                    ChoiceTile(
                        text = state.name(player),
                        selected = player in draft.klopfer,
                        onClick = { viewModel.toggleKlopfer(player) },
                        multiSelect = true,
                        minHeight = 56.dp,
                        icon = painterResource(R.drawable.ic_matchbox),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (rules.kontraEnabled || draft.kontra != KontraLevel.NONE) {
            SectionHeading(stringResource(R.string.form_kontra))
            ChoiceRow(
                options = KontraLevel.entries.filter { it != KontraLevel.RETOUR || rules.retourEnabled || draft.kontra == it },
                selected = draft.kontra,
                label = { stringResource(it.labelRes()) },
                onSelect = viewModel::selectKontra,
            )
            if (draft.kontra != KontraLevel.NONE && draft.declarer != null) {
                Text(
                    stringResource(R.string.form_kontra_by),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                ChoiceRow(
                    options = opponents,
                    selected = draft.kontraBy,
                    label = { state.name(it) },
                    onSelect = viewModel::selectKontraBy,
                )
            }
        }

        SectionHeading(stringResource(R.string.form_laufende))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            val counts = listOf(0) + (rules.laufendeMin(draft.type)..rules.laufendeMax(draft.type)).toList()
            for (count in counts) {
                ChoiceTile(
                    text = if (count == 0) stringResource(R.string.form_laufende_none) else count.toString(),
                    selected = draft.laufende == count,
                    onClick = { viewModel.selectLaufende(count) },
                    minHeight = 48.dp,
                    modifier = Modifier.widthIn(min = 56.dp),
                )
            }
        }

        if (draft.type.isSolo) {
            SectionHeading(stringResource(R.string.form_announcement))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (rules.allowsTout(draft.type)) {
                    ChoiceTile(
                        text = stringResource(R.string.announcement_tout),
                        selected = draft.announcement == Announcement.TOUT,
                        onClick = { viewModel.selectAnnouncement(Announcement.TOUT) },
                        multiSelect = true,
                        minHeight = 48.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rules.allowsSie(draft.type)) {
                    ChoiceTile(
                        text = stringResource(R.string.announcement_sie),
                        selected = draft.announcement == Announcement.SIE,
                        onClick = { viewModel.selectAnnouncement(Announcement.SIE) },
                        multiSelect = true,
                        minHeight = 48.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (draft.announcement == Announcement.NONE) {
            SectionHeading(stringResource(R.string.form_level))
            ChoiceRow(
                options = ResultLevel.entries,
                selected = draft.level,
                label = { stringResource(it.labelRes()) },
                onSelect = viewModel::selectLevel,
            )
        }

        if (rules.bock.enabled || state.bockExponent > 0) {
            ChoiceTile(
                text = stringResource(R.string.form_bock),
                selected = state.bockExponent > 0,
                supportingText = if (state.autoBockExponent > 0 && draft.bockOverride == BockOverride.AUTO) {
                    stringResource(R.string.form_bock_hint_counter)
                } else {
                    null
                },
                onClick = { viewModel.setBock(state.bockExponent == 0) },
                multiSelect = true,
                minHeight = 56.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
        }

        val suits = if (draft.type == GameType.RUFSPIEL) listOf(Suit.EICHEL, Suit.GRAS, Suit.SCHELLEN) else Suit.entries
        if (draft.type == GameType.RUFSPIEL || draft.type == GameType.FARBSOLO) {
            SectionHeading(
                stringResource(if (draft.type == GameType.RUFSPIEL) R.string.form_suit_rufsau else R.string.form_suit_trump),
            )
            ChoiceRow(
                options = suits,
                selected = draft.suit,
                label = { stringResource(it.labelRes()) },
                onSelect = viewModel::selectSuit,
            )
        }

        ValuePreview(state = state, asMoney = asMoney)
    }
}

/** Vier Spieler-Kacheln in zwei Reihen. */
@Composable
private fun PlayerGrid(
    seating: List<PlayerId>,
    name: (PlayerId?) -> String,
    selected: (PlayerId) -> Boolean,
    supporting: @Composable (PlayerId) -> String?,
    onTap: (PlayerId) -> Unit,
    multiSelect: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = 60.dp,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (pair in seating.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (player in pair) {
                    ChoiceTile(
                        text = name(player),
                        selected = selected(player),
                        supportingText = supporting(player),
                        onClick = { onTap(player) },
                        multiSelect = multiSelect,
                        minHeight = minHeight,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Zeigt, wie sich der Spielwert zusammensetzt. Screenreader lesen Änderungen vor. */
@Composable
private fun ValuePreview(state: GameFormUiState, asMoney: Boolean) {
    val breakdown = state.breakdown ?: return
    val formula = if (breakdown.totalFactor > 1) {
        stringResource(
            R.string.form_value_formula,
            Money.format(breakdown.sum, asMoney),
            breakdown.totalFactor,
            Money.format(breakdown.value, asMoney),
        )
    } else {
        Money.format(breakdown.value, asMoney)
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.form_value), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(formula, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Unterer Rand: „Verloren“ und „Gewonnen“; beim Ändern zusätzlich „Änderung speichern“. */
@Composable
private fun SaveBar(
    state: GameFormUiState,
    asMoney: Boolean,
    onResult: (Boolean) -> Unit,
    onSaveChanges: () -> Unit,
) {
    val extra = LocalExtraColors.current
    val draft = state.draft
    val hint = when (state.missing) {
        MissingInput.DECLARER ->
            stringResource(if (draft.type.hasDeclarer) R.string.form_missing_declarer else R.string.form_missing_pair)

        MissingInput.PARTNER -> stringResource(R.string.form_missing_partner)
        null -> null
    }
    Surface(shadowElevation = 8.dp) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ResultButton(
                    label = stringResource(R.string.form_lost),
                    amount = Money.format(-state.declarerAmount, asMoney, signed = true),
                    color = extra.loss,
                    enabled = state.canSave && draft.announcement != Announcement.SIE,
                    highlighted = !state.editing || !draft.declarerWon,
                    onClick = { onResult(false) },
                    modifier = Modifier.weight(1f),
                )
                ResultButton(
                    label = stringResource(R.string.form_won),
                    amount = Money.format(state.declarerAmount, asMoney, signed = true),
                    color = extra.gain,
                    enabled = state.canSave,
                    highlighted = !state.editing || draft.declarerWon,
                    onClick = { onResult(true) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.editing) {
                Button(
                    onClick = onSaveChanges,
                    enabled = state.canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.form_save_changes), style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
private fun ResultButton(
    label: String,
    amount: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = if (highlighted) {
        ButtonDefaults.buttonColors(containerColor = color, contentColor = MaterialTheme.colorScheme.surface)
    } else {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onClick, enabled = enabled, colors = colors, modifier = modifier.heightIn(min = 64.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(amount, style = MaterialTheme.typography.labelLarge)
        }
    }
}
