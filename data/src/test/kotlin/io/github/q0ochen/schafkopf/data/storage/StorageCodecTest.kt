package io.github.q0ochen.schafkopf.data.storage

import io.github.q0ochen.schafkopf.domain.evening.BockOverride
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.AiProvider
import io.github.q0ochen.schafkopf.domain.model.AiSettings
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.BockRules
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.PayoutRule
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.time.Instant

/** Das Dateiformat (Schema Version 1): Hin und zurück, genaue Schreibweise in der Datei, Verhalten bei Fehlern. */
class StorageCodecTest {

    private fun tree(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.texts(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.numbers(key: String): Map<String, Int> =
        getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.int }

    /** Die Vorlage Sauspiel, so wie sie in Schema Version 1 in einer Datei steht. */
    private val sauspielRulesJson = """
        {
          "name": "Sauspiel 10-20-50 mit Bockrunde",
          "unit": 10,
          "prices": {"rufspiel": 20, "farbsolo": 50, "wenz": 50, "geier": 50},
          "enabledTypes": ["rufspiel", "farbsolo", "wenz", "geier"],
          "laufendeMin": {"rufspiel": 3, "farbsolo": 3, "wenz": 2, "geier": 2},
          "laufendeMax": {"rufspiel": 14, "farbsolo": 14, "wenz": 4, "geier": 4},
          "klopfenEnabled": true,
          "kontraEnabled": true,
          "retourEnabled": true,
          "toutFactor": 2,
          "sieFactor": 2,
          "sieLaufende": 8,
          "bock": {
            "enabled": true,
            "gamesPerTrigger": 4,
            "factor": 2,
            "stacking": "append",
            "thrownInConsumesBockGame": false
          },
          "dealerAdvancesAfterThrowIn": true,
          "displayAsMoney": true,
          "payoutRule": "schafkopf"
        }
    """.trimIndent()

    // ---------- Hin und zurück ----------

    @Test
    fun `ein Abend mit jeder Eintragsart und vollem Regelwerk kommt unveraendert zurueck`() {
        val evening = TestData.fullEvening()
        assertEquals(evening, StorageCodec.decodeEvening(StorageCodec.encodeEvening(evening)))
    }

    @Test
    fun `jede Eintragsart kommt auch einzeln unveraendert zurueck`() {
        val evening = TestData.fullEvening()
        assertEquals(
            setOf(GameEntry::class, ThrownInEntry::class, BockTriggerEntry::class, ManualEntry::class),
            evening.entries.map { it::class }.toSet(),
        )
        for (entry in evening.entries) {
            val single = evening.copy(entries = listOf(entry))
            assertEquals(single, StorageCodec.decodeEvening(StorageCodec.encodeEvening(single)), "Eintrag ${entry.id.value}")
        }
    }

    @Test
    fun `ein laufender Abend ohne freiwillige Angaben kommt unveraendert zurueck`() {
        val evening = TestData.simpleEvening("abend-einfach")
        assertEquals(evening, StorageCodec.decodeEvening(StorageCodec.encodeEvening(evening)))
    }

    @Test
    fun `ein volles Regelwerk kommt unveraendert zurueck`() {
        assertEquals(TestData.fullRules, TestData.fullRules.toDto().toDomain())
        assertEquals(RuleSet.sauspiel(), RuleSet.sauspiel().toDto().toDomain())
    }

    @Test
    fun `die Spielerliste kommt unveraendert und in derselben Reihenfolge zurueck`() {
        val players = listOf(TestData.done, TestData.anna.copy(archived = true), TestData.centa, TestData.bertl)
        assertEquals(players, StorageCodec.decodePlayers(StorageCodec.encodePlayers(players)))
        assertEquals(emptyList<Player>(), StorageCodec.decodePlayers(StorageCodec.encodePlayers(emptyList())))
    }

    @Test
    fun `die Einstellungen kommen unveraendert zurueck`() {
        assertEquals(
            TestData.customSettings,
            StorageCodec.decodeSettings(StorageCodec.encodeSettings(TestData.customSettings)),
        )
        assertEquals(AppSettings(), StorageCodec.decodeSettings(StorageCodec.encodeSettings(AppSettings())))
    }

    @Test
    fun `das Dateiformat kennt jedes Feld der Fachklassen`() {
        // Kommt in der Fachlogik ein Feld dazu, muss es auch ins Dateiformat (Dtos.kt und Mappers.kt). Sonst ginge
        // es beim Speichern stillschweigend verloren. Schlägt dieser Test fehl, fehlt genau das.
        fun fields(type: Class<*>): Set<String> = type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(setOf("id", "name", "colorIndex", "archived"), fields(Player::class.java))
        assertEquals(
            setOf("id", "startedAt", "finishedAt", "location", "seating", "firstDealer", "rules", "entries"),
            fields(Evening::class.java),
        )
        assertEquals(
            setOf(
                "id", "recordedAt", "type", "suit", "declarer", "partner", "declarerWon", "level", "laufende",
                "announcement", "klopfer", "kontra", "kontraBy", "retourBy", "bockOverride", "declarerEyes", "note",
            ),
            fields(GameEntry::class.java),
        )
        assertEquals(setOf("id", "recordedAt"), fields(ThrownInEntry::class.java))
        assertEquals(setOf("id", "recordedAt", "reason", "player", "handThrownIn"), fields(BockTriggerEntry::class.java))
        assertEquals(setOf("id", "recordedAt", "amounts", "note"), fields(ManualEntry::class.java))
        assertEquals(
            setOf(
                "name", "unit", "prices", "enabledTypes", "laufendeMin", "laufendeMax", "klopfenEnabled",
                "kontraEnabled", "retourEnabled", "toutFactor", "sieFactor", "sieLaufende", "bock",
                "dealerAdvancesAfterThrowIn", "displayAsMoney", "payoutRule",
            ),
            fields(RuleSet::class.java),
        )
        assertEquals(
            setOf("enabled", "gamesPerTrigger", "factor", "stacking", "thrownInConsumesBockGame"),
            fields(BockRules::class.java),
        )
        assertEquals(
            setOf("defaultRules", "keepScreenOn", "themeMode", "haptics", "ai"),
            fields(AppSettings::class.java),
        )
        assertEquals(
            setOf("enabled", "provider", "model", "baseUrl", "sendRealNames", "consentHost"),
            fields(AiSettings::class.java),
        )
    }

    // ---------- Schreibweise in der Datei ----------

    @Test
    fun `Schema Version 1 eines Abends steht genau so in der Datei`() {
        val evening = Evening(
            id = EveningId("abend-1"),
            startedAt = Instant.parse("2026-11-14T19:00:00Z"),
            finishedAt = Instant.parse("2026-11-14T23:30:00Z"),
            location = "Wirtshaus zur Post",
            seating = listOf(PlayerId("A"), PlayerId("B"), PlayerId("C"), PlayerId("D")),
            firstDealer = PlayerId("B"),
            rules = RuleSet.sauspiel(),
            entries = listOf(
                GameEntry(
                    id = EntryId("e1"),
                    recordedAt = Instant.parse("2026-11-14T19:10:00Z"),
                    type = GameType.RUFSPIEL,
                    suit = Suit.EICHEL,
                    declarer = PlayerId("A"),
                    partner = PlayerId("B"),
                    declarerWon = true,
                    level = ResultLevel.SCHNEIDER,
                    laufende = 3,
                    klopfer = setOf(PlayerId("C")),
                    kontra = KontraLevel.KONTRA,
                    kontraBy = PlayerId("D"),
                    declarerEyes = 95,
                    note = "Eichel-Sau",
                ),
                ThrownInEntry(EntryId("e2"), Instant.parse("2026-11-14T19:15:00Z")),
                BockTriggerEntry(
                    id = EntryId("e3"),
                    recordedAt = Instant.parse("2026-11-14T19:20:00Z"),
                    reason = BockReason.SECHS_NIXER,
                    player = PlayerId("C"),
                    handThrownIn = true,
                ),
                ManualEntry(
                    id = EntryId("e4"),
                    recordedAt = Instant.parse("2026-11-14T19:25:00Z"),
                    amounts = mapOf(PlayerId("A") to 100, PlayerId("D") to -100),
                    note = "Wette",
                ),
            ),
        )
        val file = """
            {
              "schemaVersion": 1,
              "id": "abend-1",
              "startedAt": "2026-11-14T19:00:00Z",
              "finishedAt": "2026-11-14T23:30:00Z",
              "location": "Wirtshaus zur Post",
              "seating": ["A", "B", "C", "D"],
              "firstDealer": "B",
              "rules": $sauspielRulesJson,
              "entries": [
                {
                  "type": "game",
                  "id": "e1",
                  "recordedAt": "2026-11-14T19:10:00Z",
                  "gameType": "rufspiel",
                  "suit": "eichel",
                  "declarer": "A",
                  "partner": "B",
                  "declarerWon": true,
                  "level": "schneider",
                  "laufende": 3,
                  "announcement": "none",
                  "klopfer": ["C"],
                  "kontra": "kontra",
                  "kontraBy": "D",
                  "bockOverride": "auto",
                  "declarerEyes": 95,
                  "note": "Eichel-Sau"
                },
                {"type": "thrown_in", "id": "e2", "recordedAt": "2026-11-14T19:15:00Z"},
                {
                  "type": "bock",
                  "id": "e3",
                  "recordedAt": "2026-11-14T19:20:00Z",
                  "reason": "sechs_nixer",
                  "player": "C",
                  "handThrownIn": true
                },
                {
                  "type": "manual",
                  "id": "e4",
                  "recordedAt": "2026-11-14T19:25:00Z",
                  "amounts": {"A": 100, "D": -100},
                  "note": "Wette"
                }
              ]
            }
        """.trimIndent()

        assertEquals(evening, StorageCodec.decodeEvening(file))
        assertEquals(Json.parseToJsonElement(file), Json.parseToJsonElement(StorageCodec.encodeEvening(evening)))
    }

    @Test
    fun `Schema Version 1 der Spielerliste und der Einstellungen steht genau so in der Datei`() {
        val playersFile = """
            {
              "schemaVersion": 1,
              "players": [
                {"id": "spieler-anna", "name": "Anna", "colorIndex": 0, "archived": false},
                {"id": "spieler-centa", "name": "Centa", "colorIndex": 2, "archived": true}
              ]
            }
        """.trimIndent()
        val players = listOf(TestData.anna, TestData.centa.copy(archived = true))
        assertEquals(players, StorageCodec.decodePlayers(playersFile))
        assertEquals(Json.parseToJsonElement(playersFile), Json.parseToJsonElement(StorageCodec.encodePlayers(players)))

        val settingsFile = """
            {
              "schemaVersion": 1,
              "defaultRules": $sauspielRulesJson,
              "keepScreenOn": true,
              "themeMode": "system",
              "haptics": true,
              "ai": {
                "enabled": false,
                "provider": "gemini",
                "model": "",
                "baseUrl": "",
                "sendRealNames": false,
                "consentHost": ""
              }
            }
        """.trimIndent()
        // Die Datei nennt jeden Wert ausdrücklich: die Vorlage Sauspiel und „wie System“.
        val settings = AppSettings(defaultRules = RuleSet.sauspiel(), themeMode = ThemeMode.SYSTEM)
        assertEquals(settings, StorageCodec.decodeSettings(settingsFile))
        assertEquals(
            Json.parseToJsonElement(settingsFile),
            Json.parseToJsonElement(StorageCodec.encodeSettings(settings)),
        )
    }

    @Test
    fun `die Art eines Eintrags steht im Feld type`() {
        val entries = tree(StorageCodec.encodeEvening(TestData.fullEvening())).getValue("entries").jsonArray
        assertEquals(
            listOf("game", "game", "thrown_in", "bock", "bock", "manual", "game"),
            entries.map { it.jsonObject.text("type") },
        )
    }

    @Test
    fun `Aufzaehlungen stehen als feste kleingeschriebene Texte in der Datei`() {
        val root = tree(StorageCodec.encodeEvening(TestData.fullEvening()))
        val entries = root.getValue("entries").jsonArray.map { it.jsonObject }
        val solo = entries[0]
        assertEquals("farbsolo", solo.text("gameType"))
        assertEquals("herz", solo.text("suit"))
        assertEquals("schwarz", solo.text("level"))
        assertEquals("tout", solo.text("announcement"))
        assertEquals("retour", solo.text("kontra"))
        assertEquals("force_on", solo.text("bockOverride"))
        assertEquals("sechs_nixer", entries[3].text("reason"))
        assertEquals("manual", entries[4].text("reason"))
        assertEquals("stack", root.getValue("rules").jsonObject.getValue("bock").jsonObject.text("stacking"))

        val settings = tree(StorageCodec.encodeSettings(TestData.customSettings))
        assertEquals("dark", settings.text("themeMode"))
        assertEquals("openai_compatible", settings.getValue("ai").jsonObject.text("provider"))
    }

    @Test
    fun `jeder Wert jeder Aufzaehlung hat seinen festen Text in der Datei und kommt wieder zurueck`() {
        // Die Texte stehen hier ausgeschrieben und werden bewusst nicht aus den Namen im Quelltext abgeleitet:
        // Wird in der Fachlogik ein Wert umbenannt, muss sein Text in der Datei derselbe bleiben, sonst wären
        // alte Dateien nicht mehr lesbar.
        checkEnum(
            GameType.entries, GameTypeDto.serializer(), { it.toDto() }, { it.toDomain() },
            mapOf(
                GameType.RUFSPIEL to "rufspiel",
                GameType.UEBER_KREUZ to "ueber_kreuz",
                GameType.FARBSOLO to "farbsolo",
                GameType.WENZ to "wenz",
                GameType.GEIER to "geier",
            ),
        )
        checkEnum(
            PayoutRule.entries, PayoutRuleDto.serializer(), { it.toDto() }, { it.toDomain() },
            mapOf(PayoutRule.SCHAFKOPF to "schafkopf", PayoutRule.POT_SPLIT to "pot_split"),
        )
        checkEnum(
            Suit.entries, SuitDto.serializer(), { it.toDto() }, { it.toDomain() },
            mapOf(Suit.EICHEL to "eichel", Suit.GRAS to "gras", Suit.HERZ to "herz", Suit.SCHELLEN to "schellen"),
        )
        checkEnum(
            ResultLevel.entries, ResultLevelDto.serializer(), { it.toDto() }, { it.toDomain() },
            mapOf(
                ResultLevel.NORMAL to "normal",
                ResultLevel.SCHNEIDER to "schneider",
                ResultLevel.SCHWARZ to "schwarz",
            ),
        )
        checkEnum(
            Announcement.entries, AnnouncementDto.serializer(), { it.toDto() }, { it.toDomain() },
            mapOf(Announcement.NONE to "none", Announcement.TOUT to "tout", Announcement.SIE to "sie"),
        )
        checkEnum(
            KontraLevel.entries, KontraLevelDto.serializer(), { it.toDto() }, { it.toDomain() },
            mapOf(KontraLevel.NONE to "none", KontraLevel.KONTRA to "kontra", KontraLevel.RETOUR to "retour"),
        )
        checkEnum(
            BockStacking.entries, BockStackingDto.serializer(), { it.toDto() }, { it.toDomain() },
            mapOf(BockStacking.APPEND to "append", BockStacking.STACK to "stack", BockStacking.IGNORE to "ignore"),
        )
        checkEnum(
            BockOverride.entries, BockOverrideDto.serializer(), { it.toDto() }, { it.toDomain() },
            mapOf(
                BockOverride.AUTO to "auto",
                BockOverride.FORCE_ON to "force_on",
                BockOverride.FORCE_OFF to "force_off",
            ),
        )
        checkEnum(
            BockReason.entries, BockReasonDto.serializer(), { it.toDto() }, { it.toDomain() },
            mapOf(BockReason.SECHS_NIXER to "sechs_nixer", BockReason.MANUAL to "manual"),
        )
        checkEnum(
            ThemeMode.entries, ThemeModeDto.serializer(), { it.toDto() }, { it.toDomain() },
            mapOf(ThemeMode.SYSTEM to "system", ThemeMode.LIGHT to "light", ThemeMode.DARK to "dark"),
        )
        checkEnum(
            AiProvider.entries, AiProviderDto.serializer(), { it.toDto() }, { it.toDomain() },
            mapOf(AiProvider.GEMINI to "gemini", AiProvider.OPENAI_COMPATIBLE to "openai_compatible"),
        )
    }

    /**
     * Prüft für eine Aufzählung, dass jeder Wert genau als der angegebene Text in der Datei steht und daraus
     * wieder derselbe Wert wird.
     *
     * @param inFile Der Text in der Datei zu jedem Wert, zum Beispiel FORCE_ON als "force_on".
     */
    private fun <E : Enum<E>, D> checkEnum(
        values: List<E>,
        serializer: KSerializer<D>,
        toDto: (E) -> D,
        toDomain: (D) -> E,
        inFile: Map<E, String>,
    ) {
        assertEquals(values.toSet(), inFile.keys, "für jeden Wert der Aufzählung ist ein Text festgelegt")
        for ((value, text) in inFile) {
            assertEquals("\"$text\"", StorageCodec.json.encodeToString(serializer, toDto(value)))
            assertEquals(value, toDomain(StorageCodec.json.decodeFromString(serializer, "\"$text\"")))
        }
    }

    @Test
    fun `Tabellen mit Spielarten stehen als JSON-Objekte mit den Namen der Spielarten in der Datei`() {
        val rules = tree(StorageCodec.encodeEvening(TestData.fullEvening())).getValue("rules").jsonObject
        assertEquals(mapOf("rufspiel" to 10, "farbsolo" to 25, "wenz" to 30, "geier" to 35), rules.numbers("prices"))
        assertEquals(mapOf("rufspiel" to 2, "farbsolo" to 4, "wenz" to 3, "geier" to 1), rules.numbers("laufendeMin"))
        assertEquals(mapOf("rufspiel" to 8, "farbsolo" to 9, "wenz" to 3, "geier" to 2), rules.numbers("laufendeMax"))
        assertEquals(listOf("rufspiel", "wenz"), rules.texts("enabledTypes"))
    }

    @Test
    fun `die Spielarten stehen unabhaengig von der Eingabe immer in derselben Reihenfolge in der Datei`() {
        val shuffled = RuleSet.sauspiel().copy(
            prices = mapOf(GameType.GEIER to 50, GameType.RUFSPIEL to 20, GameType.WENZ to 50, GameType.FARBSOLO to 50),
            enabledTypes = setOf(GameType.GEIER, GameType.WENZ, GameType.FARBSOLO, GameType.RUFSPIEL),
        )
        assertEquals(
            StorageCodec.json.encodeToString(RuleSetDto.serializer(), RuleSet.sauspiel().toDto()),
            StorageCodec.json.encodeToString(RuleSetDto.serializer(), shuffled.toDto()),
        )
    }

    @Test
    fun `Zeitpunkte stehen als ISO-8601-Text und Kennungen als Text in der Datei`() {
        val root = tree(StorageCodec.encodeEvening(TestData.fullEvening()))
        val entries = root.getValue("entries").jsonArray.map { it.jsonObject }

        assertEquals("2026-11-14T19:00:00Z", root.text("startedAt"))
        assertEquals("2026-11-14T23:00:00Z", root.text("finishedAt"))
        assertEquals("2026-11-14T19:05:30.123456789Z", entries[0].text("recordedAt"))

        assertEquals("abend-voll", root.text("id"))
        assertTrue(root.getValue("id").jsonPrimitive.isString)
        assertEquals("spieler-bertl", root.text("firstDealer"))
        assertEquals(
            listOf("spieler-anna", "spieler-bertl", "spieler-centa", "spieler-done"),
            root.texts("seating"),
        )
        assertEquals("spieler-centa", entries[0].text("declarer"))
        assertEquals(listOf("spieler-done", "spieler-anna"), entries[0].texts("klopfer"))
        assertEquals(
            mapOf("spieler-anna" to 150, "spieler-bertl" to -50, "spieler-centa" to -100),
            entries[5].numbers("amounts"),
        )
    }

    @Test
    fun `Standardwerte werden ausgeschrieben und leere Angaben weggelassen`() {
        val text = StorageCodec.encodeEvening(TestData.simpleEvening("abend-einfach"))
        val root = tree(text)
        val game = root.getValue("entries").jsonArray[0].jsonObject

        assertEquals(1, root.number("schemaVersion"))
        assertEquals("", root.text("location"))
        assertEquals("normal", game.text("level"))
        assertEquals(0, game.number("laufende"))
        assertEquals("none", game.text("announcement"))
        assertEquals("auto", game.text("bockOverride"))
        assertEquals(emptyList<String>(), game.texts("klopfer"))

        assertFalse("finishedAt" in root)
        assertFalse("suit" in game)
        assertFalse("kontraBy" in game)
        assertFalse("retourBy" in game)
        assertFalse("declarerEyes" in game)
        assertFalse(text.contains("null"), "in der Datei steht nirgends ein leerer Wert")
    }

    // ---------- Lesen älterer, neuerer und kaputter Dateien ----------

    @Test
    fun `unbekannte Felder werden ueberlesen`() {
        val file = """
            {
              "schemaVersion": 1,
              "neuesFeld": {"tief": [1, 2, {"x": null}]},
              "id": "abend-neu",
              "startedAt": "2026-11-14T19:00:00Z",
              "seating": ["A", "B", "C", "D"],
              "firstDealer": "B",
              "wetter": "Schnee",
              "rules": {"unit": 10, "zusatzregel": true, "bock": {"enabled": true, "nochwas": 3}},
              "entries": [
                {"type": "game", "id": "e1", "recordedAt": "2026-11-14T19:10:00Z", "gameType": "wenz",
                 "declarer": "A", "declarerWon": true, "stimmung": "gut"},
                {"id": "e2", "grund": "alle weiter", "type": "thrown_in", "recordedAt": "2026-11-14T19:12:00Z"}
              ]
            }
        """.trimIndent()
        val expected = Evening(
            id = EveningId("abend-neu"),
            startedAt = Instant.parse("2026-11-14T19:00:00Z"),
            seating = listOf(PlayerId("A"), PlayerId("B"), PlayerId("C"), PlayerId("D")),
            firstDealer = PlayerId("B"),
            rules = RuleSet.sauspiel(),
            entries = listOf(
                GameEntry(
                    id = EntryId("e1"),
                    recordedAt = Instant.parse("2026-11-14T19:10:00Z"),
                    type = GameType.WENZ,
                    declarer = PlayerId("A"),
                    declarerWon = true,
                ),
                ThrownInEntry(EntryId("e2"), Instant.parse("2026-11-14T19:12:00Z")),
            ),
        )
        assertEquals(expected, StorageCodec.decodeEvening(file))

        assertEquals(
            listOf(TestData.anna),
            StorageCodec.decodePlayers(
                """{"schemaVersion":1,"sortierung":"name","players":[{"id":"spieler-anna","name":"Anna","spitzname":"Anni"}]}""",
            ),
        )
        assertEquals(
            AppSettings(haptics = false),
            StorageCodec.decodeSettings("""{"haptics":false,"sprache":"bairisch","ai":{"temperatur":0.7}}"""),
        )
    }

    @Test
    fun `fehlende optionale Felder bekommen Standardwerte`() {
        val file = """
            {
              "id": "abend-alt",
              "startedAt": "2026-01-02T18:00:00Z",
              "seating": ["A", "B", "C", "D"],
              "firstDealer": "A",
              "entries": [
                {"type": "game", "id": "e1", "recordedAt": "2026-01-02T18:10:00Z", "gameType": "wenz",
                 "declarer": "A", "declarerWon": true},
                {"type": "thrown_in", "id": "e2", "recordedAt": "2026-01-02T18:12:00Z"},
                {"type": "bock", "id": "e3", "recordedAt": "2026-01-02T18:13:00Z", "reason": "sechs_nixer"},
                {"type": "manual", "id": "e4", "recordedAt": "2026-01-02T18:14:00Z", "amounts": {"A": 50, "B": -50}}
              ]
            }
        """.trimIndent()
        val expected = Evening(
            id = EveningId("abend-alt"),
            startedAt = Instant.parse("2026-01-02T18:00:00Z"),
            finishedAt = null,
            location = "",
            seating = listOf(PlayerId("A"), PlayerId("B"), PlayerId("C"), PlayerId("D")),
            firstDealer = PlayerId("A"),
            rules = RuleSet.sauspiel(),
            entries = listOf(
                GameEntry(
                    id = EntryId("e1"),
                    recordedAt = Instant.parse("2026-01-02T18:10:00Z"),
                    type = GameType.WENZ,
                    declarer = PlayerId("A"),
                    declarerWon = true,
                ),
                ThrownInEntry(EntryId("e2"), Instant.parse("2026-01-02T18:12:00Z")),
                BockTriggerEntry(EntryId("e3"), Instant.parse("2026-01-02T18:13:00Z"), BockReason.SECHS_NIXER),
                ManualEntry(
                    id = EntryId("e4"),
                    recordedAt = Instant.parse("2026-01-02T18:14:00Z"),
                    amounts = mapOf(PlayerId("A") to 50, PlayerId("B") to -50),
                    note = "",
                ),
            ),
        )
        assertEquals(expected, StorageCodec.decodeEvening(file))

        // Ein Abend ganz ohne Einträge und ein Regelwerk, in dem nur ein Wert genannt ist.
        val bare = StorageCodec.decodeEvening(
            """{"id":"x","startedAt":"2026-01-02T18:00:00Z","seating":["A","B","C","D"],"firstDealer":"A","rules":{"unit":5}}""",
        )
        assertEquals(emptyList<Any>(), bare.entries)
        assertEquals(RuleSet.sauspiel().copy(unit = 5), bare.rules)

        assertEquals(
            listOf(Player(PlayerId("p1"), "Xaver", colorIndex = 0, archived = false)),
            StorageCodec.decodePlayers("""{"players":[{"id":"p1","name":"Xaver"}]}"""),
        )
        assertEquals(emptyList<Player>(), StorageCodec.decodePlayers("{}"))
        assertEquals(AppSettings(), StorageCodec.decodeSettings("{}"))
    }

    @Test
    fun `die festen Standardwerte des Dateiformats entsprechen der Vorlage Sauspiel und den Standardeinstellungen`() {
        assertEquals(RuleSet.sauspiel(), RuleSetDto().toDomain())
        assertEquals(AppSettings(), SettingsDto().toDomain())
    }

    @Test
    fun `ein unbekannter Wert einer Aufzaehlung ist ein sauberer Lesefehler`() {
        val evening = StorageCodec.encodeEvening(TestData.fullEvening())
        val brokenEvenings = listOf(
            evening.replace("\"gameType\":\"farbsolo\"", "\"gameType\":\"ramsch\""),
            evening.replace("\"type\":\"thrown_in\"", "\"type\":\"pause\""),
            evening.replace("\"suit\":\"herz\"", "\"suit\":\"laub\""),
            evening.replace("\"level\":\"schwarz\"", "\"level\":\"durch\""),
            evening.replace("\"announcement\":\"tout\"", "\"announcement\":\"bettel\""),
            evening.replace("\"kontra\":\"retour\"", "\"kontra\":\"sub\""),
            evening.replace("\"bockOverride\":\"force_on\"", "\"bockOverride\":\"vielleicht\""),
            evening.replace("\"reason\":\"sechs_nixer\"", "\"reason\":\"laune\""),
            evening.replace("\"stacking\":\"stack\"", "\"stacking\":\"turm\""),
            evening.replace("\"prices\":{\"rufspiel\"", "\"prices\":{\"ramsch\""),
            evening.replace("\"enabledTypes\":[\"rufspiel\"", "\"enabledTypes\":[\"ramsch\""),
        )
        for (broken in brokenEvenings) {
            assertNotEquals(evening, broken)
            assertThrows(UnreadableDataException::class.java) { StorageCodec.decodeEvening(broken) }
        }

        val settings = StorageCodec.encodeSettings(TestData.customSettings)
        val brokenSettings = listOf(
            settings.replace("\"themeMode\":\"dark\"", "\"themeMode\":\"bunt\""),
            settings.replace("\"provider\":\"openai_compatible\"", "\"provider\":\"orakel\""),
        )
        for (broken in brokenSettings) {
            assertNotEquals(settings, broken)
            assertThrows(UnreadableDataException::class.java) { StorageCodec.decodeSettings(broken) }
        }
    }

    @Test
    fun `kaputter Text ist ein sauberer Lesefehler`() {
        val evening = StorageCodec.encodeEvening(TestData.fullEvening())
        val broken = listOf("", "   ", "{", "nix", "[]", "null", "42", evening.dropLast(1), evening.take(evening.length / 2), evening + "x")
        for (text in broken) {
            assertThrows(UnreadableDataException::class.java, { StorageCodec.decodeEvening(text) }, "Abend aus: $text")
            assertThrows(UnreadableDataException::class.java, { StorageCodec.decodePlayers(text) }, "Spieler aus: $text")
            assertThrows(UnreadableDataException::class.java, { StorageCodec.decodeSettings(text) }, "Einstellungen aus: $text")
        }
    }

    @Test
    fun `extrem tief verschachtelter Text ist ein sauberer Lesefehler und kein Absturz`() {
        // 200 000 ineinandergeschachtelte Listen würden beim Lesen den Aufrufstapel sprengen. Die JSON-Bibliothek
        // bricht bei so tiefer Verschachtelung von sich aus ab; daraus muss ein gewöhnlicher Lesefehler werden.
        val brackets = "[".repeat(200_000)
        // Ein Abend mit einem Eintrag, dessen Art nicht vorn steht: Um sie zu finden, wird der ganze Eintrag gelesen.
        val evening = "{\"id\":\"tief\",\"startedAt\":\"2026-11-14T19:00:00Z\",\"seating\":[\"A\",\"B\",\"C\",\"D\"]," +
            "\"firstDealer\":\"A\",\"entries\":[{\"id\":\"e1\",\"unsinn\":$brackets"

        assertThrows(UnreadableDataException::class.java) { StorageCodec.decodeEvening(evening) }
        assertFalse(StorageCodec.isJsonObject(brackets))
        assertFalse(StorageCodec.isJsonObject("{\"unsinn\":$brackets"))
    }

    @Test
    fun `ein vollstaendiges JSON-Objekt wird von abgeschnittenem Text unterschieden`() {
        val evening = StorageCodec.encodeEvening(TestData.fullEvening())
        assertTrue(StorageCodec.isJsonObject(evening))
        assertTrue(StorageCodec.isJsonObject("{}"))

        for (text in listOf("", "{", evening.dropLast(1), evening.take(evening.length / 2), "[]", "42", "\"Text\"")) {
            assertFalse(StorageCodec.isJsonObject(text), text)
        }
    }

    @Test
    fun `eine fehlende Pflichtangabe oder ein ungueltiger Zeitpunkt ist ein sauberer Lesefehler`() {
        val evening = StorageCodec.encodeEvening(TestData.fullEvening())
        val broken = listOf(
            evening.replace("\"firstDealer\":\"spieler-bertl\",", ""),
            evening.replace("\"declarer\":\"spieler-centa\",", ""),
            evening.replace("\"startedAt\":\"2026-11-14T19:00:00Z\"", "\"startedAt\":\"gestern\""),
            evening.replace("\"finishedAt\":\"2026-11-14T23:00:00Z\"", "\"finishedAt\":\"14.11.2026 23:00\""),
            evening.replace("\"recordedAt\":\"2026-11-14T19:05:30.123456789Z\"", "\"recordedAt\":\"\""),
            evening.replace("\"seating\":[", "\"seating\":7,\"sitze\":["),
        )
        for (text in broken) {
            assertNotEquals(evening, text)
            assertThrows(UnreadableDataException::class.java) { StorageCodec.decodeEvening(text) }
        }
        assertThrows(UnreadableDataException::class.java) {
            StorageCodec.decodePlayers("""{"players":[{"name":"ohne Kennung"}]}""")
        }
    }

    @Test
    fun `ein Zeitpunkt, der sich nicht mehr in Millisekunden ausdruecken laesst, ist ein sauberer Lesefehler`() {
        val evening = StorageCodec.encodeEvening(TestData.fullEvening())
        // In Millisekunden seit 1970 lassen sich rund 292 Millionen Jahre in beide Richtungen ausdrücken:
        // von -292275055-05-16 bis +292278994-08-17. Die ersten beiden Werte liegen eine Milliarde Jahre entfernt
        // (dazu gibt es nicht einmal mehr ein Datum), die anderen beiden knapp außerhalb des Bereichs.
        val impossible = listOf(
            "+1000000000-12-31T23:59:59.999999999Z",
            "-1000000000-01-01T00:00:00Z",
            "+292278995-01-01T00:00:00Z",
            "-292275056-01-01T00:00:00Z",
        )
        // Beginn und Ende des Abends und je ein Eintrag jeder Art: Spiel, zusammengeworfen, Bockrunde, freie Buchung.
        val places = listOf(
            "\"startedAt\":\"2026-11-14T19:00:00Z\"",
            "\"finishedAt\":\"2026-11-14T23:00:00Z\"",
            "\"recordedAt\":\"2026-11-14T19:05:30.123456789Z\"",
            "\"recordedAt\":\"2026-11-14T19:15:00Z\"",
            "\"recordedAt\":\"2026-11-14T19:20:00Z\"",
            "\"recordedAt\":\"2026-11-14T19:30:00Z\"",
        )
        for (place in places) {
            for (time in impossible) {
                val broken = evening.replace(place, place.substringBefore(':') + ":\"$time\"")
                assertNotEquals(evening, broken, place)
                assertThrows(UnreadableDataException::class.java, { StorageCodec.decodeEvening(broken) }, "$place wird zu $time")
            }
        }

        // Die Grenzen selbst gehören noch dazu und kommen unverändert zurück.
        val edges = TestData.simpleEvening("rand").copy(
            startedAt = Instant.ofEpochMilli(Long.MIN_VALUE),
            finishedAt = Instant.ofEpochMilli(Long.MAX_VALUE),
        )
        assertEquals(edges, StorageCodec.decodeEvening(StorageCodec.encodeEvening(edges)))
    }

    @Test
    fun `eine hoehere Schema-Version ist ein eigener sauberer Lesefehler`() {
        val evening = StorageCodec.encodeEvening(TestData.fullEvening())
        val newer = evening.replace("{\"schemaVersion\":1,", "{\"schemaVersion\":2,")
        assertNotEquals(evening, newer)

        assertThrows(NewerSchemaException::class.java) { StorageCodec.decodeEvening(newer) }
        assertThrows(NewerSchemaException::class.java) { StorageCodec.decodeEveningElement(Json.parseToJsonElement(newer)) }
        assertThrows(NewerSchemaException::class.java) { StorageCodec.decodePlayers("""{"schemaVersion":2,"players":[]}""") }
        assertThrows(NewerSchemaException::class.java) { StorageCodec.decodeSettings("""{"schemaVersion":99}""") }

        // Eine Version unter 1 gibt es nicht; das ist schlicht eine kaputte Datei.
        val invalid = assertThrows(UnreadableDataException::class.java) {
            StorageCodec.decodePlayers("""{"schemaVersion":0,"players":[]}""")
        }
        assertFalse(invalid is NewerSchemaException)
    }

    @Test
    fun `eine unsichtbare Markierung am Dateianfang stoert nicht`() {
        val mark = Char(0xFEFF).toString()
        val evening = TestData.fullEvening()
        assertEquals(evening, StorageCodec.decodeEvening(mark + StorageCodec.encodeEvening(evening)))
        assertEquals(TestData.players, StorageCodec.decodePlayers(mark + StorageCodec.encodePlayers(TestData.players)))
        assertEquals(AppSettings(), StorageCodec.decodeSettings(mark + StorageCodec.encodeSettings(AppSettings())))
    }

    @Test
    fun `Abend, Spielerliste und Regelwerk lassen sich auch aus einem JSON-Baum lesen`() {
        val evening = TestData.fullEvening()
        assertEquals(
            evening,
            StorageCodec.decodeEveningElement(Json.parseToJsonElement(StorageCodec.encodeEvening(evening))),
        )
        assertEquals(
            listOf(Player(PlayerId("p1"), "Xaver", colorIndex = 4, archived = true)),
            StorageCodec.decodePlayerList(
                Json.parseToJsonElement("""[{"id":"p1","name":"Xaver","colorIndex":4,"archived":true,"alter":52}]"""),
            ),
        )
        assertThrows(UnreadableDataException::class.java) {
            StorageCodec.decodePlayerList(Json.parseToJsonElement("""{"keine":"Liste"}"""))
        }
        assertThrows(UnreadableDataException::class.java) {
            StorageCodec.decodeEveningElement(Json.parseToJsonElement("""["kein", "Abend"]"""))
        }
        assertEquals(
            TestData.fullRules,
            StorageCodec.decodeRulesElement(Json.parseToJsonElement(StorageCodec.encodeRules(TestData.fullRules))),
        )
        assertThrows(UnreadableDataException::class.java) {
            StorageCodec.decodeRulesElement(Json.parseToJsonElement("""["kein", "Regelwerk"]"""))
        }
    }
}
