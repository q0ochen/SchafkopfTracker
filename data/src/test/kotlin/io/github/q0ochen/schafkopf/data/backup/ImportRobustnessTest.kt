package io.github.q0ochen.schafkopf.data.backup

import io.github.q0ochen.schafkopf.data.storage.StorageCodec
import io.github.q0ochen.schafkopf.data.storage.TestData
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.SettleUp
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

/**
 * Sicherheitsnetz für den Import. Aus einer gültigen Sicherung entstehen einige tausend verfälschte Fassungen, in
 * denen jeweils genau eine Stelle ersetzt, entfernt oder verdoppelt ist: so, wie eine von Hand bearbeitete, eine
 * beschädigte oder eine fremde Datei aussehen kann.
 *
 * Keine davon darf den Import zum Absturz bringen. Und jeder Abend, der trotzdem übernommen wird, muss sich danach
 * speichern, durchrechnen und als Tabelle ausgeben lassen.
 */
class ImportRobustnessTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC)

    /** Eine verfälschte Sicherung und in Worten, was an ihr anders ist. */
    private class Variant(val change: String, val root: JsonElement)

    /** Womit jede Stelle der Sicherung der Reihe nach ersetzt wird: Werte der falschen Art und Grenzfälle. */
    private val replacements: List<JsonElement> = listOf(
        JsonNull,
        JsonPrimitive(-1),
        JsonPrimitive(3),
        JsonPrimitive(Int.MAX_VALUE),
        JsonPrimitive(Int.MIN_VALUE),
        JsonPrimitive(Long.MAX_VALUE),
        JsonPrimitive(true),
        JsonPrimitive(""),
        JsonPrimitive("x"),
        // Die Kennung einer Spielerin vom Tisch, an falscher Stelle zum Beispiel als ihre eigene Mitspielerin.
        JsonPrimitive("spieler-anna"),
        // Der späteste Zeitpunkt, den es überhaupt gibt, und der späteste, mit dem die App noch rechnen kann.
        JsonPrimitive("+1000000000-12-31T23:59:59.999999999Z"),
        JsonPrimitive("+292278994-08-17T07:12:55.807Z"),
        JsonPrimitive("rufspiel"),
        JsonPrimitive("wenz"),
        JsonPrimitive("sie"),
        JsonArray(emptyList()),
        JsonObject(emptyMap()),
        JsonArray(listOf(JsonPrimitive("spieler-anna"), JsonPrimitive("spieler-anna"))),
        // Zwei Beträge, deren Summe nur durch einen Überlauf null ergäbe.
        JsonObject(mapOf("spieler-anna" to JsonPrimitive(Int.MIN_VALUE), "spieler-bertl" to JsonPrimitive(Int.MIN_VALUE))),
    )

    /** Alle Fassungen, in denen genau eine Stelle unterhalb von [element] ersetzt, entfernt oder verdoppelt ist. */
    private fun variants(element: JsonElement, path: String): Sequence<Variant> = sequence {
        for (replacement in replacements) yield(Variant("$path ersetzt durch $replacement", replacement))
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                yield(Variant("$path.$key entfernt", JsonObject(element - key)))
                for (inner in variants(value, "$path.$key")) {
                    yield(Variant(inner.change, JsonObject(element + (key to inner.root))))
                }
            }

            is JsonArray -> element.forEachIndexed { index, value ->
                yield(Variant("$path[$index] entfernt", JsonArray(element.filterIndexed { i, _ -> i != index })))
                yield(Variant("$path[$index] verdoppelt", JsonArray(element + value)))
                for (inner in variants(value, "$path[$index]")) {
                    yield(Variant(inner.change, JsonArray(element.mapIndexed { i, old -> if (i == index) inner.root else old })))
                }
            }

            else -> Unit
        }
    }

    /** Was an einem übernommenen Abend nicht stimmt; leer, wenn sich mit ihm arbeiten lässt. */
    private fun problemsOf(evening: Evening, players: List<Player>): List<String> = buildList {
        val seating = evening.seating
        if (seating.size != 4 || seating.toSet().size != 4 || seating.any { it.value.isBlank() }) add("Sitzordnung $seating")
        if (evening.firstDealer !in seating) add("der erste Geber sitzt nicht am Tisch")
        if (evening.entries.map { it.id }.toSet().size != evening.entries.size) add("doppelte Kennung eines Eintrags")
        try {
            // So speichert die Datenbank Zeitpunkte; was sich so nicht ausdrücken lässt, ließe sich nicht speichern.
            evening.startedAt.toEpochMilli()
            evening.finishedAt?.toEpochMilli()
            evening.entries.forEach { it.recordedAt.toEpochMilli() }
            if (StorageCodec.decodeEvening(StorageCodec.encodeEvening(evening)) != evening) add("kommt aus der Datei verändert zurück")
            val state = EveningCalculator.compute(evening)
            state.results.forEachIndexed { index, result ->
                if (result.amounts.values.sumOf { it.toLong() } != 0L) add("Eintrag Nr. ${index + 1} ist kein Nullsummenspiel")
            }
            SettleUp.transfers(state.balances)
            CsvExport.games(listOf(evening), players, ZoneId.of("Europe/Berlin"))
        } catch (e: Exception) {
            add("Ausnahme ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Test
    fun `verfaelschte Sicherungen bringen weder den Import noch die spaetere Arbeit zum Absturz`() =
        runTest(timeout = 5.minutes) {
            // Die Ausgangslage: vier Spieler und ein Abend mit jeder Eintragsart und jeder freiwilligen Angabe.
            val exporter = BackupService(
                players = InMemoryPlayerRepository(TestData.players),
                evenings = InMemoryEveningRepository(listOf(TestData.fullEvening("abend-voll", TestData.start))),
                settings = InMemorySettingsRepository(),
                clock = clock,
                appVersion = "0.1.0",
                dispatcher = Dispatchers.Unconfined,
            )
            val original = Json.parseToJsonElement(exporter.exportJson()).jsonObject

            var tried = 0
            var unreadable = 0
            var accepted = 0
            val problems = ArrayList<String>()
            for (variant in variants(original, "Sicherung")) {
                tried++
                val players = InMemoryPlayerRepository()
                val evenings = InMemoryEveningRepository()
                val subject = BackupService(players, evenings, InMemorySettingsRepository(), clock, "0.1.0", Dispatchers.Unconfined)
                try {
                    val result = subject.import(variant.root.toString())
                    if (result.error != null) unreadable++
                    if (evenings.evenings.first().toSet() != result.newEvenings.toSet()) {
                        problems += "${variant.change}: anderes gespeichert als gemeldet"
                    }
                    val allPlayers = players.players.first()
                    if (allPlayers.any { it.id.value.isBlank() }) problems += "${variant.change}: Spieler ohne Kennung angelegt"
                    for (evening in result.newEvenings) {
                        accepted++
                        for (problem in problemsOf(evening, allPlayers)) problems += "${variant.change}: $problem"
                    }
                } catch (e: Exception) {
                    problems += "${variant.change}: Ausnahme ${e.javaClass.simpleName}: ${e.message}"
                }
            }

            assertEquals(emptyList<String>(), problems.take(10), "${problems.size} Auffälligkeiten bei $tried Fassungen")
            // Der Test hat wirklich etwas geprüft. Am 15.11.2026 waren es 3843 Fassungen, davon 401 als Ganzes
            // unlesbar und 1244 mit übernommenem Abend; die Grenzen lassen Luft für spätere Änderungen am Format.
            assertTrue(tried > 2_000, "nur $tried Fassungen")
            assertTrue(unreadable > 100, "nur $unreadable unlesbare Fassungen")
            assertTrue(accepted > 200, "nur $accepted Fassungen mit übernommenem Abend")
        }
}
