package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Der Hinweis auf vermutlich doppelt eingetragene Spiele. */
class DuplicateCheckTest {

    private fun suspects(vararg entries: io.github.q0ochen.schafkopf.domain.evening.EveningEntry): Set<String> =
        DuplicateCheck.suspects(evening(*entries)).map { it.value }.toSet()

    @Test
    fun `zwei gleiche Spiele kurz hintereinander - das hintere wird gemeldet`() {
        assertEquals(setOf("zwei"), suspects(game("eins", 600), game("zwei", 630)))
    }

    @Test
    fun `Kennung, Zeitpunkt und Notiz duerfen sich unterscheiden`() {
        assertEquals(setOf("zwei"), suspects(game("eins", 600, note = "schön gespielt"), game("zwei", 601, note = "")))
    }

    @Test
    fun `die Reihenfolge der Uhrzeiten spielt keine Rolle`() {
        // Das zuerst eingetragene Spiel steht nach dem Zusammenführen hinten.
        assertEquals(setOf("zwei"), suspects(game("eins", 660), game("zwei", 600)))
    }

    @Test
    fun `genau zwei Minuten Abstand zaehlen noch, mehr nicht`() {
        assertEquals(setOf("zwei"), suspects(game("eins", 600), game("zwei", 720)))
        assertEquals(emptySet<String>(), suspects(game("eins", 600), game("zwei", 721)))
        assertEquals(
            emptySet<String>(),
            suspects(game("eins", 600), game("zwei", 720).let { it.copy(recordedAt = it.recordedAt.plusMillis(1)) }),
        )
    }

    @Test
    fun `jeder Unterschied im Spiel selbst macht es zu einem anderen Spiel`() {
        val first = game("eins", 600)
        fun second() = game("zwei", 610)

        assertEquals(emptySet<String>(), suspects(first, second().copy(declarerWon = false)))
        assertEquals(emptySet<String>(), suspects(first, second().copy(declarer = P3)))
        assertEquals(emptySet<String>(), suspects(first, second().copy(partner = P4)))
        assertEquals(emptySet<String>(), suspects(first, second().copy(laufende = 3)))
        assertEquals(emptySet<String>(), suspects(first, second().copy(type = GameType.WENZ, partner = null)))
        assertEquals(emptySet<String>(), suspects(first, second().copy(klopfer = setOf(P2))))
        assertEquals(emptySet<String>(), suspects(first, second().copy(kontra = KontraLevel.KONTRA)))
    }

    @Test
    fun `nur direkt aufeinander folgende Spiele werden verglichen`() {
        val between = ThrownInEntry(EntryId("weiter"), START.plusSeconds(605))

        assertEquals(emptySet<String>(), suspects(game("eins", 600), between, game("zwei", 610)))
        assertEquals(emptySet<String>(), suspects(game("eins", 600), game("anders", 605, won = false), game("zwei", 610)))
    }

    @Test
    fun `dreimal dasselbe Spiel meldet das zweite und das dritte`() {
        assertEquals(setOf("zwei", "drei"), suspects(game("eins", 600), game("zwei", 610), game("drei", 620)))
    }

    @Test
    fun `ein Abend ohne Spiele oder mit einem Spiel hat keine Verdachtsfaelle`() {
        assertEquals(emptySet<String>(), suspects())
        assertEquals(emptySet<String>(), suspects(game("eins", 600)))
        assertEquals(
            emptySet<String>(),
            suspects(ThrownInEntry(EntryId("a"), START.plusSeconds(1)), ThrownInEntry(EntryId("b"), START.plusSeconds(2))),
        )
    }

    @Test
    fun `zwei Handys tragen dasselbe Spiel ein - nach dem Zusammenfuehren faellt es auf`() {
        val base = evening(game("g1", 60))
        val mine = game("m", 300, won = false, laufende = 3)
        val theirs = mine.copy(id = EntryId("t"), recordedAt = START.plusSeconds(312))

        val merged = EveningMerge.merge3(base, base.append(mine), base.append(theirs))

        assertEquals(listOf("g1", "m", "t"), merged.ids())
        assertEquals(setOf(EntryId("t")), DuplicateCheck.suspects(merged))
    }
}
