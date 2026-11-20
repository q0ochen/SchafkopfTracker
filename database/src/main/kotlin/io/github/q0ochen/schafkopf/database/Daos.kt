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

    /** 1, wenn es den Abend gibt, sonst 0. */
    @Query("SELECT COUNT(*) FROM evenings WHERE id = :id")
    abstract suspend fun exists(id: String): Int

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

@Dao
interface SettingsDao {
    /** Die Einstellungen als Text; `null`, solange nie etwas gespeichert wurde. Meldet sich bei jeder Änderung neu. */
    @Query("SELECT json FROM settings WHERE id = 0")
    fun observe(): Flow<String?>

    @Query("SELECT json FROM settings WHERE id = 0")
    suspend fun current(): String?

    @Upsert
    suspend fun upsert(settings: SettingsEntity)
}

/** Was sich das Gerät zum Abgleich mit der gemeinsamen Runde merkt. */
@Dao
interface RoundDao {
    /** Die verbundene Runde als Text; `null`, solange keine verbunden ist. Meldet sich bei jeder Änderung neu. */
    @Query("SELECT json FROM round_link WHERE id = 0")
    fun observeLink(): Flow<String?>

    @Upsert
    suspend fun upsertLink(link: RoundLinkEntity)

    @Query("DELETE FROM round_link")
    suspend fun deleteLink()

    @Query("SELECT * FROM evening_bases")
    suspend fun eveningBases(): List<EveningBaseEntity>

    @Upsert
    suspend fun upsertEveningBase(base: EveningBaseEntity)

    @Query("DELETE FROM evening_bases WHERE evening_id = :eveningId")
    suspend fun deleteEveningBase(eveningId: String)

    @Query("DELETE FROM evening_bases")
    suspend fun deleteEveningBases()

    @Query("SELECT * FROM player_bases")
    suspend fun playerBases(): List<PlayerBaseEntity>

    @Upsert
    suspend fun upsertPlayerBase(base: PlayerBaseEntity)

    @Query("DELETE FROM player_bases WHERE id = :id")
    suspend fun deletePlayerBase(id: String)

    @Query("DELETE FROM player_bases")
    suspend fun deletePlayerBases()
}

@Database(
    entities = [
        PlayerEntity::class,
        EveningEntity::class,
        EntryEntity::class,
        SettingsEntity::class,
        RoundLinkEntity::class,
        EveningBaseEntity::class,
        PlayerBaseEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SchafkopfDatabase : RoomDatabase() {
    abstract fun playerDao(): PlayerDao
    abstract fun eveningDao(): EveningDao
    abstract fun settingsDao(): SettingsDao
    abstract fun roundDao(): RoundDao

    companion object {
        const val FILE_NAME = "schafkopf.db"
    }
}
