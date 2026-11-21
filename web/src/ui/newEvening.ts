/*
 * Logik des Bildschirms „Neuer Abend“ – verhaltensgleich zur Android-App (`ui/newevening/NewEveningViewModel.kt`),
 * hier als reine Funktionen über der Auswahl des Nutzers.
 */
import { MAX_LOCATION_LENGTH } from "../core/evening/EveningEdit";
import { kotlinTrim } from "../core/kotlin";
import type { Evening, Player, PlayerId, RuleSet } from "../core/model";

/** Am Tisch sitzen genau vier. */
export const PLAYERS_AT_TABLE = 4;

/**
 * Was der Nutzer bisher gewählt hat.
 *
 * @property customized `false`, solange der Nutzer an der Sitzordnung nichts geändert hat; dann gilt der Vorschlag
 *   (die Sitzordnung des letzten Abends oder die feste Vierer-Runde).
 */
export interface NewEveningSelection {
  readonly customized: boolean;
  readonly seating: readonly PlayerId[];
  readonly firstDealer: PlayerId | null;
  readonly location: string;
}

export const EMPTY_SELECTION: NewEveningSelection = { customized: false, seating: [], firstDealer: null, location: "" };

/** Was der Bildschirm anzeigt. */
export interface NewEveningView {
  /** Wählbare (nicht archivierte) Spieler in Anlagereihenfolge. */
  readonly players: readonly Player[];
  readonly seating: readonly PlayerId[];
  readonly firstDealer: PlayerId | null;
  readonly location: string;
  readonly lastSeating: readonly PlayerId[];
  /** Wie viele noch fehlen. */
  readonly missing: number;
  readonly canStart: boolean;
  readonly canUseLast: boolean;
}

/**
 * @param evenings Alle Abende, der neueste zuerst.
 */
export function newEveningView(
  allPlayers: readonly Player[],
  evenings: readonly Evening[],
  chosen: NewEveningSelection,
): NewEveningView {
  const active = allPlayers.filter((it) => !it.archived);
  const activeIds = new Set(active.map((it) => it.id));
  const lastSeating = (evenings[0]?.seating ?? []).filter((it) => activeIds.has(it));
  // Vorschlag für die feste Runde: wie beim letzten Mal, sonst die vier Spieler in Anlagereihenfolge.
  const suggestion =
    lastSeating.length === PLAYERS_AT_TABLE
      ? lastSeating
      : active.length === PLAYERS_AT_TABLE
        ? active.map((it) => it.id)
        : [];
  const seating = chosen.customized ? chosen.seating.filter((it) => activeIds.has(it)) : suggestion;
  const firstDealer = chosen.firstDealer !== null && seating.includes(chosen.firstDealer) ? chosen.firstDealer : (seating[0] ?? null);
  const missing = PLAYERS_AT_TABLE - seating.length;
  return {
    players: active,
    seating,
    firstDealer,
    location: chosen.location,
    lastSeating,
    missing,
    canStart: missing === 0 && firstDealer !== null && seating.includes(firstDealer),
    canUseLast: lastSeating.length === PLAYERS_AT_TABLE,
  };
}

/** Übernimmt beim ersten Eingriff des Nutzers die gerade angezeigte Sitzordnung als Ausgangspunkt. */
function startingFromShown(view: NewEveningView, chosen: NewEveningSelection): NewEveningSelection {
  return chosen.customized ? chosen : { ...chosen, customized: true, seating: view.seating };
}

/** Wählt einen Spieler aus oder ab. Mehr als vier lassen sich nicht auswählen. */
export function toggleSeat(view: NewEveningView, chosen: NewEveningSelection, player: PlayerId): NewEveningSelection {
  const current = startingFromShown(view, chosen);
  if (current.seating.includes(player)) return { ...current, seating: current.seating.filter((it) => it !== player) };
  if (current.seating.length < PLAYERS_AT_TABLE) return { ...current, seating: [...current.seating, player] };
  return current;
}

/** Setzt einen gerade angelegten Spieler an den Tisch, wenn noch Platz ist. */
export function seatNewPlayer(view: NewEveningView, chosen: NewEveningSelection, player: PlayerId): NewEveningSelection {
  const current = startingFromShown(view, chosen);
  if (current.seating.length >= PLAYERS_AT_TABLE || current.seating.includes(player)) return current;
  return { ...current, seating: [...current.seating, player] };
}

/**
 * Die Sitzordnung, in der ein Spieler mit seinem Nachbarn den Platz tauscht: einen Platz nach vorn (−1) oder hinten
 * (+1). Am Rand oder für jemanden, der nicht am Tisch sitzt, bleibt sie, wie sie ist (dieselbe Liste). Auch für
 * „Abend bearbeiten“.
 */
export function movedInSeating(seating: readonly PlayerId[], player: PlayerId, delta: number): readonly PlayerId[] {
  const from = seating.indexOf(player);
  const to = from + delta;
  if (from < 0 || to < 0 || to >= seating.length) return seating;
  const moved = [...seating];
  moved[from] = moved[to];
  moved[to] = player;
  return moved;
}

/** Verschiebt einen Spieler in der Sitzordnung um einen Platz nach vorn (−1) oder hinten (+1). */
export function moveSeat(view: NewEveningView, chosen: NewEveningSelection, player: PlayerId, delta: number): NewEveningSelection {
  const current = startingFromShown(view, chosen);
  const seating = movedInSeating(current.seating, player, delta);
  return seating === current.seating ? current : { ...current, seating };
}

export function setFirstDealer(chosen: NewEveningSelection, player: PlayerId): NewEveningSelection {
  return { ...chosen, firstDealer: player };
}

export function setLocation(chosen: NewEveningSelection, location: string): NewEveningSelection {
  return { ...chosen, location: location.slice(0, MAX_LOCATION_LENGTH) };
}

/** Übernimmt Spieler und Sitzordnung des letzten Abends. */
export function adoptLastSeating(view: NewEveningView, chosen: NewEveningSelection): NewEveningSelection {
  return { ...chosen, customized: true, seating: view.lastSeating, firstDealer: view.lastSeating[0] ?? null };
}

/** Der neue Abend; `null`, solange noch etwas fehlt. */
export function createEvening(view: NewEveningView, rules: RuleSet, id: string, startedAt: string): Evening | null {
  const dealer = view.firstDealer;
  if (!view.canStart || dealer === null) return null;
  return {
    id,
    startedAt,
    finishedAt: null,
    location: kotlinTrim(view.location),
    seating: [...view.seating],
    firstDealer: dealer,
    rules,
    entries: [],
  };
}
