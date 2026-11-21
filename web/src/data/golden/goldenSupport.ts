/*
 * Hilfen für den Vergleich mit den Golden-Daten aus dem Kotlin-Code (kotlin-format-golden.json, erzeugt von
 * data/src/test/kotlin/io/github/q0ochen/schafkopf/data/golden/WebFormatGoldenTest.kt).
 *
 * In der Golden-Datei stehen die Daten in der Form von core/model.ts, nur ohne `Map` und `Record`: Tabellen stehen als
 * Paarlisten `[schlüssel, wert]` in der Reihenfolge, in der Kotlin sie hält.
 */
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { isDeepStrictEqual } from "node:util";
import { type BockRules, type Evening, type EveningEntry, GAME_TYPES, type GameType, type Player, type RuleSet } from "../../core/model";
import type { EveningChecker, ImportPreview, ImportResult } from "../format/backupFormat";
import { NewerSchemaError, UnreadableDataError } from "../format/errors";

// ---------- Aufbau der Datei ----------

export type Pairs = Array<[string, number]>;

export interface GoldenPlayer {
  id: string;
  name: string;
  colorIndex: number;
  archived: boolean;
}

export interface GoldenRules {
  name: string;
  unit: number;
  prices: Pairs;
  enabledTypes: string[];
  laufendeMin: Pairs;
  laufendeMax: Pairs;
  klopfenEnabled: boolean;
  kontraEnabled: boolean;
  retourEnabled: boolean;
  toutFactor: number;
  sieFactor: number;
  sieLaufende: number;
  bock: { enabled: boolean; gamesPerTrigger: number; factor: number; stacking: string; thrownInConsumesBockGame: boolean };
  dealerAdvancesAfterThrowIn: boolean;
  displayAsMoney: boolean;
  payoutRule: string;
}

export type GoldenEntry =
  | {
      kind: "game";
      id: string;
      recordedAt: string;
      type: string;
      suit: string | null;
      declarer: string;
      partner: string | null;
      declarerWon: boolean;
      level: string;
      laufende: number;
      announcement: string;
      klopfer: string[];
      kontra: string;
      kontraBy: string | null;
      retourBy: string | null;
      bockOverride: string;
      declarerEyes: number | null;
      note: string;
    }
  | { kind: "thrown_in"; id: string; recordedAt: string }
  | { kind: "bock"; id: string; recordedAt: string; reason: string; player: string | null; handThrownIn: boolean }
  | { kind: "manual"; id: string; recordedAt: string; amounts: Pairs; note: string };

export interface GoldenEvening {
  id: string;
  startedAt: string;
  finishedAt: string | null;
  location: string;
  seating: string[];
  firstDealer: string;
  rules: GoldenRules;
  entries: GoldenEntry[];
}

/** Ergebnis eines Lesevorgangs: das Model oder die Fehlerart (mit Meldung, wo die App sie selbst festlegt). */
export type Outcome = { model: unknown } | { error: "unreadable"; message?: string } | { error: "newer"; message: string } | { error: "parse" };

/** Unterschied zweier JSON-Bäume: Liste von `[Pfad, neuer Wert]`. */
export type Diff = Array<[Array<string | number>, unknown]>;

/** Ein Lesefall: entweder ein ganzer Text oder eine verfälschte Fassung eines Ausgangstextes (`base`, `at`, `put`). */
export interface ReadCase {
  id: string;
  text?: string;
  base?: string;
  what?: string;
  at?: [number, number];
  put?: string;
  stream?: Outcome;
  streamDiff?: Diff;
  tree?: Diff;
  treeFromBase?: Diff;
}

export interface GoldenCheck {
  evening: GoldenEvening;
  results: Array<{ issues: string[]; bockExponent: number; amounts: Pairs; valueOutOfRange: boolean | null }>;
}

export interface BackupExpect {
  checks: GoldenCheck[];
  preview: unknown;
  import: unknown;
}

export interface BackupCase {
  id: string;
  text?: string;
  base?: string;
  what?: string;
  at?: [number, number];
  put?: string;
  knownPlayers?: GoldenPlayer[];
  knownEvenings?: GoldenEvening[];
  expect?: BackupExpect;
  expectDiff?: Diff;
  /** Was der Import meldet, wenn das Speichern des zweiten Spielers scheitert. */
  failingSecondPlayer?: unknown;
  /** Was der Import meldet, wenn das Speichern des ersten Abends scheitert. */
  failingFirstEvening?: unknown;
}

export interface GoldenBase {
  name: string;
  text: string;
  stream?: Outcome;
  tree?: Diff;
  knownPlayers?: GoldenPlayer[];
  knownEvenings?: GoldenEvening[];
  expect?: BackupExpect;
}

export interface GoldenFile {
  bases: GoldenBase[];
  instantParse: Array<[string, string | null]>;
  instantFormat: Array<[string, number, string]>;
  writeEvenings: Array<{ id: string; model: GoldenEvening; text: string; roundTrip: boolean }>;
  writePlayers: Array<{ id: string; model: GoldenPlayer[]; text: string }>;
  writeRules: Array<{ id: string; model: GoldenRules; text: string }>;
  writeBackups: Array<{
    id: string;
    players: GoldenPlayer[];
    evenings: GoldenEvening[];
    defaultRules: GoldenRules;
    appVersion: string;
    exportedAt: string;
    text: string;
  }>;
  /** Ein Abend mit Zeitpunkten in anderer Schreibweise, gelesen und wieder geschrieben (`null`: unlesbar). */
  rewriteEvenings: Array<{ id: string; text: string; rewritten: string | null }>;
  readEvenings: ReadCase[];
  readRules: ReadCase[];
  readPlayers: ReadCase[];
  readSettings: ReadCase[];
  readBackups: BackupCase[];
  isJsonObject: Array<[string, boolean]>;
  /** Antworttexte von Photon und die Ortsvorschläge, die Kotlin daraus macht, oder `error` (docs/ARCHITEKTUR.md, 4.3). */
  places: PlaceCase[];
}

export interface PlaceCase {
  id: string;
  json: string;
  suggestions?: Array<{ title: string; details: string; text: string }>;
  error?: true;
}

const here = path.dirname(fileURLToPath(import.meta.url));

export function loadGolden(): GoldenFile {
  return JSON.parse(readFileSync(path.join(here, "kotlin-format-golden.json"), "utf8")) as GoldenFile;
}

// ---------- Golden-Form ⇄ Model ----------

function table(pairs: Pairs): Partial<Record<GameType, number>> {
  const result: Partial<Record<GameType, number>> = {};
  for (const [key, value] of pairs) result[key as GameType] = value;
  return result;
}

function pairsOf(record: Readonly<Partial<Record<GameType, number>>>): Pairs {
  return Object.entries(record).map(([key, value]) => [key, value as number]);
}

export function toPlayer(golden: GoldenPlayer): Player {
  return { id: golden.id, name: golden.name, colorIndex: golden.colorIndex, archived: golden.archived };
}

export function fromPlayer(player: Player): GoldenPlayer {
  return { id: player.id, name: player.name, colorIndex: player.colorIndex, archived: player.archived };
}

export function toRules(golden: GoldenRules): RuleSet {
  return {
    name: golden.name,
    unit: golden.unit,
    prices: table(golden.prices),
    enabledTypes: golden.enabledTypes as GameType[],
    laufendeMin: table(golden.laufendeMin),
    laufendeMax: table(golden.laufendeMax),
    klopfenEnabled: golden.klopfenEnabled,
    kontraEnabled: golden.kontraEnabled,
    retourEnabled: golden.retourEnabled,
    toutFactor: golden.toutFactor,
    sieFactor: golden.sieFactor,
    sieLaufende: golden.sieLaufende,
    bock: { ...golden.bock, stacking: golden.bock.stacking as BockRules["stacking"] },
    dealerAdvancesAfterThrowIn: golden.dealerAdvancesAfterThrowIn,
    displayAsMoney: golden.displayAsMoney,
    payoutRule: golden.payoutRule as RuleSet["payoutRule"],
  };
}

export function fromRules(rules: RuleSet): GoldenRules {
  return {
    name: rules.name,
    unit: rules.unit,
    prices: pairsOf(rules.prices),
    enabledTypes: [...rules.enabledTypes],
    laufendeMin: pairsOf(rules.laufendeMin),
    laufendeMax: pairsOf(rules.laufendeMax),
    klopfenEnabled: rules.klopfenEnabled,
    kontraEnabled: rules.kontraEnabled,
    retourEnabled: rules.retourEnabled,
    toutFactor: rules.toutFactor,
    sieFactor: rules.sieFactor,
    sieLaufende: rules.sieLaufende,
    bock: {
      enabled: rules.bock.enabled,
      gamesPerTrigger: rules.bock.gamesPerTrigger,
      factor: rules.bock.factor,
      stacking: rules.bock.stacking,
      thrownInConsumesBockGame: rules.bock.thrownInConsumesBockGame,
    },
    dealerAdvancesAfterThrowIn: rules.dealerAdvancesAfterThrowIn,
    displayAsMoney: rules.displayAsMoney,
    payoutRule: rules.payoutRule,
  };
}

function toEntry(golden: GoldenEntry): EveningEntry {
  switch (golden.kind) {
    case "game":
      return { ...golden, klopfer: [...golden.klopfer] } as EveningEntry;
    case "manual":
      return { kind: "manual", id: golden.id, recordedAt: golden.recordedAt, amounts: new Map(golden.amounts), note: golden.note };
    default:
      return { ...golden } as EveningEntry;
  }
}

function fromEntry(entry: EveningEntry): GoldenEntry {
  switch (entry.kind) {
    case "game":
      return {
        kind: "game",
        id: entry.id,
        recordedAt: entry.recordedAt,
        type: entry.type,
        suit: entry.suit,
        declarer: entry.declarer,
        partner: entry.partner,
        declarerWon: entry.declarerWon,
        level: entry.level,
        laufende: entry.laufende,
        announcement: entry.announcement,
        klopfer: [...entry.klopfer],
        kontra: entry.kontra,
        kontraBy: entry.kontraBy,
        retourBy: entry.retourBy,
        bockOverride: entry.bockOverride,
        declarerEyes: entry.declarerEyes,
        note: entry.note,
      };
    case "thrown_in":
      return { kind: "thrown_in", id: entry.id, recordedAt: entry.recordedAt };
    case "bock":
      return { kind: "bock", id: entry.id, recordedAt: entry.recordedAt, reason: entry.reason, player: entry.player, handThrownIn: entry.handThrownIn };
    case "manual":
      return { kind: "manual", id: entry.id, recordedAt: entry.recordedAt, amounts: [...entry.amounts], note: entry.note };
  }
}

export function toEvening(golden: GoldenEvening): Evening {
  return {
    id: golden.id,
    startedAt: golden.startedAt,
    finishedAt: golden.finishedAt,
    location: golden.location,
    seating: [...golden.seating],
    firstDealer: golden.firstDealer,
    rules: toRules(golden.rules),
    entries: golden.entries.map(toEntry),
  };
}

export function fromEvening(evening: Evening): GoldenEvening {
  return {
    id: evening.id,
    startedAt: evening.startedAt,
    finishedAt: evening.finishedAt,
    location: evening.location,
    seating: [...evening.seating],
    firstDealer: evening.firstDealer,
    rules: fromRules(evening.rules),
    entries: evening.entries.map(fromEntry),
  };
}

export function previewForm(preview: ImportPreview): unknown {
  return {
    error: preview.error === null ? null : { kind: preview.error.kind, message: preview.error.message },
    exportedAt: preview.exportedAt,
    appVersion: preview.appVersion,
    newPlayers: preview.newPlayers.map(fromPlayer),
    sameNamePlayers: preview.sameNamePlayers.map(fromPlayer),
    existingPlayerCount: preview.existingPlayerCount,
    differingPlayers: preview.differingPlayers.map(fromPlayer),
    newEvenings: preview.newEvenings.map(fromEvening),
    existingEvenings: preview.existingEvenings.map(fromEvening),
    invalidEvenings: preview.invalidEvenings.map((invalid) => ({
      id: invalid.id,
      startedAt: invalid.startedAt,
      problem: invalid.problem,
      reason: invalid.reason,
    })),
    defaultRules: preview.defaultRules === null ? null : fromRules(preview.defaultRules),
  };
}

export function importForm(result: ImportResult): unknown {
  return {
    error: result.error === null ? null : result.error.kind,
    newPlayers: result.newPlayers.map((player) => player.id),
    sameNamePlayers: result.sameNamePlayers.map((player) => player.id),
    existingPlayerCount: result.existingPlayerCount,
    newEvenings: result.newEvenings.map((evening) => evening.id),
    existingEvenings: result.existingEvenings.map((evening) => evening.id),
    invalidEvenings: result.invalidEvenings.map((invalid) => invalid.id),
  };
}

/**
 * Das Regelwerk so, wie es nach Schreiben und Lesen aussieht: Kotlin schreibt Tabellen und Spielarten in der Reihenfolge
 * der Aufzählung (beim Vergleich zweier Regelwerke spielt die Reihenfolge in Kotlin keine Rolle).
 */
export function sortedRules(rules: GoldenRules): GoldenRules {
  const order = (type: string): number => GAME_TYPES.indexOf(type as GameType);
  const sortPairs = (pairs: Pairs): Pairs => [...pairs].sort((a, b) => order(a[0]) - order(b[0]));
  return {
    ...rules,
    prices: sortPairs(rules.prices),
    enabledTypes: [...rules.enabledTypes].sort((a, b) => order(a) - order(b)),
    laufendeMin: sortPairs(rules.laufendeMin),
    laufendeMax: sortPairs(rules.laufendeMax),
  };
}

export function sortedEvening(evening: GoldenEvening): GoldenEvening {
  return { ...evening, rules: sortedRules(evening.rules) };
}

// ---------- Ergebnisse und Unterschiede ----------

/** Das Ergebnis eines Lesevorgangs in derselben Form wie in der Golden-Datei. */
export function outcome(read: () => unknown): Outcome {
  try {
    return { model: read() };
  } catch (error) {
    if (error instanceof NewerSchemaError) return { error: "newer", message: error.message };
    if (error instanceof UnreadableDataError) {
      // Nur die eigenen Meldungen der App (ohne Ursache) sind festgelegt, nicht die der JSON-Bibliothek.
      return error.cause === undefined ? { error: "unreadable", message: error.message } : { error: "unreadable" };
    }
    throw error;
  }
}

function clone<T>(value: T): T {
  return structuredClone(value);
}

/** Wendet einen Unterschied aus der Golden-Datei auf einen Ausgangswert an. */
export function applyDiff<T>(base: T, diff: Diff | undefined): T {
  let root: unknown = clone(base);
  for (const [pathParts, value] of diff ?? []) {
    if (pathParts.length === 0) {
      root = clone(value);
      continue;
    }
    let target = root as Record<string | number, unknown>;
    for (const part of pathParts.slice(0, -1)) target = target[part] as Record<string | number, unknown>;
    target[pathParts[pathParts.length - 1]] = clone(value);
  }
  return root as T;
}

/** Der Text eines Falls: ganz angegeben oder als verfälschte Fassung seines Ausgangstextes. */
export function caseText(item: { text?: string; base?: string; at?: [number, number]; put?: string }, bases: ReadonlyMap<string, GoldenBase>): string {
  if (item.text !== undefined) return item.text;
  const base = bases.get(item.base ?? "");
  if (base === undefined || item.at === undefined || item.put === undefined) throw new Error(`Fall ohne Text und ohne Ausgangstext`);
  return base.text.substring(0, item.at[0]) + item.put + base.text.substring(item.at[1]);
}

/** Sammelt Abweichungen und zeigt die ersten wenigen lesbar an, statt tausender Einzelprüfungen. */
export class Mismatches {
  readonly first: string[] = [];
  count = 0;
  checked = 0;

  check(id: string, actual: unknown, expected: unknown, input?: string): void {
    this.checked++;
    if (isDeepStrictEqual(actual, expected)) return;
    this.count++;
    if (this.first.length < 5) {
      const show = (value: unknown): string => {
        const json = JSON.stringify(value);
        return json === undefined ? String(value) : json.length > 700 ? `${json.substring(0, 700)}…` : json;
      };
      this.first.push(
        `${id}${input === undefined ? "" : `\n  Eingabe: ${show(input.length > 300 ? `${input.substring(0, 300)}…` : input)}`}` +
          `\n  TypeScript: ${show(actual)}\n  Kotlin:     ${show(expected)}`,
      );
    }
  }

  /** Text für die Fehlermeldung des Tests. */
  get summary(): string {
    return `${this.count} von ${this.checked} Fällen weichen ab`;
  }
}

/**
 * Eine Testumsetzung der Prüf-Schnittstelle: Sie nimmt die Rechenergebnisse, die Kotlin für genau diesen Abend
 * ausgerechnet hat, aus der Golden-Datei. Fragt der Port nach einem Abend, den Kotlin nicht durchgerechnet hätte, ist
 * das ein Fehler.
 */
export function goldenChecker(checks: readonly GoldenCheck[]): EveningChecker {
  const lookup = (evening: Evening): GoldenCheck => {
    const form = fromEvening(evening);
    const found = checks.find((check) => isDeepStrictEqual(check.evening, form));
    if (found === undefined) throw new Error(`Keine Rechenergebnisse aus Kotlin für den Abend „${evening.id}“`);
    return found;
  };
  return {
    computeResults: (evening) =>
      lookup(evening).results.map((result) => ({ issues: result.issues, bockExponent: result.bockExponent, amounts: new Map(result.amounts) })),
    isValueOutOfRange: (evening, entry, bockExponent) => {
      const check = lookup(evening);
      const result = check.results[evening.entries.indexOf(entry)];
      if (result === undefined || result.bockExponent !== bockExponent || result.valueOutOfRange === null) {
        throw new Error(`Unerwartete Frage nach dem Spielwert im Abend „${evening.id}“`);
      }
      return result.valueOutOfRange;
    },
  };
}
