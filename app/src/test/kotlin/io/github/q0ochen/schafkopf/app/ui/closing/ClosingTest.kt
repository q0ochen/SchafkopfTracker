package io.github.q0ochen.schafkopf.app.ui.closing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.q0ochen.schafkopf.app.ui.TestEvenings
import io.github.q0ochen.schafkopf.app.ui.TestEvenings.anna
import io.github.q0ochen.schafkopf.app.ui.TestEvenings.bert
import io.github.q0ochen.schafkopf.app.ui.TestEvenings.cilli
import io.github.q0ochen.schafkopf.app.ui.TestEvenings.done
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.Transfer
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/**
 * Abschluss eines Abends: Endstand, „Wer zahlt wem“ und der Text zum Teilen. Die Texte kommen aus den Ressourcen
 * der App (deshalb Robolectric, aber ohne die App selbst zu starten).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ClosingTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources
    private val berlin = ZoneId.of("Europe/Berlin")
    private val nbsp = " "

    @Test
    fun `Endstand nach Platz, bei gleichem Platz in Sitzreihenfolge, und wer wem zahlt`() {
        // Rufspiel Anna mit Cilli gewonnen (je 10), Wenz von Bert verloren (Bert −150, die anderen je +50).
        val state = EveningCalculator.compute(TestEvenings.evening())
        val closing = Closing.of(state, TestEvenings.names)

        assertEquals(listOf(anna, cilli, done, bert), closing.standings.map { it.player })
        assertEquals(listOf(1, 1, 3, 4), closing.standings.map { it.rank })
        assertEquals(listOf(60, 60, 40, -160), closing.standings.map { it.balance })
        assertEquals(listOf("Anna", "Cilli", "Done", "Bert"), closing.standings.map { it.name })
        assertEquals(listOf(Transfer(bert, anna, 60), Transfer(bert, cilli, 60), Transfer(bert, done, 40)), closing.transfers)
        assertTrue(closing.balanced)
    }

    @Test
    fun `eine fehlerhafte freie Buchung laesst einen Rest offen`() {
        val broken = ManualEntry(EntryId("frei"), Instant.parse("2026-11-14T20:00:00Z"), mapOf(anna to 5), note = "")
        val state = EveningCalculator.compute(TestEvenings.evening(extra = listOf(broken)))
        val closing = Closing.of(state, TestEvenings.names)
        assertFalse(closing.balanced)
        assertEquals(65, closing.standings.first().balance)
    }

    @Test
    fun `der Text zum Teilen nennt Datum, Ort, Spiele, Endstand und Zahlungen`() {
        val evening = TestEvenings.evening(location = "  Wirtshaus zur Post ")
        val state = EveningCalculator.compute(evening)
        val text = closingShareText(resources, state, Closing.of(state, TestEvenings.names), TestEvenings.names, berlin)
        assertEquals(
            listOf(
                "Schafkopf-Abend vom Sa., 14. Nov. 2026 · Wirtshaus zur Post",
                "2 Spiele",
                "",
                "1. Anna +0,60$nbsp€",
                "1. Cilli +0,60$nbsp€",
                "3. Done +0,40$nbsp€",
                "4. Bert −1,60$nbsp€",
                "",
                "Wer zahlt wem:",
                "Bert → Anna: 0,60$nbsp€",
                "Bert → Cilli: 0,60$nbsp€",
                "Bert → Done: 0,40$nbsp€",
            ).joinToString("\n"),
            text,
        )
    }

    @Test
    fun `ohne Ort, in Punkten und ohne Zahlungen`() {
        val points = RuleSet.standard().copy(displayAsMoney = false)
        val evening = TestEvenings.evening(rules = points).copy(entries = emptyList())
        val state = EveningCalculator.compute(evening)
        val text = closingShareText(resources, state, Closing.of(state, TestEvenings.names), TestEvenings.names, berlin)
        assertEquals(
            listOf("Schafkopf-Abend vom Sa., 14. Nov. 2026", "0 Spiele", "", "1. Anna 0", "1. Bert 0", "1. Cilli 0", "1. Done 0")
                .joinToString("\n"),
            text,
        )
        val game = EveningCalculator.compute(TestEvenings.evening(rules = points))
        val withGames = closingShareText(resources, game, Closing.of(game, TestEvenings.names), TestEvenings.names, berlin)
        assertTrue(withGames, withGames.contains("4. Bert −160\n"))
        assertTrue(withGames, withGames.endsWith("Bert → Done: 40"))
    }
}
