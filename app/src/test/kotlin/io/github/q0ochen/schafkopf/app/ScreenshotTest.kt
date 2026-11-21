package io.github.q0ochen.schafkopf.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.q0ochen.schafkopf.app.backup.AutoBackupStore
import io.github.q0ochen.schafkopf.app.ui.ai.AiSettingsScreen
import io.github.q0ochen.schafkopf.app.ui.backup.BackupScreen
import io.github.q0ochen.schafkopf.app.ui.board.BoardScreen
import io.github.q0ochen.schafkopf.app.ui.closing.ClosingScreen
import io.github.q0ochen.schafkopf.app.ui.editevening.EditEveningScreen
import io.github.q0ochen.schafkopf.app.ui.common.labelRes
import io.github.q0ochen.schafkopf.app.ui.statistics.GameModeChart
import io.github.q0ochen.schafkopf.app.ui.statistics.StatisticsScreen
import io.github.q0ochen.schafkopf.app.ui.statistics.modeChartGroups
import io.github.q0ochen.schafkopf.app.ui.waitForText
import io.github.q0ochen.schafkopf.app.ui.gameform.GameFormScreen
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.newevening.NewEveningScreen
import io.github.q0ochen.schafkopf.app.ui.start.StartScreen
import io.github.q0ochen.schafkopf.app.ui.start.StartViewModel
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
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import io.github.q0ochen.schafkopf.domain.stats.StatisticEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.androidx.compose.koinViewModel
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

    private fun seed(finished: Boolean = false, secondEvening: Boolean = false) = runBlocking {
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
            GameEntry(id(), start, GameType.UEBER_KREUZ, declarer = hans, partner = vroni, declarerWon = true, klopfer = setOf(kathi)),
        )
        evenings.save(
            Evening(
                id = EveningId(eveningId),
                startedAt = start,
                finishedAt = start.takeIf { finished },
                location = "Wirtshaus zur Post",
                seating = seating,
                firstDealer = sepp,
                rules = RuleSet.standard(),
                entries = entries,
            ),
        )
        if (secondEvening) {
            val earlier = start.minusSeconds(14 * 24 * 3600L)
            evenings.save(
                Evening(
                    id = EveningId("abend-0"),
                    startedAt = earlier,
                    finishedAt = earlier.plusSeconds(4 * 3600L),
                    seating = seating,
                    firstDealer = hans,
                    rules = RuleSet.standard(),
                    entries = listOf(
                        GameEntry(EntryId("f1"), earlier, GameType.RUFSPIEL, Suit.SCHELLEN, declarer = hans, partner = vroni, declarerWon = true),
                        GameEntry(EntryId("f2"), earlier, GameType.FARBSOLO, Suit.EICHEL, declarer = kathi, declarerWon = false, laufende = 3),
                        GameEntry(EntryId("f3"), earlier, GameType.RUFSPIEL, Suit.EICHEL, declarer = sepp, partner = hans, declarerWon = true, klopfer = setOf(sepp)),
                        GameEntry(EntryId("f4"), earlier, GameType.WENZ, declarer = vroni, declarerWon = true, klopfer = setOf(vroni, kathi)),
                    ),
                ),
            )
        }
    }

    /**
     * @param ready Ein Text, der erst mit den geladenen Daten erscheint; bis dahin wird gewartet.
     * @param fontScale Schriftgröße wie in den Einstellungen des Handys (1 = normal).
     */
    private fun show(
        themeMode: ThemeMode = ThemeMode.LIGHT,
        ready: String? = null,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                SchafkopfTheme(themeMode) {
                    Surface(modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
        compose.waitForIdle()
        ready?.let { compose.waitForText(it) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun start_mit_laufendem_abend() {
        seed()
        var viewModel: StartViewModel? = null
        show {
            viewModel = koinViewModel()
            StartScreen(Navigator())
        }
        compose.waitForText("Wirtshaus zur Post") {
            "ViewModel: ${viewModel?.state?.value?.let { "loading=${it.loading}, ${it.evenings.size} Abende" }}"
        }
        capture("01_start")
    }

    @Test
    fun neuer_abend() {
        seed(finished = true)
        show(ready = "Wie letztes Mal") { NewEveningScreen(Navigator()) }
        compose.onNodeWithText("Wie letztes Mal").performClick()
        capture("02_neuer_abend")
    }

    @Test
    fun abend_hell() {
        seed()
        show(ready = "Sepp") { BoardScreen(eveningId, Navigator()) }
        capture("03_abend_hell")
    }

    @Test
    fun abend_dunkel() {
        seed()
        show(ThemeMode.DARK, ready = "Sepp") { BoardScreen(eveningId, Navigator()) }
        capture("04_abend_dunkel")
    }

    @Test
    fun spiel_eintragen_leer() {
        seed()
        show(ready = "Sepp") { GameFormScreen(eveningId, null, Navigator()) }
        capture("05_formular_leer")
    }

    @Test
    fun spiel_eintragen_ausgefuellt() {
        seed()
        show(ready = "Sepp") { GameFormScreen(eveningId, null, Navigator()) }
        compose.onAllNodesWithText("Sepp")[0].performClick()
        compose.onAllNodesWithText("Kathi")[0].performClick()
        compose.onAllNodesWithText("Hans")[1].performClick()
        // „Kontra“ steht zweimal da: als Überschrift und als Auswahl.
        compose.onAllNodesWithText("Kontra")[1].performClick()
        compose.onNodeWithText("Schneider").performClick()
        capture("06_formular_ausgefuellt")
    }

    @Test
    fun ueber_kreuz_eintragen() {
        seed()
        show(ThemeMode.DARK, ready = "Sepp") { GameFormScreen(eveningId, null, Navigator()) }
        compose.onNodeWithText("Über Kreuz").performClick()
        // Ein Tipp auf Sepp wählt das Paar Sepp und Kathi, die sich gegenübersitzen.
        compose.onAllNodesWithText("Sepp")[0].performClick()
        capture("10_formular_ueber_kreuz_dunkel")
    }

    @Test
    fun spiel_aendern_dunkel() {
        seed()
        show(ThemeMode.DARK, ready = "Vroni") { GameFormScreen(eveningId, "e4", Navigator()) }
        capture("07_formular_aendern_dunkel")
    }

    @Test
    fun einstellungen_dunkel() {
        seed()
        show(ThemeMode.DARK, ready = "hell") { SettingsScreen(Navigator()) }
        capture("08_einstellungen_dunkel")
    }

    @Test
    fun abschluss_dunkel() {
        seed(finished = true)
        show(ThemeMode.DARK, ready = "Wer zahlt wem") { ClosingScreen(eveningId, Navigator()) }
        capture("11_abschluss_dunkel")
    }

    @Test
    fun statistik_abend_hell() {
        seed()
        show(ready = "Kontostand") { StatisticsScreen(eveningId, Navigator()) }
        capture("12_statistik_abend_hell")
        // Antippen zeigt das Fadenkreuz mit den Ständen nach diesem Spiel.
        compose.onNodeWithContentDescription("Vermögensverlauf", substring = true).performTouchInput { click(center) }
        capture("13_statistik_fadenkreuz_hell")
        compose.onNodeWithText("Auszeichnungen").performScrollTo()
        capture("14_statistik_auszeichnungen_hell")
    }

    @Test
    fun statistik_alle_dunkel() {
        seed(secondEvening = true)
        show(ThemeMode.DARK, ready = "Kontostand") { StatisticsScreen(null, Navigator()) }
        capture("15_statistik_alle_dunkel")
        compose.onNodeWithText("Verlauf als Tabelle zeigen").performScrollTo().performClick()
        compose.onNodeWithText("Spielarten: selbst gespielt").performScrollTo()
        capture("16_statistik_tabelle_dunkel")
        compose.onNodeWithText("Rekorde je Spieler").performScrollTo()
        capture("17_statistik_rekorde_dunkel")
    }

    /** Ganz unten in der Statistik aller Abende: die Bilanz des Kommentators (ohne Sprachmodell aus Textbausteinen). */
    @Test
    fun statistik_bilanz_dunkel() {
        seed(secondEvening = true)
        show(ThemeMode.DARK, ready = "Kontostand") { StatisticsScreen(null, Navigator()) }
        compose.onNodeWithText("Kommentar schreiben lassen").performScrollTo().performClick()
        compose.waitForText("Kommentar teilen")
        compose.onNodeWithText("Kommentar teilen").performScrollTo()
        capture("24_statistik_bilanz_dunkel")
    }

    /** Das Spielarten-Diagramm aller Abende für sich, in einem hohen Fenster, damit es ganz aufs Bild passt. */
    @Test
    @Config(qualifiers = "w411dp-h1300dp-normal-long-notround-any-420dpi-keyshidden-nonav")
    fun statistik_spielarten_dunkel() {
        seed(secondEvening = true)
        showModeChart(ThemeMode.DARK)
        capture("22_statistik_spielarten_dunkel")
    }

    /** Dasselbe auf einem schmalen Handy (360 dp) und hell. */
    @Test
    @Config(qualifiers = "w360dp-h1300dp-normal-long-notround-any-420dpi-keyshidden-nonav")
    fun statistik_spielarten_schmal_hell() {
        seed(secondEvening = true)
        showModeChart(ThemeMode.LIGHT)
        capture("23_statistik_spielarten_schmal_hell")
    }

    /** Große Schrift (doppelt so groß) auf einem schmalen Handy: Kein Betrag darf abgeschnitten werden. */
    @Test
    @Config(qualifiers = "w360dp-h1300dp-normal-long-notround-any-420dpi-keyshidden-nonav")
    fun statistik_spielarten_grosse_schrift() {
        seed(secondEvening = true)
        showModeChart(ThemeMode.DARK, fontScale = 2f)
        capture("25_statistik_spielarten_grosse_schrift")
    }

    /** Der Kontostand bei großer Schrift: Die Spalten wachsen mit, die Tabelle lässt sich seitlich schieben. */
    @Test
    fun statistik_grosse_schrift() {
        seed(secondEvening = true)
        show(ThemeMode.DARK, ready = "Kontostand", fontScale = 1.6f) { StatisticsScreen(null, Navigator()) }
        capture("26_statistik_grosse_schrift")
    }

    private fun showModeChart(themeMode: ThemeMode, fontScale: Float = 1f) {
        val context = ApplicationProvider.getApplicationContext<TestSchafkopfApp>()
        val names = mapOf(sepp to "Sepp", hans to "Hans", kathi to "Kathi", vroni to "Vroni")
        val evenings = runBlocking { GlobalContext.get().get<EveningRepository>().evenings.first() }
        val groups = modeChartGroups(
            stats = StatisticEngine.forEvenings(evenings),
            typeLabel = { context.getString(it.labelRes()) },
            name = { names.getValue(it) },
            colorIndex = { seating.indexOf(it) },
        )
        // Wie in der App in einer scrollbaren Spalte; was nicht ins Fenster passt, liegt dann einfach darunter.
        show(themeMode, fontScale = fontScale) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                GameModeChart(groups, asMoney = true, modifier = Modifier.padding(16.dp))
            }
        }
    }

    @Test
    fun sicherung_dunkel() {
        seed()
        GlobalContext.get().get<AutoBackupStore>().apply {
            setTarget("content://drive/1", "schafkopf-sicherung.json")
            recordSuccess(start.plusSeconds(3 * 3600L))
        }
        show(ThemeMode.DARK, ready = "Datei: schafkopf-sicherung.json") { BackupScreen(Navigator()) }
        capture("19_sicherung_dunkel")
    }

    @Test
    fun abend_kommentator_einstellungen_dunkel() {
        seed()
        runBlocking {
            GlobalContext.get().get<SettingsRepository>().update { it.copy(ai = it.ai.copy(enabled = true)) }
        }
        show(ThemeMode.DARK, ready = "Google Gemini") { AiSettingsScreen(Navigator()) }
        capture("20_kommentator_einstellungen_dunkel")
    }

    @Test
    fun abschluss_mit_kommentar_dunkel() {
        seed(finished = true)
        show(ThemeMode.DARK, ready = "Wer zahlt wem") { ClosingScreen(eveningId, Navigator()) }
        compose.onNodeWithText("Kommentar schreiben lassen").performScrollTo().performClick()
        compose.waitForText("Kommentar teilen")
        compose.onNodeWithText("Kommentar teilen").performScrollTo()
        capture("21_abschluss_kommentar_dunkel")
    }

    @Test
    fun abend_menue_dunkel() {
        seed()
        show(ThemeMode.DARK, ready = "Sepp") { BoardScreen(eveningId, Navigator()) }
        compose.onNodeWithContentDescription("Mehr").performClick()
        compose.waitForText("Abend bearbeiten")
        capture("60_abend_menue_dunkel")
    }

    @Test
    fun abend_bearbeiten_dunkel() {
        seed()
        show(ThemeMode.DARK, ready = "Datum ändern") { EditEveningScreen(eveningId, Navigator()) }
        capture("61_abend_bearbeiten_dunkel")
    }

    // Die Datumsauswahl bringt ihre Texte aus dem System mit; auf Deutsch, wie auf den Handys der Runde.
    @Test
    @Config(qualifiers = "de-rDE-w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav")
    fun abend_bearbeiten_datum_hell() {
        seed(finished = true)
        show(ready = "Datum ändern") { EditEveningScreen(eveningId, Navigator()) }
        compose.onNodeWithText("Datum ändern").performClick()
        compose.waitForText("Übernehmen")
        capture("62_abend_bearbeiten_datum_hell")
    }

    @Test
    fun regelwerk_dunkel() {
        seed()
        show(ThemeMode.DARK, ready = "Runde 10/50") { RulesScreen(null, Navigator()) }
        capture("09_regelwerk_dunkel")
    }
}
