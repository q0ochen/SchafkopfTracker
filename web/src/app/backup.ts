/*
 * Sicherung speichern und einlesen. Das Dateiformat ist das der Android-App (`data/backup/BackupFormat.kt`,
 * portiert in `data/format`): Eine Sicherung vom iPhone lässt sich in der Android-App einlesen und umgekehrt.
 *
 * Der Import führt zusammen und löscht nichts (wie `BackupService.import` der Android-App): Spieler und Abende,
 * die es noch nicht gibt, kommen dazu; was es schon gibt, bleibt. Zusätzlich bietet die Web-App an, abweichende
 * Namen schon vorhandener Spieler aus der Sicherung zu übernehmen – das braucht es beim Umzug auf ein neues Gerät,
 * wo die vier vorbelegten Spieler noch „Spieler 1“ bis „Spieler 4“ heißen.
 */
import type { Evening, InstantText, Player, RuleSet } from "../core/model";
import { instantParts } from "../core/instant";

/** Was eine Sicherung beim Einlesen bewirken würde (Felder wie `ImportPreview` der Android-App). */
export interface ImportPreview {
  readonly error: { readonly kind: string; readonly message: string } | null;
  readonly exportedAt: InstantText | null;
  readonly appVersion: string;
  readonly newPlayers: readonly Player[];
  readonly sameNamePlayers: readonly Player[];
  readonly existingPlayerCount: number;
  readonly differingPlayers: readonly Player[];
  readonly newEvenings: readonly Evening[];
  readonly existingEvenings: readonly Evening[];
  readonly invalidEvenings: ReadonlyArray<{ readonly id: string; readonly reason: string }>;
  readonly defaultRules: RuleSet | null;
}

/** Schreiben und Lesen von Sicherungen (umgesetzt mit dem portierten Dateiformat). */
export interface BackupPort {
  write(players: readonly Player[], evenings: readonly Evening[], defaultRules: RuleSet, appVersion: string, exportedAt: InstantText): string;
  read(text: string, knownPlayers: readonly Player[], knownEvenings: readonly Evening[]): ImportPreview;
}

/** Dateiname der Sicherung, zum Beispiel „schafkopf-sicherung-2026-11-15.json“ (Datum in UTC). */
export function backupFileName(now: InstantText): string {
  const day = /^(\d{4}-\d{2}-\d{2})T/.exec(now)?.[1];
  if (day !== undefined) {
    // Nur ein gültiger Zeitpunkt ergibt ein Datum im Namen.
    try {
      instantParts(now);
      return `schafkopf-sicherung-${day}.json`;
    } catch {
      // fällt auf den Namen ohne Datum zurück
    }
  }
  return "schafkopf-sicherung.json";
}

/** Was beim Einlesen gespeichert wird. */
export interface ImportPlan {
  /** Neue Spieler und – auf Wunsch – vorhandene Spieler in der Fassung der Sicherung. */
  readonly players: readonly Player[];
  /** Nur neue, gültige Abende; vorhandene bleiben, wie sie sind. */
  readonly evenings: readonly Evening[];
  /** Die Regel-Vorlage der Sicherung, falls übernommen werden soll. */
  readonly defaultRules: RuleSet | null;
}

export function importPlan(preview: ImportPreview, options: { takeNames: boolean; takeRules: boolean }): ImportPlan {
  return {
    players: options.takeNames ? [...preview.newPlayers, ...preview.differingPlayers] : [...preview.newPlayers],
    evenings: [...preview.newEvenings],
    defaultRules: options.takeRules ? preview.defaultRules : null,
  };
}

/** Ob die Vorschau überhaupt etwas zu übernehmen anbietet. */
export function hasAnythingToImport(preview: ImportPreview): boolean {
  return (
    preview.error === null &&
    (preview.newPlayers.length > 0 || preview.newEvenings.length > 0 || preview.differingPlayers.length > 0 || preview.defaultRules !== null)
  );
}

function count(n: number, one: string, other: string): string {
  return `${n} ${n === 1 ? one : other}`;
}

/** Die Vorschau in einfachen Sätzen für den Bestätigungsdialog. */
export function importSummary(preview: ImportPreview): string[] {
  if (preview.error !== null) return [preview.error.message];
  const lines: string[] = [];
  lines.push(
    preview.newEvenings.length > 0 ? `${count(preview.newEvenings.length, "neuer Abend kommt", "neue Abende kommen")} dazu.` : "Keine neuen Abende.",
  );
  if (preview.existingEvenings.length > 0) {
    lines.push(`${count(preview.existingEvenings.length, "Abend ist schon da und bleibt, wie er ist", "Abende sind schon da und bleiben, wie sie sind")}.`);
  }
  if (preview.invalidEvenings.length > 0) {
    lines.push(`${count(preview.invalidEvenings.length, "Abend wird", "Abende werden")} ausgelassen:`);
    for (const invalid of preview.invalidEvenings) lines.push(`– ${invalid.reason}`);
  }
  if (preview.newPlayers.length > 0) {
    lines.push(`${count(preview.newPlayers.length, "neuer Spieler kommt", "neue Spieler kommen")} dazu: ${preview.newPlayers.map((it) => it.name).join(", ")}.`);
  }
  if (preview.sameNamePlayers.length > 0) {
    lines.push(
      `Achtung: ${preview.sameNamePlayers.map((it) => it.name).join(", ")} ${preview.sameNamePlayers.length === 1 ? "heißt" : "heißen"} wie ein vorhandener Spieler, ist aber ein anderer und wird zusätzlich angelegt.`,
    );
  }
  return lines;
}
