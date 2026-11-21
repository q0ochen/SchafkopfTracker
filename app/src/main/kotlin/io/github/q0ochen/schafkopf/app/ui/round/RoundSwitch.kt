package io.github.q0ochen.schafkopf.app.ui.round

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.round.RoundState
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundNames
import io.github.q0ochen.schafkopf.domain.round.RoundSummary

/*
 * Bausteine für das Wechseln zwischen Runden (docs/ARCHITEKTUR.md 8.9), gemeinsam für den Start-Bildschirm und den
 * Bildschirm „Gemeinsame Runden“.
 */

/**
 * Was die Auswahl „Runde“ auf dem Start-Bildschirm zeigt.
 *
 * @property current Die verbundene Runde (ausgewählt).
 * @property choices Alle Einträge in der Reihenfolge von `my_rounds`; fehlt die verbundene darin, steht sie vorn.
 * @property duplicates Die Einträge, deren Name mehrfach vorkommt (sie nennen ihre Mitglieder).
 */
data class RoundSelection(val current: RoundSummary, val choices: List<RoundSummary>, val duplicates: Set<RoundId>)

/**
 * Die Auswahl, wenn es etwas zu wählen gibt (docs/ARCHITEKTUR.md 8.9): verbunden, keine neue Anmeldung nötig, die Runden
 * des Kontos sind (einmal) gelesen, und es gibt außer der verbundenen mindestens eine weitere. Sonst `null`.
 */
fun roundSelection(state: RoundState): RoundSelection? {
    val link = state.link ?: return null
    val rounds = state.rounds ?: return null
    if (state.needsSignIn || rounds.none { it.id != link.roundId }) return null
    val choices = knownRounds(state)
    return RoundSelection(choices.first { it.id == link.roundId }, choices, RoundNames.duplicates(choices))
}

/**
 * Die Runden des Kontos, soweit bekannt ([RoundState.rounds]); die verbundene ist immer dabei (fehlt sie, vorn). Damit
 * zählt ihr Name auch dann als vergeben, wenn die Liste noch nicht gelesen ist.
 */
fun knownRounds(state: RoundState): List<RoundSummary> {
    val rounds = state.rounds.orEmpty()
    val link = state.link ?: return rounds
    if (rounds.any { it.id == link.roundId }) return rounds
    val linked = RoundSummary(
        id = link.roundId,
        name = link.roundName,
        rev = link.cursor,
        memberCount = link.members.size,
        myPlayerId = link.playerId,
        isCreator = link.isCreator,
    )
    return listOf(linked) + rounds
}

/** „4 Mitglieder“ bzw. „4 Mitglieder: Anna, Bert“, wenn die Namen bekannt sind. */
@Composable
fun membersLine(round: RoundSummary): String {
    val count = pluralStringResource(R.plurals.round_members_count, round.memberCount, round.memberCount)
    return if (round.memberNames.isEmpty()) count else stringResource(R.string.round_members_names, count, round.memberNames.joinToString(", "))
}

/**
 * Der Eintrag einer Runde in der Auswahl: ihr Name; heißen mehrere Runden des Kontos gleich ([duplicates]), dahinter
 * die Mitglieder in Klammern.
 */
@Composable
fun roundChoiceLabel(round: RoundSummary, duplicates: Set<RoundId>): String {
    if (round.id !in duplicates) return round.name
    val members = if (round.memberNames.isEmpty()) {
        pluralStringResource(R.plurals.round_members_count, round.memberCount, round.memberCount)
    } else {
        round.memberNames.joinToString(", ")
    }
    return stringResource(R.string.round_choice_with_members, round.name, members)
}

/**
 * Die Auswahl „Runde“: ein Knopf mit der verbundenen Runde, der die Runden des Kontos aufklappt. Wer eine andere wählt,
 * landet bei [onChoose]; die Auswahl selbst ändert sich erst, wenn wirklich gewechselt ist.
 */
@Composable
fun RoundChoice(
    current: RoundSummary,
    choices: List<RoundSummary>,
    duplicates: Set<RoundId>,
    enabled: Boolean,
    onChoose: (RoundSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val label = roundChoiceLabel(current, duplicates)
    val description = stringResource(R.string.round_choice_description, label)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.round_choice_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = description },
            ) {
                Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (choice in choices) {
                    val isCurrent = choice.id == current.id
                    DropdownMenuItem(
                        text = { Text(roundChoiceLabel(choice, duplicates), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            open = false
                            if (!isCurrent) onChoose(choice)
                        },
                        // Die verbundene Runde mit Häkchen, nicht nur über Farbe (für Screenreader: „ausgewählt“).
                        leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.alpha(if (isCurrent) 1f else 0f)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { selected = isCurrent },
                    )
                }
            }
        }
    }
}

/**
 * Die Rückfrage vor dem Wechseln in eine andere Runde.
 *
 * @param from Der Name der bisherigen Runde.
 * @param pending So viele Änderungen warten nach dem Abgleich noch; sie gingen beim Wechseln verloren.
 */
@Composable
fun SwitchDialog(request: SwitchRequest, from: String, pending: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val (title, first, confirm) = when (request) {
        is SwitchRequest.Use -> Triple(
            stringResource(R.string.round_switch_title, request.target),
            stringResource(R.string.round_switch_text, request.target),
            stringResource(R.string.round_switch),
        )
        is SwitchRequest.Join -> Triple(
            stringResource(R.string.round_switch_join_title, request.target),
            stringResource(R.string.round_switch_text, request.target),
            stringResource(R.string.round_join),
        )
        is SwitchRequest.Create -> Triple(
            stringResource(R.string.round_switch_create_title, request.target),
            stringResource(R.string.round_switch_create_text),
            stringResource(R.string.round_switch_create),
        )
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(first)
                Text(stringResource(R.string.round_switch_keeps, from))
                if (pending > 0) {
                    Text(pluralStringResource(R.plurals.round_switch_pending, pending, pending), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirm) } },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
