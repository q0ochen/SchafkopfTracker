/*
 * Texte und Zahlen der Statistik, die der Bildschirm und das Bild zum Teilen gemeinsam haben (docs/ARCHITEKTUR.md,
 * 4.4): die Auswahl oben, die Zusammenfassung, der Kontostand und die Linien des Vermögensverlaufs.
 */
import type { Evening, PlayerId } from "../core/model";
import type { Statistics } from "../core/stats/StatisticEngine";
import type { ChartSeries } from "./charts/BalanceChart";
import { formatAmount, formatDate, plural } from "./format";

/** Was ausgewertet ist: „Abend vom ‹Datum› · ‹Ort›“ oder, ohne Abend, „Alle Abende (ewige Tabelle)“. */
export function statisticsTitle(evening: Evening | null): string {
  if (evening === null) return "Alle Abende (ewige Tabelle)";
  const location = evening.location.trim();
  return `Abend vom ${formatDate(evening.startedAt)}${location !== "" ? ` · ${location}` : ""}`;
}

/** Zum Beispiel „3 Abende · 25 Spiele · Umsatz 12,30 €“; für einen einzelnen Abend ohne die Zahl der Abende. */
export function statisticsSummary(stats: Statistics, single: boolean, asMoney: boolean): string {
  const evenings = single ? "" : `${plural(stats.eveningCount, "Abend", "Abende")} · `;
  return `${evenings}${plural(stats.gameCount, "Spiel", "Spiele")} · Umsatz ${formatAmount(stats.finance.turnover, asMoney)}`;
}

/** Eine Zeile des Kontostands. `wonRate` ist `null`, wenn der Spieler kein Spiel gespielt hat. */
export interface AccountRow {
  readonly player: PlayerId;
  readonly rank: number;
  readonly balance: number;
  readonly games: number;
  readonly wonRate: number | null;
}

/** Der Kontostand, bester Platz zuerst. */
export function accountRows(stats: Statistics): AccountRow[] {
  return [...stats.finance.standings]
    .sort((a, b) => a.rank - b.rank)
    .map((standing) => {
      const stat = stats.history.playerStats.find((it) => it.player === standing.player) ?? null;
      const games = stat?.games ?? 0;
      return {
        player: standing.player,
        rank: standing.rank,
        balance: standing.balance,
        games,
        wonRate: stat !== null && games > 0 ? stat.gamesWon / games : null,
      };
    });
}

/** Die Linien des Vermögensverlaufs: je Spieler in der Reihenfolge der Statistik, mit Name und Farbe. */
export function balanceSeriesOf(
  stats: Statistics,
  names: ReadonlyMap<PlayerId, string>,
  colors: ReadonlyMap<PlayerId, number>,
): ChartSeries[] {
  return stats.players.map((id) => ({
    id,
    name: names.get(id) ?? "?",
    colorIndex: colors.get(id) ?? 0,
    values: stats.finance.balanceSeries.get(id) ?? [0],
  }));
}
