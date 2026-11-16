package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Ob ein Spieler in einem Abend vorkommt – davon hängt ab, ob er sich löschen lässt. */
class EveningInvolvesTest {

    private val start: Instant = Instant.parse("2026-11-14T19:00:00Z")
    private val seating = listOf(PlayerId("a"), PlayerId("b"), PlayerId("c"), PlayerId("d"))

    private fun evening(vararg entries: EveningEntry, firstDealer: PlayerId = PlayerId("a")) = Evening(
        id = EveningId("abend"),
        startedAt = start,
        seating = seating,
        firstDealer = firstDealer,
        rules = RuleSet.standard(),
        entries = entries.toList(),
    )

    @Test
    fun `wer am Tisch sitzt, kommt vor - auch ohne einen einzigen Eintrag`() {
        assertTrue(evening().involves(PlayerId("c")))
        assertFalse(evening().involves(PlayerId("gast")))
    }

    @Test
    fun `auch wer nur in einem Eintrag steht, kommt vor (beschaedigte Daten)`() {
        val gast = PlayerId("gast")
        val inGame = listOf(
            GameEntry(EntryId("1"), start, GameType.RUFSPIEL, declarer = gast, partner = PlayerId("b"), declarerWon = true),
            GameEntry(EntryId("2"), start, GameType.RUFSPIEL, declarer = PlayerId("a"), partner = gast, declarerWon = true),
            GameEntry(EntryId("3"), start, GameType.WENZ, declarer = PlayerId("a"), declarerWon = true, klopfer = setOf(gast)),
            GameEntry(
                EntryId("4"),
                start,
                GameType.WENZ,
                declarer = PlayerId("a"),
                declarerWon = true,
                kontra = KontraLevel.RETOUR,
                kontraBy = gast,
            ),
            GameEntry(
                EntryId("5"),
                start,
                GameType.WENZ,
                declarer = PlayerId("a"),
                declarerWon = true,
                kontra = KontraLevel.RETOUR,
                retourBy = gast,
            ),
            BockTriggerEntry(EntryId("6"), start, BockReason.SECHS_NIXER, player = gast),
            ManualEntry(EntryId("7"), start, mapOf(gast to 10, PlayerId("a") to -10), "Ausgleich"),
        )
        for (entry in inGame) assertTrue(evening(entry).involves(gast), "Eintrag ${entry.id.value}")
        assertTrue(evening(firstDealer = gast).involves(gast))
    }

    @Test
    fun `Eintraege anderer Spieler und Zusammengeworfen zaehlen nicht`() {
        val gast = PlayerId("gast")
        val others = evening(
            ThrownInEntry(EntryId("1"), start),
            GameEntry(EntryId("2"), start, GameType.RUFSPIEL, declarer = PlayerId("a"), partner = PlayerId("b"), declarerWon = true),
            BockTriggerEntry(EntryId("3"), start, BockReason.SECHS_NIXER, player = null),
            ManualEntry(EntryId("4"), start, mapOf(PlayerId("a") to 10, PlayerId("b") to -10), ""),
        )
        assertFalse(others.involves(gast))
    }
}
