package io.github.q0ochen.schafkopf.app.di

import androidx.test.core.app.ApplicationProvider
import io.github.q0ochen.schafkopf.app.TestSchafkopfApp
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Die App speichert dauerhaft: Was eingetragen wurde, ist nach einem Neustart der App noch da. Geprüft mit der
 * echten Verdrahtung ([appModule]) und einem echten Ordner, ohne Gerät (Robolectric).
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
        // Die Dateien liegen im privaten Speicher der App, im Ordner „daten“.
        val folder = File(app.filesDir, STORAGE_FOLDER)
        assertTrue(File(folder, "players.json").isFile)
        assertTrue(File(folder, "settings.json").isFile)
        assertEquals(1, File(folder, "evenings").listFiles { file -> file.name.endsWith(".json") }?.size)
    }
}
