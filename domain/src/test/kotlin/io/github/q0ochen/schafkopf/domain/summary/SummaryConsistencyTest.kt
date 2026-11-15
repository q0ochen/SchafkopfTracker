package io.github.q0ochen.schafkopf.domain.summary

import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.stats.RandomEvenings
import io.github.q0ochen.schafkopf.domain.stats.SampleEvening
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Prüft an vielen zufälligen Abenden, dass die Teile der Zusammenfassung zueinander passen: Fakten, Vorlage,
 * JSON für das Sprachmodell und die Aufbereitung der Antwort.
 */
class SummaryConsistencyTest {

    private val seating = listOf(SampleEvening.a, SampleEvening.b, SampleEvening.c, SampleEvening.d)
    private val names = listOf("Anna", "Bert", "Chris", "Dora")

    /** „mit 150 Punkte“ oder „bei 0 Punkte“ wäre falsch: Hinter „mit“ und „bei“ muss „Punkten“ stehen. */
    private val wrongCase = Regex("""\b(?:mit|bei)(?: je)? [+\x{2212}]?\d+ Punkte(?!n)""")

    @Test
    fun `Fakten, Vorlage, JSON und Aufbereitung passen bei zufaelligen Abenden zusammen`() {
        val random = Random(1516)
        repeat(300) { round ->
            val evening = RandomEvenings.evening(random, "zufall-$round", seating).copy(location = "Stüberl")
            val state = EveningCalculator.compute(evening)
            val facts = SummaryFactsBuilder.build(state, SampleEvening.names, SampleEvening.zone)

            // Die Fakten sind in sich stimmig.
            val allEqual = state.balances.values.toSet().size == 1
            assertEquals(state.gamesPlayed, facts.gamesPlayed)
            assertEquals(names, facts.players.map { it.name })
            assertEquals(names.toSet(), facts.standings.map { it.player.name }.toSet())
            assertEquals(facts.standings.map { it.rank }.sorted(), facts.standings.map { it.rank })
            assertEquals(allEqual, facts.winner == null)
            assertEquals(allEqual, facts.payer == null)
            assertEquals(allEqual, facts.transfers.isEmpty())
            assertEquals(state.gamesPlayed == 0, facts.gameOfTheEvening == null)

            // Die Vorlage hat immer 6 bis 12 Zeilen und beginnt mit Datum und Ort.
            val text = TemplateSummary.render(facts)
            val lines = text.split("\n")
            assertTrue(lines.size in 6..12, "Abend $round hat ${lines.size} Zeilen")
            assertTrue(lines.all { it.isNotBlank() })
            assertEquals("Schafkopf am 01.01.2026, Stüberl", lines.first())

            // Das JSON ist gültig; ohne echte Namen verlässt kein Name das Gerät, der Ort nie.
            val hidden = SummaryPrompt.payload(facts, useRealNames = false)
            val real = SummaryPrompt.payload(facts, useRealNames = true)
            assertTrue(Json.parseToJsonElement(hidden.userText) is JsonObject)
            assertTrue(Json.parseToJsonElement(real.userText) is JsonObject)
            assertEquals(4, hidden.placeholders.size)
            for (secret in names + "Stüberl") assertFalse(hidden.userText.contains(secret), "Im JSON steht: $secret")
            assertFalse(real.userText.contains("Stüberl"))

            // Die Vorlage nennt nur Beträge aus den Fakten: Sie übersteht die Aufbereitung unverändert und ohne Warnung.
            assertEquals(FinishedSummary(text, emptySet()), SummaryPrompt.finish(text, real))
            // Stehen statt der Namen Platzhalter im Text, kommt nach dem Einsetzen derselbe Text heraus.
            val masked = names.foldIndexed(text) { index, current, name -> current.replace(name, "[[S${index + 1}]]") }
            assertFalse(names.any { it in masked })
            assertEquals(FinishedSummary(text, emptySet()), SummaryPrompt.finish(masked, hidden))

            // Derselbe Abend um Punkte: gleich viele Zeilen, kein Euro-Betrag, und hinter „mit“ und „bei“
            // steht der Wemfall („mit +150 Punkten“).
            val pointFacts = SummaryFactsBuilder.build(
                EveningCalculator.compute(evening.copy(rules = evening.rules.copy(displayAsMoney = false))),
                SampleEvening.names,
                SampleEvening.zone,
            )
            val pointText = TemplateSummary.render(pointFacts)
            assertEquals(lines.size, pointText.split("\n").size)
            assertFalse(pointText.contains("€"))
            assertFalse(wrongCase.containsMatchIn(pointText), "Falscher Fall in Abend $round: $pointText")
            assertEquals(
                FinishedSummary(pointText, emptySet()),
                SummaryPrompt.finish(pointText, SummaryPrompt.payload(pointFacts, useRealNames = true)),
            )
        }
    }
}
