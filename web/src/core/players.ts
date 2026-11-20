/*
 * Regeln für Spieler wie im Kotlin-Kern (`domain/model/Player.kt`): Namen bereinigen, Farbe für neue Spieler.
 * Reine Funktionen; die Standard-Runde und alles, was den Zustand der App braucht, steht in `app/players.ts`.
 */
import type { Player } from "./model";

/** Längster erlaubter Name (Kotlin: `Player.MAX_NAME_LENGTH`). */
export const MAX_NAME_LENGTH = 24;

/**
 * Bereinigt eine Namenseingabe wie die Android-App (`Player.cleanName`): Leerraum am Rand weg, Leerraum innen zu
 * einem Leerzeichen. `null`, wenn der Name leer oder länger als 24 Zeichen ist.
 */
export function cleanName(input: string): string | null {
  // Wie Javas „\s“: nur die ASCII-Leerzeichen.
  const name = input.trim().replace(/[ \t\n\v\f\r]+/g, " ");
  return name.length > 0 && name.length <= MAX_NAME_LENGTH ? name : null;
}

/** So viele Spielerfarben hat die Oberfläche; danach wiederholen sie sich (Android: `Player.COLOR_COUNT`). */
export const COLOR_COUNT = 8;

/**
 * Die Farbe für einen neuen Spieler: die erste, die kein aktiver (nicht archivierter) Spieler trägt; sind alle
 * vergeben, die nächste Nummer nach der höchsten (Android: `Player.nextColorIndex`).
 */
export function nextColorIndex(existing: readonly Player[]): number {
  const taken = new Set(existing.filter((it) => !it.archived).map((it) => ((it.colorIndex % COLOR_COUNT) + COLOR_COUNT) % COLOR_COUNT));
  for (let index = 0; index < COLOR_COUNT; index++) if (!taken.has(index)) return index;
  return Math.max(-1, ...existing.map((it) => it.colorIndex)) + 1;
}
