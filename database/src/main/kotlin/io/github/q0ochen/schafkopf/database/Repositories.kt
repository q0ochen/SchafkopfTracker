package io.github.q0ochen.schafkopf.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningNotStorableException
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import io.github.q0ochen.schafkopf.domain.round.EveningBase
import io.github.q0ochen.schafkopf.domain.round.RemotePlayer
import io.github.q0ochen.schafkopf.domain.round.RoundLink
import io.github.q0ochen.schafkopf.domain.round.RoundStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Öffnet die Datenbank der App (eine Datei im privaten Speicher der App). */
fun openSchafkopfDatabase(context: Context): SchafkopfDatabase =
    Room.databaseBuilder(context.applicationContext, SchafkopfDatabase::class.java, SchafkopfDatabase.FILE_NAME)
        .addMigrations(*SCHAFKOPF_MIGRATIONS)
        .build()

/**
 * Version 2 (gemeinsame Runde): drei neue Tabellen für das, was sich das Gerät zum Abgleich merkt. An den
 * vorhandenen Tabellen ändert sich nichts; Spieler, Abende und Einstellungen bleiben, wie sie sind.
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `round_link` (`id` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `evening_bases` " +
                "(`evening_id` TEXT NOT NULL, `version` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`evening_id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `player_bases` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color_index` INTEGER NOT NULL, " +
                "`archived` INTEGER NOT NULL, `position` INTEGER NOT NULL, `owner` TEXT, `name_locked` INTEGER NOT NULL, " +
                "`rev` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

/** Alle Umzüge zwischen den Versionen der Datenbank, für [openSchafkopfDatabase] und für Tests. */
val SCHAFKOPF_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

/** Spieler in der Room-Datenbank. */
class RoomPlayerRepository(private val database: SchafkopfDatabase) : PlayerRepository {
    private val dao = database.playerDao()

    override val players: Flow<List<Player>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override suspend fun save(player: Player) {
        database.withTransaction {
            // Ein vorhandener Spieler behält seinen Platz in der Reihenfolge, ein neuer kommt ans Ende.
            val sortOrder = dao.find(player.id.value)?.sortOrder ?: (dao.highestSortOrder() + 1)
            dao.upsert(PlayerEntity(player.id.value, player.name, player.colorIndex, player.archived, sortOrder))
        }
    }

    override suspend fun delete(id: PlayerId) {
        dao.delete(id.value)
    }
}

/**
 * Abende in der Room-Datenbank. Jede Änderung meldet sich über die Flows von selbst bei allen Bildschirmen.
 */
class RoomEveningRepository(
    database: SchafkopfDatabase,
    private val rulesCodec: RulesCodec,
) : EveningRepository {
    private val dao = database.eveningDao()

    override val evenings: Flow<List<Evening>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain(rulesCodec) } }.distinctUntilChanged()

    override fun evening(id: EveningId): Flow<Evening?> =
        dao.observe(id.value).map { row -> row?.toDomain(rulesCodec) }.distinctUntilChanged()

    override suspend fun save(evening: Evening) {
        try {
            dao.save(
                evening = evening.toEntity(rulesCodec),
                entries = evening.entries.mapIndexed { position, entry -> entry.toEntity(evening.id.value, position) },
            )
        } catch (e: SQLiteConstraintException) {
            // Die Kennung eines Eintrags gibt es schon in einem anderen Abend, oder sie kommt im Abend doppelt vor.
            // Die Transaktion wurde zurückgenommen; gespeichert ist nichts.
            throw EveningNotStorableException("Der Abend ${evening.id.value} lässt sich nicht speichern.", e)
        }
    }

    override suspend fun delete(id: EveningId) {
        dao.delete(id.value)
    }
}

/**
 * Wandelt die verbundene Runde und die Basen der Abende in Text für die Datenbank und zurück.
 * Die Umsetzung liegt im Modul `data` (Abende in derselben Darstellung wie in der Sicherung).
 */
interface RoundCodec {
    fun encodeLink(link: RoundLink): String

    /** @return `null`, wenn sich der Text nicht lesen lässt. */
    fun decodeLink(text: String): RoundLink?

    fun encodeEvening(evening: Evening): String

    /** @return `null`, wenn sich der Text nicht lesen lässt. */
    fun decodeEvening(text: String): Evening?
}

/**
 * Was sich das Gerät zum Abgleich mit der gemeinsamen Runde merkt, in der Room-Datenbank.
 *
 * Die Basen werden beim ersten Zugriff einmal gelesen und danach im Speicher mitgeführt: Der Abgleich fragt sie
 * bei jedem Lauf vollständig ab, und geschrieben werden sie nur hier. Eine Basis, die sich nicht mehr lesen lässt,
 * gilt als nicht vorhanden (dann wird beim nächsten Abgleich ohne Basis zusammengeführt; verloren geht dabei nichts).
 */
class RoomRoundStore(
    private val database: SchafkopfDatabase,
    private val codec: RoundCodec,
) : RoundStore {
    private val dao = database.roundDao()
    private val mutex = Mutex()
    private var evenings: MutableMap<EveningId, EveningBase>? = null
    private var players: MutableMap<PlayerId, RemotePlayer>? = null

    override val link: Flow<RoundLink?> =
        dao.observeLink().map { text -> text?.let(codec::decodeLink) }.distinctUntilChanged()

    override suspend fun setLink(link: RoundLink?) {
        if (link == null) dao.deleteLink() else dao.upsertLink(RoundLinkEntity(json = codec.encodeLink(link)))
    }

    override suspend fun eveningBases(): Map<EveningId, EveningBase> = mutex.withLock { HashMap(loadedEvenings()) }

    override suspend fun setEveningBase(id: EveningId, base: EveningBase?): Unit = mutex.withLock {
        val cache = loadedEvenings()
        if (base == null) {
            dao.deleteEveningBase(id.value)
            cache.remove(id)
        } else {
            dao.upsertEveningBase(EveningBaseEntity(id.value, base.version, codec.encodeEvening(base.evening)))
            cache[id] = base
        }
    }

    override suspend fun playerBases(): Map<PlayerId, RemotePlayer> = mutex.withLock { HashMap(loadedPlayers()) }

    override suspend fun setPlayerBase(id: PlayerId, base: RemotePlayer?): Unit = mutex.withLock {
        val cache = loadedPlayers()
        if (base == null) {
            dao.deletePlayerBase(id.value)
            cache.remove(id)
        } else {
            dao.upsertPlayerBase(
                PlayerBaseEntity(id.value, base.name, base.colorIndex, base.archived, base.position, base.owner, base.nameLocked, base.rev),
            )
            cache[id] = base.copy(id = id, deleted = false)
        }
    }

    override suspend fun clearBases(): Unit = mutex.withLock {
        database.withTransaction {
            dao.deleteEveningBases()
            dao.deletePlayerBases()
        }
        evenings = HashMap()
        players = HashMap()
    }

    private suspend fun loadedEvenings(): MutableMap<EveningId, EveningBase> = evenings ?: HashMap<EveningId, EveningBase>().also { map ->
        for (row in dao.eveningBases()) {
            val evening = codec.decodeEvening(row.json) ?: continue
            map[EveningId(row.eveningId)] = EveningBase(row.version, evening)
        }
        evenings = map
    }

    private suspend fun loadedPlayers(): MutableMap<PlayerId, RemotePlayer> = players ?: HashMap<PlayerId, RemotePlayer>().also { map ->
        for (row in dao.playerBases()) {
            map[PlayerId(row.id)] =
                RemotePlayer(PlayerId(row.id), row.name, row.colorIndex, row.archived, row.position, row.owner, row.nameLocked, rev = row.rev)
        }
        players = map
    }
}

/**
 * Wandelt die Einstellungen in Text für die Tabelle `settings` und zurück.
 * Die Umsetzung liegt im Modul `data` (dieselbe Darstellung wie die frühere Datei `settings.json`).
 */
interface SettingsCodec {
    fun encode(settings: AppSettings): String

    /** @return `null`, wenn sich der Text nicht lesen lässt. */
    fun decode(text: String): AppSettings?
}

/**
 * Die Einstellungen in der Room-Datenbank. Solange nichts gespeichert ist oder sich der Text nicht lesen lässt,
 * gelten die Standardwerte; abstürzen darf die App deswegen nie.
 */
class RoomSettingsRepository(
    private val database: SchafkopfDatabase,
    private val codec: SettingsCodec,
) : SettingsRepository {
    private val dao = database.settingsDao()

    override val settings: Flow<AppSettings> =
        dao.observe().map { text -> text?.let(codec::decode) ?: AppSettings() }.distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        // In einer Transaktion: Zwei Änderungen kurz nacheinander gehen nicht verloren.
        database.withTransaction {
            val current = dao.current()?.let(codec::decode) ?: AppSettings()
            dao.upsert(SettingsEntity(json = codec.encode(transform(current))))
        }
    }
}

/**
 * Was [importMissing] übernommen hat.
 *
 * @property settings `true`, wenn die Einstellungen übernommen wurden (nur solange die Datenbank keine hatte).
 */
data class ImportedCounts(val players: Int, val evenings: Int, val settings: Boolean)

/**
 * Übernimmt Spieler und Abende, die es in der Datenbank noch nicht gibt (gleiche Kennung), dazu die Einstellungen,
 * solange die Datenbank noch keine hat. Vorhandenes bleibt unverändert. Alles in einer Transaktion: Entweder ist
 * danach alles übernommen oder nichts; deshalb lässt sich die Übernahme gefahrlos wiederholen.
 */
suspend fun SchafkopfDatabase.importMissing(
    players: List<Player>,
    evenings: List<Evening>,
    settings: AppSettings,
    rulesCodec: RulesCodec,
    settingsCodec: SettingsCodec,
): ImportedCounts = withTransaction {
    val playerRepository = RoomPlayerRepository(this)
    val eveningRepository = RoomEveningRepository(this, rulesCodec)
    val newPlayers = players.filter { playerDao().find(it.id.value) == null }
    val newEvenings = evenings.filter { eveningDao().exists(it.id.value) == 0 }
    newPlayers.forEach { playerRepository.save(it) }
    newEvenings.forEach { eveningRepository.save(it) }
    val takeSettings = settingsDao().current() == null
    if (takeSettings) settingsDao().upsert(SettingsEntity(json = settingsCodec.encode(settings)))
    ImportedCounts(newPlayers.size, newEvenings.size, takeSettings)
}
