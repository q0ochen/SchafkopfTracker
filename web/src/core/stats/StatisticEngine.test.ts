// Statistik der beiden von Hand durchgerechneten Abende (testing/roundEvenings.ts) und die Abfragen auf den
// Ergebnissen. Alles Weitere prüft golden/kotlinGolden.test.ts gegen den Kotlin-Kern.
import { describe, expect, it } from "vitest";
import {
  EveningCalculator,
  EveningStatistics,
  gameModesOf,
  gelegtOf,
  HistoryStatistics,
  isPair,
  pairOf,
  partnerRecord,
  recordOf,
  standingOf,
  StatisticEngine,
  statsFor,
} from "../index";
import { firstEvening, secondEvening } from "../testing/roundEvenings";

describe("StatisticEngine", () => {
  it("ewige Tabelle über beide Abende: E +880, C +310, A +200, B +110, D −1500", () => {
    // Die Reihenfolge der übergebenen Liste spielt keine Rolle, derselbe Abend doppelt zählt einmal.
    const statistics = StatisticEngine.forEvenings([secondEvening, firstEvening, secondEvening]);
    expect(statistics.players).toEqual(["E", "C", "A", "B", "D"]);
    expect(statistics.finance.standings.map((standing) => standing.balance)).toEqual([880, 310, 200, 110, -1500]);
    expect(statistics.eveningCount).toBe(2);
    expect(statistics.gameCount).toBe(20);
    expect(statistics.finance.eveningStarts).toEqual([0, 14]);
    expect(statistics.finance.eveningIds).toEqual(["runde-1", "runde-2"]);
    expect(statistics.finance.balanceSeries.get("D")?.at(-1)).toBe(-1500);
    // D saß am zweiten Abend nicht am Tisch: Seine Linie bleibt dort waagerecht.
    expect(new Set(statistics.finance.balanceSeries.get("D")?.slice(14))).toEqual(new Set([-1500]));
  });

  it("ein einzelner Abend: Spieler in Sitzreihenfolge, die Spielerliste der history nach Saldo", () => {
    const statistics = StatisticEngine.forEvening(firstEvening);
    expect(statistics.players).toEqual(["A", "B", "C", "D"]);
    expect(statistics.history.playerStats.map((stats) => stats.player)).toEqual(["C", "A", "B", "D"]);
    expect(statistics.history).toEqual(HistoryStatistics.compute([firstEvening]));
    const eveningStats = EveningStatistics.compute(EveningCalculator.compute(firstEvening));
    expect(eveningStats.gamesPlayed).toBe(14);
    expect(eveningStats.mostExpensiveGame).toEqual({ eveningId: "runde-1", entryId: "r06", gameNumber: 5, value: 280 });
  });

  it("die Abfragen finden den Spieler oder liefern null", () => {
    const statistics = StatisticEngine.forEvenings([firstEvening, secondEvening]);
    expect(standingOf(statistics.finance, "E")).toEqual({ player: "E", balance: 880, rank: 1 });
    expect(standingOf(statistics.finance, "X")).toBeNull();
    expect(gameModesOf(statistics, "A")?.atTable.get("RUFSPIEL")?.games).toBeGreaterThan(0);
    expect(gameModesOf(statistics, "X")).toBeNull();
    expect(gelegtOf(statistics, "D")?.player).toBe("D");
    expect(recordOf(statistics.bock, "B")?.player).toBe("B");
    expect(recordOf(statistics.solo, "X")).toBeNull();
    const pair = statistics.partners.pairs[0];
    expect(pairOf(statistics.partners, pair.second, pair.first)).toBe(pair);
    expect(isPair(pair, pair.second, pair.first)).toBe(true);
    expect(statsFor(statistics.history, "C")?.balance).toBe(310);
    expect(statsFor(statistics.history, "X")).toBeNull();
    expect(partnerRecord(statistics.history, "B", "A")?.first).toBe("A");
  });

  it("abgeleitete Felder sind fertig berechnet", () => {
    const statistics = StatisticEngine.forEvening(firstEvening);
    for (const record of statistics.gelegt) {
      expect(record.lost).toBe(record.count - record.won);
      expect(record.winRate).toBe(record.count === 0 ? null : record.won / record.count);
      expect(record.shouldLeaveIt).toBe(record.badge === "BRANDSTIFTER");
    }
    for (const stats of statistics.history.playerStats) {
      expect(stats.gamesLost).toBe(stats.games - stats.gamesWon);
      expect(stats.overall).toEqual({ played: stats.games, won: stats.gamesWon, lost: stats.gamesLost, rate: stats.gamesWon / stats.games });
      expect(stats.klopferCount).toBe(stats.klopferGiven.played);
    }
  });
});
