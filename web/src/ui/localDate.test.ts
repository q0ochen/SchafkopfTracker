/* Tage im Kalender und geplante Abende. Die Vitest-Konfiguration rechnet in Europe/Berlin (vite.config.ts, `TZ`). */
import { describe, expect, it } from "vitest";
import { testEvening } from "./testing";
import { isAfterDay, isPlanned, localDateOf, normalizedLocalDate, startOnDay } from "./localDate";

const TODAY = "2026-11-21";

describe("Ortsdatum", () => {
  it("rechnet in der Zeitzone Europe/Berlin (sonst prüften die Fälle zur Sommerzeit nichts)", () => {
    expect(new Date(Date.UTC(2026, 6, 1)).getTimezoneOffset()).toBe(-120);
    expect(new Date(Date.UTC(2026, 11, 1)).getTimezoneOffset()).toBe(-60);
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

  it("nimmt nur echte Daten aus einem Datumsfeld, in einheitlicher Schreibweise", () => {
    expect(normalizedLocalDate("2026-11-21")).toBe("2026-11-21");
    expect(normalizedLocalDate("2024-02-29")).toBe("2024-02-29");
    expect(normalizedLocalDate("0001-01-01")).toBe("0001-01-01");
    expect(normalizedLocalDate("10000-01-01")).toBe("10000-01-01");
    for (const text of ["", "2026-02-29", "2026-02-30", "2026-13-01", "2026-00-10", "2026-11-00", "0000-01-01", "14.11.2026", "2026-1-14"]) {
      expect(normalizedLocalDate(text), text).toBeNull();
    }
  });

  it("vergleicht Tage, auch über vierstellige Jahre hinaus", () => {
    expect(isAfterDay("2026-11-22", TODAY)).toBe(true);
    expect(isAfterDay("2027-01-01", TODAY)).toBe(true);
    expect(isAfterDay("10000-01-01", "9999-12-31")).toBe(true);
    expect(isAfterDay(TODAY, TODAY)).toBe(false);
    expect(isAfterDay("2026-11-20", TODAY)).toBe(false);
    expect(isAfterDay("", TODAY)).toBe(false);
    expect(isAfterDay("2026-12-12", "")).toBe(false);
  });
});

describe("Beginn an einem anderen Tag", () => {
  it("nimmt dieselbe Ortszeit, Bruchteile eingeschlossen, auch an einem Tag nach heute", () => {
    expect(startOnDay("2026-11-20T18:30:00.123456789Z", "2026-11-13")).toBe("2026-11-13T18:30:00.123456789Z");
    expect(startOnDay("2026-11-21T18:05:00.250Z", "2026-12-12")).toBe("2026-12-12T18:05:00.250Z");
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
  });
});

describe("geplante Abende (docs/ARCHITEKTUR.md, 4.2)", () => {
  it("ein laufender Abend an einem späteren Tag ist geplant", () => {
    expect(isPlanned(testEvening([], { startedAt: "2026-12-12T18:00:00Z" }), TODAY)).toBe(true);
    // Morgen um 0:30 Uhr Ortszeit (in UTC noch heute).
    expect(isPlanned(testEvening([], { startedAt: "2026-11-21T23:30:00Z" }), TODAY)).toBe(true);
    expect(isPlanned(testEvening([], { startedAt: "+10000-01-01T00:00:00Z" }), TODAY)).toBe(true);
  });

  it("heute, früher oder beendet ist nicht geplant", () => {
    // Heute um 23:30 Uhr Ortszeit, also auch nach jetzt: Am Tag selbst läuft der Abend.
    expect(isPlanned(testEvening([], { startedAt: "2026-11-21T22:30:00Z" }), TODAY)).toBe(false);
    // Heute um 0:30 Uhr Ortszeit (in UTC noch gestern).
    expect(isPlanned(testEvening([], { startedAt: "2026-11-20T23:30:00Z" }), TODAY)).toBe(false);
    expect(isPlanned(testEvening([], { startedAt: "2026-11-14T18:00:00Z" }), TODAY)).toBe(false);
    expect(isPlanned(testEvening([], { startedAt: "2026-12-12T18:00:00Z", finishedAt: "2026-11-21T10:00:00Z" }), TODAY)).toBe(false);
  });

  it("ordnet Zeitpunkte außerhalb des Bereichs von Date ein (nur bei beschädigten Daten)", () => {
    expect(isPlanned(testEvening([], { startedAt: "+1000000000-12-31T23:59:59.999999999Z" }), TODAY)).toBe(true);
    expect(isPlanned(testEvening([], { startedAt: "-1000000000-01-01T00:00:00Z" }), TODAY)).toBe(false);
    expect(isPlanned(testEvening([], { startedAt: "-0001-12-30T12:00:00Z" }), TODAY)).toBe(false);
  });

  it("ohne gültiges Heute ist nichts geplant", () => {
    expect(isPlanned(testEvening([], { startedAt: "2026-12-12T18:00:00Z" }), "")).toBe(false);
  });
});
