package io.github.q0ochen.schafkopf.app.ui.statistics

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import io.github.q0ochen.schafkopf.app.TestSchafkopfApp
import io.github.q0ochen.schafkopf.app.ui.TestEvenings
import io.github.q0ochen.schafkopf.app.ui.theme.SchafkopfTheme
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.stats.ModeRecord
import io.github.q0ochen.schafkopf.domain.stats.StatisticEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Das Spielarten-Diagramm: Was Screenreader je Zeile vorlesen und wie die Spielarten gegliedert sind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestSchafkopfApp::class)
class GameModeChartTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun stopDependencyInjection() = stopKoin()

    private val nbsp = " "

    private val groups = listOf(
        ModeChartGroup(
            title = "Rufspiel",
            games = 6,
            rows = listOf(
                ModeChartRow("Sepp", 0, ModeRecord(games = 4, won = 3, balance = 160)),
                ModeChartRow("Hans", 1, ModeRecord(games = 0, won = 0, balance = 0)),
                ModeChartRow("Kathi", 2, ModeRecord(games = 2, won = 0, balance = -60)),
                ModeChartRow("Vroni", 3, null),
            ),
        ),
        ModeChartGroup(
            title = "Wenz",
            games = 1,
            rows = listOf(ModeChartRow("Hans", 1, ModeRecord(games = 1, won = 1, balance = 150))),
        ),
    )

    private fun show(asMoney: Boolean = true) {
        compose.setContent {
            SchafkopfTheme(ThemeMode.DARK) {
                Surface { GameModeChart(groups, asMoney) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `jede Zeile liest sich als Satz mit Durchschnitt und Gewinnquote`() {
        show()
        compose.onNodeWithContentDescription(
            "Sepp, Rufspiel: im Schnitt +0,40$nbsp€ je Spiel, 3 von 4 gewonnen (75$nbsp%)",
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Kathi, Rufspiel: im Schnitt −0,30$nbsp€ je Spiel, 0 von 2 gewonnen (0$nbsp%)",
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Hans, Wenz: im Schnitt +1,50$nbsp€ je Spiel, 1 von 1 gewonnen (100$nbsp%)",
        ).assertIsDisplayed()
    }

    @Test
    fun `wer die Spielart nicht selbst gespielt hat, steht mit Hinweis da`() {
        show()
        compose.onNodeWithContentDescription("Hans, Rufspiel: nicht selbst gespielt").assertIsDisplayed()
        compose.onNodeWithContentDescription("Vroni, Rufspiel: nicht selbst gespielt").assertIsDisplayed()
    }

    @Test
    fun `jede Spielart ist eine Ueberschrift mit der Zahl ihrer Spiele`() {
        show()
        compose.onAllNodes(isHeading() and hasText("Rufspiel") and hasText("6 Spiele")).assertCountEquals(1)
        compose.onAllNodes(isHeading() and hasText("Wenz") and hasText("1 Spiel")).assertCountEquals(1)
    }

    @Test
    fun `die Gruppen enthalten nur gespielte Spielarten und alle Spieler in Sitzreihenfolge`() {
        // Rufspiel: Anna mit Cilli gewonnen (je +10); Wenz: Bert verliert 150.
        val stats = StatisticEngine.forEvening(TestEvenings.evening())
        val groups = modeChartGroups(
            stats = stats,
            typeLabel = { it.name },
            name = { TestEvenings.names.getValue(it) },
            colorIndex = { TestEvenings.seating.indexOf(it) },
        )
        assertEquals(listOf(GameType.RUFSPIEL.name, GameType.WENZ.name), groups.map { it.title })
        assertEquals(listOf(1, 1), groups.map { it.games })
        val (ruf, wenz) = groups
        assertEquals(listOf("Anna", "Bert", "Cilli", "Done"), ruf.rows.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), ruf.rows.map { it.colorIndex })
        assertEquals(ModeRecord(games = 1, won = 1, balance = 10), ruf.rows[0].played)
        assertNull("Bert war im Rufspiel Gegenspieler", ruf.rows[1].played)
        assertEquals(ModeRecord(games = 1, won = 1, balance = 10), ruf.rows[2].played)
        assertEquals(ModeRecord(games = 1, won = 0, balance = -150), wenz.rows[1].played)
        assertEquals(-150, wenz.rows[1].played?.averageAmount)
        assertNull(wenz.rows[0].played)
    }

    @Test
    fun `in Punkten statt Euro`() {
        show(asMoney = false)
        compose.onNodeWithContentDescription(
            "Sepp, Rufspiel: im Schnitt +40 je Spiel, 3 von 4 gewonnen (75$nbsp%)",
        ).assertIsDisplayed()
    }
}
