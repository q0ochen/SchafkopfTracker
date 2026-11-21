/*
 * Logik des Bildschirms „Abend bearbeiten“ (docs/ARCHITEKTUR.md, Abschnitt 4.1) – verhaltensgleich zur Android-App,
 * hier als reine Funktionen über dem Entwurf des Nutzers.
 *
 * Beim Öffnen wird der Abend einmal gelesen; das ist `original` für `EveningEdit.apply`. Gespeichert wird nur, was
 * sich gegenüber diesem Stand geändert hat: Was ein anderes Handy der gemeinsamen Runde inzwischen geändert hat,
 * bleibt so.
 */
import { EveningEdit, eveningDetailsOf, MAX_LOCATION_LENGTH, type EveningDetails } from "../core/evening/EveningEdit";
import { formatInstant, instantParts, instantToEpochMillis } from "../core/instant";
import type { Evening, InstantText, PlayerId } from "../core/model";
import { movedInSeating } from "./newEvening";

/** Was der Nutzer bisher eingestellt hat; beim Öffnen genau der Stand des Abends. */
export interface EditEveningDraft {
  /**
   * Der Tag des Beginns als Ortsdatum „JJJJ-MM-TT“ in der Zeitzone des Browsers, so wie ein Datumsfeld ihn liefert.
   * Leer, wenn ein Datumsfeld den Tag nicht zeigen kann (nur bei beschädigten Daten).
   */
  readonly date: string;
  readonly location: string;
  readonly seating: readonly PlayerId[];
  readonly firstDealer: PlayerId;
}

/** Der Stand beim Öffnen: `original` für `EveningEdit.apply` und der Entwurf, der damit beginnt. */
export interface EditEveningStart {
  readonly original: EveningDetails;
  readonly draft: EditEveningDraft;
}

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

/**
 * Der Tag eines Zeitpunkts als Ortsdatum „JJJJ-MM-TT“ in der Zeitzone des Browsers. Leer, wenn ein Datumsfeld diesen
 * Tag nicht zeigen kann (vor dem Jahr 1 oder außerhalb des Bereichs von `Date`).
 */
export function localDateOf(instant: InstantText): string {
  const date = new Date(instantToEpochMillis(instant));
  if (Number.isNaN(date.getTime()) || date.getFullYear() < 1) return "";
  return localDateText({ year: date.getFullYear(), month: date.getMonth() + 1, day: date.getDate() });
}

/**
 * Derselbe Beginn an einem anderen Tag `date` („JJJJ-MM-TT“): dieselbe Ortszeit in der Zeitzone des Browsers, auch
 * über eine Umstellung der Sommerzeit hinweg; Bruchteile unter einer Millisekunde bleiben. Gerechnet wird mit den
 * Settern von `Date` für die Ortszeit: Fällt die Uhrzeit an dem Tag aus (die Stunde, die bei der Umstellung auf die
 * Sommerzeit fehlt), rückt sie um diese Stunde vor; gibt es sie zweimal (Umstellung auf die Winterzeit), gilt die
 * frühere. Lässt sich der bisherige Beginn nicht als `Date` darstellen (beschädigte Daten), beginnt der Abend um
 * Mitternacht. `null`, wenn `date` kein Datum ist.
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

/** Der Entwurf beim Öffnen: genau die Angaben des Abends, der Beginn als Ortsdatum. */
export function draftOf(original: EveningDetails): EditEveningDraft {
  return {
    date: localDateOf(original.startedAt),
    location: original.location,
    seating: original.seating,
    firstDealer: original.firstDealer,
  };
}

/** Beim Öffnen: der Abend, einmal gelesen, als `original` und der Entwurf daraus. */
export function startEditing(evening: Evening): EditEveningStart {
  const original = eveningDetailsOf(evening);
  return { original, draft: draftOf(original) };
}

/**
 * Wählt einen anderen Tag. Tage nach `today` (ebenfalls „JJJJ-MM-TT“, das Ortsdatum von heute) und alles, was kein
 * Datum ist (auch ein geleertes Feld), bleiben unbeachtet.
 */
export function setDate(draft: EditEveningDraft, date: string, today: string): EditEveningDraft {
  const day = parseLocalDate(date);
  const latest = parseLocalDate(today);
  if (day === null || latest === null || compareDays(day, latest) > 0) return draft;
  const text = localDateText(day);
  return text === draft.date ? draft : { ...draft, date: text };
}

/** Der Ort, wie er im Feld steht, auf {@link MAX_LOCATION_LENGTH} Zeichen gekürzt (gesäubert wird beim Speichern). */
export function setLocation(draft: EditEveningDraft, location: string): EditEveningDraft {
  return { ...draft, location: location.slice(0, MAX_LOCATION_LENGTH) };
}

/** Rückt einen Spieler um einen Platz nach vorn (−1) oder hinten (+1), wie bei „Neuer Abend“. */
export function moveSeat(draft: EditEveningDraft, player: PlayerId, delta: number): EditEveningDraft {
  const seating = movedInSeating(draft.seating, player, delta);
  return seating === draft.seating ? draft : { ...draft, seating };
}

/** Legt fest, wer das erste Spiel gegeben hat; nur jemand am Tisch. */
export function setFirstDealer(draft: EditEveningDraft, player: PlayerId): EditEveningDraft {
  return draft.seating.includes(player) ? { ...draft, firstDealer: player } : draft;
}

/** Der Ort zählt als geändert, wenn er gesäubert anders ist als der ursprüngliche (Leerraum am Rand zählt nicht). */
function locationChanged(original: EveningDetails, draft: EditEveningDraft): boolean {
  return EveningEdit.cleanLocation(draft.location) !== EveningEdit.cleanLocation(original.location);
}

function sameSeating(a: readonly PlayerId[], b: readonly PlayerId[]): boolean {
  return a.length === b.length && a.every((player, index) => player === b[index]);
}

/** Ob sich gegenüber dem Stand beim Öffnen etwas geändert hat; nur dann ist „Speichern“ aktiv. */
export function changed(original: EveningDetails, draft: EditEveningDraft): boolean {
  return (
    draft.date !== localDateOf(original.startedAt) ||
    locationChanged(original, draft) ||
    !sameSeating(draft.seating, original.seating) ||
    draft.firstDealer !== original.firstDealer
  );
}

/**
 * Die bearbeiteten Angaben für `EveningEdit.apply(current, original, edited)`:
 *
 * - **Beginn:** genau der ursprüngliche, wenn der Tag derselbe ist; sonst dieselbe Ortszeit am neuen Tag
 *   ({@link startOnDay}).
 * - **Ort:** der Entwurf, wenn er als geändert zählt; sonst der ursprüngliche Ort unverändert. So überschreibt ein bloß
 *   getrimmter Ort nie, was ein anderes Handy inzwischen eingetragen hat.
 * - **Sitzordnung** und **erster Geber:** wie im Entwurf.
 */
export function editedDetails(original: EveningDetails, draft: EditEveningDraft): EveningDetails {
  const sameDay = draft.date === localDateOf(original.startedAt);
  return {
    startedAt: sameDay ? original.startedAt : (startOnDay(original.startedAt, draft.date) ?? original.startedAt),
    location: locationChanged(original, draft) ? draft.location : original.location,
    seating: draft.seating,
    firstDealer: draft.firstDealer,
  };
}
