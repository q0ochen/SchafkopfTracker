/*
 * Testdaten: die beiden von Hand durchgerechneten Abende aus dem Kotlin-Kern
 * (domain/src/test/kotlin/io/github/q0ochen/schafkopf/domain/stats/RoundEvenings.kt), Regelwerk der Runde.
 * Die Tabellen dort zeigen jeden Betrag und jeden Stand; die Tests der Web-App prüfen dieselben Zahlen.
 */
import { instantFromEpochMillis, instantToEpochMillis } from "../instant";
import type { Evening, GameEntry, InstantText, PlayerId } from "../model";
import { RuleSets } from "../rules/RuleSet";

/** Ein Spiel mit den Standardwerten der Kotlin-Klasse `GameEntry` für alles, was nicht angegeben ist. */
export function game(
  id: string,
  recordedAt: InstantText,
  fields: Partial<Omit<GameEntry, "kind" | "id" | "recordedAt">> &
    Pick<GameEntry, "type" | "declarer" | "declarerWon">,
): GameEntry {
  return {
    kind: "game",
    id,
    recordedAt,
    suit: null,
    partner: null,
    level: "NORMAL",
    laufende: 0,
    announcement: "NONE",
    klopfer: [],
    kontra: "NONE",
    kontraBy: null,
    retourBy: null,
    bockOverride: "AUTO",
    declarerEyes: null,
    note: "",
    ...fields,
  };
}

/** Ein Zeitpunkt so viele Minuten nach `start`. */
export function minutesAfter(start: InstantText, minutes: number): InstantText {
  return instantFromEpochMillis(instantToEpochMillis(start) + minutes * 60_000);
}

const A: PlayerId = "A";
const B: PlayerId = "B";
const C: PlayerId = "C";
const D: PlayerId = "D";
const E: PlayerId = "E";

const firstStart = "2026-11-07T18:30:00Z";
const at = (minutes: number): InstantText => minutesAfter(firstStart, minutes);

/**
 * Erster Abend: A, B, C, D im Uhrzeigersinn. Endstand A +310, B +30, C +1160, D −1500.
 *
 * ```
 * Spiel    1    2     3     4     5     6      7      8      9     10     11     12     13     14
 * A       10   30   -50   -30   250   310     70    320    280    340    360    260    240    310
 * B       10  -10   230   250   530   470    230    -20     20   -160   -180    120    100     30
 * C      -10   10   -70   -90   190   130    850   1050   1090   1150   1170   1070   1090   1160
 * D      -10  -30  -110  -130  -970  -910  -1150  -1350  -1390  -1330  -1350  -1450  -1430  -1500
 * ```
 */
export const firstEvening: Evening = {
  id: "runde-1",
  startedAt: firstStart,
  finishedAt: at(120),
  location: "Beim Wirt",
  seating: [A, B, C, D],
  firstDealer: A,
  rules: RuleSets.standard(),
  entries: [
    game("r01", at(5), { type: "RUFSPIEL", declarer: A, partner: B, declarerWon: true }),
    game("r02", at(12), { type: "UEBER_KREUZ", declarer: A, partner: C, declarerWon: true, level: "SCHNEIDER" }),
    game("r03", at(20), { type: "FARBSOLO", suit: "HERZ", declarer: B, declarerWon: true, laufende: 3 }),
    game("r04", at(27), { type: "RUFSPIEL", declarer: C, partner: D, declarerWon: false, klopfer: [C] }),
    { kind: "thrown_in", id: "r05", recordedAt: at(30) },
    game("r06", at(38), { type: "WENZ", declarer: D, declarerWon: false, laufende: 2, klopfer: [A, D] }),
    { kind: "bock", id: "r07", recordedAt: at(41), reason: "SECHS_NIXER", player: B, handThrownIn: true },
    game("r08", at(48), { type: "RUFSPIEL", declarer: A, partner: D, declarerWon: true, level: "SCHWARZ" }),
    game("r09", at(56), { type: "GEIER", declarer: C, declarerWon: true, level: "SCHNEIDER", klopfer: [C] }),
    { kind: "manual", id: "r10", recordedAt: at(58), amounts: new Map([[A, 50], [B, -50]]), note: "Wette" },
    game("r11", at(65), { type: "UEBER_KREUZ", declarer: B, partner: D, declarerWon: false, laufende: 4, klopfer: [D] }),
    game("r12", at(72), { type: "RUFSPIEL", declarer: B, partner: C, declarerWon: true, klopfer: [C] }),
    game("r13", at(80), { type: "FARBSOLO", suit: "EICHEL", declarer: B, declarerWon: false, level: "SCHNEIDER" }),
    game("r14", at(87), { type: "RUFSPIEL", declarer: D, partner: B, declarerWon: false, klopfer: [D] }),
    game("r15", at(95), { type: "WENZ", declarer: B, declarerWon: true, level: "SCHWARZ", laufende: 3 }),
    game("r16", at(102), { type: "RUFSPIEL", declarer: A, partner: B, declarerWon: false, laufende: 2, klopfer: [D] }),
    game("r17", at(110), { type: "RUFSPIEL", declarer: C, partner: A, declarerWon: true, level: "SCHNEIDER", laufende: 5 }),
  ],
};

const secondStart = "2026-11-14T18:30:00Z";
const later = (minutes: number): InstantText => minutesAfter(secondStart, minutes);

/** Zweiter Abend, eine Woche später: E statt D, im Uhrzeigersinn E, A, C, B. Endstand E +880, A −110, C −850, B +80. */
export const secondEvening: Evening = {
  id: "runde-2",
  startedAt: secondStart,
  finishedAt: null,
  location: "",
  seating: [E, A, C, B],
  firstDealer: E,
  rules: RuleSets.standard(),
  entries: [
    game("s01", later(5), { type: "RUFSPIEL", declarer: A, partner: E, declarerWon: true, klopfer: [A, B] }),
    game("s02", later(12), {
      type: "RUFSPIEL",
      declarer: C,
      partner: B,
      declarerWon: false,
      level: "SCHWARZ",
      laufende: 3,
      klopfer: [B],
    }),
    game("s03", later(20), { type: "UEBER_KREUZ", declarer: A, partner: B, declarerWon: true }),
    game("s04", later(28), { type: "GEIER", declarer: E, declarerWon: true, laufende: 2, klopfer: [A] }),
    { kind: "bock", id: "s05", recordedAt: later(30), reason: "MANUAL", player: null, handThrownIn: true },
    game("s06", later(37), { type: "RUFSPIEL", declarer: B, partner: E, declarerWon: true, level: "SCHWARZ", klopfer: [A, B] }),
    game("s07", later(45), { type: "FARBSOLO", suit: "GRAS", declarer: C, declarerWon: false }),
    { kind: "manual", id: "s08", recordedAt: later(50), amounts: new Map([[B, 30], [E, -30]]), note: "Getränke" },
  ],
};
