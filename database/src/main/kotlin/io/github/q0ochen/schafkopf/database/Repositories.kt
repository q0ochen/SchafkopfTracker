package io.github.q0ochen.schafkopf.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Öffnet die Datenbank der App (eine Datei im privaten Speicher der App). */
fun openSchafkopfDatabase(context: Context): SchafkopfDatabase =
    Room.databaseBuilder(context.applicationContext, SchafkopfDatabase::class.java, SchafkopfDatabase.FILE_NAME).build()

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
        dao.save(
            evening = evening.toEntity(rulesCodec),
            entries = evening.entries.mapIndexed { position, entry -> entry.toEntity(evening.id.value, position) },
        )
    }

    override suspend fun delete(id: EveningId) {
        dao.delete(id.value)
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
