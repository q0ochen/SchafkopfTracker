/* Logik von „Abend bearbeiten“. Die Vitest-Konfiguration rechnet in Europe/Berlin (vite.config.ts, `TZ`). */
import { describe, expect, it } from "vitest";
import { EveningEdit } from "../core/evening/EveningEdit";
import type { Evening } from "../core/model";
import { RuleSets } from "../core/rules/RuleSet";
import { changed, draftOf, editedDetails, moveSeat, setDate, setFirstDealer, setLocation, startEditing } from "./editEvening";

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

});

describe("Abend bearbeiten: Eingaben", () => {
  const { original, draft } = startEditing(evening("2026-11-20T18:30:00Z"));

  it("nimmt jeden Tag, auch einen nach heute (dann ist der Abend geplant)", () => {
    expect(setDate(draft, "2026-11-14").date).toBe("2026-11-14");
    expect(setDate(draft, "2026-11-21").date).toBe("2026-11-21");
    expect(setDate(draft, "2026-11-22").date).toBe("2026-11-22");
    expect(setDate(draft, "2027-01-01").date).toBe("2027-01-01");
    expect(setDate(draft, "0001-01-01").date).toBe("0001-01-01");
    expect(setDate(draft, "9999-12-31").date).toBe("9999-12-31");
  });

  it("übergeht, was kein Datum ist, auch ein geleertes Feld", () => {
    for (const text of ["", "2026-02-29", "2026-02-30", "2026-13-01", "2026-00-10", "2026-11-00", "0000-01-01", "14.11.2026", "2026-1-14"]) {
      expect(setDate(draft, text), text).toBe(draft);
    }
    expect(setDate(draft, "2024-02-29").date).toBe("2024-02-29");
    // Derselbe Tag noch einmal ändert nichts.
    expect(setDate(draft, "2026-11-20")).toBe(draft);
  });

  it("kürzt den Ort auf 100 Zeichen", () => {
    expect(setLocation(draft, `  ${"x".repeat(120)}`).location).toHaveLength(100);
    expect(setLocation(draft, "Klinikum Bamberg, Buger Straße 80, 96049 Bamberg").location).toBe("Klinikum Bamberg, Buger Straße 80, 96049 Bamberg");
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
    expect(changed(original, setDate(draft, "2026-11-14"))).toBe(true);
    expect(changed(original, setLocation(draft, "Daheim"))).toBe(true);
    expect(changed(original, moveSeat(draft, "spieler-2", -1))).toBe(true);
    expect(changed(original, setFirstDealer(draft, "spieler-2"))).toBe(true);
    // Zurückgestellt ist es wieder unverändert.
    expect(changed(original, setDate(setDate(draft, "2026-11-14"), "2026-11-20"))).toBe(false);
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
    const there = setDate(exact.draft, "2026-11-14");
    const back = setDate(there, "2026-11-20");
    expect(editedDetails(exact.original, back).startedAt).toBe("2026-11-20T18:30:00.123456789Z");
    expect(editedDetails(exact.original, setLocation(exact.draft, "Daheim")).startedAt).toBe("2026-11-20T18:30:00.123456789Z");
  });

  it("nimmt am neuen Tag dieselbe Ortszeit, Bruchteile eingeschlossen", () => {
    const exact = startEditing(evening("2026-11-20T18:30:00.123456789Z"));
    expect(editedDetails(exact.original, setDate(exact.draft, "2026-11-13")).startedAt).toBe("2026-11-13T18:30:00.123456789Z");
    // Nach Mitternacht Ortszeit begonnen (00:30 am 21.11.): 00:30 am gewählten Tag.
    const late = startEditing(evening("2026-11-20T23:30:00Z"));
    expect(late.draft.date).toBe("2026-11-21");
    expect(editedDetails(late.original, setDate(late.draft, "2026-11-14")).startedAt).toBe("2026-11-13T23:30:00Z");
  });

  it("nimmt auch einen Tag nach heute mit derselben Ortszeit", () => {
    const opened = startEditing(evening("2026-11-20T18:30:00Z"));
    const planned = setDate(opened.draft, "2026-12-12");
    expect(changed(opened.original, planned)).toBe(true);
    expect(editedDetails(opened.original, planned).startedAt).toBe("2026-12-12T18:30:00Z");
  });

  it("beginnt bei unlesbarem Beginn um Mitternacht", () => {
    const broken = startEditing(evening("+1000000000-12-31T23:59:59.999999999Z"));
    expect(broken.draft.date).toBe("");
    expect(changed(broken.original, broken.draft)).toBe(false);
    expect(editedDetails(broken.original, setDate(broken.draft, "2026-11-14")).startedAt).toBe("2026-11-13T23:00:00Z");
  });

  it("speichert mit EveningEdit.apply nur, was hier geändert wurde", () => {
    const opened = evening("2026-11-20T18:30:00Z");
    const { original, draft: start } = startEditing(opened);
    let draft = setDate(start, "2026-11-14");
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
