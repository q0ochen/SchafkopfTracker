package io.github.q0ochen.schafkopf.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bildschirme lassen sich als Text sichern und wiederherstellen, auch aus einer älteren Version der App. */
class ScreenTest {

    @Test
    fun `jeder Bildschirm uebersteht Speichern und Wiederherstellen`() {
        val screens = listOf(
            Screen.Start,
            Screen.NewEvening,
            Screen.Board("a"),
            Screen.GameForm("a", null),
            Screen.GameForm("a", "e1"),
            Screen.Closing("a"),
            Screen.Statistics(null),
            Screen.Statistics("a"),
            Screen.Players,
            Screen.Settings,
            Screen.Rules(null),
            Screen.Rules("a"),
            Screen.AiSettings,
            Screen.Backup,
        )
        for (screen in screens) assertEquals(screen, Screen.decode(screen.encode()))
    }

    @Test
    fun `der fruehere Verlauf oeffnet die Statistik des Abends`() {
        assertEquals(Screen.Statistics("a"), Screen.decode("progress|a"))
        assertEquals(Screen.Statistics(null), Screen.decode("stats"))
        assertEquals(null, Screen.decode("unbekannt"))
    }
}
