package io.github.q0ochen.schafkopf.app.ui.statistics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.q0ochen.schafkopf.app.ui.TestEvenings
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.stats.GelegtBadge
import io.github.q0ochen.schafkopf.domain.stats.StatisticEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** Die Statistik als Text zum Teilen, mit den Texten aus den Ressourcen der App. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StatisticsTextsTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources
    private val berlin = ZoneId.of("Europe/Berlin")
    private val nbsp = " "
    private val name: (PlayerId) -> String = { TestEvenings.names[it] ?: "?" }

    @Test
    fun `ein Abend als Text`() {
        val evening = TestEvenings.evening()
        val stats = StatisticEngine.forEvening(evening)
        val text = statisticsShareText(resources, stats, evening.startedAt, name, asMoney = true, zone = berlin)
        val streichholz = "Streichholz-Sparer: hat noch nie gelegt"
        assertEquals(
            listOf(
                "Schafkopf-Statistik: Abend vom Sa., 14. Nov. 2026",
                "2 Spiele · Umsatz 1,70$nbsp€",
                "",
                "Kontostand:",
                "1. Anna +0,60$nbsp€",
                "1. Cilli +0,60$nbsp€",
                "3. Done +0,40$nbsp€",
                "4. Bert −1,60$nbsp€",
                "",
                "Auszeichnungen:",
                "Bock-König: noch niemand",
                "Solo-Spezialist: Bert (1 von 1 angesagten Spielen allein)",
                "Mauerblümchen: noch niemand",
                "Lieblingspartner: noch niemand",
                "",
                "Sollte er es lassen?",
                "Anna – $streichholz",
                "Bert – $streichholz",
                "Cilli – $streichholz",
                "Done – $streichholz",
            ).joinToString("\n"),
            text,
        )
    }

    @Test
    fun `alle Abende als Text, in Punkten`() {
        val older = TestEvenings.evening(id = "alt", startedAt = Instant.parse("2026-11-01T18:30:00Z"))
        val stats = StatisticEngine.forEvenings(listOf(older, TestEvenings.evening()))
        val text = statisticsShareText(resources, stats, null, name, asMoney = false, zone = berlin)
        val lines = text.lines()
        assertEquals("Schafkopf-Statistik: alle Abende", lines[0])
        assertEquals("2 Abende · 4 Spiele · Umsatz 340", lines[1])
        assertTrue(text, lines.contains("4. Bert −320"))
    }

    @Test
    fun `jedes Abzeichen hat seinen Text`() {
        val texts = GelegtBadge.entries.map { resources.getString(it.labelRes()) }
        assertEquals(GelegtBadge.entries.size, texts.toSet().size)
        assertTrue(texts.all { it.isNotBlank() })
    }
}
