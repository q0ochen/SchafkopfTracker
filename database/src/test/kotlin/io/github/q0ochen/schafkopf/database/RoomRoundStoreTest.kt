package io.github.q0ochen.schafkopf.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningNotStorableException
import io.github.q0ochen.schafkopf.domain.round.EveningBase
import io.github.q0ochen.schafkopf.domain.round.RemotePlayer
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundLink
import io.github.q0ochen.schafkopf.domain.round.RoundMember
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * Was sich das Gerät zum Abgleich mit der gemeinsamen Runde merkt, und der Umzug der Datenbank auf Version 2:
 * echtes Room mit SQLite, ausgeführt auf der JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class RoomRoundStoreTest {

    /** Merkt sich Abende und Runden unter einem Schlüssel statt sie wirklich in JSON zu übersetzen. */
    private class FakeRoundCodec : RoundCodec {
        val evenings = HashMap<String, Evening>()
        val links = HashMap<String, RoundLink>()

        override fun encodeLink(link: RoundLink): String = "runde-${links.size}".also { links[it] = link }

        override fun decodeLink(text: String): RoundLink? = links[text]

        override fun encodeEvening(evening: Evening): String = "abend-${evenings.size}".also { evenings[it] = evening }

        override fun decodeEvening(text: String): Evening? = evenings[text]
    }

    private object PlainRulesCodec : RulesCodec {
        override fun encode(rules: RuleSet): String = rules.name

        override fun decode(text: String): RuleSet? = if (text == RuleSet.standard().name) RuleSet.standard() else null
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: SchafkopfDatabase
    private val codec = FakeRoundCodec()
    private val start: Instant = Instant.parse("2026-12-12T18:00:00Z")
    private val seating = listOf("spieler-1", "spieler-2", "spieler-3", "spieler-4").map(::PlayerId)

    private fun evening(id: String, vararg entries: String) = Evening(
        id = EveningId(id),
        startedAt = start,
        seating = seating,
        firstDealer = seating.first(),
        rules = RuleSet.standard(),
        entries = entries.map { ThrownInEntry(EntryId(it), start.plusSeconds(60)) },
    )

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, SchafkopfDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = database.close()

    // --- Die verbundene Runde ---

    @Test
    fun `ohne Runde gibt es keine Verbindung, mit Runde kommt sie unveraendert zurueck`() = runTest {
        val store = RoomRoundStore(database, codec)
        val link = RoundLink(
            roundId = RoundId("runde"),
            roundName = "Stammtisch",
            userId = "konto-1",
            playerId = PlayerId("spieler-1"),
            isCreator = true,
            cursor = 42,
            rulesBase = RuleSet.sauspiel(),
            members = listOf(RoundMember("konto-1", PlayerId("spieler-1")), RoundMember("konto-2", null)),
        )

        store.link.test {
            assertNull(awaitItem())
            store.setLink(link)
            assertEquals(link, awaitItem())
            store.setLink(link.copy(cursor = 43))
            assertEquals(43L, awaitItem()?.cursor)
            store.setLink(null)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `eine Verbindung, die sich nicht mehr lesen laesst, gilt als keine`() = runTest {
        database.roundDao().upsertLink(RoundLinkEntity(json = "unlesbar"))

        assertNull(RoomRoundStore(database, codec).link.first())
    }

    // --- Basen ---

    @Test
    fun `Basen der Abende und Spieler ueberstehen das Schliessen der App`() = runTest {
        val store = RoomRoundStore(database, codec)
        val first = EveningBase(3, evening("abend-1", "e1"))
        val second = EveningBase(1, evening("abend-2"))
        val owned = RemotePlayer(PlayerId("spieler-1"), "Nord", 0, archived = false, position = 1, owner = "konto-1", nameLocked = true, rev = 9)
        val guest = RemotePlayer(PlayerId("gast"), "Wirt", 4, archived = true, position = 5, rev = 12)

        store.setEveningBase(first.evening.id, first)
        store.setEveningBase(second.evening.id, second)
        store.setPlayerBase(owned.id, owned)
        store.setPlayerBase(guest.id, guest)
        store.setEveningBase(first.evening.id, first.copy(version = 4))
        store.setPlayerBase(guest.id, guest.copy(name = "Wirtin"))

        val expectedEvenings = mapOf(first.evening.id to first.copy(version = 4), second.evening.id to second)
        val expectedPlayers = mapOf(owned.id to owned, guest.id to guest.copy(name = "Wirtin"))
        assertEquals(expectedEvenings, store.eveningBases())
        assertEquals(expectedPlayers, store.playerBases())
        // Ein neuer Speicher über derselben Datenbank liest alles wieder ein.
        val reopened = RoomRoundStore(database, codec)
        assertEquals(expectedEvenings, reopened.eveningBases())
        assertEquals(expectedPlayers, reopened.playerBases())
    }

    @Test
    fun `eine Basis laesst sich einzeln entfernen, alle zusammen beim Trennen`() = runTest {
        val store = RoomRoundStore(database, codec)
        val base = EveningBase(1, evening("abend-1"))
        val player = RemotePlayer(PlayerId("gast"), "Wirt", 4, archived = false)
        store.setEveningBase(base.evening.id, base)
        store.setEveningBase(EveningId("abend-2"), EveningBase(1, evening("abend-2")))
        store.setPlayerBase(player.id, player)
        store.setPlayerBase(PlayerId("gast-2"), player.copy(id = PlayerId("gast-2"), name = "Nachbar"))

        store.setEveningBase(base.evening.id, null)
        store.setPlayerBase(player.id, null)
        assertEquals(setOf(EveningId("abend-2")), store.eveningBases().keys)
        assertEquals(setOf(PlayerId("gast-2")), RoomRoundStore(database, codec).playerBases().keys)

        store.clearBases()
        assertTrue(store.eveningBases().isEmpty() && store.playerBases().isEmpty())
        val reopened = RoomRoundStore(database, codec)
        assertTrue(reopened.eveningBases().isEmpty() && reopened.playerBases().isEmpty())
        // Danach lässt sich wieder ganz normal schreiben.
        store.setEveningBase(base.evening.id, base)
        assertEquals(mapOf(base.evening.id to base), RoomRoundStore(database, codec).eveningBases())
    }

    @Test
    fun `eine Basis, die sich nicht mehr lesen laesst, gilt als nicht vorhanden`() = runTest {
        database.roundDao().upsertEveningBase(EveningBaseEntity("abend-1", version = 3, json = "unlesbar"))
        val readable = EveningBase(2, evening("abend-2"))
        RoomRoundStore(database, codec).setEveningBase(readable.evening.id, readable)

        assertEquals(mapOf(readable.evening.id to readable), RoomRoundStore(database, codec).eveningBases())
    }

    @Test
    fun `was der Abgleich abfragt, ist eine Kopie und aendert sich nicht nachtraeglich`() = runTest {
        val store = RoomRoundStore(database, codec)
        val before = store.eveningBases()

        store.setEveningBase(EveningId("abend-1"), EveningBase(1, evening("abend-1")))

        assertTrue(before.isEmpty())
        assertEquals(1, store.eveningBases().size)
    }

    // --- Abende, die sich nicht speichern lassen ---

    @Test
    fun `traegt ein Eintrag die Kennung eines Eintrags aus einem anderen Abend, wird nichts gespeichert`() = runTest {
        val evenings = RoomEveningRepository(database, PlainRulesCodec)
        evenings.save(evening("abend-1", "e1", "e2"))

        assertThrows(EveningNotStorableException::class.java) {
            kotlinx.coroutines.runBlocking { evenings.save(evening("abend-2", "neu", "e2")) }
        }
        assertThrows(EveningNotStorableException::class.java) {
            kotlinx.coroutines.runBlocking { evenings.save(evening("abend-1", "e1", "doppelt", "doppelt")) }
        }

        // Der vorhandene Abend ist unverändert, der neue gar nicht da.
        assertEquals(listOf(evening("abend-1", "e1", "e2")), evenings.evenings.first())
    }

    // --- Umzug von Version 1 ---

    @Test
    fun `der Umzug auf Version 2 behaelt Spieler, Abende und Einstellungen und legt die neuen Tabellen an`() = runTest {
        val name = "umzug-test.db"
        context.deleteDatabase(name)
        createVersion1(name)

        val migrated = Room.databaseBuilder(context, SchafkopfDatabase::class.java, name)
            .addMigrations(*SCHAFKOPF_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(listOf(Player(PlayerId("spieler-1"), "Nord", colorIndex = 2, archived = true)), RoomPlayerRepository(migrated).players.first())
            val stored = RoomEveningRepository(migrated, PlainRulesCodec).evenings.first().single()
            assertEquals(evening("abend-1", "e1"), stored)
            assertEquals("{\"einstellungen\":true}", migrated.settingsDao().current())

            // Die neuen Tabellen sind da und leer.
            val store = RoomRoundStore(migrated, codec)
            assertNull(store.link.first())
            assertTrue(store.eveningBases().isEmpty() && store.playerBases().isEmpty())
            store.setLink(RoundLink(RoundId("runde"), "Stammtisch", "konto-1"))
            store.setEveningBase(stored.id, EveningBase(1, stored))
            store.setPlayerBase(PlayerId("spieler-1"), RemotePlayer(PlayerId("spieler-1"), "Nord", 2, archived = true))
            assertEquals("Stammtisch", RoomRoundStore(migrated, codec).link.first()?.roundName)
            assertEquals(2, migrated.openHelper.readableDatabase.version)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * Legt eine Datenbank genau so an, wie Version 1 der App sie angelegt hat: mit den Anweisungen aus dem
     * eingecheckten Schema `schemas/…/1.json`, dazu ein Spieler, ein Abend mit einem Eintrag und die Einstellungen.
     */
    private fun createVersion1(name: String) {
        val schema = JSONObject(File("schemas/${SchafkopfDatabase::class.java.name}/1.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (i in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
            db.execSQL("INSERT INTO players (id, name, color_index, archived, sort_order) VALUES ('spieler-1', 'Nord', 2, 1, 0)")
            db.execSQL(
                "INSERT INTO evenings (id, started_at, finished_at, location, seat_1, seat_2, seat_3, seat_4, first_dealer, rules_json) " +
                    "VALUES ('abend-1', ${start.toEpochMilli()}, NULL, '', 'spieler-1', 'spieler-2', 'spieler-3', 'spieler-4', 'spieler-1', " +
                    "'${RuleSet.standard().name}')",
            )
            db.execSQL(
                "INSERT INTO entries (id, evening_id, position, kind, recorded_at) " +
                    "VALUES ('e1', 'abend-1', 0, 'thrown_in', ${start.plusSeconds(60).toEpochMilli()})",
            )
            db.execSQL("INSERT INTO settings (id, json) VALUES (0, '{\"einstellungen\":true}')")
            db.version = 1
        }
    }
}
