/* Namen von Runden (docs/ARCHITEKTUR.md, 8.7, „Eindeutige Namen“): bereinigen und vergleichen wie der Server. Port von `RoundNamesTest.kt`. */
import { describe, expect, it } from "vitest";
import { roundSummary, type RoundSummary } from "./RoundModels";
import { RoundNames } from "./RoundNames";

function round(id: string, name: string, ...names: string[]): RoundSummary {
  return roundSummary({ id, name, rev: 1, memberCount: Math.max(1, names.length), myPlayerId: null, isCreator: false, memberNames: names });
}

describe("RoundNames", () => {
  it("bereinigt wie der Server: Folgen von Leer- und Steuerzeichen werden ein Leerzeichen, Ränder fallen weg", () => {
    expect(RoundNames.clean("  Stammtisch \t  Runde \n")).toBe("Stammtisch Runde");
    expect(RoundNames.clean("a\u0000\u001Fb\u007F")).toBe("a b");
    expect(RoundNames.clean(" \t\r\n ")).toBe("");
    // Nur ASCII: Ein geschütztes Leerzeichen bleibt, wie es ist (der Server sieht es je nach Einstellung anders).
    expect(RoundNames.clean("a b")).toBe("a b");
    // Anders als `trim()` von JavaScript bleibt auch ein Zeichen wie U+FEFF am Rand stehen.
    expect(RoundNames.clean("﻿Runde ")).toBe("﻿Runde");
  });

  it("gültig sind 1 bis 40 Zeichen nach dem Bereinigen, gezählt wie auf dem Server", () => {
    expect(RoundNames.valid(" \t ")).toBe(false);
    expect(RoundNames.valid("x")).toBe(true);
    expect(RoundNames.valid(`  ${"x".repeat(40)}  `)).toBe(true);
    expect(RoundNames.valid("x".repeat(41))).toBe(false);
    expect(RoundNames.valid(`Ä${"ö".repeat(39)}`)).toBe(true);
    // Zeichen außerhalb der Grundebene zählen einmal, nicht zweimal.
    expect(RoundNames.valid("🃏".repeat(40))).toBe(true);
    expect(RoundNames.valid("🃏".repeat(41))).toBe(false);
    expect(RoundNames.MAX_LENGTH).toBe(40);
  });

  it("derselbe Name ohne Groß und Klein und nach dem Bereinigen", () => {
    expect(RoundNames.same("Stammtisch", "  stammTISCH\t")).toBe(true);
    expect(RoundNames.same("Stamm  tisch", "stamm tisch")).toBe(true);
    expect(RoundNames.same("Stammtisch", "Stamm tisch")).toBe(false);
    expect(RoundNames.same("Ärger", "ärger")).toBe(true);
  });

  it("vergeben ist ein Name, wenn eine Runde des Kontos so heißt", () => {
    const rounds = [round("a", "Stammtisch"), round("b", "Klub")];
    expect(RoundNames.taken(" klub ", rounds)).toBe(true);
    expect(RoundNames.taken("Klub 2", rounds)).toBe(false);
    expect(RoundNames.taken("Stammtisch", [])).toBe(false);
  });

  it("der Vorschlag ist die erste freie Fassung mit Nummer", () => {
    expect(RoundNames.suggest([])).toBe("Schafkopfrunde");
    expect(RoundNames.DEFAULT).toBe("Schafkopfrunde");
    expect(RoundNames.suggest([round("a", "Stammtisch")])).toBe("Schafkopfrunde");
    expect(RoundNames.suggest([round("a", "schafkopfrunde")])).toBe("Schafkopfrunde 2");
    expect(RoundNames.suggest([round("a", "Schafkopfrunde 3"), round("b", "Schafkopfrunde"), round("c", "Schafkopfrunde 2")])).toBe(
      "Schafkopfrunde 4",
    );
    expect(RoundNames.suggest([round("a", "Schafkopfrunde"), round("b", "Schafkopfrunde 3")])).toBe("Schafkopfrunde 2");
    expect(RoundNames.suggest([], "Klub")).toBe("Klub");
  });

  it("eine eigene, noch leere Runde ist wiederverwendbar und blockiert ihren Namen nicht – außer sie ist verbunden", () => {
    const fresh = roundSummary({ id: "f", name: "Stammtisch", rev: 1, memberCount: 1, myPlayerId: null, isCreator: true });
    const used = { ...fresh, id: "u", name: "Klub", rev: 2 };
    const foreign = { ...fresh, id: "x", name: "Freitag", isCreator: false };
    const joined = { ...fresh, id: "j", name: "Samstag", memberCount: 2 };

    expect(RoundNames.reusable(fresh)).toBe(true);
    expect(RoundNames.reusable(used)).toBe(false);
    expect(RoundNames.reusable(foreign)).toBe(false);
    expect(RoundNames.reusable(joined)).toBe(false);
    expect(RoundNames.blocking([fresh, used, foreign, joined], null)).toEqual([used, foreign, joined]);
    expect(RoundNames.blocking([fresh, used], "f")).toEqual([fresh, used]);
    // Die Vorgabe nimmt den Namen der leeren Runde, wenn er nur dort vergeben ist.
    expect(RoundNames.suggest(RoundNames.blocking([{ ...fresh, name: "Schafkopfrunde" }], null))).toBe("Schafkopfrunde");
  });

  it("gleichnamige Runden werden erkannt, ohne Groß und Klein", () => {
    const rounds = [
      round("a", "Stammtisch", "Anna", "Bert"),
      round("b", "Klub"),
      round("c", "STAMMTISCH ", "Cilli"),
      round("d", "Freitag"),
      round("e", "freitag"),
    ];
    expect([...RoundNames.duplicates(rounds)]).toEqual(["a", "c", "d", "e"]);
    expect(RoundNames.duplicates(rounds.slice(0, 2))).toEqual(new Set());
  });
});
