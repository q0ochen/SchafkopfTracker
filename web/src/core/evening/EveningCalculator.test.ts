// Ein von Hand durchgerechneter Abend (Tabelle in testing/roundEvenings.ts) und die Hilfen zum Abend.
// Alles Weitere prüft golden/kotlinGolden.test.ts gegen den Kotlin-Kern.
import { describe, expect, it } from "vitest";
import type { Evening, ManualEntry } from "../index";
import {
  append,
  EveningCalculator,
  insertAt,
  isRunning,
  Progression,
  remove,
  replace,
  RuleSets,
  SettleUp,
} from "../index";
import { firstEvening, game } from "../testing/roundEvenings";

const plain = (map: ReadonlyMap<string, number>) => Object.fromEntries(map);

describe("EveningCalculator", () => {
  const state = EveningCalculator.compute(firstEvening);

  it("rechnet den Abend wie von Hand: Stand nach jedem Spiel und Endstand", () => {
    const series = Progression.balanceSeries(state);
    expect(series.get("A")).toEqual([0, 10, 30, -50, -30, 250, 310, 70, 320, 280, 340, 360, 260, 240, 310]);
    expect(series.get("B")).toEqual([0, 10, -10, 230, 250, 530, 470, 230, -20, 20, -160, -180, 120, 100, 30]);
    expect(series.get("C")).toEqual([0, -10, 10, -70, -90, 190, 130, 850, 1050, 1090, 1150, 1170, 1070, 1090, 1160]);
    expect(series.get("D")).toEqual([0, -10, -30, -110, -130, -970, -910, -1150, -1350, -1390, -1330, -1350, -1450, -1430, -1500]);
    expect(plain(state.balances)).toEqual({ A: 310, B: 30, C: 1160, D: -1500 });
    expect(plain(state.ranks)).toEqual({ A: 2, B: 3, C: 1, D: 4 });
    expect(state.gamesPlayed).toBe(14);
  });

  it("Geber, Spielnummern und Bockspiele (6 Nixer bei B: die Spiele 6 bis 9 zählen doppelt)", () => {
    const games = state.results.filter((result) => result.gameNumber !== null);
    expect(games.map((result) => result.bockExponent)).toEqual([0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0]);
    // Nach dem zusammengeworfenen Blatt und nach der Bockrunde (Blatt zusammengeworfen) gibt jeweils der Nächste,
    // nach der freien Buchung (zehnter Eintrag) gibt derselbe noch einmal.
    expect(state.results.map((result) => result.dealer).join("")).toBe("ABCDABCDABBCDABCD");
    expect(state.nextDealer).toBe("A");
    // Wenz D, verloren, 2 Laufende, gelegt haben A und D: (50 + 20) × 4 = 280.
    expect(state.results[5].breakdown?.value).toBe(280);
    expect(plain(state.results[5].amounts)).toEqual({ A: 280, B: 280, C: 280, D: -840 });
  });

  it("wer zahlt wem", () => {
    expect(SettleUp.isBalanced(state.balances)).toBe(true);
    expect(SettleUp.transfers(state.balances)).toEqual([
      { from: "D", to: "C", amount: 1160 },
      { from: "D", to: "A", amount: 310 },
      { from: "D", to: "B", amount: 30 },
    ]);
  });

  it("wirft auch mit beschädigten Daten nie und bucht dann nichts", () => {
    const broken: Evening = { ...firstEvening, seating: [], firstDealer: "C" };
    const result = EveningCalculator.compute(broken);
    expect(result.results).toHaveLength(firstEvening.entries.length);
    expect(result.balances.size).toBe(0);
    expect(result.nextDealer).toBe("C");
    expect(result.results[0].issues).toEqual(["RULE_VIOLATION"]);
  });

  it("meldet einen Saldo, der nicht mehr in einen Int passt, und bucht den Eintrag nicht", () => {
    const big = (id: string): ManualEntry => ({
      kind: "manual",
      id,
      recordedAt: "2026-11-15T19:00:00Z",
      amounts: new Map([["A", 2_000_000_000], ["B", -2_000_000_000]]),
      note: "",
    });
    const evening: Evening = { ...firstEvening, entries: [big("m1"), big("m2")] };
    const result = EveningCalculator.compute(evening);
    expect(result.results[1].issues).toEqual(["BALANCE_OUT_OF_RANGE"]);
    expect(plain(result.balances)).toEqual({ A: 2_000_000_000, B: -2_000_000_000, C: 0, D: 0 });
  });
});

describe("Hilfen zum Abend", () => {
  const empty: Evening = { ...firstEvening, finishedAt: null, entries: [], rules: RuleSets.sauspiel() };
  const one = game("x1", "2026-11-15T19:00:00Z", { type: "WENZ", declarer: "A", declarerWon: true });
  const two = game("x2", "2026-11-15T19:05:00Z", { type: "GEIER", declarer: "B", declarerWon: false });

  it("anhängen, ersetzen, entfernen und an einer Stelle einfügen, ohne das Original zu verändern", () => {
    const withOne = append(empty, one);
    expect(withOne.entries).toEqual([one]);
    expect(empty.entries).toEqual([]);
    const replaced = replace(append(withOne, two), { ...one, declarerWon: false });
    expect(replaced.entries.map((entry) => entry.id)).toEqual(["x1", "x2"]);
    expect(replaced.entries[0]).toMatchObject({ declarerWon: false });
    expect(remove(replaced, "x1").entries).toEqual([two]);
    // Eine Stelle außerhalb der Liste wird auf Anfang bzw. Ende begrenzt (wie Kotlins coerceIn).
    expect(insertAt(withOne, -3, two).entries).toEqual([two, one]);
    expect(insertAt(withOne, 99, two).entries).toEqual([one, two]);
  });

  it("ein Abend läuft, solange kein Ende eingetragen ist", () => {
    expect(isRunning(empty)).toBe(true);
    expect(isRunning(firstEvening)).toBe(false);
  });
});
