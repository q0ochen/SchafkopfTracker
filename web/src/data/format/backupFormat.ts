/*
 * Die Sicherungsdatei (Kotlin: BackupFormat, BackupModels und die Übernahme aus BackupService): schreiben, lesen mit
 * Vorschau und die Entscheidung, was ein Import speichert. Reine Funktionen ohne Zugriff auf einen Speicher.
 *
 * Eine Sicherung der Web-App liest die Android-App und umgekehrt; dafür ist die Datei Zeichen für Zeichen dieselbe,
 * und die Prüfungen und deutschen Meldungen beim Lesen sind dieselben.
 */
import type { Evening, EveningEntry, GameEntry, GameType, InstantText, Player, PlayerId, RuleSet } from "../../core/model";
import { compareInstants } from "../../core/instant";
import { JsonFormatError, NewerSchemaError, UnreadableDataError } from "./errors";
import { canonicalInstant, tryParseInstant } from "./instantText";
import { EMPTY_ARRAY, type JsonElement, type JsonObjectElement, parseToJsonElement, textOf } from "./jsonTree";
import { KotlinxLexer } from "./kotlinxLexer";
import { javaEqualsIgnoreCase, kotlinIsBlank, kotlinTrim } from "./kotlinText";
import { eveningOut, playerOut, rulesOut } from "./mappers";
import { OutObject, writeJson } from "./schemaCodec";
import { decodeEveningElement, decodePlayerList, decodeRulesElement, withoutByteOrderMark } from "./storageCodec";

/** Kennzeichen im Kopf der Datei, an dem die App ihre eigenen Sicherungen erkennt. */
export const BACKUP_FORMAT_NAME = "schafkopf-tracker-backup";

/** Version des Aufbaus der Sicherungsdatei. */
export const BACKUP_FORMAT_VERSION = 1;

/** Warum eine Sicherung nicht gelesen oder nicht vollständig eingespielt werden konnte (Kotlin: `BackupErrorKind`). */
export type BackupErrorKind = "INVALID_JSON" | "WRONG_FORMAT" | "NEWER_VERSION" | "STORAGE_FAILED";

/** Ein Fehler beim Lesen oder Einspielen einer Sicherung; `message` ist ein fertiger deutscher Satz für die Anzeige. */
export interface BackupError {
  readonly kind: BackupErrorKind;
  readonly message: string;
}

/** Warum ein einzelner Abend aus der Sicherung nicht übernommen wird (Kotlin: `EveningProblem`). */
export type EveningProblem =
  | "UNREADABLE"
  | "NEWER_VERSION"
  | "DUPLICATE_ID"
  | "WRONG_SEATING"
  | "DEALER_NOT_AT_TABLE"
  | "UNKNOWN_PLAYER"
  | "MANUAL_NOT_ZERO_SUM"
  | "GAME_NOT_ZERO_SUM"
  | "AMOUNT_OUT_OF_RANGE"
  | "DUPLICATE_ENTRY_ID";

/** Befund zu einem Abend, der so nicht übernommen werden kann; `reason` ist ein verständlicher deutscher Satz. */
export interface EveningFinding {
  readonly problem: EveningProblem;
  readonly reason: string;
}

/**
 * Ein Abend aus der Sicherung, der nicht übernommen wird.
 *
 * @property id Kennung des Abends, soweit sie lesbar war; sonst leer.
 * @property startedAt Beginn des Abends, soweit er lesbar war.
 */
export interface InvalidEvening extends EveningFinding {
  readonly id: string;
  readonly startedAt: InstantText | null;
}

/**
 * Was ein Import bewirken würde, ohne dass etwas gespeichert wird (Kotlin: `ImportPreview`).
 *
 * - `error`: gesetzt, wenn die Datei gar nicht als Sicherung lesbar ist; alle Listen sind dann leer.
 * - `newPlayers`: Spieler, die angelegt würden; `sameNamePlayers` der Teil davon, der so heißt wie ein schon
 *   vorhandener Spieler mit anderer Kennung (gleich bis auf Groß- und Kleinschreibung und Leerraum am Rand).
 * - `existingPlayerCount`: Spieler der Sicherung, die es schon gibt; sie bleiben, wie sie sind. `differingPlayers`:
 *   der Teil davon, der in der Sicherung anders heißt oder aussieht, in der Fassung der Sicherung.
 * - `newEvenings`, `existingEvenings` (in der Fassung des Geräts), `invalidEvenings` (mit Grund).
 * - `defaultRules`: die Vorlage des Regelwerks aus der Sicherung; `null`, wenn sie fehlt oder unlesbar ist. Der Import
 *   übernimmt sie nicht.
 */
export interface ImportPreview {
  readonly error: BackupError | null;
  readonly exportedAt: InstantText | null;
  readonly appVersion: string;
  readonly newPlayers: readonly Player[];
  readonly sameNamePlayers: readonly Player[];
  readonly existingPlayerCount: number;
  readonly differingPlayers: readonly Player[];
  readonly newEvenings: readonly Evening[];
  readonly existingEvenings: readonly Evening[];
  readonly invalidEvenings: readonly InvalidEvening[];
  readonly defaultRules: RuleSet | null;
}

/** `true`, wenn die Datei eine lesbare Sicherung ist. */
export function isReadable(preview: ImportPreview): boolean {
  return preview.error === null;
}

/** `true`, wenn ein Import etwas hinzufügen würde. */
export function hasNewData(preview: ImportPreview): boolean {
  return preview.newPlayers.length > 0 || preview.newEvenings.length > 0;
}

/**
 * Ergebnis eines Eintrags im Verlauf des Abends, soweit die Prüfung es braucht (Kotlin: `EntryResult`).
 *
 * - `issues`: die Namen der `EntryIssue`-Werte; ausgewertet wird nur „BALANCE_OUT_OF_RANGE“.
 * - `bockExponent`: 0 = kein Bockspiel, 1 = Bockspiel, 2 = zwei überlagerte Bockrunden.
 * - `amounts`: Betrag je Spieler aus diesem Eintrag in Cent.
 */
export interface CheckedEntryResult {
  readonly issues: readonly string[];
  readonly bockExponent: number;
  readonly amounts: ReadonlyMap<PlayerId, number>;
}

/**
 * Was die Prüfung eines Abends vom Rechenkern braucht (Kotlin: `EveningCalculator` und `ScoreCalculator`). Aufgerufen
 * wird beides nur für Abende mit gültiger Sitzordnung: genau vier verschiedene Spieler, jeder mit Kennung, und der
 * erste Geber sitzt am Tisch.
 */
export interface EveningChecker {
  /** Kotlin: `EveningCalculator.compute(evening).results` – genau ein Ergebnis je Eintrag, in derselben Reihenfolge. */
  computeResults(evening: Evening): readonly CheckedEntryResult[];
  /**
   * Kotlin: `RuleViolation.VALUE_OUT_OF_RANGE in ScoreCalculator.validate(entry.toFacts(evening.seating, bockExponent),
   * evening.rules)` – ob sich mit dem Spielwert dieses Spiels nicht rechnen lässt (negativ oder größer als `MAX_VALUE`).
   */
  isValueOutOfRange(evening: Evening, entry: GameEntry, bockExponent: number): boolean;
}

// ---------- Schreiben ----------

/**
 * Die Sicherung als JSON-Text (Kotlin: `BackupFormat.write`): Kopf, alle Spieler, alle Abende vom frühesten Beginn an
 * (gleich frühe in ihrer bisherigen Reihenfolge) und die Vorlage des Regelwerks, eingerückt mit vier Leerzeichen.
 * Die Einstellungen der KI-Zusammenfassung und Schlüssel stehen nie darin.
 *
 * @param exportedAt Zeitpunkt der Sicherung (zum Beispiel `instantFromEpochMillis(Date.now())`).
 */
export function writeBackup(
  players: readonly Player[],
  evenings: readonly Evening[],
  defaultRules: RuleSet,
  appVersion: string,
  exportedAt: InstantText,
): string {
  const sorted = evenings
    .map((evening) => ({ evening, startedAt: canonicalInstant(evening.startedAt) }))
    .sort((a, b) => compareInstants(a.startedAt, b.startedAt))
    .map(({ evening }) => evening);
  return writeJson(
    new OutObject([
      ["format", BACKUP_FORMAT_NAME],
      ["schemaVersion", BACKUP_FORMAT_VERSION],
      ["appVersion", appVersion],
      ["exportedAt", canonicalInstant(exportedAt)],
      ["players", players.map(playerOut)],
      ["evenings", sorted.map(eveningOut)],
      ["defaultRules", rulesOut(defaultRules)],
    ]),
    true,
  );
}

// ---------- Lesen ----------

function failure(kind: BackupErrorKind, message: string): ImportPreview {
  return {
    error: { kind, message },
    exportedAt: null,
    appVersion: "",
    newPlayers: [],
    sameNamePlayers: [],
    existingPlayerCount: 0,
    differingPlayers: [],
    newEvenings: [],
    existingEvenings: [],
    invalidEvenings: [],
    defaultRules: null,
  };
}

const notJson = (): ImportPreview => failure("INVALID_JSON", "Die Datei lässt sich nicht lesen. Sie ist beschädigt oder keine Sicherung.");

const unreadablePlayers = (): ImportPreview =>
  failure("WRONG_FORMAT", "Die Sicherung ist beschädigt: Die Liste der Spieler lässt sich nicht lesen.");

/** Zwei Namen gelten als gleich, wenn sie sich nur in Groß- und Kleinschreibung oder Leerraum am Rand unterscheiden. */
function sameName(first: string, second: string): boolean {
  return javaEqualsIgnoreCase(kotlinTrim(first), kotlinTrim(second));
}

function samePlayer(first: Player, second: Player): boolean {
  return first.id === second.id && first.name === second.name && first.colorIndex === second.colorIndex && first.archived === second.archived;
}

/** Kotlin: `(element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull`. */
function versionOf(element: JsonElement): number | null {
  if (element.kind === "object" || element.kind === "array") return null;
  if (element.kind === "literal" && element.isString) return null;
  const content = element.kind === "null" ? "null" : element.content;
  try {
    const value = new KotlinxLexer(content).consumeNumericLiteralFully();
    return value >= -2_147_483_648n && value <= 2_147_483_647n ? Number(value) : null;
  } catch (error) {
    if (error instanceof JsonFormatError) return null;
    throw error;
  }
}

/** Der Zeitpunkt, nur für die Anzeige; `null`, wenn der Text fehlt oder kein brauchbarer Zeitpunkt ist. */
function instantOrNull(text: string | null): InstantText | null {
  return text === null ? null : tryParseInstant(text);
}

/**
 * Liest eine Sicherung und vergleicht sie mit dem, was es auf dem Gerät schon gibt (Kotlin: `BackupFormat.read`).
 * Wirft nie wegen der Datei: Ist sie als Ganzes nicht brauchbar, steht der Grund in `error`; ist ein einzelner Abend
 * nicht brauchbar, steht er mit Grund in `invalidEvenings`.
 */
export function readBackup(
  text: string,
  knownPlayers: readonly Player[],
  knownEvenings: readonly Evening[],
  checker: EveningChecker,
): ImportPreview {
  let root: JsonElement;
  try {
    root = parseToJsonElement(withoutByteOrderMark(text));
  } catch (error) {
    if (error instanceof JsonFormatError) return notJson();
    throw error;
  }
  if (root.kind !== "object" || textOf(root, "format") !== BACKUP_FORMAT_NAME) {
    return failure("WRONG_FORMAT", "Die Datei ist keine Sicherung dieser App.");
  }

  // Fehlt die Angabe, gilt die erste Version; steht dort etwas anderes als eine Zahl, ist die Datei beschädigt.
  const versionElement = root.entries.get("schemaVersion");
  const version = versionElement === undefined ? BACKUP_FORMAT_VERSION : versionOf(versionElement);
  if (version === null || version < 1) {
    return failure("WRONG_FORMAT", "Die Sicherung ist beschädigt: Die Angabe zur Version ist ungültig.");
  }
  if (version > BACKUP_FORMAT_VERSION) {
    return failure(
      "NEWER_VERSION",
      "Die Sicherung stammt aus einer neueren Version der App. Bitte die App aktualisieren und es dann noch einmal versuchen.",
    );
  }

  let importedPlayers: Player[];
  try {
    const seen = new Set<PlayerId>();
    importedPlayers = decodePlayerList(root.entries.get("players") ?? EMPTY_ARRAY).filter((player) => {
      if (seen.has(player.id)) return false;
      seen.add(player.id);
      return true;
    });
  } catch (error) {
    if (error instanceof UnreadableDataError) return unreadablePlayers();
    throw error;
  }
  // Eine leere Kennung ist so viel wert wie eine fehlende: Der Spieler ließe sich später niemandem zuordnen.
  if (importedPlayers.some((player) => kotlinIsBlank(player.id))) return unreadablePlayers();
  const eveningsElement = root.entries.get("evenings");
  let eveningElements: readonly JsonElement[];
  if (eveningsElement === undefined) {
    eveningElements = [];
  } else if (eveningsElement.kind === "array") {
    eveningElements = eveningsElement.items;
  } else {
    return failure("WRONG_FORMAT", "Die Sicherung ist beschädigt: Die Liste der Abende lässt sich nicht lesen.");
  }

  const knownPlayersById = new Map(knownPlayers.map((player) => [player.id, player] as const));
  const newPlayers = importedPlayers.filter((player) => !knownPlayersById.has(player.id));
  // Dieselbe Kennung, aber anderer Name, andere Farbe oder anderer Archiv-Vermerk als auf diesem Gerät.
  const differingPlayers = importedPlayers.filter((player) => {
    const known = knownPlayersById.get(player.id);
    return known !== undefined && !samePlayer(known, player);
  });
  const knownById = new Map(knownEvenings.map((evening) => [evening.id, evening] as const));
  const newEvenings = new Map<string, Evening>();
  const existingEvenings = new Map<string, Evening>();
  const invalidEvenings: InvalidEvening[] = [];

  for (const element of eveningElements) {
    const raw: JsonObjectElement | null = element.kind === "object" ? element : null;
    const rawId = raw === null ? "" : (textOf(raw, "id") ?? "");
    const existing = knownById.get(rawId);
    if (existing !== undefined) {
      existingEvenings.set(existing.id, existing);
      continue;
    }
    let finding: EveningFinding | null;
    try {
      const evening = decodeEveningElement(element);
      finding = findingFor(evening, newEvenings, checker);
      if (finding === null) newEvenings.set(evening.id, evening);
    } catch (error) {
      if (error instanceof NewerSchemaError) {
        finding = { problem: "NEWER_VERSION", reason: "Der Abend wurde mit einer neueren Version der App gespeichert." };
      } else if (error instanceof UnreadableDataError) {
        finding = { problem: "UNREADABLE", reason: "Der Abend ist beschädigt und lässt sich nicht lesen." };
      } else {
        throw error;
      }
    }
    if (finding !== null) {
      invalidEvenings.push({
        id: rawId,
        startedAt: raw === null ? null : instantOrNull(textOf(raw, "startedAt")),
        problem: finding.problem,
        reason: finding.reason,
      });
    }
  }

  const defaultRulesElement = root.entries.get("defaultRules");
  return {
    error: null,
    exportedAt: instantOrNull(textOf(root, "exportedAt")),
    appVersion: textOf(root, "appVersion") ?? "",
    newPlayers,
    sameNamePlayers: newPlayers.filter((candidate) => knownPlayers.some((known) => sameName(known.name, candidate.name))),
    existingPlayerCount: importedPlayers.length - newPlayers.length,
    differingPlayers,
    newEvenings: [...newEvenings.values()],
    existingEvenings: [...existingEvenings.values()],
    invalidEvenings,
    defaultRules: defaultRulesElement === undefined ? null : rulesOrNull(defaultRulesElement),
  };
}

/** Die Vorlage des Regelwerks, nur zum Anbieten; `null`, wenn sie sich nicht lesen lässt. */
function rulesOrNull(element: JsonElement): RuleSet | null {
  try {
    return decodeRulesElement(element);
  } catch (error) {
    if (error instanceof UnreadableDataError) return null;
    throw error;
  }
}

/** Prüft einen lesbaren Abend; `null` heißt: Er kann übernommen werden. */
function findingFor(evening: Evening, alreadyAccepted: ReadonlyMap<string, Evening>, checker: EveningChecker): EveningFinding | null {
  if (kotlinIsBlank(evening.id)) return { problem: "UNREADABLE", reason: "Der Abend hat keine Kennung." };
  if (alreadyAccepted.has(evening.id)) {
    return { problem: "DUPLICATE_ID", reason: "Der Abend steht mehrfach in der Sicherung. Übernommen wird nur der erste." };
  }
  return firstEveningProblem(evening, checker);
}

const SOLO_TYPES: readonly GameType[] = ["FARBSOLO", "WENZ", "GEIER"];

/** Alle Spieler, die ein Eintrag nennt. */
function namedPlayers(entry: EveningEntry): PlayerId[] {
  switch (entry.kind) {
    case "game":
      return [entry.declarer, entry.partner, entry.kontraBy, entry.retourBy].filter((player): player is PlayerId => player !== null).concat(entry.klopfer);
    case "thrown_in":
      return [];
    case "bock":
      return entry.player === null ? [] : [entry.player];
    case "manual":
      return [...entry.amounts.keys()];
  }
}

/**
 * Prüft, ob ein Abend in sich stimmig ist (Kotlin: `EveningCheck.firstProblem`): genau vier verschiedene Spieler am
 * Tisch, jeder mit einer Kennung, der erste Geber sitzt am Tisch, in den Einträgen kommen nur Spieler vom Tisch vor,
 * jede freie Buchung und jedes Spiel geht auf null auf, kein Betrag sprengt den Bereich, mit dem die App rechnet, und
 * kein Eintrag trägt die Kennung eines anderen.
 *
 * @returns das erste Problem mit einem verständlichen Satz dazu; `null`, wenn alles stimmt.
 */
export function firstEveningProblem(evening: Evening, checker: EveningChecker): EveningFinding | null {
  const seating = evening.seating;
  if (seating.length !== 4) {
    return { problem: "WRONG_SEATING", reason: `Am Tisch müssen genau vier Spieler sitzen, hier sind es ${seating.length}.` };
  }
  if (new Set(seating).size !== seating.length) return { problem: "WRONG_SEATING", reason: "Am Tisch sitzt derselbe Spieler mehrfach." };
  if (seating.some((player) => kotlinIsBlank(player))) return { problem: "WRONG_SEATING", reason: "Am Tisch sitzt ein Spieler ohne Kennung." };
  if (!seating.includes(evening.firstDealer)) return { problem: "DEALER_NOT_AT_TABLE", reason: "Der erste Geber sitzt nicht am Tisch." };

  // Mit einer gültigen Sitzordnung lässt sich der Abend durchrechnen: ein Ergebnis je Eintrag, in derselben Reihenfolge.
  const results = checker.computeResults(evening);
  const entryIds = new Set<string>();
  for (let index = 0; index < evening.entries.length; index++) {
    const entry = evening.entries[index];
    const number = index + 1;
    if (namedPlayers(entry).some((player) => !seating.includes(player))) {
      return { problem: "UNKNOWN_PLAYER", reason: `Im Eintrag Nr. ${number} kommt ein Spieler vor, der nicht am Tisch sitzt.` };
    }
    // Genau summiert, damit auch absurd große Beträge die Prüfung nicht durch einen Überlauf täuschen.
    if (entry.kind === "manual" && [...entry.amounts.values()].reduce((sum, amount) => sum + BigInt(amount), 0n) !== 0n) {
      return {
        problem: "MANUAL_NOT_ZERO_SUM",
        reason: `Die freie Buchung im Eintrag Nr. ${number} geht nicht auf: Die Beträge ergeben zusammen nicht null.`,
      };
    }
    // Ein Spiel zu zweit gegen zwei braucht zwei verschiedene Spieler auf der Spielerseite.
    if (entry.kind === "game" && !SOLO_TYPES.includes(entry.type) && entry.partner === entry.declarer) {
      return {
        problem: "GAME_NOT_ZERO_SUM",
        reason: `Im Spiel im Eintrag Nr. ${number} ist der Ansager als sein eigener Mitspieler eingetragen.`,
      };
    }
    // Ein Spiel, mit dessen Wert die App nicht rechnen kann, und ein Eintrag, der einen Saldo aus dem Zahlenbereich
    // triebe, buchen nichts: Sie fehlten sonst stillschweigend in den Salden des Abends.
    const result = index < results.length ? results[index] : undefined;
    if (result !== undefined && isOutOfRange(evening, entry, result, checker)) {
      return {
        problem: "AMOUNT_OUT_OF_RANGE",
        reason: `Der Eintrag Nr. ${number} lässt sich nicht abrechnen: Ein Betrag liegt außerhalb des Bereichs, mit dem die App rechnen kann.`,
      };
    }
    // Jedes Spiel ist ein Nullsummenspiel; die Salden des Abends stimmten sonst nicht mehr.
    const amounts = result?.amounts ?? new Map<PlayerId, number>();
    if (entry.kind === "game" && [...amounts.values()].reduce((sum, amount) => sum + amount, 0) !== 0) {
      return {
        problem: "GAME_NOT_ZERO_SUM",
        reason: `Das Spiel im Eintrag Nr. ${number} geht nicht auf: Die Beträge der Spieler ergeben zusammen nicht null.`,
      };
    }
    // Über ihre Kennung werden Einträge geändert und gelöscht; innerhalb eines Abends muss sie eindeutig sein.
    if (entryIds.has(entry.id)) {
      return { problem: "DUPLICATE_ENTRY_ID", reason: `Der Eintrag Nr. ${number} hat dieselbe Kennung wie ein früherer Eintrag des Abends.` };
    }
    entryIds.add(entry.id);
  }
  return null;
}

/** Ob der Eintrag nichts gebucht hat, weil ein Betrag nicht in den Bereich passt, mit dem die App rechnet. */
function isOutOfRange(evening: Evening, entry: EveningEntry, result: CheckedEntryResult, checker: EveningChecker): boolean {
  if (result.issues.includes("BALANCE_OUT_OF_RANGE")) return true;
  if (entry.kind !== "game") return false;
  return checker.isValueOutOfRange(evening, entry, result.bockExponent);
}

// ---------- Übernahme (Kotlin: BackupService.import) ----------

/**
 * Was ein Import speichert: neue Spieler und neue, gültige Abende, in dieser Reihenfolge erst alle Spieler, dann alle
 * Abende. Vorhandenes bleibt unverändert, ungültige Abende werden ausgelassen, die Einstellungen nie angetastet.
 */
export interface ImportPlan {
  /** Gesetzt, wenn die Datei nicht lesbar ist; dann wird nichts gespeichert. */
  readonly error: BackupError | null;
  readonly players: readonly Player[];
  readonly evenings: readonly Evening[];
}

export function planImport(preview: ImportPreview): ImportPlan {
  if (preview.error !== null) return { error: preview.error, players: [], evenings: [] };
  return { error: null, players: preview.newPlayers, evenings: preview.newEvenings };
}

/**
 * Was ein Import tatsächlich bewirkt hat (Kotlin: `ImportResult`). Ist `error` gesetzt und die Datei war lesbar, ist
 * das Speichern unterwegs gescheitert; die Listen nennen dann, was bis dahin gespeichert wurde.
 */
export interface ImportResult {
  readonly error: BackupError | null;
  readonly newPlayers: readonly Player[];
  readonly sameNamePlayers: readonly Player[];
  readonly existingPlayerCount: number;
  readonly newEvenings: readonly Evening[];
  readonly existingEvenings: readonly Evening[];
  readonly invalidEvenings: readonly InvalidEvening[];
}

/** Der Fehler, wenn sich beim Import etwas nicht speichern ließ. */
export const STORAGE_FAILED_ERROR: BackupError = {
  kind: "STORAGE_FAILED",
  message: "Die Sicherung konnte nicht vollständig eingespielt werden, weil sich die Daten nicht speichern ließen. Ist der Speicher voll?",
};

/**
 * Das Ergebnis eines Imports aus der Vorschau und dem, was tatsächlich gespeichert wurde.
 * @param storageFailed ob das Speichern unterwegs gescheitert ist.
 */
export function importResultOf(
  preview: ImportPreview,
  savedPlayers: readonly Player[],
  savedEvenings: readonly Evening[],
  storageFailed: boolean,
): ImportResult {
  if (preview.error !== null) {
    return { error: preview.error, newPlayers: [], sameNamePlayers: [], existingPlayerCount: 0, newEvenings: [], existingEvenings: [], invalidEvenings: [] };
  }
  return {
    error: storageFailed ? STORAGE_FAILED_ERROR : null,
    newPlayers: savedPlayers,
    sameNamePlayers: preview.sameNamePlayers.filter((player) => savedPlayers.some((saved) => samePlayer(saved, player))),
    existingPlayerCount: preview.existingPlayerCount,
    newEvenings: savedEvenings,
    existingEvenings: preview.existingEvenings,
    invalidEvenings: preview.invalidEvenings,
  };
}

/**
 * Spielt eine gelesene Sicherung ein wie `BackupService.import`: erst alle neuen Spieler, dann alle neuen Abende,
 * nacheinander. Scheitert ein Speichern, hört der Import dort auf und meldet `STORAGE_FAILED`. Zwei Importe dürfen
 * nicht gleichzeitig laufen (Kotlin sperrt dafür); die Vorschau muss unmittelbar vorher aus dem aktuellen Stand gelesen
 * worden sein.
 */
export async function runImport(
  preview: ImportPreview,
  savePlayer: (player: Player) => Promise<void>,
  saveEvening: (evening: Evening) => Promise<void>,
): Promise<ImportResult> {
  const plan = planImport(preview);
  if (plan.error !== null) return importResultOf(preview, [], [], false);
  const savedPlayers: Player[] = [];
  const savedEvenings: Evening[] = [];
  let storageFailed = false;
  try {
    for (const player of plan.players) {
      await savePlayer(player);
      savedPlayers.push(player);
    }
    for (const evening of plan.evenings) {
      await saveEvening(evening);
      savedEvenings.push(evening);
    }
  } catch {
    storageFailed = true;
  }
  return importResultOf(preview, savedPlayers, savedEvenings, storageFailed);
}
