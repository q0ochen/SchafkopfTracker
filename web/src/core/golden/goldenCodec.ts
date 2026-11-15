/*
 * Liest die Eingaben aus `kotlin-golden.json` (erzeugt vom Kotlin-Kern, siehe
 * domain/src/test/kotlin/io/github/q0ochen/schafkopf/domain/golden/WebGoldenTest.kt) als Daten von `../model.ts` und
 * bringt die Ergebnisse des TypeScript-Ports in dieselbe JSON-Form wie die Ausgaben des Kotlin-Kerns.
 *
 * Die Ausgaben werden allgemein umgewandelt (`toJson`): Maps werden zu `[Schlüssel, Wert]`-Paaren in ihrer Reihenfolge,
 * Objekte behalten genau ihre Felder. Weil der Vergleich auch die Feldnamen prüft, fällt so jedes Feld auf, das im Port
 * fehlt, anders heißt oder überzählig ist.
 */
import type {
  BockTriggerEntry,
  EntryResult,
  Evening,
  EveningEntry,
  EveningState,
  GameEntry,
  GameFacts,
  GameType,
  RuleSet,
  RuleViolation,
  ThrownInEntry,
} from "../index";

/** Ein JSON-Wert. */
export type Json = null | boolean | number | string | Json[] | JsonObject;
export interface JsonObject {
  [key: string]: Json;
}

/** Eine Map in der Datei: `[Schlüssel, Wert]`-Paare in Kotlin-Reihenfolge. */
type Pairs<K extends string, V> = [K, V][];

/** Ein Regelwerk in der Datei: wie `RuleSet`, die Tabellen je Spielart als Paarlisten. */
export interface RuleSetJson extends Omit<RuleSet, "prices" | "laufendeMin" | "laufendeMax"> {
  readonly prices: Pairs<GameType, number>;
  readonly laufendeMin: Pairs<GameType, number>;
  readonly laufendeMax: Pairs<GameType, number>;
}

/** Ein Eintrag in der Datei: wie `EveningEntry`, die Beträge einer freien Buchung als Paarliste. */
export type EntryJson =
  | GameEntry
  | ThrownInEntry
  | BockTriggerEntry
  | {
      readonly kind: "manual";
      readonly id: string;
      readonly recordedAt: string;
      readonly amounts: Pairs<string, number>;
      readonly note: string;
    };

/** Ein Abend in der Datei. */
export interface EveningJson extends Omit<Evening, "rules" | "entries"> {
  readonly rules: RuleSetJson;
  readonly entries: readonly EntryJson[];
}

export function decodeRuleSet(json: RuleSetJson): RuleSet {
  return {
    ...json,
    prices: Object.fromEntries(json.prices),
    enabledTypes: [...json.enabledTypes],
    laufendeMin: Object.fromEntries(json.laufendeMin),
    laufendeMax: Object.fromEntries(json.laufendeMax),
    bock: { ...json.bock },
  };
}

export function decodeEntry(json: EntryJson): EveningEntry {
  switch (json.kind) {
    case "manual":
      return { ...json, amounts: new Map(json.amounts) };
    case "game":
      return { ...json, klopfer: [...json.klopfer] };
    default:
      return { ...json };
  }
}

export function decodeEvening(json: EveningJson): Evening {
  return {
    ...json,
    seating: [...json.seating],
    rules: decodeRuleSet(json.rules),
    entries: json.entries.map(decodeEntry),
  };
}

export function decodeFacts(json: GameFacts): GameFacts {
  return { ...json, players: [...json.players] };
}

/**
 * Ein Ergebnis des Ports als JSON: Maps als Paarlisten, Mengen als Listen, Objekte mit genau ihren Feldern.
 * Wirft bei Werten, die es in JSON nicht gibt (`undefined`, Funktionen …): Die hätte der Port nicht liefern dürfen.
 */
export function toJson(value: unknown): Json {
  if (value === null || typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return value;
  }
  if (value instanceof Map) return [...value].map(([key, entry]) => [toJson(key), toJson(entry)]);
  if (value instanceof Set) return [...value].map(toJson);
  if (Array.isArray(value)) return value.map(toJson);
  if (typeof value === "object" && Object.getPrototypeOf(value) === Object.prototype) {
    return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, toJson(entry)]));
  }
  throw new Error(`Kein JSON-Wert im Ergebnis: ${String(value)}`);
}

/** Ein Ergebnis von `EveningCalculator.compute` wie in der Datei: statt des Eintrags seine Kennung. */
export function encodeEntryResult(result: EntryResult, violations: readonly RuleViolation[] | null): Json {
  const { entry, ...rest } = result;
  return { entryId: entry.id, ...(toJson(rest) as JsonObject), violations: violations === null ? null : [...violations] };
}

/** Ein `EveningState` wie in der Datei: ohne den Abend selbst (das ist die Eingabe). */
export function encodeState(state: EveningState, results: Json[]): Json {
  const { evening: _evening, results: _results, ...rest } = state;
  return { results, ...(toJson(rest) as JsonObject) };
}

/**
 * Die erste Abweichung zwischen der Ausgabe des Kotlin-Kerns und der des Ports als lesbarer Text mit dem Pfad dorthin;
 * `null`, wenn beide gleich sind. Zahlen müssen bitgleich sein (`Object.is`: auch −0 und 0 unterscheiden sich).
 */
export function firstDifference(expected: Json, actual: Json, path = ""): string | null {
  const show = (value: Json): string => JSON.stringify(value).slice(0, 200);
  if (expected === null || typeof expected !== "object" || actual === null || typeof actual !== "object") {
    return Object.is(expected, actual) ? null : `${path || "(Wurzel)"}: Kotlin ${show(expected)}, TypeScript ${show(actual)}`;
  }
  if (Array.isArray(expected) || Array.isArray(actual)) {
    if (!Array.isArray(expected) || !Array.isArray(actual)) {
      return `${path}: Kotlin ${show(expected)}, TypeScript ${show(actual)}`;
    }
    for (let index = 0; index < Math.min(expected.length, actual.length); index++) {
      const difference = firstDifference(expected[index], actual[index], `${path}[${index}]`);
      if (difference !== null) return difference;
    }
    return expected.length === actual.length
      ? null
      : `${path}: Kotlin ${expected.length} Elemente, TypeScript ${actual.length} (${show(expected)} / ${show(actual)})`;
  }
  const expectedKeys = Object.keys(expected);
  const actualKeys = Object.keys(actual);
  const missing = expectedKeys.filter((key) => !(key in actual));
  const extra = actualKeys.filter((key) => !(key in expected));
  if (missing.length > 0 || extra.length > 0) {
    return `${path || "(Wurzel)"}: Felder fehlen im Port [${missing.join(", ")}], überzählig [${extra.join(", ")}]`;
  }
  for (const key of expectedKeys) {
    const difference = firstDifference(expected[key], actual[key], `${path}.${key}`);
    if (difference !== null) return difference;
  }
  return null;
}
