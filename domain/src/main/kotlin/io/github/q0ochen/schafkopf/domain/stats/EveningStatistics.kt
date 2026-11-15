package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType

/**
 * Das teuerste Spiel: das gespielte Spiel mit dem höchsten Spielwert.
 *
 * @property value Der Spielwert in Cent: so viel zahlt ein Verlierer an einen Gewinner.
 */
data class ExpensiveGame(val eveningId: EveningId, val entryId: EntryId, val gameNumber: Int, val value: Int)

/**
 * Die Statistik eines Abends.
 *
 * @property playerStats Kennzahlen je Spieler in Sitzreihenfolge.
 * @property gamesPlayed Gespielte Spiele, die abgerechnet werden konnten.
 * @property gameTypeCounts Wie oft jede Spielart gespielt wurde. Jede Spielart ist enthalten, notfalls mit 0.
 * @property bockGamesCount Gespielte Spiele, die als Bockspiel doppelt (oder mehrfach) zählten.
 * @property bockTriggerCount Wie oft eine Bockrunde ausgelöst wurde. Gezählt wird jeder solche Eintrag, auch wenn er
 *   nach dem Regelwerk des Abends ohne Wirkung blieb (Bockrunde abgeschaltet oder Regel „ignorieren“).
 * @property thrownInCount Wie oft das Blatt zusammengeworfen wurde, weil alle „weiter“ gesagt haben.
 * @property leadChanges Führungswechsel: wie oft nach einem abgerechneten Spiel ein anderer Spieler alleiniger Erster
 *   war als der letzte alleinige Erste davor. Der erste alleinige Erste des Abends ist noch kein Wechsel.
 *   Freie Buchungen zählen zum nächsten abgerechneten Spiel, hinter dem letzten Spiel zu diesem.
 * @property mostExpensiveGame Das Spiel mit dem höchsten Spielwert, bei Gleichstand das frühere; `null` ohne Spiel.
 * @property turnover Umsatz: die Summe aller positiven Beträge aller Einträge, auch der freien Buchungen.
 */
data class EveningStats(
    val eveningId: EveningId,
    val playerStats: List<PlayerStats>,
    val gamesPlayed: Int,
    val gameTypeCounts: Map<GameType, Int>,
    val bockGamesCount: Int,
    val bockTriggerCount: Int,
    val thrownInCount: Int,
    val leadChanges: Int,
    val mostExpensiveGame: ExpensiveGame?,
    val turnover: Int,
) {
    /** Die Kennzahlen eines Spielers; `null`, wenn er an diesem Abend nicht am Tisch saß. */
    fun statsFor(player: PlayerId): PlayerStats? = playerStats.firstOrNull { it.player == player }
}

/**
 * Berechnet die Statistik eines Abends aus dem durchgerechneten Abend. Reine Funktion; nichts wird gespeichert.
 *
 * Gezählt werden gespielte Spiele, die abgerechnet werden konnten. Ein Spiel mit unbekanntem Ansager oder fehlendem
 * Mitspieler bucht nichts und bleibt außen vor. Ein Abend ohne gezähltes Spiel gilt nicht als gespielter Abend:
 * Alles, was an Spielen hängt, ist dann 0. Sichtbar bleibt, was trotzdem eingetragen wurde: der Saldo aus freien
 * Buchungen, zusammengeworfene Blätter und ausgelöste Bockrunden.
 */
object EveningStatistics {

    fun compute(state: EveningState): EveningStats = compute(EveningDigest(state))

    internal fun compute(digest: EveningDigest): EveningStats {
        val table = TableAccumulator()
        table.add(digest)
        return EveningStats(
            eveningId = digest.eveningId,
            playerStats = digest.seating.map { player ->
                val accumulator = PlayerAccumulator(player)
                accumulator.add(digest)
                accumulator.build()
            },
            gamesPlayed = table.gameCount,
            gameTypeCounts = table.gameTypeCounts,
            bockGamesCount = table.bockGamesCount,
            bockTriggerCount = table.bockTriggerCount,
            thrownInCount = table.thrownInCount,
            leadChanges = table.leadChanges,
            mostExpensiveGame = table.mostExpensiveGame,
            turnover = table.turnover,
        )
    }
}
