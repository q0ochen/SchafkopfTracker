/* Logik von „Abend bearbeiten“. Die Vitest-Konfiguration rechnet in Europe/Berlin (vite.config.ts, `TZ`). */
import { describe, expect, it } from "vitest";
import { EveningEdit } from "../core/evening/EveningEdit";
import type { Evening } from "../core/model";
import { RuleSets } from "../core/rules/RuleSet";
import {
  changed,
  draftOf,
  editedDetails,
  localDateOf,
  moveSeat,
  setDate,
  setFirstDealer,
  setLocation,
  startEditing,
  startOnDay,
} from "./editEvening";

const TODAY = "2026-11-21";

function evening(startedAt: string, location = "Wirtshaus"): Evening {
  return {
    id: "abend",
    startedAt,
    finishedAt: null,
    location,
    seating: ["spieler-1", "spieler-2", "spieler-3", "spieler-4"],
    firstDealer: "spieler-1",
    rules: RuleSets.standard(),
    entries: [{ kind: "thrown_in", id: "wurf-1", recordedAt: "2026-11-20T18:40:00Z" }],
  };
}

describe("Abend bearbeiten: Ausgangsstand", () => {
  it("rechnet in der Zeitzone Europe/Berlin (sonst prüften die Fälle zur Sommerzeit nichts)", () => {
    expect(new Date(Date.UTC(2026, 6, 1)).getTimezoneOffset()).toBe(-120);
    expect(new Date(Date.UTC(2026, 11, 1)).getTimezoneOffset()).toBe(-60);
  });

  it("liest den Abend einmal: original und ein Entwurf mit dem Ortsdatum", () => {
    // 23:30 UTC ist in Berlin schon der nächste Tag.
    const { original, draft } = startEditing(evening("2026-11-20T23:30:00Z"));
    expect(original).toEqual({
      startedAt: "2026-11-20T23:30:00Z",
      location: "Wirtshaus",
      seating: ["spieler-1", "spieler-2", "spieler-3", "spieler-4"],
      firstDealer: "spieler-1",
    });
    expect(draft).toEqual({
      date: "2026-11-21",
      location: "Wirtshaus",
      seating: ["spieler-1", "spieler-2", "spieler-3", "spieler-4"],
      firstDealer: "spieler-1",
    });
    expect(changed(original, draft)).toBe(false);
    expect(editedDetails(original, draft)).toEqual(original);
  });

  it("zeigt das Ortsdatum; Tage, die ein Datumsfeld nicht kennt, bleiben leer", () => {
    expect(localDateOf("2026-11-20T22:59:59.999Z")).toBe("2026-11-20");
    expect(localDateOf("2026-11-20T23:00:00Z")).toBe("2026-11-21");
    expect(localDateOf("2026-07-01T21:59:00Z")).toBe("2026-07-01");
    expect(localDateOf("2026-07-01T22:00:00Z")).toBe("2026-07-02");
    expect(localDateOf("0099-06-01T12:00:00Z")).toBe("0099-06-01");
    expect(localDateOf("-0001-12-30T12:00:00Z")).toBe("");
    expect(localDateOf("+1000000000-12-31T23:59:59.999999999Z")).toBe("");
  });
});

describe("Abend bearbeiten: Eingaben", () => {
  const { original, draft } = startEditing(evening("2026-11-20T18:30:00Z"));

  it("nimmt einen früheren Tag oder heute, aber keinen Tag nach heute", () => {
    expect(setDate(draft, "2026-11-14", TODAY).date).toBe("2026-11-14");
    expect(setDate(draft, TODAY, TODAY).date).toBe(TODAY);
    expect(setDate(draft, "2026-11-22", TODAY)).toBe(draft);
    expect(setDate(draft, "2027-01-01", TODAY)).toBe(draft);
    expect(setDate(draft, "0001-01-01", TODAY).date).toBe("0001-01-01");
  });

  it("übergeht, was kein Datum ist, auch ein geleertes Feld", () => {
    for (const text of ["", "2026-02-29", "2026-02-30", "2026-13-01", "2026-00-10", "2026-11-00", "0000-01-01", "14.11.2026", "2026-1-14"]) {
      expect(setDate(draft, text, TODAY), text).toBe(draft);
    }
    expect(setDate(draft, "2024-02-29", TODAY).date).toBe("2024-02-29");
    // Derselbe Tag noch einmal ändert nichts.
    expect(setDate(draft, "2026-11-20", TODAY)).toBe(draft);
  });

  it("kürzt den Ort auf 40 Zeichen", () => {
    expect(setLocation(draft, `  ${"x".repeat(50)}`).location).toHaveLength(40);
    expect(setLocation(draft, "Beim Huber").location).toBe("Beim Huber");
  });

  it("stellt die Sitzordnung um wie bei „Neuer Abend“ und hält die Grenzen ein", () => {
    let current = moveSeat(draft, "spieler-2", -1);
    expect(current.seating).toEqual(["spieler-2", "spieler-1", "spieler-3", "spieler-4"]);
    expect(moveSeat(current, "spieler-2", -1)).toBe(current);
    expect(moveSeat(current, "spieler-4", 1)).toBe(current);
    expect(moveSeat(current, "gast", 1)).toBe(current);
    current = moveSeat(current, "spieler-1", 1);
    expect(current.seating).toEqual(["spieler-2", "spieler-3", "spieler-1", "spieler-4"]);
    // Die Spieler selbst bleiben dieselben.
    expect([...current.seating].sort()).toEqual([...original.seating].sort());
  });

  it("wählt als ersten Geber nur jemanden am Tisch", () => {
    expect(setFirstDealer(draft, "spieler-3").firstDealer).toBe("spieler-3");
    expect(setFirstDealer(draft, "gast")).toBe(draft);
  });
});

describe("Abend bearbeiten: geändert und Speichern", () => {
  const { original, draft } = startEditing(evening("2026-11-20T18:30:00Z"));

  it("meldet jede Art von Änderung", () => {
    expect(changed(original, draft)).toBe(false);
    expect(changed(original, setDate(draft, "2026-11-14", TODAY))).toBe(true);
    expect(changed(original, setLocation(draft, "Daheim"))).toBe(true);
    expect(changed(original, moveSeat(draft, "spieler-2", -1))).toBe(true);
    expect(changed(original, setFirstDealer(draft, "spieler-2"))).toBe(true);
    // Zurückgestellt ist es wieder unverändert.
    expect(changed(original, setDate(setDate(draft, "2026-11-14", TODAY), "2026-11-20", TODAY))).toBe(false);
    expect(changed(original, moveSeat(moveSeat(draft, "spieler-2", -1), "spieler-2", 1))).toBe(false);
  });

  it("zählt den Ort erst als geändert, wenn er gesäubert anders ist, und gibt sonst den ursprünglichen weiter", () => {
    const spaced = startEditing(evening("2026-11-20T18:30:00Z", "Ort "));
    // Unverändert: nicht geändert, und der ursprüngliche Ort geht so, wie er ist, hinein.
    expect(changed(spaced.original, spaced.draft)).toBe(false);
    expect(editedDetails(spaced.original, spaced.draft).location).toBe("Ort ");
    // Nur getrimmt oder mit Leerraum ergänzt: ebenfalls nicht geändert.
    for (const text of ["Ort", "  Ort", "Ort\t"]) {
      const trimmed = setLocation(spaced.draft, text);
      expect(changed(spaced.original, trimmed), text).toBe(false);
      expect(editedDetails(spaced.original, trimmed).location, text).toBe("Ort ");
    }
    // Wirklich geändert: der Entwurf (gesäubert wird beim Übernehmen).
    const other = setLocation(spaced.draft, " Ort 2 ");
    expect(changed(spaced.original, other)).toBe(true);
    expect(editedDetails(spaced.original, other).location).toBe(" Ort 2 ");
    expect(EveningEdit.apply(evening("2026-11-20T18:30:00Z", "Ort "), spaced.original, editedDetails(spaced.original, other)).location).toBe("Ort 2");
    // Kein Ort und nur Leerzeichen: nicht geändert.
    const empty = startEditing(evening("2026-11-20T18:30:00Z", ""));
    expect(changed(empty.original, setLocation(empty.draft, "   "))).toBe(false);
  });

  it("behält den Beginn genau, wenn der Tag gleich bleibt, auch mit Bruchteilen", () => {
    const exact = startEditing(evening("2026-11-20T18:30:00.123456789Z"));
    const there = setDate(exact.draft, "2026-11-14", TODAY);
    const back = setDate(there, "2026-11-20", TODAY);
    expect(editedDetails(exact.original, back).startedAt).toBe("2026-11-20T18:30:00.123456789Z");
    expect(editedDetails(exact.original, setLocation(exact.draft, "Daheim")).startedAt).toBe("2026-11-20T18:30:00.123456789Z");
  });

  it("nimmt am neuen Tag dieselbe Ortszeit, Bruchteile eingeschlossen", () => {
    const exact = startEditing(evening("2026-11-20T18:30:00.123456789Z"));
    expect(editedDetails(exact.original, setDate(exact.draft, "2026-11-13", TODAY)).startedAt).toBe("2026-11-13T18:30:00.123456789Z");
    // Nach Mitternacht Ortszeit begonnen (00:30 am 21.11.): 00:30 am gewählten Tag.
    const late = startEditing(evening("2026-11-20T23:30:00Z"));
    expect(late.draft.date).toBe("2026-11-21");
    expect(editedDetails(late.original, setDate(late.draft, "2026-11-14", TODAY)).startedAt).toBe("2026-11-13T23:30:00Z");
  });

  it("hält die Ortszeit über die Umstellung der Sommerzeit hinweg", () => {
    // 19:00 Winterzeit (UTC+1) wird 19:00 Sommerzeit (UTC+2) und umgekehrt.
    expect(startOnDay("2026-03-28T18:00:00Z", "2026-03-29")).toBe("2026-03-29T17:00:00Z");
    expect(startOnDay("2026-07-04T17:00:00Z", "2026-11-14")).toBe("2026-11-14T18:00:00Z");
    expect(startOnDay("2026-10-24T17:00:00Z", "2026-10-25")).toBe("2026-10-25T18:00:00Z");
    // 02:30 gibt es am 29.03.2026 nicht: Es wird 03:30 Sommerzeit.
    expect(startOnDay("2026-03-28T01:30:00Z", "2026-03-29")).toBe("2026-03-29T01:30:00Z");
    expect(new Date("2026-03-29T01:30:00Z").getHours()).toBe(3);
    // 02:30 gibt es am 25.10.2026 zweimal: Es gilt das frühere (noch Sommerzeit).
    expect(startOnDay("2026-10-24T00:30:00Z", "2026-10-25")).toBe("2026-10-25T00:30:00Z");
    expect(new Date("2026-10-25T00:30:00Z").getHours()).toBe(2);
  });

  it("übergeht kein Datum und beginnt bei unlesbarem Beginn um Mitternacht", () => {
    expect(startOnDay("2026-11-20T18:30:00Z", "")).toBeNull();
    expect(startOnDay("2026-11-20T18:30:00Z", "2026-02-30")).toBeNull();
    expect(startOnDay("+1000000000-12-31T23:59:59.999999999Z", "2026-11-14")).toBe("2026-11-13T23:00:00Z");
    const broken = startEditing(evening("+1000000000-12-31T23:59:59.999999999Z"));
    expect(broken.draft.date).toBe("");
    expect(changed(broken.original, broken.draft)).toBe(false);
    expect(editedDetails(broken.original, setDate(broken.draft, "2026-11-14", TODAY)).startedAt).toBe("2026-11-13T23:00:00Z");
  });

  it("speichert mit EveningEdit.apply nur, was hier geändert wurde", () => {
    const opened = evening("2026-11-20T18:30:00Z");
    const { original, draft: start } = startEditing(opened);
    let draft = setDate(start, "2026-11-14", TODAY);
    draft = moveSeat(draft, "spieler-3", -1);
    draft = setFirstDealer(draft, "spieler-4");
    // Inzwischen hat ein anderes Handy den Ort geändert und ein Spiel eingetragen.
    const meanwhile: Evening = {
      ...opened,
      location: "Beim Huber",
      entries: [...opened.entries, { kind: "thrown_in", id: "wurf-2", recordedAt: "2026-11-20T19:00:00Z" }],
    };
    const saved = EveningEdit.apply(meanwhile, original, editedDetails(original, draft));
    expect(saved).toEqual({
      ...meanwhile,
      startedAt: "2026-11-14T18:30:00Z",
      seating: ["spieler-1", "spieler-3", "spieler-2", "spieler-4"],
      firstDealer: "spieler-4",
    });
    // Ohne Änderung bleibt der Abend, wie er ist.
    expect(EveningEdit.apply(meanwhile, original, editedDetails(original, draftOf(original)))).toBe(meanwhile);
  });
});
