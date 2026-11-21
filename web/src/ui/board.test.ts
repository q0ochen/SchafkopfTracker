import { describe, expect, it } from "vitest";
import { EveningCalculator } from "../core/evening/EveningCalculator";
import { addBockTrigger, addThrownIn, removeLast, restore } from "./board";
import { closingOf, closingText } from "./closing";
import { entryText } from "./entryText";
import { fixedFactory, game, testEvening } from "./testing";

const NBSP = " ";
const MINUS = "−";
const names = new Map([
  ["spieler-1", "Anna"],
  ["spieler-2", "Bert"],
  ["spieler-3", "Cilli"],
  ["spieler-4", "Done"],
]);

describe("Aktionen der Abend-Ansicht", () => {
  it("trägt „Zusammengeworfen“ und eine Bockrunde ein", () => {
    const factory = fixedFactory();
    let evening = addThrownIn(testEvening(), factory);
    evening = addBockTrigger(evening, "spieler-2", true, factory);
    evening = addBockTrigger(evening, null, false, factory);
    expect(evening.entries).toEqual([
      { kind: "thrown_in", id: "id-1", recordedAt: "2026-11-14T20:00:00Z" },
      { kind: "bock", id: "id-2", recordedAt: "2026-11-14T20:00:00Z", reason: "SECHS_NIXER", player: "spieler-2", handThrownIn: true },
      { kind: "bock", id: "id-3", recordedAt: "2026-11-14T20:00:00Z", reason: "SECHS_NIXER", player: null, handThrownIn: false },
    ]);
  });

  it("nimmt den letzten Eintrag zurück und setzt ihn an derselben Stelle wieder ein", () => {
    const evening = testEvening([game("g1"), game("g2"), game("g3")]);
    const result = removeLast(evening);
    expect(result?.evening.entries.map((it) => it.id)).toEqual(["g1", "g2"]);
    expect(result?.removed.index).toBe(2);
    if (!result) return;
    // Inzwischen wurde etwas Neues eingetragen: der zurückgenommene Eintrag kommt wieder an seine Stelle.
    const meanwhile = { ...result.evening, entries: [...result.evening.entries, game("g4")] };
    expect(restore(meanwhile, result.removed).entries.map((it) => it.id)).toEqual(["g1", "g2", "g3", "g4"]);
    // Nicht doppelt.
    expect(restore(evening, result.removed)).toBe(evening);
    expect(removeLast(testEvening())).toBeNull();
  });
});

describe("Texte der Einträge (wie EntryTexts.kt)", () => {
  it("beschreibt Spiele mit allen Einzelheiten", () => {
    const evening = testEvening([
      game("g1", { suit: "GRAS", laufende: 3, klopfer: ["spieler-2"], kontra: "KONTRA" }),
      game("g2", { type: "UEBER_KREUZ", declarer: "spieler-2", partner: "spieler-4", declarerWon: false, level: "SCHNEIDER" }),
      game("g3", { type: "FARBSOLO", partner: null, suit: "HERZ", announcement: "TOUT" }),
      { kind: "thrown_in", id: "t1", recordedAt: "2026-11-14T19:30:00Z" },
      { kind: "bock", id: "b1", recordedAt: "2026-11-14T19:31:00Z", reason: "SECHS_NIXER", player: "spieler-3", handThrownIn: true },
      game("g4", { type: "WENZ", declarer: "spieler-4", partner: null }),
    ]);
    const state = EveningCalculator.compute(evening);
    const texts = state.results.map((result) => entryText(result, names, true));
    // Rufspiel: (10 + 3 × 10) × 2 (gelegt) × 2 (Kontra) = 160 Cent.
    expect(texts[0]).toEqual({
      title: "1 · Rufspiel (Gras) · Anna mit Cilli",
      detail: `gewonnen · 3 Laufende · 1× gelegt · Kontra · Wert 1,60${NBSP}€`,
    });
    // Über Kreuz mit Schneider: 10 + 10 = 20 Cent.
    expect(texts[1]).toEqual({ title: "2 · Über Kreuz · Bert und Done", detail: `verloren · Schneider · Wert 0,20${NBSP}€` });
    // Farbsolo Tout: 50 × 2 = 100 Cent.
    expect(texts[2]).toEqual({ title: "3 · Farbsolo (Herz) · Anna", detail: `gewonnen · Tout · Wert 1,00${NBSP}€` });
    expect(texts[3]).toEqual({ title: "Zusammengeworfen", detail: "" });
    expect(texts[4]).toEqual({ title: "Bockrunde: 6 Nixer bei Cilli", detail: "Zusammengeworfen" });
    // Wenz im Bock: 50 × 2 = 100 Cent.
    expect(texts[5]).toEqual({ title: "4 · Wenz · Done", detail: `gewonnen · Bock · Wert 1,00${NBSP}€` });
  });
});

describe("Abend teilen", () => {
  it("rechnet Endstand und „Wer zahlt wem“ und schreibt den Text zum Teilen", () => {
    // Rufspiel Anna+Cilli gewinnen 10, Farbsolo Bert verliert 3 × 50.
    const evening = testEvening(
      [game("g1"), game("g2", { type: "FARBSOLO", declarer: "spieler-2", partner: null, declarerWon: false })],
      { location: "Gasthaus Post" },
    );
    const state = EveningCalculator.compute(evening);
    const closing = closingOf(state, names);
    expect(closing.standings.map((it) => [it.rank, it.name, it.balance])).toEqual([
      [1, "Anna", 60],
      [1, "Cilli", 60],
      [3, "Done", 40],
      [4, "Bert", -160],
    ]);
    expect(closing.balanced).toBe(true);
    expect(closing.transfers.reduce((sum, it) => sum + it.amount, 0)).toBe(160);
    expect(closing.transfers.every((it) => it.from === "spieler-2")).toBe(true);
    const text = closingText(state, closing, names);
    expect(text.split("\n").slice(0, 7)).toEqual([
      "Schafkopf-Abend vom Sa., 14. Nov. 2026 · Gasthaus Post",
      `2${NBSP}Spiele`,
      "",
      `1. Anna +0,60${NBSP}€`,
      `1. Cilli +0,60${NBSP}€`,
      `3. Done +0,40${NBSP}€`,
      `4. Bert ${MINUS}1,60${NBSP}€`,
    ]);
    expect(text).toContain("Wer zahlt wem:");
    expect(text).toContain(`Bert → Anna: 0,60${NBSP}€`);
  });
});
