package io.github.q0ochen.schafkopf.domain.golden

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningDetails
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import java.time.Instant
import kotlin.random.Random

/** Ein Abend, der Stand beim Öffnen von „Abend bearbeiten“ und das Bearbeitete, für `EveningEdit.apply`. */
internal data class EditCase(val id: String, val current: Evening, val original: EveningDetails, val edited: EveningDetails)

/**
 * Die Eingaben der Golden-Daten für „Abend bearbeiten“ (docs/ARCHITEKTUR.md, Abschnitt 2, „Abend“): gezielte Fälle,
 * auch zur Schreibweise von Zeitpunkten (Bruchteile, Jahre über 9999 und vor 0, Ende des Zeitbereichs), und
 * Zufallsfälle mit festem Startwert. Der Port in `web/src/core/evening/EveningEdit.ts` muss dieselben Ergebnisse liefern.
 */
internal object EditGoldenCases {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val start: Instant = Instant.parse("2026-12-12T18:00:00Z")

    private val evening = Evening(
        id = EveningId("abend"),
        startedAt = start,
        location = "Wirtshaus",
        seating = listOf(a, b, c, d),
        firstDealer = a,
        rules = RuleSet.standard(),
        entries = listOf(
            GameEntry(
                id = EntryId("spiel-1"),
                recordedAt = start.plusSeconds(900),
                type = GameType.RUFSPIEL,
                declarer = a,
                partner = b,
                declarerWon = true,
            ),
        ),
    )

    private val original = EveningDetails.of(evening)

    private fun instant(text: String): Instant = Instant.parse(text)

    fun cases(): List<EditCase> = targeted() + randomCases()

    private fun targeted(): List<EditCase> {
        val finished = evening.copy(finishedAt = instant("2026-12-12T23:45:10.5Z"))
        return listOf(
            EditCase("unverändert", evening, original, original),
            EditCase("datum-laufend", evening, original, original.copy(startedAt = instant("2026-12-05T18:00:00Z"))),
            EditCase("datum-beendet", finished, original, original.copy(startedAt = instant("2026-12-11T18:00:00Z"))),
            EditCase(
                "datum-beendet-nanosekunden",
                finished.copy(startedAt = instant("2026-12-12T18:00:00.123456789Z")),
                original,
                original.copy(startedAt = instant("2026-12-11T19:00:00.123Z")),
            ),
            EditCase(
                "datum-beendet-mikrosekunden",
                finished.copy(finishedAt = instant("2026-12-12T23:00:00.000001Z")),
                original,
                original.copy(startedAt = instant("2026-12-13T18:00:00.000002Z")),
            ),
            EditCase(
                "datum-inzwischen-verschoben",
                finished.copy(startedAt = start.plusSeconds(3600)),
                original,
                original.copy(startedAt = instant("2026-12-10T18:00:00Z")),
            ),
            EditCase(
                "datum-ende-im-jahr-10000",
                evening.copy(startedAt = instant("2026-01-01T00:00:00Z"), finishedAt = instant("9999-12-31T23:00:00Z")),
                EveningDetails.of(evening.copy(startedAt = instant("2026-01-01T00:00:00Z"))),
                original.copy(startedAt = instant("2026-01-03T00:00:00Z")),
            ),
            EditCase(
                "datum-vor-dem-jahr-0",
                evening.copy(startedAt = instant("0001-01-01T10:00:00Z"), finishedAt = instant("0001-01-01T12:00:00.25Z")),
                EveningDetails.of(evening.copy(startedAt = instant("0001-01-01T10:00:00Z"))),
                original.copy(startedAt = instant("-0001-12-30T10:00:00Z")),
            ),
            EditCase(
                "datum-ende-am-rand",
                evening.copy(finishedAt = Instant.MAX),
                original,
                original.copy(startedAt = start.plusSeconds(60)),
            ),
            EditCase(
                "datum-ende-am-anfang",
                evening.copy(finishedAt = Instant.MIN),
                original,
                original.copy(startedAt = start.minusSeconds(60)),
            ),
            EditCase("datum-unverändert-inzwischen-geändert", evening.copy(startedAt = start.plusSeconds(60)), original, original),
            EditCase("ort-gesäubert", evening, original, original.copy(location = "  Beim Huber  ")),
            EditCase("ort-gekürzt", evening, original, original.copy(location = "x".repeat(39) + "  yyy")),
            EditCase("ort-zu-lang", evening, original, original.copy(location = "Gasthaus " + "z".repeat(50))),
            EditCase("ort-sonderzeichen", evening, original, original.copy(location = " ﻿Ort \u001c")),
            EditCase("ort-leer", evening, original, original.copy(location = " \t ")),
            EditCase("ort-unverändert-inzwischen-geändert", evening.copy(location = "Daheim"), original, original),
            EditCase("sitzordnung-umgestellt", evening, original, original.copy(seating = listOf(b, a, d, c))),
            EditCase("sitzordnung-fremder-spieler", evening, original, original.copy(seating = listOf(a, b, c, PlayerId("E")))),
            EditCase("sitzordnung-doppelt", evening, original, original.copy(seating = listOf(a, a, b, c))),
            EditCase("sitzordnung-zu-kurz", evening, original, original.copy(seating = listOf(a, b, c))),
            EditCase("sitzordnung-zu-lang", evening, original, original.copy(seating = listOf(a, b, c, d, a))),
            EditCase("sitzordnung-inzwischen-umgestellt", evening.copy(seating = listOf(a, c, b, d)), original, original.copy(seating = listOf(d, c, b, a))),
            EditCase("geber-neu", evening, original, original.copy(firstDealer = c)),
            EditCase("geber-nicht-am-tisch", evening, original, original.copy(firstDealer = PlayerId("E"))),
            EditCase("geber-unverändert-inzwischen-geändert", evening.copy(firstDealer = d), original, original),
            EditCase("geber-und-sitzordnung", evening, original, original.copy(seating = listOf(c, d, a, b), firstDealer = b)),
            EditCase(
                "kaputte-sitzordnung-am-tisch",
                evening.copy(seating = listOf(a, b, c)),
                original,
                original.copy(seating = listOf(b, a, c, d), firstDealer = c),
            ),
            EditCase(
                "alles",
                finished,
                original,
                EveningDetails(instant("2026-12-06T19:30:00Z"), "Daheim", listOf(c, d, a, b), d),
            ),
        )
    }

    /** Zufällige Abende und Änderungen; ein Teil der Änderungen ist absichtlich ungültig. */
    private fun randomCases(): List<EditCase> {
        val random = Random(20261121)
        val table = listOf(a, b, c, d)
        val locations = listOf("", "Wirtshaus", " Beim Huber ", "Daheim", "x".repeat(45), " Stammtisch ")
        fun Random.instant(): Instant =
            Instant.ofEpochSecond(nextLong(1_577_836_800L, 1_893_456_000L), if (nextBoolean()) 0L else nextLong(0, 1_000_000_000))
        fun Random.seating(): List<PlayerId> = when (nextInt(6)) {
            0 -> listOf(a, b, c, PlayerId("E"))
            1 -> listOf(a, b, c)
            else -> table.shuffled(this)
        }
        fun Random.details(): EveningDetails = EveningDetails(
            startedAt = instant(),
            location = locations.random(this),
            seating = seating(),
            firstDealer = (table + PlayerId("E")).random(this),
        )
        return List(40) { number ->
            val current = evening.copy(
                startedAt = random.instant(),
                finishedAt = if (random.nextBoolean()) random.instant() else null,
                location = locations.random(random),
                seating = table.shuffled(random),
                firstDealer = table.random(random),
            )
            val original = if (random.nextBoolean()) EveningDetails.of(current) else random.details()
            // Jede Angabe mal geändert, mal nicht.
            val changes = random.details()
            val edited = EveningDetails(
                startedAt = if (random.nextBoolean()) changes.startedAt else original.startedAt,
                location = if (random.nextBoolean()) changes.location else original.location,
                seating = if (random.nextBoolean()) changes.seating else original.seating,
                firstDealer = if (random.nextBoolean()) changes.firstDealer else original.firstDealer,
            )
            EditCase("zufall-${number.toString().padStart(2, '0')}", current, original, edited)
        }
    }
}
