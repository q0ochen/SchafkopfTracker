package io.github.q0ochen.schafkopf.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.q0ochen.schafkopf.app.ui.ai.AiSettingsScreen
import io.github.q0ochen.schafkopf.app.ui.backup.BackupScreen
import io.github.q0ochen.schafkopf.app.ui.board.BoardScreen
import io.github.q0ochen.schafkopf.app.ui.closing.ClosingScreen
import io.github.q0ochen.schafkopf.app.ui.gameform.GameFormScreen
import io.github.q0ochen.schafkopf.app.ui.newevening.NewEveningScreen
import io.github.q0ochen.schafkopf.app.ui.settings.PlayersScreen
import io.github.q0ochen.schafkopf.app.ui.settings.RulesScreen
import io.github.q0ochen.schafkopf.app.ui.settings.SettingsScreen
import io.github.q0ochen.schafkopf.app.ui.start.StartScreen
import io.github.q0ochen.schafkopf.app.ui.statistics.StatisticsScreen

/**
 * Hält je Bildschirm auf dem Stapel einen eigenen Behälter für ViewModels. Er überlebt Drehungen des Geräts
 * und wird geleert, sobald der Bildschirm vom Stapel verschwindet.
 */
class NavStoresViewModel : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun storeFor(entryId: String): ViewModelStore = stores.getOrPut(entryId) { ViewModelStore() }

    fun retainOnly(entryIds: Set<String>) {
        val gone = stores.keys.filterNot { it in entryIds }
        for (id in gone) stores.remove(id)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/** Zeigt den obersten Bildschirm des Stapels und kümmert sich um „Zurück“. */
@Composable
fun AppNavHost() {
    val navigator = rememberSaveable(saver = Navigator.Saver) { Navigator() }
    val stores: NavStoresViewModel = viewModel()
    val stateHolder = rememberSaveableStateHolder()
    val entry = navigator.current
    val entryIds = navigator.entries.map { it.id }

    LaunchedEffect(entryIds) { stores.retainOnly(entryIds.toSet()) }
    BackHandler(enabled = navigator.canGoBack) { navigator.back() }

    val owner = remember(entry.id) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = stores.storeFor(entry.id)
        }
    }

    stateHolder.SaveableStateProvider(entry.id) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            when (val screen = entry.screen) {
                Screen.Start -> StartScreen(navigator)
                Screen.NewEvening -> NewEveningScreen(navigator)
                is Screen.Board -> BoardScreen(screen.eveningId, navigator)
                is Screen.GameForm -> GameFormScreen(screen.eveningId, screen.entryId, navigator)
                is Screen.Closing -> ClosingScreen(screen.eveningId, navigator)
                is Screen.Statistics -> StatisticsScreen(screen.eveningId, navigator)
                Screen.Players -> PlayersScreen(navigator)
                Screen.Settings -> SettingsScreen(navigator)
                is Screen.Rules -> RulesScreen(screen.eveningId, navigator)
                Screen.AiSettings -> AiSettingsScreen(navigator)
                Screen.Backup -> BackupScreen(navigator)
            }
        }
    }
}
