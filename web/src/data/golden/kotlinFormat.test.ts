// Vergleich mit dem, was der echte Kotlin-Code der Android-App schreibt und liest (kotlin-format-golden.json, erzeugt
// von WebFormatGoldenTest.kt; neu erzeugen mit: tools/jvm-build.sh :data:test --tests '*WebFormatGoldenTest'
// -PgoldenWrite=true). Geschriebene Texte müssen Zeichen für Zeichen gleich sein, gelesene Models gleich, Fehlerarten
// gleich und Vorschau und Übernahme einer Sicherung gleich.
import { describe, expect, it } from "vitest";
import { type ImportPreview, importResultOf, planImport, readBackup, runImport, writeBackup } from "../format/backupFormat";
import { JsonFormatError } from "../format/errors";
import { canonicalInstant, formatInstant, parseInstantValue, tryParseInstant } from "../format/instantText";
import { type JsonElement, parseToJsonElement } from "../format/jsonTree";
import {
  decodeEvening,
  decodeEveningElement,
  decodePlayers,
  decodeRules,
  decodeRulesElement,
  decodeSettings,
  encodeEvening,
  encodePlayers,
  encodeRules,
  isJsonObject,
} from "../format/storageCodec";
import type { Evening, Player } from "../../core/model";
import {
  applyDiff,
  type BackupExpect,
  caseText,
  fromEvening,
  fromPlayer,
  fromRules,
  type GoldenBase,
  goldenChecker,
  importForm,
  loadGolden,
  Mismatches,
  type Outcome,
  outcome,
  previewForm,
  type ReadCase,
  sortedEvening,
  sortedRules,
  toEvening,
  toPlayer,
  toRules,
} from "./goldenSupport";

const golden = loadGolden();
const bases = new Map<string, GoldenBase>(golden.bases.map((base) => [base.name, base]));

/** Liest einen Text als Teil einer Sicherung: erst als Baum, dann übersetzt. */
function treeOutcome(text: string, decode: (element: JsonElement) => unknown): Outcome {
  let element: JsonElement;
  try {
    element = parseToJsonElement(text);
  } catch (error) {
    if (error instanceof JsonFormatError) return { error: "parse" };
    throw error;
  }
  return outcome(() => decode(element));
}

/** Prüft alle Lesefälle eines Abschnitts: als ganze Datei (Strom) und, wo angegeben, als Teil einer Sicherung (Baum). */
function checkReadCases(
  cases: readonly ReadCase[],
  stream: (text: string) => unknown,
  tree: ((element: JsonElement) => unknown) | null,
): { stream: Mismatches; tree: Mismatches } {
  const streamMismatches = new Mismatches();
  const treeMismatches = new Mismatches();
  for (const item of cases) {
    const text = caseText(item, bases);
    const base = item.base === undefined ? undefined : bases.get(item.base);
    const expectedStream = item.stream ?? applyDiff(base?.stream as Outcome, item.streamDiff);
    streamMismatches.check(item.what === undefined ? item.id : `${item.id} (${item.what})`, outcome(() => stream(text)), expectedStream, text);
    if (tree !== null) {
      const expectedTree = item.treeFromBase !== undefined ? applyDiff(base?.stream as Outcome, item.treeFromBase) : applyDiff(expectedStream, item.tree);
      treeMismatches.check(item.what === undefined ? item.id : `${item.id} (${item.what})`, treeOutcome(text, tree), expectedTree, text);
    }
  }
  return { stream: streamMismatches, tree: treeMismatches };
}

describe("Zeitpunkte wie die Android-App", () => {
  it("liest genau die Texte, die Kotlin annimmt, und schreibt sie kanonisch", () => {
    const mismatches = new Mismatches();
    for (const [input, expected] of golden.instantParse) mismatches.check(JSON.stringify(input), tryParseInstant(input), expected);
    expect(mismatches.first, mismatches.summary).toEqual([]);
    expect(golden.instantParse.length).toBeGreaterThan(400);
  });

  it("schreibt Zeitpunkte wie Instant.toString(), im ganzen Bereich von Instant", () => {
    const mismatches = new Mismatches();
    for (const [epochSecond, nano, text] of golden.instantFormat) {
      mismatches.check(`${epochSecond}/${nano}`, formatInstant({ epochSecond: BigInt(epochSecond), nano }), text);
      mismatches.check(`zurück ${text}`, parseInstantValue(text), { epochSecond: BigInt(epochSecond), nano });
      mismatches.check(`kanonisch ${text}`, canonicalInstant(text), text);
    }
    expect(mismatches.first, mismatches.summary).toEqual([]);
  });
});

describe("Schreiben wie die Android-App (Zeichen für Zeichen)", () => {
  it("Abende", () => {
    const mismatches = new Mismatches();
    for (const item of golden.writeEvenings) {
      mismatches.check(item.id, encodeEvening(toEvening(item.model)), item.text);
      if (item.roundTrip) mismatches.check(`${item.id} zurück`, fromEvening(decodeEvening(item.text)), sortedEvening(item.model));
    }
    expect(mismatches.first, mismatches.summary).toEqual([]);
    expect(golden.writeEvenings.length).toBeGreaterThan(100);
  });

  it("Spielerlisten und Regelwerke", () => {
    const mismatches = new Mismatches();
    for (const item of golden.writePlayers) {
      mismatches.check(item.id, encodePlayers(item.model.map(toPlayer)), item.text);
      mismatches.check(`${item.id} zurück`, decodePlayers(item.text).map(fromPlayer), item.model);
    }
    for (const item of golden.writeRules) {
      mismatches.check(item.id, encodeRules(toRules(item.model)), item.text);
      mismatches.check(`${item.id} zurück`, fromRules(decodeRules(item.text)), sortedRules(item.model));
    }
    expect(mismatches.first, mismatches.summary).toEqual([]);
  });

  it("Zeitpunkte in anderer Schreibweise: gelesen und wieder geschrieben wie Instant.toString()", () => {
    const mismatches = new Mismatches();
    for (const item of golden.rewriteEvenings) {
      const rewritten = outcome(() => encodeEvening(decodeEvening(item.text)));
      mismatches.check(item.id, "model" in rewritten ? rewritten.model : null, item.rewritten);
    }
    expect(mismatches.first, mismatches.summary).toEqual([]);
    // Zum Beispiel wird aus „…T19:00:00.000Z“ beim Schreiben „…T19:00:00Z“.
    const zeros = golden.rewriteEvenings.find((item) => item.id === "2026-11-14T19:00:00.000Z");
    expect(zeros?.rewritten).toContain('"startedAt":"2026-11-14T19:00:00Z"');
  });

  it("Sicherungen, mit Einrückung und nach Beginn sortierten Abenden", () => {
    const mismatches = new Mismatches();
    for (const item of golden.writeBackups) {
      const text = writeBackup(item.players.map(toPlayer), item.evenings.map(toEvening), toRules(item.defaultRules), item.appVersion, item.exportedAt);
      mismatches.check(item.id, text, item.text);
    }
    expect(mismatches.first, mismatches.summary).toEqual([]);
  });
});

describe("Lesen wie die Android-App", () => {
  it("erkennt vollständige JSON-Objekte wie Kotlin", () => {
    const mismatches = new Mismatches();
    for (const [text, expected] of golden.isJsonObject) mismatches.check(JSON.stringify(text.substring(0, 80)), isJsonObject(text), expected);
    expect(mismatches.first, mismatches.summary).toEqual([]);
    expect(golden.isJsonObject.length).toBeGreaterThan(200);
  });

  it("Abende: als Datei und als Teil einer Sicherung", () => {
    const result = checkReadCases(golden.readEvenings, (text) => fromEvening(decodeEvening(text)), (element) => fromEvening(decodeEveningElement(element)));
    expect(result.stream.first, result.stream.summary).toEqual([]);
    expect(result.tree.first, result.tree.summary).toEqual([]);
    expect(golden.readEvenings.length).toBeGreaterThan(3000);
  });

  it("Regelwerke: als Text und als Teil einer Sicherung", () => {
    const result = checkReadCases(golden.readRules, (text) => fromRules(decodeRules(text)), (element) => fromRules(decodeRulesElement(element)));
    expect(result.stream.first, result.stream.summary).toEqual([]);
    expect(result.tree.first, result.tree.summary).toEqual([]);
  });

  it("Spielerlisten", () => {
    const result = checkReadCases(golden.readPlayers, (text) => decodePlayers(text).map(fromPlayer), null);
    expect(result.stream.first, result.stream.summary).toEqual([]);
  });

  it("Einstellungen (soweit die Web-App sie hat)", () => {
    const result = checkReadCases(
      golden.readSettings,
      (text) => {
        const settings = decodeSettings(text);
        return { defaultRules: fromRules(settings.defaultRules), keepScreenOn: settings.keepScreenOn, themeMode: settings.themeMode };
      },
      null,
    );
    expect(result.stream.first, result.stream.summary).toEqual([]);
  });
});

/** Spielt ein und lässt das Speichern des Spielers bzw. Abends Nr. n (ab 0 gezählt; -1: nie) scheitern. */
async function importFailingAt(preview: ImportPreview, failPlayerAt: number, failEveningAt: number) {
  let players = 0;
  let evenings = 0;
  return runImport(
    preview,
    async () => {
      if (players++ === failPlayerAt) throw new Error("nachgestellter Fehler");
    },
    async () => {
      if (evenings++ === failEveningAt) throw new Error("nachgestellter Fehler");
    },
  );
}

describe("Sicherung lesen und einspielen wie die Android-App", () => {
  it("dieselbe Vorschau mit denselben Meldungen und dieselbe Übernahme", async () => {
    const previews = new Mismatches();
    const imports = new Mismatches();
    for (const item of golden.readBackups) {
      const base = item.base === undefined ? undefined : bases.get(item.base);
      const text = caseText(item, bases);
      const expected = item.expect ?? applyDiff(base?.expect as BackupExpect, item.expectDiff);
      const knownPlayers: Player[] = (item.knownPlayers ?? base?.knownPlayers ?? []).map(toPlayer);
      const knownEvenings: Evening[] = (item.knownEvenings ?? base?.knownEvenings ?? []).map(toEvening);
      const label = item.what === undefined ? item.id : `${item.id} (${item.what})`;
      let preview;
      try {
        preview = readBackup(text, knownPlayers, knownEvenings, goldenChecker(expected.checks));
      } catch (error) {
        previews.check(label, `Ausnahme: ${String(error)}`, expected.preview, text);
        continue;
      }
      previews.check(label, previewForm(preview), expected.preview, text);
      const plan = planImport(preview);
      imports.check(label, importForm(importResultOf(preview, plan.players, plan.evenings, false)), expected.import, text);
      // Dasselbe über das Einspielen mit Speicher-Funktionen, die alles annehmen.
      const saved: string[] = [];
      const result = await runImport(
        preview,
        async (player) => {
          saved.push(`spieler:${player.id}`);
        },
        async (evening) => {
          saved.push(`abend:${evening.id}`);
        },
      );
      imports.check(`${label} (runImport)`, importForm(result), expected.import);
      imports.check(
        `${label} (Reihenfolge)`,
        saved,
        [...plan.players.map((player) => `spieler:${player.id}`), ...plan.evenings.map((evening) => `abend:${evening.id}`)],
      );
      // Scheitert das Speichern unterwegs, nennt das Ergebnis, was bis dahin gespeichert wurde.
      if (item.failingSecondPlayer !== undefined) {
        imports.check(`${label} (zweiter Spieler scheitert)`, importForm(await importFailingAt(preview, 1, -1)), item.failingSecondPlayer);
      }
      if (item.failingFirstEvening !== undefined) {
        imports.check(`${label} (erster Abend scheitert)`, importForm(await importFailingAt(preview, -1, 0)), item.failingFirstEvening);
      }
    }
    expect(previews.first, previews.summary).toEqual([]);
    expect(imports.first, imports.summary).toEqual([]);
    expect(golden.readBackups.length).toBeGreaterThan(1000);
  });
});
