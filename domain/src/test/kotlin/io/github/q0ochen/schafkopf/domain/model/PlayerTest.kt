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
}
