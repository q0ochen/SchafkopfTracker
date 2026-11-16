package io.github.q0ochen.schafkopf.data.golden

import io.github.q0ochen.schafkopf.data.backup.BackupFormat
import io.github.q0ochen.schafkopf.data.storage.EveningDto
import io.github.q0ochen.schafkopf.data.storage.RuleSetDto
import io.github.q0ochen.schafkopf.data.storage.StorageCodec
import io.github.q0ochen.schafkopf.data.storage.TestData
import io.github.q0ochen.schafkopf.data.storage.toDto
import io.github.q0ochen.schafkopf.domain.evening.BockOverride
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.BockRules
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.PayoutRule
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.ScoreCalculator
import io.github.q0ochen.schafkopf.domain.rules.Suit
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Instant
import java.util.Random

/** Unsichtbare Markierung am Dateianfang (U+FEFF), über die Zeichennummer gebildet. */
internal val BOM: String = Char(0xFEFF).toString()

/**
 * Die handgeschriebenen Fälle der Golden-Daten für die Web-App und ihre Bausteine: besondere Texte, Kennungen,
 * Zeitpunkte und Regelwerke sowie Zufallsdaten mit festem Startwert. Siehe WebFormatGoldenTest.
 */
internal object GoldenCases {

    private val prettyJson = Json(StorageCodec.json) { prettyPrint = true }
    private val exportedAt: Instant = Instant.parse("2026-11-15T10:00:00Z")

    // ---------- Lesen: Abend, Regelwerk, Spieler, Einstellungen ----------

    fun handwrittenEveningTexts(): List<Pair<String, String>> {
        val minimal = """{"id":"x","startedAt":"2026-11-14T19:00:00Z","seating":["A","B","C","D"],"firstDealer":"A"}"""
        val head = """"id":"x","startedAt":"2026-11-14T19:00:00Z","seating":["A","B","C","D"],"firstDealer":"A""""

        fun withEntries(vararg entries: String) = """{$head,"entries":[${entries.joinToString(",")}]}"""

        fun withField(extra: String) = "{$head,$extra}"
        val game = """"id":"e1","recordedAt":"2026-11-14T19:10:00Z","gameType":"wenz","declarer":"A","declarerWon":true"""
        val full = StorageCodec.encodeEvening(TestData.fullEvening())
        val texts = ArrayList<Pair<String, String>>()
        texts += "minimal" to minimal
        texts += "voll" to full
        texts += "voll-huebsch" to prettyJson.encodeToString(EveningDto.serializer(), TestData.fullEvening().toDto())
        texts += "voll-crlf-tabs" to prettyJson.encodeToString(EveningDto.serializer(), TestData.fullEvening().toDto())
            .replace("\n", "\r\n").replace("    ", "\t")
        texts += "leer" to ""
        texts += "nur-leerraum" to "   \n\t\r"
        texts += "null" to "null"
        texts += "zahl" to "42"
        texts += "text" to "\"abend\""
        texts += "liste" to "[]"
        texts += "leeres-objekt" to "{}"
        texts += "bom" to BOM + minimal
        texts += "bom-doppelt" to BOM + BOM + minimal
        texts += "bom-nach-leerraum" to " " + BOM + minimal
        texts += "leerraum-aussen" to " \r\n\t$minimal \n"
        texts += "anderer-leerraum" to "\u000c$minimal"
        texts += "nbsp-davor" to "\u00a0$minimal"
        texts += "text-danach" to minimal + "x"
        texts += "zwei-objekte" to minimal + minimal
        texts += "komma-danach" to "$minimal,"
        texts += "abgeschnitten" to minimal.dropLast(1)
        texts += "halb" to full.take(full.length / 2)
        texts += "fehlendes-komma" to minimal.replace("\"x\",", "\"x\" ")
        texts += "fehlendes-komma-ganz" to minimal.replace("\"x\",", "\"x\"")
        texts += "fehlendes-komma-liste" to minimal.replace("\"A\",\"B\"", "\"A\" \"B\"")
        texts += "fehlender-doppelpunkt" to minimal.replace("\"id\":", "\"id\" ")
        texts += "komma-am-ende" to minimal.replace("\"A\"}", "\"A\",}")
        texts += "komma-am-ende-liste" to minimal.replace("\"D\"]", "\"D\",]")
        texts += "komma-am-anfang" to minimal.replace("{\"id\"", "{,\"id\"")
        texts += "komma-am-anfang-liste" to minimal.replace("[\"A\"", "[,\"A\"")
        texts += "doppeltes-komma" to minimal.replace("\"x\",", "\"x\",,")
        texts += "kommentar-block" to minimal.replace("{\"id\"", "{/* c */\"id\"")
        texts += "kommentar-zeile" to minimal.replace("{\"id\"", "{// c\n\"id\"")
        texts += "einfache-anfuehrungszeichen" to minimal.replace("\"id\":\"x\"", "'id':'x'")
        texts += "schluessel-ohne-anfuehrungszeichen" to minimal.replace("\"id\":", "id:")
        texts += "doppelte-schluessel" to minimal.replace("{\"id\":\"x\"", "{\"id\":\"y\",\"id\":\"x\"")
        texts += "doppelte-schluessel-erst-falsch" to minimal.replace("{\"id\":\"x\"", "{\"id\":5,\"id\":\"x\"")
        texts += "doppelte-schluessel-dann-falsch" to minimal.replace("{\"id\":\"x\"", "{\"id\":\"x\",\"id\":5")
        texts += "doppelte-liste" to minimal.replace("{\"id\":\"x\"", "{\"seating\":[\"Q\"],\"id\":\"x\"")
        val escapes = listOf(
            "\"a\\nb\"", "\"\\u0041\\u00e4\\u00C4\"", "\"\\/\"", "\"\\\"\"", "\"\\\\\"", "\"\\b\\f\\r\\t\"", "\"\\x\"", "\"\\u12\"",
            "\"\\u12G4\"", "\"\\uD83D\\uDE00\"", "\"\\uD800\"", "\"\\uDC00x\"", "\"a\tb\"", "\"a\u0000b\"", "\"a\nb\"", "\"\\'\"",
            "\"\\U0041\"", "\"\\\"", "\"abc", "\"\\u0041", "\"\\", "\"\u007f\u0085\u2028\"", "\"\\u000\"", "\"\\u00411\"",
        )
        escapes.forEachIndexed { index, value -> texts += "escape-$index" to minimal.replace("\"id\":\"x\"", "\"id\":$value") }
        texts += "unbekannt-tief" to withField("\"zz\":{\"a\":[1,{\"b\":null,\"c\":[true,false,\"x\"]}],\"d\":-1.5e3}")
        texts += "unbekannt-literal" to withField("\"zz\":hallo")
        texts += "unbekannt-literal-zwei" to withField("\"zz\":hallo welt")
        texts += "unbekannt-komma" to minimal.replace("{\"id\"", "{\"zz\":,\"id\"")
        texts += "unbekannt-kaputt-innen" to withField("\"zz\":[1 2 : , x}")
        texts += "unbekannt-unordentlich" to withField("\"zz\":[1 2 : , , x y \\ ]")
        texts += "unbekannt-steuerzeichen" to withField("\"zz\":[\u0001]")
        texts += "unbekannt-text-kaputt" to withField("\"zz\":[\"\\x\"]")
        texts += "unbekannt-zahl-kaputt" to withField("\"zz\":1.2.3e")
        texts += "unbekannt-tief-400" to withField("\"zz\":" + "[".repeat(400) + "]".repeat(400))
        texts += "unbekannt-tief-5000" to withField("\"zz\":" + "[".repeat(5000) + "]".repeat(5000))
        texts += "unbekannt-offen" to withField("\"zz\":[[[")
        texts += "unbekannt-objekt-tief" to withField("\"zz\":" + "{\"a\":".repeat(300) + "1" + "}".repeat(300))
        for (version in listOf("2", "99", "0", "-1", "\"1\"", "\"2\"", "1.0", "1e0", "10e-1", "2e0", "2147483648", "null", "true", "\"\"", "01", "-0")) {
            texts += "version-$version" to minimal.replace("{\"id\"", "{\"schemaVersion\":$version,\"id\"")
        }
        texts += "version-2-falsches-feld" to """{"schemaVersion":2,"id":5}"""
        texts += "version-2-unvollstaendig" to """{"schemaVersion":2}"""
        texts += "version-2-kaputte-zeit" to minimal.replace("{\"id\"", "{\"schemaVersion\":2,\"id\"").replace("2026-11-14T19:00:00Z", "gestern")
        texts += "version-0-kaputte-zeit" to minimal.replace("{\"id\"", "{\"schemaVersion\":0,\"id\"").replace("2026-11-14T19:00:00Z", "gestern")
        texts += "version-hinten" to minimal.replace("\"A\"}", "\"A\",\"schemaVersion\":2}")
        // Einträge und die Art des Eintrags
        texts += "eintrag-typ-hinten" to withEntries("{$game,\"type\":\"game\"}")
        texts += "eintrag-typ-fehlt" to withEntries("{$game}")
        texts += "eintrag-typ-null" to withEntries("{\"type\":null,$game}")
        texts += "eintrag-typ-zahl" to withEntries("{\"type\":5,$game}")
        texts += "eintrag-typ-unbekannt" to withEntries("{\"type\":\"pause\",$game}")
        texts += "eintrag-typ-gross" to withEntries("{\"type\":\"GAME\",$game}")
        texts += "eintrag-typ-ohne-anfuehrungszeichen" to withEntries("{\"type\":game,$game}")
        texts += "eintrag-typ-escape" to withEntries("{\"typ\\u0065\":\"g\\u0061me\",$game}")
        texts += "eintrag-typ-doppelt-vorn" to withEntries("{\"type\":\"game\",$game,\"type\":\"bock\"}")
        texts += "eintrag-typ-doppelt-hinten" to withEntries("{$game,\"type\":\"bock\",\"type\":\"game\"}")
        texts += "eintrag-typ-objekt" to withEntries("{$game,\"type\":{}}")
        texts += "eintrag-typ-liste" to withEntries("{\"type\":[\"game\"],$game}")
        texts += "eintrag-zahl" to withEntries("5")
        texts += "eintrag-null" to withEntries("null")
        texts += "eintrag-liste" to withEntries("[]")
        texts += "eintrag-leer" to withEntries("{}")
        texts += "eintrag-leerraum-typ" to withEntries("{ \"type\" : \"game\" , $game }")
        // Liest Kotlin einen Eintrag über den Baum, beginnt die Zählung der Verschachtelung nicht bei null.
        for (depth in listOf(396, 397, 398, 399)) {
            val nested = "[".repeat(depth) + "]".repeat(depth)
            texts += "eintrag-tief-hinten-$depth" to withEntries("{$game,\"zz\":$nested,\"type\":\"game\"}")
            texts += "eintrag-tief-vorn-$depth" to withEntries("{\"type\":\"game\",$game,\"zz\":$nested}")
        }
        // Unterschiede zwischen dem schnellen Weg (Art vorn) und dem Weg über den Baum (Art hinten)
        val quirks = listOf(
            "\"declarerWon\":\"TRUE\"", "\"declarerWon\":TRUE", "\"declarerWon\":\"fal\u017Fe\"", "\"declarerWon\":\" true\"",
            "\"laufende\":\" 3\"", "\"laufende\":\"3 \"", "\"laufende\":\"\\\"3\\\"\"", "\"gameType\":wenz", "\"declarer\":A",
            "\"declarerEyes\":\"95\"", "\"declarerEyes\":null", "\"suit\":null", "\"suit\":herz", "\"note\":null", "\"laufende\":1e1",
            "\"laufende\":\"1e1\"", "\"laufende\":-0", "\"klopfer\":[\"A\",\"A\",\"B\"]", "\"klopfer\":[A]", "\"klopfer\":null",
            "\"partner\":\"\"", "\"declarerWon\":\"true\"", "\"declarerWon\":tRuE", "\"declarerWon\":True ",
        )
        quirks.forEachIndexed { index, field ->
            texts += "schnell-$index" to withEntries("{\"type\":\"game\",$game,$field}")
            texts += "baum-$index" to withEntries("{$game,$field,\"type\":\"game\"}")
        }
        val thrown = """"type":"thrown_in","id":"t","recordedAt":"2026-11-14T19:11:00Z""""
        val bock = """"type":"bock","id":"b","recordedAt":"2026-11-14T19:12:00Z","reason":"sechs_nixer""""
        val manual = """"type":"manual","id":"m","recordedAt":"2026-11-14T19:13:00Z""""
        texts += "alle-arten-minimal" to withEntries("{$thrown}", "{$bock}", "{$manual,\"amounts\":{}}", "{\"type\":\"game\",$game}")
        texts += "buchung-ohne-betraege" to withEntries("{$manual}")
        val amounts = listOf(
            "{}", "{\"1\":5,\"0\":-5}", "{\"A\":1,\"B\":-1,\"A\":2}", "{\"A\":\"5\"}", "{\"A\":5.0}", "{\"A\":null}", "{\"A\":1,}",
            "{\"A\" 1}", "{\"A\":1 \"B\":2}", "{,\"A\":1}", "{\"A\":1,,\"B\":2}", "{\"__proto__\":5,\"constructor\":-5}", "{\"\":0}",
            "{\"A\":2147483647,\"B\":-2147483648}", "{\"A\":2147483648}", "{\"A\":\"1e2\"}", "{\"A\":\" 5\"}", "[]", "null", "{A:1}",
            "{\"A\":true}", "{\"A\":{}}", "{\"A\":[1]}",
        )
        amounts.forEachIndexed { index, value ->
            texts += "betraege-$index" to withEntries("{$manual,\"amounts\":$value}")
            texts += "betraege-baum-$index" to withEntries("{\"id\":\"m\",\"amounts\":$value,\"recordedAt\":\"2026-11-14T19:13:00Z\",\"type\":\"manual\"}")
        }
        texts += "bock-ohne-grund" to withEntries("{\"type\":\"bock\",\"id\":\"b\",\"recordedAt\":\"2026-11-14T19:12:00Z\"}")
        texts += "spiel-ohne-ergebnis" to withEntries("{\"type\":\"game\",\"id\":\"e1\",\"recordedAt\":\"2026-11-14T19:10:00Z\",\"gameType\":\"wenz\",\"declarer\":\"A\"}")
        texts += "spiel-ohne-art" to withEntries("{\"type\":\"game\",\"id\":\"e1\",\"recordedAt\":\"2026-11-14T19:10:00Z\",\"declarer\":\"A\",\"declarerWon\":true}")
        texts += "eintrag-ohne-zeit" to withEntries("{\"type\":\"thrown_in\",\"id\":\"t\"}")
        texts += "eintrag-ohne-kennung" to withEntries("{\"type\":\"thrown_in\",\"recordedAt\":\"2026-11-14T19:11:00Z\"}")
        // Felder des Abends
        val fields = listOf(
            "\"finishedAt\":null", "\"finishedAt\":\"\"", "\"location\":null", "\"location\":5", "\"rules\":null", "\"rules\":{}",
            "\"rules\":{\"bock\":{}}", "\"rules\":{\"bock\":null}", "\"rules\":{\"prices\":{}}", "\"rules\":{\"prices\":null}",
            "\"rules\":{\"enabledTypes\":[]}", "\"rules\":{\"enabledTypes\":[\"wenz\",\"wenz\",\"geier\"]}", "\"rules\":[]",
            "\"entries\":null", "\"entries\":{}", "\"entries\":[]",
        )
        fields.forEachIndexed { index, field -> texts += "feld-$index" to withField(field) }
        val seatings = listOf("[]", "[\"A\"]", "[null]", "\"ABCD\"", "null", "[\"A\",\"A\",\"A\",\"A\"]", "[1,2,3,4]", "[\"\",\" \"]")
        seatings.forEachIndexed { index, seating -> texts += "sitze-$index" to minimal.replace("[\"A\",\"B\",\"C\",\"D\"]", seating) }
        texts += "ohne-geber" to minimal.replace(",\"firstDealer\":\"A\"", "")
        texts += "geber-null" to minimal.replace("\"firstDealer\":\"A\"", "\"firstDealer\":null")
        texts += "ohne-kennung" to minimal.replace("\"id\":\"x\",", "")
        texts += "ohne-beginn" to minimal.replace("\"startedAt\":\"2026-11-14T19:00:00Z\",", "")
        texts += "ohne-sitze" to minimal.replace("\"seating\":[\"A\",\"B\",\"C\",\"D\"],", "")
        // Zahlen an einer Stelle, an der eine ganze Zahl stehen muss
        val numbers = listOf(
            "-0", "007", "1e1", "1E+1", "100e-1", "1e-1", "1.0", "2147483647", "2147483648", "-2147483648", "-2147483649",
            "9223372036854775807", "9223372036854775808", "92233720368547758080", "18446744073709551616", "1e19", "0e400",
            "0e308", "\"\"", "-", "+1", "1e", "1e+", "1e-", "1-", "--1", "1ee2", "30e-1", "\"30e-1\"", "0x10", "Infinity", "NaN",
            "\"-0\"", "1e9", "1e10", "-1e9", "123456789e-9", "1000000e-6", "5e-0", "\"5\"x",
        )
        numbers.forEachIndexed { index, number -> texts += "zahl-$index" to withEntries("{\"type\":\"game\",$game,\"laufende\":$number}") }
        return texts
    }

    fun handwrittenRuleTexts(): List<Pair<String, String>> {
        val texts = ArrayList<Pair<String, String>>()
        for ((name, rules) in specialRules()) texts += "geschrieben-$name" to StorageCodec.encodeRules(rules)
        texts += "huebsch" to prettyJson.encodeToString(RuleSetDto.serializer(), TestData.fullRules.toDto())
        val raw = listOf(
            "{}", "{\"unit\":5}", "", "null", "[]", "\"x\"", "{\"unit\":null}", "{\"bock\":{}}", "{\"bock\":null}", "{\"bock\":[]}",
            "{\"bock\":{\"stacking\":\"stack\",\"factor\":\"3\"}}", "{\"payoutRule\":\"POT_SPLIT\"}", "{\"payoutRule\":\"pot_split\"}",
            "{\"prices\":{\"ramsch\":5}}", "{\"prices\":{\"rufspiel\":\"5\"}}", "{\"prices\":{\"rufspiel\":5,\"rufspiel\":6,\"wenz\":1}}",
            "{\"prices\":{\"ueber_kreuz\":15}}", "{\"enabledTypes\":[\"wenz\",\"rufspiel\",\"wenz\"]}", "{\"enabledTypes\":[\"ramsch\"]}",
            "{\"enabledTypes\":\"wenz\"}", "{\"enabledTypes\":[null]}", "{\"laufendeMin\":{}}", "{\"laufendeMax\":{\"geier\":-1}}",
            "{\"name\":\"\\u00c4\",\"zusatz\":[1,2,{}]}", "{\"klopfenEnabled\":\"false\"}", "{\"klopfenEnabled\":FALSE}",
            "{\"toutFactor\":2147483648}", "{\"sieLaufende\":-2147483648}", "{\"unit\":5,}", "{\"unit\":5 \"name\":\"x\"}",
            "$BOM{\"unit\":5}", "{\"unit\":5}x", "{\"prices\":{\"rufspiel\":1 \"wenz\":2}}", "{\"prices\":[]}",
        )
        raw.forEachIndexed { index, text -> texts += "roh-$index" to text }
        return texts
    }

    fun handwrittenPlayerTexts(): List<Pair<String, String>> {
        val texts = ArrayList<Pair<String, String>>()
        texts += "geschrieben" to StorageCodec.encodePlayers(TestData.players)
        texts += "geschrieben-leer" to StorageCodec.encodePlayers(emptyList())
        val raw = listOf(
            "{}", "{\"players\":[]}", "{\"players\":null}", "{\"players\":{}}", "{\"players\":[{\"id\":\"p1\",\"name\":\"Xaver\"}]}",
            "{\"players\":[{\"name\":\"ohne Kennung\"}]}", "{\"players\":[{\"id\":\"p1\"}]}", "{\"players\":[{\"id\":\"\",\"name\":\"leer\"}]}",
            "{\"players\":[{\"id\":\"p\",\"name\":\"a\"},{\"id\":\"p\",\"name\":\"b\"}]}", "{\"schemaVersion\":2,\"players\":[]}",
            "{\"schemaVersion\":0,\"players\":[]}", "{\"schemaVersion\":-5}", "{\"schemaVersion\":\"2\"}", "{\"schemaVersion\":2,\"players\":5}",
            "$BOM{\"players\":[]}", "{\"players\":[{\"id\":\"p1\",\"name\":\"X\",\"colorIndex\":\"7\",\"archived\":\"TRUE\"}]}",
            "{\"players\":[{\"id\":\"p1\",\"name\":\"X\",\"colorIndex\":7.5}]}", "{\"players\":[{\"id\":\"p1\",\"name\":\"X\",\"archived\":null}]}",
            "{\"players\":[{\"id\":\"p1\",\"name\":\"X\",\"spitzname\":\"Y\"}],\"sortierung\":\"name\"}", "[]", "", "null",
            "{\"players\":[null]}", "{\"players\":[{\"id\":5,\"name\":\"X\"}]}", "{\"players\":[{\"id\":\"p1\",\"name\":null}]}",
        )
        raw.forEachIndexed { index, text -> texts += "roh-$index" to text }
        return texts
    }

    fun handwrittenSettingsTexts(): List<Pair<String, String>> {
        val texts = ArrayList<Pair<String, String>>()
        texts += "standard" to StorageCodec.encodeSettings(AppSettings())
        texts += "eigene" to StorageCodec.encodeSettings(TestData.customSettings)
        for (mode in ThemeMode.entries) {
            texts += "thema-${mode.name}" to StorageCodec.encodeSettings(AppSettings(themeMode = mode, keepScreenOn = false, defaultRules = RuleSet.sauspiel()))
        }
        val raw = listOf(
            "{}", "{\"themeMode\":\"light\"}", "{\"themeMode\":\"LIGHT\"}", "{\"haptics\":\"x\"}", "{\"haptics\":false}",
            "{\"ai\":{\"provider\":\"orakel\"}}", "{\"ai\":null}", "{\"ai\":{\"temperatur\":0.7}}", "{\"defaultRules\":{}}",
            "{\"defaultRules\":null}", "{\"keepScreenOn\":\"false\"}", "{\"keepScreenOn\":null}", "{\"schemaVersion\":2}",
            "{\"schemaVersion\":0}", "$BOM{\"themeMode\":\"system\"}", "{\"sprache\":\"bairisch\",\"themeMode\":\"system\"}",
            "", "[]", "{\"defaultRules\":{\"unit\":5},\"themeMode\":\"dark\"}", "{\"ai\":{\"provider\":\"openai_compatible\",\"model\":5}}",
        )
        raw.forEachIndexed { index, text -> texts += "roh-$index" to text }
        return texts
    }

    // ---------- Sicherung ----------

    /** Eine Sicherung und der Stand des Geräts, auf dem sie eingespielt wird. */
    class BackupCase(val id: String, val text: String, val knownPlayers: List<Player>, val knownEvenings: List<Evening>)

    private fun playerElement(player: Player): JsonObject = buildJsonObject {
        put("id", player.id.value)
        put("name", player.name)
        put("colorIndex", player.colorIndex)
        put("archived", player.archived)
    }

    private fun backupText(
        players: List<JsonElement> = TestData.players.map { playerElement(it) },
        evenings: List<JsonElement>,
        format: JsonElement = JsonPrimitive("schafkopf-tracker-backup"),
        extra: Map<String, JsonElement> = emptyMap(),
    ): String = JsonObject(
        linkedMapOf<String, JsonElement>(
            "format" to format,
            "schemaVersion" to JsonPrimitive(1),
            "appVersion" to JsonPrimitive("0.1.0"),
            "exportedAt" to JsonPrimitive("2026-11-15T10:00:00Z"),
            "players" to JsonArray(players),
            "evenings" to JsonArray(evenings),
        ) + extra,
    ).toString()

    private fun eveningElement(evening: Evening): JsonObject = Json.parseToJsonElement(StorageCodec.encodeEvening(evening)) as JsonObject

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

    fun handwrittenBackups(): List<BackupCase> {
        val cases = ArrayList<BackupCase>()
        val archived = Player(PlayerId("spieler-emil"), "Emil", colorIndex = 4, archived = true)
        val start = TestData.start
        val full = TestData.fullEvening("abend-voll", start)
        val simple = TestData.simpleEvening("abend-einfach", start.minusSeconds(7 * 86_400))
        val written = BackupFormat.write(TestData.players + archived, listOf(full, simple), TestData.fullRules, "0.1.0", exportedAt)
        cases += BackupCase("geschrieben-leeres-geraet", written, emptyList(), emptyList())
        cases += BackupCase("geschrieben-alles-vorhanden", written, TestData.players + archived, listOf(full, simple))
        cases += BackupCase(
            "geschrieben-anders-vorhanden",
            written,
            listOf(TestData.anna.copy(name = "Annamirl", colorIndex = 7)),
            listOf(TestData.simpleEvening("abend-voll", start.plusSeconds(60)).copy(location = "Bei uns daheim")),
        )
        cases += BackupCase(
            "geschrieben-platzhalter",
            written,
            TestData.players.mapIndexed { index, player -> player.copy(name = "Spieler ${index + 1}") },
            emptyList(),
        )
        val sameNames = listOf(" anna ", "ANNA", "Bertl", "bertl ", "centa\u00a0", "\u2003done", "\uFEFFDone", "\u001fCenta")
        cases += BackupCase(
            "gleiche-namen",
            written,
            sameNames.mapIndexed { index, name -> Player(PlayerId("hier-$index"), name, index) },
            emptyList(),
        )
        val caseNames = listOf(
            "Stra\u00dfe" to "STRASSE", "\u00df" to "\u1e9e", "\u0130" to "i", "\u0131" to "I", "\u03a3" to "\u03c2", "\u03c3" to "\u03c2",
            "\u01c5" to "\u01c6", "\u00b5" to "\u03bc", "\u212a" to "k", "\u212b" to "\u00e5", "\ufb00" to "FF", "\u10d0" to "\u1c90",
            "\ud801\udc00" to "\ud801\udc28", "\u00ff" to "\u0178", "\u1f80" to "\u1f88", "\u1fb3" to "\u1fbc", "\u0149" to "\u02bcN",
            "\u0390" to "\u0390", "a\u0301" to "\u00e1", "\u00c5" to "\u00e5", "\u0345" to "\u0399", "\u017f" to "s", "\u13a0" to "\uab70",
        )
        val caseBackup = backupText(
            players = caseNames.mapIndexed { index, (name, _) ->
                buildJsonObject {
                    put("id", "s-$index")
                    put("name", name)
                }
            },
            evenings = emptyList(),
        )
        cases += BackupCase("namen-gross-klein", caseBackup, caseNames.mapIndexed { index, (_, other) -> Player(PlayerId("g-$index"), other, 0) }, emptyList())

        val good = TestData.simpleEvening("gut", start)
        val game = good.entries.first() as GameEntry
        val stranger = PlayerId("fremd")

        fun problem(id: String, evening: Evening) = eveningElement(evening.copy(id = EveningId(id)))
        val problems = listOf(
            problem("drei-am-tisch", good.copy(seating = TestData.seating.take(3))),
            problem("fuenf-am-tisch", good.copy(seating = TestData.seating + stranger)),
            problem("niemand-am-tisch", good.copy(seating = emptyList())),
            problem("doppelt-am-tisch", good.copy(seating = listOf(TestData.a, TestData.b, TestData.c, TestData.c))),
            problem("platz-ohne-kennung", good.copy(seating = listOf(TestData.a, TestData.b, TestData.c, PlayerId("")))),
            problem("platz-leerzeichen", good.copy(seating = listOf(TestData.a, TestData.b, TestData.c, PlayerId(" \t")))),
            problem("platz-umbruch", good.copy(seating = listOf(TestData.a, TestData.b, TestData.c, PlayerId("\u2028\u001c")))),
            problem("platz-nbsp", good.copy(seating = listOf(TestData.a, TestData.b, TestData.c, PlayerId("\u00a0")))),
            problem("platz-bom", good.copy(seating = listOf(TestData.a, TestData.b, TestData.c, PlayerId("\uFEFF")))),
            problem("fremder-geber", good.copy(firstDealer = stranger)),
            problem("fremder-ansager", good.copy(entries = listOf(game, game.copy(id = EntryId("e2"), declarer = stranger)))),
            problem("fremder-partner", good.copy(entries = listOf(game.copy(partner = stranger)))),
            problem("fremder-klopfer", good.copy(entries = listOf(game.copy(klopfer = setOf(TestData.a, stranger))))),
            problem("fremder-kontra", good.copy(entries = listOf(game.copy(kontraBy = stranger)))),
            problem("fremder-retour", good.copy(entries = listOf(game.copy(retourBy = stranger)))),
            problem("fremder-bock", good.copy(entries = listOf(game, BockTriggerEntry(EntryId("bock"), start, BockReason.SECHS_NIXER, player = stranger)))),
            problem("fremder-in-buchung", good.copy(entries = listOf(ManualEntry(EntryId("frei"), start, mapOf(TestData.a to 50, stranger to -50), "Wette")))),
            problem("schiefe-buchung", good.copy(entries = listOf(game, ManualEntry(EntryId("frei"), start, mapOf(TestData.a to 50, TestData.b to -40), "Wette")))),
            problem(
                "ueberlauf-buchung",
                good.copy(entries = listOf(ManualEntry(EntryId("frei"), start, mapOf(TestData.a to Int.MAX_VALUE, TestData.b to Int.MAX_VALUE, TestData.c to 2), "Unsinn"))),
            ),
            problem("eigener-mitspieler", good.copy(entries = listOf(game.copy(partner = TestData.a)))),
            problem("eigener-mitspieler-kreuz", good.copy(entries = listOf(game.copy(type = GameType.UEBER_KREUZ, partner = TestData.a)))),
            problem("wenz-mit-mitspieler", good.copy(entries = listOf(game.copy(type = GameType.WENZ, partner = TestData.b)))),
            problem("rufspiel-ohne-mitspieler", good.copy(entries = listOf(game.copy(partner = null)))),
            problem("grundwert-riesig", good.copy(rules = good.rules.copy(prices = good.rules.prices + (GameType.RUFSPIEL to Int.MAX_VALUE)))),
            problem("grundwert-negativ", good.copy(rules = good.rules.copy(prices = good.rules.prices + (GameType.RUFSPIEL to -20)))),
            problem("grundwert-groesster", good.copy(rules = good.rules.copy(prices = good.rules.prices + (GameType.RUFSPIEL to ScoreCalculator.MAX_VALUE)))),
            problem(
                "bock-riesig",
                good.copy(
                    rules = good.rules.copy(bock = BockRules(factor = 1_000_000)),
                    entries = listOf(game.copy(bockOverride = BockOverride.FORCE_ON, klopfer = setOf(TestData.a, TestData.b, TestData.c, TestData.d))),
                ),
            ),
            problem(
                "saldo-ueberlauf",
                good.copy(
                    entries = listOf(
                        game,
                        ManualEntry(EntryId("frei-1"), start, mapOf(TestData.a to 2_000_000_000, TestData.b to -2_000_000_000), "Unsinn"),
                        ManualEntry(EntryId("frei-2"), start, mapOf(TestData.a to 2_000_000_000, TestData.b to -2_000_000_000), "Unsinn"),
                    ),
                ),
            ),
            problem("doppelte-eintragskennung", good.copy(entries = listOf(game, ThrownInEntry(EntryId("e2"), start), game.copy(declarerWon = false)))),
            problem("solo-mit-sie-verloren", good.copy(entries = listOf(game.copy(type = GameType.FARBSOLO, partner = null, announcement = Announcement.SIE, declarerWon = false)))),
            problem("alles-gut", TestData.fullEvening()),
        )
        cases += BackupCase("jedes-problem", backupText(evenings = problems), emptyList(), emptyList())
        cases += BackupCase(
            "jedes-problem-mit-geraet",
            backupText(evenings = problems.filter { it["id"] in listOf(JsonPrimitive("fremder-geber"), JsonPrimitive("alles-gut"), JsonPrimitive("schiefe-buchung")) }),
            listOf(TestData.anna),
            listOf(TestData.simpleEvening("fremder-geber", start.plusSeconds(5))),
        )

        val goodElement = eveningElement(good)
        val entries = goodElement.getValue("entries") as JsonArray
        val unreadable = listOf(
            goodElement.with("id", JsonPrimitive("unbekannte-spielart"))
                .with("entries", JsonArray(listOf((entries[0] as JsonObject).with("gameType", JsonPrimitive("ramsch"))))),
            JsonObject(goodElement.with("id", JsonPrimitive("ohne-geber")) - "firstDealer"),
            goodElement.with("id", JsonPrimitive("falsche-zeit")).with("startedAt", JsonPrimitive("gestern Abend")),
            JsonPrimitive("gar kein Abend"),
            goodElement.with("id", JsonPrimitive("")),
            goodElement.with("id", JsonPrimitive("  ")),
            goodElement.with("id", JsonPrimitive(5)),
            goodElement.with("id", JsonPrimitive("aus-der-zukunft")).with("schemaVersion", JsonPrimitive(2)),
            goodElement.with("id", JsonPrimitive("version-null")).with("schemaVersion", JsonPrimitive(0)),
            goodElement,
            goodElement.with("location", JsonPrimitive("Doppelt")),
            goodElement.with("id", JsonPrimitive("unmoeglicher-beginn")).with("startedAt", JsonPrimitive("+1000000000-12-31T23:59:59.999999999Z")),
            goodElement.with("id", JsonPrimitive("versatz")).with("startedAt", JsonPrimitive("2026-11-14T20:00:00+01:00")),
            goodElement.with("id", JsonPrimitive("bruchteil-null")).with("startedAt", JsonPrimitive("2026-11-14T19:00:00.000Z")),
            JsonNull,
            JsonArray(emptyList()),
            JsonObject(emptyMap()),
            goodElement.with("id", JsonPrimitive("typ-hinten")).with(
                "entries",
                JsonArray(listOf(JsonObject((entries[0] as JsonObject) - "type" + ("type" to JsonPrimitive("game"))))),
            ),
            goodElement.with("id", JsonPrimitive("baum-eigenheiten")).with(
                "entries",
                JsonArray(
                    listOf(
                        JsonObject(
                            linkedMapOf(
                                "type" to JsonPrimitive("game"),
                                "id" to JsonPrimitive("e1"),
                                "recordedAt" to JsonPrimitive("2026-11-14T19:10:00Z"),
                                "gameType" to JsonPrimitive("rufspiel"),
                                "declarer" to JsonPrimitive("spieler-anna"),
                                "partner" to JsonPrimitive("spieler-bertl"),
                                "declarerWon" to JsonPrimitive("TRUE"),
                                "laufende" to JsonPrimitive(" 3"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        cases += BackupCase("unlesbare-abende", backupText(evenings = unreadable), emptyList(), emptyList())
        cases += BackupCase("vorhandener-beschaedigter-abend", backupText(evenings = listOf(JsonObject(goodElement - "seating"), goodElement)), emptyList(), listOf(good))
        cases += BackupCase(
            "doppelt-erst-kaputt",
            backupText(evenings = listOf(goodElement.with("startedAt", JsonPrimitive("kaputt")), goodElement, goodElement)),
            emptyList(),
            emptyList(),
        )

        // Falsches Format, neuere Version, kaputtes JSON
        val wrong = listOf(
            "{\"hallo\":\"welt\"}", "[1, 2, 3]", "\"nur ein Text\"", "42", "null", "true",
            backupText(evenings = emptyList(), format = JsonPrimitive("anderes-programm")),
            backupText(evenings = emptyList(), format = JsonPrimitive(17)),
            backupText(evenings = emptyList(), format = JsonNull),
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":\"eins\"}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":\"1\"}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":0}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":-1}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1.0}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1e0}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":01}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":+1}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":-0}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":null}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":2}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":2147483648}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":{}}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":eins}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1,\"players\":\"keine Liste\"}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1,\"players\":null}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1,\"players\":[{\"name\":\"ohne Kennung\"}]}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1,\"players\":[{\"id\":\"\",\"name\":\"leere Kennung\"}]}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1,\"players\":[{\"id\":\"  \",\"name\":\"leere Kennung\"}]}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1,\"players\":[{\"id\":\"\\u2029\",\"name\":\"x\"}]}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1,\"players\":[{\"id\":\"\\ufeff\",\"name\":\"x\"}]}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1,\"players\":[{\"id\":\"a\",\"name\":\"x\",\"archived\":\"ja\"}]}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1,\"evenings\":{\"kein\":\"Feld\"}}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1,\"evenings\":null}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":1,\"evenings\":\"\"}",
            "{\"format\":\"schafkopf-tracker-backup\",\"schemaVersion\":2,\"players\":5}",
            "{\"format\":\"schafkopf-tracker-backup\"}",
            "{\"format\":\"schafkopf-tracker-backup\",\"defaultRules\":{\"enabledTypes\":[\"ramsch\"]}}",
            "{\"format\":\"schafkopf-tracker-backup\",\"defaultRules\":{\"unit\":5}}",
            "{\"format\":\"schafkopf-tracker-backup\",\"defaultRules\":null}",
            "{\"format\":\"schafkopf-tracker-backup\",\"defaultRules\":{\"unit\":\" 5\",\"klopfenEnabled\":\"FALSE\",\"stacking\":1}}",
            "{\"format\":\"schafkopf-tracker-backup\",\"exportedAt\":5,\"appVersion\":7}",
            "{\"format\":\"schafkopf-tracker-backup\",\"exportedAt\":\"2026-11-15T11:00:00+01:00\",\"appVersion\":\"x\"}",
            "{\"format\":\"schafkopf-tracker-backup\",\"exportedAt\":\"+1000000000-12-31T23:59:59.999999999Z\"}",
            "{\"format\":\"schafkopf-tracker-backup\",\"exportedAt\":null,\"appVersion\":null}",
            "{\"format\":schafkopf-tracker-backup}",
            "{\"format\":\"schafkopf-tracker-backup\",\"format\":\"anders\"}",
            "{\"format\":\"anders\",\"format\":\"schafkopf-tracker-backup\"}",
            "{\"format\":\"schafkopf-tracker-backup\",\"zz\":" + "[".repeat(398) + "]".repeat(398) + "}",
            "{\"format\":\"schafkopf-tracker-backup\",\"zz\":" + "[".repeat(399) + "]".repeat(399) + "}",
            "{\"format\":\"schafkopf-tracker-backup\",\"zz\":" + "{\"a\":".repeat(399) + "1" + "}".repeat(399) + "}",
            "{\"format\":\"schafkopf-tracker-backup\",\"zz\":" + "{\"a\":".repeat(398) + "1" + "}".repeat(398) + "}",
            "[".repeat(20_000), "{\"format\":" + "[".repeat(20_000),
            "", "   ", "{", "{\"format\":", written.take(written.length / 2), written.dropLast(1), "$written}", "$written x",
            BOM + written, BOM + BOM + written, " $BOM$written", "$BOM   $written",
            "{\"format\":\"schafkopf-tracker-backup\",}", "{\"format\":\"schafkopf-tracker-backup\" \"schemaVersion\":1}",
            "{\"format\":\"schafkopf-tracker-backup\",\"zz\":hallo}", "{\"format\":\"schafkopf-tracker-backup\",\"zz\":'x'}",
            "{\"format\":\"schafkopf-tracker-backup\",\"zz\":[1 2]}", "{\"format\":\"schafkopf-tracker-backup\",\"zz\":\"\\x\"}",
            "{\"format\":\"schafkopf-tracker-backup\",\"zz\":\"a\u0001b\"}", "{'format':'schafkopf-tracker-backup'}",
            "{\"format\":\"schafkopf-tracker-backup\"/**/}", "{\"form\\u0061t\":\"schafkopf-tracker-backup\"}",
        )
        wrong.forEachIndexed { index, text -> cases += BackupCase("roh-$index", text, emptyList(), emptyList()) }

        // Doppelte und vorhandene Spieler
        val duplicates = backupText(
            players = listOf(
                buildJsonObject {
                    put("id", "spieler-anna")
                    put("name", "Anna")
                },
                buildJsonObject {
                    put("id", "spieler-bertl")
                    put("name", "Bertl")
                    put("colorIndex", 1)
                },
                buildJsonObject {
                    put("id", "spieler-anna")
                    put("name", "Doppelt")
                },
            ),
            evenings = emptyList(),
        )
        cases += BackupCase("doppelte-spieler", duplicates, listOf(TestData.bertl), emptyList())
        cases += BackupCase("doppelte-spieler-geraet-anders", duplicates, listOf(TestData.bertl.copy(colorIndex = 3), Player(PlayerId("x"), "anna", 0)), emptyList())
        return cases
    }

    // ---------- Bausteine ----------

    fun specialRules(): List<Pair<String, RuleSet>> = listOf(
        "standard" to RuleSet.standard(),
        "sauspiel" to RuleSet.sauspiel(),
        "voll" to TestData.fullRules,
        "leere-tabellen" to RuleSet.standard().copy(prices = emptyMap(), laufendeMin = emptyMap(), laufendeMax = emptyMap(), enabledTypes = emptySet()),
        "ueber-kreuz-allein" to RuleSet.sauspiel().copy(prices = mapOf(GameType.UEBER_KREUZ to 15), enabledTypes = setOf(GameType.UEBER_KREUZ)),
        "verdreht" to RuleSet.standard().copy(
            prices = linkedMapOf(GameType.GEIER to 1, GameType.UEBER_KREUZ to 2, GameType.RUFSPIEL to 3),
            enabledTypes = linkedSetOf(GameType.GEIER, GameType.RUFSPIEL, GameType.UEBER_KREUZ),
            laufendeMax = linkedMapOf(GameType.WENZ to 4, GameType.FARBSOLO to 9),
        ),
        "extrem" to RuleSet.standard().copy(
            name = "",
            unit = Int.MIN_VALUE,
            prices = mapOf(GameType.RUFSPIEL to Int.MAX_VALUE, GameType.WENZ to -1),
            toutFactor = 0,
            sieFactor = -5,
            sieLaufende = Int.MAX_VALUE,
            bock = BockRules(false, Int.MIN_VALUE, Int.MAX_VALUE, BockStacking.IGNORE, true),
            payoutRule = PayoutRule.POT_SPLIT,
        ),
        "topf" to RuleSet.standard().copy(payoutRule = PayoutRule.POT_SPLIT, bock = BockRules(stacking = BockStacking.STACK)),
    )

    /** Das Beispiel aus StorageCodecTest („Schema Version 1 eines Abends steht genau so in der Datei“). */
    fun schemaExample(): Evening {
        val a = PlayerId("A")
        val b = PlayerId("B")
        val c = PlayerId("C")
        val d = PlayerId("D")
        return Evening(
            id = EveningId("abend-1"),
            startedAt = Instant.parse("2026-11-14T19:00:00Z"),
            finishedAt = Instant.parse("2026-11-14T23:30:00Z"),
            location = "Wirtshaus zur Post",
            seating = listOf(a, b, c, d),
            firstDealer = b,
            rules = RuleSet.sauspiel(),
            entries = listOf(
                GameEntry(
                    id = EntryId("e1"),
                    recordedAt = Instant.parse("2026-11-14T19:10:00Z"),
                    type = GameType.RUFSPIEL,
                    suit = Suit.EICHEL,
                    declarer = a,
                    partner = b,
                    declarerWon = true,
                    level = ResultLevel.SCHNEIDER,
                    laufende = 3,
                    klopfer = setOf(c),
                    kontra = KontraLevel.KONTRA,
                    kontraBy = d,
                    declarerEyes = 95,
                    note = "Eichel-Sau",
                ),
                ThrownInEntry(EntryId("e2"), Instant.parse("2026-11-14T19:15:00Z")),
                BockTriggerEntry(EntryId("e3"), Instant.parse("2026-11-14T19:20:00Z"), BockReason.SECHS_NIXER, player = c, handThrownIn = true),
                ManualEntry(EntryId("e4"), Instant.parse("2026-11-14T19:25:00Z"), mapOf(a to 100, d to -100), "Wette"),
            ),
        )
    }

    fun <T> pick(random: Random, list: List<T>): T = list[random.nextInt(list.size)]

    fun randomInt(random: Random): Int = when (random.nextInt(10)) {
        0 -> Int.MIN_VALUE
        1 -> Int.MAX_VALUE
        2 -> random.nextInt()
        3 -> -random.nextInt(100)
        else -> random.nextInt(130)
    }

    fun randomInstant(random: Random): Instant = when (random.nextInt(8)) {
        0 -> Instant.ofEpochSecond(1_794_682_800L + random.nextInt(86_400 * 30))
        1 -> Instant.ofEpochMilli(1_794_682_800_000L + random.nextInt(1_000_000_000))
        2 -> Instant.ofEpochSecond(1_794_682_800L + random.nextInt(86_400), random.nextInt(1_000_000).toLong() * 1_000)
        3 -> Instant.ofEpochSecond(1_794_682_800L + random.nextInt(86_400), random.nextInt(1_000_000_000).toLong())
        4 -> Instant.ofEpochMilli(random.nextLong())
        5 -> Instant.ofEpochSecond(-62_167_219_200L + Math.floorMod(random.nextLong(), 315_569_520_000L))
        6 -> pick(random, SPECIAL_INSTANTS)
        else -> Instant.ofEpochSecond(random.nextInt(2_000_000_000).toLong())
    }

    fun randomRules(random: Random): RuleSet {
        fun table(): Map<GameType, Int> = GameType.entries.shuffled(random).filter { random.nextInt(3) != 0 }.associateWith { randomInt(random) }
        return RuleSet(
            name = pick(random, SPECIAL_TEXTS),
            unit = randomInt(random),
            prices = table(),
            enabledTypes = GameType.entries.shuffled(random).filter { random.nextBoolean() }.toSet(),
            laufendeMin = table(),
            laufendeMax = table(),
            klopfenEnabled = random.nextBoolean(),
            kontraEnabled = random.nextBoolean(),
            retourEnabled = random.nextBoolean(),
            toutFactor = randomInt(random),
            sieFactor = randomInt(random),
            sieLaufende = randomInt(random),
            bock = BockRules(random.nextBoolean(), randomInt(random), randomInt(random), pick(random, BockStacking.entries), random.nextBoolean()),
            dealerAdvancesAfterThrowIn = random.nextBoolean(),
            displayAsMoney = random.nextBoolean(),
            payoutRule = pick(random, PayoutRule.entries),
        )
    }

    fun randomEvening(random: Random, id: String): Evening {
        val pool = if (random.nextInt(4) == 0) SPECIAL_IDS else listOf("A", "B", "C", "D", "spieler-anna", "spieler-bertl", "1", "2")
        val seating = when (random.nextInt(10)) {
            0 -> emptyList()
            1 -> List(3) { PlayerId(pick(random, pool)) }
            2 -> List(5) { PlayerId(pick(random, pool)) }
            else -> pool.shuffled(random).take(4).map { PlayerId(it) }
        }

        fun player(): PlayerId = if (seating.isNotEmpty() && random.nextInt(6) != 0) seating[random.nextInt(seating.size)] else PlayerId(pick(random, pool))
        val entries = List(random.nextInt(8)) { index ->
            val entryId = EntryId(if (random.nextInt(8) == 0) pick(random, SPECIAL_IDS) else "e$index")
            val at = randomInstant(random)
            when (random.nextInt(4)) {
                0 -> GameEntry(
                    id = entryId,
                    recordedAt = at,
                    type = pick(random, GameType.entries),
                    suit = if (random.nextBoolean()) pick(random, Suit.entries) else null,
                    declarer = player(),
                    partner = if (random.nextBoolean()) player() else null,
                    declarerWon = random.nextBoolean(),
                    level = pick(random, ResultLevel.entries),
                    laufende = randomInt(random),
                    announcement = pick(random, Announcement.entries),
                    klopfer = List(random.nextInt(4)) { player() }.toSet(),
                    kontra = pick(random, KontraLevel.entries),
                    kontraBy = if (random.nextBoolean()) player() else null,
                    retourBy = if (random.nextBoolean()) player() else null,
                    bockOverride = pick(random, BockOverride.entries),
                    declarerEyes = if (random.nextBoolean()) randomInt(random) else null,
                    note = pick(random, SPECIAL_TEXTS),
                )

                1 -> ThrownInEntry(entryId, at)
                2 -> BockTriggerEntry(entryId, at, pick(random, BockReason.entries), if (random.nextBoolean()) player() else null, random.nextBoolean())
                else -> ManualEntry(entryId, at, List(random.nextInt(5)) { player() to randomInt(random) }.toMap(), pick(random, SPECIAL_TEXTS))
            }
        }
        return Evening(
            id = EveningId(id),
            startedAt = randomInstant(random),
            finishedAt = if (random.nextBoolean()) randomInstant(random) else null,
            location = pick(random, SPECIAL_TEXTS),
            seating = seating,
            firstDealer = if (seating.isNotEmpty() && random.nextInt(5) != 0) seating[random.nextInt(seating.size)] else PlayerId(pick(random, pool)),
            rules = if (random.nextInt(3) == 0) randomRules(random) else pick(random, specialRules()).second,
            entries = entries,
        )
    }

    val SPECIAL_TEXTS = listOf(
        "", "Wirtshaus zur Post", "Zum Grünen Baum", "ÄÖÜ äöü ß ẞ", "Emoji 😀 🂡 👨‍👩‍👧", "Zeile 1\nZeile 2", "Windows\r\nZeile",
        "Tab\tTab", "Anführungszeichen \"so\" und 'so'", "Backslash \\ und /Slash/",
        "Steuerzeichen \u0000 \u0001 \u0008 \u000b \u000c \u001f \u007f", "Zeilentrenner \u2028 \u2029", "BOM \uFEFF mitten",
        "einzelnes \uD800 Ersatzzeichen", "\uDC00 am Anfang", "</script><b>x</b>", "\${x} {{y}}", "null", "true", "123", "   ",
        "  Leerraum außen  ", "Ω≈ç√∫ \u00A0 geschütztes Leerzeichen", "مرحبا שלום", "e\u0301 kombiniert", "x".repeat(300),
    )

    val SPECIAL_IDS = listOf(
        "A", "B", "C", "D", "1", "2", "10", "007", "-1", "1e3", "0x1F", "", " ", "a b", "ä", "😀", "id\"x", "back\\slash",
        "tab\tid", "null", "__proto__", "constructor", "toString", "hasOwnProperty", "spieler-anna",
    )

    val SPECIAL_INSTANTS = listOf(
        Instant.parse("2026-11-14T19:00:00Z"),
        Instant.parse("2026-11-14T19:00:00.123Z"),
        Instant.parse("2026-11-14T19:00:00.120Z"),
        Instant.parse("2026-11-14T19:00:00.000001Z"),
        Instant.parse("2026-11-14T19:00:00.000000001Z"),
        Instant.parse("2026-11-14T19:00:00.100000Z"),
        Instant.parse("2026-11-14T19:00:00.123456789Z"),
        Instant.EPOCH,
        Instant.ofEpochSecond(-1),
        Instant.ofEpochSecond(-1, 999_999_999),
        Instant.parse("0000-01-01T00:00:00Z"),
        Instant.parse("-0001-12-31T23:59:59Z"),
        Instant.parse("9999-12-31T23:59:59.999999999Z"),
        Instant.parse("+10000-01-01T00:00:00Z"),
        Instant.parse("-10000-01-01T00:00:00Z"),
        Instant.ofEpochMilli(Long.MIN_VALUE),
        Instant.ofEpochMilli(Long.MAX_VALUE),
        Instant.MIN,
        Instant.MAX,
        Instant.parse("1969-12-31T23:59:59.5Z"),
        Instant.parse("2000-02-29T12:00:00Z"),
    )
}

/** Spieler im Speicher, bei denen das Speichern des Spielers Nr. [failAt] (ab 0 gezählt) scheitert. */
internal class FailingPlayerRepository(initial: List<Player>, private val failAt: Int) : PlayerRepository {
    private val inner = InMemoryPlayerRepository(initial)
    private var saves = 0
    override val players: Flow<List<Player>> = inner.players

    override suspend fun save(player: Player) {
        if (saves++ == failAt) throw IOException("nachgestellter Fehler")
        inner.save(player)
    }

    override suspend fun delete(id: PlayerId) = inner.delete(id)
}

/** Abende im Speicher, bei denen das Speichern des Abends Nr. [failAt] (ab 0 gezählt) scheitert. */
internal class FailingEveningRepository(initial: List<Evening>, private val failAt: Int) : EveningRepository {
    private val inner = InMemoryEveningRepository(initial)
    private var saves = 0
    override val evenings: Flow<List<Evening>> = inner.evenings

    override fun evening(id: EveningId): Flow<Evening?> = inner.evening(id)

    override suspend fun save(evening: Evening) {
        if (saves++ == failAt) throw IOException("nachgestellter Fehler")
        inner.save(evening)
    }

    override suspend fun delete(id: EveningId) = inner.delete(id)
}
