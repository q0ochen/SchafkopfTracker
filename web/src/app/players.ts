/* Spieler der Runde: Standard-Runde und Namensregeln wie in der Android-App. */
import type { Player } from "../core/model";

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
