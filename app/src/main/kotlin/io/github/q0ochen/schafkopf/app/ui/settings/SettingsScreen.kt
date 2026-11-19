package io.github.q0ochen.schafkopf.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ui.common.ChoiceRow
import io.github.q0ochen.schafkopf.app.ui.common.LinkRow
import io.github.q0ochen.schafkopf.app.ui.common.SectionHeading
import io.github.q0ochen.schafkopf.app.ui.common.SubScreenTopBar
import io.github.q0ochen.schafkopf.app.ui.common.SwitchRow
import org.koin.androidx.compose.koinViewModel
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.navigation.Screen
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {
    val state: StateFlow<AppSettings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun change(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.update(transform) }
    }
}

@Composable
fun SettingsScreen(navigator: Navigator) {
    val viewModel: SettingsViewModel = koinViewModel()
    val settings by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { SubScreenTopBar(title = stringResource(R.string.settings_title), onBack = { navigator.back() }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SwitchRow(
                title = stringResource(R.string.settings_keep_screen_on),
                subtitle = stringResource(R.string.settings_keep_screen_on_hint),
                checked = settings.keepScreenOn,
                onCheckedChange = { on -> viewModel.change { it.copy(keepScreenOn = on) } },
            )

            SectionHeading(stringResource(R.string.settings_theme))
            ChoiceRow(
                options = ThemeMode.entries,
                selected = settings.themeMode,
                label = {
                    stringResource(
                        when (it) {
                            ThemeMode.SYSTEM -> R.string.settings_theme_system
                            ThemeMode.LIGHT -> R.string.settings_theme_light
                            ThemeMode.DARK -> R.string.settings_theme_dark
                        },
                    )
                },
                onSelect = { mode -> viewModel.change { it.copy(themeMode = mode) } },
            )

            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            LinkRow(
                title = stringResource(R.string.round_title),
                subtitle = stringResource(R.string.round_entry_hint),
                onClick = { navigator.open(Screen.Round) },
            )
            HorizontalDivider()
            LinkRow(stringResource(R.string.settings_rules), onClick = { navigator.open(Screen.Rules(null)) })
            HorizontalDivider()
            LinkRow(
                title = stringResource(R.string.settings_ai),
                subtitle = stringResource(R.string.settings_ai_hint),
                onClick = { navigator.open(Screen.AiSettings) },
            )
            HorizontalDivider()
            LinkRow(
                title = stringResource(R.string.settings_backup),
                subtitle = stringResource(R.string.settings_backup_hint),
                onClick = { navigator.open(Screen.Backup) },
            )
            HorizontalDivider()

            // Die Version steht hier, damit man sie in Fehlermeldungen angeben kann.
            val context = LocalContext.current
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            }
            Text(
                text = stringResource(R.string.settings_version, version),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
            )
        }
    }
}
