import { describe, expect, it } from "vitest";
import type { Evening } from "../../core/model";
import { RuleSets } from "../../core/rules/RuleSet";
import { StatisticEngine } from "../../core/stats/StatisticEngine";
import { game } from "../../core/testing/roundEvenings";
import { barPercent, barScale, modeChartGroups, played } from "./GameModeChart";

describe("Skala des Spielarten-Diagramms (wie BarScale der Android-App)", () => {
  it("reicht so weit wie der größte Betrag, egal ob Gewinn oder Verlust", () => {
    expect(barScale([-50, 150, 0])).toBe(150);
    expect(barScale([10, -40])).toBe(40);
    expect(barScale([])).toBe(0);
    expect(barScale([0, 0])).toBe(0);
  });

  it("der größte Betrag reicht bis an den Rand, also die halbe Breite", () => {
    expect(barPercent(150, 150)).toBe(50);
    expect(barPercent(-75, 150)).toBe(25);
    expect(barPercent(10, 40)).toBe(12.5);
    expect(barPercent(0, 150)).toBe(0);
    expect(barPercent(5, 0)).toBe(0);
    expect(barPercent(-5000, 1000)).toBe(50);
  });
});

describe("Gruppen des Spielarten-Diagramms", () => {
  const start = "2026-11-14T18:30:00Z";
  const evening: Evening = {
    id: "abend-1",
    startedAt: start,
    finishedAt: null,
    location: "",
    seating: ["anna", "bert", "cilli", "done"],
    firstDealer: "anna",
    rules: RuleSets.standard(),
    entries: [
      // Rufspiel Anna mit Cilli gewonnen (je +10), dann Wenz von Bert verloren (Bert −150).
      game("g1", start, { type: "RUFSPIEL", declarer: "anna", partner: "cilli", declarerWon: true }),
      game("g2", start, { type: "WENZ", declarer: "bert", declarerWon: false }),
    ],
  };
  const names: Record<string, string> = { anna: "Anna", bert: "Bert", cilli: "Cilli", done: "Done" };

  it("enthalten nur gespielte Spielarten und alle Spieler in Sitzreihenfolge", () => {
    const groups = modeChartGroups(
      StatisticEngine.forEvening(evening),
      (id) => names[id] ?? "?",
      (id) => evening.seating.indexOf(id),
    );
    expect(groups.map((it) => it.title)).toEqual(["Rufspiel", "Wenz"]);
    expect(groups.map((it) => it.games)).toEqual([1, 1]);
    const [ruf, wenz] = groups;
    expect(ruf.rows.map((it) => it.name)).toEqual(["Anna", "Bert", "Cilli", "Done"]);
    expect(ruf.rows.map((it) => it.colorIndex)).toEqual([0, 1, 2, 3]);
    expect(played(ruf.rows[0])).toMatchObject({ games: 1, won: 1, balance: 10, averageAmount: 10 });
    // Bert war im Rufspiel Gegenspieler: nicht selbst gespielt.
    expect(played(ruf.rows[1])).toBeNull();
    expect(played(wenz.rows[1])).toMatchObject({ games: 1, won: 0, balance: -150, averageAmount: -150 });
    expect(played(wenz.rows[0])).toBeNull();
  });
});
