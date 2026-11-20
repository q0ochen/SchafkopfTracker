package io.github.q0ochen.schafkopf.app.round

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.q0ochen.schafkopf.app.SchafkopfApp
import io.github.q0ochen.schafkopf.app.storage.JsonRulesCodec
import io.github.q0ochen.schafkopf.app.storage.JsonSettingsCodec
import io.github.q0ochen.schafkopf.database.RoomEveningRepository
import io.github.q0ochen.schafkopf.database.RoomPlayerRepository
import io.github.q0ochen.schafkopf.database.RoomRoundStore
import io.github.q0ochen.schafkopf.database.RoomSettingsRepository
import io.github.q0ochen.schafkopf.database.SchafkopfDatabase
import io.github.q0ochen.schafkopf.domain.evening.BockOverride
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.round.InMemoryRoundServer
import io.github.q0ochen.schafkopf.domain.round.RoundConnection
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundSync
import io.github.q0ochen.schafkopf.domain.round.SyncOutcome
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant

/**
 * Der Abgleich mit der echten Datenbank der App (Room) und der echten Übersetzung nach JSON: Was über Datenbank,
 * Server und zurück läuft, muss danach genauso aussehen wie vorher. Sonst hielte jedes Gerät den Abend für geändert
 * und lüde ihn immer wieder hoch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoundSyncRoomTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = InMemoryRoundServer()
    private val devices = ArrayList<RoomDevice>()
    private val seating = SchafkopfApp.DEFAULT_PLAYERS.map { it.id }
    private val start: Instant = Instant.parse("2026-12-12T18:00:00Z")

    /** Ein Handy mit eigener Datenbank. [restarted] baut alles über derselben Datenbank neu auf, wie nach einem Neustart. */
    private inner class RoomDevice(val userId: String, existing: SchafkopfDatabase? = null) {
        val database: SchafkopfDatabase =
            existing ?: Room.inMemoryDatabaseBuilder(context, SchafkopfDatabase::class.java).allowMainThreadQueries().build()
        val players = RoomPlayerRepository(database)
        val evenings = RoomEveningRepository(database, JsonRulesCodec)
        val settings = RoomSettingsRepository(database, JsonSettingsCodec)
        val store = RoomRoundStore(database, JsonRoundCodec)
        val session = server.sessionFor(userId)
        private var counter = 0
        private val ids = IdGenerator { "$userId-${System.identityHashCode(this)}-${++counter}" }
        val editor = EveningEditor(evenings, Clock.systemUTC(), ids)
        val sync = RoundSync(session, store, players, evenings, settings, editor)
        val connection = RoundConnection(session, store, players, evenings, sync, ids)

        init {
            if (existing == null) devices += this
        }

        fun restarted() = RoomDevice(userId, database)

        suspend fun roundId(): RoundId = store.link.first()!!.roundId

        suspend fun synced(): SyncOutcome.Synced = sync.sync() as SyncOutcome.Synced

        suspend fun addGame(evening: EveningId, won: Boolean = true) {
            editor.update(evening) {
                it.append(
                    GameEntry(editor.newEntryId(), editor.now(), GameType.RUFSPIEL, declarer = seating[0], partner = seating[1], declarerWon = won),
                )
            }
        }
    }

    @After
    fun close() = devices.forEach { it.database.close() }

    /** Ein Abend mit allem, was die App eintragen kann. */
    private fun fullEvening(id: String = "abend-1") = Evening(
        id = EveningId(id),
        startedAt = start,
        finishedAt = start.plusSeconds(4 * 3600),
        location = "Wirtshaus „Zur Post“ & Söhne",
        seating = seating,
        firstDealer = seating[2],
        rules = RuleSet.sauspiel(),
        entries = listOf(
            GameEntry(EntryId("$id-1"), start.plusMillis(60_123), GameType.RUFSPIEL, Suit.EICHEL, declarer = seating[0], partner = seating[2], declarerWon = true),
            GameEntry(
                id = EntryId("$id-2"),
                recordedAt = start.plusSeconds(120),
                type = GameType.FARBSOLO,
                suit = Suit.HERZ,
                declarer = seating[1],
                declarerWon = false,
                level = ResultLevel.SCHWARZ,
                laufende = 4,
                announcement = Announcement.TOUT,
                klopfer = setOf(seating[3], seating[0]),
                kontra = KontraLevel.RETOUR,
                kontraBy = seating[0],
                retourBy = seating[1],
                bockOverride = BockOverride.FORCE_ON,
                declarerEyes = 17,
                note = "teuer, mit „Anführung“ und\nZeilenumbruch",
            ),
            ThrownInEntry(EntryId("$id-3"), start.plusSeconds(180)),
            BockTriggerEntry(EntryId("$id-4"), start.plusSeconds(240), BockReason.SECHS_NIXER, player = seating[3], handThrownIn = false),
            BockTriggerEntry(EntryId("$id-5"), start.plusSeconds(300), BockReason.MANUAL),
            ManualEntry(EntryId("$id-6"), start.plusSeconds(360), mapOf(seating[0] to 150, seating[1] to -150), "Wette"),
            GameEntry(EntryId("$id-7"), start.plusSeconds(420), GameType.UEBER_KREUZ, declarer = seating[0], partner = seating[2], declarerWon = true),
            GameEntry(EntryId("$id-8"), start.plusSeconds(480), GameType.WENZ, declarer = seating[3], declarerWon = true, laufende = 2),
            GameEntry(EntryId("$id-9"), start.plusSeconds(540), GameType.GEIER, declarer = seating[2], declarerWon = false),
        ),
    )

    /** Handy eins legt die Runde mit seinen Daten an, Handy zwei tritt bei. */
    private suspend fun round(vararg evenings: Evening): Pair<RoomDevice, RoomDevice> {
        val one = RoomDevice("konto-1")
        SchafkopfApp.DEFAULT_PLAYERS.forEachIndexed { index, player -> one.players.save(player.copy(name = "Platz ${index + 1}")) }
        evenings.forEach { one.evenings.save(it) }
        assertTrue(one.connection.create("Stammtisch", one.userId, SchafkopfApp.DEFAULT_PLAYERS) is SyncOutcome.Synced)
        val two = RoomDevice("konto-2")
        assertTrue(two.connection.join(one.session.createInvite(one.roundId()), two.userId) is SyncOutcome.Synced)
        one.synced()
        return one to two
    }

    /** Nichts wartet, und ein weiterer Abgleich holt nichts und lädt nichts hoch. */
    private suspend fun assertSettled(vararg all: RoomDevice) {
        for (device in all) {
            device.session.calls.clear()
            assertEquals("${device.userId} ist nicht zur Ruhe gekommen", SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 0), device.sync.sync())
            assertEquals("${device.userId} hat noch einmal geschrieben", listOf("head"), device.session.calls)
        }
    }

    @Test
    fun `ein Abend mit allen Eintragsarten sieht nach Datenbank, Server und zurueck aus wie vorher`() = runBlocking {
        val evening = fullEvening()
        val (one, two) = round(evening)

        assertEquals(evening, one.evenings.evening(evening.id).first())
        assertEquals(evening, server.eveningRow(one.roundId(), evening.id)?.evening)
        assertEquals(evening, two.evenings.evening(evening.id).first())
        assertEquals(one.players.players.first(), two.players.players.first())
        assertEquals(RuleSet.standard(), two.settings.settings.first().defaultRules)
        // Der Abend wurde genau einmal geschrieben; niemand hält ihn danach für geändert.
        assertEquals(1, server.eveningRow(one.roundId(), evening.id)?.version)
        assertSettled(one, two)
        assertEquals(1, server.eveningRow(one.roundId(), evening.id)?.version)
    }

    @Test
    fun `nach einem Neustart der App ist nichts zu tun und nichts verloren`() = runBlocking {
        val (one, two) = round(fullEvening())
        val round = one.roundId()
        two.addGame(EveningId("abend-1"))

        // Handy zwei wird beendet, bevor das Spiel hochgeladen ist, und neu gestartet.
        val again = two.restarted()
        assertEquals(round, again.roundId())
        assertEquals(1, again.sync.pendingCount())
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), again.sync.sync())

        one.synced()
        assertEquals(10, one.evenings.evening(EveningId("abend-1")).first()?.entries?.size)
        assertSettled(one.restarted(), again.restarted())
    }

    @Test
    fun `zwei Handys tragen gleichzeitig ein und kommen zur Ruhe`() = runBlocking {
        val (one, two) = round(fullEvening().copy(finishedAt = null))
        val evening = EveningId("abend-1")

        one.addGame(evening)
        two.addGame(evening, won = false)
        two.editor.update(evening) { it.copy(location = "Nebenzimmer") }
        one.synced()
        two.synced()
        one.synced()

        val merged = one.evenings.evening(evening).first()!!
        assertEquals(11, merged.entries.size)
        assertEquals("Nebenzimmer", merged.location)
        assertEquals(merged, two.evenings.evening(evening).first())
        assertEquals(merged, server.eveningRow(one.roundId(), evening)?.evening)
        assertSettled(one, two)
    }

    @Test
    fun `das Regelwerk fuer neue Abende wandert einmal und bleibt dann stehen`() = runBlocking {
        val (one, two) = round()
        val rules = RuleSet.sauspiel().copy(name = "Unsere Runde", sieLaufende = 4)

        one.settings.update { it.copy(defaultRules = rules) }
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 1, pending = 0), one.sync.sync())
        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 0), two.sync.sync())

        assertEquals(rules, two.settings.settings.first().defaultRules)
        assertSettled(one, two, two.restarted())
    }

    @Test
    fun `Zeitpunkte genauer als Millisekunden werden einmal gerundet und laufen dann nicht im Kreis`() = runBlocking {
        val (one, two) = round()
        val round = one.roundId()
        // Ein anderes Programm hat einen Abend mit Nanosekunden geschrieben; die Datenbank der App kennt Millisekunden.
        val precise = Instant.parse("2026-12-12T19:00:00.123456789Z")
        val foreign = fullEvening("fremd").copy(startedAt = precise, entries = listOf(ThrownInEntry(EntryId("fremd-1"), precise)))
        one.session.saveEvening(round, foreign, expectedVersion = 0)

        one.synced()
        two.synced()
        one.synced()

        val rounded = Instant.parse("2026-12-12T19:00:00.123Z")
        val stored = one.evenings.evening(foreign.id).first()!!
        assertEquals(rounded, stored.startedAt)
        assertEquals(rounded, stored.entries.single().recordedAt)
        assertEquals(stored, two.evenings.evening(foreign.id).first())
        // Genau eine berichtigte Fassung, dann ist Ruhe.
        assertEquals(2, server.eveningRow(round, foreign.id)?.version)
        assertEquals(stored, server.eveningRow(round, foreign.id)?.evening)
        assertSettled(one, two)
        assertEquals(2, server.eveningRow(round, foreign.id)?.version)
    }

    @Test
    fun `ein Abend, den die Datenbank nicht speichern kann, haelt die anderen nicht auf`() = runBlocking {
        val (one, two) = round(fullEvening())
        val round = one.roundId()
        // Ein fremder Abend, dessen Eintrag dieselbe Kennung trägt wie ein Eintrag eines vorhandenen Abends.
        val clash = fullEvening("fremd").copy(entries = listOf(ThrownInEntry(EntryId("abend-1-3"), start)))
        one.session.saveEvening(round, clash, expectedVersion = 0)
        one.session.saveEvening(round, fullEvening("gut").copy(entries = emptyList()), expectedVersion = 0)

        val outcome = two.synced()

        assertEquals(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 0, unreadable = 1), outcome)
        assertNull(two.evenings.evening(clash.id).first())
        assertFalse(two.evenings.evening(EveningId("gut")).first() == null)
        assertEquals(fullEvening(), two.evenings.evening(EveningId("abend-1")).first())
        // Auf dem Server bleibt der fremde Abend unberührt. Das Gerät merkt ihn sich und liest ihn bei jedem Abgleich
        // einzeln nach (vielleicht lässt er sich später speichern), schreibt aber nichts.
        assertEquals(setOf(clash.id), two.store.link.first()?.unreadable)
        two.session.calls.clear()
        assertEquals(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 0, unreadable = 1), two.sync.sync())
        assertEquals(listOf("head", "evening"), two.session.calls)
        assertEquals(1, server.eveningRow(round, clash.id)?.version)
        assertNull(two.evenings.evening(clash.id).first())
        // Und nach einem Neustart weiß das Gerät das noch.
        assertEquals(setOf(clash.id), two.restarted().store.link.first()?.unreadable)
    }

    @Test
    fun `Das bin ich und ein Gast ueberstehen Datenbank und Neustart`() = runBlocking {
        val (one, two) = round()
        two.connection.claim(seating[1], "Zwei")
        one.players.save(io.github.q0ochen.schafkopf.domain.model.Player(PlayerId("gast"), "Wirt", colorIndex = 4, archived = true))
        one.synced()
        two.synced()

        val again = two.restarted()
        assertEquals(seating[1], again.store.link.first()?.playerId)
        val bases = again.store.playerBases()
        assertEquals("konto-2", bases.getValue(seating[1]).owner)
        assertTrue(bases.getValue(seating[1]).nameLocked)
        assertNull(bases.getValue(PlayerId("gast")).owner)
        assertEquals(listOf("Platz 1", "Zwei", "Platz 3", "Platz 4", "Wirt"), again.players.players.first().map { it.name })
        assertSettled(one, again)
    }
}
