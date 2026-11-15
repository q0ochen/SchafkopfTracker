// Lesbare Beispiele für das Dateiformat. Die vollständige Gegenprüfung mit dem Kotlin-Code steht in
// src/data/golden/kotlinFormat.test.ts; hier geht es um das Verhalten, auf das sich Speicherung und Oberfläche verlassen.
import { describe, expect, it } from "vitest";
import type { Evening, GameEntry, Player } from "../../core/model";
import {
  type CheckedEntryResult,
  decodeEvening,
  decodePlayers,
  decodeRules,
  decodeRulesOrNull,
  decodeSettings,
  defaultSettings,
  encodeEvening,
  encodePlayers,
  encodeRules,
  encodeSettings,
  type EveningChecker,
  hasNewData,
  InvalidInstantError,
  InvalidModelError,
  isJsonObject,
  isReadable,
  NewerSchemaError,
  parseInstant,
  readBackup,
  runImport,
  STORAGE_FAILED_ERROR,
  tryParseInstant,
  UnreadableDataError,
  writeBackup,
} from "./index";
import { standardRules } from "./dtos";

const anna: Player = { id: "spieler-anna", name: "Anna", colorIndex: 0, archived: false };
const bertl: Player = { id: "spieler-bertl", name: "Bertl", colorIndex: 1, archived: false };

function evening(id: string, startedAt = "2026-11-14T19:00:00Z"): Evening {
  return {
    id,
    startedAt,
    finishedAt: null,
    location: "Wirtshaus zur Post",
    seating: ["A", "B", "C", "D"],
    firstDealer: "B",
    rules: standardRules(),
    entries: [
      {
        kind: "game",
        id: "e1",
        recordedAt: "2026-11-14T19:10:00Z",
        type: "RUFSPIEL",
        suit: "EICHEL",
        declarer: "A",
        partner: "B",
        declarerWon: true,
        level: "SCHNEIDER",
        laufende: 3,
        announcement: "NONE",
        klopfer: ["C"],
        kontra: "KONTRA",
        kontraBy: "D",
        retourBy: null,
        bockOverride: "AUTO",
        declarerEyes: 95,
        note: "Eichel-Sau",
      },
      { kind: "thrown_in", id: "e2", recordedAt: "2026-11-14T19:15:00Z" },
      { kind: "bock", id: "e3", recordedAt: "2026-11-14T19:20:00Z", reason: "SECHS_NIXER", player: "C", handThrownIn: true },
      { kind: "manual", id: "e4", recordedAt: "2026-11-14T19:25:00Z", amounts: new Map([["A", 100], ["D", -100]]), note: "Wette" },
    ],
  };
}

/** Eine Prüf-Schnittstelle für Beispiele: Jedes Spiel geht auf, nichts liegt außerhalb des Bereichs. */
const balancedChecker: EveningChecker = {
  computeResults: (checked) =>
    checked.entries.map((): CheckedEntryResult => ({ issues: [], bockExponent: 0, amounts: new Map(checked.seating.map((player) => [player, 0])) })),
  isValueOutOfRange: () => false,
};

describe("Abend als Datei", () => {
  it("kommt unverändert zurück und steht in der Schreibweise der Android-App in der Datei", () => {
    const original = evening("abend-1");
    const text = encodeEvening(original);
    expect(text.startsWith('{"schemaVersion":1,"id":"abend-1","startedAt":"2026-11-14T19:00:00Z","location":"Wirtshaus zur Post"')).toBe(true);
    expect(text).toContain('{"type":"game","id":"e1","recordedAt":"2026-11-14T19:10:00Z","gameType":"rufspiel","suit":"eichel","declarer":"A"');
    expect(text).toContain('"amounts":{"A":100,"D":-100}');
    // Leere Angaben (finishedAt, retourBy) stehen nicht in der Datei.
    expect(text).not.toContain("null");
    expect(decodeEvening(text)).toEqual(original);
  });

  it("schreibt Zeitpunkte so, wie Kotlin sie schreibt", () => {
    const text = encodeEvening({ ...evening("abend-2"), startedAt: "2026-11-14T19:00:00.000Z" });
    expect(text).toContain('"startedAt":"2026-11-14T19:00:00Z"');
    const read = decodeEvening(text.replace('"2026-11-14T19:00:00Z"', '"2026-11-14T20:00:00.500+01:00"'));
    expect(read.startedAt).toBe("2026-11-14T19:00:00.500Z");
  });

  it("nimmt an, was auch die Android-App annimmt, selbst wenn es kein sauberes JSON ist", () => {
    const text = encodeEvening(evening("abend-3"));
    // Ein fehlendes Komma zwischen zwei Feldern, Wahrheitswerte in Großbuchstaben und Zahlen in Anführungszeichen.
    const lax = text.replace('"id":"abend-3",', '"id":"abend-3" ').replace('"declarerWon":true', '"declarerWon":"TRUE"').replace('"laufende":3', '"laufende":"3"');
    expect(decodeEvening(lax)).toEqual(decodeEvening(text));
    // Eine unsichtbare Markierung am Anfang wird überlesen, unbekannte Felder auch.
    expect(decodeEvening("﻿" + text.replace('{"schemaVersion":1,', '{"schemaVersion":1,"wetter":"Schnee",'))).toEqual(decodeEvening(text));
  });

  it("unterscheidet unlesbare Dateien von Dateien einer neueren App", () => {
    const text = encodeEvening(evening("abend-4"));
    expect(() => decodeEvening("{")).toThrow(UnreadableDataError);
    expect(() => decodeEvening(text.replace('"laufende":3', '"laufende":3.0'))).toThrow(UnreadableDataError);

    const newer = (() => {
      try {
        decodeEvening(text.replace('"schemaVersion":1', '"schemaVersion":2'));
      } catch (error) {
        return error;
      }
      return null;
    })();
    expect(newer).toBeInstanceOf(NewerSchemaError);
    expect(newer).toBeInstanceOf(UnreadableDataError);
    expect((newer as NewerSchemaError).found).toBe(2);
    expect((newer as NewerSchemaError).message).toBe("Schema-Version 2 ist neuer als die unterstützte Version 1.");

    // Eine Version unter 1 gibt es nicht; das ist schlicht eine kaputte Datei.
    expect(() => decodePlayers('{"schemaVersion":0,"players":[]}')).toThrow("Ungültige Schema-Version 0.");
  });

  it("lehnt beim Schreiben Werte ab, die in der Android-App nicht vorkommen können", () => {
    const broken = evening("abend-5");
    const game = broken.entries[0] as GameEntry;
    expect(() => encodeEvening({ ...broken, entries: [{ ...game, laufende: 1.5 }] })).toThrow(InvalidModelError);
    expect(() => encodeEvening({ ...broken, startedAt: "gestern" })).toThrow(InvalidInstantError);
  });
});

describe("Spieler, Regelwerk und Einstellungen", () => {
  it("Spielerliste hin und zurück", () => {
    const text = encodePlayers([anna, { ...bertl, archived: true }]);
    expect(text).toBe(
      '{"schemaVersion":1,"players":[{"id":"spieler-anna","name":"Anna","colorIndex":0,"archived":false},' +
        '{"id":"spieler-bertl","name":"Bertl","colorIndex":1,"archived":true}]}',
    );
    expect(decodePlayers(text)).toEqual([anna, { ...bertl, archived: true }]);
    expect(decodePlayers("{}")).toEqual([]);
  });

  it("Regelwerk: fehlende Werte kommen aus der Vorlage Sauspiel, Spielarten stehen sortiert in der Datei", () => {
    const rules = decodeRules('{"unit":5}');
    expect(rules.unit).toBe(5);
    expect(rules.name).toBe("Sauspiel 10-20-50 mit Bockrunde");
    expect(rules.prices).toEqual({ RUFSPIEL: 20, FARBSOLO: 50, WENZ: 50, GEIER: 50 });
    expect(encodeRules({ ...rules, enabledTypes: ["GEIER", "RUFSPIEL"] })).toContain('"enabledTypes":["rufspiel","geier"]');
    expect(decodeRulesOrNull('{"enabledTypes":["ramsch"]}')).toBeNull();
  });

  it("Einstellungen der Web-App hin und zurück; fehlt etwas, gilt der Standard", () => {
    const settings = { ...defaultSettings(), keepScreenOn: false, themeMode: "LIGHT" as const };
    expect(decodeSettings(encodeSettings(settings))).toEqual(settings);
    expect(decodeSettings("{}")).toEqual(defaultSettings());
    // Eine Datei der Android-App mit Vibration und KI-Einstellungen ist lesbar; übernommen wird, was die Web-App kennt.
    expect(decodeSettings('{"themeMode":"system","haptics":false,"ai":{"enabled":true}}').themeMode).toBe("SYSTEM");
  });

  it("Zeitpunkte und vollständige JSON-Objekte", () => {
    expect(parseInstant("2026-11-14t19:00:00.000z")).toBe("2026-11-14T19:00:00Z");
    expect(parseInstant("2026-12-31T24:00:00Z")).toBe("2027-01-01T00:00:00Z");
    expect(tryParseInstant("+1000000000-12-31T23:59:59.999999999Z")).toBeNull();
    expect(() => parseInstant("14.11.2026")).toThrow(InvalidInstantError);
    expect(isJsonObject('{"a":1}')).toBe(true);
    expect(isJsonObject('{"a":1')).toBe(false);
  });
});

describe("Sicherung", () => {
  const backup = writeBackup([anna, bertl], [evening("spaet", "2026-11-14T20:00:00Z"), evening("frueh")], standardRules(), "0.1.0", "2026-11-15T10:00:00Z");

  it("hat den vereinbarten Kopf, ist eingerückt und nennt die Abende vom frühesten an", () => {
    expect(backup.startsWith('{\n    "format": "schafkopf-tracker-backup",\n    "schemaVersion": 1,\n    "appVersion": "0.1.0",')).toBe(true);
    expect(backup.indexOf('"id": "frueh"')).toBeLessThan(backup.indexOf('"id": "spaet"'));
  });

  it("zeigt in der Vorschau, was neu wäre, und meldet ungültige Abende mit Grund", () => {
    const preview = readBackup(backup, [anna], [], balancedChecker);
    expect(isReadable(preview)).toBe(true);
    expect(hasNewData(preview)).toBe(true);
    expect(preview.newPlayers).toEqual([bertl]);
    expect(preview.existingPlayerCount).toBe(1);
    expect(preview.newEvenings.map((item) => item.id)).toEqual(["frueh", "spaet"]);

    const threeSeats = writeBackup([], [{ ...evening("drei"), seating: ["A", "B", "C"] }], standardRules(), "0.1.0", "2026-11-15T10:00:00Z");
    expect(readBackup(threeSeats, [], [], balancedChecker).invalidEvenings).toEqual([
      { id: "drei", startedAt: "2026-11-14T19:00:00Z", problem: "WRONG_SEATING", reason: "Am Tisch müssen genau vier Spieler sitzen, hier sind es 3." },
    ]);
    expect(readBackup("[1, 2, 3]", [], [], balancedChecker).error).toEqual({ kind: "WRONG_FORMAT", message: "Die Datei ist keine Sicherung dieser App." });
  });

  it("spielt erst die Spieler und dann die Abende ein und meldet, wenn das Speichern scheitert", async () => {
    const preview = readBackup(backup, [], [], balancedChecker);
    const saved: string[] = [];
    const complete = await runImport(
      preview,
      async (player) => {
        saved.push(player.id);
      },
      async (item) => {
        saved.push(item.id);
      },
    );
    expect(complete.error).toBeNull();
    expect(saved).toEqual(["spieler-anna", "spieler-bertl", "frueh", "spaet"]);

    const failed = await runImport(
      preview,
      async () => undefined,
      async () => {
        throw new Error("Speicher voll");
      },
    );
    expect(failed.error).toEqual(STORAGE_FAILED_ERROR);
    expect(failed.newPlayers).toEqual([anna, bertl]);
    expect(failed.newEvenings).toEqual([]);
  });
});
