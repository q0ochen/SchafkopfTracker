package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/* Gemeinsames Werkzeug für die Tests der gemeinsamen Runde: eine stellbare Uhr und nachgestellte Geräte. */

internal val P1 = PlayerId("spieler-1")
internal val P2 = PlayerId("spieler-2")
internal val P3 = PlayerId("spieler-3")
internal val P4 = PlayerId("spieler-4")
internal val SEATING = listOf(P1, P2, P3, P4)

/** Der Abend der Runde am 12.12.2026, 19 Uhr. */
internal val START: Instant = Instant.parse("2026-12-12T18:00:00Z")

/** Die vorbelegten Spieler, wie die App sie beim ersten Start anlegt. */
internal val PLACEHOLDERS: List<Player> =
    List(4) { index -> Player(PlayerId("spieler-${index + 1}"), "Spieler ${index + 1}", colorIndex = index) }

/** Eine Uhr, die nur läuft, wenn der Test sie stellt. */
internal class TestClock(var now: Instant = START) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

/** Ein Rufspiel, [seconds] Sekunden nach Beginn des Abends aufgeschrieben. */
internal fun game(
    id: String,
    seconds: Long,
    declarer: PlayerId = P1,
    partner: PlayerId = P2,
    won: Boolean = true,
    laufende: Int = 0,
    note: String = "",
): GameEntry = GameEntry(
    id = EntryId(id),
    recordedAt = START.plusSeconds(seconds),
    type = GameType.RUFSPIEL,
    declarer = declarer,
    partner = partner,
    declarerWon = won,
    laufende = laufende,
    note = note,
)

internal fun evening(vararg entries: EveningEntry, id: String = "abend"): Evening = Evening(
    id = EveningId(id),
    startedAt = START,
    seating = SEATING,
    firstDealer = P1,
    rules = RuleSet.standard(),
    entries = entries.toList(),
)

internal fun Evening.ids(): List<String> = entries.map { it.id.value }

/** Ein Server-Zugang, in den sich ein Test einklinken kann: [beforeSave] läuft vor jedem Hochladen eines Abends. */
internal class HookedApi(private val inner: RoundApi) : RoundApi by inner {
    var beforeSave: (suspend () -> Unit)? = null

    override suspend fun saveEvening(round: RoundId, evening: Evening, expectedVersion: Int, deleted: Boolean): SavedEvening? {
        val hook = beforeSave
        beforeSave = null
        hook?.invoke()
        return inner.saveEvening(round, evening, expectedVersion, deleted)
    }
}

/** Ein nachgestelltes Handy: eigene Daten, eigener Zugang zum Server, eigener Abgleich. */
internal class Device(server: InMemoryRoundServer, val userId: String, private val clock: Clock) {
    val session = server.sessionFor(userId)
    val api = HookedApi(session)
    val players = InMemoryPlayerRepository()
    val evenings = InMemoryEveningRepository()
    val settings = InMemorySettingsRepository()
    val store = InMemoryRoundStore()
    private var counter = 0
    val ids = IdGenerator { "$userId-${++counter}" }
    val editor = EveningEditor(evenings, clock, ids)
    val sync = RoundSync(api, store, players, evenings, settings, editor)
    val connection = RoundConnection(api, store, players, evenings, sync, ids)

    suspend fun link(): RoundLink? = store.link.first()

    suspend fun roundId(): RoundId = checkNotNull(link()).roundId

    suspend fun evening(id: EveningId): Evening? = evenings.evening(id).first()

    suspend fun allEvenings(): List<Evening> = evenings.evenings.first().sortedBy { it.id.value }

    suspend fun allPlayers(): List<Player> = players.players.first()

    /** Legt die vorbelegten Spieler an, wie die App beim ersten Start. */
    suspend fun firstStart() {
        PLACEHOLDERS.forEach { players.save(it) }
    }

    suspend fun startEvening(id: String, seating: List<PlayerId> = SEATING): EveningId {
        val evening = Evening(
            id = EveningId(id),
            startedAt = clock.instant(),
            seating = seating,
            firstDealer = seating.first(),
            rules = settings.settings.first().defaultRules,
        )
        evenings.save(evening)
        return evening.id
    }

    /** Trägt ein Rufspiel ein, mit Kennung und Zeit wie in der App. */
    suspend fun addGame(evening: EveningId, won: Boolean = true, laufende: Int = 0): EntryId {
        val id = editor.newEntryId()
        editor.update(evening) {
            it.append(
                GameEntry(
                    id = id,
                    recordedAt = editor.now(),
                    type = GameType.RUFSPIEL,
                    declarer = it.seating[0],
                    partner = it.seating[1],
                    declarerWon = won,
                    laufende = laufende,
                ),
            )
        }
        return id
    }

    /** Ein Abgleich, der durchgelaufen sein muss. */
    suspend fun synced(): SyncOutcome.Synced {
        val outcome = sync.sync()
        check(outcome is SyncOutcome.Synced) { "Abgleich von $userId: $outcome" }
        return outcome
    }
}
