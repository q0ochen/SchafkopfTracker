/* Spieler der Runde: Standard-Runde, Namensregeln, Farben und Löschen wie in der Android-App. */
import { involves } from "../core/evening/Evening";
import type { Evening, Player, PlayerId } from "../core/model";

// Die Regeln selbst stehen im Kern (auch der Abgleich der gemeinsamen Runde braucht sie).
export { cleanName, COLOR_COUNT, MAX_NAME_LENGTH, nextColorIndex } from "../core/players";

/**
 * Die feste Vierer-Runde mit denselben Kennungen wie in der Android-App (`SchafkopfApp.DEFAULT_PLAYERS`), damit
 * Sicherungen von verschiedenen Geräten dieselben Spieler meinen. Die echten Namen trägt die Runde in der App ein.
 */
export function defaultPlayers(): Player[] {
  return [1, 2, 3, 4].map((n) => ({ id: `spieler-${n}`, name: `Spieler ${n}`, colorIndex: n - 1, archived: false }));
}

/**
 * Ob der Spieler in diesem Abend vorkommt: am Tisch, als Geber oder in irgendeinem Eintrag (Android:
 * `Evening.involves`). Solche Spieler dürfen nicht gelöscht werden, sonst verlöre der Abend seine Namen und ließe
 * sich nicht mehr sichern und einlesen.
 */
export function eveningInvolves(evening: Evening, player: PlayerId): boolean {
  return involves(evening, player);
}

/** In wie vielen Abenden der Spieler vorkommt. */
export function eveningCount(evenings: readonly Evening[], player: PlayerId): number {
  return evenings.filter((evening) => involves(evening, player)).length;
}
