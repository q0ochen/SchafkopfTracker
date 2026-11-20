/* Der Hinweis auf vermutlich doppelt eingetragene Spiele. Port von `DuplicateCheckTest.kt`. */
import { describe, expect, it } from "vitest";
import { append } from "../evening/Evening";
import type { EveningEntry, GameEntry, ThrownInEntry } from "../model";
import { DuplicateCheck } from "./DuplicateCheck";
import { EveningMerge } from "./EveningMerge";
import { after, evening, game, ids, P2, P3, P4 } from "./testing/fixtures";

function suspects(...entries: EveningEntry[]): string[] {
  return [...DuplicateCheck.suspects(evening(entries))];
}

describe("DuplicateCheck.suspects", () => {
  it("zwei gleiche Spiele kurz hintereinander: das hintere wird gemeldet", () => {
    expect(suspects(game("eins", 600), game("zwei", 630))).toEqual(["zwei"]);
  });

  it("Kennung, Zeitpunkt und Notiz dürfen sich unterscheiden", () => {
    expect(suspects(game("eins", 600, { note: "schön gespielt" }), game("zwei", 601, { note: "" }))).toEqual(["zwei"]);
  });

  it("die Reihenfolge der Uhrzeiten spielt keine Rolle", () => {
    // Das zuerst eingetragene Spiel steht nach dem Zusammenführen hinten.
    expect(suspects(game("eins", 660), game("zwei", 600))).toEqual(["zwei"]);
  });

  it("genau zwei Minuten Abstand zählen noch, mehr nicht", () => {
    expect(suspects(game("eins", 600), game("zwei", 720))).toEqual(["zwei"]);
    expect(suspects(game("eins", 600), game("zwei", 721))).toEqual([]);
    expect(suspects(game("eins", 600), { ...game("zwei", 720), recordedAt: after(720, 1) })).toEqual([]);
    expect(DuplicateCheck.WINDOW_SECONDS).toBe(120);
  });

  it("jeder Unterschied im Spiel selbst macht es zu einem anderen Spiel", () => {
    const first = game("eins", 600);
    const second = (fields: Partial<GameEntry>): GameEntry => ({ ...game("zwei", 610), ...fields });

    expect(suspects(first, second({ declarerWon: false }))).toEqual([]);
    expect(suspects(first, second({ declarer: P3 }))).toEqual([]);
    expect(suspects(first, second({ partner: P4 }))).toEqual([]);
    expect(suspects(first, second({ laufende: 3 }))).toEqual([]);
    expect(suspects(first, second({ type: "WENZ", partner: null }))).toEqual([]);
    expect(suspects(first, second({ klopfer: [P2] }))).toEqual([]);
    expect(suspects(first, second({ kontra: "KONTRA" }))).toEqual([]);
    // Wer gelegt hat, ist eine Menge: Die Reihenfolge macht keinen Unterschied.
    expect(suspects({ ...first, klopfer: [P2, P3] }, second({ klopfer: [P3, P2] }))).toEqual(["zwei"]);
  });

  it("nur direkt aufeinander folgende Spiele werden verglichen", () => {
    const between: ThrownInEntry = { kind: "thrown_in", id: "weiter", recordedAt: after(605) };

    expect(suspects(game("eins", 600), between, game("zwei", 610))).toEqual([]);
    expect(suspects(game("eins", 600), game("anders", 605, { won: false }), game("zwei", 610))).toEqual([]);
  });

  it("dreimal dasselbe Spiel meldet das zweite und das dritte", () => {
    expect(suspects(game("eins", 600), game("zwei", 610), game("drei", 620))).toEqual(["zwei", "drei"]);
  });

  it("ein Abend ohne Spiele oder mit einem Spiel hat keine Verdachtsfälle", () => {
    expect(suspects()).toEqual([]);
    expect(suspects(game("eins", 600))).toEqual([]);
    expect(suspects({ kind: "thrown_in", id: "a", recordedAt: after(1) }, { kind: "thrown_in", id: "b", recordedAt: after(2) })).toEqual([]);
  });

  it("zwei Handys tragen dasselbe Spiel ein: nach dem Zusammenführen fällt es auf", () => {
    const base = evening([game("g1", 60)]);
    const mine = game("m", 300, { won: false, laufende: 3 });
    const theirs: GameEntry = { ...mine, id: "t", recordedAt: after(312) };

    const merged = EveningMerge.merge3(base, append(base, mine), append(base, theirs));

    expect(ids(merged)).toEqual(["g1", "m", "t"]);
    expect(DuplicateCheck.suspects(merged)).toEqual(new Set(["t"]));
  });
});
