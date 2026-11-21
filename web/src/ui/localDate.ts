/*
 * Tage im Kalender in der Zeitzone des Browsers, für die Datumsfelder von „Neuer Abend“ und „Abend bearbeiten“ und für
 * geplante Abende (docs/ARCHITEKTUR.md, Abschnitt 4.1 und 4.2). Ein Tag steht als Ortsdatum „JJJJ-MM-TT“ da, so wie ein
 * Datumsfeld des Browsers ihn liefert. Reine Funktionen; die Zeitzone ist die des Browsers (in den Unit-Tests
 * Europe/Berlin, siehe vite.config.ts).
 */
import { isRunning } from "../core/evening/Evening";
import { formatInstant, instantParts, instantToEpochMillis } from "../core/instant";
import type { Evening, InstantText } from "../core/model";

/** Ein Tag im Kalender, Monat und Tag ab 1. */
interface LocalDay {
  readonly year: number;
  readonly month: number;
  readonly day: number;
}

const LOCAL_DATE = /^(\d{4,})-(\d{2})-(\d{2})$/;

function pad2(value: number): string {
  return String(value).padStart(2, "0");
}

function localDateText({ year, month, day }: LocalDay): string {
  return `${String(year).padStart(4, "0")}-${pad2(month)}-${pad2(day)}`;
}

/** Ein Datum „JJJJ-MM-TT“ (ab dem Jahr 1, wie es ein Datumsfeld liefert); `null`, wenn es keines ist. */
function parseLocalDate(text: string): LocalDay | null {
  const match = LOCAL_DATE.exec(text);
  if (!match) return null;
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  if (year < 1 || month < 1 || month > 12) return null;
  // Der Tag 0 des Folgemonats ist der letzte dieses Monats (in UTC gerechnet, damit keine Zeitzone hineinspielt).
  const lastDay = new Date(0);
  lastDay.setUTCFullYear(year, month, 0);
  return day >= 1 && day <= lastDay.getUTCDate() ? { year, month, day } : null;
}

function compareDays(a: LocalDay, b: LocalDay): number {
  return a.year - b.year || a.month - b.month || a.day - b.day;
}

/** Der Tag eines Zeitpunkts in der Zeitzone des Browsers; `null` außerhalb des Bereichs von `Date`. */
function localDayOf(instant: InstantText): LocalDay | null {
  const date = new Date(instantToEpochMillis(instant));
  if (Number.isNaN(date.getTime())) return null;
  return { year: date.getFullYear(), month: date.getMonth() + 1, day: date.getDate() };
}

/**
 * Das Datum „JJJJ-MM-TT“ in einheitlicher Schreibweise, wie es ein Datumsfeld zeigt; `null`, wenn `text` kein Datum
 * ist (auch ein geleertes Feld).
 */
export function normalizedLocalDate(text: string): string | null {
  const day = parseLocalDate(text);
  return day === null ? null : localDateText(day);
}

/**
 * Der Tag eines Zeitpunkts als Ortsdatum „JJJJ-MM-TT“ in der Zeitzone des Browsers. Leer, wenn ein Datumsfeld diesen
 * Tag nicht zeigen kann (vor dem Jahr 1 oder außerhalb des Bereichs von `Date`).
 */
export function localDateOf(instant: InstantText): string {
  const day = localDayOf(instant);
  return day === null || day.year < 1 ? "" : localDateText(day);
}

/** Ob der Tag `date` nach dem Tag `reference` liegt (beide „JJJJ-MM-TT“); `false`, wenn eines kein Datum ist. */
export function isAfterDay(date: string, reference: string): boolean {
  const day = parseLocalDate(date);
  const other = parseLocalDate(reference);
  return day !== null && other !== null && compareDays(day, other) > 0;
}

/**
 * Derselbe Beginn an einem anderen Tag `date` („JJJJ-MM-TT“): dieselbe Ortszeit in der Zeitzone des Browsers, auch
 * über eine Umstellung der Sommerzeit hinweg; Bruchteile unter einer Millisekunde bleiben. Gerechnet wird mit den
 * Settern von `Date` für die Ortszeit: Fällt die Uhrzeit an dem Tag aus (die Stunde, die bei der Umstellung auf die
 * Sommerzeit fehlt), rückt sie um diese Stunde vor; gibt es sie zweimal (Umstellung auf die Winterzeit), gilt die
 * frühere (wie Kotlins `ZonedDateTime.of`). Lässt sich der bisherige Beginn nicht als `Date` darstellen (beschädigte
 * Daten), beginnt der Abend um Mitternacht. `null`, wenn `date` kein Datum ist.
 */
export function startOnDay(startedAt: InstantText, date: string): InstantText | null {
  const day = parseLocalDate(date);
  if (day === null) return null;
  const local = new Date(instantToEpochMillis(startedAt));
  const subMillis = Number.isNaN(local.getTime()) ? 0 : instantParts(startedAt).nano % 1_000_000;
  local.setFullYear(day.year, day.month - 1, day.day);
  const millis = local.getTime();
  if (Number.isNaN(millis)) return null;
  const seconds = Math.floor(millis / 1000);
  return formatInstant({ epochSecond: BigInt(seconds), nano: (millis - seconds * 1000) * 1_000_000 + subMillis });
}

/**
 * Ob ein Abend geplant ist (docs/ARCHITEKTUR.md, Abschnitt 4.2; Kotlin: `Evening.isPlanned(today, zone)`): Er ist nicht
 * beendet, und der Tag seines Beginns liegt in der Zeitzone des Browsers nach `today` („JJJJ-MM-TT“, das Ortsdatum von
 * heute). Am Tag selbst gilt er als laufend. Gespeichert wird dafür nichts. Ein Beginn außerhalb des Bereichs von
 * `Date` (nur bei beschädigten Daten) liegt weit in der Zukunft oder Vergangenheit und wird danach eingeordnet.
 */
export function isPlanned(evening: Evening, today: string): boolean {
  const reference = parseLocalDate(today);
  if (!isRunning(evening) || reference === null) return false;
  const day = localDayOf(evening.startedAt);
  if (day === null) return instantToEpochMillis(evening.startedAt) > 0;
  return compareDays(day, reference) > 0;
}
