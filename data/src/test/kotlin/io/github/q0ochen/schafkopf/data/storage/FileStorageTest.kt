package io.github.q0ochen.schafkopf.data.storage

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.AiSettings
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

class FileStorageTest {

    @field:TempDir
    lateinit var dir: Path

    private fun storage(folder: Path = dir): FileStorage = FileStorage(folder, Dispatchers.Unconfined)

    /** Alle Dateien unterhalb eines Ordners, mit Schrägstrich als Trenner und nach Namen sortiert. */
    private fun filesBelow(folder: Path): List<String> {
        val found = ArrayList<String>()

        fun visit(current: Path) {
            Files.newDirectoryStream(current).use { entries ->
                for (entry in entries) {
                    if (Files.isDirectory(entry)) visit(entry) else found.add(folder.relativize(entry).toString().replace('\\', '/'))
                }
            }
        }

        if (Files.isDirectory(folder)) visit(folder)
        return found.sorted()
    }

    // ---------- Laden ----------

    @Test
    fun `ohne Dateien ist alles leer und es gelten die Standardeinstellungen`() = runTest {
        val storage = storage()

        assertEquals(emptyList<Player>(), storage.players.players.first())
        assertEquals(emptyList<Evening>(), storage.evenings.evenings.first())
        assertNull(storage.evenings.evening(EveningId("gibt-es-nicht")).first())
        assertEquals(AppSettings(), storage.settings.settings.first())
    }

    @Test
    fun `geladen wird erst beim ersten Zugriff und nur ein Mal, auf dem uebergebenen Dispatcher`() = runTest {
        val folder = dir.resolve("daten").resolve("tief")
        val dispatcher = CountingDispatcher()
        val storage = FileStorage(folder, dispatcher)

        // Das Anlegen allein fasst den Datenträger nicht an.
        assertFalse(Files.exists(folder))
        assertEquals(0, dispatcher.dispatched)

        // Der erste Zugriff lädt alles und legt fehlende Ordner an.
        storage.players.players.first()
        assertTrue(Files.isDirectory(folder.resolve("evenings")))
        assertEquals(1, dispatcher.dispatched)

        // Danach kommt alles aus dem Speicher.
        storage.evenings.evenings.first()
        storage.evenings.evening(EveningId("x")).first()
        storage.settings.settings.first()
        storage.players.players.first()
        assertEquals(1, dispatcher.dispatched)

        // Jeder Schreibzugriff geht genau ein Mal auf den Datenträger.
        storage.players.save(TestData.anna)
        assertEquals(2, dispatcher.dispatched)
        storage.evenings.save(TestData.simpleEvening("abend"))
        assertEquals(3, dispatcher.dispatched)
        storage.settings.update { it.copy(haptics = false) }
        assertEquals(4, dispatcher.dispatched)
        storage.evenings.delete(EveningId("abend"))
        assertEquals(5, dispatcher.dispatched)
    }

    @Test
    fun `das Laden laeuft zu Ende, auch wenn der erste Zuhoerer aufgibt`() = runTest {
        val dispatcher = CountingDispatcher()
        val storage = FileStorage(dir, dispatcher)
        val listener = launch {
            cancel()
            storage.players.players.first()
        }
        listener.join()

        assertTrue(listener.isCancelled)
        assertEquals(1, dispatcher.dispatched)

        // Der nächste Zugriff muss nicht noch einmal laden.
        assertEquals(emptyList<Player>(), storage.players.players.first())
        assertEquals(1, dispatcher.dispatched)
    }

    @Test
    fun `ein neues FileStorage auf demselben Ordner liest dasselbe wieder`() = runTest {
        val first = storage()
        for (player in TestData.players) first.players.save(player)
        val full = TestData.fullEvening("abend-voll", TestData.start)
        val simple = TestData.simpleEvening("abend-einfach", TestData.start.minusSeconds(86_400))
        first.evenings.save(simple)
        first.evenings.save(full)
        first.settings.update { TestData.customSettings }

        val second = storage()

        assertEquals(TestData.players, second.players.players.first())
        assertEquals(listOf(full, simple), second.evenings.evenings.first())
        assertEquals(full, second.evenings.evening(full.id).first())
        assertEquals(simple, second.evenings.evening(simple.id).first())
        assertEquals(TestData.customSettings, second.settings.settings.first())
    }

    @Test
    fun `ein geloeschter Spieler bleibt auch nach dem Neuladen weg`() = runTest {
        val first = storage()
        first.players.save(TestData.anna)
        first.players.save(TestData.bertl)
        first.players.delete(TestData.anna.id)
        assertEquals(listOf(TestData.bertl), first.players.players.first())
        assertEquals(listOf(TestData.bertl), storage().players.players.first())
    }

    @Test
    fun `einen unbekannten Spieler zu loeschen schreibt nichts`() = runTest {
        val storage = storage()
        storage.players.delete(PlayerId("gibt-es-nicht"))
        assertEquals(emptyList<Player>(), storage.players.players.first())
        assertEquals(emptyList<String>(), filesBelow(dir))
    }

    @Test
    fun `nach dem Speichern liegen genau die erwarteten Dateien im Ordner und keine temporaere`() = runTest {
        val storage = storage()
        storage.players.save(TestData.anna)
        storage.players.save(TestData.bertl)
        storage.evenings.save(TestData.fullEvening("abend-voll"))
        storage.evenings.save(TestData.simpleEvening("abend-einfach"))
        storage.evenings.save(TestData.simpleEvening("abend-einfach").copy(location = "Keller"))
        storage.settings.update { it.copy(keepScreenOn = false) }

        assertEquals(
            listOf("evenings/abend-einfach.json", "evenings/abend-voll.json", "players.json", "settings.json"),
            filesBelow(dir),
        )
    }

    @Test
    fun `jede Datei traegt die Schema-Version 1`() = runTest {
        val storage = storage()
        storage.players.save(TestData.anna)
        storage.evenings.save(TestData.simpleEvening("abend"))
        storage.settings.update { it }

        for (name in listOf("players.json", "settings.json", "evenings/abend.json")) {
            val root = Json.parseToJsonElement(Files.readString(dir.resolve(name))).jsonObject
            assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.int, name)
        }
    }

    // ---------- Abgebrochene Schreibvorgänge (Akku leer) ----------

    @Test
    fun `eine Hilfsdatei neben der eigentlichen Datei wird entfernt und der bestaetigte Stand gilt`() = runTest {
        val first = storage()
        val evening = TestData.simpleEvening("abend")
        first.players.save(TestData.anna)
        first.evenings.save(evening)
        // Nachgestellt: Der nächste Schreibvorgang kam nicht mehr bis zum Ersetzen, egal wie weit er war.
        Files.writeString(dir.resolve("players.json.tmp"), "{\"schemaVersion\":1,\"play")
        Files.writeString(
            dir.resolve("evenings/abend.json.tmp"),
            StorageCodec.encodeEvening(evening.copy(location = "nie bestätigt")),
        )

        val second = storage()

        assertEquals(listOf(TestData.anna), second.players.players.first())
        assertEquals(listOf(evening), second.evenings.evenings.first())
        assertEquals(listOf("evenings/abend.json", "players.json"), filesBelow(dir))
    }

    @Test
    fun `fehlt die eigentliche Datei, tritt eine vollstaendig geschriebene Hilfsdatei an ihre Stelle`() = runTest {
        // Nachgestellt: Abbruch, nachdem die neue Fassung fertig geschrieben, aber noch nicht an ihrem Platz war.
        val evening = TestData.fullEvening("abend")
        Files.createDirectories(dir.resolve("evenings"))
        Files.writeString(dir.resolve("evenings/abend.json.tmp"), StorageCodec.encodeEvening(evening))
        Files.writeString(dir.resolve("players.json.tmp"), StorageCodec.encodePlayers(listOf(TestData.anna)))
        Files.writeString(dir.resolve("settings.json.tmp"), StorageCodec.encodeSettings(TestData.customSettings))

        val storage = storage()

        assertEquals(listOf(evening), storage.evenings.evenings.first())
        assertEquals(listOf(TestData.anna), storage.players.players.first())
        assertEquals(TestData.customSettings, storage.settings.settings.first())
        assertEquals(listOf("evenings/abend.json", "players.json", "settings.json"), filesBelow(dir))
    }

    @Test
    fun `eine nur halb geschriebene Hilfsdatei ohne eigentliche Datei wird entfernt`() = runTest {
        Files.createDirectories(dir.resolve("evenings"))
        Files.writeString(dir.resolve("players.json.tmp"), "{\"schemaVersion\":1,\"play")
        Files.writeString(dir.resolve("evenings/abend.json.tmp"), "{\"id\":")
        Files.writeString(dir.resolve("settings.json.tmp"), "")

        val storage = storage()

        assertEquals(emptyList<Player>(), storage.players.players.first())
        assertEquals(emptyList<Evening>(), storage.evenings.evenings.first())
        assertEquals(AppSettings(), storage.settings.settings.first())
        assertEquals(emptyList<String>(), filesBelow(dir))
    }

    @Test
    fun `fremde Dateien mit der Endung tmp werden beim Laden weder geloescht noch umbenannt`() = runTest {
        // Im selben Ordner können Dateien liegen, die nicht von diesem Speicher stammen. Aufgeräumt werden nur die
        // eigenen Hilfsdateien: players.json.tmp, settings.json.tmp und evenings/<Name>.json.tmp.
        Files.createDirectories(dir.resolve("evenings"))
        Files.writeString(dir.resolve("notizen.tmp"), "{\"fremd\":true}")
        Files.writeString(dir.resolve("halb.tmp"), "{\"fremd\":")
        Files.writeString(dir.resolve("evenings/liste.tmp"), "{}")

        val storage = storage()
        assertEquals(emptyList<Player>(), storage.players.players.first())
        assertEquals(emptyList<Evening>(), storage.evenings.evenings.first())

        assertEquals(listOf("evenings/liste.tmp", "halb.tmp", "notizen.tmp"), filesBelow(dir))
        assertEquals("{\"fremd\":true}", Files.readString(dir.resolve("notizen.tmp")))
        assertEquals("{\"fremd\":", Files.readString(dir.resolve("halb.tmp")))
        assertEquals("{}", Files.readString(dir.resolve("evenings/liste.tmp")))
    }

    // ---------- Kaputte, neuere und ältere Dateien ----------

    @Test
    fun `eine kaputte Datei wird zu defekt und die uebrigen Daten bleiben lesbar`() = runTest {
        val first = storage()
        first.players.save(TestData.anna)
        first.settings.update { it.copy(haptics = false) }
        val good = TestData.simpleEvening("gut")
        first.evenings.save(good)
        first.evenings.save(TestData.simpleEvening("kaputt"))
        Files.writeString(dir.resolve("evenings/kaputt.json"), "{ das ist kein JSON")

        val second = storage()

        assertEquals(listOf(good), second.evenings.evenings.first())
        assertNull(second.evenings.evening(EveningId("kaputt")).first())
        assertEquals(listOf(TestData.anna), second.players.players.first())
        assertEquals(AppSettings(haptics = false), second.settings.settings.first())
        assertEquals(
            listOf("evenings/gut.json", "evenings/kaputt.json.defekt", "players.json", "settings.json"),
            filesBelow(dir),
        )
        assertEquals("{ das ist kein JSON", Files.readString(dir.resolve("evenings/kaputt.json.defekt")))

        // Die Kennung ist wieder frei: Ein neuer Abend mit ihr wird ganz normal gespeichert.
        val again = TestData.simpleEvening("kaputt")
        second.evenings.save(again)
        assertEquals(again, storage().evenings.evening(again.id).first())
    }

    @Test
    fun `kaputte Spielerliste und kaputte Einstellungen werden beiseitegelegt und die App laeuft weiter`() = runTest {
        val evening = TestData.simpleEvening("abend")
        Files.createDirectories(dir.resolve("evenings"))
        Files.writeString(dir.resolve("evenings/abend.json"), StorageCodec.encodeEvening(evening))
        Files.writeString(dir.resolve("players.json"), "[1, 2")
        Files.writeString(dir.resolve("settings.json"), "")

        val storage = storage()

        assertEquals(emptyList<Player>(), storage.players.players.first())
        assertEquals(AppSettings(), storage.settings.settings.first())
        assertEquals(listOf(evening), storage.evenings.evenings.first())
        assertEquals(
            listOf("evenings/abend.json", "players.json.defekt", "settings.json.defekt"),
            filesBelow(dir),
        )

        // Danach lässt sich wieder ganz normal speichern.
        storage.players.save(TestData.anna)
        storage.settings.update { it.copy(keepScreenOn = false) }
        assertEquals(listOf(TestData.anna), storage().players.players.first())
        assertEquals(AppSettings(keepScreenOn = false), storage().settings.settings.first())
    }

    @Test
    fun `eine vorhandene defekt-Datei wird ueberschrieben`() = runTest {
        Files.createDirectories(dir.resolve("evenings"))
        Files.writeString(dir.resolve("evenings/abend.json.defekt"), "alter Schaden")
        Files.writeString(dir.resolve("evenings/abend.json"), "neuer Schaden")

        assertEquals(emptyList<Evening>(), storage().evenings.evenings.first())

        assertEquals(listOf("evenings/abend.json.defekt"), filesBelow(dir))
        assertEquals("neuer Schaden", Files.readString(dir.resolve("evenings/abend.json.defekt")))
    }

    @Test
    fun `eine extrem tief verschachtelte Datei wird beiseitegelegt und bringt die App nicht zum Absturz`() = runTest {
        val good = TestData.simpleEvening("gut")
        val brackets = "[".repeat(200_000)
        Files.createDirectories(dir.resolve("evenings"))
        Files.writeString(dir.resolve("evenings/gut.json"), StorageCodec.encodeEvening(good))
        // 200 000 ineinandergeschachtelte Listen in einem Eintrag, dessen Art nicht vorn steht: Um sie zu finden,
        // wird der ganze Eintrag gelesen. Das darf höchstens einen Lesefehler geben.
        Files.writeString(
            dir.resolve("evenings/tief.json"),
            "{\"id\":\"tief\",\"startedAt\":\"2026-11-14T19:00:00Z\",\"seating\":[\"A\",\"B\",\"C\",\"D\"]," +
                "\"firstDealer\":\"A\",\"entries\":[{\"id\":\"e1\",\"unsinn\":$brackets",
        )
        // Dasselbe als liegengebliebene Hilfsdatei eines Abends, den es sonst nicht gibt.
        Files.writeString(dir.resolve("evenings/neu.json.tmp"), brackets)

        val storage = storage()

        assertEquals(listOf(good), storage.evenings.evenings.first())
        assertEquals(listOf("evenings/gut.json", "evenings/tief.json.defekt"), filesBelow(dir))
    }

    @Test
    fun `Dateien aus einer neueren App und Dateien mit unbekanntem Wert werden beiseitegelegt`() = runTest {
        val good = TestData.simpleEvening("gut")
        val newer = StorageCodec.encodeEvening(TestData.simpleEvening("neuer"))
            .replace("{\"schemaVersion\":1,", "{\"schemaVersion\":2,")
        val unknownGame = StorageCodec.encodeEvening(TestData.simpleEvening("ramsch"))
            .replace("\"gameType\":\"rufspiel\"", "\"gameType\":\"ramsch\"")
        assertTrue(newer.contains("\"schemaVersion\":2"))
        assertTrue(unknownGame.contains("\"gameType\":\"ramsch\""))
        Files.createDirectories(dir.resolve("evenings"))
        Files.writeString(dir.resolve("evenings/gut.json"), StorageCodec.encodeEvening(good))
        Files.writeString(dir.resolve("evenings/neuer.json"), newer)
        Files.writeString(dir.resolve("evenings/ramsch.json"), unknownGame)
        Files.writeString(dir.resolve("players.json"), """{"schemaVersion":2,"players":[{"id":"x","name":"Zukunft"}]}""")

        val storage = storage()

        assertEquals(emptyList<Player>(), storage.players.players.first())
        assertEquals(listOf(good), storage.evenings.evenings.first())
        assertEquals(
            listOf(
                "evenings/gut.json",
                "evenings/neuer.json.defekt",
                "evenings/ramsch.json.defekt",
                "players.json.defekt",
            ),
            filesBelow(dir),
        )
    }

    @Test
    fun `unbekannte Felder werden ueberlesen und fehlende optionale Felder bekommen Standardwerte`() = runTest {
        Files.createDirectories(dir.resolve("evenings"))
        Files.writeString(
            dir.resolve("players.json"),
            """{"schemaVersion":1,"neu":true,"players":[{"id":"spieler-anna","name":"Anna","lieblingsspiel":"wenz"}]}""",
        )
        Files.writeString(dir.resolve("settings.json"), """{"zukunft":{"a":[1,2,3]},"themeMode":"dark"}""")
        Files.writeString(
            dir.resolve("evenings/abend.json"),
            """{"id":"abend","startedAt":"2026-11-14T19:00:00Z","seating":["A","B","C","D"],"firstDealer":"A","wetter":"Schnee"}""",
        )

        val storage = storage()

        assertEquals(listOf(TestData.anna), storage.players.players.first())
        assertEquals(AppSettings(themeMode = ThemeMode.DARK), storage.settings.settings.first())
        assertEquals(
            listOf(
                Evening(
                    id = EveningId("abend"),
                    startedAt = Instant.parse("2026-11-14T19:00:00Z"),
                    seating = listOf(PlayerId("A"), PlayerId("B"), PlayerId("C"), PlayerId("D")),
                    firstDealer = PlayerId("A"),
                    rules = RuleSet.sauspiel(),
                ),
            ),
            storage.evenings.evenings.first(),
        )
        // Nichts wurde beiseitegelegt.
        assertEquals(listOf("evenings/abend.json", "players.json", "settings.json"), filesBelow(dir))
    }

    @Test
    fun `liegen mehrere Dateien fuer denselben Abend vor, gilt die mit dem regulaeren Namen`() = runTest {
        val evening = TestData.simpleEvening("abend")
        storage().evenings.save(evening)
        val copy = StorageCodec.encodeEvening(evening.copy(location = "alte Kopie"))
        Files.writeString(dir.resolve("evenings/aaa-kopie.json"), copy)
        Files.writeString(dir.resolve("evenings/zzz-kopie.json"), copy)

        val storage = storage()

        assertEquals(listOf(evening), storage.evenings.evenings.first())
        assertEquals(
            listOf("evenings/aaa-kopie.json.defekt", "evenings/abend.json", "evenings/zzz-kopie.json.defekt"),
            filesBelow(dir),
        )
    }

    // ---------- Spieler ----------

    @Test
    fun `Spieler behalten die Reihenfolge des Anlegens und werden an ihrer Stelle ersetzt`() = runTest {
        val storage = storage()
        storage.players.save(TestData.bertl)
        storage.players.save(TestData.anna)
        storage.players.save(TestData.centa)
        assertEquals(listOf(TestData.bertl, TestData.anna, TestData.centa), storage.players.players.first())

        val renamed = TestData.anna.copy(name = "Annamirl", colorIndex = 9, archived = true)
        storage.players.save(renamed)

        assertEquals(listOf(TestData.bertl, renamed, TestData.centa), storage.players.players.first())
        assertEquals(listOf(TestData.bertl, renamed, TestData.centa), storage().players.players.first())
    }

    // ---------- Abende ----------

    @Test
    fun `die Abende kommen mit dem neuesten zuerst`() = runTest {
        val storage = storage()
        val day = 86_400L
        val middle = TestData.simpleEvening("mitte", TestData.start)
        val oldest = TestData.simpleEvening("alt", TestData.start.minusSeconds(day))
        val newest = TestData.simpleEvening("neu", TestData.start.plusSeconds(day))
        storage.evenings.save(middle)
        storage.evenings.save(oldest)
        storage.evenings.save(newest)

        assertEquals(listOf(newest, middle, oldest), storage.evenings.evenings.first())
        assertEquals(listOf(newest, middle, oldest), storage().evenings.evenings.first())

        // Bekommt ein Abend einen anderen Beginn, wird er neu einsortiert.
        val moved = oldest.copy(startedAt = TestData.start.plusSeconds(2 * day))
        storage.evenings.save(moved)
        assertEquals(listOf(moved, newest, middle), storage.evenings.evenings.first())

        // Bei gleichem Beginn entscheidet die Kennung, damit die Reihenfolge immer dieselbe ist.
        val twin = TestData.simpleEvening("mitte-2", TestData.start)
        storage.evenings.save(twin)
        assertEquals(listOf(moved, newest, middle, twin), storage.evenings.evenings.first())
        assertEquals(listOf(moved, newest, middle, twin), storage().evenings.evenings.first())
    }

    @Test
    fun `ein gespeicherter Abend ersetzt den Abend mit derselben Kennung`() = runTest {
        val storage = storage()
        val long = TestData.fullEvening("abend")
        storage.evenings.save(long)
        val short = long.copy(entries = long.entries.take(1), finishedAt = null, location = "")
        storage.evenings.save(short)

        assertEquals(listOf(short), storage.evenings.evenings.first())
        assertEquals(listOf(short), storage().evenings.evenings.first())
        // Die Datei enthält genau den neuen Inhalt, kein Rest des längeren alten bleibt stehen.
        assertEquals(StorageCodec.encodeEvening(short), Files.readString(dir.resolve("evenings/abend.json")))
    }

    @Test
    fun `delete entfernt den Abend und seine Datei`() = runTest {
        val storage = storage()
        val stays = TestData.simpleEvening("bleibt")
        val leaves = TestData.fullEvening("geht")
        storage.evenings.save(stays)
        storage.evenings.save(leaves)

        storage.evenings.delete(leaves.id)

        assertEquals(listOf(stays), storage.evenings.evenings.first())
        assertNull(storage.evenings.evening(leaves.id).first())
        assertEquals(listOf("evenings/bleibt.json"), filesBelow(dir))
        assertEquals(listOf(stays), storage().evenings.evenings.first())

        // Einen Abend zu löschen, den es nicht gibt, ist kein Fehler.
        storage.evenings.delete(EveningId("gibt-es-nicht"))
        assertEquals(listOf(stays), storage.evenings.evenings.first())
    }

    @Test
    fun `gefaehrliche Kennungen ergeben harmlose Dateinamen`() = runTest {
        val folder = dir.resolve("daten")
        val storage = storage(folder)
        val ids = listOf("../x", "..", "a/b\\c:d*e?", "", "Abend 1.json", "ok_ID-7")
        ids.forEachIndexed { index, id ->
            storage.evenings.save(TestData.simpleEvening(id, TestData.start.plusSeconds(index.toLong())))
        }

        // Nur Buchstaben, Ziffern, "_" und "-" bleiben; alles andere wird zu "_".
        assertEquals(
            setOf(
                "daten/evenings/___x.json",
                "daten/evenings/__.json",
                "daten/evenings/a_b_c_d_e_.json",
                "daten/evenings/_.json",
                "daten/evenings/Abend_1_json.json",
                "daten/evenings/ok_ID-7.json",
            ),
            filesBelow(dir).toSet(),
        )
        // Die Kennungen selbst bleiben unverändert erhalten.
        assertEquals(ids.toSet(), storage(folder).evenings.evenings.first().map { it.id.value }.toSet())
    }

    @Test
    fun `sehr lange Kennungen ergeben einen gekuerzten Dateinamen`() = runTest {
        val storage = storage()
        val longId = "x".repeat(500)
        val evening = TestData.simpleEvening(longId)
        storage.evenings.save(evening)

        assertEquals(listOf("evenings/" + "x".repeat(80) + ".json"), filesBelow(dir))
        assertEquals(listOf(evening), storage().evenings.evenings.first())
    }

    @Test
    fun `Kennungen mit gleichem Dateinamen ueberschreiben sich nicht`() = runTest {
        val storage = storage()
        val slash = TestData.simpleEvening("a/b", TestData.start)
        val underscore = TestData.simpleEvening("a_b", TestData.start.plusSeconds(1))
        val dot = TestData.simpleEvening("a.b", TestData.start.plusSeconds(2))
        storage.evenings.save(slash)
        storage.evenings.save(underscore)
        storage.evenings.save(dot)

        assertEquals(listOf("evenings/a_b.json", "evenings/a_b_2.json", "evenings/a_b_3.json"), filesBelow(dir))
        val reloaded = storage()
        assertEquals(listOf(dot, underscore, slash), reloaded.evenings.evenings.first())

        // Eine Änderung landet wieder in derselben Datei, ein gelöschter Abend nimmt nur seine eigene Datei mit.
        val changed = underscore.copy(location = "Keller")
        reloaded.evenings.save(changed)
        reloaded.evenings.delete(slash.id)
        assertEquals(listOf("evenings/a_b_2.json", "evenings/a_b_3.json"), filesBelow(dir))
        assertEquals(listOf(dot, changed), storage().evenings.evenings.first())
    }

    // ---------- Einstellungen ----------

    @Test
    fun `Einstellungen werden schrittweise geaendert, gespeichert und wieder gelesen`() = runTest {
        val storage = storage()
        storage.settings.update { it.copy(keepScreenOn = false) }
        storage.settings.update { it.copy(ai = it.ai.copy(enabled = true, model = "testmodell-7")) }

        val expected = AppSettings(keepScreenOn = false, ai = AiSettings(enabled = true, model = "testmodell-7"))
        assertEquals(expected, storage.settings.settings.first())
        assertEquals(expected, storage().settings.settings.first())

        storage.settings.update { TestData.customSettings }
        assertEquals(TestData.customSettings, storage().settings.settings.first())
    }

    // ---------- Beobachten ----------

    @Test
    fun `ein beobachteter Abend meldet jede eigene Aenderung und am Ende sein Verschwinden`() = runTest {
        val storage = storage()
        val evening = TestData.fullEvening("abend")
        val seen = ArrayList<Evening?>()
        val observer = launch(Dispatchers.Unconfined) {
            storage.evenings.evening(evening.id).collect { seen.add(it) }
        }

        storage.evenings.save(evening)
        val longer = evening.append(ThrownInEntry(EntryId("neu"), TestData.start.plusSeconds(3000)))
        storage.evenings.save(longer)
        storage.evenings.save(TestData.simpleEvening("ein-anderer"))
        storage.players.save(TestData.anna)
        storage.evenings.delete(evening.id)
        observer.cancel()

        assertEquals(listOf(null, evening, longer, null), seen)
    }

    @Test
    fun `beobachtete Listen melden nur ihre eigenen Aenderungen`() = runTest {
        val storage = storage()
        val playerNames = ArrayList<List<String>>()
        val eveningIds = ArrayList<List<String>>()
        val themes = ArrayList<ThemeMode>()
        val observers = listOf(
            launch(Dispatchers.Unconfined) {
                storage.players.players.collect { list -> playerNames.add(list.map { it.name }) }
            },
            launch(Dispatchers.Unconfined) {
                storage.evenings.evenings.collect { list -> eveningIds.add(list.map { it.id.value }) }
            },
            launch(Dispatchers.Unconfined) {
                storage.settings.settings.collect { themes.add(it.themeMode) }
            },
        )

        storage.players.save(TestData.anna)
        storage.evenings.save(TestData.simpleEvening("abend"))
        storage.settings.update { it.copy(themeMode = ThemeMode.LIGHT) }
        storage.players.save(TestData.bertl)
        observers.forEach { it.cancel() }

        assertEquals(listOf(emptyList(), listOf("Anna"), listOf("Anna", "Bertl")), playerNames)
        assertEquals(listOf(emptyList(), listOf("abend")), eveningIds)
        assertEquals(listOf(ThemeMode.DARK, ThemeMode.LIGHT), themes)
    }

    // ---------- Schreiben ----------

    @Test
    fun `mit dem Test-Dispatcher als IO-Dispatcher verhaelt sich der Speicher genauso`() = runTest {
        val storage = FileStorage(dir, StandardTestDispatcher(testScheduler))
        val evening = TestData.fullEvening("abend")

        storage.players.save(TestData.anna)
        storage.evenings.save(evening)
        storage.settings.update { it.copy(haptics = false) }

        assertEquals(listOf(TestData.anna), storage.players.players.first())
        assertEquals(evening, storage.evenings.evening(evening.id).first())
        assertEquals(AppSettings(haptics = false), storage.settings.settings.first())
        val reloaded = FileStorage(dir, StandardTestDispatcher(testScheduler))
        assertEquals(listOf(evening), reloaded.evenings.evenings.first())
    }

    @Test
    fun `gleichzeitige Schreibzugriffe gehen nicht verloren`() = runTest {
        // Die Aufrufer laufen echt nebeneinander auf mehreren Threads; der Speicher muss sie nacheinander bedienen.
        val storage = storage()
        val players = List(40) { Player(PlayerId("spieler-$it"), "Spieler $it", colorIndex = it) }
        val evenings = List(20) { TestData.simpleEvening("abend-$it", TestData.start.plusSeconds(it.toLong())) }

        withContext(Dispatchers.Default) {
            for (player in players) launch { storage.players.save(player) }
            for (evening in evenings) launch { storage.evenings.save(evening) }
        }

        assertEquals(players.toSet(), storage.players.players.first().toSet())
        assertEquals(evenings.sortedWith(NEWEST_FIRST), storage.evenings.evenings.first())
        val reloaded = storage()
        assertEquals(players.toSet(), reloaded.players.players.first().toSet())
        assertEquals(40, reloaded.players.players.first().size)
        assertEquals(evenings.sortedWith(NEWEST_FIRST), reloaded.evenings.evenings.first())
    }

    @Test
    fun `ein begonnenes Speichern laesst sich nicht abbrechen`() = runTest {
        val storage = storage()
        val caller = launch {
            cancel()
            storage.players.save(TestData.anna)
        }
        caller.join()

        assertTrue(caller.isCancelled)
        assertEquals(listOf(TestData.anna), storage().players.players.first())
    }

    @Test
    fun `schlaegt das Schreiben fehl, kommt der Fehler zurueck und der Stand im Speicher bleibt`() = runTest {
        // Wo der Ordner für die Abende sein müsste, liegt eine Datei: Abende lassen sich nicht speichern.
        Files.writeString(dir.resolve("evenings"), "hier liegt eine Datei statt eines Ordners")
        val storage = storage()

        val failure = runCatching { storage.evenings.save(TestData.simpleEvening("abend")) }.exceptionOrNull()

        assertTrue(failure is IOException, "erwartet war eine IOException, bekommen: $failure")
        assertEquals(emptyList<Evening>(), storage.evenings.evenings.first())
        assertEquals(listOf("evenings"), filesBelow(dir))

        // Die Spieler liegen in einer anderen Datei und lassen sich weiter speichern.
        storage.players.save(TestData.anna)
        assertEquals(listOf(TestData.anna), storage().players.players.first())
    }

    @Test
    fun `geschrieben wird erst vollstaendig in eine Hilfsdatei im selben Ordner, dann wird in einem Schritt ersetzt`() {
        val moves = ArrayList<String>()
        val contentsAtMove = ArrayList<String>()
        val mover = FileMover { source, target, atomic ->
            moves.add("${dir.relativize(source)} -> ${dir.relativize(target)}, atomar=$atomic".replace('\\', '/'))
            contentsAtMove.add(Files.readString(source))
            NioFileMover.move(source, target, atomic)
        }
        val files = StorageFiles(dir, mover)
        val evening = TestData.simpleEvening("abend")

        files.writePlayers(listOf(TestData.anna))
        files.writeEvening(evening, emptyMap())

        assertEquals(
            listOf(
                "players.json.tmp -> players.json, atomar=true",
                "evenings/abend.json.tmp -> evenings/abend.json, atomar=true",
            ),
            moves,
        )
        // Beim Ersetzen steht der vollständige neue Inhalt schon in der Hilfsdatei.
        assertEquals(
            listOf(StorageCodec.encodePlayers(listOf(TestData.anna)), StorageCodec.encodeEvening(evening)),
            contentsAtMove,
        )
        assertEquals(listOf("evenings/abend.json", "players.json"), filesBelow(dir))
    }

    @Test
    fun `kann das Dateisystem nicht in einem Schritt ersetzen, wird die Datei trotzdem ersetzt`() {
        val attempts = ArrayList<Boolean>()
        val mover = FileMover { source, target, atomic ->
            attempts.add(atomic)
            if (atomic) throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "nachgestellt")
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
        val files = StorageFiles(dir, mover)

        files.writePlayers(listOf(TestData.anna))
        files.writePlayers(listOf(TestData.anna, TestData.bertl))

        // Je Schreibvorgang erst der Versuch in einem Schritt, dann der Ersatzweg.
        assertEquals(listOf(true, false, true, false), attempts)
        assertEquals(listOf(TestData.anna, TestData.bertl), StorageFiles(dir).load().players)
        assertEquals(listOf("players.json"), filesBelow(dir))
    }

    @Test
    fun `scheitert das Ersetzen, bleibt die alte Datei unversehrt und keine temporaere liegen`() {
        StorageFiles(dir).writePlayers(listOf(TestData.anna))
        val failing = StorageFiles(dir, FileMover { _, _, _ -> throw IOException("nachgestellter Fehler") })

        val failure = runCatching { failing.writePlayers(listOf(TestData.bertl)) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(listOf(TestData.anna), StorageFiles(dir).load().players)
        assertEquals(listOf("players.json"), filesBelow(dir))
    }

    @Test
    fun `scheitert der Ersatzweg nach dem Entfernen der alten Datei, geht die neue Fassung nicht verloren`() {
        StorageFiles(dir).writePlayers(listOf(TestData.anna))
        val mover = FileMover { source, target, atomic ->
            if (atomic) throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "nachgestellt")
            // So arbeitet der Ersatzweg: erst die alte Datei entfernen, dann die neue einsetzen.
            // Hier scheitert der zweite Schritt.
            Files.delete(target)
            throw IOException("nachgestellter Fehler beim Einsetzen")
        }

        val failure = runCatching {
            StorageFiles(dir, mover).writePlayers(listOf(TestData.anna, TestData.bertl))
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        // Die fertig geschriebene Hilfsdatei ist die einzige Fassung, die es noch gibt; sie bleibt liegen ...
        assertEquals(listOf("players.json.tmp"), filesBelow(dir))
        // ... und tritt beim nächsten Laden an die Stelle der fehlenden Datei.
        assertEquals(listOf(TestData.anna, TestData.bertl), StorageFiles(dir).load().players)
        assertEquals(listOf("players.json"), filesBelow(dir))
    }

    @Test
    fun `ein geloeschter Abend nimmt auch eine liegengebliebene Hilfsdatei mit`() {
        val files = StorageFiles(dir)
        val evening = TestData.simpleEvening("abend")
        val file = files.writeEvening(evening, emptyMap())
        Files.writeString(
            dir.resolve("evenings/abend.json.tmp"),
            StorageCodec.encodeEvening(evening.copy(location = "liegen geblieben")),
        )

        files.delete(file)

        assertEquals(emptyList<String>(), filesBelow(dir))
        assertEquals(emptyList<Evening>(), StorageFiles(dir).load().evenings)
    }

    @Test
    fun `aus einer Kennung wird ein ungefaehrlicher Dateiname`() {
        assertEquals("___x", safeFileStem("../x"))
        assertEquals("ok_ID-7", safeFileStem("ok_ID-7"))
        assertEquals("4f1c-9a_B", safeFileStem("4f1c-9a_B"))
        assertEquals("_", safeFileStem(""))
        assertEquals("C__Windows_x", safeFileStem("C:\\Windows\\x"))
        assertEquals("____", safeFileStem("äöüß"))
        assertEquals("y".repeat(80), safeFileStem("y".repeat(81)))
    }
}
