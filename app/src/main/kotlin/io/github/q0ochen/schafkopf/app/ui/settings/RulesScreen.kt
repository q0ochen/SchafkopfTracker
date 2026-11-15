package io.github.q0ochen.schafkopf.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.common.CentField
import io.github.q0ochen.schafkopf.app.ui.common.ChoiceRow
import io.github.q0ochen.schafkopf.app.ui.common.ChoiceTile
import io.github.q0ochen.schafkopf.app.ui.common.SectionHeading
import io.github.q0ochen.schafkopf.app.ui.common.StepperRow
import io.github.q0ochen.schafkopf.app.ui.common.SubScreenTopBar
import io.github.q0ochen.schafkopf.app.ui.common.SwitchRow
import io.github.q0ochen.schafkopf.app.ui.common.labelRes
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.PayoutRule
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Bearbeitet entweder die Vorlage für neue Abende ([eveningId] ist `null`) oder das Regelwerk eines Abends.
 * Jede Änderung wird sofort gespeichert.
 */
class RulesViewModel(
    private val eveningId: EveningId?,
    private val settings: SettingsRepository,
    evenings: EveningRepository,
    private val editor: EveningEditor,
) : ViewModel() {

    private val source: Flow<RuleSet?> = if (eveningId == null) {
        settings.settings.map { it.defaultRules }
    } else {
        evenings.evening(eveningId).map { it?.rules }
    }

    val rules: StateFlow<RuleSet?> = source.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun change(transform: (RuleSet) -> RuleSet) {
        viewModelScope.launch {
            if (eveningId == null) {
                settings.update { it.copy(defaultRules = transform(it.defaultRules)) }
            } else {
                editor.update(eveningId) { it.copy(rules = transform(it.rules)) }
            }
        }
    }
}

@Composable
fun RulesScreen(eveningId: String?, navigator: Navigator) {
    val viewModel: RulesViewModel = koinViewModel { parametersOf(eveningId.orEmpty()) }
    val rules by viewModel.rules.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            SubScreenTopBar(
                title = stringResource(if (eveningId == null) R.string.rules_title_template else R.string.rules_title_evening),
                onBack = { navigator.back() },
            )
        },
    ) { padding ->
        val current = rules
        if (current == null) {
            Text(stringResource(R.string.common_loading), modifier = Modifier.padding(padding).padding(16.dp))
        } else {
            RulesEditor(
                rules = current,
                isTemplate = eveningId == null,
                onChange = viewModel::change,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun RulesEditor(rules: RuleSet, isTemplate: Boolean, onChange: ((RuleSet) -> RuleSet) -> Unit, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        Text(
            stringResource(if (isTemplate) R.string.rules_template_hint else R.string.rules_evening_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = rules.name,
            onValueChange = { name -> onChange { it.copy(name = name.take(MAX_NAME_LENGTH)) } },
            label = { Text(stringResource(R.string.rules_name)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        SectionHeading(stringResource(R.string.rules_tariff))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onChange { it.withTariff(10, 10, 50) } },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.rules_preset_round)) }
            OutlinedButton(
                onClick = { onChange { it.withTariff(10, 20, 50) } },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.rules_preset_10_20_50)) }
        }
        CentField(
            label = stringResource(R.string.rules_unit),
            value = rules.unit,
            onValueChange = { value -> onChange { it.copy(unit = value) } },
            modifier = Modifier.fillMaxWidth(),
        )
        for (pair in GameType.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (type in pair) {
                    CentField(
                        label = stringResource(R.string.rules_price, stringResource(type.labelRes())),
                        value = rules.price(type),
                        onValueChange = { value -> onChange { it.copy(prices = it.prices + (type to value)) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        SectionHeading(stringResource(R.string.rules_types))
        // Fünf Spielarten passen nicht lesbar in eine Zeile: drei oben, zwei unten, alle gleich breit.
        for (rowTypes in GameType.entries.chunked(TYPES_PER_ROW)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (type in rowTypes) {
                    ChoiceTile(
                        text = stringResource(type.labelRes()),
                        selected = type in rules.enabledTypes,
                        multiSelect = true,
                        minHeight = 48.dp,
                        onClick = {
                            onChange { current ->
                                val changed = if (type in current.enabledTypes) current.enabledTypes - type else current.enabledTypes + type
                                // Mindestens eine Spielart muss übrig bleiben.
                                if (changed.isEmpty()) current else current.copy(enabledTypes = changed)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(TYPES_PER_ROW - rowTypes.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        SectionHeading(stringResource(R.string.rules_payout))
        ChoiceRow(
            options = PayoutRule.entries,
            selected = rules.payoutRule,
            label = {
                stringResource(
                    when (it) {
                        PayoutRule.SCHAFKOPF -> R.string.rules_payout_schafkopf
                        PayoutRule.POT_SPLIT -> R.string.rules_payout_pot_split
                    },
                )
            },
            onSelect = { rule -> onChange { it.copy(payoutRule = rule) } },
            minHeight = 56.dp,
        )
        Text(
            stringResource(
                when (rules.payoutRule) {
                    PayoutRule.SCHAFKOPF -> R.string.rules_payout_schafkopf_hint
                    PayoutRule.POT_SPLIT -> R.string.rules_payout_pot_split_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        SectionHeading(stringResource(R.string.rules_laufende))
        for (type in GameType.entries) {
            StepperRow(
                title = stringResource(type.labelRes()),
                value = rules.laufendeMin(type),
                range = 1..rules.laufendeMax(type),
                onValueChange = { value -> onChange { it.copy(laufendeMin = it.laufendeMin + (type to value)) } },
            )
        }

        SectionHeading(stringResource(R.string.rules_doubling))
        SwitchRow(stringResource(R.string.rules_klopfen), rules.klopfenEnabled, { on -> onChange { it.copy(klopfenEnabled = on) } })
        SwitchRow(
            stringResource(R.string.rules_kontra),
            rules.kontraEnabled,
            { on -> onChange { it.copy(kontraEnabled = on, retourEnabled = it.retourEnabled && on) } },
        )
        SwitchRow(
            stringResource(R.string.rules_retour),
            rules.retourEnabled,
            { on -> onChange { it.copy(retourEnabled = on) } },
            enabled = rules.kontraEnabled,
        )

        SectionHeading(stringResource(R.string.rules_sie))
        ChoiceRow(
            options = listOf(2, 4),
            selected = rules.sieFactor.takeIf { it == 2 || it == 4 },
            label = { stringResource(if (it == 2) R.string.rules_sie_tout else R.string.rules_sie_fourfold) },
            onSelect = { factor -> onChange { it.copy(sieFactor = factor) } },
            minHeight = 56.dp,
        )

        SectionHeading(stringResource(R.string.rules_bock))
        SwitchRow(
            stringResource(R.string.rules_bock_enabled),
            rules.bock.enabled,
            { on -> onChange { it.copy(bock = it.bock.copy(enabled = on)) } },
        )
        if (rules.bock.enabled) {
            StepperRow(
                title = stringResource(R.string.rules_bock_length),
                value = rules.bock.gamesPerTrigger,
                range = 1..12,
                onValueChange = { value -> onChange { it.copy(bock = it.bock.copy(gamesPerTrigger = value)) } },
            )
            Text(
                stringResource(R.string.rules_bock_stacking),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            ChoiceRow(
                options = BockStacking.entries,
                selected = rules.bock.stacking,
                label = {
                    stringResource(
                        when (it) {
                            BockStacking.APPEND -> R.string.rules_bock_append
                            BockStacking.STACK -> R.string.rules_bock_stack
                            BockStacking.IGNORE -> R.string.rules_bock_ignore
                        },
                    )
                },
                onSelect = { stacking -> onChange { it.copy(bock = it.bock.copy(stacking = stacking)) } },
            )
            SwitchRow(
                stringResource(R.string.rules_bock_thrown_in),
                rules.bock.thrownInConsumesBockGame,
                { on -> onChange { it.copy(bock = it.bock.copy(thrownInConsumesBockGame = on)) } },
            )
        }

        SectionHeading(stringResource(R.string.rules_misc))
        SwitchRow(
            stringResource(R.string.rules_dealer_advances),
            rules.dealerAdvancesAfterThrowIn,
            { on -> onChange { it.copy(dealerAdvancesAfterThrowIn = on) } },
        )
        Text(stringResource(R.string.rules_display), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        ChoiceRow(
            options = listOf(true, false),
            selected = rules.displayAsMoney,
            label = { stringResource(if (it) R.string.rules_display_money else R.string.rules_display_points) },
            onSelect = { money -> onChange { it.copy(displayAsMoney = money) } },
        )

        OutlinedButton(
            onClick = { onChange { RuleSet.standard() } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.rules_reset)) }
        OutlinedButton(
            onClick = { onChange { RuleSet.sauspiel() } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 16.dp)
                .heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.rules_reset_sauspiel)) }
    }
}

private const val TYPES_PER_ROW = 3

/** Setzt Einheit, Rufspiel-Preis und den Preis aller Einzelspiele auf einmal. */
private fun RuleSet.withTariff(unit: Int, rufspiel: Int, solo: Int): RuleSet = copy(
    unit = unit,
    prices = GameType.entries.associateWith { if (it.isSolo) solo else rufspiel },
)

private const val MAX_NAME_LENGTH = 40
