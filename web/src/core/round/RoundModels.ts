/*
 * Die gemeinsame Runde: Mehrere Handys tragen in dieselben Abende ein und gleichen sich über einen Server ab.
 * Vertrag: docs/ARCHITEKTUR.md, Abschnitt 8. Port von `domain/round/RoundModels.kt`.
 *
 * Wie im übrigen Kern sind Kotlin-Datenklassen Schnittstellen mit `readonly`-Feldern, ihre Methoden Funktionen
 * (`toPlayer(row)`, `sameContent(row, player)`), Kotlin-`null` ist `null`. Änderungsnummern (`rev`, in Kotlin `Long`)
 * sind Zahlen: Sie zählen Änderungen einer Runde und bleiben weit unter 2^53.
 */
import type { Evening, EveningId, Player, PlayerId, RuleSet } from "../model";

/** Kennung einer gemeinsamen Runde auf dem Server. */
export type RoundId = string;

/**
 * Ein Mitglied der Runde: ein Konto und, sobald es „Das bin ich“ gesagt hat, sein Spieler.
 *
 * @property userId Kennung des Kontos beim Server.
 */
export interface RoundMember {
  readonly userId: string;
  readonly playerId: PlayerId | null;
}

/**
 * Ein Spieler, wie ihn der Server kennt.
 *
 * @property position Reihenfolge des Anlegens in der Runde.
 * @property owner Das Konto, dem der Spieler gehört; `null` bei einem Gast ohne Konto.
 * @property nameLocked Der Name ist fest: Sein Besitzer hat ihn selbst festgelegt.
 * @property deleted Der Spieler wurde gelöscht; die Zeile bleibt, damit alle Geräte davon erfahren.
 * @property rev Mit welcher Änderung der Runde die Zeile zuletzt geschrieben wurde.
 */
export interface RemotePlayer {
  readonly id: PlayerId;
  readonly name: string;
  readonly colorIndex: number;
  readonly archived: boolean;
  readonly position: number;
  readonly owner: string | null;
  readonly nameLocked: boolean;
  readonly deleted: boolean;
  readonly rev: number;
}

/** Ein `RemotePlayer` mit den Standardwerten der Kotlin-Klasse für alles, was nicht angegeben ist. */
export function remotePlayer(
  fields: Pick<RemotePlayer, "id" | "name" | "colorIndex" | "archived"> & Partial<RemotePlayer>,
): RemotePlayer {
  return { position: 0, owner: null, nameLocked: false, deleted: false, rev: 0, ...fields };
}

/** Der Spieler, wie ihn die App führt (Kotlin: `RemotePlayer.toPlayer()`). */
export function toPlayer(row: RemotePlayer): Player {
  return { id: row.id, name: row.name, colorIndex: row.colorIndex, archived: row.archived };
}

/**
 * Ob `player` in allem übereinstimmt, was Geräte ändern können: Name, Farbe und „archiviert“ (Kotlin:
 * `RemotePlayer.sameContent`).
 */
export function sameContent(row: RemotePlayer, player: Player): boolean {
  return player.id === row.id && player.name === row.name && player.colorIndex === row.colorIndex && player.archived === row.archived;
}

/**
 * Ein Abend, wie ihn der Server hat.
 *
 * @property version Zählt jede gespeicherte Fassung dieses Abends, ab 1.
 * @property deleted Der Abend wurde gelöscht; die Zeile bleibt, damit alle Geräte davon erfahren.
 * @property evening Der Inhalt; `null`, wenn sich das Dokument nicht lesen lässt.
 */
export interface RemoteEvening {
  readonly id: EveningId;
  readonly version: number;
  readonly deleted: boolean;
  readonly rev: number;
  readonly evening: Evening | null;
}

/** Die Fassung eines Abends, die dieses Gerät zuletzt vom Server gesehen oder dorthin geschrieben hat. */
export interface EveningBase {
  readonly version: number;
  readonly evening: Evening;
}

/**
 * Der Kopf einer Runde.
 *
 * @property rev Zählt jede Änderung der Runde (Abende, Spieler, Regeln, Mitglieder).
 * @property rules Das Regelwerk für neue Abende; `null`, solange keines hinterlegt ist oder es sich nicht lesen lässt.
 * @property createdBy Konto des Erstellers; `null`, wenn es ihn nicht mehr gibt.
 * @property rulesUnreadable Der Server hat ein Regelwerk, das diese Version der App nicht lesen kann. Es wird dann
 *   weder übernommen noch überschrieben.
 */
export interface RoundHead {
  readonly id: RoundId;
  readonly name: string;
  readonly rev: number;
  readonly rules: RuleSet | null;
  readonly createdBy: string | null;
  readonly rulesUnreadable: boolean;
}

/**
 * Eine Runde des angemeldeten Kontos in der Übersicht.
 *
 * @property memberNames Die Namen der Spieler, die Mitglieder mit „Das bin ich“ übernommen haben (auch der eigene),
 *   sortiert; damit lassen sich Runden desselben Namens unterscheiden. Leer, wenn noch niemand einen Namen hat oder
 *   der Server die Angabe nicht kennt (vor dem 21.11.2026).
 */
export interface RoundSummary {
  readonly id: RoundId;
  readonly name: string;
  readonly rev: number;
  readonly memberCount: number;
  readonly myPlayerId: PlayerId | null;
  readonly isCreator: boolean;
  readonly memberNames: readonly string[];
}

/** Eine `RoundSummary` mit den Standardwerten der Kotlin-Klasse für alles, was nicht angegeben ist. */
export function roundSummary(
  fields: Pick<RoundSummary, "id" | "name" | "rev" | "memberCount" | "myPlayerId" | "isCreator"> & Partial<RoundSummary>,
): RoundSummary {
  return { memberNames: [], ...fields };
}

/** Was eine Einladung verrät, bevor man beitritt. */
export interface InviteInfo {
  readonly roundId: RoundId;
  readonly roundName: string;
  readonly memberCount: number;
  readonly alreadyMember: boolean;
}

/** Antwort auf ein gespeichertes Abend-Dokument. */
export interface SavedEvening {
  readonly version: number;
  readonly rev: number;
}

/**
 * Die Runde, mit der dieses Gerät verbunden ist.
 *
 * @property userId Das angemeldete Konto.
 * @property playerId Der eigene Spieler; `null`, solange „Das bin ich“ noch aussteht.
 * @property cursor Das größte `rev`, das dieses Gerät vollständig verarbeitet hat.
 * @property rulesBase Das zuletzt abgeglichene Regelwerk für neue Abende.
 * @property unreadable Abende des Servers, die dieses Gerät nicht lesen oder nicht speichern konnte (zum Beispiel von
 *   einer neueren App), jede Kennung höchstens einmal (Kotlin: `Set<EveningId>`). Sie liegen hinter `cursor` und werden
 *   deshalb bei jedem Abgleich einzeln neu gelesen.
 */
export interface RoundLink {
  readonly roundId: RoundId;
  readonly roundName: string;
  readonly userId: string;
  readonly playerId: PlayerId | null;
  readonly isCreator: boolean;
  readonly cursor: number;
  readonly rulesBase: RuleSet | null;
  readonly members: readonly RoundMember[];
  readonly unreadable: readonly EveningId[];
}

/** Ein `RoundLink` mit den Standardwerten der Kotlin-Klasse für alles, was nicht angegeben ist. */
export function roundLink(fields: Pick<RoundLink, "roundId" | "roundName" | "userId"> & Partial<RoundLink>): RoundLink {
  return { playerId: null, isCreator: false, cursor: 0, rulesBase: null, members: [], unreadable: [], ...fields };
}

/**
 * Was beim Reden mit dem Server schiefgehen kann. Ab `NOT_MEMBER` sind die Namen in Kleinbuchstaben die Fehlercodes
 * des Servers; davor stehen die Fehler der App selbst:
 * - `OFFLINE`: keine Verbindung zum Server,
 * - `SIGNED_OUT`: die Anmeldung ist abgelaufen oder wurde widerrufen,
 * - `SERVER`: der Server antwortet mit einem Fehler (zum Beispiel, weil das Projekt pausiert ist),
 * - `NOT_CONFIGURED`: die App kennt keinen Server (keine Konfiguration).
 */
export type RoundError =
  | "OFFLINE"
  | "SIGNED_OUT"
  | "SERVER"
  | "NOT_CONFIGURED"
  | "NOT_MEMBER"
  | "INVALID_NAME"
  | "NAME_TAKEN"
  | "NAME_LOCKED"
  | "ALREADY_CLAIMED"
  | "PLAYER_TAKEN"
  | "PLAYER_OWNED"
  | "INVALID_INVITE"
  | "LIMIT_REACHED"
  | "TOO_LARGE"
  | "INVALID_DOC";

/** Alle Fehler in der Reihenfolge der Kotlin-Aufzählung. */
export const ROUND_ERRORS: readonly RoundError[] = [
  "OFFLINE",
  "SIGNED_OUT",
  "SERVER",
  "NOT_CONFIGURED",
  "NOT_MEMBER",
  "INVALID_NAME",
  "NAME_TAKEN",
  "NAME_LOCKED",
  "ALREADY_CLAIMED",
  "PLAYER_TAKEN",
  "PLAYER_OWNED",
  "INVALID_INVITE",
  "LIMIT_REACHED",
  "TOO_LARGE",
  "INVALID_DOC",
];

/** Was in Kotlin im `companion object` von `RoundError` steht. */
export const RoundError = {
  /** Der Fehler zu einem Fehlercode des Servers (zum Beispiel `name_taken`); `null`, wenn der Code unbekannt ist. */
  fromCode(code: string): RoundError | null {
    const first = ROUND_ERRORS.indexOf("NOT_MEMBER");
    return ROUND_ERRORS.find((error, index) => index >= first && error.toLowerCase() === code) ?? null;
  },
};

/**
 * Ein Aufruf des Servers ist gescheitert.
 *
 * @property detail Kurzer technischer Zusatz (zum Beispiel „HTTP 503“); nie ein Schlüssel, ein Token oder Inhalt.
 */
export class RoundException extends Error {
  readonly error: RoundError;
  readonly detail: string | null;

  constructor(error: RoundError, detail: string | null = null, cause?: unknown) {
    super(detail === null || detail === "" ? error : `${error}: ${detail}`, cause === undefined ? undefined : { cause });
    this.name = "RoundException";
    this.error = error;
    this.detail = detail;
  }
}
