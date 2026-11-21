/*
 * Nachträgliche Änderungen an einem Abend (Bildschirm „Abend bearbeiten“; Kotlin: `domain/evening/EveningEdit.kt`,
 * Vertrag in docs/ARCHITEKTUR.md, Abschnitt 2, „Abend“). Reine Funktionen; dass sie für dieselben Eingaben genau
 * dasselbe liefern wie der Kotlin-Kern, prüft der Golden-Abschnitt `edits`.
 *
 * Wer am Tisch sitzt, bleibt, denn die Einträge nennen die Spieler. Die Einträge selbst bleiben ebenso; das Regelwerk
 * hat einen eigenen Bildschirm. Weil Beträge, Salden und Geber jedes Mal neu berechnet werden (`EveningCalculator`),
 * ändern sich mit der Sitzordnung und dem ersten Geber nur die Geber, keine Beträge.
 */
import { compareInstants, durationBetween, formatInstant, instantParts, plusDuration, type DurationParts } from "../instant";
import { kotlinTrim } from "../kotlin";
import type { Evening, InstantText, PlayerId } from "../model";

/** Höchstlänge des Orts in Zeichen (UTF-16-Einheiten wie Kotlins `take`), wie auf dem Bildschirm „Neuer Abend“. */
export const MAX_LOCATION_LENGTH = 40;

/**
 * Was sich an einem Abend nachträglich ändern lässt (Kotlin: `EveningDetails`): wann er begann, wo er war, die
 * Sitzordnung und wer das erste Spiel gegeben hat.
 */
export interface EveningDetails {
  readonly startedAt: InstantText;
  readonly location: string;
  readonly seating: readonly PlayerId[];
  readonly firstDealer: PlayerId;
}

/** Die vier Angaben eines Abends (Kotlin: `EveningDetails.of(evening)`). */
export function eveningDetailsOf(evening: Evening): EveningDetails {
  return {
    startedAt: evening.startedAt,
    location: evening.location,
    seating: evening.seating,
    firstDealer: evening.firstDealer,
  };
}

/** Der Ort, wie er gespeichert wird: ohne Leerraum an den Rändern, höchstens {@link MAX_LOCATION_LENGTH} Zeichen. */
function cleanLocation(location: string): string {
  return kotlinTrim(kotlinTrim(location).slice(0, MAX_LOCATION_LENGTH));
}

/** Das Ende, um `shift` verschoben; läge es außerhalb des Bereichs von `Instant`, bleibt es (wie in Kotlin). */
function shifted(instant: InstantText, shift: DurationParts): InstantText {
  const moved = plusDuration(instantParts(instant), shift);
  return moved === null ? instant : formatInstant(moved);
}

/** Gleiche Spieler in gleicher Reihenfolge (Kotlins `==` auf Listen). */
function sameSeating(a: readonly PlayerId[], b: readonly PlayerId[]): boolean {
  return a.length === b.length && a.every((player, index) => player === b[index]);
}

/** Ob `seating` genau die Spieler von `table` sind, jeder einmal, in beliebiger Reihenfolge. */
function isRearrangement(seating: readonly PlayerId[], table: readonly PlayerId[]): boolean {
  const players = new Set(seating);
  const atTable = new Set(table);
  return (
    seating.length === table.length &&
    players.size === seating.length &&
    players.size === atTable.size &&
    [...players].every((player) => atTable.has(player))
  );
}

export const EveningEdit = {
  /**
   * Übernimmt in `current` nur, was zwischen `original` (dem Stand beim Öffnen von „Abend bearbeiten“) und `edited`
   * geändert wurde. Alles andere bleibt, wie es inzwischen ist: In der gemeinsamen Runde kann ein anderes Handy den
   * Abend in der Zwischenzeit geändert haben, und das soll nicht mit dem alten Stand überschrieben werden. Ohne
   * Änderung kommt `current` selbst zurück.
   *
   * - **Beginn:** wird übernommen (als Zeit verglichen, nicht als Text). Ein beendeter Abend behält seine Dauer, das
   *   Ende verschiebt sich mit (läge es außerhalb des Bereichs von `Instant`, bleibt es). Die Einträge behalten ihre
   *   Zeiten.
   * - **Ort:** ohne Leerraum an den Rändern und höchstens {@link MAX_LOCATION_LENGTH} Zeichen lang.
   * - **Sitzordnung:** nur, wenn es genau die Spieler am Tisch sind, jeder einmal.
   * - **Erster Geber:** nur, wenn er danach am Tisch sitzt.
   */
  apply(current: Evening, original: EveningDetails, edited: EveningDetails): Evening {
    let result = current;
    if (compareInstants(edited.startedAt, original.startedAt) !== 0) {
      const startedAt = instantParts(edited.startedAt);
      const finishedAt = current.finishedAt;
      result = {
        ...result,
        startedAt: formatInstant(startedAt),
        finishedAt: finishedAt == null ? finishedAt : shifted(finishedAt, durationBetween(instantParts(current.startedAt), startedAt)),
      };
    }
    if (edited.location !== original.location) {
      result = { ...result, location: cleanLocation(edited.location) };
    }
    if (!sameSeating(edited.seating, original.seating) && isRearrangement(edited.seating, current.seating)) {
      result = { ...result, seating: [...edited.seating] };
    }
    if (edited.firstDealer !== original.firstDealer && result.seating.includes(edited.firstDealer)) {
      result = { ...result, firstDealer: edited.firstDealer };
    }
    return result;
  },

  /**
   * Der Ort, wie er gespeichert wird (Kotlin: `location.trim().take(40).trim()`): ohne Leerraum an den Rändern im
   * Sinn von Kotlin (`kotlinTrim`), dann auf {@link MAX_LOCATION_LENGTH} Zeichen gekürzt und noch einmal getrimmt.
   */
  cleanLocation,
};
