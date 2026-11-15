/*
 * Darstellung von Beträgen, Daten und Fachbegriffen – wie in der Android-App (`ui/common/Money.kt`,
 * `ui/common/Formatting.kt` und die Texte in `strings.xml`). Gerechnet wird überall in ganzen Cent;
 * formatiert wird nur hier.
 */
import { instantToEpochMillis } from "../core/instant";
import type {
  Announcement,
  BockStacking,
  GameType,
  InstantText,
  KontraLevel,
  PayoutRule,
  ResultLevel,
  Suit,
  ThemeMode,
} from "../core/model";

/** Echtes Minuszeichen (U+2212), wie in der Android-App. */
export const MINUS = "−";
const NO_BREAK_SPACE = " ";

/**
 * Ein Betrag in Cent als Text.
 *
 * @param asMoney `true`: Euro mit zwei Nachkommastellen („1,20 €“). `false`: Punkte („120“).
 * @param signed `true`: positive Beträge bekommen ein Plus, damit Gewinn und Verlust nie nur an der Farbe hängen.
 */
export function formatAmount(cents: number, asMoney: boolean, signed = false): string {
  const sign = cents < 0 ? MINUS : cents > 0 && signed ? "+" : "";
  const absolute = Math.abs(cents);
  if (!asMoney) return `${sign}${absolute}`;
  const euros = Math.trunc(absolute / 100);
  const rest = String(absolute % 100).padStart(2, "0");
  return `${sign}${euros},${rest}${NO_BREAK_SPACE}€`;
}

/** Ob ein Betrag als Gewinn, Verlust oder neutral gilt (für die Farbe; der Text trägt immer ein Vorzeichen). */
export function amountTone(cents: number): "gain" | "loss" | "neutral" {
  if (cents > 0) return "gain";
  if (cents < 0) return "loss";
  return "neutral";
}

const DATE_FORMAT = new Intl.DateTimeFormat("de-DE", { weekday: "short", day: "numeric", month: "short", year: "numeric" });
const TIME_FORMAT = new Intl.DateTimeFormat("de-DE", { hour: "2-digit", minute: "2-digit" });
const DATE_TIME_FORMAT = new Intl.DateTimeFormat("de-DE", { dateStyle: "medium", timeStyle: "short" });

/** Datum eines Abends, zum Beispiel „Sa., 14. Nov. 2026“ (in der Zeitzone des Geräts). */
export function formatDate(instant: InstantText): string {
  return DATE_FORMAT.format(new Date(instantToEpochMillis(instant)));
}

/** Uhrzeit, zum Beispiel „19:05“. */
export function formatTime(instant: InstantText): string {
  return TIME_FORMAT.format(new Date(instantToEpochMillis(instant)));
}

/** Datum mit Uhrzeit, zum Beispiel „14.11.2026, 19:05“. */
export function formatDateTime(instant: InstantText): string {
  return DATE_TIME_FORMAT.format(new Date(instantToEpochMillis(instant)));
}

/** Einzahl oder Mehrzahl mit Zahl davor, zum Beispiel „1 Spiel“ oder „3 Spiele“. */
export function plural(count: number, one: string, other: string): string {
  return `${count}${NO_BREAK_SPACE}${count === 1 ? one : other}`;
}

/** Anteil als ganze Prozent, zum Beispiel „67 %“; ohne Fälle ein Gedankenstrich. */
export function formatPercent(rate: number | null): string {
  if (rate === null || !Number.isFinite(rate)) return "–";
  return `${Math.round(rate * 100)}${NO_BREAK_SPACE}%`;
}

export const GAME_TYPE_LABELS: Readonly<Record<GameType, string>> = {
  RUFSPIEL: "Rufspiel",
  UEBER_KREUZ: "Über Kreuz",
  FARBSOLO: "Farbsolo",
  WENZ: "Wenz",
  GEIER: "Geier",
};

export const SUIT_LABELS: Readonly<Record<Suit, string>> = {
  EICHEL: "Eichel",
  GRAS: "Gras",
  HERZ: "Herz",
  SCHELLEN: "Schellen",
};

export const LEVEL_LABELS: Readonly<Record<ResultLevel, string>> = {
  NORMAL: "einfach",
  SCHNEIDER: "Schneider",
  SCHWARZ: "Schwarz",
};

export const ANNOUNCEMENT_LABELS: Readonly<Record<Announcement, string>> = {
  NONE: "keine",
  TOUT: "Tout",
  SIE: "Sie",
};

export const KONTRA_LABELS: Readonly<Record<KontraLevel, string>> = {
  NONE: "kein Kontra",
  KONTRA: "Kontra",
  RETOUR: "Retour",
};

export const PAYOUT_LABELS: Readonly<Record<PayoutRule, string>> = {
  SCHAFKOPF: "Schafkopf-Regel",
  POT_SPLIT: "Topf-Teilung",
};

export const PAYOUT_HINTS: Readonly<Record<PayoutRule, string>> = {
  SCHAFKOPF:
    "Der Alleinspieler rechnet mit jedem der drei Gegner einzeln ab: Er bekommt oder zahlt dreimal den Spielwert.",
  POT_SPLIT: "Es geht nur um einen Spielwert: Die drei Gegner teilen ihn sich (je ein Drittel).",
};

export const BOCK_STACKING_LABELS: Readonly<Record<BockStacking, string>> = {
  APPEND: "anhängen",
  STACK: "stapeln (×4)",
  IGNORE: "ignorieren",
};

export const THEME_LABELS: Readonly<Record<ThemeMode, string>> = {
  SYSTEM: "wie System",
  LIGHT: "hell",
  DARK: "dunkel",
};
