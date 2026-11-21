/* „Abend bearbeiten“ im Rechenkern, angelehnt an EveningEditTest.kt des Kotlin-Kerns. */
import { describe, expect, it } from "vitest";
import type { Evening, GameEntry } from "../model";
import { RuleSets } from "../rules/RuleSet";
import { EveningEdit, eveningDetailsOf, MAX_LOCATION_LENGTH, type EveningDetails } from "./EveningEdit";

const start = "2026-11-20T18:30:00Z";

const game: GameEntry = {
  kind: "game",
  id: "spiel-1",
  recordedAt: "2026-11-20T18:40:00Z",
  type: "RUFSPIEL",
  suit: null,
  declarer: "a",
  partner: "b",
  declarerWon: true,
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
};

const evening: Evening = {
  id: "abend",
  startedAt: start,
  finishedAt: null,
  location: "Wirtshaus",
  seating: ["a", "b", "c", "d"],
  firstDealer: "a",
  rules: RuleSets.standard(),
  entries: [game],
};

const original: EveningDetails = eveningDetailsOf(evening);

function edit(current: Evening, edited: Partial<EveningDetails>): Evening {
  return EveningEdit.apply(current, original, { ...original, ...edited });
}

describe("EveningEdit.apply", () => {
  it("ohne Änderung bleibt der Abend, wie er ist (dasselbe Objekt)", () => {
    expect(EveningEdit.apply(evening, original, original)).toBe(evening);
    // Zeitpunkte zählen als Zeit, nicht als Text: „…:00.000Z“ ist derselbe Beginn wie „…:00Z“.
    expect(edit(evening, { startedAt: "2026-11-20T18:30:00.000Z", seating: ["a", "b", "c", "d"] })).toBe(evening);
  });

  it("das neue Datum gilt, die Einträge behalten ihre Zeiten", () => {
    const earlier = "2026-11-13T18:30:00Z";
    const result = edit(evening, { startedAt: earlier });
    expect(result.startedAt).toBe(earlier);
    expect(result.finishedAt).toBeNull();
    expect(result.entries).toEqual([game]);
    expect(result).toEqual({ ...evening, startedAt: earlier });
  });

  it("ein beendeter Abend behält seine Dauer, auf die Nanosekunde", () => {
    const finished = { ...evening, finishedAt: "2026-11-20T23:45:10.5Z" };
    const result = edit(finished, { startedAt: "2026-11-19T18:30:00.000000001Z" });
    expect(result.startedAt).toBe("2026-11-19T18:30:00.000000001Z");
    expect(result.finishedAt).toBe("2026-11-19T23:45:10.500000001Z");
  });

  it("schreibt neue Zeitpunkte wie Instant.toString()", () => {
    const finished = { ...evening, finishedAt: "2026-11-20T23:00:00Z" };
    const result = edit(finished, { startedAt: "2026-11-21T18:30:00.120000Z" });
    expect(result.startedAt).toBe("2026-11-21T18:30:00.120Z");
    expect(result.finishedAt).toBe("2026-11-21T23:00:00.120Z");
  });

  it("das Ende verschiebt sich vom inzwischen geänderten Beginn aus", () => {
    // Ein anderes Handy hat den Beginn schon um eine Stunde verlegt; hier wird das Datum gewählt.
    const meanwhile = { ...evening, startedAt: "2026-11-20T19:30:00Z", finishedAt: "2026-11-20T20:30:00Z" };
    const result = edit(meanwhile, { startedAt: "2026-11-19T18:30:00Z" });
    expect(result.startedAt).toBe("2026-11-19T18:30:00Z");
    expect(result.finishedAt).toBe("2026-11-19T19:30:00Z");
  });

  it("läge das Ende außerhalb des Zeitbereichs, bleibt es", () => {
    const atEnd = { ...evening, finishedAt: "+1000000000-12-31T23:59:59.999999999Z" };
    const result = edit(atEnd, { startedAt: "2026-11-20T18:31:00Z" });
    expect(result.startedAt).toBe("2026-11-20T18:31:00Z");
    expect(result.finishedAt).toBe("+1000000000-12-31T23:59:59.999999999Z");
    // Am Anfang rechnet Java in zwei Schritten (erst eine ganze Sekunde zurück): Auch hier bleibt das Ende.
    const atStart = { ...evening, finishedAt: "-1000000000-01-01T00:00:00.600Z" };
    expect(edit(atStart, { startedAt: "2026-11-20T18:29:59.500Z" }).finishedAt).toBe("-1000000000-01-01T00:00:00.600Z");
  });

  it("das Ende rückt über das Jahr 9999 hinaus und vor das Jahr 0", () => {
    const late = { ...evening, startedAt: "2026-01-01T00:00:00Z", finishedAt: "9999-12-31T23:00:00Z" };
    expect(EveningEdit.apply(late, eveningDetailsOf(late), { ...eveningDetailsOf(late), startedAt: "2026-01-03T00:00:00Z" }).finishedAt).toBe(
      "+10000-01-02T23:00:00Z",
    );
    const early = { ...evening, startedAt: "0001-01-01T10:00:00Z", finishedAt: "0001-01-01T12:00:00.250Z" };
    expect(EveningEdit.apply(early, eveningDetailsOf(early), { ...eveningDetailsOf(early), startedAt: "-0001-12-30T10:00:00Z" }).finishedAt).toBe(
      "-0001-12-30T12:00:00.250Z",
    );
  });

  it("der Ort wird gesäubert und gekürzt", () => {
    expect(edit(evening, { location: "  Beim Huber  " }).location).toBe("Beim Huber");
    expect(edit(evening, { location: `${"x".repeat(39)}  yyy` }).location).toBe("x".repeat(39));
    expect(edit(evening, { location: "   " }).location).toBe("");
  });

  it("nur Geändertes wird übernommen, anderes bleibt wie inzwischen", () => {
    // Ein anderes Handy hat inzwischen den Ort und den ersten Geber geändert; hier nur die Sitzordnung.
    const meanwhile = { ...evening, location: "Beim Huber", firstDealer: "c" };
    const result = edit(meanwhile, { seating: ["b", "a", "c", "d"] });
    expect(result.location).toBe("Beim Huber");
    expect(result.firstDealer).toBe("c");
    expect(result.seating).toEqual(["b", "a", "c", "d"]);
    expect(result.startedAt).toBe(start);
  });

  it("eine neue Sitzordnung muss dieselben Spieler haben, jeden einmal", () => {
    for (const wrong of [["a", "b", "c", "e"], ["a", "b", "c"], ["a", "a", "b", "c"], ["a", "b", "c", "d", "d"], []]) {
      expect(edit(evening, { seating: wrong }).seating, wrong.join(",")).toEqual(evening.seating);
    }
    expect(edit(evening, { seating: ["d", "c", "b", "a"] }).seating).toEqual(["d", "c", "b", "a"]);
    // Sitzt jemand doppelt am Tisch (beschädigte Daten), lässt sich nichts umstellen.
    const broken = { ...evening, seating: ["a", "a", "b", "c"] };
    expect(EveningEdit.apply(broken, eveningDetailsOf(broken), { ...eveningDetailsOf(broken), seating: ["a", "b", "c", "d"] }).seating).toEqual(
      ["a", "a", "b", "c"],
    );
  });

  it("die neue Sitzordnung gilt auch, wenn ein anderes Handy sie schon umgestellt hat", () => {
    const meanwhile = { ...evening, seating: ["a", "c", "b", "d"] };
    expect(edit(meanwhile, { seating: ["d", "a", "b", "c"] }).seating).toEqual(["d", "a", "b", "c"]);
  });

  it("der erste Geber muss am Tisch sitzen", () => {
    expect(edit(evening, { firstDealer: "c" }).firstDealer).toBe("c");
    expect(edit(evening, { firstDealer: "e" }).firstDealer).toBe("a");
  });

  it("alles auf einmal", () => {
    const edited: EveningDetails = { startedAt: "2026-11-14T19:00:00Z", location: "Daheim", seating: ["c", "d", "a", "b"], firstDealer: "d" };
    const result = EveningEdit.apply(evening, original, edited);
    expect(eveningDetailsOf(result)).toEqual(edited);
    expect(result.entries).toBe(evening.entries);
    expect(result.rules).toBe(evening.rules);
    expect(result.id).toBe(evening.id);
  });

  it("verändert die Eingaben nicht", () => {
    const seating = ["d", "c", "b", "a"];
    const edited: EveningDetails = { ...original, seating, firstDealer: "b" };
    const result = EveningEdit.apply(evening, original, edited);
    seating.reverse();
    expect(result.seating).toEqual(["d", "c", "b", "a"]);
    expect(evening.seating).toEqual(["a", "b", "c", "d"]);
  });
});

describe("EveningEdit.cleanLocation und eveningDetailsOf", () => {
  it("cleanLocation entfernt Leerraum wie Kotlin und kürzt auf 40 Zeichen", () => {
    expect(EveningEdit.cleanLocation("\tOrt\n")).toBe("Ort");
    expect(EveningEdit.cleanLocation("y".repeat(60))).toHaveLength(MAX_LOCATION_LENGTH);
    expect(MAX_LOCATION_LENGTH).toBe(40);
    // U+FEFF zählt in Kotlin nicht als Leerraum, U+001C schon (in JavaScript umgekehrt).
    expect(EveningEdit.cleanLocation("﻿Ort ")).toBe("﻿Ort");
    expect(EveningEdit.cleanLocation("\u001COrt ")).toBe("Ort");
    // Erst trimmen, dann kürzen, dann noch einmal trimmen.
    expect(EveningEdit.cleanLocation(`   ${"z".repeat(38)} abc`)).toBe(`${"z".repeat(38)} a`);
    expect(EveningEdit.cleanLocation(`${"z".repeat(39)} abc`)).toBe("z".repeat(39));
  });

  it("eveningDetailsOf liest die vier Angaben", () => {
    expect(eveningDetailsOf(evening)).toEqual({ startedAt: start, location: "Wirtshaus", seating: ["a", "b", "c", "d"], firstDealer: "a" });
  });
});
