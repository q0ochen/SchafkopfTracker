package io.github.q0ochen.schafkopf.domain.round

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Namen von Runden (docs/ARCHITEKTUR.md, 8.7, „Eindeutige Namen“): bereinigen und vergleichen wie der Server. */
class RoundNamesTest {

    private fun round(id: String, name: String, vararg names: String) =
        RoundSummary(RoundId(id), name, rev = 1, memberCount = names.size.coerceAtLeast(1), myPlayerId = null, isCreator = false, memberNames = names.toList())

    @Test
    fun `bereinigt wie der Server - Folgen von Leer- und Steuerzeichen werden ein Leerzeichen, Raender fallen weg`() {
        assertEquals("Stammtisch Runde", RoundNames.clean("  Stammtisch \t  Runde \n"))
        assertEquals("a b", RoundNames.clean("a\u0000\u001Fb\u007F"))
        assertEquals("", RoundNames.clean(" \t\r\n "))
        // Nur ASCII: Ein geschütztes Leerzeichen bleibt, wie es ist (der Server sieht es je nach Einstellung anders).
        assertEquals("a b", RoundNames.clean("a b"))
    }

    @Test
    fun `gueltig sind 1 bis 40 Zeichen nach dem Bereinigen, gezaehlt wie auf dem Server`() {
        assertFalse(RoundNames.valid(" \t "))
        assertTrue(RoundNames.valid("x"))
        assertTrue(RoundNames.valid("  " + "x".repeat(40) + "  "))
        assertFalse(RoundNames.valid("x".repeat(41)))
        assertTrue(RoundNames.valid("Ä" + "ö".repeat(39)))
        // Zeichen außerhalb der Grundebene zählen einmal, nicht zweimal.
        assertTrue(RoundNames.valid("🃏".repeat(40)))
        assertFalse(RoundNames.valid("🃏".repeat(41)))
    }

    @Test
    fun `derselbe Name ohne Gross und Klein und nach dem Bereinigen`() {
        assertTrue(RoundNames.same("Stammtisch", "  stammTISCH\t"))
        assertTrue(RoundNames.same("Stamm  tisch", "stamm tisch"))
        assertFalse(RoundNames.same("Stammtisch", "Stamm tisch"))
        assertTrue(RoundNames.same("Ärger", "ärger"))
    }

    @Test
    fun `vergeben ist ein Name, wenn eine Runde des Kontos so heisst`() {
        val rounds = listOf(round("a", "Stammtisch"), round("b", "Klub"))
        assertTrue(RoundNames.taken(" klub ", rounds))
        assertFalse(RoundNames.taken("Klub 2", rounds))
        assertFalse(RoundNames.taken("Stammtisch", emptyList()))
    }

    @Test
    fun `der Vorschlag ist die erste freie Fassung mit Nummer`() {
        assertEquals("Schafkopfrunde", RoundNames.suggest(emptyList()))
        assertEquals("Schafkopfrunde", RoundNames.suggest(listOf(round("a", "Stammtisch"))))
        assertEquals("Schafkopfrunde 2", RoundNames.suggest(listOf(round("a", "schafkopfrunde"))))
        assertEquals(
            "Schafkopfrunde 4",
            RoundNames.suggest(listOf(round("a", "Schafkopfrunde 3"), round("b", "Schafkopfrunde"), round("c", "Schafkopfrunde 2"))),
        )
        assertEquals("Schafkopfrunde 2", RoundNames.suggest(listOf(round("a", "Schafkopfrunde"), round("b", "Schafkopfrunde 3"))))
        assertEquals("Klub", RoundNames.suggest(emptyList(), base = "Klub"))
    }

    @Test
    fun `gleichnamige Runden werden erkannt, ohne Gross und Klein`() {
        val rounds = listOf(
            round("a", "Stammtisch", "Anna", "Bert"),
            round("b", "Klub"),
            round("c", "STAMMTISCH ", "Cilli"),
            round("d", "Freitag"),
            round("e", "freitag"),
        )
        assertEquals(setOf(RoundId("a"), RoundId("c"), RoundId("d"), RoundId("e")), RoundNames.duplicates(rounds))
        assertEquals(emptySet<RoundId>(), RoundNames.duplicates(rounds.take(2)))
    }
}
