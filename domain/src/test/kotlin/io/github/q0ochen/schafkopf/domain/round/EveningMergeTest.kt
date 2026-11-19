package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Das Zusammenführen zweier Fassungen eines Abends (docs/ARCHITEKTUR.md, Abschnitt 8.5). */
class EveningMergeTest {

    private val g1 = game("g1", 60)
    private val g2 = game("g2", 120)
    private val g3 = game("g3", 180)

    // --- Einträge: neu, geändert, gelöscht ---

    @Test
    fun `gleichzeitig auf zwei Handys eingetragene Spiele stehen in der Reihenfolge ihrer Uhrzeit`() {
        val base = evening(g1)
        val mine = game("m", 200)
        val theirs = game("t", 190)

        val merged = EveningMerge.merge3(base, evening(g1, mine), evening(g1, theirs))

        assertEquals(listOf("g1", "t", "m"), merged.ids())
        // Egal, wer zuerst abgleicht: Am Ende steht dieselbe Liste.
        assertEquals(merged, EveningMerge.merge3(base, evening(g1, theirs), evening(g1, mine)))
    }

    @Test
    fun `bei gleicher Uhrzeit entscheidet die Kennung`() {
        val base = evening(g1)
        val first = game("a", 200)
        val second = game("b", 200)

        assertEquals(listOf("g1", "a", "b"), EveningMerge.merge3(base, evening(g1, second), evening(g1, first)).ids())
        assertEquals(listOf("g1", "a", "b"), EveningMerge.merge3(base, evening(g1, first), evening(g1, second)).ids())
    }

    @Test
    fun `mehrere neue Spiele beider Seiten werden wie zwei sortierte Stapel gemischt`() {
        val base = evening(g1)
        val local = evening(g1, game("m1", 200), game("m2", 400))
        val remote = evening(g1, game("t1", 300), game("t2", 500))

        assertEquals(listOf("g1", "m1", "t1", "m2", "t2"), EveningMerge.merge3(base, local, remote).ids())
        assertEquals(listOf("g1", "m1", "t1", "m2", "t2"), EveningMerge.merge3(base, remote, local).ids())
    }

    @Test
    fun `die eigene Reihenfolge bleibt, auch wenn die eigene Uhr zwischendurch zurueckgestellt wurde`() {
        val base = evening(g1)
        // m2 wurde nach m1 eingetragen, trägt aber die frühere Zeit.
        val local = evening(g1, game("m1", 400), game("m2", 200))
        val remote = evening(g1, game("t", 300))

        assertEquals(listOf("g1", "t", "m1", "m2"), EveningMerge.merge3(base, local, remote).ids())
    }

    @Test
    fun `ein neues Spiel bleibt hinter dem Eintrag, hinter dem es hier steht`() {
        val base = evening(g1, g2, g3)
        // Nach dem Löschen und „Rückgängig“ steht x wieder zwischen g1 und g2, hat aber die späteste Zeit.
        val local = evening(g1, game("x", 900), g2, g3)

        assertEquals(listOf("g1", "x", "g2", "g3"), EveningMerge.merge3(base, local, base).ids())
    }

    @Test
    fun `ein neuer Eintrag ganz vorn bleibt vorn`() {
        val base = evening(g1, g2)
        val local = evening(game("x", 900), g1, g2)
        val remote = evening(g1, g2, game("t", 300))

        assertEquals(listOf("x", "g1", "g2", "t"), EveningMerge.merge3(base, local, remote).ids())
    }

    @Test
    fun `eine Aenderung von hier bleibt, eine Aenderung des Servers kommt an`() {
        val base = evening(g1, g2)
        val mine = g1.copy(declarerWon = false)
        val theirs = g2.copy(laufende = 3)

        val merged = EveningMerge.merge3(base, evening(mine, g2), evening(g1, theirs))

        assertEquals(listOf(mine, theirs), merged.entries)
    }

    @Test
    fun `aendern beide denselben Eintrag, gewinnt die Aenderung von hier`() {
        val base = evening(g1)
        val mine = g1.copy(laufende = 4)
        val theirs = g1.copy(laufende = 5)

        assertEquals(listOf(mine), EveningMerge.merge3(base, evening(mine), evening(theirs)).entries)
        assertEquals(listOf(theirs), EveningMerge.merge3(base, evening(theirs), evening(mine)).entries)
    }

    @Test
    fun `haben beide dasselbe geaendert, ist das kein Konflikt`() {
        val base = evening(g1)
        val changed = g1.copy(laufende = 4)

        assertEquals(listOf(changed), EveningMerge.merge3(base, evening(changed), evening(changed)).entries)
    }

    @Test
    fun `hier geloescht und auf dem Server unveraendert bleibt geloescht`() {
        val base = evening(g1, g2, g3)

        assertEquals(listOf("g1", "g3"), EveningMerge.merge3(base, evening(g1, g3), base).ids())
    }

    @Test
    fun `auf dem Server geloescht und hier unveraendert entfaellt`() {
        val base = evening(g1, g2, g3)

        assertEquals(listOf("g1", "g3"), EveningMerge.merge3(base, base, evening(g1, g3)).ids())
    }

    @Test
    fun `eine Aenderung gewinnt gegen das Loeschen, in beiden Richtungen`() {
        val base = evening(g1, g2, g3)
        val changed = g2.copy(declarerWon = false)

        // Hier geändert, auf dem Server gelöscht: Der Eintrag bleibt an seiner Stelle.
        assertEquals(listOf(g1, changed, g3), EveningMerge.merge3(base, evening(g1, changed, g3), evening(g1, g3)).entries)
        // Hier gelöscht, auf dem Server geändert: Der Eintrag kommt zurück.
        assertEquals(listOf(g1, changed, g3), EveningMerge.merge3(base, evening(g1, g3), evening(g1, changed, g3)).entries)
    }

    @Test
    fun `loeschen beide denselben Eintrag, bleibt er geloescht`() {
        val base = evening(g1, g2)

        assertEquals(listOf("g1"), EveningMerge.merge3(base, evening(g1), evening(g1)).ids())
    }

    @Test
    fun `geloescht, hochgeladen und dann Rueckgaengig bringt den Eintrag an seine Stelle zurueck`() {
        // Der Server kennt schon die Fassung ohne g2; hier wurde g2 danach wieder eingefügt.
        val base = evening(g1, g3)

        assertEquals(listOf("g1", "g2", "g3"), EveningMerge.merge3(base, evening(g1, g2, g3), base).ids())
    }

    @Test
    fun `alle Arten von Eintraegen werden zusammengefuehrt`() {
        val thrown = ThrownInEntry(EntryId("w"), START.plusSeconds(200))
        val bock = BockTriggerEntry(EntryId("b"), START.plusSeconds(210), BockReason.SECHS_NIXER, player = P3)
        val manual = ManualEntry(EntryId("f"), START.plusSeconds(220), mapOf(P1 to 50, P2 to -50), "Runde Schnaps")
        val base = evening(g1)

        val merged = EveningMerge.merge3(base, evening(g1, thrown, manual), evening(g1, bock))

        assertEquals(listOf<EveningEntry>(g1, thrown, bock, manual), merged.entries)
    }

    // --- Ohne Basis ---

    @Test
    fun `ohne Basis bleibt alles von beiden Seiten, bei gleicher Kennung der Eintrag von hier`() {
        val mine = g1.copy(laufende = 2)
        val local = evening(mine, game("m", 300))
        val remote = evening(g1, g2)

        val merged = EveningMerge.merge3(null, local, remote)

        assertEquals(listOf("g1", "g2", "m"), merged.ids())
        assertEquals(mine, merged.entries.first())
    }

    @Test
    fun `ohne Basis gilt der Kopf des Servers`() {
        val local = evening(g1).copy(location = "Wirtshaus", finishedAt = START.plusSeconds(7200))
        val remote = evening(g1).copy(location = "Stüberl", firstDealer = P2)

        val merged = EveningMerge.merge3(null, local, remote)

        assertEquals(remote.copy(entries = listOf(g1)), merged)
    }

    // --- Kopf ---

    @Test
    fun `im Kopf bleibt je Feld die Aenderung von hier, alles andere kommt vom Server`() {
        val base = evening(g1)
        val finished = START.plusSeconds(10_800)
        val local = base.copy(finishedAt = finished)
        val remote = base.copy(location = "Wirtshaus", firstDealer = P3)

        val merged = EveningMerge.merge3(base, local, remote)

        assertEquals(finished, merged.finishedAt)
        assertEquals("Wirtshaus", merged.location)
        assertEquals(P3, merged.firstDealer)
        assertEquals(base.seating, merged.seating)
    }

    @Test
    fun `aendern beide dasselbe Feld im Kopf, gewinnt das von hier`() {
        val base = evening()
        val local = base.copy(location = "hier", rules = RuleSet.sauspiel(), seating = listOf(P4, P3, P2, P1), startedAt = START.plusSeconds(1))
        val remote = base.copy(location = "dort", rules = base.rules, seating = listOf(P2, P1, P4, P3), startedAt = START.plusSeconds(2))

        assertEquals(local, EveningMerge.merge3(base, local, remote))
        assertEquals(remote, EveningMerge.merge3(base, remote, local).copy(rules = base.rules))
    }

    @Test
    fun `ein hier wieder geoeffneter Abend bleibt offen`() {
        val base = evening(g1).copy(finishedAt = START.plusSeconds(3600))
        val local = base.copy(finishedAt = null)

        assertEquals(null, EveningMerge.merge3(base, local, base).finishedAt)
    }

    // --- Beschädigte Daten ---

    @Test
    fun `doppelte Kennungen ergeben nie doppelte Eintraege`() {
        val twice = g1.copy(laufende = 7)
        val merged = EveningMerge.merge3(evening(g1), evening(g1, twice, game("m", 300), game("m", 400)), evening(g1, twice, g2, g2))

        assertEquals(listOf("g1", "g2", "m"), merged.ids())
    }

    // --- Gesetze, mit vielen zufälligen Fällen geprüft ---

    @Test
    fun `nichts Neues geht verloren und die Reihenfolge des Servers bleibt`() = repeatRandom { base, local, remote ->
        val merged = EveningMerge.merge3(base, local, remote)
        val ids = merged.ids()

        assertEquals(ids.size, ids.toSet().size, "doppelte Kennungen")
        val known = base.ids().toSet()
        assertTrue(ids.containsAll(local.ids().filter { it !in known }), "ein hier neuer Eintrag fehlt")
        assertTrue(ids.containsAll(remote.ids().filter { it !in known }), "ein neuer Eintrag des Servers fehlt")
        // Was der Server kennt, steht in seiner Reihenfolge.
        val kept = ids.filter { it in remote.ids() }
        assertEquals(remote.ids().filter { it in kept }, kept)
        // Was nur hier steht, bleibt in der Reihenfolge von hier.
        val own = ids.filter { it !in remote.ids() }
        assertEquals(local.ids().filter { it in own }, own)
    }

    @Test
    fun `ist eine Seite unveraendert, gilt die andere`() = repeatRandom { base, local, remote ->
        assertEquals(local, EveningMerge.merge3(base, local, local))
        assertEquals(remote, EveningMerge.merge3(base, base, remote))
        assertEquals(local, EveningMerge.merge3(base, local, base))
        assertEquals(base, EveningMerge.merge3(base, base, base))
    }

    @Test
    fun `noch einmal mit derselben Fassung des Servers zusammengefuehrt aendert sich nichts`() = repeatRandom { base, local, remote ->
        val merged = EveningMerge.merge3(base, local, remote)

        assertEquals(merged, EveningMerge.merge3(base, merged, remote))
        // Nach dem Abgleich ist die Fassung des Servers die neue Basis.
        assertEquals(merged, EveningMerge.merge3(remote, merged, remote))
    }

    @Test
    fun `ohne Konflikt ist es gleich, wer zuerst abgleicht`() = repeatRandom(conflicts = false) { base, local, remote ->
        // Das eine Handy lädt hoch, das andere führt zusammen – und umgekehrt.
        assertEquals(EveningMerge.merge3(base, local, remote), EveningMerge.merge3(base, remote, local))
    }

    @Test
    fun `nach dem Hochladen uebernehmen alle anderen die Fassung des Servers`() = repeatRandom { base, local, remote ->
        // Handy 1 hat `remote` hochgeladen, Handy 2 führt zusammen und lädt hoch, Handy 1 holt das Ergebnis.
        val merged = EveningMerge.merge3(base, local, remote)

        assertEquals(merged, EveningMerge.merge3(remote, remote, merged))
    }

    /** Lässt [check] mit 400 zufälligen, aber bei jedem Lauf gleichen Fällen laufen. */
    private fun repeatRandom(conflicts: Boolean = true, check: (base: Evening, local: Evening, remote: Evening) -> Unit) {
        val random = Random(20261212)
        repeat(400) { round ->
            val base = evening(*Array(random.nextInt(0, 7)) { game("g$it", 60L * (it + 1)) })
            val forLocal = base.entries.filterIndexed { index, _ -> !conflicts && index % 2 == 0 || conflicts }
            val forRemote = base.entries.filterIndexed { index, _ -> !conflicts && index % 2 == 1 || conflicts }
            val local = change(base, random, "m$round", editable = forLocal, header = conflicts || round % 2 == 0)
            val remote = change(base, random, "t$round", editable = forRemote, header = conflicts || round % 2 == 1)
            check(base, local, remote)
        }
    }

    /** Ändert einen Abend so, wie es die App kann: Einträge ändern, löschen und neue anhängen, den Abend beenden. */
    private fun change(base: Evening, random: Random, prefix: String, editable: List<EveningEntry>, header: Boolean): Evening {
        var result = base
        for (entry in editable) {
            when (random.nextInt(6)) {
                0 -> result = result.remove(entry.id)
                1 -> result = result.replace((entry as GameEntry).copy(laufende = random.nextInt(1, 9)))
            }
        }
        // Neue Einträge trägt jedes Handy mit fortlaufender Zeit ein.
        var time = 1000L
        repeat(random.nextInt(0, 4)) { index ->
            time += random.nextLong(1, 200)
            result = result.append(game("$prefix-$index", time))
        }
        if (header && random.nextInt(4) == 0) result = result.copy(finishedAt = START.plusSeconds(random.nextLong(3600, 7200)))
        if (header && random.nextInt(4) == 0) result = result.copy(location = prefix)
        return result
    }
}
