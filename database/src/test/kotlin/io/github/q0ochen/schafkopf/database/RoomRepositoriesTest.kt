package io.github.q0ochen.schafkopf.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.github.q0ochen.schafkopf.domain.evening.BockOverride
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Integrationstests der Datenbank: echtes Room mit SQLite im Arbeitsspeicher, ausgeführt auf der JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class RoomRepositoriesTest {

    /** Merkt sich Regelwerke unter einem Schlüssel; so lässt sich prüfen, dass genau die Kopie des Abends zurückkommt. */
    private class FakeRulesCodec : RulesCodec {
        private val store = HashMap<String, RuleSet>()

        override fun encode(rules: RuleSet): String = store.entries.firstOrNull { it.value == rules }?.key
            ?: "regeln-${store.size}".also { store[it] = rules }

        override fun decode(text: String): RuleSet? = store[text]
    }

    private lateinit var database: SchafkopfDatabase
    private lateinit var players: RoomPlayerRepository
    private lateinit var evenings: RoomEveningRepository

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val start: Instant = Instant.parse("2026-11-14T18:30:00Z")

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SchafkopfDatabase::class.java).allowMainThreadQueries().build()
        players = RoomPlayerRepository(database)
        evenings = RoomEveningRepository(database, FakeRulesCodec())
    }

    @After
    fun close() = database.close()

    private fun evening(id: String = "abend", startedAt: Instant = start, entries: List<io.github.q0ochen.schafkopf.domain.evening.EveningEntry> = emptyList()) =
        Evening(
            id = EveningId(id),
            startedAt = startedAt,
            location = "Wirtshaus",
            seating = listOf(a, b, c, d),
            firstDealer = b,
            rules = RuleSet.sauspiel(),
            entries = entries,
        )

    private fun fullGame(id: String) = GameEntry(
        id = EntryId(id),
        recordedAt = start.plusSeconds(60),
        type = GameType.FARBSOLO,
        suit = Suit.HERZ,
        declarer = c,
        partner = null,
        declarerWon = false,
        level = ResultLevel.SCHWARZ,
        laufende = 4,
        announcement = Announcement.TOUT,
        klopfer = setOf(a, d),
        kontra = KontraLevel.RETOUR,
        kontraBy = a,
        retourBy = c,
        bockOverride = BockOverride.FORCE_ON,
        declarerEyes = 17,
        note = "teuer",
    )

    private fun entryCount(): Int = database.query("SELECT COUNT(*) FROM entries", null).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

    @Test
    fun `Spieler bleiben in der Reihenfolge, in der sie angelegt wurden`() = runTest {
        players.save(Player(b, "Berta", colorIndex = 1))
        players.save(Player(a, "Anton", colorIndex = 0))
        players.save(Player(c, "Cilli", colorIndex = 2))

        assertEquals(listOf("Berta", "Anton", "Cilli"), players.players.first().map { it.name })
    }

    @Test
    fun `umbenannte und archivierte Spieler behalten ihren Platz`() = runTest {
        players.save(Player(a, "Anton", colorIndex = 0))
        players.save(Player(b, "Berta", colorIndex = 1))
        players.save(Player(a, "Toni", colorIndex = 5, archived = true))

        assertEquals(
            listOf(Player(a, "Toni", 5, archived = true), Player(b, "Berta", 1)),
            players.players.first(),
        )
    }

    @Test
    fun `die Spielerliste meldet jede Aenderung`() = runTest {
        players.players.test {
            assertEquals(emptyList<Player>(), awaitItem())
            players.save(Player(a, "Anton", 0))
            assertEquals(listOf("Anton"), awaitItem().map { it.name })
            players.save(Player(a, "Toni", 0))
            assertEquals(listOf("Toni"), awaitItem().map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ein Abend mit allen Eintragsarten kommt unveraendert zurueck`() = runTest {
        val original = evening(
            entries = listOf(
                GameEntry(EntryId("e1"), start, GameType.RUFSPIEL, Suit.EICHEL, declarer = a, partner = c, declarerWon = true),
                fullGame("e2"),
                ThrownInEntry(EntryId("e3"), start.plusSeconds(120)),
                BockTriggerEntry(EntryId("e4"), start.plusSeconds(180), BockReason.SECHS_NIXER, player = d, handThrownIn = false),
                BockTriggerEntry(EntryId("e5"), start.plusSeconds(200), BockReason.MANUAL),
                ManualEntry(EntryId("e6"), start.plusSeconds(240), mapOf(a to 150, b to -150), "Wette"),
            ),
        ).copy(finishedAt = start.plusSeconds(3600))

        evenings.save(original)

        assertEquals(original, evenings.evening(original.id).first())
        assertEquals(listOf(original), evenings.evenings.first())
    }

    @Test
    fun `der neueste Abend steht vorn`() = runTest {
        evenings.save(evening("alt", start))
        evenings.save(evening("neu", start.plusSeconds(86_400)))
        evenings.save(evening("mitte", start.plusSeconds(3_600)))

        assertEquals(listOf("neu", "mitte", "alt"), evenings.evenings.first().map { it.id.value })
    }

    @Test
    fun `erneutes Speichern ersetzt die Eintraege und behaelt ihre Reihenfolge`() = runTest {
        val first = ThrownInEntry(EntryId("e1"), start)
        val second = fullGame("e2")
        val third = ThrownInEntry(EntryId("e3"), start)
        evenings.save(evening(entries = listOf(first, second, third)))

        val changed = evening(entries = listOf(third, second.copy(laufende = 0)))
        evenings.save(changed)

        assertEquals(changed, evenings.evening(changed.id).first())
        assertEquals(2, entryCount())
    }

    @Test
    fun `der Abend meldet Anlegen, Aendern und Loeschen`() = runTest {
        val id = EveningId("abend")
        evenings.evening(id).test {
            assertNull(awaitItem())
            evenings.save(evening())
            assertEquals(0, awaitItem()?.entries?.size)
            evenings.save(evening(entries = listOf(ThrownInEntry(EntryId("e1"), start))))
            assertEquals(1, awaitItem()?.entries?.size)
            evenings.delete(id)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mit dem Abend verschwinden auch seine Eintraege`() = runTest {
        evenings.save(evening("bleibt", entries = listOf(ThrownInEntry(EntryId("x1"), start))))
        evenings.save(evening("weg", entries = listOf(fullGame("y1"), ThrownInEntry(EntryId("y2"), start))))
        assertEquals(3, entryCount())

        evenings.delete(EveningId("weg"))

        assertEquals(1, entryCount())
        assertEquals(listOf("bleibt"), evenings.evenings.first().map { it.id.value })
    }

    @Test
    fun `unlesbare Zeilen werden uebersprungen statt die App abstuerzen zu lassen`() = runTest {
        evenings.save(evening(entries = listOf(ThrownInEntry(EntryId("gut"), start))))
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            "INSERT INTO entries (id, evening_id, position, kind, recorded_at) VALUES ('fremd', 'abend', 1, 'unbekannt', 0)",
        )
        sql.execSQL(
            "INSERT INTO entries (id, evening_id, position, kind, recorded_at, game_type, declarer, declarer_won) " +
                "VALUES ('kaputt', 'abend', 2, 'game', 0, 'SKAT', 'A', 1)",
        )

        assertEquals(listOf("gut"), evenings.evening(EveningId("abend")).first()?.entries?.map { it.id.value })
    }

    @Test
    fun `ein unlesbares Regelwerk faellt auf die Standardregeln zurueck`() = runTest {
        evenings.save(evening().copy(rules = RuleSet.sauspiel().copy(unit = 77)))
        database.openHelper.writableDatabase.execSQL("UPDATE evenings SET rules_json = 'kaputt' WHERE id = 'abend'")

        // Die Standardregeln der Runde (RuleSet.standard()), nicht die Vorlage Sauspiel.
        assertEquals(RuleSet.standard(), evenings.evening(EveningId("abend")).first()?.rules)
    }

    /**
     * Die Namen der Enum-Werte stehen in der Datenbank. Wer hier etwas umbenennt, macht alte Daten unlesbar
     * und braucht eine Migration.
     */
    @Test
    fun `Schema-Waechter - gespeicherte Namen der Aufzaehlungen`() {
        assertEquals(listOf("NORMAL", "SCHNEIDER", "SCHWARZ"), ResultLevel.entries.map { it.name })
        assertEquals(listOf("NONE", "TOUT", "SIE"), Announcement.entries.map { it.name })
        assertEquals(listOf("NONE", "KONTRA", "RETOUR"), KontraLevel.entries.map { it.name })
        assertEquals(listOf("EICHEL", "GRAS", "HERZ", "SCHELLEN"), Suit.entries.map { it.name })
        assertEquals(listOf("AUTO", "FORCE_ON", "FORCE_OFF"), BockOverride.entries.map { it.name })
        assertEquals(listOf("SECHS_NIXER", "MANUAL"), BockReason.entries.map { it.name })
        assertEquals(setOf("RUFSPIEL", "FARBSOLO", "WENZ", "GEIER"), GameType.entries.map { it.name }.toSet() - "UEBER_KREUZ")
    }
}
