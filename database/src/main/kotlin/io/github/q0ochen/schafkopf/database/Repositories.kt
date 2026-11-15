package io.github.q0ochen.schafkopf.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
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
