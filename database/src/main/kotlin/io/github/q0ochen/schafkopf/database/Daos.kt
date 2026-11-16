package io.github.q0ochen.schafkopf.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerDao {
    /** Alle Spieler in Anlagereihenfolge; meldet sich bei jeder Änderung neu. */
    @Query("SELECT * FROM players ORDER BY sort_order, id")
    fun observeAll(): Flow<List<PlayerEntity>>

    @Query("SELECT * FROM players WHERE id = :id")
    suspend fun find(id: String): PlayerEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM players")
    suspend fun highestSortOrder(): Int

    @Upsert
    suspend fun upsert(player: PlayerEntity)

    @Query("DELETE FROM players WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
abstract class EveningDao {
    /** Alle Abende mit ihren Einträgen, der neueste zuerst; meldet sich bei jeder Änderung neu. */
    @Transaction
    @Query("SELECT * FROM evenings ORDER BY started_at DESC, id")
    abstract fun observeAll(): Flow<List<EveningWithEntries>>

    @Transaction
    @Query("SELECT * FROM evenings WHERE id = :id")
    abstract fun observe(id: String): Flow<EveningWithEntries?>

    @Upsert
    protected abstract suspend fun upsertEvening(evening: EveningEntity)

    @Query("DELETE FROM entries WHERE evening_id = :eveningId")
    protected abstract suspend fun deleteEntries(eveningId: String)

    @Insert
    protected abstract suspend fun insertEntries(entries: List<EntryEntity>)

    /**
     * Speichert einen Abend samt Einträgen in einer Transaktion: Entweder ist danach alles gespeichert oder nichts.
     */
    @Transaction
    open suspend fun save(evening: EveningEntity, entries: List<EntryEntity>) {
        upsertEvening(evening)
        deleteEntries(evening.id)
        insertEntries(entries)
    }

    /** Löscht den Abend; seine Einträge verschwinden über den Fremdschlüssel mit. */
    @Query("DELETE FROM evenings WHERE id = :id")
    abstract suspend fun delete(id: String)
}

@Database(
    entities = [PlayerEntity::class, EveningEntity::class, EntryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SchafkopfDatabase : RoomDatabase() {
    abstract fun playerDao(): PlayerDao
    abstract fun eveningDao(): EveningDao

    companion object {
        const val FILE_NAME = "schafkopf.db"
    }
}
