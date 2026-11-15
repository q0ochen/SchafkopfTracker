package io.github.q0ochen.schafkopf.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.theme.LocalExtraColors
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.Suit

/** Kopfzeile eines Unterbildschirms mit Zurück-Pfeil. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreenTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        },
        actions = { actions() },
    )
}

/** Überschrift eines Abschnitts; Screenreader erkennen sie als Überschrift. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/**
 * Große Auswahlkachel. Der gewählte Zustand ist an Farbe, Rahmen UND Zusatztext erkennbar.
 *
 * @param multiSelect `true`: Kachel lässt sich unabhängig an- und abwählen (z. B. „geklopft“).
 */
@Composable
fun ChoiceTile(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    multiSelect: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 56.dp,
    accent: Color? = null,
    icon: Painter? = null,
) {
    val colors = MaterialTheme.colorScheme
    val state = stringResource(if (selected) R.string.form_selected else R.string.form_not_selected)
    val interaction = if (multiSelect) {
        Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.primaryContainer else colors.surface,
        contentColor = when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            selected -> colors.onPrimaryContainer
            else -> colors.onSurface
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) colors.primary else colors.outline,
        ),
        modifier = modifier
            .heightIn(min = minHeight)
            .semantics { stateDescription = supportingText ?: state }
            .then(interaction),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Das Symbol erscheint nur im gewählten Zustand, zusätzlich zu Farbe und Rahmen.
                if (icon != null && selected) {
                    Icon(painter = icon, contentDescription = null, modifier = Modifier.size(22.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = accent?.takeIf { enabled && !selected } ?: Color.Unspecified,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (supportingText != null) {
                Text(text = supportingText, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Gleich breite Auswahlkacheln, von denen genau eine gewählt ist.
 *
 * @param maxPerRow Gibt es mehr Kacheln, wird nach so vielen umbrochen; alle Kacheln bleiben gleich breit.
 */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 48.dp,
    maxPerRow: Int = options.size,
) {
    val perRow = maxPerRow.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        for (rowOptions in options.chunked(perRow)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (option in rowOptions) {
                    ChoiceTile(
                        text = label(option),
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        minHeight = minHeight,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Eine kürzere letzte Zeile wird aufgefüllt, damit ihre Kacheln nicht breiter werden.
                if (options.size > perRow) {
                    repeat(perRow - rowOptions.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Betrag mit Vorzeichen; Gewinn und Verlust unterscheiden sich in Farbe und Vorzeichen. */
@Composable
fun AmountText(
    cents: Int,
    asMoney: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    val extra = LocalExtraColors.current
    Text(
        text = Money.format(cents, asMoney = asMoney, signed = true),
        color = when {
            cents > 0 -> extra.gain
            cents < 0 -> extra.loss
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = style,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        modifier = modifier,
    )
}

@StringRes
fun GameType.labelRes(): Int = when (this) {
    GameType.RUFSPIEL -> R.string.type_rufspiel
    GameType.UEBER_KREUZ -> R.string.type_ueber_kreuz
    GameType.FARBSOLO -> R.string.type_farbsolo
    GameType.WENZ -> R.string.type_wenz
    GameType.GEIER -> R.string.type_geier
}

@StringRes
fun Suit.labelRes(): Int = when (this) {
    Suit.EICHEL -> R.string.suit_eichel
    Suit.GRAS -> R.string.suit_gras
    Suit.HERZ -> R.string.suit_herz
    Suit.SCHELLEN -> R.string.suit_schellen
}

@StringRes
fun ResultLevel.labelRes(): Int = when (this) {
    ResultLevel.NORMAL -> R.string.level_normal
    ResultLevel.SCHNEIDER -> R.string.level_schneider
    ResultLevel.SCHWARZ -> R.string.level_schwarz
}

@StringRes
fun KontraLevel.labelRes(): Int = when (this) {
    KontraLevel.NONE -> R.string.kontra_none
    KontraLevel.KONTRA -> R.string.kontra_kontra
    KontraLevel.RETOUR -> R.string.kontra_retour
}

/** Einheitlicher Innenabstand der Bildschirme. */
val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
