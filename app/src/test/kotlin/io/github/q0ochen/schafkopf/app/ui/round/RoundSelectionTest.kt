package io.github.q0ochen.schafkopf.app.ui.round

import io.github.q0ochen.schafkopf.app.round.RoundProblem
import io.github.q0ochen.schafkopf.app.round.RoundState
import io.github.q0ochen.schafkopf.data.round.Account
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundLink
import io.github.q0ochen.schafkopf.domain.round.RoundMember
import io.github.q0ochen.schafkopf.domain.round.RoundSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wann die Auswahl „Runde“ auf dem Start-Bildschirm steht und was sie zeigt (docs/ARCHITEKTUR.md 8.9). */
class RoundSelectionTest {

    private val account = Account("konto-1", "ich@example.org")
    private val link = RoundLink(RoundId("a"), "Stammtisch", "konto-1", members = listOf(RoundMember("konto-1", null)))
    private val stammtisch = RoundSummary(RoundId("a"), "Stammtisch", rev = 4, memberCount = 2, myPlayerId = null, isCreator = true)
    private val klub = RoundSummary(RoundId("b"), "Klub", rev = 2, memberCount = 3, myPlayerId = null, isCreator = false)
    private val linked = RoundState(configured = true, loaded = true, account = account, link = link)

    @Test
    fun `ohne Runde, ohne gelesene Liste, ohne Anmeldung oder ohne andere Runde gibt es keine Auswahl`() {
        assertNull(roundSelection(linked.copy(link = null, rounds = listOf(stammtisch, klub))))
        assertNull(roundSelection(linked))
        assertNull(roundSelection(linked.copy(rounds = listOf(stammtisch))))
        assertNull(roundSelection(linked.copy(rounds = listOf(stammtisch, klub), problem = RoundProblem.SIGNED_OUT)))
        assertNull(roundSelection(linked.copy(rounds = listOf(stammtisch, klub), account = null)))
    }

    @Test
    fun `die Auswahl zeigt die Runden in der Reihenfolge des Servers, die verbundene ist ausgewaehlt`() {
        val selection = roundSelection(linked.copy(rounds = listOf(klub, stammtisch)))!!

        assertEquals(stammtisch, selection.current)
        assertEquals(listOf(klub, stammtisch), selection.choices)
        assertEquals(emptySet<RoundId>(), selection.duplicates)
        // Auch eine Liste, deren letzte Lesung scheiterte, zählt.
        assertEquals(selection, roundSelection(linked.copy(rounds = listOf(klub, stammtisch), roundsFailed = true)))
    }

    @Test
    fun `fehlt die verbundene Runde in der Liste, steht sie vorn`() {
        val selection = roundSelection(linked.copy(rounds = listOf(klub)))!!

        assertEquals(RoundId("a"), selection.current.id)
        assertEquals("Stammtisch", selection.current.name)
        assertEquals(listOf(RoundId("a"), RoundId("b")), selection.choices.map { it.id })
    }

    @Test
    fun `gleichnamige Runden werden erkannt`() {
        val other = RoundSummary(RoundId("c"), "STAMMTISCH", rev = 1, memberCount = 4, myPlayerId = null, isCreator = false, memberNames = listOf("Anna"))

        val selection = roundSelection(linked.copy(rounds = listOf(stammtisch, klub, other)))!!

        assertEquals(setOf(RoundId("a"), RoundId("c")), selection.duplicates)
    }
}
