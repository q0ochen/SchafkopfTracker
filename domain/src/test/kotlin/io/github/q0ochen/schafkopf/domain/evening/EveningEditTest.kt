package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant

class EveningEditTest {

    private val a = PlayerId("a")
    private val b = PlayerId("b")
    private val c = PlayerId("c")
    private val d = PlayerId("d")
    private val start: Instant = Instant.parse("2026-11-20T18:30:00Z")

    private val game = GameEntry(
        id = EntryId("spiel-1"),
        recordedAt = start.plusSeconds(600),
        type = GameType.RUFSPIEL,
        declarer = a,
        partner = b,
        declarerWon = true,
    )

    private val evening = Evening(
        id = EveningId("abend"),
        startedAt = start,
        location = "Wirtshaus",
        seating = listOf(a, b, c, d),
        firstDealer = a,
        rules = RuleSet.standard(),
        entries = listOf(game),
    )

    private val original = EveningDetails.of(evening)

    @Test
    fun `ohne Aenderung bleibt der Abend, wie er ist`() {
        assertSame(evening, EveningEdit.apply(evening, original, original))
    }

    @Test
    fun `das neue Datum gilt, die Eintraege behalten ihre Zeiten`() {
        val earlier = Instant.parse("2026-11-13T18:30:00Z")
        val result = EveningEdit.apply(evening, original, original.copy(startedAt = earlier))
        assertEquals(earlier, result.startedAt)
        assertNull(result.finishedAt)
        assertEquals(listOf(game), result.entries)
        assertEquals(evening.copy(startedAt = earlier), result)
    }

    @Test
    fun `ein beendeter Abend behaelt seine Dauer`() {
        val finished = evening.copy(finishedAt = Instant.parse("2026-11-20T23:45:10.5Z"))
        val result = EveningEdit.apply(finished, original, original.copy(startedAt = Instant.parse("2026-11-19T18:30:00.000000001Z")))
        assertEquals(Instant.parse("2026-11-19T23:45:10.500000001Z"), result.finishedAt)
    }

    @Test
    fun `das Ende verschiebt sich vom inzwischen geaenderten Beginn aus`() {
        // Ein anderes Handy hat den Beginn schon um eine Stunde verlegt; hier wird das Datum gewählt.
        val meanwhile = evening.copy(startedAt = start.plusSeconds(3600), finishedAt = start.plusSeconds(7200))
        val result = EveningEdit.apply(meanwhile, original, original.copy(startedAt = start.minusSeconds(86_400)))
        assertEquals(start.minusSeconds(86_400), result.startedAt)
        assertEquals(start.minusSeconds(86_400).plusSeconds(3600), result.finishedAt)
    }

    @Test
    fun `laege das Ende ausserhalb des Zeitbereichs, bleibt es`() {
        val atEnd = evening.copy(finishedAt = Instant.MAX)
        val result = EveningEdit.apply(atEnd, original, original.copy(startedAt = start.plusSeconds(60)))
        assertEquals(start.plusSeconds(60), result.startedAt)
        assertEquals(Instant.MAX, result.finishedAt)
    }

    @Test
    fun `der Ort wird gesaeubert und gekuerzt`() {
        val result = EveningEdit.apply(evening, original, original.copy(location = "  Beim Huber  "))
        assertEquals("Beim Huber", result.location)

        val long = "x".repeat(39) + "  yyy"
        assertEquals("x".repeat(39), EveningEdit.apply(evening, original, original.copy(location = long)).location)
        assertEquals("", EveningEdit.apply(evening, original, original.copy(location = "   ")).location)
    }

    @Test
    fun `cleanLocation entfernt Leerraum wie Kotlin und kuerzt auf 40 Zeichen`() {
        assertEquals("Ort", EveningEdit.cleanLocation("\tOrt\n"))
        assertEquals(EveningDetails.MAX_LOCATION_LENGTH, EveningEdit.cleanLocation("y".repeat(60)).length)
        // U+FEFF zählt in Kotlin nicht als Leerraum.
        assertEquals("﻿Ort", EveningEdit.cleanLocation("﻿Ort "))
    }

    @Test
    fun `nur Geaendertes wird uebernommen, anderes bleibt wie inzwischen`() {
        // Ein anderes Handy hat inzwischen den Ort und den ersten Geber geändert; hier nur die Sitzordnung.
        val meanwhile = evening.copy(location = "Beim Huber", firstDealer = c)
        val result = EveningEdit.apply(meanwhile, original, original.copy(seating = listOf(b, a, c, d)))
        assertEquals("Beim Huber", result.location)
        assertEquals(c, result.firstDealer)
        assertEquals(listOf(b, a, c, d), result.seating)
        assertEquals(start, result.startedAt)
    }

    @Test
    fun `eine neue Sitzordnung muss dieselben Spieler haben, jeden einmal`() {
        val other = PlayerId("e")
        for (wrong in listOf(listOf(a, b, c, other), listOf(a, b, c), listOf(a, a, b, c), listOf(a, b, c, d, d), emptyList())) {
            assertEquals(evening.seating, EveningEdit.apply(evening, original, original.copy(seating = wrong)).seating, "$wrong")
        }
        assertEquals(listOf(d, c, b, a), EveningEdit.apply(evening, original, original.copy(seating = listOf(d, c, b, a))).seating)
    }

    @Test
    fun `die neue Sitzordnung gilt auch, wenn ein anderes Handy sie schon umgestellt hat`() {
        val meanwhile = evening.copy(seating = listOf(a, c, b, d))
        assertEquals(listOf(d, a, b, c), EveningEdit.apply(meanwhile, original, original.copy(seating = listOf(d, a, b, c))).seating)
    }

    @Test
    fun `der erste Geber muss am Tisch sitzen`() {
        assertEquals(c, EveningEdit.apply(evening, original, original.copy(firstDealer = c)).firstDealer)
        assertEquals(a, EveningEdit.apply(evening, original, original.copy(firstDealer = PlayerId("e"))).firstDealer)
    }

    @Test
    fun `alles auf einmal`() {
        val edited = EveningDetails(
            startedAt = Instant.parse("2026-11-14T19:00:00Z"),
            location = "Daheim",
            seating = listOf(c, d, a, b),
            firstDealer = d,
        )
        val result = EveningEdit.apply(evening, original, edited)
        assertEquals(EveningDetails.of(result), edited)
        assertEquals(evening.entries, result.entries)
        assertEquals(evening.rules, result.rules)
        assertEquals(evening.id, result.id)
    }

    @Test
    fun `EveningDetails of liest die vier Angaben`() {
        assertEquals(EveningDetails(start, "Wirtshaus", listOf(a, b, c, d), a), EveningDetails.of(evening))
    }
}
