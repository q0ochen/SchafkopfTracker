// Von Hand nachvollziehbare Fälle zur Abrechnung eines Spiels (Referenzfälle aus docs/ARCHITEKTUR.md, Abschnitt
// „Abrechnung“). Alles Weitere prüft golden/kotlinGolden.test.ts gegen den Kotlin-Kern.
import { describe, expect, it } from "vitest";
import type { GameFacts, RuleSet } from "../index";
import { losers, PotSplitPayout, RuleSets, ScoreCalculator, SchafkopfPayout, winners } from "../index";

const table = ["A", "B", "C", "D"];
const standard = RuleSets.standard();

function facts(fields: Partial<GameFacts> & Pick<GameFacts, "type" | "declarer">): GameFacts {
  return {
    players: table,
    partner: null,
    declarerWon: true,
    level: "NORMAL",
    laufende: 0,
    announcement: "NONE",
    klopferCount: 0,
    kontra: "NONE",
    bockExponent: 0,
    ...fields,
  };
}

const amounts = (game: GameFacts, rules: RuleSet = standard) => Object.fromEntries(ScoreCalculator.amounts(game, rules));

describe("ScoreCalculator", () => {
  it("Rufspiel ohne Zusätze: je ±10", () => {
    const rufspiel = facts({ type: "RUFSPIEL", declarer: "A", partner: "B" });
    expect(ScoreCalculator.breakdown(rufspiel, standard).value).toBe(10);
    expect(amounts(rufspiel)).toEqual({ A: 10, B: 10, C: -10, D: -10 });
    expect(winners(rufspiel, rufspiel.players)).toEqual(["A", "B"]);
    expect(losers(rufspiel, rufspiel.players)).toEqual(["C", "D"]);
    expect(ScoreCalculator.validate(rufspiel, standard)).toEqual([]);
  });

  it("Farbsolo mit 3 Laufenden, schwarz, zweimal gelegt: (50 + 30 + 20) × 4 = 400 je Gegner, 1200 für den Solisten", () => {
    const solo = facts({ type: "FARBSOLO", declarer: "C", laufende: 3, level: "SCHWARZ", klopferCount: 2 });
    expect(ScoreCalculator.breakdown(solo, standard)).toEqual({
      basePrice: 50,
      laufendeCounted: 3,
      laufendeAmount: 30,
      levelAmount: 20,
      sum: 100,
      announcementFactor: 1,
      klopferFactor: 4,
      kontraFactor: 1,
      bockFactor: 1,
      value: 400,
      totalFactor: 4,
    });
    expect(amounts(solo)).toEqual({ A: -400, B: -400, C: 1200, D: -400 });
  });

  it("Laufende unter der Schwelle zählen nicht; Tout verdoppelt ohne Schneider und Schwarz", () => {
    expect(ScoreCalculator.breakdown(facts({ type: "RUFSPIEL", declarer: "A", partner: "B", laufende: 2 }), standard).value).toBe(10);
    const tout = facts({ type: "WENZ", declarer: "B", announcement: "TOUT", level: "SCHWARZ", laufende: 2 });
    // (50 + 2 × 10) × 2 = 140; der Zuschlag für Schwarz entfällt beim Tout.
    expect(ScoreCalculator.breakdown(tout, standard).value).toBe(140);
  });

  it("Topf-Teilung: Der Solist bekommt T, die Gegner teilen sich T, Rest-Cent an die vorderen", () => {
    const wenz = facts({ type: "WENZ", declarer: "B" });
    expect(amounts(wenz, { ...standard, payoutRule: "POT_SPLIT" })).toEqual({ A: -17, B: 50, C: -17, D: -16 });
    expect(Object.fromEntries(PotSplitPayout.payout(50, ["B"], ["A", "C", "D"]))).toEqual({ B: 50, A: -17, C: -17, D: -16 });
    expect(Object.fromEntries(SchafkopfPayout.payout(50, ["B"], ["A", "C", "D"]))).toEqual({ B: 150, A: -50, C: -50, D: -50 });
  });

  it("der größte Spielwert, mit dem noch gerechnet wird", () => {
    expect(ScoreCalculator.MAX_VALUE).toBe(715_827_882);
    const solo = facts({ type: "FARBSOLO", declarer: "A" });
    const limit = { ...standard, prices: { FARBSOLO: 715_827_882 } };
    expect(amounts(solo, limit)).toEqual({ A: 2_147_483_646, B: -715_827_882, C: -715_827_882, D: -715_827_882 });
    const above = { ...standard, prices: { FARBSOLO: 715_827_883 } };
    expect(ScoreCalculator.validate(solo, above)).toEqual(["VALUE_OUT_OF_RANGE"]);
    expect(amounts(solo, above)).toEqual({ A: 0, B: 0, C: 0, D: 0 });
  });

  it("vierzig überlagerte Bockrunden: Der Wert bleibt am Rand stehen, das Faktorprodukt läuft wie ein Kotlin-Int über", () => {
    const game = facts({ type: "RUFSPIEL", declarer: "A", partner: "B", bockExponent: 40 });
    const breakdown = ScoreCalculator.breakdown(game, standard);
    expect(breakdown.bockFactor).toBe(2_147_483_647);
    expect(breakdown.value).toBe(2_147_483_647);
    expect(ScoreCalculator.validate(game, standard)).toEqual(["VALUE_OUT_OF_RANGE"]);
    // Tout × 2³¹−1 mit vier Legern (×16): in 32 Bit gerechnet wie in Kotlin.
    const tout = facts({ type: "WENZ", declarer: "A", announcement: "TOUT", klopferCount: 4 });
    expect(ScoreCalculator.breakdown(tout, { ...standard, toutFactor: 2_147_483_647 }).totalFactor).toBe(-16);
  });

  it("Regelverstöße in der Reihenfolge von Kotlin", () => {
    const broken = facts({ type: "RUFSPIEL", declarer: "X", partner: "A", players: ["A", "A", "B"], klopferCount: 5 });
    expect(ScoreCalculator.validate(broken, { ...standard, klopfenEnabled: false })).toEqual([
      "WRONG_PLAYER_COUNT",
      "DUPLICATE_PLAYERS",
      "DECLARER_NOT_AT_TABLE",
      "KLOPFER_OUT_OF_RANGE",
      "KLOPFEN_DISABLED",
    ]);
  });
});
