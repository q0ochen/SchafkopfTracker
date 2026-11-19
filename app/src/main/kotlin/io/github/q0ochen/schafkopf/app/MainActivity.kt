package io.github.q0ochen.schafkopf.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.q0ochen.schafkopf.app.round.RoundCoordinator
import io.github.q0ochen.schafkopf.app.ui.navigation.AppNavHost
import io.github.q0ochen.schafkopf.app.ui.theme.SchafkopfTheme
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val settingsRepository: SettingsRepository by inject()
    private val round: RoundCoordinator by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Nur beim ersten Anlegen: Nach einer Drehung trüge die Activity denselben Link noch einmal vor.
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            SchafkopfTheme(themeMode = settings.themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost()
                }
            }
        }
    }

    /** Die App läuft schon und wird über einen Link geöffnet: Rückkehr aus der Anmeldung oder eine Einladung. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** Solange die App sichtbar ist, gleicht sie die gemeinsame Runde laufend ab. */
    override fun onStart() {
        super.onStart()
        round.setForeground(true)
    }

    override fun onStop() {
        round.setForeground(false)
        super.onStop()
    }

    private fun handleLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        round.onLink(link)
    }
}
