import { describe, expect, it } from "vitest";
import type { Player } from "../core/model";
import { game, testEvening } from "../ui/testing";
import { COLOR_COUNT, eveningCount, eveningInvolves, nextColorIndex } from "./players";

const player = (id: string, colorIndex: number, archived = false): Player => ({ id, name: id, colorIndex, archived });

describe("Farbe für neue Spieler (wie Player.nextColorIndex)", () => {
  it("nimmt die erste freie Farbe, auch nach dem Löschen eines Spielers", () => {
    expect(nextColorIndex([])).toBe(0);
    expect(nextColorIndex([player("a", 0), player("b", 1), player("c", 2), player("d", 3)])).toBe(4);
    expect(nextColorIndex([player("a", 0), player("c", 2), player("d", 3), player("e", 4)])).toBe(1);
    expect(nextColorIndex([player("a", 0, true), player("b", 1)])).toBe(0);
    expect(nextColorIndex([player("a", 8), player("b", -6)])).toBe(1);
  });

  it("geht nach der höchsten Nummer weiter, wenn alle Farben vergeben sind", () => {
    const all = Array.from({ length: COLOR_COUNT }, (_, index) => player(`p${index}`, index));
    expect(nextColorIndex(all)).toBe(COLOR_COUNT);
    expect(nextColorIndex([...all, player("x", 11)])).toBe(12);
  });
});

describe("Kommt ein Spieler in einem Abend vor? (wie Evening.involves)", () => {
  it("am Tisch oder als Geber", () => {
    expect(eveningInvolves(testEvening(), "spieler-3")).toBe(true);
    expect(eveningInvolves(testEvening(), "gast")).toBe(false);
    expect(eveningInvolves(testEvening([], { firstDealer: "gast" }), "gast")).toBe(true);
  });

  it("in irgendeinem Eintrag, auch in beschädigten Daten", () => {
    const cases = [
      game("1", { declarer: "gast" }),
      game("2", { partner: "gast" }),
      game("3", { klopfer: ["gast"] }),
      game("4", { kontra: "KONTRA", kontraBy: "gast" }),
      game("5", { kontra: "RETOUR", retourBy: "gast" }),
    ];
    for (const entry of cases) expect(eveningInvolves(testEvening([entry]), "gast")).toBe(true);
    expect(
      eveningInvolves(
        testEvening([{ kind: "bock", id: "b", recordedAt: "2026-11-14T19:00:00Z", reason: "SECHS_NIXER", player: "gast", handThrownIn: true }]),
        "gast",
      ),
    ).toBe(true);
    expect(
      eveningInvolves(
        testEvening([{ kind: "manual", id: "m", recordedAt: "2026-11-14T19:00:00Z", amounts: new Map([["gast", 10], ["spieler-1", -10]]), note: "" }]),
        "gast",
      ),
    ).toBe(true);
  });

  it("Einträge anderer und Zusammengeworfen zählen nicht", () => {
    const evening = testEvening([
      { kind: "thrown_in", id: "t", recordedAt: "2026-11-14T19:00:00Z" },
      game("g"),
      { kind: "bock", id: "b", recordedAt: "2026-11-14T19:00:00Z", reason: "SECHS_NIXER", player: null, handThrownIn: true },
    ]);
    expect(eveningInvolves(evening, "gast")).toBe(false);
    expect(eveningCount([evening, testEvening([], { id: "zwei" })], "spieler-1")).toBe(2);
    expect(eveningCount([evening], "gast")).toBe(0);
  });
});
