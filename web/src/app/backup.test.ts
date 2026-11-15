import { describe, expect, it } from "vitest";
import type { Evening, Player } from "../core/model";
import { backupFileName, hasAnythingToImport, importPlan, importSummary, type ImportPreview } from "./backup";

const anna: Player = { id: "spieler-1", name: "Anna", colorIndex: 0, archived: false };
const gast: Player = { id: "p9", name: "Gast", colorIndex: 4, archived: false };
const evening = { id: "e1" } as Evening;
const other = { id: "e2" } as Evening;

const empty: ImportPreview = {
  error: null,
  exportedAt: "2026-11-15T10:00:00Z",
  appVersion: "0.1.0",
  newPlayers: [],
  sameNamePlayers: [],
  existingPlayerCount: 4,
  differingPlayers: [],
  newEvenings: [],
  existingEvenings: [],
  invalidEvenings: [],
  defaultRules: null,
};

describe("Sicherung", () => {
  it("nennt die Datei nach dem Tag der Sicherung", () => {
    expect(backupFileName("2026-11-15T21:30:00Z")).toBe("schafkopf-sicherung-2026-11-15.json");
    expect(backupFileName("2026-11-15T21:30:00.123Z")).toBe("schafkopf-sicherung-2026-11-15.json");
    expect(backupFileName("kaputt")).toBe("schafkopf-sicherung.json");
  });

  it("übernimmt neue Spieler und Abende, abweichende Namen nur auf Wunsch", () => {
    const preview: ImportPreview = { ...empty, newPlayers: [gast], differingPlayers: [anna], newEvenings: [evening], existingEvenings: [other] };
    expect(importPlan(preview, { takeNames: true, takeRules: false })).toEqual({ players: [gast, anna], evenings: [evening], defaultRules: null });
    expect(importPlan(preview, { takeNames: false, takeRules: false }).players).toEqual([gast]);
  });

  it("fasst die Vorschau in einfachen Sätzen zusammen", () => {
    const preview: ImportPreview = {
      ...empty,
      newPlayers: [gast],
      sameNamePlayers: [gast],
      newEvenings: [evening],
      existingEvenings: [other, other],
      invalidEvenings: [{ id: "x", reason: "Der Abend vom 1.11.2026 ist beschädigt." }],
    };
    expect(importSummary(preview)).toEqual([
      "1 neuer Abend kommt dazu.",
      "2 Abende sind schon da und bleiben, wie sie sind.",
      "1 Abend wird ausgelassen:",
      "– Der Abend vom 1.11.2026 ist beschädigt.",
      "1 neuer Spieler kommt dazu: Gast.",
      "Achtung: Gast heißt wie ein vorhandener Spieler, ist aber ein anderer und wird zusätzlich angelegt.",
    ]);
    expect(importSummary({ ...empty, error: { kind: "NOT_A_BACKUP", message: "Das ist keine Sicherung." } })).toEqual([
      "Das ist keine Sicherung.",
    ]);
  });

  it("erkennt, ob es etwas zu übernehmen gibt", () => {
    expect(hasAnythingToImport(empty)).toBe(false);
    expect(hasAnythingToImport({ ...empty, newEvenings: [evening] })).toBe(true);
    expect(hasAnythingToImport({ ...empty, differingPlayers: [anna] })).toBe(true);
    expect(hasAnythingToImport({ ...empty, error: { kind: "X", message: "kaputt" }, newEvenings: [evening] })).toBe(false);
  });
});
