/* Spieler der Runde: Standard-Runde, Namensregeln, Farben und Löschen wie in der Android-App. */
import type { Evening, Player, PlayerId } from "../core/model";

export const MAX_NAME_LENGTH = 24;

/**
 * Die feste Vierer-Runde mit denselben Kennungen wie in der Android-App (`SchafkopfApp.DEFAULT_PLAYERS`), damit
 * Sicherungen von verschiedenen Geräten dieselben Spieler meinen. Die echten Namen trägt die Runde in der App ein.
 */
export function defaultPlayers(): Player[] {
  return [1, 2, 3, 4].map((n) => ({ id: `spieler-${n}`, name: `Spieler ${n}`, colorIndex: n - 1, archived: false }));
}

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

/**
 * Ob der Spieler in diesem Abend vorkommt: am Tisch, als Geber oder in irgendeinem Eintrag (Android:
 * `Evening.involves`). Solche Spieler dürfen nicht gelöscht werden, sonst verlöre der Abend seine Namen und ließe
 * sich nicht mehr sichern und einlesen.
 */
export function eveningInvolves(evening: Evening, player: PlayerId): boolean {
  if (evening.seating.includes(player) || evening.firstDealer === player) return true;
  return evening.entries.some((entry) => {
    switch (entry.kind) {
      case "game":
        return (
          entry.declarer === player ||
          entry.partner === player ||
          entry.klopfer.includes(player) ||
          entry.kontraBy === player ||
          entry.retourBy === player
        );
      case "bock":
        return entry.player === player;
      case "manual":
        return entry.amounts.has(player);
      case "thrown_in":
        return false;
    }
  });
}

/** In wie vielen Abenden der Spieler vorkommt. */
export function eveningCount(evenings: readonly Evening[], player: PlayerId): number {
  return evenings.filter((evening) => eveningInvolves(evening, player)).length;
}
