package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.evening.Ranking
import io.github.q0ochen.schafkopf.domain.model.PlayerId

/**
 * Der Verlauf eines Abends als Zahlenreihen, zum Beispiel für ein Diagramm.
 *
 * Jede Reihe hat `gamesPlayed + 1` Punkte: Index 0 ist der Start, Index n der Stand nach dem n-ten gespielten Spiel.
 * Freie Buchungen zählen zum nächsten gespielten Spiel. Stehen sie hinter dem letzten Spiel, zählen sie zum letzten
 * Punkt. Der letzte Punkt ist deshalb immer der aktuelle Stand des Abends; ohne ein einziges Spiel steht eine
 * freie Buchung im einzigen Punkt der Reihe.
 *
 * Die Punkte folgen den Spielnummern des Abends. Auch ein Spiel, das sich nicht abrechnen ließ, trägt eine Nummer
 * und hat deshalb hier seinen Punkt (der Stand bleibt dort stehen). Die Statistik ([StatisticEngine]) zählt solche
 * Spiele dagegen nicht mit; ihr Verlauf hat dann entsprechend weniger Punkte.
 */
object Progression {

    /** Saldo je Spieler in Cent, in Sitzreihenfolge. */
    fun balanceSeries(state: EveningState): Map<PlayerId, List<Int>> {
        val points = balancePoints(state)
        return state.evening.seating.associateWith { player -> points.map { it[player] ?: 0 } }
    }

    /** Platz je Spieler, in Sitzreihenfolge. Bei Gleichstand teilen sich Spieler einen Platz. */
    fun rankSeries(state: EveningState): Map<PlayerId, List<Int>> {
        val points = balancePoints(state).map { Ranking.ranks(it) }
        return state.evening.seating.associateWith { player -> points.map { it[player] ?: 1 } }
    }

    /** Die Salden aller Spieler am Start und nach jedem gespielten Spiel. */
    internal fun balancePoints(state: EveningState): List<Map<PlayerId, Int>> {
        val points = ArrayList<Map<PlayerId, Int>>(state.gamesPlayed + 1)
        points += state.evening.seating.associateWith { 0 }
        for (result in state.results) {
            if (result.gameNumber != null) points += result.balances
        }
        points[points.lastIndex] = state.balances
        return points
    }
}
