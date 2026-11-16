package io.github.q0ochen.schafkopf.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Einstellungen in der Datenbank, und ob die Datenbank noch leer ist (entscheidet über die Übernahme alter Daten). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class RoomSettingsRepositoryTest {

    /** Merkt sich Einstellungen unter einem Schlüssel; „kaputt“ ist nicht lesbar. */
    private class FakeSettingsCodec : SettingsCodec {
        private val store = HashMap<String, AppSettings>()

        override fun encode(settings: AppSettings): String = store.entries.firstOrNull { it.value == settings }?.key
            ?: "einstellungen-${store.size}".also { store[it] = settings }

        override fun decode(text: String): AppSettings? = store[text]
    }

    private class FakeRulesCodec : RulesCodec {
        override fun encode(rules: RuleSet): String = "regeln"
        override fun decode(text: String): RuleSet = RuleSet.standard()
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: SchafkopfDatabase
    private val codec = FakeSettingsCodec()

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, SchafkopfDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = database.close()

    @Test
    fun `ohne gespeicherte Einstellungen gelten die Standardwerte`() = runTest {
        assertEquals(AppSettings(), RoomSettingsRepository(database, codec).settings.first())
    }

    @Test
    fun `Aenderungen werden gespeichert und gemeldet, keine geht verloren`() = runTest {
        val settings = RoomSettingsRepository(database, codec)
        settings.settings.test {
            assertEquals(AppSettings(), awaitItem())
            settings.update { it.copy(themeMode = ThemeMode.LIGHT) }
            assertEquals(ThemeMode.LIGHT, awaitItem().themeMode)
            settings.update { it.copy(keepScreenOn = false) }
            val both = awaitItem()
            assertEquals(ThemeMode.LIGHT, both.themeMode)
            assertFalse(both.keepScreenOn)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(AppSettings(themeMode = ThemeMode.LIGHT, keepScreenOn = false), RoomSettingsRepository(database, codec).settings.first())
    }

    @Test
    fun `unlesbare Einstellungen fallen auf die Standardwerte zurueck`() = runTest {
        val settings = RoomSettingsRepository(database, codec)
        settings.update { it.copy(themeMode = ThemeMode.SYSTEM) }
        database.openHelper.writableDatabase.execSQL("UPDATE settings SET json = 'kaputt' WHERE id = 0")
        assertEquals(AppSettings(), settings.settings.first())
        settings.update { it.copy(haptics = false) }
        assertEquals(AppSettings(haptics = false), settings.settings.first())
    }

    @Test
    fun `die Uebernahme fuegt nur Fehlendes hinzu und laesst sich gefahrlos wiederholen`() = runTest {
        val seating = listOf("a", "b", "c", "d").map(::PlayerId)
        fun evening(id: String, location: String = "") = Evening(
            EveningId(id), Instant.parse("2026-11-14T18:30:00Z"), location = location, seating = seating,
            firstDealer = seating[0], rules = RuleSet.standard(),
        )
        val rules = FakeRulesCodec()
        RoomPlayerRepository(database).save(Player(PlayerId("a"), "Anni", 0))
        RoomEveningRepository(database, rules).save(evening("x", location = "schon da"))
        val old = listOf(Player(PlayerId("a"), "Anna", 0), Player(PlayerId("b"), "Bert", 1))
        val oldSettings = AppSettings(themeMode = ThemeMode.SYSTEM)

        val first = database.importMissing(old, listOf(evening("x"), evening("y")), oldSettings, rules, codec)
        assertEquals(ImportedCounts(players = 1, evenings = 1, settings = true), first)
        assertEquals(listOf("Anni", "Bert"), RoomPlayerRepository(database).players.first().map { it.name })
        assertEquals(
            setOf("x" to "schon da", "y" to ""),
            RoomEveningRepository(database, rules).evenings.first().map { it.id.value to it.location }.toSet(),
        )
        assertEquals(ThemeMode.SYSTEM, RoomSettingsRepository(database, codec).settings.first().themeMode)

        val again = database.importMissing(old, listOf(evening("x"), evening("y")), AppSettings(themeMode = ThemeMode.LIGHT), rules, codec)
        assertEquals(ImportedCounts(players = 0, evenings = 0, settings = false), again)
        assertEquals(ThemeMode.SYSTEM, RoomSettingsRepository(database, codec).settings.first().themeMode)
        assertTrue(RoomPlayerRepository(database).players.first().size == 2)
        assertFalse(RoomEveningRepository(database, rules).evenings.first().size != 2)
    }

    @Test
    fun `die Datenbank-Datei bleibt ueber das Schliessen hinaus erhalten`() = runTest {
        val name = "dauerhaft.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, SchafkopfDatabase::class.java, name).allowMainThreadQueries().build()
        RoomPlayerRepository(first).save(Player(PlayerId("a"), "Anna", 0))
        RoomSettingsRepository(first, codec).update { it.copy(themeMode = ThemeMode.LIGHT) }
        first.close()

        val again = Room.databaseBuilder(context, SchafkopfDatabase::class.java, name).allowMainThreadQueries().build()
        assertEquals(listOf(Player(PlayerId("a"), "Anna", 0)), RoomPlayerRepository(again).players.first())
        assertEquals(ThemeMode.LIGHT, RoomSettingsRepository(again, codec).settings.first().themeMode)
        again.close()
    }
}
