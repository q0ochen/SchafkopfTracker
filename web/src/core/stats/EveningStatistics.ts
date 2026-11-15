/*
 * Die Statistik eines Abends (Kotlin: `domain/stats/EveningStatistics.kt`).
 */
import type { EveningState } from "../evening/EveningCalculator";
import type { EntryId, EveningId, GameType, PlayerId } from "../model";
import { EveningDigest, PlayerAccumulator, TableAccumulator } from "./EveningDigest";
import type { PlayerStats } from "./PlayerStats";

/**
 * Das teuerste Spiel: das gespielte Spiel mit dem höchsten Spielwert (Kotlin: `ExpensiveGame`).
 *
 * @property value Der Spielwert in Cent: so viel zahlt ein Verlierer an einen Gewinner.
 */
export interface ExpensiveGame {
  readonly eveningId: EveningId;
  readonly entryId: EntryId;
  readonly gameNumber: number;
  readonly value: number;
}

/**
 * Die Statistik eines Abends (Kotlin: `EveningStats`).
 *
 * @property playerStats Kennzahlen je Spieler in Sitzreihenfolge.
 * @property gamesPlayed Gespielte Spiele, die abgerechnet werden konnten.
 * @property gameTypeCounts Wie oft jede Spielart gespielt wurde. Jede Spielart ist enthalten (Reihenfolge wie
 *   `GAME_TYPES`), notfalls mit 0.
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
export interface EveningStats {
  readonly eveningId: EveningId;
  readonly playerStats: readonly PlayerStats[];
  readonly gamesPlayed: number;
  readonly gameTypeCounts: ReadonlyMap<GameType, number>;
  readonly bockGamesCount: number;
  readonly bockTriggerCount: number;
  readonly thrownInCount: number;
  readonly leadChanges: number;
  readonly mostExpensiveGame: ExpensiveGame | null;
  readonly turnover: number;
}

/**
 * Die Kennzahlen eines Spielers (Kotlin: `EveningStats.statsFor` und `HistoryStats.statsFor`); `null`, wenn er an
 * diesem Abend bzw. an keinem der Abende am Tisch saß.
 */
export function statsFor(stats: { readonly playerStats: readonly PlayerStats[] }, player: PlayerId): PlayerStats | null {
  return stats.playerStats.find((it) => it.player === player) ?? null;
}

/** Die Statistik aus einem schon durchgerechneten Abend (Kotlin: `EveningStatistics.compute(digest)`). */
export function eveningStatsFromDigest(digest: EveningDigest): EveningStats {
  const table = new TableAccumulator();
  table.add(digest);
  return {
    eveningId: digest.eveningId,
    playerStats: digest.seating.map((player) => {
      const accumulator = new PlayerAccumulator(player);
      accumulator.add(digest);
      return accumulator.build();
    }),
    gamesPlayed: table.gameCount,
    gameTypeCounts: table.gameTypeCounts,
    bockGamesCount: table.bockGamesCount,
    bockTriggerCount: table.bockTriggerCount,
    thrownInCount: table.thrownInCount,
    leadChanges: table.leadChanges,
    mostExpensiveGame: table.mostExpensiveGame,
    turnover: table.turnover,
  };
}

/**
 * Berechnet die Statistik eines Abends aus dem durchgerechneten Abend. Reine Funktion; nichts wird gespeichert.
 *
 * Gezählt werden gespielte Spiele, die abgerechnet werden konnten. Ein Spiel mit unbekanntem Ansager oder fehlendem
 * Mitspieler bucht nichts und bleibt außen vor. Ein Abend ohne gezähltes Spiel gilt nicht als gespielter Abend:
 * Alles, was an Spielen hängt, ist dann 0. Sichtbar bleibt, was trotzdem eingetragen wurde: der Saldo aus freien
 * Buchungen, zusammengeworfene Blätter und ausgelöste Bockrunden.
 */
export const EveningStatistics = {
  compute(state: EveningState): EveningStats {
    return eveningStatsFromDigest(new EveningDigest(state));
  },
} as const;
