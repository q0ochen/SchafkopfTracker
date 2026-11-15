/*
 * Die Daten der Web-App: dieselben Tatsachen wie in der Android-App (Kotlin, Modul `domain`, Pakete `model`, `rules`
 * und `evening`), als schlichte, unveränderliche TypeScript-Daten.
 *
 * - Namen und Bedeutung folgen genau dem Kotlin-Kern; Abweichungen stehen hier jeweils dabei.
 * - Aufzählungen sind Texte mit den Namen der Kotlin-Enums (zum Beispiel „RUFSPIEL“); die Reihenfolge der
 *   Konstanten (GAME_TYPES usw.) ist die der Kotlin-Enums.
 * - Kennungen sind Texte, Zeitpunkte ISO-8601-Texte wie in den Dateien der Android-App (zum Beispiel
 *   „2026-11-14T19:00:00Z“). Zeitpunkte nie als Text vergleichen, sondern mit den Hilfen des Kerns.
 * - Beträge sind ganze Cent.
 */

/** Kennung eines Spielers. Bleibt gleich, auch wenn der Name geändert wird. */
export type PlayerId = string;

/** Kennung eines Abends. */
export type EveningId = string;

/** Kennung eines Eintrags (Spiel, Zusammengeworfen, Bockrunde, freie Buchung) innerhalb eines Abends. */
export type EntryId = string;

/** Ein Zeitpunkt als ISO-8601-Text in UTC, so wie Kotlins `Instant.toString()` ihn schreibt. */
export type InstantText = string;

/**
 * Ein Spieler der Runde.
 *
 * @property colorIndex Feste Farbe des Spielers (Index in die Palette der Oberfläche).
 * @property archived Archivierte Spieler erscheinen nicht mehr in der Auswahl, bleiben aber in alten Abenden erhalten.
 */
export interface Player {
  readonly id: PlayerId;
  readonly name: string;
  readonly colorIndex: number;
  readonly archived: boolean;
}

/** Spielarten (Kotlin: `GameType`). */
export type GameType = "RUFSPIEL" | "UEBER_KREUZ" | "FARBSOLO" | "WENZ" | "GEIER";
export const GAME_TYPES: readonly GameType[] = ["RUFSPIEL", "UEBER_KREUZ", "FARBSOLO", "WENZ", "GEIER"];

/** Die vier Farben des bayerischen Blatts (Rufsau beim Rufspiel, Trumpffarbe beim Farbsolo). */
export type Suit = "EICHEL" | "GRAS" | "HERZ" | "SCHELLEN";
export const SUITS: readonly Suit[] = ["EICHEL", "GRAS", "HERZ", "SCHELLEN"];

/** Ausgang über den einfachen Sieg hinaus. Schwarz schließt Schneider ein. */
export type ResultLevel = "NORMAL" | "SCHNEIDER" | "SCHWARZ";
export const RESULT_LEVELS: readonly ResultLevel[] = ["NORMAL", "SCHNEIDER", "SCHWARZ"];

/** Besondere Ansage eines Alleinspielers. */
export type Announcement = "NONE" | "TOUT" | "SIE";
export const ANNOUNCEMENTS: readonly Announcement[] = ["NONE", "TOUT", "SIE"];

/** Kontra (Stoß) der Gegenspieler und Retour (Re) der Spielerseite. */
export type KontraLevel = "NONE" | "KONTRA" | "RETOUR";
export const KONTRA_LEVELS: readonly KontraLevel[] = ["NONE", "KONTRA", "RETOUR"];

/** Was passiert, wenn während einer laufenden Bockrunde erneut eine ausgelöst wird. */
export type BockStacking = "APPEND" | "STACK" | "IGNORE";
export const BOCK_STACKINGS: readonly BockStacking[] = ["APPEND", "STACK", "IGNORE"];

/** Wie der Spielwert verteilt wird: Schafkopf-Regel oder Topf-Teilung. */
export type PayoutRule = "SCHAFKOPF" | "POT_SPLIT";
export const PAYOUT_RULES: readonly PayoutRule[] = ["SCHAFKOPF", "POT_SPLIT"];

/** Ob ein Spiel abweichend vom Bock-Zähler als Bockspiel zählt. */
export type BockOverride = "AUTO" | "FORCE_ON" | "FORCE_OFF";
export const BOCK_OVERRIDES: readonly BockOverride[] = ["AUTO", "FORCE_ON", "FORCE_OFF"];

/** Warum eine Bockrunde ausgelöst wurde. */
export type BockReason = "SECHS_NIXER" | "MANUAL";
export const BOCK_REASONS: readonly BockReason[] = ["SECHS_NIXER", "MANUAL"];

/** Hausregel Bockrunde (Kotlin: `BockRules`). */
export interface BockRules {
  readonly enabled: boolean;
  /** Länge einer Bockrunde in gewerteten Spielen (4 = jeder gibt einmal). */
  readonly gamesPerTrigger: number;
  /** Faktor, mit dem der gesamte Spielwert eines Bockspiels malgenommen wird. */
  readonly factor: number;
  readonly stacking: BockStacking;
  /** Ob ein zusammengeworfenes Blatt ein Bockspiel verbraucht. */
  readonly thrownInConsumesBockGame: boolean;
}

/**
 * Das Regelwerk eines Abends (Kotlin: `RuleSet`). Jeder Abend speichert seine eigene Kopie.
 *
 * Die Tabellen je Spielart dürfen unvollständig sein (ältere Dateien); was fehlt, liefert der Kern über
 * `price`, `laufendeMin` und `laufendeMax` (für „Über Kreuz“ zuerst der Wert des Rufspiels, sonst der Standard).
 * `enabledTypes` ist eine Menge: jede Spielart höchstens einmal, die Reihenfolge hat keine Bedeutung.
 */
export interface RuleSet {
  readonly name: string;
  readonly unit: number;
  readonly prices: Readonly<Partial<Record<GameType, number>>>;
  readonly enabledTypes: readonly GameType[];
  readonly laufendeMin: Readonly<Partial<Record<GameType, number>>>;
  readonly laufendeMax: Readonly<Partial<Record<GameType, number>>>;
  readonly klopfenEnabled: boolean;
  readonly kontraEnabled: boolean;
  readonly retourEnabled: boolean;
  readonly toutFactor: number;
  readonly sieFactor: number;
  readonly sieLaufende: number;
  readonly bock: BockRules;
  readonly dealerAdvancesAfterThrowIn: boolean;
  readonly displayAsMoney: boolean;
  readonly payoutRule: PayoutRule;
}

/**
 * Ein gespieltes Spiel (Kotlin: `GameEntry`).
 *
 * `klopfer` ist eine Menge (jeder Spieler höchstens einmal, Reihenfolge des Eintragens).
 */
export interface GameEntry {
  readonly kind: "game";
  readonly id: EntryId;
  readonly recordedAt: InstantText;
  readonly type: GameType;
  readonly suit: Suit | null;
  readonly declarer: PlayerId;
  readonly partner: PlayerId | null;
  readonly declarerWon: boolean;
  readonly level: ResultLevel;
  readonly laufende: number;
  readonly announcement: Announcement;
  readonly klopfer: readonly PlayerId[];
  readonly kontra: KontraLevel;
  readonly kontraBy: PlayerId | null;
  readonly retourBy: PlayerId | null;
  readonly bockOverride: BockOverride;
  readonly declarerEyes: number | null;
  readonly note: string;
}

/** Alle haben „weiter“ gesagt: Das Blatt wurde zusammengeworfen, niemand zahlt. */
export interface ThrownInEntry {
  readonly kind: "thrown_in";
  readonly id: EntryId;
  readonly recordedAt: InstantText;
}

/** Eine Bockrunde wurde ausgelöst (Kotlin: `BockTriggerEntry`). */
export interface BockTriggerEntry {
  readonly kind: "bock";
  readonly id: EntryId;
  readonly recordedAt: InstantText;
  readonly reason: BockReason;
  /** Wer die 6 Nixer hatte; freiwillige Angabe. */
  readonly player: PlayerId | null;
  /** Ob das Blatt dabei zusammengeworfen und neu gegeben wurde. */
  readonly handThrownIn: boolean;
}

/**
 * Freie Buchung für alles, was die App nicht kennt (Kotlin: `ManualEntry`). Die Beträge in Cent sollen sich zu 0
 * summieren. Eine `Map`, weil die Reihenfolge der Spieler wie in Kotlin erhalten bleiben muss (auch bei Kennungen,
 * die wie Zahlen aussehen).
 */
export interface ManualEntry {
  readonly kind: "manual";
  readonly id: EntryId;
  readonly recordedAt: InstantText;
  readonly amounts: ReadonlyMap<PlayerId, number>;
  readonly note: string;
}

/** Ein Eintrag in der Liste eines Abends. Die Art steht in `kind` (wie das Feld `type` in den Dateien). */
export type EveningEntry = GameEntry | ThrownInEntry | BockTriggerEntry | ManualEntry;

/**
 * Ein Schafkopf-Abend (Kotlin: `Evening`).
 *
 * @property seating Die vier Spieler im Uhrzeigersinn. In dieser Reihenfolge wechselt der Geber.
 * @property finishedAt `null`, solange der Abend läuft.
 */
export interface Evening {
  readonly id: EveningId;
  readonly startedAt: InstantText;
  readonly finishedAt: InstantText | null;
  readonly location: string;
  readonly seating: readonly PlayerId[];
  readonly firstDealer: PlayerId;
  readonly rules: RuleSet;
  readonly entries: readonly EveningEntry[];
}

/** Helles oder dunkles Erscheinungsbild. */
export type ThemeMode = "SYSTEM" | "LIGHT" | "DARK";

/**
 * Einstellungen der Web-App. Entspricht `AppSettings` der Android-App ohne die Einstellungen der
 * KI-Zusammenfassung (die gibt es in der Web-App nicht).
 */
export interface AppSettings {
  /** Vorlage für neue Abende. Jeder Abend bekommt eine eigene Kopie. */
  readonly defaultRules: RuleSet;
  /** Bildschirm anlassen, solange ein Abend angezeigt wird (soweit der Browser das erlaubt). */
  readonly keepScreenOn: boolean;
  /** Erscheinungsbild; dunkel ist der Standard. */
  readonly themeMode: ThemeMode;
}
