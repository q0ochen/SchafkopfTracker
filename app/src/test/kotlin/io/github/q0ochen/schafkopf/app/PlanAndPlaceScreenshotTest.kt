package io.github.q0ochen.schafkopf.app

import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.q0ochen.schafkopf.app.ui.editevening.EditEveningScreen
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.newevening.NewEveningScreen
import io.github.q0ochen.schafkopf.app.ui.start.StartScreen
import io.github.q0ochen.schafkopf.app.ui.theme.SchafkopfTheme
import io.github.q0ochen.schafkopf.app.ui.waitForText
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Geplante Abende und Ortsvorschläge (docs/ARCHITEKTUR.md, 4.2 und 4.3): Bildschirmfotos 63 bis 66 und die Abläufe
 * dahinter. „Jetzt“ ist hier fest Samstag, 21.11.2026, 10:00 Uhr in Berlin; die Ortsvorschläge kommen aus
 * [TestPlaceSearch], nie aus dem Internet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [36],
    // Die Datumsauswahl bringt ihre Texte aus dem System mit; auf Deutsch, wie auf den Handys der Runde.
    qualifiers = "de-rDE-w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav",
    application = TestSchafkopfApp::class,
)
class PlanAndPlaceScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val now: Instant = Instant.parse("2026-11-21T09:00:00Z")
    private val seating = listOf("sepp", "hans", "kathi", "vroni").map(::PlayerId)

    @After
    fun stopDependencyInjection() {
        TestPlaceSearch.offline = false
        stopKoin()
    }

    /** Vier Spieler und ein beendeter Abend vom 14.11.2026; auf Wunsch dazu ein geplanter am 12.12.2026. */
    private fun seed(planned: Boolean = false) = runBlocking {
        ApplicationProvider.getApplicationContext<TestSchafkopfApp>()
        loadKoinModules(module { single<Clock> { Clock.fixed(now, berlin) } })
        val koin = GlobalContext.get()
        val players = koin.get<PlayerRepository>()
        val evenings = koin.get<EveningRepository>()
        listOf("Sepp", "Hans", "Kathi", "Vroni").forEachIndexed { index, name -> players.save(Player(seating[index], name, colorIndex = index)) }
        val past = Instant.parse("2026-11-14T18:30:00Z")
        evenings.save(
            Evening(
                id = EveningId("abend-1"),
                startedAt = past,
                finishedAt = past.plusSeconds(4 * 3600L),
                location = "Wirtshaus zur Post",
                seating = seating,
                firstDealer = seating[0],
                rules = RuleSet.standard(),
                entries = listOf(
                    GameEntry(EntryId("e1"), past, GameType.RUFSPIEL, Suit.EICHEL, declarer = seating[0], partner = seating[2], declarerWon = true),
                ),
            ),
        )
        if (planned) {
            evenings.save(
                Evening(
                    id = EveningId("abend-2"),
                    startedAt = Instant.parse("2026-12-12T18:00:00Z"),
                    location = "Klinikum Bamberg, Buger Straße 80, 96049 Bamberg",
                    seating = seating,
                    firstDealer = seating[1],
                    rules = RuleSet.standard(),
                ),
            )
        }
    }

    private fun show(themeMode: ThemeMode, ready: String, content: @Composable () -> Unit) {
        compose.setContent {
            SchafkopfTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        compose.waitForIdle()
        compose.waitForText(ready)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** Lässt die Wartezeit der Ortsvorschläge (400 ms nach der letzten Eingabe) auf dem Hauptthread verstreichen. */
    private fun letSuggestionsArrive() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        compose.waitForIdle()
    }

    private fun typeLocation(text: String) {
        compose.onNode(hasSetTextAction() and hasText("Ort (freiwillig)")).performScrollTo().performTextInput(text)
        letSuggestionsArrive()
    }

    @Test
    fun start_mit_geplantem_abend_dunkel() {
        seed(planned = true)
        show(ThemeMode.DARK, ready = "Abende") { StartScreen(Navigator()) }
        // Die Überschrift „Abende“ steht schon vor den Daten da; die Karten kommen, sobald die Datenbank geantwortet hat.
        compose.waitUntil(30_000) {
            runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("geplant")) }.isSuccess
        }
        compose.onNodeWithText("Wirtshaus zur Post", substring = true).assertExists()
        // Ein geplanter Abend ist kein laufender: keine Karte „Abend läuft“.
        compose.onAllNodesWithText("Abend läuft").assertCountEquals(0)
        compose.onAllNodesWithText("Weiterspielen").assertCountEquals(0)
        capture("63_start_geplant_dunkel")
    }

    @Test
    fun neuer_abend_geplant_dunkel() {
        seed()
        show(ThemeMode.DARK, ready = "Wie letztes Mal") { NewEveningScreen(Navigator()) }
        compose.onNodeWithText("Wie letztes Mal").performClick()
        compose.onNodeWithText("Sa., 21. Nov. 2026").performScrollTo()
        compose.onNodeWithText("Datum ändern").performClick()
        compose.waitForText("Übernehmen")
        compose.onNode(hasText("28. November 2026", substring = true) and hasClickAction()).performClick()
        compose.onNodeWithText("Übernehmen").performClick()
        compose.waitForText("Der Abend ist geplant.")
        compose.onNodeWithText("Sa., 28. Nov. 2026").assertExists()
        compose.onNodeWithText("Abend anlegen").assertExists()
        compose.onNodeWithText("Ort (freiwillig)").performScrollTo()
        capture("64_neuer_abend_geplant_dunkel")
    }

    @Test
    fun neuer_abend_ortsvorschlaege_dunkel() {
        seed()
        show(ThemeMode.DARK, ready = "Wie letztes Mal") { NewEveningScreen(Navigator()) }
        compose.onNodeWithText("Wie letztes Mal").performClick()
        typeLocation("Klinikum Bamberg")
        compose.waitForText("Buger Straße 80, 96049 Bamberg")
        compose.waitForText("Vorschläge: © OpenStreetMap-Mitwirkende, über photon.komoot.io")
        capture("65_neuer_abend_ortsvorschlaege_dunkel")

        // Antippen übernimmt den ganzen Vorschlag und schließt die Liste, ohne neu zu suchen.
        val before = TestPlaceSearch.queries.size
        compose.onNode(hasText("Buger Straße 80, 96049 Bamberg") and hasClickAction()).performClick()
        compose.waitForText("Klinikum Bamberg, Buger Straße 80, 96049 Bamberg")
        letSuggestionsArrive()
        compose.onAllNodesWithText("Vorschläge: © OpenStreetMap-Mitwirkende, über photon.komoot.io").assertCountEquals(0)
        assertEquals(before, TestPlaceSearch.queries.size)
        // Dass der Ort so beim neuen Abend ankommt, prüft NewEveningViewModelTest.
    }

    @Test
    fun abend_bearbeiten_ohne_netz_hell() {
        seed()
        show(ThemeMode.LIGHT, ready = "Datum ändern") { EditEveningScreen("abend-1", Navigator()) }
        TestPlaceSearch.offline = true
        compose.onNode(hasSetTextAction() and hasText("Ort (freiwillig)")).performTextReplacement("Gasthof Hirsch")
        letSuggestionsArrive()
        compose.waitForText("Ortsvorschläge gibt es nur mit Internet.")
        capture("66_abend_bearbeiten_ohne_netz_hell")
    }
}
