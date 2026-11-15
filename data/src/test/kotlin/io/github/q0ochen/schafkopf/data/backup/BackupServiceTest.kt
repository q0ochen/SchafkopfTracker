package io.github.q0ochen.schafkopf.data.backup

import io.github.q0ochen.schafkopf.data.storage.CountingDispatcher
import io.github.q0ochen.schafkopf.data.storage.FileStorage
import io.github.q0ochen.schafkopf.data.storage.StorageCodec
import io.github.q0ochen.schafkopf.data.storage.TestData
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.ScoreCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class BackupServiceTest {

    @field:TempDir
    lateinit var dir: Path

    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC)

    private val archived = Player(PlayerId("spieler-emil"), "Emil", colorIndex = 4, archived = true)
    private val stranger = PlayerId("fremd")

    private fun storage(name: String): FileStorage = FileStorage(dir.resolve(name), Dispatchers.Unconfined)

    private fun service(storage: FileStorage): BackupService = BackupService(storage, clock, "0.1.0")

    /** Fünf Spieler (einer archiviert), zwei gültige Abende und eigene Einstellungen. */
    private suspend fun filledStorage(name: String): FileStorage {
        val storage = storage(name)
        for (player in TestData.players + archived) storage.players.save(player)
        storage.evenings.save(TestData.fullEvening("abend-voll", TestData.start))
        storage.evenings.save(TestData.simpleEvening("abend-einfach", TestData.start.minusSeconds(7 * 86_400)))
        storage.settings.update { TestData.customSettings }
        return storage
    }

    /** Inhalt aller Dateien eines Speichers, um zu prüfen, dass sich auf dem Datenträger nichts geändert hat. */
    private fun fileContents(name: String): Map<String, String> {
        val root = dir.resolve(name)
        val contents = sortedMapOf<String, String>()

        fun visit(current: Path) {
            Files.newDirectoryStream(current).use { entries ->
                for (entry in entries) {
                    if (Files.isDirectory(entry)) visit(entry) else contents[root.relativize(entry).toString()] = Files.readString(entry)
                }
            }
        }

        if (Files.isDirectory(root)) visit(root)
        return contents
    }

    private fun eveningJson(evening: Evening): JsonObject =
        Json.parseToJsonElement(StorageCodec.encodeEvening(evening)).jsonObject

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

    /** Baut eine Sicherungsdatei von Hand, damit auch Fälle prüfbar sind, die die App selbst nie schreiben würde. */
    private fun backup(
        evenings: List<JsonElement>,
        players: List<Player> = TestData.players,
        format: String = "schafkopf-tracker-backup",
        schemaVersion: Int = 1,
    ): String = buildJsonObject {
        put("format", format)
        put("schemaVersion", schemaVersion)
        put("appVersion", "0.1.0")
        put("exportedAt", "2026-11-15T10:00:00Z")
        putJsonArray("players") {
            for (player in players) {
                addJsonObject {
                    put("id", player.id.value)
                    put("name", player.name)
                    put("colorIndex", player.colorIndex)
                    put("archived", player.archived)
                }
            }
        }
        putJsonArray("evenings") {
            for (evening in evenings) add(evening)
        }
    }.toString()

    // ---------- Export ----------

    @Test
    fun `der Export hat den vereinbarten Kopf und enthaelt Spieler, Abende und die Vorlage des Regelwerks`() = runTest {
        val json = service(filledStorage("quelle")).exportJson()
        val root = Json.parseToJsonElement(json).jsonObject

        assertEquals(
            setOf("format", "schemaVersion", "appVersion", "exportedAt", "players", "evenings", "defaultRules"),
            root.keys,
        )
        assertEquals("schafkopf-tracker-backup", root.getValue("format").jsonPrimitive.content)
        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("0.1.0", root.getValue("appVersion").jsonPrimitive.content)
        assertEquals("2026-11-15T10:00:00Z", root.getValue("exportedAt").jsonPrimitive.content)
        assertEquals(
            listOf("Anna", "Bertl", "Centa", "Done", "Emil"),
            root.getValue("players").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        // Die Abende stehen vom ältesten zum neuesten in der Datei.
        assertEquals(
            listOf("abend-einfach", "abend-voll"),
            root.getValue("evenings").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content },
        )
        assertEquals(
            "Hausregeln 5-10-25",
            root.getValue("defaultRules").jsonObject.getValue("name").jsonPrimitive.content,
        )
    }

    @Test
    fun `der Export enthaelt weder einen Schluessel noch die Einstellungen der KI`() = runTest {
        val json = service(filledStorage("quelle")).exportJson()

        for (forbidden in listOf(
            "apiKey", "\"ai\"", "provider", "consentHost", "sendRealNames", "baseUrl",
            "testmodell-7", "ki.example.invalid", "openai_compatible", "themeMode", "keepScreenOn", "haptics",
        )) {
            assertFalse(json.contains(forbidden, ignoreCase = true), "im Export steht: $forbidden")
        }
    }

    @Test
    fun `ein leerer Speicher ergibt eine gueltige leere Sicherung`() = runTest {
        val json = service(storage("leer")).exportJson()

        val preview = service(storage("ziel")).preview(json)

        assertNull(preview.error)
        assertTrue(preview.isReadable)
        assertFalse(preview.hasNewData)
        assertEquals(Instant.parse("2026-11-15T10:00:00Z"), preview.exportedAt)
        assertEquals("0.1.0", preview.appVersion)
    }

    @Test
    fun `das Zusammenbauen und Zerlegen des Textes laeuft auf dem Dispatcher des Speichers`() = runTest {
        val dispatcher = CountingDispatcher()
        val subject = service(FileStorage(dir.resolve("ziel"), dispatcher))

        // Erster Zugriff: einmal laden, einmal den Text zusammenbauen.
        val json = subject.exportJson()
        assertEquals(2, dispatcher.dispatched)

        // Die Vorschau lädt nicht noch einmal und zerlegt den Text einmal.
        subject.preview(json)
        assertEquals(3, dispatcher.dispatched)
    }

    @Test
    fun `kommt waehrend des Exports ein Abend mit einem neuen Spieler dazu, fehlt in der Sicherung kein Spieler`() = runTest {
        val emil = Player(PlayerId("spieler-emil"), "Emil", colorIndex = 4)
        val lateEvening = TestData.simpleEvening("abend-neu", TestData.start.plusSeconds(3600))
            .copy(seating = listOf(TestData.a, TestData.b, TestData.c, emil.id))
        val store = GrowingStore(
            initialPlayers = TestData.players,
            initialEvenings = listOf(TestData.simpleEvening("abend-alt", TestData.start)),
            latePlayer = emil,
            lateEvening = lateEvening,
        )
        val subject = BackupService(
            players = store.players,
            evenings = store.evenings,
            settings = InMemorySettingsRepository(),
            clock = clock,
            appVersion = "0.1.0",
            dispatcher = Dispatchers.Unconfined,
        )

        val root = Json.parseToJsonElement(subject.exportJson()).jsonObject

        assertTrue(store.grown, "während des Exports kam etwas dazu")
        val exportedPlayers = root.getValue("players").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
            .toSet()
        val seatedPlayers = root.getValue("evenings").jsonArray
            .flatMap { evening -> evening.jsonObject.getValue("seating").jsonArray.map { it.jsonPrimitive.content } }
            .toSet()
        // Jeder, der in einem gesicherten Abend am Tisch sitzt, steht auch in der Spielerliste der Sicherung.
        assertEquals(emptySet<String>(), seatedPlayers - exportedPlayers)
    }

    // ---------- Import ----------

    @Test
    fun `Export und Import in einen leeren Speicher ergeben dieselben Daten`() = runTest {
        val source = filledStorage("quelle")
        val json = service(source).exportJson()
        val target = storage("ziel")

        val result = service(target).import(json)

        assertNull(result.error)
        assertTrue(result.isSuccess)
        assertEquals(5, result.newPlayerCount)
        assertEquals(0, result.sameNamePlayerCount)
        assertEquals(0, result.existingPlayerCount)
        assertEquals(2, result.newEveningCount)
        assertEquals(0, result.existingEveningCount)
        assertEquals(0, result.invalidEveningCount)
        assertEquals(source.players.players.first(), target.players.players.first())
        assertEquals(source.evenings.evenings.first(), target.evenings.evenings.first())

        // Auch nach einem Neustart der App.
        val restarted = storage("ziel")
        assertEquals(TestData.players + archived, restarted.players.players.first())
        assertEquals(
            listOf(
                TestData.fullEvening("abend-voll", TestData.start),
                TestData.simpleEvening("abend-einfach", TestData.start.minusSeconds(7 * 86_400)),
            ),
            restarted.evenings.evenings.first(),
        )
    }

    @Test
    fun `die Einstellungen werden beim Import nicht veraendert`() = runTest {
        val json = service(filledStorage("quelle")).exportJson()
        val target = storage("ziel")
        val own = AppSettings(defaultRules = RuleSet.sauspiel().copy(name = "Meine Regeln", unit = 20), haptics = false)
        target.settings.update { own }

        service(target).import(json)

        assertEquals(own, target.settings.settings.first())
        assertEquals(own, storage("ziel").settings.settings.first())
    }

    @Test
    fun `die Vorschau zeigt, was neu waere, und speichert nichts`() = runTest {
        val json = service(filledStorage("quelle")).exportJson()
        val target = storage("ziel")

        val preview = service(target).preview(json)

        assertNull(preview.error)
        assertTrue(preview.isReadable)
        assertTrue(preview.hasNewData)
        assertEquals(TestData.players + archived, preview.newPlayers)
        assertEquals(5, preview.newPlayerCount)
        assertEquals(0, preview.sameNamePlayerCount)
        assertEquals(0, preview.existingPlayerCount)
        assertEquals(0, preview.differingPlayerCount)
        // Die Vorlage des Regelwerks aus der Sicherung wird genannt, aber nicht übernommen.
        assertEquals(TestData.fullRules, preview.defaultRules)
        assertEquals(AppSettings(), target.settings.settings.first())
        assertEquals(listOf("abend-einfach", "abend-voll"), preview.newEvenings.map { it.id.value })
        assertEquals(2, preview.newEveningCount)
        assertEquals(0, preview.existingEveningCount)
        assertEquals(0, preview.invalidEveningCount)
        assertEquals(Instant.parse("2026-11-15T10:00:00Z"), preview.exportedAt)
        assertEquals("0.1.0", preview.appVersion)

        assertEquals(emptyList<Player>(), target.players.players.first())
        assertEquals(emptyList<Evening>(), target.evenings.evenings.first())
        assertEquals(emptyMap<String, String>(), fileContents("ziel"))
    }

    @Test
    fun `ein zweiter Import derselben Sicherung aendert nichts`() = runTest {
        val json = service(filledStorage("quelle")).exportJson()
        val target = storage("ziel")
        val subject = service(target)
        subject.import(json)
        val players = target.players.players.first()
        val evenings = target.evenings.evenings.first()
        val files = fileContents("ziel")

        val preview = subject.preview(json)
        val second = subject.import(json)

        assertFalse(preview.hasNewData)
        assertEquals(5, preview.existingPlayerCount)
        assertEquals(0, preview.differingPlayerCount)
        assertEquals(2, preview.existingEveningCount)
        assertNull(second.error)
        assertEquals(0, second.newPlayerCount)
        assertEquals(5, second.existingPlayerCount)
        assertEquals(0, second.newEveningCount)
        assertEquals(2, second.existingEveningCount)
        assertEquals(0, second.invalidEveningCount)
        assertEquals(evenings.map { it.id }.toSet(), second.existingEvenings.map { it.id }.toSet())
        assertEquals(players, target.players.players.first())
        assertEquals(evenings, target.evenings.evenings.first())
        assertEquals(files, fileContents("ziel"))
    }

    @Test
    fun `zwei gleichzeitige Importe derselben Sicherung melden nichts doppelt`() = runTest {
        val json = service(filledStorage("quelle")).exportJson()
        val target = storage("ziel")
        val subject = service(target)

        // Zwei Aufrufer echt nebeneinander auf verschiedenen Threads, wie bei doppeltem Tippen.
        val results = withContext(Dispatchers.Default) {
            listOf(async { subject.import(json) }, async { subject.import(json) }).awaitAll()
        }

        assertTrue(results.all { it.isSuccess })
        // Einer der beiden hat alles angelegt (5 Spieler, 2 Abende), der andere fand alles schon vor.
        assertEquals(listOf(0, 5), results.map { it.newPlayerCount }.sorted())
        assertEquals(listOf(0, 5), results.map { it.existingPlayerCount }.sorted())
        assertEquals(listOf(0, 2), results.map { it.newEveningCount }.sorted())
        assertEquals(listOf(0, 2), results.map { it.existingEveningCount }.sorted())
        assertEquals(TestData.players + archived, target.players.players.first())
        assertEquals(2, target.evenings.evenings.first().size)
    }

    @Test
    fun `vorhandene Spieler und Abende bleiben unveraendert und zaehlen als bereits vorhanden`() = runTest {
        val json = service(filledStorage("quelle")).exportJson()
        val target = storage("ziel")
        // Dieselben Kennungen wie in der Sicherung, aber mit anderem Inhalt.
        val ownAnna = TestData.anna.copy(name = "Annamirl", colorIndex = 7)
        val ownEvening = TestData.simpleEvening("abend-voll", TestData.start.plusSeconds(60)).copy(location = "Bei uns daheim")
        target.players.save(ownAnna)
        target.evenings.save(ownEvening)

        val preview = service(target).preview(json)
        val result = service(target).import(json)

        assertEquals(listOf(TestData.bertl, TestData.centa, TestData.done, archived), preview.newPlayers)
        assertEquals(1, preview.existingPlayerCount)
        // Die Vorschau nennt, wie die vorhandene Spielerin in der Sicherung heißt; übernommen wird das nicht.
        assertEquals(listOf(TestData.anna), preview.differingPlayers)
        assertEquals(1, preview.differingPlayerCount)
        assertEquals(listOf("abend-einfach"), preview.newEvenings.map { it.id.value })
        assertEquals(listOf(ownEvening), preview.existingEvenings)
        assertEquals(preview.newPlayers, result.newPlayers)
        assertEquals(1, result.existingPlayerCount)
        assertEquals(preview.newEvenings, result.newEvenings)
        assertEquals(listOf(ownEvening), result.existingEvenings)

        assertEquals(
            listOf(ownAnna, TestData.bertl, TestData.centa, TestData.done, archived),
            target.players.players.first(),
        )
        assertEquals(ownEvening, target.evenings.evening(EveningId("abend-voll")).first())
        assertEquals(2, target.evenings.evenings.first().size)
    }

    @Test
    fun `ein namensgleicher Spieler mit anderer Kennung wird trotzdem angelegt und gemeldet`() = runTest {
        val json = service(filledStorage("quelle")).exportJson()
        val target = storage("ziel")
        val localAnna = Player(PlayerId("hier-angelegt"), " anna ", colorIndex = 5)
        target.players.save(localAnna)

        val preview = service(target).preview(json)
        val result = service(target).import(json)

        assertEquals(listOf(TestData.anna), preview.sameNamePlayers)
        assertEquals(1, preview.sameNamePlayerCount)
        assertEquals(5, preview.newPlayerCount)
        assertEquals(0, preview.existingPlayerCount)
        assertEquals(listOf(TestData.anna), result.sameNamePlayers)
        assertEquals(1, result.sameNamePlayerCount)
        assertEquals(5, result.newPlayerCount)
        assertEquals(listOf(localAnna) + TestData.players + archived, target.players.players.first())
    }

    @Test
    fun `auf einem neuen Geraet nennt die Vorschau, wie die vorbelegten Spieler in der Sicherung heissen`() = runTest {
        val json = service(filledStorage("quelle")).exportJson()
        // Ein frisch eingerichtetes Gerät: dieselben vier Kennungen wie in der Sicherung, aber noch mit Platzhalternamen.
        val target = storage("ziel")
        val placeholders = TestData.players.mapIndexed { index, player -> player.copy(name = "Spieler ${index + 1}") }
        for (player in placeholders) target.players.save(player)
        val subject = service(target)

        val preview = subject.preview(json)
        val result = subject.import(json)

        // Die vier gibt es schon, neu ist nur der fünfte. In der Sicherung heißen die vier aber anders.
        assertEquals(listOf(archived), preview.newPlayers)
        assertEquals(4, preview.existingPlayerCount)
        assertEquals(TestData.players, preview.differingPlayers)
        assertEquals(4, preview.differingPlayerCount)
        // Der Import selbst ändert Vorhandenes nicht.
        assertNull(result.error)
        assertEquals(placeholders + archived, target.players.players.first())

        // Wer die Namen aus der Sicherung haben will, speichert die genannten Spieler; ihr Platz in der Liste bleibt.
        for (player in preview.differingPlayers) target.players.save(player)
        assertEquals(TestData.players + archived, target.players.players.first())
        assertEquals(0, subject.preview(json).differingPlayerCount)
    }

    // ---------- Ungültige Abende ----------

    @Test
    fun `jede Art ungueltiger Abende wird mit ihrem Grund erkannt`() {
        val good = TestData.simpleEvening("gut")
        val game = good.entries.first() as GameEntry

        fun withEntry(entry: EveningEntry): Evening = good.copy(entries = listOf(game, entry))

        val cases = listOf(
            good.copy(seating = TestData.seating.take(3)) to EveningProblem.WRONG_SEATING,
            good.copy(seating = TestData.seating + stranger) to EveningProblem.WRONG_SEATING,
            good.copy(seating = emptyList()) to EveningProblem.WRONG_SEATING,
            good.copy(seating = listOf(TestData.a, TestData.b, TestData.c, TestData.c)) to EveningProblem.WRONG_SEATING,
            good.copy(firstDealer = stranger) to EveningProblem.DEALER_NOT_AT_TABLE,
            withEntry(game.copy(declarer = stranger)) to EveningProblem.UNKNOWN_PLAYER,
            withEntry(game.copy(partner = stranger)) to EveningProblem.UNKNOWN_PLAYER,
            withEntry(game.copy(klopfer = setOf(TestData.a, stranger))) to EveningProblem.UNKNOWN_PLAYER,
            withEntry(game.copy(kontraBy = stranger)) to EveningProblem.UNKNOWN_PLAYER,
            withEntry(game.copy(retourBy = stranger)) to EveningProblem.UNKNOWN_PLAYER,
            withEntry(
                BockTriggerEntry(EntryId("bock"), TestData.start, BockReason.SECHS_NIXER, player = stranger),
            ) to EveningProblem.UNKNOWN_PLAYER,
            // Summe 50 − 50 = 0, aber einer der beiden sitzt nicht am Tisch.
            withEntry(
                ManualEntry(EntryId("frei"), TestData.start, mapOf(TestData.a to 50, stranger to -50), "Wette"),
            ) to EveningProblem.UNKNOWN_PLAYER,
            // Summe 50 − 40 = 10, also nicht 0.
            withEntry(
                ManualEntry(EntryId("frei"), TestData.start, mapOf(TestData.a to 50, TestData.b to -40), "Wette"),
            ) to EveningProblem.MANUAL_NOT_ZERO_SUM,
            // Die Summe wäre nur durch einen Überlauf 0: 2 147 483 647 + 2 147 483 647 + 2 passt nicht in 32 Bit.
            withEntry(
                ManualEntry(
                    EntryId("frei"),
                    TestData.start,
                    mapOf(TestData.a to Int.MAX_VALUE, TestData.b to Int.MAX_VALUE, TestData.c to 2),
                    "Unsinn",
                ),
            ) to EveningProblem.MANUAL_NOT_ZERO_SUM,
            // Ein Grundwert, mit dem sich nicht rechnen lässt: Das Spiel würde nichts buchen.
            good.copy(rules = good.rules.copy(prices = good.rules.prices + (GameType.RUFSPIEL to Int.MAX_VALUE))) to
                EveningProblem.AMOUNT_OUT_OF_RANGE,
            good.copy(rules = good.rules.copy(prices = good.rules.prices + (GameType.RUFSPIEL to -20))) to
                EveningProblem.AMOUNT_OUT_OF_RANGE,
            // Jede der beiden Buchungen geht für sich auf, zusammen passt der Saldo aber nicht mehr in 32 Bit:
            // 2 000 000 000 + 2 000 000 000 ist mehr als 2 147 483 647.
            good.copy(
                entries = listOf(
                    game,
                    ManualEntry(EntryId("frei-1"), TestData.start, mapOf(TestData.a to 2_000_000_000, TestData.b to -2_000_000_000), "Unsinn"),
                    ManualEntry(EntryId("frei-2"), TestData.start, mapOf(TestData.a to 2_000_000_000, TestData.b to -2_000_000_000), "Unsinn"),
                ),
            ) to EveningProblem.AMOUNT_OUT_OF_RANGE,
        )
        for ((evening, problem) in cases) {
            val finding = EveningCheck.firstProblem(evening)
            assertEquals(problem, finding?.problem, "bei $evening")
            assertTrue(finding != null && finding.reason.isNotBlank())
        }

        assertEquals(
            "Am Tisch müssen genau vier Spieler sitzen, hier sind es 3.",
            EveningCheck.firstProblem(good.copy(seating = TestData.seating.take(3)))?.reason,
        )
        assertEquals(
            "Im Eintrag Nr. 2 kommt ein Spieler vor, der nicht am Tisch sitzt.",
            EveningCheck.firstProblem(withEntry(game.copy(declarer = stranger)))?.reason,
        )
        assertEquals(
            "Der Eintrag Nr. 1 lässt sich nicht abrechnen: Ein Betrag liegt außerhalb des Bereichs, mit dem die App rechnen kann.",
            EveningCheck.firstProblem(
                good.copy(rules = good.rules.copy(prices = good.rules.prices + (GameType.RUFSPIEL to -20))),
            )?.reason,
        )
        // Der größte Spielwert, mit dem die App noch rechnet, ist kein Befund.
        assertNull(
            EveningCheck.firstProblem(
                good.copy(rules = good.rules.copy(prices = good.rules.prices + (GameType.RUFSPIEL to ScoreCalculator.MAX_VALUE))),
            ),
        )

        // Stimmige Abende haben keinen Befund, auch der mit jeder Eintragsart nicht.
        assertNull(EveningCheck.firstProblem(good))
        assertNull(EveningCheck.firstProblem(TestData.fullEvening()))
        assertNull(
            EveningCheck.firstProblem(
                withEntry(ManualEntry(EntryId("frei"), TestData.start, mapOf(TestData.a to 50, TestData.b to -50), "Wette")),
            ),
        )
    }

    @Test
    fun `ungueltige Abende stehen mit Grund in der Vorschau und werden nicht importiert`() = runTest {
        val good = TestData.simpleEvening("gut", TestData.start)
        val game = good.entries.first() as GameEntry
        val threeSeats = TestData.simpleEvening("drei-am-tisch", TestData.start.plusSeconds(1))
            .copy(seating = TestData.seating.take(3))
        val strangeDealer = TestData.simpleEvening("fremder-geber", TestData.start.plusSeconds(2))
            .copy(firstDealer = stranger)
        val strangePlayer = TestData.simpleEvening("fremder-spieler", TestData.start.plusSeconds(3))
            .copy(entries = listOf(game.copy(partner = stranger)))
        val unbalanced = TestData.simpleEvening("schiefe-buchung", TestData.start.plusSeconds(4)).copy(
            entries = listOf(ManualEntry(EntryId("frei"), TestData.start, mapOf(TestData.a to 50, TestData.b to -40), "Wette")),
        )
        val source = storage("quelle")
        for (player in TestData.players) source.players.save(player)
        for (evening in listOf(good, threeSeats, strangeDealer, strangePlayer, unbalanced)) source.evenings.save(evening)
        val json = service(source).exportJson()
        val target = storage("ziel")

        val preview = service(target).preview(json)
        val result = service(target).import(json)

        val expected = listOf(
            "drei-am-tisch" to EveningProblem.WRONG_SEATING,
            "fremder-geber" to EveningProblem.DEALER_NOT_AT_TABLE,
            "fremder-spieler" to EveningProblem.UNKNOWN_PLAYER,
            "schiefe-buchung" to EveningProblem.MANUAL_NOT_ZERO_SUM,
        )
        assertEquals(listOf(good), preview.newEvenings)
        assertEquals(expected, preview.invalidEvenings.map { it.id to it.problem })
        assertEquals(4, preview.invalidEveningCount)
        assertEquals(TestData.start.plusSeconds(1), preview.invalidEvenings.first().startedAt)
        assertEquals(
            "Am Tisch müssen genau vier Spieler sitzen, hier sind es 3.",
            preview.invalidEvenings.first().reason,
        )
        assertEquals(
            "Die freie Buchung im Eintrag Nr. 1 geht nicht auf: Die Beträge ergeben zusammen nicht null.",
            preview.invalidEvenings.last().reason,
        )
        assertTrue(preview.invalidEvenings.all { it.reason.isNotBlank() })

        assertNull(result.error)
        assertEquals(listOf(good), result.newEvenings)
        assertEquals(preview.invalidEvenings, result.invalidEvenings)
        assertEquals(4, result.invalidEveningCount)
        assertEquals(listOf(good), target.evenings.evenings.first())
        assertEquals(TestData.players, target.players.players.first())
    }

    @Test
    fun `unlesbare, zu neue und doppelte Abende werden einzeln ausgelassen`() = runTest {
        val good = eveningJson(TestData.simpleEvening("gut", TestData.start))
        val entries = good.getValue("entries").jsonArray
        val unknownGame = good
            .with("id", JsonPrimitive("unbekannte-spielart"))
            .with("entries", JsonArray(listOf(entries[0].jsonObject.with("gameType", JsonPrimitive("ramsch")))))
        val noDealer = JsonObject(good.with("id", JsonPrimitive("ohne-geber")) - "firstDealer")
        val badTime = good.with("id", JsonPrimitive("falsche-zeit")).with("startedAt", JsonPrimitive("gestern Abend"))
        val notAnObject = JsonPrimitive("gar kein Abend")
        val noId = good.with("id", JsonPrimitive(""))
        val newer = good.with("id", JsonPrimitive("aus-der-zukunft")).with("schemaVersion", JsonPrimitive(2))
        val json = backup(listOf(unknownGame, noDealer, good, badTime, notAnObject, noId, newer, good))
        val target = storage("ziel")

        val preview = service(target).preview(json)
        val result = service(target).import(json)

        assertNull(preview.error)
        assertEquals(listOf("gut"), preview.newEvenings.map { it.id.value })
        assertEquals(
            listOf(
                "unbekannte-spielart" to EveningProblem.UNREADABLE,
                "ohne-geber" to EveningProblem.UNREADABLE,
                "falsche-zeit" to EveningProblem.UNREADABLE,
                "" to EveningProblem.UNREADABLE,
                "" to EveningProblem.UNREADABLE,
                "aus-der-zukunft" to EveningProblem.NEWER_VERSION,
                "gut" to EveningProblem.DUPLICATE_ID,
            ),
            preview.invalidEvenings.map { it.id to it.problem },
        )
        // Der Beginn steht dabei, soweit er sich lesen ließ.
        assertEquals(
            listOf(TestData.start, TestData.start, null, null, TestData.start, TestData.start, TestData.start),
            preview.invalidEvenings.map { it.startedAt },
        )
        assertTrue(preview.invalidEvenings.all { it.reason.isNotBlank() })

        assertNull(result.error)
        assertEquals(preview.invalidEvenings, result.invalidEvenings)
        assertEquals(listOf(TestData.simpleEvening("gut", TestData.start)), target.evenings.evenings.first())
    }

    @Test
    fun `eine doppelte Eintragskennung oder ein Platz ohne Kennung machen einen Abend ungueltig`() = runTest {
        val good = TestData.simpleEvening("gut", TestData.start)
        val game = good.entries.first() as GameEntry
        // Der erste und der dritte Eintrag tragen beide die Kennung "spiel-1". Innerhalb eines Abends braucht jeder
        // Eintrag seine eigene, sonst ließe sich später nicht mehr sagen, welcher geändert oder gelöscht werden soll.
        val sameIdTwice = TestData.simpleEvening("doppelter-eintrag", TestData.start.plusSeconds(1)).copy(
            entries = listOf(game, ThrownInEntry(EntryId("e2"), TestData.start), game.copy(declarerWon = false)),
        )
        // Vier verschiedene Plätze, aber einer davon ist leer.
        val seatWithoutId = TestData.simpleEvening("platz-ohne-kennung", TestData.start.plusSeconds(2))
            .copy(seating = listOf(TestData.a, TestData.b, TestData.c, PlayerId("")))
        val json = backup(listOf(sameIdTwice, seatWithoutId, good).map { eveningJson(it) })
        val target = storage("ziel")

        val preview = service(target).preview(json)
        val result = service(target).import(json)

        assertEquals(listOf(good), preview.newEvenings)
        assertEquals(
            listOf(
                "doppelter-eintrag" to EveningProblem.DUPLICATE_ENTRY_ID,
                "platz-ohne-kennung" to EveningProblem.WRONG_SEATING,
            ),
            preview.invalidEvenings.map { it.id to it.problem },
        )
        assertEquals(
            listOf(
                "Der Eintrag Nr. 3 hat dieselbe Kennung wie ein früherer Eintrag des Abends.",
                "Am Tisch sitzt ein Spieler ohne Kennung.",
            ),
            preview.invalidEvenings.map { it.reason },
        )
        assertNull(result.error)
        assertEquals(preview.invalidEvenings, result.invalidEvenings)
        assertEquals(listOf(good), result.newEvenings)
        assertEquals(listOf(good), target.evenings.evenings.first())

        // Auch eine Kennung nur aus Leerzeichen ist keine.
        val blankSeat = good.copy(seating = listOf(TestData.a, TestData.b, TestData.c, PlayerId("  ")))
        assertEquals(EveningProblem.WRONG_SEATING, EveningCheck.firstProblem(blankSeat)?.problem)
    }

    @Test
    fun `ein Rufspiel, in dem der Ansager sein eigener Mitspieler ist, macht einen Abend ungueltig`() = runTest {
        val good = TestData.simpleEvening("gut", TestData.start)
        val game = good.entries.first() as GameEntry
        // Rufspiel ohne Zusätze. Anna ist Ansagerin und zugleich als ihre eigene Mitspielerin eingetragen:
        // So ein Spiel lässt sich nicht abrechnen und würde in den Salden des Abends fehlen.
        val ownPartner = TestData.simpleEvening("eigener-mitspieler", TestData.start.plusSeconds(1))
            .copy(entries = listOf(game.copy(partner = TestData.a)))
        val json = backup(listOf(ownPartner, good).map { eveningJson(it) })
        val target = storage("ziel")

        val preview = service(target).preview(json)
        val result = service(target).import(json)

        assertEquals(listOf(good), preview.newEvenings)
        assertEquals(
            listOf(
                InvalidEvening(
                    id = "eigener-mitspieler",
                    startedAt = TestData.start.plusSeconds(1),
                    problem = EveningProblem.GAME_NOT_ZERO_SUM,
                    reason = "Im Spiel im Eintrag Nr. 1 ist der Ansager als sein eigener Mitspieler eingetragen.",
                ),
            ),
            preview.invalidEvenings,
        )
        assertNull(result.error)
        assertEquals(preview.invalidEvenings, result.invalidEvenings)
        assertEquals(listOf(good), target.evenings.evenings.first())

        // Was aufgeht, wird übernommen, auch wenn die App es als Regelverstoß anzeigt:
        // Ein Wenz von Anna (50 Cent) mit überflüssig eingetragenem Mitspieler: 3 × 50 − 50 − 50 − 50 = 0.
        assertNull(EveningCheck.firstProblem(good.copy(entries = listOf(game.copy(type = GameType.WENZ, partner = TestData.b)))))
        // Ein Rufspiel ohne Mitspieler lässt sich nicht abrechnen; es bucht nichts: 0 + 0 + 0 + 0 = 0.
        assertNull(EveningCheck.firstProblem(good.copy(entries = listOf(game.copy(partner = null)))))
    }

    @Test
    fun `ein Abend mit einem unmoeglichen Zeitpunkt wird ausgelassen`() = runTest {
        val good = eveningJson(TestData.simpleEvening("gut", TestData.start))
        // Eine Milliarde Jahre in der Zukunft: Dazu lässt sich kein Datum mehr anzeigen, und in Millisekunden
        // (so rechnet die Datenbank) lässt sich der Zeitpunkt auch nicht ausdrücken.
        val farFuture = "+1000000000-12-31T23:59:59.999999999Z"
        val impossibleStart = good.with("id", JsonPrimitive("unmoeglicher-beginn")).with("startedAt", JsonPrimitive(farFuture))
        val impossibleEntry = good.with("id", JsonPrimitive("unmoeglicher-eintrag")).with(
            "entries",
            JsonArray(listOf(good.getValue("entries").jsonArray[0].jsonObject.with("recordedAt", JsonPrimitive(farFuture)))),
        )
        val json = backup(listOf(impossibleStart, impossibleEntry, good))
            .replace("\"exportedAt\":\"2026-11-15T10:00:00Z\"", "\"exportedAt\":\"$farFuture\"")
        val target = storage("ziel")

        val preview = service(target).preview(json)
        val result = service(target).import(json)

        assertNull(preview.error)
        // Auch der Zeitpunkt der Sicherung selbst gilt dann als nicht lesbar.
        assertNull(preview.exportedAt)
        assertEquals(listOf("gut"), preview.newEvenings.map { it.id.value })
        assertEquals(
            listOf(
                InvalidEvening(
                    id = "unmoeglicher-beginn",
                    startedAt = null,
                    problem = EveningProblem.UNREADABLE,
                    reason = "Der Abend ist beschädigt und lässt sich nicht lesen.",
                ),
                InvalidEvening(
                    id = "unmoeglicher-eintrag",
                    startedAt = TestData.start,
                    problem = EveningProblem.UNREADABLE,
                    reason = "Der Abend ist beschädigt und lässt sich nicht lesen.",
                ),
            ),
            preview.invalidEvenings,
        )
        assertNull(result.error)
        assertEquals(preview.invalidEvenings, result.invalidEvenings)
        val imported = target.evenings.evenings.first()
        assertEquals(listOf(TestData.simpleEvening("gut", TestData.start)), imported)
        // Mit dem, was übernommen wurde, lässt sich weiterarbeiten: Die Tabelle aller Spiele entsteht ohne Fehler.
        val csv = CsvExport.games(imported, target.players.players.first(), ZoneId.of("Europe/Berlin"))
        assertEquals(2, csv.split("\r\n").size - 1)
    }

    @Test
    fun `ein schon vorhandener Abend zaehlt als vorhanden, auch wenn er in der Sicherung beschaedigt ist`() = runTest {
        val target = storage("ziel")
        val own = TestData.simpleEvening("abend", TestData.start)
        target.evenings.save(own)
        val damaged = JsonObject(eveningJson(own) - "seating")
        val json = backup(listOf(damaged, eveningJson(own)))

        val preview = service(target).preview(json)

        assertEquals(listOf(own), preview.existingEvenings)
        assertEquals(1, preview.existingEveningCount)
        assertEquals(0, preview.invalidEveningCount)
        assertEquals(0, preview.newEveningCount)
    }

    // ---------- Unbrauchbare Dateien ----------

    @Test
    fun `ein falsches Format ergibt ein Fehlerergebnis und keine Ausnahme`() = runTest {
        val target = storage("ziel")
        val subject = service(target)
        val wrong = listOf(
            """{"hallo":"welt"}""",
            """[1, 2, 3]""",
            "\"nur ein Text\"",
            "42",
            backup(emptyList(), format = "anderes-programm"),
            """{"format":"schafkopf-tracker-backup","schemaVersion":"eins"}""",
            """{"format":"schafkopf-tracker-backup","schemaVersion":0}""",
            """{"format":"schafkopf-tracker-backup","schemaVersion":1,"players":"keine Liste"}""",
            """{"format":"schafkopf-tracker-backup","schemaVersion":1,"players":[{"name":"ohne Kennung"}]}""",
            // Eine leere Kennung ist so viel wert wie eine fehlende.
            """{"format":"schafkopf-tracker-backup","schemaVersion":1,"players":[{"id":"","name":"leere Kennung"}]}""",
            """{"format":"schafkopf-tracker-backup","schemaVersion":1,"players":[{"id":"  ","name":"leere Kennung"}]}""",
            """{"format":"schafkopf-tracker-backup","schemaVersion":1,"evenings":{"kein":"Feld"}}""",
            """{"format":17,"schemaVersion":1}""",
        )
        for (text in wrong) {
            val preview = subject.preview(text)
            assertEquals(BackupErrorKind.WRONG_FORMAT, preview.error?.kind, text)
            assertFalse(preview.isReadable)
            assertFalse(preview.hasNewData)
            assertTrue(preview.error?.message.orEmpty().isNotBlank())

            val result = subject.import(text)
            assertEquals(BackupErrorKind.WRONG_FORMAT, result.error?.kind, text)
            assertFalse(result.isSuccess)
            assertEquals(preview.error, result.error)
        }
        assertEquals("Die Datei ist keine Sicherung dieser App.", subject.preview("""{"hallo":"welt"}""").error?.message)
        assertEquals(emptyMap<String, String>(), fileContents("ziel"))
    }

    @Test
    fun `eine hoehere Version ergibt ein eigenes Fehlerergebnis`() = runTest {
        val target = storage("ziel")
        val json = backup(listOf(eveningJson(TestData.simpleEvening("gut"))), schemaVersion = 2)

        val preview = service(target).preview(json)
        val result = service(target).import(json)

        assertEquals(BackupErrorKind.NEWER_VERSION, preview.error?.kind)
        assertEquals(
            "Die Sicherung stammt aus einer neueren Version der App. " +
                "Bitte die App aktualisieren und es dann noch einmal versuchen.",
            preview.error?.message,
        )
        assertEquals(0, preview.newEveningCount)
        assertEquals(preview.error, result.error)
        assertEquals(emptyList<Evening>(), target.evenings.evenings.first())
        assertEquals(emptyList<Player>(), target.players.players.first())
    }

    @Test
    fun `kaputtes JSON ergibt ein eigenes Fehlerergebnis`() = runTest {
        val good = service(filledStorage("quelle")).exportJson()
        val target = storage("ziel")
        val subject = service(target)

        for (text in listOf("", "   ", "{", "{\"format\":", good.take(good.length / 2), good.dropLast(1), "$good}")) {
            val preview = subject.preview(text)
            assertEquals(BackupErrorKind.INVALID_JSON, preview.error?.kind, text.take(40))
            assertTrue(preview.error?.message.orEmpty().isNotBlank())

            val result = subject.import(text)
            assertEquals(BackupErrorKind.INVALID_JSON, result.error?.kind, text.take(40))
        }
        assertEquals(emptyList<Player>(), target.players.players.first())
        assertEquals(emptyList<Evening>(), target.evenings.evenings.first())
    }

    @Test
    fun `eine absichtlich extrem tief verschachtelte Datei ergibt ein Fehlerergebnis und keinen Absturz`() = runTest {
        val subject = service(storage("ziel"))

        val preview = subject.preview("[".repeat(200_000))
        val result = subject.import("{\"format\":" + "[".repeat(200_000))

        assertEquals(BackupErrorKind.INVALID_JSON, preview.error?.kind)
        assertEquals(BackupErrorKind.INVALID_JSON, result.error?.kind)
    }

    @Test
    fun `eine Sicherung ohne Listen oder mit Markierung am Dateianfang ist lesbar`() = runTest {
        val subject = service(storage("ziel"))

        val bare = subject.preview("""{"format":"schafkopf-tracker-backup"}""")
        assertNull(bare.error)
        assertFalse(bare.hasNewData)
        assertNull(bare.exportedAt)
        assertEquals("", bare.appVersion)
        assertNull(bare.defaultRules)

        // Eine unlesbare Vorlage des Regelwerks (hier: eine unbekannte Spielart) macht die Sicherung nicht unbrauchbar.
        val oddRules = subject.preview("""{"format":"schafkopf-tracker-backup","defaultRules":{"enabledTypes":["ramsch"]}}""")
        assertNull(oddRules.error)
        assertNull(oddRules.defaultRules)
        // Fehlt in der Vorlage ein Wert, gilt dafür wie in jeder Datei der Wert der Vorlage Sauspiel.
        val partialRules = subject.preview("""{"format":"schafkopf-tracker-backup","defaultRules":{"unit":5}}""")
        assertEquals(RuleSet.sauspiel().copy(unit = 5), partialRules.defaultRules)

        val marked = subject.preview(Char(0xFEFF).toString() + backup(listOf(eveningJson(TestData.simpleEvening("gut")))))
        assertNull(marked.error)
        assertEquals(4, marked.newPlayerCount)
        assertEquals(1, marked.newEveningCount)
    }

    @Test
    fun `doppelte Spieler in der Sicherung werden nur einmal angelegt`() = runTest {
        val target = storage("ziel")
        val json = backup(emptyList(), players = listOf(TestData.anna, TestData.bertl, TestData.anna.copy(name = "Doppelt")))

        val result = service(target).import(json)

        assertEquals(listOf(TestData.anna, TestData.bertl), result.newPlayers)
        assertEquals(listOf(TestData.anna, TestData.bertl), target.players.players.first())
    }

    // ---------- Andere Umsetzungen der Ports ----------

    /** Ein Speicher für Abende, bei dem jedes Speichern scheitert. */
    private class FailingEveningRepository : EveningRepository {
        override val evenings: Flow<List<Evening>> = flowOf(emptyList())

        override fun evening(id: EveningId): Flow<Evening?> = flowOf(null)

        override suspend fun save(evening: Evening): Unit = throw IllegalStateException("nachgestellter Fehler")

        override suspend fun delete(id: EveningId) = Unit
    }

    /**
     * Spieler und Abende im Arbeitsspeicher, bei denen unmittelbar nach dem ersten Lesen (gleich welcher der beiden
     * Listen) ein neuer Spieler und ein Abend mit ihm dazukommen. Der Leser bekommt noch den Stand von vorher:
     * so, als trüge jemand genau während des Exports etwas ein.
     */
    private class GrowingStore(
        initialPlayers: List<Player>,
        initialEvenings: List<Evening>,
        private val latePlayer: Player,
        private val lateEvening: Evening,
    ) {
        private val storedPlayers = InMemoryPlayerRepository(initialPlayers)
        private val storedEvenings = InMemoryEveningRepository(initialEvenings)

        var grown = false
            private set

        private suspend fun growOnce() {
            if (grown) return
            grown = true
            storedPlayers.save(latePlayer)
            storedEvenings.save(lateEvening)
        }

        val players: PlayerRepository = object : PlayerRepository {
            override val players: Flow<List<Player>> = flow {
                val read = storedPlayers.players.first()
                growOnce()
                emit(read)
            }

            override suspend fun save(player: Player) = storedPlayers.save(player)
        }

        val evenings: EveningRepository = object : EveningRepository {
            override val evenings: Flow<List<Evening>> = flow {
                val read = storedEvenings.evenings.first()
                growOnce()
                emit(read)
            }

            override fun evening(id: EveningId): Flow<Evening?> = storedEvenings.evening(id)

            override suspend fun save(evening: Evening) = storedEvenings.save(evening)

            override suspend fun delete(id: EveningId) = storedEvenings.delete(id)
        }
    }

    @Test
    fun `die Sicherung arbeitet mit jeder Umsetzung der Ports, nicht nur mit dem Datei-Speicher`() = runTest {
        val json = service(filledStorage("quelle")).exportJson()
        val players = InMemoryPlayerRepository()
        val evenings = InMemoryEveningRepository()
        val settings = InMemorySettingsRepository(AppSettings(haptics = false))
        val subject = BackupService(players, evenings, settings, clock, "0.1.0", Dispatchers.Unconfined)

        val result = subject.import(json)

        assertNull(result.error)
        assertEquals(5, result.newPlayerCount)
        assertEquals(2, result.newEveningCount)
        assertEquals(TestData.players + archived, players.players.first())
        assertEquals(listOf("abend-voll", "abend-einfach"), evenings.evenings.first().map { it.id.value })
        assertEquals(AppSettings(haptics = false), settings.settings.first())

        // Und wieder hinaus: Der Export enthält dieselben Spieler und Abende wie die eingespielte Sicherung.
        val exportedAgain = Json.parseToJsonElement(subject.exportJson()).jsonObject
        val original = Json.parseToJsonElement(json).jsonObject
        assertEquals(original.getValue("players"), exportedAgain.getValue("players"))
        assertEquals(original.getValue("evenings"), exportedAgain.getValue("evenings"))
        assertFalse(subject.preview(json).hasNewData)
    }

    @Test
    fun `scheitert das Speichern aus irgendeinem Grund, steht das im Ergebnis statt in einer Ausnahme`() = runTest {
        val json = service(filledStorage("quelle")).exportJson()
        val players = InMemoryPlayerRepository()
        val subject = BackupService(
            players = players,
            evenings = FailingEveningRepository(),
            settings = InMemorySettingsRepository(),
            clock = clock,
            appVersion = "0.1.0",
            dispatcher = Dispatchers.Unconfined,
        )

        val result = subject.import(json)

        assertEquals(BackupErrorKind.STORAGE_FAILED, result.error?.kind)
        assertFalse(result.isSuccess)
        assertEquals(5, result.newPlayerCount)
        assertEquals(0, result.newEveningCount)
        assertEquals(TestData.players + archived, players.players.first())
    }

    @Test
    fun `laesst sich nicht speichern, meldet der Import das als Fehlerergebnis`() = runTest {
        val json = service(filledStorage("quelle")).exportJson()
        // Wo der Ordner für die Abende sein müsste, liegt eine Datei: Spieler lassen sich speichern, Abende nicht.
        Files.createDirectories(dir.resolve("ziel"))
        Files.writeString(dir.resolve("ziel").resolve("evenings"), "hier liegt eine Datei statt eines Ordners")
        val target = storage("ziel")

        val result = service(target).import(json)

        assertEquals(BackupErrorKind.STORAGE_FAILED, result.error?.kind)
        assertFalse(result.isSuccess)
        assertTrue(result.error?.message.orEmpty().isNotBlank())
        assertEquals(5, result.newPlayerCount)
        assertEquals(0, result.newEveningCount)
        assertEquals(TestData.players + archived, target.players.players.first())
        assertEquals(emptyList<Evening>(), target.evenings.evenings.first())
    }
}
