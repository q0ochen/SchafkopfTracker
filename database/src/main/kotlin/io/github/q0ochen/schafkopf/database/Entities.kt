package io.github.q0ochen.schafkopf.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/*
 * Tabellen der Datenbank. Gespeichert werden nur Tatsachen; Beträge und Salden rechnet die Fachlogik
 * bei jedem Lesen neu aus. Zeitpunkte sind Millisekunden seit 1970 (UTC), Kennungen sind Text.
 */

/** Ein Spieler. `sort_order` hält die Reihenfolge fest, in der die Spieler angelegt wurden. */
@Entity(tableName = "players")
data class PlayerEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    val archived: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

/**
 * Ein Abend. Die vier Plätze stehen im Uhrzeigersinn; `rules_json` ist die Kopie des Regelwerks,
 * mit dem der Abend gespielt wurde.
 */
@Entity(tableName = "evenings")
data class EveningEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    val location: String,
    @ColumnInfo(name = "seat_1") val seat1: String,
    @ColumnInfo(name = "seat_2") val seat2: String,
    @ColumnInfo(name = "seat_3") val seat3: String,
    @ColumnInfo(name = "seat_4") val seat4: String,
    @ColumnInfo(name = "first_dealer") val firstDealer: String,
    @ColumnInfo(name = "rules_json") val rulesJson: String,
)

/**
 * Ein Eintrag eines Abends. `kind` sagt, welche Art Eintrag es ist; nur die dazu passenden Spalten sind gefüllt.
 * `position` ist die Reihenfolge innerhalb des Abends.
 */
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(
            entity = EveningEntity::class,
            parentColumns = ["id"],
            childColumns = ["evening_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("evening_id")],
)
data class EntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "evening_id") val eveningId: String,
    val position: Int,
    val kind: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,

    // Gespieltes Spiel
    @ColumnInfo(name = "game_type") val gameType: String? = null,
    val suit: String? = null,
    val declarer: String? = null,
    val partner: String? = null,
    @ColumnInfo(name = "declarer_won") val declarerWon: Boolean? = null,
    val level: String? = null,
    val laufende: Int? = null,
    val announcement: String? = null,
    /** Kennungen der Spieler, die gelegt haben, durch Komma getrennt. */
    @ColumnInfo(name = "gelegt_by") val gelegtBy: String? = null,
    val kontra: String? = null,
    @ColumnInfo(name = "kontra_by") val kontraBy: String? = null,
    @ColumnInfo(name = "retour_by") val retourBy: String? = null,
    @ColumnInfo(name = "bock_override") val bockOverride: String? = null,
    @ColumnInfo(name = "declarer_eyes") val declarerEyes: Int? = null,
    val note: String? = null,

    // Bockrunde ausgelöst
    @ColumnInfo(name = "bock_reason") val bockReason: String? = null,
    @ColumnInfo(name = "bock_player") val bockPlayer: String? = null,
    @ColumnInfo(name = "hand_thrown_in") val handThrownIn: Boolean? = null,

    // Freie Buchung: „Kennung=Cent“ je Spieler, durch Komma getrennt
    @ColumnInfo(name = "manual_amounts") val manualAmounts: String? = null,
) {
    companion object {
        const val KIND_GAME = "game"
        const val KIND_THROWN_IN = "thrown_in"
        const val KIND_BOCK = "bock"
        const val KIND_MANUAL = "manual"
    }
}

/**
 * Die Einstellungen der App als eine einzige Zeile (Kennung 0). Gespeichert als JSON-Text in der Schreibweise der
 * früheren Datei `settings.json`; so braucht eine neue Einstellung keine neue Spalte.
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val json: String,
) {
    companion object {
        const val SINGLE_ROW = 0
    }
}

/** Ein Abend mit allen seinen Einträgen, in einer Abfrage geladen. */
data class EveningWithEntries(
    @Embedded val evening: EveningEntity,
    @Relation(parentColumn = "id", entityColumn = "evening_id") val entries: List<EntryEntity>,
)
