package io.github.q0ochen.schafkopf.data.golden

import io.github.q0ochen.schafkopf.data.backup.BackupFormat
import io.github.q0ochen.schafkopf.data.backup.BackupService
import io.github.q0ochen.schafkopf.data.backup.ImportPreview
import io.github.q0ochen.schafkopf.data.backup.ImportResult
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.SPECIAL_IDS
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.SPECIAL_INSTANTS
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.SPECIAL_TEXTS
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.handwrittenBackups
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.handwrittenEveningTexts
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.handwrittenPlayerTexts
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.handwrittenRuleTexts
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.handwrittenSettingsTexts
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.pick
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.randomEvening
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.randomInstant
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.randomRules
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.schemaExample
import io.github.q0ochen.schafkopf.data.golden.GoldenCases.specialRules
import io.github.q0ochen.schafkopf.data.storage.NewerSchemaException
import io.github.q0ochen.schafkopf.data.storage.StorageCodec
import io.github.q0ochen.schafkopf.data.storage.TestData
import io.github.q0ochen.schafkopf.data.storage.UnreadableDataException
import io.github.q0ochen.schafkopf.data.storage.parseInstant
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.RuleViolation
import io.github.q0ochen.schafkopf.domain.rules.ScoreCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random

/**
 * Golden-Daten für die Web-App (`web/src/data`): Der echte Kotlin-Code schreibt und liest hier viele feste Fälle,
 * und alle Ergebnisse stehen in `web/src/data/golden/kotlin-format-golden.json`. Die Tests der Web-App verlangen,
 * dass ihr TypeScript-Port für dieselben Eingaben genau dasselbe liefert: denselben Text beim Schreiben, dasselbe
 * Model beim Lesen, dieselbe Fehlerart und dieselbe Vorschau einer Sicherung. So bleiben Sicherungen zwischen der
 * Android-App und der Web-App austauschbar.
 *
 * Mit `-PgoldenWrite=true` (Systemeigenschaft `golden.write`) schreibt der Test die Datei neu. Sonst vergleicht er sie
 * mit dem, was der Kotlin-Code heute erzeugt, und schlägt fehl, wenn sie veraltet ist.
 */
class WebFormatGoldenTest {

    @Test
    fun `die Golden-Daten der Web-App passen zum heutigen Dateiformat`() {
        val path = requireNotNull(System.getProperty("golden.file")) {
            "Die Systemeigenschaft golden.file fehlt; sie wird in data/build.gradle.kts gesetzt."
        }
        val file = Path.of(path)
        val content = WebFormatGolden.generate()
        if (System.getProperty("golden.write") == "true") {
            Files.createDirectories(file.parent)
            Files.writeString(file, content)
            return
        }
        val existing = if (Files.isRegularFile(file)) Files.readString(file) else ""
        if (existing != content) throw AssertionError(driftMessage(existing, content))
    }

    private fun driftMessage(old: String, new: String): String {
        val oldLines = old.lines()
        val newLines = new.lines()
        val line = (0 until maxOf(oldLines.size, newLines.size)).firstOrNull { oldLines.getOrNull(it) != newLines.getOrNull(it) }
        return buildString {
            appendLine(
                "Die Golden-Daten der Web-App sind veraltet: web/src/data/golden/kotlin-format-golden.json passt nicht " +
                    "mehr zu dem, was der Kotlin-Code heute schreibt und liest.",
            )
            appendLine("Neu erzeugen mit: tools/jvm-build.sh :data:test --tests '*WebFormatGoldenTest' -PgoldenWrite=true")
            appendLine("Danach web/src/data anpassen, bis „cd web && npm test“ grün ist.")
            if (line != null) {
                appendLine("Erste Abweichung in Zeile ${line + 1}:")
                appendLine("  bisher: " + (oldLines.getOrNull(line)?.take(400) ?: "(fehlt)"))
                appendLine("  jetzt:  " + (newLines.getOrNull(line)?.take(400) ?: "(fehlt)"))
            }
        }
    }
}

/**
 * Erzeugt den Inhalt der Golden-Datei. Alles ist fest vorgegeben oder kommt aus Zufallsfolgen mit festem Startwert:
 * Jeder Lauf ergibt denselben Text.
 */
internal object WebFormatGolden {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC)

    fun generate(): String {
        val sections = LinkedHashMap<String, List<JsonElement>>()
        val bases = ArrayList<JsonElement>()
        sections["bases"] = bases
        sections["instantParse"] = instantParseCases()
        sections["instantFormat"] = instantFormatCases()
        sections["writeEvenings"] = writeEveningCases()
        sections["writePlayers"] = writePlayerCases()
        sections["writeRules"] = writeRuleCases()
        sections["writeBackups"] = writeBackupCases()
        sections["rewriteEvenings"] = rewriteEveningCases()
        sections["readEvenings"] = readEveningCases(bases)
        sections["readRules"] = readRuleCases(bases)
        sections["readPlayers"] = readPlayerCases(bases)
        sections["readSettings"] = readSettingsCases()
        sections["readBackups"] = readBackupCases(bases)
        sections["isJsonObject"] = isJsonObjectCases()
        sections["places"] = PlaceGoldenCases.cases()
        return render(sections)
    }

    // ---------- Ausgabe der Datei ----------

    /**
     * Jeder Fall steht auf einer eigenen Zeile, damit Änderungen im Diff lesbar bleiben. Alle Zeichen außerhalb von
     * ASCII stehen als \u-Escape in der Datei: So kommt jeder Text unverändert an, auch einzelne Ersatzzeichen.
     */
    private fun render(sections: Map<String, List<JsonElement>>): String = buildString {
        append("{\n")
        append("\"about\": \"Erzeugt von data/src/test/kotlin/io/github/q0ochen/schafkopf/data/golden/WebFormatGoldenTest.kt")
        append(" - nicht von Hand bearbeiten.\",\n")
        var sectionIndex = 0
        for ((name, cases) in sections) {
            val ids = cases.mapNotNull { (it as? JsonObject)?.get("id") ?: (it as? JsonObject)?.get("name") }
            check(ids.size == ids.toSet().size) { "doppelte Kennung im Abschnitt $name" }
            append("\"").append(name).append("\": [\n")
            cases.forEachIndexed { caseIndex, case ->
                append(ascii(case.toString()))
                if (caseIndex < cases.size - 1) append(',')
                append('\n')
            }
            append(']')
            if (++sectionIndex < sections.size) append(',')
            append('\n')
        }
        append("}\n")
    }

    private fun ascii(text: String): String = buildString(text.length) {
        for (char in text) {
            if (char.code < 0x7f) append(char) else append("\\u").append(char.code.toString(16).padStart(4, '0'))
        }
    }

    // ---------- Zeitpunkte ----------

    private fun instantParseCases(): List<JsonElement> {
        val inputs = LinkedHashSet<String>()
        val base = "2026-11-14T19:00:00"
        inputs += listOf("${base}Z", "${base}z", "2026-11-14t19:00:00Z", "2026-11-14t19:00:00z", "$base.Z", "$base.5Z")
        for (digits in 1..12) inputs += "$base." + "123456789123".take(digits) + "Z"
        inputs += listOf("$base.000Z", "$base.100Z", "$base.000000Z", "$base.000000000Z", "$base.000000001Z", "$base.120000Z")
        val offsets = listOf(
            "+00:00", "-00:00", "+01:00", "-01:00", "+01:30", "+05:45", "+14:00", "-12:00", "+18:00", "-18:00", "+18:01",
            "-18:01", "+19:00", "+23:59", "+24:00", "+01:60", "+01:00:30", "+01:00:3", "+01:00:", "+0100", "+01", "+1:00",
            "+01:00:60", "+00:00:01", "-00:00:01", "Z+01:00", "+01:00Z", "UTC", "GMT", "+01:00:30.5", "+18:00:00", "-18:00:01",
        )
        for (offset in offsets) {
            inputs += base + offset
            inputs += "$base.123$offset"
        }
        val dates = listOf(
            "2024-02-29", "2025-02-29", "2000-02-29", "1900-02-29", "2026-02-30", "2026-04-31", "2026-04-30", "2026-00-10",
            "2026-13-01", "2026-12-00", "2026-12-32", "2026-1-01", "2026-01-1", "26-01-01", "0000-01-01", "-0001-01-01",
            "-0000-01-01", "+2026-01-01", "02026-01-01", "+02026-01-01", "+12026-01-01", "12026-01-01", "-12026-01-01",
            "+0010000-01-01", "999-01-01", "-999-01-01", "-0999-01-01", "-0004-02-29", "-0100-02-29", "-0400-02-29",
            "+10000-02-29", "+10400-02-29", "-10000-12-31", "-10001-01-01", "-19999-03-01", "-20000-02-01",
        )
        for (date in dates) inputs += "${date}T00:00:00Z"
        val times = listOf(
            "24:00:00", "24:00:00.000000000", "24:00:00.1", "24:00:01", "24:01:00", "23:59:60", "23:59:60.5", "23:59:61",
            "23:58:60", "00:00:60", "25:00:00", "23:60:00", "19:00", "19:00:0", "19:00:00:00", "9:00:00", "19:0:00",
            "1:00:00", "-1:00:00",
        )
        for (time in times) inputs += "2026-12-31T${time}Z"
        inputs += listOf(
            "2026-12-31T23:59:60+01:00", "2026-12-31T23:59:60-01:00", "2026-12-31T22:59:60Z", "2026-12-31T24:00:00+01:00",
            "2026-02-28T24:00:00Z", "2024-02-28T24:00:00Z",
            "+292278994-08-17T07:12:55.807Z", "+292278994-08-17T07:12:55.807000001Z", "+292278994-08-17T07:12:55.808Z",
            "+292278994-08-17T07:12:56Z", "+292278994-08-17T08:12:55.807+01:00", "+292278994-08-17T06:12:55.807-01:00",
            "-292275055-05-16T16:47:04.192Z", "-292275055-05-16T16:47:04.191999999Z", "-292275055-05-16T16:47:04.191Z",
            "-292275055-05-16T17:47:04.192+01:00", "+1000000000-12-31T23:59:59.999999999Z", "-1000000000-01-01T00:00:00Z",
            "+1000000001-01-01T00:00:00Z", "+9999999999-12-31T23:59:59Z", "+99999999999-12-31T23:59:59Z",
            "-9999999999-01-01T00:00:00Z", "+2147483648-01-01T00:00:00Z", "+4294969322-01-01T00:00:00Z",
            "+292278995-01-01T00:00:00Z", "-292275056-01-01T00:00:00Z", "+100000000-01-01T00:00:00Z",
            "-100000000-01-01T00:00:00Z", "+292278993-12-31T23:59:59.999999999Z",
        )
        inputs += listOf(
            "", " ", "Z", "T", "2026", "2026-11-14", "2026-11-14T", base, "2026-11-14 19:00:00Z", " ${base}Z", "${base}Z ",
            "${base}Z\n", "$base,5Z", "\uFF12\uFF10\uFF12\uFF16-11-14T19:00:00Z", "2026\u201011\u201014T19:00:00Z",
            "$base.\uFF11Z", "gestern", "null", "1731610800", "+-2026-01-01T00:00:00Z", "-+2026-01-01T00:00:00Z",
            "${base}ZZ", "$base.-1Z", "2026-11-14T-1:00:00Z", "2026-11-14T19:-1:00Z", "2026-11-14T19:00:00.1 Z",
            "2026-11-14\u0000T19:00:00Z", "${BOM}2026-11-14T19:00:00Z",
        )
        val random = Random(1_115_2026L)
        repeat(150) { inputs += randomInstantInRange(random).toString() }
        repeat(100) {
            val instant = randomInstantInRange(random)
            val offset = ZoneOffset.ofTotalSeconds((random.nextInt(2 * 18 * 60 + 1) - 18 * 60) * 60)
            try {
                inputs += instant.atOffset(offset).toString()
            } catch (e: DateTimeException) {
                // Am Rand des Bereichs gibt es zu manchen Versätzen kein Datum; dann entfällt der Fall.
            }
        }
        repeat(50) { inputs += randomInstantInRange(random).toString().lowercase() }
        return inputs.map { input ->
            buildJsonArray {
                add(input)
                add(
                    try {
                        parseInstant(input).toString()
                    } catch (e: DateTimeException) {
                        null
                    },
                )
            }
        }
    }

    private fun instantFormatCases(): List<JsonElement> {
        val instants = LinkedHashSet<Instant>()
        instants += SPECIAL_INSTANTS
        instants += listOf(
            Instant.ofEpochSecond(-62_167_219_200L), Instant.ofEpochSecond(-62_167_219_201L),
            Instant.ofEpochSecond(253_402_300_799L), Instant.ofEpochSecond(253_402_300_800L),
            Instant.ofEpochSecond(-377_705_116_800L), Instant.ofEpochSecond(-377_705_116_801L),
            Instant.ofEpochSecond(-377_705_116_800L - 1, 1), Instant.ofEpochSecond(-693_362_793_600L),
            Instant.ofEpochSecond(-62_167_219_200L - 315_569_520_000L), Instant.ofEpochSecond(-62_167_219_200L + 315_569_520_000L),
            Instant.ofEpochSecond(0, 1), Instant.ofEpochSecond(0, 10), Instant.ofEpochSecond(0, 1_000), Instant.ofEpochSecond(0, 1_000_000),
            Instant.ofEpochSecond(0, 100_000_000), Instant.ofEpochSecond(0, 999_999_999), Instant.ofEpochSecond(59), Instant.ofEpochSecond(60),
        )
        val random = Random(42_2026L)
        repeat(300) { instants += randomInstantInRange(random) }
        return instants.map { instant ->
            buildJsonArray {
                add(instant.epochSecond.toString())
                add(instant.nano)
                add(instant.toString())
            }
        }
    }

    /** Ein beliebiger Zeitpunkt im ganzen Bereich von [Instant], mit Bruchteilen in allen Längen. */
    private fun randomInstantInRange(random: Random): Instant {
        val span = Instant.MAX.epochSecond - Instant.MIN.epochSecond + 1
        val seconds = when (random.nextInt(4)) {
            0 -> Instant.MIN.epochSecond + Math.floorMod(random.nextLong(), span)
            1 -> Math.floorMod(random.nextLong(), 9_223_372_036_854_775L * 2) - 9_223_372_036_854_775L
            2 -> -62_167_219_200L + Math.floorMod(random.nextLong(), 315_569_520_000L)
            else -> 1_700_000_000L + random.nextInt(200_000_000)
        }
        val nanos = when (random.nextInt(4)) {
            0 -> 0
            1 -> random.nextInt(1_000) * 1_000_000
            2 -> random.nextInt(1_000_000) * 1_000
            else -> random.nextInt(1_000_000_000)
        }
        return Instant.ofEpochSecond(seconds, nanos.toLong())
    }

    // ---------- Schreiben ----------

    private fun writeEveningCases(): List<JsonElement> {
        val evenings = ArrayList<Pair<String, Evening>>()
        evenings += "voll" to TestData.fullEvening()
        evenings += "einfach" to TestData.simpleEvening("abend-einfach")
        evenings += "schema-beispiel" to schemaExample()
        evenings += "ohne-eintraege" to TestData.simpleEvening("leer").copy(entries = emptyList())
        evenings += "ohne-sitzordnung" to TestData.simpleEvening("ohne-sitze").copy(seating = emptyList())
        for ((name, rules) in specialRules()) {
            evenings += "regeln-$name" to TestData.fullEvening("regeln-$name").copy(rules = rules)
        }
        SPECIAL_INSTANTS.forEachIndexed { index, instant ->
            val evening = TestData.fullEvening("zeit-$index")
            evenings += "zeit-$index" to evening.copy(
                startedAt = instant,
                finishedAt = SPECIAL_INSTANTS[(index + 1) % SPECIAL_INSTANTS.size],
                entries = evening.entries.mapIndexed { position, entry ->
                    withRecordedAt(entry, SPECIAL_INSTANTS[(index + position) % SPECIAL_INSTANTS.size])
                },
            )
        }
        SPECIAL_TEXTS.forEachIndexed { index, text ->
            val evening = TestData.fullEvening("text-$index")
            evenings += "text-$index" to evening.copy(
                location = text,
                rules = evening.rules.copy(name = text),
                entries = evening.entries.map { entry ->
                    when (entry) {
                        is GameEntry -> entry.copy(note = text)
                        is ManualEntry -> entry.copy(note = text)
                        else -> entry
                    }
                },
            )
        }
        SPECIAL_IDS.forEachIndexed { index, id ->
            val seating = listOf(PlayerId(id), PlayerId("$id-2"), PlayerId("3$id"), PlayerId("4"))
            evenings += "kennung-$index" to Evening(
                id = EveningId(id),
                startedAt = TestData.start,
                seating = seating,
                firstDealer = seating[0],
                rules = RuleSet.standard(),
                entries = listOf(
                    GameEntry(EntryId(id), TestData.start, GameType.RUFSPIEL, declarer = seating[0], partner = seating[1], declarerWon = true, klopfer = seating.reversed().toSet()),
                    ManualEntry(EntryId("$id-m"), TestData.start, mapOf(seating[3] to 5, seating[0] to -5, PlayerId("10") to 0, PlayerId("2") to 0), id),
                    BockTriggerEntry(EntryId("$id-b"), TestData.start, BockReason.MANUAL, seating[2], handThrownIn = false),
                ),
            )
        }
        val random = Random(20_261_115L)
        repeat(40) { evenings += "zufall-$it" to randomEvening(random, "zufall-$it") }
        return evenings.map { (id, evening) -> writeEveningCase(id, evening) }
    }

    private fun writeEveningCase(id: String, evening: Evening): JsonObject {
        val text = StorageCodec.encodeEvening(evening)
        val roundTrip = try {
            StorageCodec.decodeEvening(text) == evening
        } catch (e: UnreadableDataException) {
            false
        }
        return buildJsonObject {
            put("id", id)
            put("model", GoldenModel.evening(evening))
            put("text", text)
            put("roundTrip", roundTrip)
        }
    }

    private fun withRecordedAt(entry: EveningEntry, at: Instant): EveningEntry = when (entry) {
        is GameEntry -> entry.copy(recordedAt = at)
        is ThrownInEntry -> entry.copy(recordedAt = at)
        is BockTriggerEntry -> entry.copy(recordedAt = at)
        is ManualEntry -> entry.copy(recordedAt = at)
    }

    /**
     * Gelesen und wieder geschrieben: Ein Zeitpunkt in anderer Schreibweise (Bruchteil aus Nullen, Versatz,
     * Kleinbuchstaben, 24:00, Schaltsekunde) steht danach in der Schreibweise von `Instant.toString()` in der Datei.
     */
    private fun rewriteEveningCases(): List<JsonElement> {
        val base = StorageCodec.encodeEvening(TestData.simpleEvening("neu-geschrieben"))
        val forms = listOf(
            "2026-11-14T19:00:00.000Z", "2026-11-14T19:00:00.000000000Z", "2026-11-14T20:00:00+01:00", "2026-11-14T18:30:00-00:30",
            "2026-11-14t19:00:00z", "2026-11-13T24:00:00Z", "2026-12-31T23:59:60Z", "2026-11-14T19:00:00.Z", "2026-11-14T19:00:00.5Z",
            "2026-11-14T19:00:00.120Z", "2026-11-14T19:00:00.123450Z", "+02026-11-14T19:00:00Z", "2026-11-14T19:00:00+00:00:01",
        )
        return forms.map { form ->
            val text = base
                .replace("\"startedAt\":\"2026-11-14T19:00:00Z\"", "\"startedAt\":\"$form\"")
                .replace("\"recordedAt\":\"2026-11-14T19:05:00Z\"", "\"recordedAt\":\"$form\"")
            check(text != base)
            buildJsonObject {
                put("id", form)
                put("text", text)
                put(
                    "rewritten",
                    try {
                        StorageCodec.encodeEvening(StorageCodec.decodeEvening(text))
                    } catch (e: UnreadableDataException) {
                        null
                    },
                )
            }
        }
    }

    private fun writePlayerCases(): List<JsonElement> {
        val lists = listOf(
            "leer" to emptyList<Player>(),
            "testdaten" to TestData.players,
            "archiviert" to listOf(TestData.anna.copy(archived = true), TestData.bertl),
            "sonderzeichen" to SPECIAL_TEXTS.mapIndexed { index, text ->
                Player(PlayerId(SPECIAL_IDS[index % SPECIAL_IDS.size]), text, colorIndex = index - 3, archived = index % 2 == 0)
            },
            "extreme-farben" to listOf(Player(PlayerId("p"), "P", Int.MIN_VALUE), Player(PlayerId("q"), "Q", Int.MAX_VALUE, archived = true)),
            "doppelte-kennung" to listOf(Player(PlayerId("p"), "Erster", 1), Player(PlayerId("p"), "Zweiter", 2)),
        )
        return lists.map { (id, players) ->
            buildJsonObject {
                put("id", id)
                put("model", GoldenModel.players(players))
                put("text", StorageCodec.encodePlayers(players))
            }
        }
    }

    private fun writeRuleCases(): List<JsonElement> {
        val random = Random(5_10_50L)
        val rules = specialRules() + List(25) { "zufall-$it" to randomRules(random) }
        return rules.map { (id, ruleSet) ->
            buildJsonObject {
                put("id", id)
                put("model", GoldenModel.rules(ruleSet))
                put("text", StorageCodec.encodeRules(ruleSet))
            }
        }
    }

    private fun writeBackupCases(): List<JsonElement> {
        val archived = Player(PlayerId("spieler-emil"), "Emil", colorIndex = 4, archived = true)
        val start = TestData.start
        val cases = ArrayList<JsonObject>()
        cases += backupWriteCase("leer", emptyList(), emptyList(), RuleSet.standard(), "0.1.0", Instant.parse("2026-11-15T10:00:00Z"))
        cases += backupWriteCase(
            "voll",
            TestData.players + archived,
            listOf(TestData.fullEvening("abend-voll", start), TestData.simpleEvening("abend-einfach", start.minusSeconds(7 * 86_400))),
            TestData.fullRules,
            "0.1.0",
            Instant.parse("2026-11-15T10:00:00Z"),
        )
        // Die Abende stehen nach Beginn sortiert in der Datei, gleich frühe in ihrer bisherigen Reihenfolge. Als Text
        // verglichen stünde „…:00.123Z“ vor „…:00Z“; sortiert wird aber nach der Zeit.
        val sorting = listOf(
            TestData.simpleEvening("c-spaet", Instant.parse("2026-11-14T19:00:00.123Z")),
            TestData.simpleEvening("b-frueh", Instant.parse("2026-11-14T19:00:00Z")),
            TestData.simpleEvening("a-gleich-1", Instant.parse("2026-11-14T19:00:00.1Z")),
            TestData.simpleEvening("d-gleich-2", Instant.parse("2026-11-14T19:00:00.100Z")),
            TestData.simpleEvening("e-vor-christus", Instant.parse("-0001-12-31T23:59:59Z")),
            TestData.simpleEvening("f-fern", Instant.parse("+10000-01-01T00:00:00Z")),
            TestData.simpleEvening("g-1969", Instant.parse("1969-12-31T23:59:59.999999999Z")),
            TestData.simpleEvening("h-gleich-3", Instant.parse("2026-11-14T19:00:00.1Z")),
        )
        cases += backupWriteCase("sortierung", TestData.players, sorting, RuleSet.sauspiel(), "0.1.0", Instant.parse("2026-11-15T10:00:00.5Z"))
        cases += backupWriteCase(
            "sonderzeichen",
            listOf(Player(PlayerId("p\"1"), "Zeile 1\nZeile 2 \u0000 😀", -1)),
            listOf(TestData.fullEvening("text").copy(location = "BOM \uFEFF und \u2028 \\ \"")),
            RuleSet.standard().copy(name = "Tab\tund ÄÖÜ"),
            "1.0 \"beta\"\n😀",
            Instant.parse("2026-11-15T10:00:00.000000001Z"),
        )
        val emptyParts = TestData.simpleEvening("leere-teile").copy(
            seating = emptyList(),
            rules = specialRules().first { it.first == "leere-tabellen" }.second,
            entries = listOf(ManualEntry(EntryId("m"), TestData.start, emptyMap(), ""), ThrownInEntry(EntryId("t"), TestData.start)),
        )
        cases += backupWriteCase("leere-teile", listOf(TestData.anna), listOf(emptyParts, TestData.simpleEvening("ohne").copy(entries = emptyList())), RuleSet.standard().copy(enabledTypes = emptySet(), prices = emptyMap()), "", Instant.EPOCH)
        val random = Random(7_7_7L)
        repeat(3) { index ->
            val evenings = List(1 + random.nextInt(3)) { randomEvening(random, "sicherung-$index-$it") }
            val players = List(random.nextInt(4)) { Player(PlayerId("p$it"), pick(random, SPECIAL_TEXTS), random.nextInt(9), random.nextBoolean()) }
            cases += backupWriteCase("zufall-$index", players, evenings, randomRules(random), pick(random, SPECIAL_TEXTS), randomInstant(random))
        }
        return cases
    }

    private fun backupWriteCase(
        id: String,
        players: List<Player>,
        evenings: List<Evening>,
        rules: RuleSet,
        appVersion: String,
        exportedAt: Instant,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("players", GoldenModel.players(players))
        putJsonArray("evenings") { evenings.forEach { add(GoldenModel.evening(it)) } }
        put("defaultRules", GoldenModel.rules(rules))
        put("appVersion", appVersion)
        put("exportedAt", exportedAt.toString())
        put("text", BackupFormat.write(players, evenings, rules, appVersion, exportedAt))
    }

    // ---------- Lesen: Abend, Regelwerk, Spieler, Einstellungen ----------

    /** Wie ein Text gelesen wird: als ganze Datei (Strom) und, wo es das gibt, als Teil einer Sicherung (Baum). */
    private class ReadKind(val stream: (String) -> JsonObject, val tree: ((String) -> JsonObject)?)

    private val eveningKind = ReadKind(
        stream = { text -> outcome { GoldenModel.evening(StorageCodec.decodeEvening(text)) } },
        tree = { text -> treeOutcome(text) { GoldenModel.evening(StorageCodec.decodeEveningElement(it)) } },
    )

    private val rulesKind = ReadKind(
        stream = { text -> outcome { GoldenModel.rules(StorageCodec.decodeRules(text)) } },
        tree = { text -> treeOutcome(text) { GoldenModel.rules(StorageCodec.decodeRulesElement(it)) } },
    )

    private val playersKind = ReadKind(stream = { text -> outcome { GoldenModel.players(StorageCodec.decodePlayers(text)) } }, tree = null)

    private val settingsKind = ReadKind(stream = { text -> outcome { GoldenModel.settings(StorageCodec.decodeSettings(text)) } }, tree = null)

    private fun outcome(block: () -> JsonElement): JsonObject = try {
        buildJsonObject { put("model", block()) }
    } catch (e: NewerSchemaException) {
        buildJsonObject {
            put("error", "newer")
            put("message", e.message)
        }
    } catch (e: UnreadableDataException) {
        buildJsonObject {
            put("error", "unreadable")
            // Nur die eigenen Meldungen der App sind festgelegt; die der JSON-Bibliothek nicht.
            if (e.cause == null) put("message", e.message)
        }
    }

    private fun treeOutcome(text: String, decode: (JsonElement) -> JsonElement): JsonObject {
        val element = try {
            StorageCodec.json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            return buildJsonObject { put("error", "parse") }
        }
        return outcome { decode(element) }
    }

    private fun readCase(id: String, text: String, kind: ReadKind): JsonObject {
        val stream = kind.stream(text)
        return buildJsonObject {
            put("id", id)
            put("text", text)
            put("stream", stream)
            if (kind.tree != null) put("tree", diff(stream, kind.tree.invoke(text)))
        }
    }

    private fun readEveningCases(bases: MutableList<JsonElement>): List<JsonElement> {
        val cases = handwrittenEveningTexts().map { (id, text) -> readCase(id, text, eveningKind) }
        val typeFirst = Json.parseToJsonElement(EVENING_BASE)
        val typeLast = moveEntryTypeToEnd(typeFirst)
        return cases +
            mutatedReadCases("abend-typ-vorn", typeFirst, eveningKind, bases, include = { true }) +
            mutatedReadCases("abend-typ-hinten", typeLast, eveningKind, bases, include = { it.startsWith("entries") })
    }

    private fun readRuleCases(bases: MutableList<JsonElement>): List<JsonElement> {
        val cases = handwrittenRuleTexts().map { (id, text) -> readCase(id, text, rulesKind) }
        val rules = TestData.fullRules.copy(
            prices = TestData.fullRules.prices + (GameType.UEBER_KREUZ to 15),
            enabledTypes = setOf(GameType.RUFSPIEL, GameType.UEBER_KREUZ, GameType.WENZ),
        )
        return cases + mutatedReadCases("regeln", Json.parseToJsonElement(StorageCodec.encodeRules(rules)), rulesKind, bases, include = { true })
    }

    private fun readPlayerCases(bases: MutableList<JsonElement>): List<JsonElement> {
        val cases = handwrittenPlayerTexts().map { (id, text) -> readCase(id, text, playersKind) }
        val base = Json.parseToJsonElement(
            StorageCodec.encodePlayers(listOf(TestData.anna.copy(archived = true, colorIndex = 2), TestData.bertl)),
        )
        return cases + mutatedReadCases("spieler", base, playersKind, bases, include = { true })
    }

    private fun readSettingsCases(): List<JsonElement> = handwrittenSettingsTexts().map { (id, text) -> readCase(id, text, settingsKind) }

    /**
     * Fassungen eines gültigen Textes, in denen genau eine Stelle ersetzt, entfernt oder verdoppelt ist. In der Datei
     * steht nur, was sich ändert (Stelle und Ersatz), und das Ergebnis als Unterschied zum Ergebnis des Ausgangstextes.
     */
    private fun mutatedReadCases(
        name: String,
        base: JsonElement,
        kind: ReadKind,
        bases: MutableList<JsonElement>,
        include: (String) -> Boolean,
    ): List<JsonElement> {
        val spanned = spanned(base)
        val baseStream = kind.stream(spanned.text)
        bases += buildJsonObject {
            put("name", name)
            put("text", spanned.text)
            put("stream", baseStream)
            if (kind.tree != null) put("tree", diff(baseStream, kind.tree.invoke(spanned.text)))
        }
        return mutations(spanned, include, VALUE_FRAGMENTS).mapIndexed { index, mutation ->
            val text = spanned.text.substring(0, mutation.start) + mutation.put + spanned.text.substring(mutation.end)
            val stream = kind.stream(text)
            buildJsonObject {
                put("id", "$name#$index")
                put("base", name)
                put("what", mutation.what)
                putJsonArray("at") {
                    add(mutation.start)
                    add(mutation.end)
                }
                put("put", mutation.put)
                put("streamDiff", diff(baseStream, stream))
                if (kind.tree != null) {
                    // Der Baum wird als Unterschied zum Strom oder, wenn das kürzer ist, zum Ausgangstext angegeben.
                    val tree = kind.tree.invoke(text)
                    val fromStream = diff(stream, tree)
                    val fromBase = diff(baseStream, tree)
                    if (fromBase.toString().length < fromStream.toString().length) put("treeFromBase", fromBase) else put("tree", fromStream)
                }
            }
        }
    }

    /** Ob ein Text ein vollständiges JSON-Objekt ist (so erkennt der Datei-Speicher einen fertig geschriebenen Stand). */
    private fun isJsonObjectCases(): List<JsonElement> {
        val texts = LinkedHashSet<String>()
        for ((_, text) in handwrittenEveningTexts() + handwrittenRuleTexts() + handwrittenPlayerTexts()) {
            if (text.length < 400) texts += text
        }
        texts += listOf(
            "", "{", "{}", "[]", "42", "\"Text\"", "$BOM{}", "{} ", " {}", "{}x", "{\"a\":hallo}",
            "{\"a\":" + "[".repeat(398) + "]".repeat(398) + "}", "{\"a\":" + "[".repeat(399) + "]".repeat(399) + "}",
        )
        return texts.map { text ->
            buildJsonArray {
                add(text)
                add(StorageCodec.isJsonObject(text))
            }
        }
    }

    // ---------- Lesen: Sicherung ----------

    private fun readBackupCases(bases: MutableList<JsonElement>): List<JsonElement> {
        val cases = ArrayList<JsonElement>()
        for (case in handwrittenBackups()) {
            cases += buildJsonObject {
                put("id", case.id)
                put("text", case.text)
                put("knownPlayers", GoldenModel.players(case.knownPlayers))
                putJsonArray("knownEvenings") { case.knownEvenings.forEach { add(GoldenModel.evening(it)) } }
                put("expect", backupExpect(case.text, case.knownPlayers, case.knownEvenings))
                put("failingSecondPlayer", failingImportJson(case.text, case.knownPlayers, case.knownEvenings, failPlayerAt = 1, failEveningAt = -1))
                put("failingFirstEvening", failingImportJson(case.text, case.knownPlayers, case.knownEvenings, failPlayerAt = -1, failEveningAt = 0))
            }
        }
        val knownPlayers = listOf(TestData.anna.copy(name = "Spieler 1"), Player(PlayerId("hier"), " bertl ", 5))
        val spanned = spanned(Json.parseToJsonElement(BACKUP_BASE))
        val baseExpect = backupExpect(spanned.text, knownPlayers, emptyList())
        bases += buildJsonObject {
            put("name", "sicherung")
            put("text", spanned.text)
            put("knownPlayers", GoldenModel.players(knownPlayers))
            put("knownEvenings", JsonArray(emptyList()))
            put("expect", baseExpect)
        }
        mutations(spanned, { true }, BACKUP_FRAGMENTS).forEachIndexed { index, mutation ->
            val text = spanned.text.substring(0, mutation.start) + mutation.put + spanned.text.substring(mutation.end)
            cases += buildJsonObject {
                put("id", "sicherung#$index")
                put("base", "sicherung")
                put("what", mutation.what)
                putJsonArray("at") {
                    add(mutation.start)
                    add(mutation.end)
                }
                put("put", mutation.put)
                put("expectDiff", diff(baseExpect, backupExpect(text, knownPlayers, emptyList())))
            }
        }
        return cases
    }

    private fun backupExpect(text: String, knownPlayers: List<Player>, knownEvenings: List<Evening>): JsonObject {
        val preview = BackupFormat.read(text, knownPlayers, knownEvenings)
        return buildJsonObject {
            put("checks", checks(text))
            put("preview", previewJson(preview))
            put("import", importJson(text, knownPlayers, knownEvenings, preview))
        }
    }

    /**
     * Die Rechenergebnisse, die die Prüfung eines Abends braucht, für jeden Abend der Sicherung, der sich lesen lässt
     * und eine gültige Sitzordnung hat (nur solche Abende rechnet die Prüfung durch).
     */
    private fun checks(text: String): JsonArray {
        val root = try {
            StorageCodec.json.parseToJsonElement(text.removePrefix(BOM))
        } catch (e: IllegalArgumentException) {
            return JsonArray(emptyList())
        }
        val elements = ((root as? JsonObject)?.get("evenings") as? JsonArray) ?: return JsonArray(emptyList())
        val found = LinkedHashMap<JsonObject, JsonObject>()
        for (element in elements) {
            val evening = try {
                StorageCodec.decodeEveningElement(element)
            } catch (e: UnreadableDataException) {
                continue
            }
            val seating = evening.seating
            val validSeating = seating.size == 4 && seating.toSet().size == 4 && seating.none { it.value.isBlank() } &&
                evening.firstDealer in seating
            if (!validSeating) continue
            val model = GoldenModel.evening(evening)
            if (model in found) continue
            found[model] = buildJsonObject {
                put("evening", model)
                put("results", entryResults(evening))
            }
        }
        return JsonArray(found.values.toList())
    }

    private fun entryResults(evening: Evening): JsonArray {
        val state = EveningCalculator.compute(evening)
        return buildJsonArray {
            state.results.forEachIndexed { index, result ->
                val entry = evening.entries[index]
                addJsonObject {
                    putJsonArray("issues") { result.issues.forEach { add(it.name) } }
                    put("bockExponent", result.bockExponent)
                    putJsonArray("amounts") {
                        for ((player, amount) in result.amounts) {
                            addJsonArray {
                                add(player.value)
                                add(amount)
                            }
                        }
                    }
                    if (entry is GameEntry) {
                        val facts = with(EveningCalculator) { entry.toFacts(evening.seating, result.bockExponent) }
                        put("valueOutOfRange", RuleViolation.VALUE_OUT_OF_RANGE in ScoreCalculator.validate(facts, evening.rules))
                    } else {
                        put("valueOutOfRange", JsonNull)
                    }
                }
            }
        }
    }

    private fun previewJson(preview: ImportPreview): JsonObject = buildJsonObject {
        val error = preview.error
        if (error == null) {
            put("error", JsonNull)
        } else {
            put(
                "error",
                buildJsonObject {
                    put("kind", error.kind.name)
                    put("message", error.message)
                },
            )
        }
        put("exportedAt", preview.exportedAt?.toString())
        put("appVersion", preview.appVersion)
        put("newPlayers", GoldenModel.players(preview.newPlayers))
        put("sameNamePlayers", GoldenModel.players(preview.sameNamePlayers))
        put("existingPlayerCount", preview.existingPlayerCount)
        put("differingPlayers", GoldenModel.players(preview.differingPlayers))
        putJsonArray("newEvenings") { preview.newEvenings.forEach { add(GoldenModel.evening(it)) } }
        putJsonArray("existingEvenings") { preview.existingEvenings.forEach { add(GoldenModel.evening(it)) } }
        putJsonArray("invalidEvenings") {
            for (invalid in preview.invalidEvenings) {
                addJsonObject {
                    put("id", invalid.id)
                    put("startedAt", invalid.startedAt?.toString())
                    put("problem", invalid.problem.name)
                    put("reason", invalid.reason)
                }
            }
        }
        val rules = preview.defaultRules
        put("defaultRules", if (rules == null) JsonNull else GoldenModel.rules(rules))
    }

    /** Was `BackupService.import` mit dieser Sicherung tatsächlich speichert, auf einem Gerät mit diesem Stand. */
    private fun importJson(text: String, knownPlayers: List<Player>, knownEvenings: List<Evening>, preview: ImportPreview): JsonObject {
        val playerRepository = InMemoryPlayerRepository(knownPlayers)
        val eveningRepository = InMemoryEveningRepository(knownEvenings)
        val service = BackupService(playerRepository, eveningRepository, InMemorySettingsRepository(), clock, "golden", Dispatchers.Unconfined)
        val result = runBlocking { service.import(text) }
        val storedPlayers = runBlocking { playerRepository.players.first() }
        val storedEvenings = runBlocking { eveningRepository.evenings.first() }
        // Gespeichert ist genau, was das Ergebnis nennt; ohne Fehler ist das dasselbe wie in der Vorschau.
        check(storedPlayers == knownPlayers + result.newPlayers) { "anders gespeichert als gemeldet" }
        check(storedEvenings.map { it.id }.toSet() == (knownEvenings + result.newEvenings).map { it.id }.toSet())
        if (result.error == null) {
            check(result.newPlayers == preview.newPlayers && result.newEvenings == preview.newEvenings)
            check(result.existingEvenings == preview.existingEvenings && result.invalidEvenings == preview.invalidEvenings)
        }
        return importResultJson(result)
    }

    /**
     * Was `BackupService.import` meldet, wenn das Speichern unterwegs scheitert: beim Spieler Nr. [failPlayerAt] bzw.
     * beim Abend Nr. [failEveningAt] (gezählt ab 0; -1 heißt: nie).
     */
    private fun failingImportJson(text: String, knownPlayers: List<Player>, knownEvenings: List<Evening>, failPlayerAt: Int, failEveningAt: Int): JsonObject {
        val players = FailingPlayerRepository(knownPlayers, failPlayerAt)
        val evenings = FailingEveningRepository(knownEvenings, failEveningAt)
        val service = BackupService(players, evenings, InMemorySettingsRepository(), clock, "golden", Dispatchers.Unconfined)
        return importResultJson(runBlocking { service.import(text) })
    }

    private fun importResultJson(result: ImportResult): JsonObject {
        return buildJsonObject {
            put("error", result.error?.kind?.name)
            putJsonArray("newPlayers") { result.newPlayers.forEach { add(it.id.value) } }
            putJsonArray("sameNamePlayers") { result.sameNamePlayers.forEach { add(it.id.value) } }
            put("existingPlayerCount", result.existingPlayerCount)
            putJsonArray("newEvenings") { result.newEvenings.forEach { add(it.id.value) } }
            putJsonArray("existingEvenings") { result.existingEvenings.forEach { add(it.id.value) } }
            putJsonArray("invalidEvenings") { result.invalidEvenings.forEach { add(it.id) } }
        }
    }

    // ---------- Unterschied zweier JSON-Bäume ----------

    /**
     * Die Stellen, an denen sich [new] von [old] unterscheidet, als Liste von `[Pfad, neuer Wert]`. Objekte mit denselben
     * Schlüsseln und Listen gleicher Länge werden Stelle für Stelle verglichen, alles andere als Ganzes ersetzt.
     */
    private fun diff(old: JsonElement, new: JsonElement): JsonArray = buildJsonArray { diffInto(old, new, emptyList(), this) }

    private fun diffInto(old: JsonElement, new: JsonElement, path: List<JsonPrimitive>, out: JsonArrayBuilder) {
        if (old == new) return
        if (old is JsonObject && new is JsonObject && old.keys.toList() == new.keys.toList()) {
            for (key in old.keys) diffInto(old.getValue(key), new.getValue(key), path + JsonPrimitive(key), out)
            return
        }
        if (old is JsonArray && new is JsonArray && old.size == new.size) {
            for (i in old.indices) diffInto(old[i], new[i], path + JsonPrimitive(i), out)
            return
        }
        out.addJsonArray {
            add(JsonArray(path))
            add(new)
        }
    }

    // ---------- Verfälschte Fassungen ----------

    /** Eine Stelle im Text: ein Wert mit seinem Feld (`"key":value`) bzw. ein Listenelement. */
    private class Slot(
        val path: String,
        val key: String?,
        val field: String?,
        val parentField: String?,
        val valueStart: Int,
        val valueEnd: Int,
        val itemStart: Int,
        val itemEnd: Int,
        val index: Int,
        val siblings: Int,
    )

    private class ObjectStart(val path: String, val position: Int, val size: Int)

    private class Spanned(val text: String, val slots: List<Slot>, val objects: List<ObjectStart>)

    private class Mutation(val what: String, val start: Int, val end: Int, val put: String)

    /** Schreibt den Baum so kompakt wie kotlinx.serialization und merkt sich dabei, wo jede Stelle im Text steht. */
    private fun spanned(root: JsonElement): Spanned {
        val out = StringBuilder()
        val slots = ArrayList<Slot>()
        val objects = ArrayList<ObjectStart>()
        fun write(element: JsonElement, path: String, field: String?) {
            when (element) {
                is JsonObject -> {
                    objects += ObjectStart(path, out.length + 1, element.size)
                    out.append('{')
                    var position = 0
                    for ((key, value) in element) {
                        if (position > 0) out.append(',')
                        val itemStart = out.length
                        out.append(JsonPrimitive(key).toString()).append(':')
                        val valueStart = out.length
                        val childPath = if (path.isEmpty()) key else "$path.$key"
                        write(value, childPath, key)
                        slots += Slot(childPath, key, key, field, valueStart, out.length, itemStart, out.length, position, element.size)
                        position++
                    }
                    out.append('}')
                }

                is JsonArray -> {
                    out.append('[')
                    element.forEachIndexed { position, value ->
                        if (position > 0) out.append(',')
                        val start = out.length
                        write(value, "$path[$position]", field)
                        slots += Slot("$path[$position]", null, field, field, start, out.length, start, out.length, position, element.size)
                    }
                    out.append(']')
                }

                else -> out.append(element.toString())
            }
        }
        write(root, "", null)
        check(out.toString() == root.toString()) { "die eigene kompakte Schreibweise weicht von kotlinx.serialization ab" }
        return Spanned(out.toString(), slots, objects)
    }

    private fun mutations(spanned: Spanned, include: (String) -> Boolean, fragments: List<String>): List<Mutation> {
        val result = ArrayList<Mutation>()
        for (slot in spanned.slots) {
            if (!include(slot.path)) continue
            for (fragment in fragments + extraFragments(slot.field)) {
                result += Mutation("${slot.path} = $fragment", slot.valueStart, slot.valueEnd, fragment)
            }
            val removal = when {
                slot.siblings == 1 -> slot.itemStart to slot.itemEnd
                slot.index < slot.siblings - 1 -> slot.itemStart to slot.itemEnd + 1
                else -> slot.itemStart - 1 to slot.itemEnd
            }
            result += Mutation("${slot.path} entfernt", removal.first, removal.second, "")
            val key = slot.key
            if (key != null) {
                val quotedKey = JsonPrimitive(key).toString()
                for (fragment in DUPLICATE_FRAGMENTS) {
                    result += Mutation("${slot.path} doppelt davor $fragment", slot.itemStart, slot.itemStart, "$quotedKey:$fragment,")
                    result += Mutation("${slot.path} doppelt danach $fragment", slot.itemEnd, slot.itemEnd, ",$quotedKey:$fragment")
                }
                for (replacement in keyFragments(slot.parentField)) {
                    result += Mutation("${slot.path} Schlüssel $replacement", slot.itemStart, slot.itemStart + quotedKey.length, replacement)
                }
            } else {
                val item = spanned.text.substring(slot.itemStart, slot.itemEnd)
                result += Mutation("${slot.path} verdoppelt", slot.itemEnd, slot.itemEnd, ",$item")
            }
        }
        for (start in spanned.objects) {
            if (!include(start.path)) continue
            for (fragment in UNKNOWN_FRAGMENTS) {
                val separator = if (start.size > 0) "," else ""
                result += Mutation("${start.path.ifEmpty { "Wurzel" }} + unbekannt $fragment", start.position, start.position, "\"zz\":$fragment$separator")
            }
        }
        return result
    }

    private fun extraFragments(field: String?): List<String> = when (field) {
        "startedAt", "finishedAt", "recordedAt", "exportedAt" -> INSTANT_FRAGMENTS
        "gameType", "enabledTypes" -> listOf("\"ueber_kreuz\"", "\"geier\"", "\"RUFSPIEL\"", "rufspiel", "\"ramsch\"")
        "type" -> listOf("\"thrown_in\"", "\"bock\"", "\"manual\"", "\"GAME\"", "game", "\"pause\"")
        "suit" -> listOf("\"schellen\"", "\"HERZ\"", "herz", "\"laub\"")
        "level" -> listOf("\"schwarz\"", "\"Normal\"", "normal")
        "announcement" -> listOf("\"tout\"", "\"sie\"", "\"TOUT\"", "\"bettel\"")
        "kontra" -> listOf("\"retour\"", "\"none\"", "kontra", "\"sub\"")
        "bockOverride" -> listOf("\"force_on\"", "\"force_off\"", "\"AUTO\"")
        "reason" -> listOf("\"manual\"", "\"SECHS_NIXER\"", "manual", "\"laune\"")
        "stacking" -> listOf("\"stack\"", "\"ignore\"", "\"Append\"")
        "payoutRule" -> listOf("\"pot_split\"", "\"POT_SPLIT\"", "pot_split")
        "format" -> listOf("\"schafkopf-tracker-backup \"", "\"SCHAFKOPF-TRACKER-BACKUP\"", "schafkopf-tracker-backup")
        "declarerWon", "archived", "handThrownIn", "klopfenEnabled", "kontraEnabled", "retourEnabled", "enabled",
        "thrownInConsumesBockGame", "dealerAdvancesAfterThrowIn", "displayAsMoney",
        -> listOf("\"false\"", "False", "fAlSe", "\"TRUE\"", "\"fal\u017Fe\"", "\" true\"", "true1", "\"true\" ")
        "id", "name", "declarer", "partner", "note", "location", "firstDealer", "seating", "klopfer", "kontraBy", "retourBy",
        "player", "appVersion",
        -> listOf("\"a\\u0000b\"", "\"\\uD83D\\uDE00\"", "\"\\uD800\"", "\"\\x\"", "\"a\nb\"", "\"tab\tx\"", "\"\\/\\\"\\\\\"", "\"\\u00e4\\u00C4\"", "\"\\u12\"")
        else -> emptyList()
    }

    private fun keyFragments(parentField: String?): List<String> = when (parentField) {
        "prices", "laufendeMin", "laufendeMax" -> listOf("\"ramsch\"", "\"RUFSPIEL\"", "\"\"", "\"ueber_kreuz\"", "\"wenz\"")
        "amounts" -> listOf("\"\"", "\"E\"", "\"1\"", "\"A\"")
        else -> emptyList()
    }

    /** Verschiebt in jedem Eintrag die Art (`type`) ans Ende: Dann liest kotlinx.serialization ihn über den Baum. */
    private fun moveEntryTypeToEnd(evening: JsonElement): JsonElement {
        val root = evening as JsonObject
        val entries = (root.getValue("entries") as JsonArray).map { element ->
            val entry = element as JsonObject
            JsonObject(LinkedHashMap(entry - "type").apply { put("type", entry.getValue("type")) })
        }
        return JsonObject(LinkedHashMap(root).apply { put("entries", JsonArray(entries)) })
    }

    /** Womit jede Stelle der Reihe nach ersetzt wird: Werte der falschen Art und Grenzfälle. */
    private val VALUE_FRAGMENTS = listOf(
        "null", "true", "false", "TRUE", "\"true\"", "0", "-0", "1", "-1", "01", "1.0", "1e0", "10e-1", "2147483647",
        "2147483648", "-2147483649", "92233720368547758080", "\"5\"", "\" 5\"", "\"\"", "\"x\"", "x", "[]", "{}", "[\"x\"]",
        "{\"x\":1}",
    )

    private val BACKUP_FRAGMENTS = listOf(
        "null", "true", "\"true\"", "0", "-1", "1.0", "1e0", "2147483648", "\"5\"", "\" 5\"", "\"\"", "\"x\"", "x", "[]", "{}",
    )

    private val DUPLICATE_FRAGMENTS = listOf("null", "\"x\"", "5")

    private val UNKNOWN_FRAGMENTS = listOf("[1 2 : x]", "hallo", "{\"type\":\"game\"}")

    private val INSTANT_FRAGMENTS = listOf(
        "\"2026-11-14T19:00:00.000Z\"", "\"2026-11-14T20:00:00+01:00\"", "\"2026-11-14t19:00:00z\"", "\"2026-11-14T19:00:00\"",
        "\"2026-11-14T24:00:00Z\"", "\"2026-12-31T23:59:60Z\"", "\"2025-02-29T00:00:00Z\"", "\"+292278994-08-17T07:12:55.808Z\"",
        "\"2026-11-14T19:00:00.Z\"", "\"2026-11-14T19:00:00,5Z\"",
    )

    /** Ein Abend mit jeder Eintragsart und vielen freiwilligen Angaben, als Ausgangspunkt für die verfälschten Fassungen. */
    private const val EVENING_BASE = """{"schemaVersion":1,"id":"abend-1","startedAt":"2026-11-14T19:00:00Z","finishedAt":"2026-11-14T23:30:00.5Z",""" +
        """"location":"Post","seating":["A","B","C","D"],"firstDealer":"B","rules":{"unit":5},"entries":[""" +
        """{"type":"game","id":"e1","recordedAt":"2026-11-14T19:10:00Z","gameType":"rufspiel","suit":"eichel","declarer":"A",""" +
        """"partner":"B","declarerWon":true,"level":"schneider","laufende":3,"announcement":"none","klopfer":["C"],""" +
        """"kontra":"kontra","kontraBy":"D","retourBy":"A","bockOverride":"auto","declarerEyes":95,"note":"n"},""" +
        """{"type":"thrown_in","id":"e2","recordedAt":"2026-11-14T19:15:00Z"},""" +
        """{"type":"bock","id":"e3","recordedAt":"2026-11-14T19:20:00Z","reason":"sechs_nixer","player":"C","handThrownIn":false},""" +
        """{"type":"manual","id":"e4","recordedAt":"2026-11-14T19:25:00Z","amounts":{"A":100,"D":-100},"note":"Wette"}]}"""

    /** Eine kleine Sicherung als Ausgangspunkt für die verfälschten Fassungen. */
    private const val BACKUP_BASE = """{"format":"schafkopf-tracker-backup","schemaVersion":1,"appVersion":"0.1.0",""" +
        """"exportedAt":"2026-11-15T10:00:00Z","players":[{"id":"spieler-anna","name":"Anna","colorIndex":0,"archived":false},""" +
        """{"id":"spieler-bertl","name":"Bertl","colorIndex":1,"archived":true}],"evenings":[{"schemaVersion":1,"id":"abend-1",""" +
        """"startedAt":"2026-11-14T19:00:00Z","seating":["spieler-anna","spieler-bertl","C","D"],"firstDealer":"C",""" +
        """"rules":{"unit":5},"entries":[{"type":"game","id":"e1","recordedAt":"2026-11-14T19:10:00Z","gameType":"rufspiel",""" +
        """"declarer":"spieler-anna","partner":"C","declarerWon":true,"laufende":3,"klopfer":["D"]},""" +
        """{"type":"manual","id":"e2","recordedAt":"2026-11-14T19:20:00Z","amounts":{"spieler-bertl":50,"D":-50}}]}],""" +
        """"defaultRules":{"name":"Haus","unit":5,"prices":{"rufspiel":20}}}"""
}
