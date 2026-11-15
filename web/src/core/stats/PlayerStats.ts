/*
 * Kennzahlen eines Spielers (Kotlin: `domain/stats/PlayerStats.kt`).
 *
 * Wie überall im Kern sind die abgeleiteten Eigenschaften der Kotlin-Klassen (`lost`, `rate` …) hier fertig
 * berechnete Felder. Die kleinen Funktionen `roleRecord(…)` und `playerStats(…)` entsprechen den Konstruktoren und
 * berechnen sie.
 */
import { intSub } from "../kotlin";
import type { EntryId, EveningId, GameType, PlayerId } from "../model";

/**
 * Eine Quote mit Zähler und Nenner: wie oft etwas vorkam (`played`) und wie oft es gut ausging (`won`)
 * (Kotlin: `RoleRecord`).
 *
 * @property lost Wie oft es schlecht ausging.
 * @property rate Anteil der Siege zwischen 0 und 1; `null`, wenn es keinen einzigen Fall gab.
 */
export interface RoleRecord {
  readonly played: number;
  readonly won: number;
  readonly lost: number;
  readonly rate: number | null;
}

export function roleRecord(played: number, won: number): RoleRecord {
  return { played, won, lost: intSub(played, won), rate: played === 0 ? null : won / played };
}

/**
 * Ein einzelner Betrag aus einem gespielten Spiel, samt der Stelle, an der das Spiel zu finden ist
 * (Kotlin: `GameAmount`).
 *
 * @property amount Betrag in Cent (positiv = gewonnen, negativ = verloren).
 */
export interface GameAmount {
  readonly eveningId: EveningId;
  readonly entryId: EntryId;
  readonly gameNumber: number;
  readonly amount: number;
}

/** Der Saldo eines Spielers an einem Abend in Cent (Kotlin: `EveningBalance`). */
export interface EveningBalance {
  readonly eveningId: EveningId;
  readonly balance: number;
}

/**
 * Alle Kennzahlen eines Spielers, für einen Abend oder über viele Abende hinweg (Kotlin: `PlayerStats`).
 *
 * Gezählt werden gespielte Spiele, die abgerechnet werden konnten. „Gewonnen“ heißt immer: Der Spieler stand auf der
 * Gewinnerseite, egal in welcher Rolle. Beträge sind ganze Cent.
 *
 * @property evenings Abende, an denen der Spieler am Tisch saß und mindestens ein Spiel gespielt wurde.
 * @property games Gespielte Spiele am Tisch.
 * @property balance Summe aller Beträge, auch aus freien Buchungen und auch von Abenden ohne ein gespieltes Spiel.
 * @property gamesWon Spiele auf der Gewinnerseite.
 * @property asDeclarer Spiele als Ansager (Spieler). Über Kreuz zählt in keiner der drei Rollen, weil dort niemand
 *   ansagt; deshalb können `asDeclarer`, `asPartner` und `asOpponent` zusammen kleiner sein als `games`.
 * @property asPartner Rufspiele als Mitspieler des Ansagers.
 * @property asOpponent Spiele als Gegenspieler.
 * @property declarerByType Spiele als Ansager, getrennt nach Spielart. Jede Spielart ist enthalten (Reihenfolge wie
 *   `GAME_TYPES`), notfalls mit 0; Über Kreuz steht immer bei 0.
 * @property klopferGiven Spiele, in denen der Spieler geklopft (gelegt) hat, und wie viele davon er gewann.
 * @property klopferEffect Was das eigene Klopfen gebracht oder gekostet hat: über alle selbst geklopften Spiele die
 *   Summe aus gebuchtem Betrag minus dem Betrag desselben Spiels ohne den eigenen Klopfer, mit dem Regelwerk des
 *   Abends exakt nachgerechnet. Mit der Schafkopf-Regel ist das die Hälfte des gebuchten Betrags; mit der
 *   Topf-Teilung kann es wegen der Rest-Cent um einen Cent davon abweichen.
 * @property kontraGiven Spiele, in denen der Spieler Kontra gegeben hat (nur wenn eingetragen ist, wer es war).
 * @property retourGiven Spiele, in denen der Spieler Retour gegeben hat (nur wenn eingetragen ist, wer es war).
 * @property schneiderWon Spiele mit Schneider auf der Gewinnerseite. Bei Tout und Sie zählt die Stufe nicht.
 * @property schneiderLost Spiele mit Schneider auf der Verliererseite.
 * @property schwarzWon Spiele mit Schwarz auf der Gewinnerseite. Schwarz zählt nicht zusätzlich als Schneider.
 * @property schwarzLost Spiele mit Schwarz auf der Verliererseite.
 * @property toutPlayed Tout und Sie als Ansager. Gezählt wird, was eingetragen und abgerechnet wurde, auch ein
 *   regelwidrig beim Rufspiel eingetragenes Tout. Nur bei Über Kreuz zählt es nicht, weil dort niemand ansagt.
 * @property biggestWin Höchster Einzelbetrag aus einem Spiel; `null`, wenn es keinen Gewinn gab.
 * @property biggestLoss Niedrigster Einzelbetrag aus einem Spiel; `null`, wenn es keinen Verlust gab.
 * @property longestWinStreak Längste Folge gewonnener Spiele innerhalb eines Abends. Zusammengeworfene Blätter,
 *   Bock-Auslöser und freie Buchungen unterbrechen die Folge nicht.
 * @property longestLoseStreak Längste Folge verlorener Spiele innerhalb eines Abends.
 * @property nixerTriggers Wie oft der Spieler mit 6 Nixern eine Bockrunde ausgelöst hat.
 * @property eveningWins Abende auf Platz 1, auch geteilt. Stehen alle gleich, gibt es keinen Sieger.
 * @property eveningLastPlaces Abende auf dem letzten Platz, auch geteilt. Stehen alle gleich, gibt es keinen Letzten.
 * @property bestEvening Abend mit dem höchsten Saldo; `null`, wenn kein Abend zählt.
 * @property worstEvening Abend mit dem niedrigsten Saldo; `null`, wenn kein Abend zählt.
 * @property gamesLost Spiele auf der Verliererseite (abgeleitet).
 * @property overall Alle Spiele als Quote: gespielt und gewonnen (abgeleitet).
 * @property klopferCount Wie oft der Spieler geklopft hat (abgeleitet, `klopferGiven.played`).
 * @property klopferLost Wie oft der Spieler nach dem eigenen Klopfen verloren hat (abgeleitet, `klopferGiven.lost`).
 */
export interface PlayerStats {
  readonly player: PlayerId;
  readonly evenings: number;
  readonly games: number;
  readonly balance: number;
  readonly gamesWon: number;
  readonly asDeclarer: RoleRecord;
  readonly asPartner: RoleRecord;
  readonly asOpponent: RoleRecord;
  readonly declarerByType: ReadonlyMap<GameType, RoleRecord>;
  readonly klopferGiven: RoleRecord;
  readonly klopferEffect: number;
  readonly kontraGiven: RoleRecord;
  readonly retourGiven: RoleRecord;
  readonly schneiderWon: number;
  readonly schneiderLost: number;
  readonly schwarzWon: number;
  readonly schwarzLost: number;
  readonly toutPlayed: RoleRecord;
  readonly biggestWin: GameAmount | null;
  readonly biggestLoss: GameAmount | null;
  readonly longestWinStreak: number;
  readonly longestLoseStreak: number;
  readonly nixerTriggers: number;
  readonly eveningWins: number;
  readonly eveningLastPlaces: number;
  readonly bestEvening: EveningBalance | null;
  readonly worstEvening: EveningBalance | null;
  readonly gamesLost: number;
  readonly overall: RoleRecord;
  readonly klopferCount: number;
  readonly klopferLost: number;
}

/** Die Felder, aus denen `playerStats` die abgeleiteten berechnet. */
export type PlayerStatsFields = Omit<PlayerStats, "gamesLost" | "overall" | "klopferCount" | "klopferLost">;

export function playerStats(fields: PlayerStatsFields): PlayerStats {
  return {
    ...fields,
    gamesLost: intSub(fields.games, fields.gamesWon),
    overall: roleRecord(fields.games, fields.gamesWon),
    klopferCount: fields.klopferGiven.played,
    klopferLost: fields.klopferGiven.lost,
  };
}
