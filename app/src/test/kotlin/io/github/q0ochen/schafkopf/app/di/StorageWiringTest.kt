package io.github.q0ochen.schafkopf.app.di

import androidx.test.core.app.ApplicationProvider
import io.github.q0ochen.schafkopf.app.TestSchafkopfApp
import io.github.q0ochen.schafkopf.app.storage.LegacyFileImport
import io.github.q0ochen.schafkopf.data.storage.FileStorage
import io.github.q0ochen.schafkopf.database.ImportedCounts
import io.github.q0ochen.schafkopf.database.SchafkopfDatabase
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * Die App speichert dauerhaft: Was eingetragen wurde, ist nach einem Neustart der App noch da, und die Daten der
 * ersten Testversionen (JSON-Dateien) kommen in die Datenbank. Geprüft mit der echten Verdrahtung ([appModule]),
 * einer echten Datenbank-Datei und echten Ordnern, ohne Gerät (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestSchafkopfApp::class)
class StorageWiringTest {

    private val anna = PlayerId("spieler-1")
    private val seating = listOf(anna, PlayerId("spieler-2"), PlayerId("spieler-3"), PlayerId("spieler-4"))
    private val start: Instant = Instant.parse("2026-11-14T18:30:00Z")

    @After
    fun stopDependencyInjection() = stopKoin()

    /** Beendet die App (Koin) und startet sie neu, wie nach dem Wischen aus der Liste der letzten Apps. */
    private fun restartApp() {
        stopKoin()
        startKoin {
            androidContext(ApplicationProvider.getApplicationContext<TestSchafkopfApp>())
            modules(appModule)
        }
    }

    @Test
    fun `Spieler, Abende und Einstellungen ueberstehen einen Neustart der App`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TestSchafkopfApp>()
        val koin = GlobalContext.get()
        val game = GameEntry(EntryId("spiel-1"), start, GameType.RUFSPIEL, declarer = anna, partner = seating[2], declarerWon = true)
        val evening = Evening(
            id = EveningId("abend-1"),
            startedAt = start,
            seating = seating,
            firstDealer = anna,
            rules = RuleSet.standard(),
            entries = listOf(game),
        )
        koin.get<PlayerRepository>().save(Player(anna, "Anna", colorIndex = 0))
        koin.get<EveningRepository>().save(evening)
        koin.get<SettingsRepository>().update { it.copy(themeMode = ThemeMode.LIGHT) }

        restartApp()

        val restarted = GlobalContext.get()
        assertEquals(listOf(Player(anna, "Anna", colorIndex = 0)), restarted.get<PlayerRepository>().players.first())
        assertEquals(listOf(evening), restarted.get<EveningRepository>().evenings.first())
        assertEquals(ThemeMode.LIGHT, restarted.get<SettingsRepository>().settings.first().themeMode)
        // Alles steht in der Datenbank im privaten Speicher der App; den Ordner der ersten Testversionen gibt es nicht.
        assertTrue(app.getDatabasePath(SchafkopfDatabase.FILE_NAME).isFile)
        assertFalse(File(app.filesDir, LegacyFileImport.FOLDER).exists())
    }

    /** Legt die Dateien an, wie sie eine der ersten Testversionen geschrieben hätte. */
    private fun writeLegacyFiles(folder: File, evenings: List<Evening>) = runBlocking {
        val old = FileStorage(folder.toPath(), Dispatchers.IO)
        old.players.save(Player(anna, "Anna", colorIndex = 0))
        old.players.save(Player(PlayerId("spieler-2"), "Bert", colorIndex = 1))
        evenings.forEach { old.evenings.save(it) }
        old.settings.update { it.copy(themeMode = ThemeMode.SYSTEM, keepScreenOn = false) }
    }

    private fun evening(id: String, startedAt: Instant = start) = Evening(
        id = EveningId(id),
        startedAt = startedAt,
        seating = seating,
        firstDealer = anna,
        rules = RuleSet.sauspiel(),
        entries = listOf(GameEntry(EntryId("$id-1"), startedAt, GameType.WENZ, declarer = anna, declarerWon = true, laufende = 2)),
    )

    @Test
    fun `die Daten der ersten Testversionen werden einmalig und vollstaendig uebernommen`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TestSchafkopfApp>()
        val folder = File(app.filesDir, LegacyFileImport.FOLDER)
        val evenings = listOf(evening("alt", start.minusSeconds(86_400)), evening("neu"))
        writeLegacyFiles(folder, evenings)
        val koin = GlobalContext.get()
        suspend fun run() = LegacyFileImport.runIfNeeded(folder.toPath(), koin.get())

        assertEquals(ImportedCounts(players = 2, evenings = 2, settings = true), run())
        assertEquals(listOf("Anna", "Bert"), koin.get<PlayerRepository>().players.first().map { it.name })
        assertEquals(evenings.sortedByDescending { it.startedAt }, koin.get<EveningRepository>().evenings.first())
        val settings = koin.get<SettingsRepository>().settings.first()
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.keepScreenOn)
        // Der alte Ordner bleibt als Rückfall erhalten, wird aber nicht noch einmal eingelesen.
        assertFalse(folder.exists())
        assertTrue(File(app.filesDir, LegacyFileImport.FOLDER + LegacyFileImport.RETIRED_SUFFIX + "/players.json").isFile)
        assertNull(run())

        // Nach einem Neustart der App ist alles noch da.
        restartApp()
        assertEquals(2, GlobalContext.get().get<EveningRepository>().evenings.first().size)
    }

    @Test
    fun `die Uebernahme ueberschreibt nichts, was schon in der Datenbank steht`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TestSchafkopfApp>()
        val koin = GlobalContext.get()
        // In der Datenbank steht schon etwas, zum Beispiel weil ein früherer Versuch nach dem Speichern abbrach.
        koin.get<PlayerRepository>().save(Player(anna, "Anni", colorIndex = 3))
        koin.get<EveningRepository>().save(evening("neu").copy(location = "schon da"))
        koin.get<SettingsRepository>().update { it.copy(themeMode = ThemeMode.LIGHT) }
        val folder = File(app.filesDir, LegacyFileImport.FOLDER)
        writeLegacyFiles(folder, listOf(evening("alt", start.minusSeconds(86_400)), evening("neu")))

        val outcome = LegacyFileImport.runIfNeeded(folder.toPath(), koin.get())
        assertEquals(ImportedCounts(players = 1, evenings = 1, settings = false), outcome)
        assertEquals(listOf("Anni", "Bert"), koin.get<PlayerRepository>().players.first().map { it.name })
        assertEquals(listOf("schon da", ""), koin.get<EveningRepository>().evenings.first().map { it.location })
        assertEquals(ThemeMode.LIGHT, koin.get<SettingsRepository>().settings.first().themeMode)
    }

    @Test
    fun `ohne alten Ordner gibt es nichts zu uebernehmen`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TestSchafkopfApp>()
        val koin = GlobalContext.get()
        val folder = File(app.filesDir, LegacyFileImport.FOLDER).toPath()
        assertNull(LegacyFileImport.runIfNeeded(folder, koin.get()))
        assertTrue(koin.get<PlayerRepository>().players.first().isEmpty())
    }
}
