/* Hilfen für Tests der Oberfläche: ein Abend mit den Standardregeln und feste Kennungen und Zeitpunkte. */
import type { Evening, EveningEntry, GameEntry } from "../core/model";
import { RuleSets } from "../core/rules/RuleSet";
import type { EntryFactory } from "./gameForm";

export const SEATING = ["spieler-1", "spieler-2", "spieler-3", "spieler-4"] as const;

export function testEvening(entries: EveningEntry[] = [], overrides: Partial<Evening> = {}): Evening {
  return {
    id: "abend-1",
    // Mittags in UTC: In jeder Zeitzone der Testrechner bleibt es der 14. November.
    startedAt: "2026-11-14T12:00:00Z",
    finishedAt: null,
    location: "",
    seating: [...SEATING],
    firstDealer: "spieler-1",
    rules: RuleSets.standard(),
    entries,
    ...overrides,
  };
}

export function game(id: string, fields: Partial<GameEntry> = {}): GameEntry {
  return {
    kind: "game",
    id,
    recordedAt: "2026-11-14T19:05:00Z",
    type: "RUFSPIEL",
    suit: null,
    declarer: "spieler-1",
    partner: "spieler-3",
    declarerWon: true,
    level: "NORMAL",
    laufende: 0,
    announcement: "NONE",
    klopfer: [],
    kontra: "NONE",
    kontraBy: null,
    retourBy: null,
    bockOverride: "AUTO",
    declarerEyes: null,
    note: "",
    ...fields,
  };
}

/** Liefert der Reihe nach „id-1“, „id-2“ … und eine feste Uhrzeit. */
export function fixedFactory(): EntryFactory {
  let next = 0;
  return {
    newId: () => `id-${++next}`,
    now: () => "2026-11-14T20:00:00Z",
  };
}
