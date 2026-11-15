/*
 * Der Verlauf eines Abends als Zahlenreihen (Kotlin: `domain/stats/Progression.kt`).
 */
import type { EveningState } from "../evening/EveningCalculator";
import { Ranking } from "../evening/EveningCalculator";
import { associateWith } from "../kotlin";
import type { PlayerId } from "../model";

/** Die Salden aller Spieler am Start und nach jedem gespielten Spiel. */
function balancePoints(state: EveningState): ReadonlyMap<PlayerId, number>[] {
  const points: ReadonlyMap<PlayerId, number>[] = [associateWith(state.evening.seating, () => 0)];
  for (const result of state.results) {
    if (result.gameNumber !== null) points.push(result.balances);
  }
  points[points.length - 1] = state.balances;
  return points;
}

/**
 * Der Verlauf eines Abends als Zahlenreihen, zum Beispiel für ein Diagramm.
 *
 * Jede Reihe hat `gamesPlayed + 1` Punkte: Index 0 ist der Start, Index n der Stand nach dem n-ten gespielten Spiel.
 * Freie Buchungen zählen zum nächsten gespielten Spiel. Stehen sie hinter dem letzten Spiel, zählen sie zum letzten
 * Punkt. Der letzte Punkt ist deshalb immer der aktuelle Stand des Abends; ohne ein einziges Spiel steht eine freie
 * Buchung im einzigen Punkt der Reihe.
 *
 * Die Punkte folgen den Spielnummern des Abends. Auch ein Spiel, das sich nicht abrechnen ließ, trägt eine Nummer und
 * hat deshalb hier seinen Punkt (der Stand bleibt dort stehen). Die Statistik (`StatisticEngine`) zählt solche Spiele
 * dagegen nicht mit; ihr Verlauf hat dann entsprechend weniger Punkte.
 */
export const Progression = {
  /** Saldo je Spieler in Cent, in Sitzreihenfolge. */
  balanceSeries(state: EveningState): ReadonlyMap<PlayerId, readonly number[]> {
    const points = balancePoints(state);
    return associateWith(state.evening.seating, (player) => points.map((point) => point.get(player) ?? 0));
  },

  /** Platz je Spieler, in Sitzreihenfolge. Bei Gleichstand teilen sich Spieler einen Platz. */
  rankSeries(state: EveningState): ReadonlyMap<PlayerId, readonly number[]> {
    const points = balancePoints(state).map((point) => Ranking.ranks(point));
    return associateWith(state.evening.seating, (player) => points.map((point) => point.get(player) ?? 1));
  },
} as const;
