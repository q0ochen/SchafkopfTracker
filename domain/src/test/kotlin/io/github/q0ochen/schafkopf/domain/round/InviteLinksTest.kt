package io.github.q0ochen.schafkopf.domain.round

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Einladungscodes aus Links und eingefügtem Text. */
class InviteLinksTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val web = "https://q0ochen.github.io/SchafkopfTracker/"

    @Test
    fun `der blosse Code wird erkannt, auch mit Leerraum drumherum`() {
        assertEquals(token, InviteLinks.tokenFrom(token))
        assertEquals(token, InviteLinks.tokenFrom("  $token\n"))
    }

    @Test
    fun `der Link zur Web-App und der Link in die App werden erkannt`() {
        assertEquals(token, InviteLinks.tokenFrom("$web#/einladung/$token"))
        assertEquals(token, InviteLinks.tokenFrom("schafkopftracker://einladung/$token"))
    }

    @Test
    fun `der Link wird auch mitten in einer Nachricht gefunden`() {
        val message = "Servus! Komm in unsere Runde: $web#/einladung/$token\nBis Samstag!"

        assertEquals(token, InviteLinks.tokenFrom(message))
        assertEquals(token, InviteLinks.tokenFrom("$web#/einladung/$token."))
        assertEquals(token, InviteLinks.tokenFrom("$web#/einladung/$token?quelle=chat"))
        assertEquals(token, InviteLinks.tokenFrom("Einladungscode: $token"))
    }

    @Test
    fun `stehen mehrere Folgen im Text, zaehlt die erste mit genau 32 Zeichen`() {
        val other = "f".repeat(32)

        assertEquals(token, InviteLinks.tokenFrom("abc ${"1".repeat(31)} $token $other"))
        assertEquals(other, InviteLinks.tokenFrom("${token}0 und $other"))
    }

    @Test
    fun `alles andere ist kein Code`() {
        assertNull(InviteLinks.tokenFrom(""))
        assertNull(InviteLinks.tokenFrom("   "))
        assertNull(InviteLinks.tokenFrom("Schafkopf"))
        assertNull(InviteLinks.tokenFrom(token.dropLast(1)))
        assertNull(InviteLinks.tokenFrom(token + "0"))
        assertNull(InviteLinks.tokenFrom(token.uppercase()))
        assertNull(InviteLinks.tokenFrom(token.replace('0', 'g')))
        assertNull(InviteLinks.tokenFrom("$web#/einladung/${token.dropLast(1)}"))
        assertNull(InviteLinks.tokenFrom("$web#/einladung/${token}0"))
        assertNull(InviteLinks.tokenFrom("Code$token"))
    }

    @Test
    fun `die erzeugten Links lassen sich wieder lesen`() {
        assertEquals("https://q0ochen.github.io/SchafkopfTracker/#/einladung/$token", InviteLinks.webLink(web, token))
        assertEquals(InviteLinks.webLink(web, token), InviteLinks.webLink(web.trimEnd('/'), token))
        assertEquals("schafkopftracker://einladung/$token", InviteLinks.appLink(token))
        assertEquals(token, InviteLinks.tokenFrom(InviteLinks.webLink(web, token)))
        assertEquals(token, InviteLinks.tokenFrom(InviteLinks.appLink(token)))
    }
}
