/*
 * Logik des Bildschirms „Abend bearbeiten“ (docs/ARCHITEKTUR.md, Abschnitt 4.1) – verhaltensgleich zur Android-App,
 * hier als reine Funktionen über dem Entwurf des Nutzers.
 *
 * Beim Öffnen wird der Abend einmal gelesen; das ist `original` für `EveningEdit.apply`. Gespeichert wird nur, was
 * sich gegenüber diesem Stand geändert hat: Was ein anderes Handy der gemeinsamen Runde inzwischen geändert hat,
 * bleibt so.
 */
import { EveningEdit, eveningDetailsOf, MAX_LOCATION_LENGTH, type EveningDetails } from "../core/evening/EveningEdit";
import type { Evening, PlayerId } from "../core/model";
import { localDateOf, normalizedLocalDate, startOnDay } from "./localDate";
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
 * Wählt einen anderen Tag, auch einen nach heute (dann ist der Abend geplant, docs/ARCHITEKTUR.md 4.2; bis zum
 * 21.11.2026 waren Tage nach heute gesperrt). Was kein Datum ist (auch ein geleertes Feld), bleibt unbeachtet.
 */
export function setDate(draft: EditEveningDraft, date: string): EditEveningDraft {
  const text = normalizedLocalDate(date);
  return text === null || text === draft.date ? draft : { ...draft, date: text };
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
