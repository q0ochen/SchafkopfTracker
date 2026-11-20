/*
 * Inhaltliche Gleichheit wie `==` auf den Datenklassen des Kotlin-Kerns. In Kotlin vergleicht `equals` jedes Feld:
 * Listen in ihrer Reihenfolge, Mengen (`klopfer`, `enabledTypes`) und Maps (`amounts`, die Tabellen je Spielart) ohne
 * Reihenfolge, Zeitpunkte als Zeit. Genau das tun diese Funktionen für die Daten aus `model.ts`.
 *
 * Der Abgleich der gemeinsamen Runde hängt daran: Ein Abend kommt vom Server mit anderer Reihenfolge der Schlüssel
 * zurück (dort liegt er als `jsonb`) und muss trotzdem als „unverändert“ gelten.
 *
 * Jede Gleichheit nennt alle Felder ihres Typs; fehlt nach einer Erweiterung von `model.ts` eines, übersetzt die
 * Datei nicht mehr.
 */
import { compareInstants } from "../instant";
import type {
  BockRules,
  BockTriggerEntry,
  Evening,
  EveningEntry,
  GameEntry,
  GameType,
  InstantText,
  ManualEntry,
  Player,
  RuleSet,
  ThrownInEntry,
} from "../model";

type Same<V> = (a: V, b: V) => boolean;

/** Je Feld eines Typs die passende Gleichheit; ein fehlendes Feld ist ein Übersetzungsfehler. */
type FieldEquality<T> = { readonly [K in keyof T]-?: Same<T[K]> };

function sameBy<T extends object>(fields: FieldEquality<T>): Same<T> {
  const keys = Object.keys(fields) as Array<keyof T>;
  return (a, b) => a === b || keys.every((key) => fields[key](a[key], b[key]));
}

/** Für Texte, Zahlen, Wahrheitswerte, Aufzählungen und `null`. */
function exact<V extends string | number | boolean | null>(a: V, b: V): boolean {
  return a === b;
}

/** Zwei Zeitpunkte sind gleich, wenn sie denselben Augenblick meinen – auch in verschiedener Schreibweise. */
export function sameInstant(a: InstantText, b: InstantText): boolean {
  if (a === b) return true;
  try {
    return compareInstants(a, b) === 0;
  } catch {
    // Kein Zeitpunkt (das kommt in gelesenen Daten nicht vor): dann zählt nur der gleiche Text.
    return false;
  }
}

function sameInstantOrNull(a: InstantText | null, b: InstantText | null): boolean {
  return a === null || b === null ? a === b : sameInstant(a, b);
}

/** Listen sind gleich, wenn sie dieselben Elemente in derselben Reihenfolge haben. */
function sameList<V>(same: Same<V>): Same<readonly V[]> {
  return (a, b) => a === b || (a.length === b.length && a.every((item, index) => same(item, b[index])));
}

/** Mengen (in `model.ts` als Listen ohne doppelte Werte) sind gleich, wenn sie dieselben Elemente haben. */
function sameSet<V>(a: readonly V[], b: readonly V[]): boolean {
  if (a === b) return true;
  const left = new Set(a);
  const right = new Set(b);
  if (left.size !== right.size) return false;
  for (const item of left) if (!right.has(item)) return false;
  return true;
}

/** Eine Tabelle je Spielart (Kotlin: `Map<GameType, Int>`): gleiche Spielarten mit gleichen Werten. */
function sameTable(a: Readonly<Partial<Record<GameType, number>>>, b: Readonly<Partial<Record<GameType, number>>>): boolean {
  if (a === b) return true;
  const keys = (table: Readonly<Partial<Record<GameType, number>>>) =>
    (Object.keys(table) as GameType[]).filter((key) => table[key] !== undefined);
  const left = keys(a);
  if (left.length !== keys(b).length) return false;
  return left.every((key) => a[key] === b[key]);
}

/** Die Beträge einer freien Buchung (Kotlin: `Map<PlayerId, Int>`): gleiche Spieler mit gleichen Beträgen. */
function sameAmounts(a: ReadonlyMap<string, number>, b: ReadonlyMap<string, number>): boolean {
  if (a === b) return true;
  if (a.size !== b.size) return false;
  for (const [player, amount] of a) if (!b.has(player) || b.get(player) !== amount) return false;
  return true;
}

const sameBock = sameBy<BockRules>({
  enabled: exact,
  gamesPerTrigger: exact,
  factor: exact,
  stacking: exact,
  thrownInConsumesBockGame: exact,
});

/** Ob zwei Regelwerke in allem übereinstimmen (Kotlin: `RuleSet ==`). */
export const sameRules: Same<RuleSet> = sameBy<RuleSet>({
  name: exact,
  unit: exact,
  prices: sameTable,
  enabledTypes: sameSet,
  laufendeMin: sameTable,
  laufendeMax: sameTable,
  klopfenEnabled: exact,
  kontraEnabled: exact,
  retourEnabled: exact,
  toutFactor: exact,
  sieFactor: exact,
  sieLaufende: exact,
  bock: sameBock,
  dealerAdvancesAfterThrowIn: exact,
  displayAsMoney: exact,
  payoutRule: exact,
});

/** Wie {@link sameRules}, auch für ein fehlendes Regelwerk. */
export function sameRulesOrNull(a: RuleSet | null, b: RuleSet | null): boolean {
  return a === null || b === null ? a === b : sameRules(a, b);
}

const sameGame = sameBy<GameEntry>({
  kind: exact,
  id: exact,
  recordedAt: sameInstant,
  type: exact,
  suit: exact,
  declarer: exact,
  partner: exact,
  declarerWon: exact,
  level: exact,
  laufende: exact,
  announcement: exact,
  klopfer: sameSet,
  kontra: exact,
  kontraBy: exact,
  retourBy: exact,
  bockOverride: exact,
  declarerEyes: exact,
  note: exact,
});

const sameThrownIn = sameBy<ThrownInEntry>({ kind: exact, id: exact, recordedAt: sameInstant });

const sameBockTrigger = sameBy<BockTriggerEntry>({
  kind: exact,
  id: exact,
  recordedAt: sameInstant,
  reason: exact,
  player: exact,
  handThrownIn: exact,
});

const sameManual = sameBy<ManualEntry>({ kind: exact, id: exact, recordedAt: sameInstant, amounts: sameAmounts, note: exact });

/** Ob zwei Einträge in allen Feldern übereinstimmen (Kotlin: `EveningEntry ==`). Verschiedene Arten sind nie gleich. */
export function sameEntry(a: EveningEntry, b: EveningEntry): boolean {
  if (a === b) return true;
  switch (a.kind) {
    case "game":
      return b.kind === "game" && sameGame(a, b);
    case "thrown_in":
      return b.kind === "thrown_in" && sameThrownIn(a, b);
    case "bock":
      return b.kind === "bock" && sameBockTrigger(a, b);
    case "manual":
      return b.kind === "manual" && sameManual(a, b);
  }
}

/** Ob zwei Abende in allem übereinstimmen: Kopf, Regelwerk und alle Einträge in ihrer Reihenfolge (Kotlin: `Evening ==`). */
export const sameEvening: Same<Evening> = sameBy<Evening>({
  id: exact,
  startedAt: sameInstant,
  finishedAt: sameInstantOrNull,
  location: exact,
  seating: sameList<string>(exact),
  firstDealer: exact,
  rules: sameRules,
  entries: sameList(sameEntry),
});

/** Wie {@link sameEvening}, auch für einen fehlenden Abend. */
export function sameEveningOrNull(a: Evening | null, b: Evening | null): boolean {
  return a === null || b === null ? a === b : sameEvening(a, b);
}

/** Ob zwei Spieler in allem übereinstimmen (Kotlin: `Player ==`). */
export const samePlayer: Same<Player> = sameBy<Player>({ id: exact, name: exact, colorIndex: exact, archived: exact });

/** Die Gleichheiten der Kopf-Felder eines Abends, einzeln (für das Zusammenführen je Feld). */
export const sameHeaderField = {
  startedAt: sameInstant,
  finishedAt: sameInstantOrNull,
  location: exact as Same<string>,
  seating: sameList<string>(exact),
  firstDealer: exact as Same<string>,
  rules: sameRules,
} satisfies { readonly [K in "startedAt" | "finishedAt" | "location" | "seating" | "firstDealer" | "rules"]: Same<Evening[K]> };
