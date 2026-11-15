/*
 * Die Verbindung von Dateiformat und Rechenkern: Alle Sicherungs-Fälle aus Kotlin (kotlin-format-golden.json) laufen
 * hier mit dem echten Rechenkern der Web-App statt mit den vorgerechneten Ergebnissen. Die Vorschau muss trotzdem
 * genau die der Android-App sein.
 */
import { describe, expect, it } from "vitest";
import type { Evening, Player } from "../core/model";
import {
  applyDiff,
  type BackupExpect,
  caseText,
  type GoldenBase,
  loadGolden,
  Mismatches,
  previewForm,
  toEvening,
  toPlayer,
} from "../data/golden/goldenSupport";
import { backupPort, coreChecker, storageCodec } from "./formatAdapters";
import { readBackup } from "../data/format";
import { game, testEvening } from "../ui/testing";

const golden = loadGolden();
const bases = new Map<string, GoldenBase>(golden.bases.map((base) => [base.name, base]));

describe("Dateiformat mit dem Rechenkern der Web-App", () => {
  it("liefert für jede Sicherung aus Kotlin dieselbe Vorschau", () => {
    const previews = new Mismatches();
    for (const item of golden.readBackups) {
      const base = item.base === undefined ? undefined : bases.get(item.base);
      const text = caseText(item, bases);
      const expected = item.expect ?? applyDiff(base?.expect as BackupExpect, item.expectDiff);
      const knownPlayers: Player[] = (item.knownPlayers ?? base?.knownPlayers ?? []).map(toPlayer);
      const knownEvenings: Evening[] = (item.knownEvenings ?? base?.knownEvenings ?? []).map(toEvening);
      const label = item.what === undefined ? item.id : `${item.id} (${item.what})`;
      let form: unknown;
      try {
        form = previewForm(readBackup(text, knownPlayers, knownEvenings, coreChecker));
      } catch (error) {
        form = `Ausnahme: ${String(error)}`;
      }
      previews.check(label, form, expected.preview, text);
    }
    expect(previews.first, previews.summary).toEqual([]);
    expect(golden.readBackups.length).toBeGreaterThan(1000);
  });

  it("schreibt eine Sicherung, die sich selbst wieder einlesen lässt", () => {
    const players: Player[] = [1, 2, 3, 4].map((n) => ({ id: `spieler-${n}`, name: `Spieler ${n}`, colorIndex: n - 1, archived: false }));
    const evening = testEvening([game("g1"), game("g2", { type: "WENZ", partner: null, declarerWon: false })]);
    const text = backupPort.write(players, [evening], evening.rules, "0.1.0", "2026-11-15T20:00:00Z");
    const preview = backupPort.read(text, players.map((it) => ({ ...it, name: `Neu ${it.id}` })), []);
    expect(preview.error).toBeNull();
    expect(preview.newEvenings).toEqual([evening]);
    expect(preview.differingPlayers.map((it) => it.name)).toEqual(["Spieler 1", "Spieler 2", "Spieler 3", "Spieler 4"]);
    expect(storageCodec.decodeEvening(storageCodec.encodeEvening(evening))).toEqual(evening);
  });
});
