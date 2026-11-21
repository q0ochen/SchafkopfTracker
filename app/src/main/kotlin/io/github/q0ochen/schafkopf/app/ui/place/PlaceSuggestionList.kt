package io.github.q0ochen.schafkopf.app.ui.place

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.domain.ports.PlaceSuggestion

/**
 * Was unter dem Feld „Ort“ steht (docs/ARCHITEKTUR.md, Abschnitt 4.3): die Vorschläge zum Antippen mit dem Hinweis auf
 * OpenStreetMap, oder nach einer gescheiterten Suche „Ortsvorschläge gibt es nur mit Internet.“. Erscheint die Liste,
 * rollt der Bildschirm sie einmal ins Bild (sonst läge sie oft unter der Tastatur); neue Vorschläge beim Weitertippen
 * rollen nicht noch einmal, damit das Feld nicht springt.
 */
@Composable
fun PlaceSuggestionList(state: PlaceSuggestionsState, onPick: (PlaceSuggestion) -> Unit, modifier: Modifier = Modifier) {
    when {
        state.suggestions.isNotEmpty() -> {
            val requester = remember { BringIntoViewRequester() }
            LaunchedEffect(Unit) { requester.bringIntoView() }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(requester),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        state.suggestions.forEachIndexed { index, suggestion ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SuggestionRow(suggestion, onClick = { onPick(suggestion) })
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.place_attribution),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.offline -> Text(
            text = stringResource(R.string.place_offline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(top = 4.dp),
        )
    }
}

/** Eine Zeile der Liste: Name und darunter Adresse; antippen übernimmt den ganzen Vorschlag als Ort. */
@Composable
private fun SuggestionRow(suggestion: PlaceSuggestion, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(suggestion.title, style = MaterialTheme.typography.titleMedium)
            if (suggestion.details.isNotEmpty()) {
                Text(
                    suggestion.details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
