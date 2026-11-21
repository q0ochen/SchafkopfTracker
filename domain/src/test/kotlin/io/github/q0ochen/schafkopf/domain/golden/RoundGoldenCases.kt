package io.github.q0ochen.schafkopf.domain.golden

import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import java.time.Instant
import kotlin.random.Random

/** Drei Fassungen eines Abends für `EveningMerge.merge3`; `base` fehlt, wenn das Gerät den Abend nie abgeglichen hat. */
internal data class MergeCase(val id: String, val base: Evening?, val local: Evening, val remote: Evening)

/** Ein Abend für `DuplicateCheck.suspects`. */
internal data class DuplicateCase(val id: String, val evening: Evening)

/** Ein eingefügter Text für `InviteLinks.tokenFrom`. */
internal data class InviteCase(val id: String, val text: String)

/** Namen von Runden: die Runden eines Kontos ([names]) und ein Name, der neu dazukommen soll ([text]). */
internal data class RoundNameCase(val id: String, val names: List<String>, val text: String)

/**
 * Die Eingaben der Golden-Daten für die gemeinsame Runde (docs/ARCHITEKTUR.md, Abschnitt 8.5): gezielte Fälle und
 * Zufallsfälle mit festen Startwerten. Der Port in `web/src/core/round` muss dieselben Ergebnisse liefern.
 */
internal object RoundGoldenCases {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val start: Instant = Instant.parse("2026-12-12T18:00:00Z")

    private fun pad(number: Int): String = number.toString().padStart(2, '0')

    private fun game(id: String, millis: Long, won: Boolean = true, laufende: Int = 0, note: String = ""): GameEntry = GameEntry(
        id = EntryId(id),
        recordedAt = start.plusMillis(millis),
        type = GameType.RUFSPIEL,
        declarer = a,
        partner = b,
        declarerWon = won,
        laufende = laufende,
        note = note,
    )

    private fun evening(vararg entries: EveningEntry): Evening = Evening(
        id = EveningId("abend"),
        startedAt = start,
        seating = listOf(a, b, c, d),
        firstDealer = a,
        rules = RuleSet.standard(),
        entries = entries.toList(),
    )

    // --- Zusammenführen ---

    private fun chosenMerges(): List<MergeCase> {
        val g1 = game("g1", 60_000)
        val g2 = game("g2", 120_000)
        val g3 = game("g3", 180_000)
        val base = evening(g1, g2, g3)
        val thrown = ThrownInEntry(EntryId("w"), start.plusMillis(200_000))
        val bock = BockTriggerEntry(EntryId("b"), start.plusMillis(210_000), BockReason.SECHS_NIXER, player = c)
        val manual = ManualEntry(EntryId("f"), start.plusMillis(220_000), mapOf(a to 50, b to -50), "Runde Schnaps")
        return listOf(
            MergeCase("gleich", base, base, base),
            MergeCase("leer", evening(), evening(), evening()),
            MergeCase("nur-hier-neu", base, base.append(game("m", 300_000)), base),
            MergeCase("nur-dort-neu", base, base, base.append(game("t", 300_000))),
            MergeCase("beide-neu-nach-zeit", base, base.append(game("m", 400_000)), base.append(game("t", 300_000))),
            MergeCase("beide-neu-gleiche-zeit-kennung", base, base.append(game("b", 300_000)), base.append(game("a", 300_000))),
            MergeCase("beide-neu-gleiche-zeit-kennung-2", base, base.append(game("a", 300_000)), base.append(game("b", 300_000))),
            // Reihenfolge der Kennungen nach UTF-16: Ziffern vor Großbuchstaben vor Kleinbuchstaben vor Umlauten.
            MergeCase("kennung-ziffern", base, base.append(game("10", 300_000)), base.append(game("9", 300_000))),
            MergeCase("kennung-gross-klein", base, base.append(game("a", 300_000)), base.append(game("B", 300_000))),
            MergeCase("kennung-umlaut", base, base.append(game("ä", 300_000)), base.append(game("z", 300_000))),
            MergeCase("kennung-emoji", base, base.append(game("😀", 300_000)), base.append(game("～", 300_000))),
            // Zeitpunkte werden als Zeit verglichen, nicht als Text: 18:05:00 liegt vor 18:05:00.500.
            MergeCase("zeit-mit-millisekunden", base, base.append(game("m", 300_500)), base.append(game("t", 300_000))),
            MergeCase("zeit-mit-millisekunden-2", base, base.append(game("m", 300_000)), base.append(game("t", 300_500))),
            MergeCase(
                "zwei-stapel",
                base,
                base.append(game("m1", 200_000)).append(game("m2", 400_000)),
                base.append(game("t1", 300_000)).append(game("t2", 500_000)),
            ),
            MergeCase(
                "eigene-uhr-zurueckgestellt",
                base,
                base.append(game("m1", 400_000)).append(game("m2", 200_000)),
                base.append(game("t", 300_000)),
            ),
            MergeCase("mitten-eingefuegt", base, base.insertAt(1, game("x", 900_000)), base),
            MergeCase("vorn-eingefuegt", base, base.insertAt(0, game("x", 900_000)), base.append(game("t", 300_000))),
            MergeCase("hier-geaendert", base, base.replace(g1.copy(declarerWon = false)), base),
            MergeCase("dort-geaendert", base, base, base.replace(g2.copy(laufende = 3))),
            MergeCase("beide-geaendert-verschieden", base, base.replace(g1.copy(laufende = 4)), base.replace(g1.copy(laufende = 5))),
            MergeCase("beide-geaendert-gleich", base, base.replace(g1.copy(laufende = 4)), base.replace(g1.copy(laufende = 4))),
            MergeCase("nur-notiz-geaendert", base, base.replace(g2.copy(note = "schön")), base.replace(g3.copy(kontra = KontraLevel.KONTRA, kontraBy = c))),
            MergeCase("hier-geloescht", base, base.remove(g2.id), base),
            MergeCase("dort-geloescht", base, base, base.remove(g2.id)),
            MergeCase("beide-geloescht", base, base.remove(g2.id), base.remove(g2.id)),
            MergeCase("hier-geaendert-dort-geloescht", base, base.replace(g2.copy(declarerWon = false)), base.remove(g2.id)),
            MergeCase("hier-geloescht-dort-geaendert", base, base.remove(g2.id), base.replace(g2.copy(declarerWon = false))),
            MergeCase("letzten-geloescht-und-neu", base, base.remove(g3.id).append(game("m", 300_000)), base.append(game("t", 250_000))),
            MergeCase("alles-geloescht", base, evening(), base.append(game("t", 300_000))),
            MergeCase("rueckgaengig-nach-hochladen", evening(g1, g3), base, evening(g1, g3)),
            MergeCase("alle-arten", evening(g1), evening(g1, thrown, manual), evening(g1, bock)),
            MergeCase("freie-buchung-geaendert", evening(g1, manual), evening(g1, manual.copy(amounts = mapOf(a to 70, b to -70))), evening(g1, manual, bock)),
            // Ohne Basis: alles von beiden Seiten, bei gleicher Kennung der Eintrag von hier, der Kopf vom Server.
            MergeCase("ohne-basis", null, evening(g1.copy(laufende = 2), game("m", 300_000)), evening(g1, g2)),
            MergeCase(
                "ohne-basis-kopf",
                null,
                evening(g1).copy(location = "Wirtshaus", finishedAt = start.plusSeconds(7200)),
                evening(g1).copy(location = "Stüberl", firstDealer = b),
            ),
            MergeCase("ohne-basis-gleich", null, base, base),
            // Kopf: je Feld bleibt die Änderung von hier, alles andere kommt vom Server.
            MergeCase(
                "kopf-verschiedene-felder",
                base,
                base.copy(finishedAt = start.plusSeconds(10_800)),
                base.copy(location = "Wirtshaus", firstDealer = c),
            ),
            MergeCase(
                "kopf-dieselben-felder",
                base,
                base.copy(location = "hier", rules = RuleSet.sauspiel(), seating = listOf(d, c, b, a), startedAt = start.plusSeconds(1)),
                base.copy(location = "dort", seating = listOf(b, a, d, c), startedAt = start.plusSeconds(2), firstDealer = d),
            ),
            MergeCase("kopf-wieder-geoeffnet", base.copy(finishedAt = start.plusSeconds(3600)), base, base.copy(finishedAt = start.plusSeconds(3600))),
            MergeCase("kopf-regeln-vom-server", base, base.append(game("m", 300_000)), base.copy(rules = RuleSet.sauspiel())),
            // Beschädigte Daten: doppelte Kennungen ergeben nie doppelte Einträge.
            MergeCase(
                "doppelte-kennungen",
                evening(g1),
                evening(g1, g1.copy(laufende = 7), game("m", 300_000), game("m", 400_000)),
                evening(g1, g1.copy(laufende = 7), g2, g2),
            ),
            // Die Reihenfolge des Servers gilt für alles, was er kennt.
            MergeCase("server-andere-reihenfolge", base, evening(g1, g2, g3, game("m", 300_000)), evening(g3, g1, g2)),
            MergeCase("hier-andere-reihenfolge", base, evening(g2, g1, game("m", 300_000), g3), base),
        )
    }

    private val entryIds = listOf("a", "b", "B", "10", "9", "ä", "😀", "x1", "x2", "x3")

    private fun Random.entry(id: String, millis: Long): EveningEntry = when (nextInt(10)) {
        0 -> ThrownInEntry(EntryId(id), start.plusMillis(millis))
        1 -> BockTriggerEntry(EntryId(id), start.plusMillis(millis), BockReason.entries.random(this), player = listOf(a, b, null).random(this))
        2 -> ManualEntry(EntryId(id), start.plusMillis(millis), mapOf(a to 10, c to -10), "frei")
        else -> game(id, millis, won = nextBoolean(), laufende = nextInt(0, 5))
    }

    /** Ändert einen Abend, wie es ein Gerät kann – und manchmal so, wie es nur beschädigte Daten können. */
    private fun Random.changed(base: Evening, prefix: String): Evening {
        var result = base
        for (entry in base.entries) {
            when (nextInt(7)) {
                0 -> result = result.remove(entry.id)
                1 -> result = result.replace(
                    when (entry) {
                        is GameEntry -> entry.copy(laufende = nextInt(5, 9), note = if (nextBoolean()) "geändert" else "")
                        is ThrownInEntry -> entry.copy(recordedAt = entry.recordedAt.plusMillis(1))
                        is BockTriggerEntry -> entry.copy(handThrownIn = !entry.handThrownIn)
                        is ManualEntry -> entry.copy(note = "geändert")
                    },
                )
            }
        }
        // Neue Einträge: meist hinten angehängt mit fortlaufender Zeit, manchmal mitten hinein oder mit alter Zeit.
        var time = 1_000_000L
        repeat(nextInt(0, 4)) { index ->
            time += when (nextInt(4)) {
                0 -> 0L
                1 -> -nextLong(1, 50_000)
                else -> nextLong(1, 200_000)
            }
            // Manchmal dieselbe Kennung wie auf dem anderen Gerät oder wie ein vorhandener Eintrag.
            val id = if (nextInt(8) == 0) entryIds.random(this) else "$prefix$index"
            val entry = entry(id, time)
            result = if (nextInt(5) == 0) result.insertAt(nextInt(0, result.entries.size + 1), entry) else result.append(entry)
        }
        if (nextInt(4) == 0) result = result.copy(finishedAt = if (result.finishedAt == null) start.plusSeconds(nextLong(3600, 7200)) else null)
        if (nextInt(4) == 0) result = result.copy(location = listOf("", "Wirtshaus", "Stüberl").random(this))
        if (nextInt(6) == 0) result = result.copy(seating = result.seating.shuffled(this))
        if (nextInt(6) == 0) result = result.copy(firstDealer = listOf(a, b, c, d).random(this))
        if (nextInt(6) == 0) result = result.copy(rules = if (result.rules == RuleSet.standard()) RuleSet.sauspiel() else RuleSet.standard())
        if (nextInt(8) == 0) result = result.copy(startedAt = start.plusSeconds(nextLong(-60, 60)))
        return result
    }

    fun mergeCases(): List<MergeCase> {
        val random = Random(20261212)
        val randomCases = List(90) { number ->
            val entries = entryIds.shuffled(random).take(random.nextInt(0, 7)).mapIndexed { index, id -> random.entry(id, 60_000L * (index + 1)) }
            val base = evening(*entries.toTypedArray()).let { if (random.nextInt(5) == 0) it.copy(finishedAt = start.plusSeconds(3600)) else it }
            val local = random.changed(base, "m")
            val remote = random.changed(base, "t")
            // Jeder sechste Fall ohne Basis, wie beim ersten Abgleich eines Abends.
            MergeCase("zufall-${pad(number)}", base.takeIf { number % 6 != 5 }, local, remote)
        }
        return chosenMerges() + randomCases
    }

    // --- Doppelt eingetragene Spiele ---

    fun duplicateCases(): List<DuplicateCase> {
        val first = game("eins", 600_000, won = false, laufende = 3)
        fun second(millis: Long) = first.copy(id = EntryId("zwei"), recordedAt = start.plusMillis(millis))
        val thrown = ThrownInEntry(EntryId("w"), start.plusMillis(605_000))
        val chosen = listOf(
            DuplicateCase("leer", evening()),
            DuplicateCase("ein-spiel", evening(first)),
            DuplicateCase("doppelt", evening(first, second(630_000))),
            DuplicateCase("doppelt-notiz-anders", evening(first.copy(note = "schön"), second(601_000))),
            DuplicateCase("doppelt-zeit-rueckwaerts", evening(first, second(540_000))),
            DuplicateCase("genau-120-sekunden", evening(first, second(720_000))),
            DuplicateCase("120-sekunden-und-1-millisekunde", evening(first, second(720_001))),
            DuplicateCase("genau-120-sekunden-rueckwaerts", evening(first, second(480_000))),
            DuplicateCase("121-sekunden-rueckwaerts", evening(first, second(479_000))),
            DuplicateCase("anderes-ergebnis", evening(first, second(610_000).copy(declarerWon = true))),
            DuplicateCase("andere-laufende", evening(first, second(610_000).copy(laufende = 4))),
            DuplicateCase("anderer-partner", evening(first, second(610_000).copy(partner = c))),
            DuplicateCase("andere-spielart", evening(first, second(610_000).copy(type = GameType.WENZ, partner = null))),
            DuplicateCase("gelegt-anders", evening(first, second(610_000).copy(klopfer = setOf(b)))),
            DuplicateCase("gelegt-gleich-andere-reihenfolge", evening(first.copy(klopfer = setOf(b, c)), second(610_000).copy(klopfer = setOf(c, b)))),
            DuplicateCase("augen-anders", evening(first, second(610_000).copy(declarerEyes = 61))),
            DuplicateCase("nicht-direkt-hintereinander", evening(first, thrown, second(610_000))),
            DuplicateCase("dreimal", evening(first, second(610_000), second(620_000).copy(id = EntryId("drei")))),
            DuplicateCase("zusammengeworfen-doppelt", evening(thrown, thrown.copy(id = EntryId("w2")))),
            DuplicateCase("zwei-paare", evening(first, second(610_000), thrown, game("drei", 900_000), game("vier", 910_000))),
        )
        val random = Random(20261213)
        val randomCases = List(40) { number ->
            // Wenige verschiedene Spiele dicht hintereinander: Da gleichen sich Nachbarn oft.
            val entries = List(random.nextInt(0, 9)) { index ->
                if (random.nextInt(6) == 0) {
                    ThrownInEntry(EntryId("e$index"), start.plusSeconds(60L * index))
                } else {
                    game("e$index", random.nextLong(0, 400_000), won = random.nextBoolean(), laufende = random.nextInt(0, 2), note = if (random.nextBoolean()) "" else "n")
                }
            }
            DuplicateCase("zufall-${pad(number)}", evening(*entries.toTypedArray()))
        }
        return chosen + randomCases
    }

    // --- Einladungscodes ---

    fun inviteCases(): List<InviteCase> {
        val token = "0123456789abcdef0123456789abcdef"
        val other = "f".repeat(32)
        val web = "https://q0ochen.github.io/SchafkopfTracker/"
        return listOf(
            "code" to token,
            "code-mit-leerraum" to "  $token\n",
            "link-web" to "$web#/einladung/$token",
            "link-app" to "schafkopftracker://einladung/$token",
            "link-in-nachricht" to "Servus! Komm in unsere Runde: $web#/einladung/$token\nBis Samstag!",
            "link-mit-punkt" to "$web#/einladung/$token.",
            "link-mit-anhang" to "$web#/einladung/$token?quelle=chat",
            "code-mit-vorsatz" to "Einladungscode: $token",
            "erste-passende-folge" to "abc ${"1".repeat(31)} $token $other",
            "zu-lang-dann-passend" to "${token}0 und $other",
            "leer" to "",
            "nur-leerraum" to "   ",
            "wort" to "Schafkopf",
            "zu-kurz" to token.dropLast(1),
            "zu-lang" to token + "0",
            "grossbuchstaben" to token.uppercase(),
            "falsches-zeichen" to token.replace('0', 'g'),
            "link-zu-kurz" to "$web#/einladung/${token.dropLast(1)}",
            "link-zu-lang" to "$web#/einladung/${token}0",
            "angeklebt" to "Code$token",
            "nur-f" to other,
            "nur-ziffern" to "1".repeat(32),
        ).map { (id, text) -> InviteCase(id, text) }
    }

    /** Bereinigen, gültig, vergeben, Vorschlag und gleichnamige Runden (`RoundNames`, docs/ARCHITEKTUR.md 8.7). */
    fun roundNameCases(): List<RoundNameCase> {
        val joker = "\uD83C\uDCCF"
        return listOf(
            RoundNameCase("leer", emptyList(), ""),
            RoundNameCase("nur-leerraum", listOf("Stammtisch"), " \t\r\n "),
            RoundNameCase("raender-und-tabulator", listOf("Stammtisch Runde"), "  Stammtisch \t  Runde \n"),
            RoundNameCase("gross-klein", listOf("Stammtisch"), "STAMMTISCH"),
            RoundNameCase("umlaute", listOf("Ärger im Wirtshaus"), "ärger im WIRTSHAUS"),
            RoundNameCase("steuerzeichen", emptyList(), "a\u0000\u001Fb\u007F"),
            RoundNameCase("geschuetztes-leerzeichen", listOf("a b"), "a\u00A0b "),
            RoundNameCase("zusammengezogen", listOf("Stamm tisch"), "Stamm  tisch"),
            RoundNameCase("ohne-leerzeichen-anders", listOf("Stamm tisch"), "Stammtisch"),
            RoundNameCase("40-zeichen", emptyList(), "  " + "x".repeat(40) + "  "),
            RoundNameCase("41-zeichen", emptyList(), "x".repeat(41)),
            RoundNameCase("40-umlaute", emptyList(), "Ä" + "ö".repeat(39)),
            RoundNameCase("40-astrale-zeichen", emptyList(), joker.repeat(40)),
            RoundNameCase("41-astrale-zeichen", emptyList(), joker.repeat(41)),
            RoundNameCase("vorschlag-frei", listOf("Stammtisch"), "Klub"),
            RoundNameCase("vorschlag-belegt", listOf("schafkopfrunde", "Schafkopfrunde 2"), "Schafkopfrunde"),
            RoundNameCase("vorschlag-luecke", listOf("Schafkopfrunde", "Schafkopfrunde 3"), "Schafkopfrunde 3"),
            RoundNameCase("doppelte", listOf("Stammtisch", "Klub", "STAMMTISCH ", "Freitag", "freitag"), "Samstag"),
            RoundNameCase("eszett", listOf("Straße"), "STRASSE"),
        )
    }
}
