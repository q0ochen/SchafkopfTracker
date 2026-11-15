/*
 * Logik des Formulars „Spiel eintragen“ – verhaltensgleich zur Android-App (`ui/gameform/GameFormViewModel.kt`),
 * hier als reine Funktionen: Jede Aktion bekommt den bisherigen Entwurf und liefert einen neuen.
 */
import { append, replace } from "../core/evening/Evening";
import { EveningCalculator } from "../core/evening/EveningCalculator";
import type {
  Announcement,
  BockOverride,
  Evening,
  GameEntry,
  GameType,
  InstantText,
  KontraLevel,
  PlayerId,
  ResultLevel,
  Suit,
} from "../core/model";
import { GAME_TYPES } from "../core/model";
import { allowsSie, allowsTout, hasDeclarer, isSolo, laufendeMax, laufendeMin } from "../core/rules/RuleSet";
import type { GameFacts, ValueBreakdown } from "../core/rules/ScoreCalculator";
import { ScoreCalculator } from "../core/rules/ScoreCalculator";

/** Was im Formular gerade gewählt ist. */
export interface GameDraft {
  readonly type: GameType;
  readonly suit: Suit | null;
  readonly declarer: PlayerId | null;
  readonly partner: PlayerId | null;
  /** Wer gelegt hat; jeder höchstens einmal, in der Reihenfolge des Antippens. */
  readonly klopfer: readonly PlayerId[];
  readonly kontra: KontraLevel;
  readonly kontraBy: PlayerId | null;
  readonly level: ResultLevel;
  readonly laufende: number;
  readonly announcement: Announcement;
  readonly bockOverride: BockOverride;
  /** Nur beim Ändern eines Spiels: das gewählte Ergebnis. */
  readonly declarerWon: boolean;
}

/** Was noch fehlt, bevor das Spiel gespeichert werden kann. */
export type MissingInput = "DECLARER" | "PARTNER";

/** Alles, was das Formular anzeigt. */
export interface GameFormState {
  readonly editing: boolean;
  /** Nummer des Spiels: beim Ändern seine eigene, sonst die nächste. */
  readonly gameNumber: number;
  readonly draft: GameDraft;
  /** Bock-Stufe, die für dieses Spiel gilt (laut Zähler oder von Hand gesetzt). */
  readonly bockExponent: number;
  /** Bock-Stufe laut Zähler, unabhängig von der Auswahl im Formular. */
  readonly autoBockExponent: number;
  readonly breakdown: ValueBreakdown | null;
  /** Betrag des Ansagers bei Gewinn (bei Verlust derselbe Betrag mit Minus). */
  readonly declarerAmount: number;
  readonly missing: MissingInput | null;
  readonly canSave: boolean;
}

/** Ein leerer Entwurf. */
export function emptyDraft(type: GameType): GameDraft {
  return {
    type,
    suit: null,
    declarer: null,
    partner: null,
    klopfer: [],
    kontra: "NONE",
    kontraBy: null,
    level: "NORMAL",
    laufende: 0,
    announcement: "NONE",
    bockOverride: "AUTO",
    declarerWon: true,
  };
}

/** Die erste erlaubte Spielart des Abends (sonst Rufspiel). */
export function defaultType(evening: Evening): GameType {
  return GAME_TYPES.find((type) => evening.rules.enabledTypes.includes(type)) ?? "RUFSPIEL";
}

/** Der Entwurf zu einem schon eingetragenen Spiel. */
export function draftOf(entry: GameEntry): GameDraft {
  return {
    type: entry.type,
    suit: entry.suit,
    declarer: entry.declarer,
    partner: entry.partner,
    klopfer: [...entry.klopfer],
    kontra: entry.kontra,
    kontraBy: entry.kontraBy,
    level: entry.level,
    laufende: entry.laufende,
    announcement: entry.announcement,
    bockOverride: entry.bockOverride,
    declarerWon: entry.declarerWon,
  };
}

/** Das Spiel mit dieser Kennung, falls es eines ist. */
export function findGame(evening: Evening, entryId: string | null): GameEntry | null {
  if (entryId === null) return null;
  const entry = evening.entries.find((it) => it.id === entryId);
  return entry?.kind === "game" ? entry : null;
}

/** Rechnet aus, was das Formular zu einem Entwurf anzeigt. */
export function formState(evening: Evening, existing: GameEntry | null, draft: GameDraft): GameFormState {
  const before: Evening = existing
    ? { ...evening, entries: takeWhile(evening.entries, (it) => it.id !== existing.id) }
    : evening;
  const computedBefore = EveningCalculator.compute(before);
  const auto = computedBefore.nextBockExponent;
  const bockExponent = draft.bockOverride === "AUTO" ? auto : draft.bockOverride === "FORCE_ON" ? Math.max(1, auto) : 0;
  const missing: MissingInput | null =
    draft.declarer === null ? "DECLARER" : !isSolo(draft.type) && draft.partner === null ? "PARTNER" : null;
  const facts: GameFacts | null =
    draft.declarer === null
      ? null
      : {
          type: draft.type,
          players: evening.seating,
          declarer: draft.declarer,
          partner: isSolo(draft.type) ? null : draft.partner,
          declarerWon: true,
          level: draft.level,
          laufende: draft.laufende,
          announcement: draft.announcement,
          klopferCount: draft.klopfer.length,
          kontra: draft.kontra,
          bockExponent,
        };
  const breakdown = facts === null ? null : ScoreCalculator.breakdown(facts, evening.rules);
  // Sitzt der Ansager nicht am Tisch (das kann nur aus beschädigten Daten stammen), gibt es für ihn keinen Betrag:
  // Das Formular zeigt dann 0.
  const declarerAmount =
    facts !== null && missing === null ? (ScoreCalculator.amounts(facts, evening.rules).get(facts.declarer) ?? 0) : 0;
  return {
    editing: existing !== null,
    gameNumber: computedBefore.gamesPlayed + 1,
    draft,
    bockExponent,
    autoBockExponent: auto,
    breakdown,
    declarerAmount,
    missing,
    canSave: missing === null,
  };
}

/** Wer dem Spieler am Tisch gegenübersitzt (zwei Plätze weiter). */
export function oppositeOf(player: PlayerId, seating: readonly PlayerId[]): PlayerId | null {
  const index = seating.indexOf(player);
  if (index < 0 || seating.length === 0) return null;
  return seating[(index + Math.floor(seating.length / 2)) % seating.length];
}

/** Wechselt die Spielart; was zur neuen Spielart nicht passt, wird zurückgesetzt. */
export function selectType(d: GameDraft, evening: Evening, type: GameType): GameDraft {
  const rules = evening.rules;
  let partner: PlayerId | null;
  if (isSolo(type)) partner = null;
  // Über Kreuz: Zum Gewählten gehört immer sein Gegenüber.
  else if (!hasDeclarer(type)) partner = d.declarer === null ? null : oppositeOf(d.declarer, evening.seating);
  // Von Über Kreuz zurück zum Rufspiel: Der Mitspieler wird neu gewählt.
  else if (!hasDeclarer(d.type)) partner = null;
  else partner = d.partner;
  let announcement: Announcement = "NONE";
  if (d.announcement === "TOUT" && allowsTout(rules, type)) announcement = "TOUT";
  if (d.announcement === "SIE" && allowsSie(rules, type)) announcement = "SIE";
  return {
    ...d,
    type,
    partner,
    suit: null,
    laufende: d.laufende <= laufendeMax(rules, type) && d.laufende >= laufendeMin(rules, type) ? d.laufende : 0,
    announcement,
    kontraBy: null,
  };
}

/**
 * Antippen eines Spielers. Einzelspiel: der Angetippte spielt. Rufspiel: der erste ist der Spieler, der zweite der
 * Mitspieler; ein dritter ersetzt den Mitspieler. Über Kreuz: Ein Tipp wählt das Paar aus dem Angetippten und seinem
 * Gegenüber. Erneutes Antippen hebt die Wahl auf.
 */
export function tapPlayer(d: GameDraft, evening: Evening, player: PlayerId): GameDraft {
  let changed: GameDraft;
  if (!hasDeclarer(d.type)) {
    changed =
      player === d.declarer || player === d.partner
        ? { ...d, declarer: null, partner: null }
        : { ...d, declarer: player, partner: oppositeOf(player, evening.seating) };
  } else if (d.declarer === player) {
    changed = { ...d, declarer: null, partner: null };
  } else if (d.partner === player) {
    changed = { ...d, partner: null };
  } else if (d.declarer === null) {
    changed = { ...d, declarer: player };
  } else if (isSolo(d.type)) {
    changed = { ...d, declarer: player };
  } else {
    changed = { ...d, partner: player };
  }
  // Wer Kontra gegeben hat, muss Gegenspieler sein.
  if (changed.kontraBy === changed.declarer || changed.kontraBy === changed.partner) changed = { ...changed, kontraBy: null };
  return changed;
}

/** Wählt die Farbe (Rufsau oder Trumpf); erneutes Antippen hebt die Wahl auf. */
export function selectSuit(d: GameDraft, suit: Suit): GameDraft {
  return { ...d, suit: suit === d.suit ? null : suit };
}

/** Schaltet „gelegt“ für einen Spieler um. */
export function toggleKlopfer(d: GameDraft, player: PlayerId): GameDraft {
  return {
    ...d,
    klopfer: d.klopfer.includes(player) ? d.klopfer.filter((it) => it !== player) : [...d.klopfer, player],
  };
}

export function selectKontra(d: GameDraft, level: KontraLevel): GameDraft {
  return { ...d, kontra: level, kontraBy: level === "NONE" ? null : d.kontraBy };
}

export function selectKontraBy(d: GameDraft, player: PlayerId): GameDraft {
  return { ...d, kontraBy: player === d.kontraBy ? null : player };
}

export function selectLevel(d: GameDraft, level: ResultLevel): GameDraft {
  return { ...d, level };
}

export function selectLaufende(d: GameDraft, count: number): GameDraft {
  return { ...d, laufende: count };
}

/** Tout oder Sie an- oder abwählen. Eine Ansage schließt Schneider und Schwarz aus; Sie ist immer gewonnen. */
export function selectAnnouncement(d: GameDraft, announcement: Announcement): GameDraft {
  const chosen: Announcement = d.announcement === announcement ? "NONE" : announcement;
  return {
    ...d,
    announcement: chosen,
    level: chosen === "NONE" ? d.level : "NORMAL",
    declarerWon: chosen === "SIE" ? true : d.declarerWon,
  };
}

/**
 * Schaltet „Bock“ für dieses Spiel ein oder aus. Stimmt die Wahl mit dem Bock-Zähler überein, entscheidet wieder der
 * Zähler; sonst gilt die Wahl von Hand.
 */
export function setBock(d: GameDraft, on: boolean, autoBockExponent: number): GameDraft {
  const auto = autoBockExponent > 0;
  const bockOverride: BockOverride = on === auto ? "AUTO" : on ? "FORCE_ON" : "FORCE_OFF";
  return { ...d, bockOverride };
}

export function selectResult(d: GameDraft, won: boolean): GameDraft {
  return { ...d, declarerWon: won };
}

/** Werkzeuge für neue Einträge (Kennung und Uhrzeit), damit Tests sie festlegen können. */
export interface EntryFactory {
  newId(): string;
  now(): InstantText;
}

/**
 * Speichert den Entwurf in den Abend: neu angehängt oder anstelle des bisherigen Spiels. `won` ist beim neuen Spiel
 * das angetippte Ergebnis, beim Ändern `null` (dann gilt das im Formular gewählte). Fehlt noch eine Angabe, bleibt
 * der Abend unverändert (`null`).
 */
export function saveDraft(
  evening: Evening,
  existing: GameEntry | null,
  d: GameDraft,
  won: boolean | null,
  factory: EntryFactory,
): Evening | null {
  const declarer = d.declarer;
  if (declarer === null || (!isSolo(d.type) && d.partner === null)) return null;
  const entry: GameEntry = {
    kind: "game",
    id: existing?.id ?? factory.newId(),
    recordedAt: existing?.recordedAt ?? factory.now(),
    type: d.type,
    suit: d.suit,
    declarer,
    partner: isSolo(d.type) ? null : d.partner,
    declarerWon: d.announcement === "SIE" || (won ?? d.declarerWon),
    level: d.announcement === "NONE" ? d.level : "NORMAL",
    laufende: d.laufende,
    announcement: d.announcement,
    klopfer: [...d.klopfer],
    kontra: d.kontra,
    kontraBy: d.kontraBy,
    retourBy: d.kontra === "RETOUR" ? (existing?.retourBy ?? null) : null,
    bockOverride: d.bockOverride,
    declarerEyes: existing?.declarerEyes ?? null,
    note: existing?.note ?? "",
  };
  return existing ? replace(evening, entry) : append(evening, entry);
}

/** Die Laufenden, die das Formular zur Wahl stellt: „keine“ und die zählenden. */
export function laufendeChoices(evening: Evening, type: GameType): number[] {
  const min = laufendeMin(evening.rules, type);
  const max = laufendeMax(evening.rules, type);
  const counts = [0];
  // Begrenzt, damit beschädigte Regeln (riesige Werte) die Seite nicht lahmlegen.
  for (let count = Math.max(min, 1); count <= max && counts.length <= 20; count++) counts.push(count);
  return counts;
}

function takeWhile<T>(items: readonly T[], predicate: (item: T) => boolean): T[] {
  const result: T[] = [];
  for (const item of items) {
    if (!predicate(item)) break;
    result.push(item);
  }
  return result;
}
