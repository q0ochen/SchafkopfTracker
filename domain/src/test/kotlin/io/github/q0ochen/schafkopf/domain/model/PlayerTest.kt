package io.github.q0ochen.schafkopf.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlayerTest {

    @Test
    fun `Namen werden von Leerraum am Rand und doppelten Leerzeichen befreit`() {
        assertEquals("Spieler Eins", Player.cleanName("  Spieler   Eins \n"))
        assertEquals("X", Player.cleanName("X"))
    }

    @Test
    fun `leere Namen werden abgelehnt`() {
        assertNull(Player.cleanName(""))
        assertNull(Player.cleanName("   \t "))
    }

    @Test
    fun `ein Name darf hoechstens 24 Zeichen haben`() {
        val longest = "a".repeat(Player.MAX_NAME_LENGTH)
        assertEquals(longest, Player.cleanName(longest))
        assertNull(Player.cleanName(longest + "b"))
    }

    @Test
    fun `Kennungen sind einfache Werte um einen Text`() {
        assertEquals("p1", PlayerId("p1").value)
        assertEquals(PlayerId("p1"), PlayerId("p1"))
        assertEquals("abend", EveningId("abend").value)
        assertEquals("e1", EntryId("e1").value)
        assertEquals("fest", IdGenerator { "fest" }.newId())
    }

    @Test
    fun `ein neuer Spieler ist nicht archiviert`() {
        val player = Player(PlayerId("p1"), "Spieler 1", colorIndex = 2)
        assertEquals(false, player.archived)
        assertEquals(2, player.colorIndex)
    }

    private fun player(id: String, colorIndex: Int, archived: Boolean = false) = Player(PlayerId(id), id, colorIndex, archived)

    @Test
    fun `ein neuer Spieler bekommt die erste freie Farbe`() {
        assertEquals(0, Player.nextColorIndex(emptyList()))
        assertEquals(4, Player.nextColorIndex(listOf(player("a", 0), player("b", 1), player("c", 2), player("d", 3))))
        // Nach dem Löschen von Spieler 2 ist dessen Farbe wieder frei.
        assertEquals(1, Player.nextColorIndex(listOf(player("a", 0), player("c", 2), player("d", 3), player("e", 4))))
        // Archivierte Spieler geben ihre Farbe frei; Nummern über 7 zählen wie ihre Farbe in der Palette.
        assertEquals(0, Player.nextColorIndex(listOf(player("a", 0, archived = true), player("b", 1))))
        assertEquals(1, Player.nextColorIndex(listOf(player("a", 8), player("b", -6))))
    }

    @Test
    fun `sind alle Farben vergeben, geht es nach der hoechsten Nummer weiter`() {
        val all = (0 until Player.COLOR_COUNT).map { player("p$it", it) }
        assertEquals(Player.COLOR_COUNT, Player.nextColorIndex(all))
        assertEquals(12, Player.nextColorIndex(all + player("x", 11)))
    }
}
