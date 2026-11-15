package io.github.q0ochen.schafkopf.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.q0ochen.schafkopf.app.ui.board.BoardScreen
import io.github.q0ochen.schafkopf.app.ui.gameform.GameFormScreen
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.newevening.NewEveningScreen
import io.github.q0ochen.schafkopf.app.ui.start.StartScreen
import io.github.q0ochen.schafkopf.app.ui.theme.SchafkopfTheme
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import io.github.q0ochen.schafkopf.app.ui.settings.RulesScreen
import io.github.q0ochen.schafkopf.app.ui.settings.SettingsScreen
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Zeichnet die wichtigsten Bildschirme mit Beispieldaten als PNG nach `app/build/outputs/roborazzi`.
 * Läuft auf der JVM, ohne Gerät und ohne Emulator.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [36],
    qualifiers = "w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav",
    application = TestSchafkopfApp::class,
)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val sepp = PlayerId("sepp")
    private val hans = PlayerId("hans")
    private val kathi = PlayerId("kathi")
    private val vroni = PlayerId("vroni")
    private val seating = listOf(sepp, hans, kathi, vroni)
    private val start: Instant = Instant.parse("2026-11-14T18:30:00Z")
    private val eveningId = "abend-1"

    /** Koin wird von der App beim Start hochgefahren; nach jedem Test wieder stoppen, damit der nächste frisch beginnt. */
    @After
    fun stopDependencyInjection() = stopKoin()

    private fun seed(finished: Boolean = false) = runBlocking {
        ApplicationProvider.getApplicationContext<TestSchafkopfApp>()
        val koin = GlobalContext.get()
        val players = koin.get<PlayerRepository>()
        val evenings = koin.get<EveningRepository>()
        listOf("Sepp", "Hans", "Kathi", "Vroni").forEachIndexed { index, name ->
            players.save(Player(seating[index], name, colorIndex = index))
        }
        var number = 0
        fun id() = EntryId("e${number++}")
        val entries = listOf(
            GameEntry(id(), start, GameType.RUFSPIEL, Suit.EICHEL, declarer = sepp, partner = kathi, declarerWon = true),
            GameEntry(
                id(), start, GameType.WENZ, declarer = hans, declarerWon = false, laufende = 2,
                klopfer = setOf(hans), kontra = KontraLevel.KONTRA, kontraBy = vroni,
            ),
            ThrownInEntry(id(), start),
            BockTriggerEntry(id(), start, BockReason.SECHS_NIXER, player = kathi),
            GameEntry(
                id(), start, GameType.FARBSOLO, Suit.HERZ, declarer = vroni, declarerWon = true,
                level = ResultLevel.SCHNEIDER, laufende = 3,
            ),
            GameEntry(id(), start, GameType.RUFSPIEL, Suit.GRAS, declarer = kathi, partner = hans, declarerWon = false, klopfer = setOf(sepp, vroni)),
            GameEntry(id(), start, GameType.GEIER, declarer = sepp, declarerWon = true),
        )
        evenings.save(
            Evening(
                id = EveningId(eveningId),
                startedAt = start,
                finishedAt = start.takeIf { finished },
                location = "Wirtshaus zur Post",
                seating = seating,
                firstDealer = sepp,
                rules = RuleSet.sauspiel(),
                entries = entries,
            ),
        )
    }

    private fun show(themeMode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
        compose.setContent {
            SchafkopfTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun start_mit_laufendem_abend() {
        seed()
        show { StartScreen(Navigator()) }
        capture("01_start")
    }

    @Test
    fun neuer_abend() {
        seed(finished = true)
        show { NewEveningScreen(Navigator()) }
        compose.onNodeWithText("Wie letztes Mal").performClick()
        capture("02_neuer_abend")
    }

    @Test
    fun abend_hell() {
        seed()
        show { BoardScreen(eveningId, Navigator()) }
        capture("03_abend_hell")
    }

    @Test
    fun abend_dunkel() {
        seed()
        show(ThemeMode.DARK) { BoardScreen(eveningId, Navigator()) }
        capture("04_abend_dunkel")
    }

    @Test
    fun spiel_eintragen_leer() {
        seed()
        show { GameFormScreen(eveningId, null, Navigator()) }
        capture("05_formular_leer")
    }

    @Test
    fun spiel_eintragen_ausgefuellt() {
        seed()
        show { GameFormScreen(eveningId, null, Navigator()) }
        compose.onAllNodesWithText("Sepp")[0].performClick()
        compose.onAllNodesWithText("Kathi")[0].performClick()
        compose.onAllNodesWithText("Hans")[1].performClick()
        // „Kontra“ steht zweimal da: als Überschrift und als Auswahl.
        compose.onAllNodesWithText("Kontra")[1].performClick()
        compose.onNodeWithText("Schneider").performClick()
        capture("06_formular_ausgefuellt")
    }

    @Test
    fun spiel_aendern_dunkel() {
        seed()
        show(ThemeMode.DARK) { GameFormScreen(eveningId, "e4", Navigator()) }
        capture("07_formular_aendern_dunkel")
    }

    @Test
    fun einstellungen_dunkel() {
        seed()
        show(ThemeMode.DARK) { SettingsScreen(Navigator()) }
        capture("08_einstellungen_dunkel")
    }

    @Test
    fun regelwerk_dunkel() {
        seed()
        show(ThemeMode.DARK) { RulesScreen(null, Navigator()) }
        capture("09_regelwerk_dunkel")
    }
}
